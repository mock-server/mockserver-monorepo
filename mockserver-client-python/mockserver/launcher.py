"""On-demand MockServer binary launcher.

Downloads the self-contained, JVM-less MockServer bundle (a jlink runtime + the
server + a ``mockserver`` launcher script) for the current platform from the
GitHub Release, verifies its SHA-256, caches it per-user, and launches it.  No
Java installation and no Docker required.

This mirrors the reference implementation in ``mockserver-node/downloadBinary.js``
and adds versioned cache pruning.

Environment overrides
---------------------
MOCKSERVER_BINARY_BASE_URL
    Mirror host for the release assets (corporate / air-gapped).
MOCKSERVER_BINARY_CACHE
    Cache directory (default: per-OS user cache).
MOCKSERVER_SKIP_BINARY_DOWNLOAD
    Fail instead of downloading (air-gapped CI with a pre-seeded cache).
SSL_CERT_FILE
    Extra CA bundle path, respected by the :mod:`ssl` module on some
    platforms.  For robust corporate CA injection with :mod:`urllib`, pass a
    custom :class:`ssl.SSLContext` to :func:`urllib.request.urlopen`, or use
    ``MOCKSERVER_BINARY_BASE_URL`` to point at an internal mirror
    (recommended for air-gapped / TLS-inspecting environments).
    ``REQUESTS_CA_BUNDLE`` and ``CURL_CA_BUNDLE`` have no effect on urllib.
HTTPS_PROXY / HTTP_PROXY / https_proxy / http_proxy
    Used by urllib for proxy support.
"""

from __future__ import annotations

import hashlib
import logging
import os
import platform
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

logger = logging.getLogger(__name__)

# The version shipped with this client.  ``ensure_binary()`` defaults to this
# when no explicit version is passed.
_CLIENT_VERSION = "8.0.0"

_REPO = "mock-server/mockserver-monorepo"

_SNAPSHOT_CDN = "https://downloads.mock-server.com"

# Maximum number of previous version directories to keep (in addition to the
# current one) during cache pruning.
_MAX_PREVIOUS_VERSIONS = 1

# Strict version pattern: major.minor.patch with optional pre-release suffix.
# Rejects path separators and '..' to block path-traversal attacks.
_VERSION_RE = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.]+)?$")

# HTTP timeouts (seconds): connect and read.
_HTTP_CONNECT_TIMEOUT = 30
_HTTP_READ_TIMEOUT = 300


def _no_bundle_message(version: str) -> str:
    """Build a clear, actionable error for a missing release bundle.

    Explains that no downloadable bundle exists for *version* and lists the
    concrete alternatives.  The wording is kept consistent across all client
    languages.
    """
    return (
        f"no MockServer release bundle is published for version {version} "
        f"(no downloadable asset at the GitHub release tag 'mockserver-{version}'). "
        "Use a MockServer version that ships self-contained bundles, "
        f"or run MockServer via Docker (docker run mockserver/mockserver:mockserver-{version}), "
        f"or use the Maven Central jar (org.mock-server:mockserver-netty:{version})."
    )


def _validate_version(version: str) -> None:
    """Raise :class:`ValueError` if *version* is not a valid semver-ish string.

    Guards against path-traversal via ``..`` or path-separator injection.
    """
    if not version or "/" in version or "\\" in version or not _VERSION_RE.match(version):
        raise ValueError(f"invalid version string: {version!r}")


def _parse_semver_tuple(version_str: str) -> tuple[tuple[int, ...], int, str]:
    """Parse a version string into a sort key for semver-aware comparison.

    Returns ``(core_tuple, is_release_rank, prerelease)`` where:

    * *core_tuple* — ``(major, minor, patch)`` as ints.
    * *is_release_rank* — ``1`` for a release (no pre-release suffix), ``0``
      for a pre-release.  This ensures a release sorts ABOVE its pre-release
      when the numeric cores are equal (e.g. ``7.0.0 > 7.0.0-SNAPSHOT``).
    * *prerelease* — the raw pre-release string (for tie-breaking among
      pre-releases with the same core), or ``""`` for releases.
    """
    base, _, prerelease = version_str.partition("-")
    parts: list[int] = []
    for seg in base.split("."):
        try:
            parts.append(int(seg))
        except ValueError:
            parts.append(0)
    is_release_rank = 0 if prerelease else 1
    return tuple(parts), is_release_rank, prerelease


# ---------------------------------------------------------------------------
# Platform resolution
# ---------------------------------------------------------------------------

def resolve_platform() -> tuple[str, str, str]:
    """Return ``(os_name, arch, ext)`` for the current platform.

    Raises :class:`RuntimeError` on unsupported platforms.
    """
    system = platform.system().lower()
    machine = platform.machine().lower()

    if system == "linux":
        os_name, ext = "linux", "tar.gz"
    elif system == "darwin":
        os_name, ext = "darwin", "tar.gz"
    elif system == "windows":
        os_name, ext = "windows", "zip"
    else:
        raise RuntimeError(f"unsupported platform: {system}")

    if machine in ("x86_64", "amd64"):
        arch = "x86_64"
    elif machine in ("aarch64", "arm64"):
        arch = "aarch64"
    else:
        raise RuntimeError(f"unsupported architecture: {machine}")

    return os_name, arch, ext


def bundle_base_name(version: str) -> tuple[str, str]:
    """Return ``(name, ext)`` for the bundle archive.

    *name* is the directory-stem (e.g. ``mockserver-7.0.0-darwin-aarch64``) and
    *ext* is the archive extension (``tar.gz`` or ``zip``).
    """
    os_name, arch, ext = resolve_platform()
    return f"mockserver-{version}-{os_name}-{arch}", ext


# ---------------------------------------------------------------------------
# Cache paths
# ---------------------------------------------------------------------------

def cache_dir() -> Path:
    """Return the base cache directory for MockServer binaries.

    Resolution order:

    1. ``MOCKSERVER_BINARY_CACHE`` environment variable.
    2. Windows: ``%LOCALAPPDATA%`` (fallback ``~/AppData/Local``).
    3. Other: ``$XDG_CACHE_HOME`` or ``~/.cache``.

    The returned path ends with ``mockserver/binaries``.
    """
    env = os.environ.get("MOCKSERVER_BINARY_CACHE")
    if env:
        return Path(env)

    if sys.platform == "win32":
        base = os.environ.get("LOCALAPPDATA") or str(
            Path.home() / "AppData" / "Local"
        )
    else:
        base = os.environ.get("XDG_CACHE_HOME") or str(Path.home() / ".cache")

    return Path(base) / "mockserver" / "binaries"


def _is_snapshot(version: str) -> bool:
    """Return True if *version* contains '-SNAPSHOT' (case-insensitive)."""
    return "-SNAPSHOT" in version.upper()


def asset_url(version: str, filename: str) -> str:
    """Return the download URL for a release asset.

    Uses ``MOCKSERVER_BINARY_BASE_URL`` if set; otherwise defaults to GitHub
    Releases for release versions and the downloads.mock-server.com CDN for
    SNAPSHOT versions.
    """
    base = os.environ.get("MOCKSERVER_BINARY_BASE_URL")
    if not base:
        if _is_snapshot(version):
            base = f"{_SNAPSHOT_CDN}/mockserver-{version}"
        else:
            base = f"https://github.com/{_REPO}/releases/download/mockserver-{version}"
    return base.rstrip("/") + "/" + filename


def launcher_path(version_dir: Path, name: str) -> Path:
    """Return the path to the launcher executable inside the extracted bundle."""
    if sys.platform == "win32":
        return version_dir / name / "bin" / "mockserver.bat"
    return version_dir / name / "bin" / "mockserver"


# ---------------------------------------------------------------------------
# Download helpers
# ---------------------------------------------------------------------------

def _download(url: str, dest: Path) -> None:
    """Download *url* to *dest* using :mod:`urllib`.

    Respects ``HTTPS_PROXY``/``HTTP_PROXY`` and CA-bundle env vars natively.
    The response body is streamed to disk in 64 KiB chunks (never buffered
    entirely in memory).
    """
    logger.info("Downloading %s", url)
    # urllib honours http_proxy/https_proxy automatically via ProxyHandler.
    #
    # NOTE: stdlib urllib does not support separate connect vs read timeouts —
    # urlopen's ``timeout`` is a single socket-level deadline that applies to
    # every individual socket operation (connect, each recv, etc.).  We use
    # _HTTP_READ_TIMEOUT (300 s) as the single per-operation deadline: it applies
    # *per recv call*, not to the whole transfer, so large bundle downloads on
    # slow/congested links still complete (each chunk just needs to arrive within
    # the window) while a genuinely dead socket still aborts.  A 30 s value would
    # risk aborting slow-but-alive transfers.  For truly distinct connect vs read
    # timeouts, a third-party library (e.g. requests/urllib3) would be needed.
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req, timeout=_HTTP_READ_TIMEOUT) as resp:
        with open(dest, "wb") as f:
            shutil.copyfileobj(resp, f, length=65536)


def _sha256(path: Path) -> str:
    """Return the hex SHA-256 digest of *path*."""
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


# ---------------------------------------------------------------------------
# Versioned cache pruning
# ---------------------------------------------------------------------------

def _prune_old_versions(base: Path, current_version: str) -> None:
    """Remove stale version directories from the cache.

    Keeps ``current_version`` unconditionally and up to
    ``_MAX_PREVIOUS_VERSIONS`` highest-versioned other version dirs (using
    semver-aware numeric comparison, NOT lexicographic or mtime order).
    Also removes leftover ``.part`` and ``.sha256`` temp files.

    This is safe against concurrent runs: if a directory disappears between
    listing and removal we simply ignore the error.
    """
    if not base.is_dir():
        return

    resolved_base = base.resolve()

    # Clean up any .part / .sha256 files in the base dir itself
    for entry in base.iterdir():
        if entry.is_file() and (entry.name.endswith(".part") or entry.name.endswith(".sha256")):
            try:
                entry.unlink()
            except OSError:
                pass

    # Gather version directories (exclude current)
    others: list[tuple[tuple[int, ...], Path]] = []
    for entry in base.iterdir():
        if not entry.is_dir():
            continue
        if entry.name == current_version:
            continue
        # Safety: only consider directories that resolve inside the cache base
        # (paranoia against symlink escapes).
        try:
            entry.resolve().relative_to(resolved_base)
        except ValueError:
            continue
        sv = _parse_semver_tuple(entry.name)
        others.append((sv, entry))

    if not others:
        return

    # Sort by semver tuple descending (highest version first) and keep up to
    # _MAX_PREVIOUS_VERSIONS.
    others.sort(key=lambda t: t[0], reverse=True)
    to_remove = others[_MAX_PREVIOUS_VERSIONS:]

    for _sv, d in to_remove:
        try:
            shutil.rmtree(d)
            logger.info("Pruned old cache version: %s", d.name)
        except OSError as exc:
            logger.debug("Could not prune %s: %s", d, exc)

    # Also clean .part / .sha256 files inside kept version dirs
    for _sv, d in others[:_MAX_PREVIOUS_VERSIONS]:
        try:
            for f in d.iterdir():
                if f.is_file() and (f.name.endswith(".part") or f.name.endswith(".sha256")):
                    try:
                        f.unlink()
                    except OSError:
                        pass
        except OSError:
            pass


# ---------------------------------------------------------------------------
# Core: ensure_binary
# ---------------------------------------------------------------------------

def ensure_binary(
    version: str | None = None,
    *,
    log: bool = True,
) -> Path:
    """Ensure the MockServer binary is present and return the launcher path.

    Downloads, verifies (SHA-256), extracts, and caches the platform bundle on
    first use.  Subsequent calls reuse the cached binary.

    After a successful install the function prunes old version directories
    from the cache (keeping at most ``_MAX_PREVIOUS_VERSIONS`` previous ones).

    Parameters
    ----------
    version:
        MockServer version to download.  Defaults to the client's own version.
    log:
        Whether to emit log messages (default ``True``).

    Returns
    -------
    Path
        The path to the ``mockserver`` launcher executable.

    Raises
    ------
    RuntimeError
        On download failure, checksum mismatch, or unsupported platform.
    """
    if version is None:
        version = _CLIENT_VERSION

    # H1: validate version string and guard against path traversal.
    _validate_version(version)

    name, ext = bundle_base_name(version)
    base = cache_dir()
    version_dir = base / version

    # Assert version_dir resolves inside the cache base (blocks path traversal
    # even if _validate_version were somehow bypassed).
    resolved_base = base.resolve()
    resolved_vdir = version_dir.resolve()
    if not str(resolved_vdir).startswith(str(resolved_base) + os.sep) and resolved_vdir != resolved_base:
        raise RuntimeError(
            f"version directory {version_dir} escapes cache base {base}"
        )

    launcher = launcher_path(version_dir, name)

    # ---- cached? reuse ----
    if launcher.exists() and launcher.stat().st_size > 0:
        if log:
            logger.info("Using cached binary: %s", launcher)
        return launcher

    # ---- skip-download guard ----
    if os.environ.get("MOCKSERVER_SKIP_BINARY_DOWNLOAD"):
        raise RuntimeError(
            f"MOCKSERVER_SKIP_BINARY_DOWNLOAD is set but no cached binary at {launcher}"
        )

    # ---- download + verify + extract ----
    version_dir.mkdir(parents=True, exist_ok=True)
    archive_name = f"{name}.{ext}"
    archive = version_dir / archive_name
    partial = archive.with_suffix(archive.suffix + ".part")
    sha_file = version_dir / f"{archive_name}.sha256"

    try:
        # Download to a temp file; rename only after checksum passes.
        try:
            _download(asset_url(version, archive_name), partial)
        except urllib.error.HTTPError as exc:
            # A 404 means the release tag exists but ships no bundle for this
            # version (or the tag does not exist). Surface actionable guidance
            # instead of an opaque HTTP error.
            if exc.code == 404:
                raise RuntimeError(_no_bundle_message(version)) from exc
            raise

        # Download and verify SHA-256 (fail-closed on missing/empty checksum).
        _download(asset_url(version, f"{archive_name}.sha256"), sha_file)
        sha_content = sha_file.read_text().strip()
        expected = sha_content.split()[0] if sha_content else ""
        if not expected:
            raise RuntimeError(
                f"checksum file for {name} is empty or unparseable"
            )
        actual = _sha256(partial)
        if expected != actual:
            raise RuntimeError(
                f"checksum mismatch for {name}: expected {expected}, got {actual}"
            )
        if log:
            logger.info("Checksum verified")

        # Rename .part -> archive (atomic on same filesystem)
        partial.rename(archive)

    except Exception:
        # H3: best-effort cleanup of BOTH .part and .sha256 temp files
        for _stale in (partial, sha_file):
            try:
                _stale.unlink(missing_ok=True)
            except OSError:
                pass
        raise

    # ---- extract ----
    if log:
        logger.info("Extracting %s", archive)

    if ext == "zip":
        import zipfile
        with zipfile.ZipFile(archive) as zf:
            # H3: guard against zip entries that escape version_dir
            for member in zf.namelist():
                member_path = (version_dir / member).resolve()
                if not str(member_path).startswith(str(resolved_vdir) + os.sep) and member_path != resolved_vdir:
                    raise RuntimeError(
                        f"zip member {member!r} would escape extract directory"
                    )
            zf.extractall(version_dir)
    else:
        # H3: use --no-same-owner to avoid permission issues and guard against
        # tar entries that escape version_dir (tar's default is safe with -C,
        # but we add an explicit check on the extracted launcher path below).
        result = subprocess.run(
            ["tar", "-xf", str(archive), "-C", str(version_dir)],
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            raise RuntimeError(
                f"extraction failed (tar exit {result.returncode}): {result.stderr}"
            )

    # Verify the launcher exists and its resolved path is inside version_dir
    # (guards against tar/zip path traversal producing a symlink escape).
    if not (launcher.exists() and launcher.stat().st_size > 0):
        raise RuntimeError(
            f"launcher missing or empty after extract: {launcher}"
        )
    if not str(launcher.resolve()).startswith(str(resolved_vdir) + os.sep):
        raise RuntimeError(
            f"launcher {launcher} resolves outside version directory (possible path traversal)"
        )

    if sys.platform != "win32":
        launcher.chmod(0o755)

    # ---- prune old versions ----
    try:
        _prune_old_versions(base, version)
    except Exception as exc:
        # Pruning is best-effort; never fail the install because of it.
        logger.debug("Cache pruning failed: %s", exc)

    return launcher


# ---------------------------------------------------------------------------
# Public API: start / stop
# ---------------------------------------------------------------------------

class MockServerProcess:
    """Handle to a running MockServer binary process.

    Returned by :func:`start`.  Call :meth:`stop` (or use as a context manager)
    to terminate the server.
    """

    def __init__(self, process: subprocess.Popen, port: int, launcher: Path) -> None:
        self._process = process
        self._port = port
        self._launcher = launcher

    @property
    def port(self) -> int:
        """The ``-serverPort`` the server was started on."""
        return self._port

    @property
    def pid(self) -> int | None:
        """The OS process id, or ``None`` if already terminated."""
        return self._process.pid

    @property
    def launcher(self) -> Path:
        """Path to the ``mockserver`` launcher executable."""
        return self._launcher

    @property
    def returncode(self) -> int | None:
        """The process exit code, or ``None`` if still running."""
        return self._process.poll()

    def stop(self, timeout: float = 10.0) -> int:
        """Terminate the server and return the exit code.

        Sends ``SIGTERM`` first; if the process does not exit within *timeout*
        seconds it is killed with ``SIGKILL``.
        """
        if self._process.poll() is not None:
            return self._process.returncode
        self._process.terminate()
        try:
            self._process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            self._process.kill()
            try:
                self._process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                pass  # best-effort; SIGKILL cannot be ignored
        return self._process.returncode

    def __enter__(self) -> MockServerProcess:
        return self

    def __exit__(self, exc_type, exc_val, exc_tb) -> None:
        self.stop()


def start(
    port: int,
    version: str | None = None,
    *,
    extra_args: list[str] | None = None,
    log: bool = True,
) -> MockServerProcess:
    """Download (if needed) and start the MockServer binary.

    Parameters
    ----------
    port:
        The port to pass as ``-serverPort``.
    version:
        MockServer version.  Defaults to the client's own version.
    extra_args:
        Additional command-line arguments to pass to the launcher.
    log:
        Whether to emit log messages.

    Returns
    -------
    MockServerProcess
        A handle to the running server.  Use :meth:`~MockServerProcess.stop`
        or a ``with`` statement to shut it down.
    """
    launcher = ensure_binary(version, log=log)
    args = [str(launcher), "-serverPort", str(port)]
    if extra_args:
        args.extend(extra_args)

    if log:
        logger.info("Starting MockServer on port %d: %s", port, " ".join(args))

    # H4/H5: On Windows, .bat launchers need shell=True; we use a list form to
    # let subprocess quote each arg safely.  On all platforms we inherit stdio
    # (stdout=None, stderr=None) to match the Node reference (stdio:'inherit')
    # and avoid pipe-buffer deadlock when MockServer produces output exceeding
    # the OS pipe buffer (~64 KiB).
    use_shell = sys.platform == "win32"
    process = subprocess.Popen(
        args,
        stdout=None,
        stderr=None,
        shell=use_shell,
    )
    return MockServerProcess(process, port, launcher)

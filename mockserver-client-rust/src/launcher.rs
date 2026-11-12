//! On-demand binary launcher for MockServer.
//!
//! Downloads the self-contained, JVM-less MockServer binary bundle for the
//! current platform from a GitHub Release, verifies its SHA-256, caches it
//! per-user, and launches it.  No Java installation and no Docker required.
//!
//! This is a faithful port of the reference implementation in
//! `mockserver-node/downloadBinary.js`.
//!
//! # Environment overrides
//!
//! | Variable | Purpose |
//! |----------|---------|
//! | `MOCKSERVER_BINARY_BASE_URL` | Mirror host for the release assets (corporate / air-gapped) |
//! | `MOCKSERVER_BINARY_CACHE` | Cache directory (default: per-OS user cache) |
//! | `MOCKSERVER_SKIP_BINARY_DOWNLOAD` | Fail instead of downloading (air-gapped CI with a pre-seeded cache) |
//! | `HTTPS_PROXY` / `HTTP_PROXY` | Honoured by the HTTP client for proxy routing |

use std::fs;
use std::io::{self, Read};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};

use sha2::{Digest, Sha256};

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const REPO: &str = "mock-server/mockserver-monorepo";

/// The MockServer version this client targets — derived from Cargo.toml at
/// compile time.
pub const VERSION: &str = env!("CARGO_PKG_VERSION");

/// HTTP connect + read timeout for binary downloads (10 minutes).
const HTTP_TIMEOUT_SECS: u64 = 600;

// ---------------------------------------------------------------------------
// Error type
// ---------------------------------------------------------------------------

/// Errors specific to the binary launcher.
#[derive(Debug)]
#[non_exhaustive]
pub enum LauncherError {
    /// The current OS or architecture is not supported.
    UnsupportedPlatform(String),
    /// Download was skipped (MOCKSERVER_SKIP_BINARY_DOWNLOAD is set) but no
    /// cached binary exists.
    SkipDownload(String),
    /// SHA-256 checksum verification failed.
    ChecksumMismatch {
        expected: String,
        actual: String,
    },
    /// The checksum file was empty or unparseable.
    ChecksumMissing(String),
    /// An I/O error (download, filesystem, process spawn).
    Io(io::Error),
    /// An HTTP transport error.
    Http(String),
    /// The server responded with HTTP 404 — no asset was published at the URL.
    NotFound(String),
    /// No downloadable release bundle exists for the requested version.
    NoBundle(String),
    /// The extraction command (`tar`) failed.
    ExtractionFailed(String),
    /// The launcher binary was missing or empty after extraction.
    LauncherMissing(String),
    /// The version string is invalid or contains path traversal.
    InvalidVersion(String),
}

impl std::fmt::Display for LauncherError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            LauncherError::UnsupportedPlatform(msg) => {
                write!(f, "unsupported platform: {msg}")
            }
            LauncherError::SkipDownload(msg) => write!(f, "{msg}"),
            LauncherError::ChecksumMismatch { expected, actual } => {
                write!(f, "checksum mismatch: expected {expected}, got {actual}")
            }
            LauncherError::ChecksumMissing(msg) => {
                write!(f, "checksum file empty or unparseable: {msg}")
            }
            LauncherError::Io(e) => write!(f, "I/O error: {e}"),
            LauncherError::Http(msg) => write!(f, "HTTP error: {msg}"),
            LauncherError::NotFound(msg) => write!(f, "not found (HTTP 404): {msg}"),
            LauncherError::NoBundle(msg) => write!(f, "{msg}"),
            LauncherError::ExtractionFailed(msg) => {
                write!(f, "extraction failed: {msg}")
            }
            LauncherError::LauncherMissing(msg) => {
                write!(f, "launcher missing after extract: {msg}")
            }
            LauncherError::InvalidVersion(msg) => {
                write!(f, "invalid version: {msg}")
            }
        }
    }
}

impl std::error::Error for LauncherError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            LauncherError::Io(e) => Some(e),
            _ => None,
        }
    }
}

impl From<io::Error> for LauncherError {
    fn from(e: io::Error) -> Self {
        LauncherError::Io(e)
    }
}

/// Result type alias for launcher operations.
pub type LauncherResult<T> = Result<T, LauncherError>;

// ---------------------------------------------------------------------------
// Version validation (H1)
// ---------------------------------------------------------------------------

/// Validate a version string against the semver-like pattern and reject path
/// separators or `..` sequences. Returns the version unchanged if valid.
fn validate_version(version: &str) -> LauncherResult<&str> {
    // Reject path separators and parent-directory traversal.
    if version.contains('/') || version.contains('\\') || version.contains("..") {
        return Err(LauncherError::InvalidVersion(format!(
            "version contains path separator or '..': {version}"
        )));
    }
    // Must match: digits.digits.digits optionally followed by [-.][alphanumeric/dot]+
    // Pattern: ^[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.]+)?$
    let mut chars = version.chars().peekable();

    // Helper: consume one or more digits, return false if none.
    fn digits(chars: &mut std::iter::Peekable<std::str::Chars>) -> bool {
        let mut found = false;
        while chars.peek().is_some_and(|c| c.is_ascii_digit()) {
            chars.next();
            found = true;
        }
        found
    }

    if !digits(&mut chars) {
        return Err(LauncherError::InvalidVersion(version.to_string()));
    }
    if chars.next() != Some('.') {
        return Err(LauncherError::InvalidVersion(version.to_string()));
    }
    if !digits(&mut chars) {
        return Err(LauncherError::InvalidVersion(version.to_string()));
    }
    if chars.next() != Some('.') {
        return Err(LauncherError::InvalidVersion(version.to_string()));
    }
    if !digits(&mut chars) {
        return Err(LauncherError::InvalidVersion(version.to_string()));
    }

    // Optional pre-release / build metadata: [-.][0-9A-Za-z.]+
    if let Some(&c) = chars.peek() {
        if c == '-' || c == '.' {
            chars.next();
            let mut found = false;
            while chars
                .peek()
                .is_some_and(|c| c.is_ascii_alphanumeric() || *c == '.')
            {
                chars.next();
                found = true;
            }
            if !found {
                return Err(LauncherError::InvalidVersion(version.to_string()));
            }
        }
    }

    if chars.next().is_some() {
        return Err(LauncherError::InvalidVersion(version.to_string()));
    }

    Ok(version)
}

/// Assert that `child` is a descendant of `base`. Both paths are
/// canonicalized (resolving symlinks) before the check. If `child` does
/// not yet exist, we canonicalize its parent (which must exist) and append
/// the final component — this avoids false negatives on macOS where `/var`
/// is a symlink to `/private/var`.
fn assert_within(base: &Path, child: &Path) -> LauncherResult<()> {
    let resolved_base = fs::canonicalize(base).unwrap_or_else(|_| base.to_path_buf());

    let resolved_child = if child.exists() {
        fs::canonicalize(child).unwrap_or_else(|_| child.to_path_buf())
    } else if let (Some(parent), Some(file_name)) = (child.parent(), child.file_name()) {
        // Parent should exist (it's the cache base we just created).
        let resolved_parent =
            fs::canonicalize(parent).unwrap_or_else(|_| parent.to_path_buf());
        resolved_parent.join(file_name)
    } else {
        child.to_path_buf()
    };

    if !resolved_child.starts_with(&resolved_base) {
        return Err(LauncherError::InvalidVersion(format!(
            "resolved path {} escapes cache base {}",
            resolved_child.display(),
            resolved_base.display()
        )));
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// Platform detection
// ---------------------------------------------------------------------------

/// Resolved platform triple for the bundle file name.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Platform {
    /// `linux`, `darwin`, or `windows`.
    pub os_name: &'static str,
    /// `x86_64` or `aarch64`.
    pub arch: &'static str,
    /// `tar.gz` or `zip`.
    pub ext: &'static str,
}

/// Map the current platform and architecture to the bundle naming tokens.
///
/// Returns an error on unsupported OS/arch combinations.
pub fn resolve_platform() -> LauncherResult<Platform> {
    let (os_name, ext) = if cfg!(target_os = "linux") {
        ("linux", "tar.gz")
    } else if cfg!(target_os = "macos") {
        ("darwin", "tar.gz")
    } else if cfg!(target_os = "windows") {
        ("windows", "zip")
    } else {
        return Err(LauncherError::UnsupportedPlatform(format!(
            "unsupported OS: {}",
            std::env::consts::OS
        )));
    };

    let arch = if cfg!(target_arch = "x86_64") {
        "x86_64"
    } else if cfg!(target_arch = "aarch64") {
        "aarch64"
    } else {
        return Err(LauncherError::UnsupportedPlatform(format!(
            "unsupported architecture: {}",
            std::env::consts::ARCH
        )));
    };

    Ok(Platform { os_name, arch, ext })
}

// ---------------------------------------------------------------------------
// Bundle naming
// ---------------------------------------------------------------------------

/// The base name and extension of the bundle archive for the given version.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BundleName {
    /// e.g. `mockserver-7.0.0-darwin-aarch64`
    pub name: String,
    /// e.g. `tar.gz`
    pub ext: &'static str,
}

/// Compute the bundle base name for a given version on the current platform.
pub fn bundle_base_name(version: &str) -> LauncherResult<BundleName> {
    let platform = resolve_platform()?;
    Ok(BundleName {
        name: format!(
            "mockserver-{}-{}-{}",
            version, platform.os_name, platform.arch
        ),
        ext: platform.ext,
    })
}

// ---------------------------------------------------------------------------
// Cache directory
// ---------------------------------------------------------------------------

/// Resolve the binary cache base directory.
///
/// Precedence:
/// 1. `MOCKSERVER_BINARY_CACHE` env var
/// 2. Windows: `%LOCALAPPDATA%` (fallback `~/AppData/Local`)
/// 3. Unix: `$XDG_CACHE_HOME` or `~/.cache`
///
/// Then append `/mockserver/binaries`.
pub fn cache_dir() -> PathBuf {
    if let Ok(dir) = std::env::var("MOCKSERVER_BINARY_CACHE") {
        return PathBuf::from(dir);
    }

    let base = if cfg!(target_os = "windows") {
        std::env::var("LOCALAPPDATA")
            .map(PathBuf::from)
            .unwrap_or_else(|_| {
                dirs::home_dir()
                    .unwrap_or_else(|| PathBuf::from("."))
                    .join("AppData")
                    .join("Local")
            })
    } else {
        std::env::var("XDG_CACHE_HOME")
            .map(PathBuf::from)
            .unwrap_or_else(|_| {
                dirs::home_dir()
                    .unwrap_or_else(|| PathBuf::from("."))
                    .join(".cache")
            })
    };

    base.join("mockserver").join("binaries")
}

// ---------------------------------------------------------------------------
// Asset URL
// ---------------------------------------------------------------------------

/// The CDN base URL used for SNAPSHOT version downloads.
const SNAPSHOT_CDN: &str = "https://downloads.mock-server.com";

/// Build a clear, actionable error message for a missing release bundle.
///
/// Explains that no downloadable bundle exists for `version` and lists the
/// concrete alternatives. The wording is kept consistent across all client
/// languages.
fn no_bundle_message(version: &str) -> String {
    format!(
        "no MockServer release bundle is published for version {version} \
         (no downloadable asset at the GitHub release tag 'mockserver-{version}'). \
         Use a MockServer version that ships self-contained bundles, \
         or run MockServer via Docker (docker run mockserver/mockserver:mockserver-{version}), \
         or use the Maven Central jar (org.mock-server:mockserver-netty:{version})."
    )
}

/// Returns `true` if the version string contains "-SNAPSHOT" (case-insensitive),
/// indicating a pre-release snapshot build.
pub fn is_snapshot(version: &str) -> bool {
    version.to_ascii_uppercase().contains("-SNAPSHOT")
}

/// Compute the download URL for a release asset.
///
/// Uses `MOCKSERVER_BINARY_BASE_URL` if set; otherwise defaults to GitHub
/// Releases for release versions and the downloads.mock-server.com CDN for
/// SNAPSHOT versions.
pub fn asset_url(version: &str, file: &str) -> String {
    let base = std::env::var("MOCKSERVER_BINARY_BASE_URL").unwrap_or_else(|_| {
        if is_snapshot(version) {
            format!("{SNAPSHOT_CDN}/mockserver-{version}")
        } else {
            format!(
                "https://github.com/{REPO}/releases/download/mockserver-{version}"
            )
        }
    });
    let base = base.trim_end_matches('/');
    format!("{base}/{file}")
}

// ---------------------------------------------------------------------------
// Launcher path
// ---------------------------------------------------------------------------

/// The expected path to the launcher binary inside the extracted bundle.
pub fn launcher_path(dir: &Path, bundle_name: &str) -> PathBuf {
    let bin_name = if cfg!(target_os = "windows") {
        "mockserver.bat"
    } else {
        "mockserver"
    };
    dir.join(bundle_name).join("bin").join(bin_name)
}

// ---------------------------------------------------------------------------
// SHA-256 helpers
// ---------------------------------------------------------------------------

/// Compute the SHA-256 hex digest of a file.
pub fn sha256_file(path: &Path) -> LauncherResult<String> {
    let mut file = fs::File::open(path)?;
    let mut hasher = Sha256::new();
    let mut buf = [0u8; 8192];
    loop {
        let n = file.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(lower_hex(&hasher.finalize()))
}

/// Compute the SHA-256 hex digest of a byte slice (used in tests).
pub fn sha256_bytes(data: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(data);
    lower_hex(&hasher.finalize())
}

fn lower_hex(bytes: &[u8]) -> String {
    use std::fmt::Write;
    bytes.iter().fold(String::with_capacity(bytes.len() * 2), |mut hex, b| {
        let _ = write!(hex, "{b:02x}");
        hex
    })
}

// ---------------------------------------------------------------------------
// Download helper
// ---------------------------------------------------------------------------

/// Download a URL to a local file path, streaming the body to disk to avoid
/// buffering large binaries in memory. Proxy env vars (`HTTPS_PROXY`,
/// `HTTP_PROXY`) are honoured by the reqwest client.
fn download(url: &str, dest: &Path) -> LauncherResult<()> {
    let client = reqwest::blocking::Client::builder()
        .timeout(std::time::Duration::from_secs(HTTP_TIMEOUT_SECS))
        .connect_timeout(std::time::Duration::from_secs(30))
        .build()
        .map_err(|e| LauncherError::Http(format!("failed to build HTTP client: {e}")))?;

    let mut resp = client
        .get(url)
        .send()
        .map_err(|e| LauncherError::Http(format!("GET {url} failed: {e}")))?;

    if resp.status() == reqwest::StatusCode::NOT_FOUND {
        return Err(LauncherError::NotFound(format!("GET {url}")));
    }
    if !resp.status().is_success() {
        return Err(LauncherError::Http(format!(
            "GET {url} failed: HTTP {}",
            resp.status()
        )));
    }

    // Stream body directly to file to avoid a large heap allocation (H6/SEC-09).
    let mut file = fs::File::create(dest)?;
    io::copy(&mut resp, &mut file)
        .map_err(|e| LauncherError::Http(format!("streaming body to {}: {e}", dest.display())))?;
    Ok(())
}

// ---------------------------------------------------------------------------
// Ensure binary (core logic)
// ---------------------------------------------------------------------------

/// Options for [`ensure_binary`].
#[derive(Debug, Clone, Default)]
pub struct EnsureOptions {
    /// Optional logger closure. If `false`, progress is silent.
    pub verbose: bool,
}

/// Ensure the platform binary is present and return the launcher path,
/// downloading + verifying + extracting + caching on first use.
///
/// SHA-256 verification is always performed when downloading — it cannot be
/// skipped by external callers.
///
/// After a successful install the cache is pruned: only the current version
/// directory (and at most one previous) is kept; stale `.part` temp files are
/// removed.
pub fn ensure_binary(version: &str, opts: &EnsureOptions) -> LauncherResult<PathBuf> {
    // H1: validate version string.
    validate_version(version)?;

    let meta = bundle_base_name(version)?;
    let base = cache_dir();
    let dir = base.join(version);

    // H1: assert the version directory stays within the cache base.
    // Create the base first so canonicalize can resolve it.
    fs::create_dir_all(&base)?;
    assert_within(&base, &dir)?;

    let launcher = launcher_path(&dir, &meta.name);

    // Reuse cached binary if it exists and is non-empty.
    if launcher.exists() {
        let size = fs::metadata(&launcher).map(|m| m.len()).unwrap_or(0);
        if size > 0 {
            if opts.verbose {
                eprintln!("Using cached binary: {}", launcher.display());
            }
            return Ok(launcher);
        }
    }

    // MOCKSERVER_SKIP_BINARY_DOWNLOAD => fail instead of downloading.
    if std::env::var("MOCKSERVER_SKIP_BINARY_DOWNLOAD").is_ok() {
        return Err(LauncherError::SkipDownload(format!(
            "MOCKSERVER_SKIP_BINARY_DOWNLOAD is set but no cached binary at {}",
            launcher.display()
        )));
    }

    fs::create_dir_all(&dir)?;

    let archive_file = format!("{}.{}", meta.name, meta.ext);
    let archive = dir.join(&archive_file);
    let partial = PathBuf::from(format!("{}.part", archive.display()));
    let sha_file = PathBuf::from(format!("{}.sha256", archive.display()));

    // Download to a temp .part file; rename only after checksum passes.
    let url = asset_url(version, &archive_file);
    if opts.verbose {
        eprintln!("Downloading {url}");
    }

    let result = (|| -> LauncherResult<()> {
        // A 404 on the archive means the release tag exists but ships no bundle
        // for this version (or the tag does not exist). Translate it into
        // actionable guidance instead of an opaque HTTP error.
        match download(&url, &partial) {
            Ok(()) => {}
            Err(LauncherError::NotFound(_)) => {
                return Err(LauncherError::NoBundle(no_bundle_message(version)));
            }
            Err(e) => return Err(e),
        }

        // Verify SHA-256 — always fail-closed. (H2: not bypassable)
        let sha_url = asset_url(version, &format!("{archive_file}.sha256"));
        download(&sha_url, &sha_file)?;

        let sha_content = fs::read_to_string(&sha_file)?;
        let expected = sha_content
            .split_whitespace()
            .next()
            .unwrap_or("")
            .to_string();

        if expected.is_empty() {
            return Err(LauncherError::ChecksumMissing(format!(
                "checksum file for {} is empty or unparseable",
                meta.name
            )));
        }

        let actual = sha256_file(&partial)?;
        if expected != actual {
            return Err(LauncherError::ChecksumMismatch { expected, actual });
        }

        if opts.verbose {
            eprintln!("Checksum verified");
        }

        fs::rename(&partial, &archive)?;
        Ok(())
    })();

    if let Err(e) = result {
        // H3: best-effort cleanup of BOTH the .part and .sha256 temp files.
        let _ = fs::remove_file(&partial);
        let _ = fs::remove_file(&sha_file);
        return Err(e);
    }

    // Extract with the system tar (handles both .tar.gz and .zip on
    // macOS/Linux bsdtar and GNU tar).
    if opts.verbose {
        eprintln!("Extracting {}", archive.display());
    }

    let tar_status = Command::new("tar")
        .args([
            "-xf",
            &archive.to_string_lossy(),
            "-C",
            &dir.to_string_lossy(),
        ])
        .status();

    match tar_status {
        Ok(status) if status.success() => {}
        Ok(status) => {
            return Err(LauncherError::ExtractionFailed(format!(
                "tar exit code: {}",
                status.code().unwrap_or(-1)
            )));
        }
        Err(e) => {
            return Err(LauncherError::ExtractionFailed(format!(
                "could not run tar: {e}"
            )));
        }
    }

    // H3: verify no extracted file escaped verDir.
    if let Ok(entries) = fs::read_dir(&dir) {
        for entry in entries.flatten() {
            let p = entry.path();
            // Resolve symlinks to catch escapes.
            let resolved = fs::canonicalize(&p).unwrap_or_else(|_| p.clone());
            let resolved_dir = fs::canonicalize(&dir).unwrap_or_else(|_| dir.clone());
            if !resolved.starts_with(&resolved_dir) {
                return Err(LauncherError::ExtractionFailed(format!(
                    "extracted file escapes version directory: {}",
                    resolved.display()
                )));
            }
        }
    }

    // Verify the launcher exists and is non-empty.
    if !launcher.exists()
        || fs::metadata(&launcher).map(|m| m.len()).unwrap_or(0) == 0
    {
        return Err(LauncherError::LauncherMissing(format!(
            "launcher missing or empty after extract: {}",
            launcher.display()
        )));
    }

    // chmod 0755 on non-Windows.
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&launcher, fs::Permissions::from_mode(0o755))?;
    }

    // Prune old version directories from the cache.
    prune_old_versions(&base, version);

    Ok(launcher)
}

// ---------------------------------------------------------------------------
// Cache pruning
// ---------------------------------------------------------------------------

/// Parse a version string into numeric segments for semver-aware comparison
/// (H7). Returns `None` if it does not start with three numeric dot-separated
/// segments.
fn parse_semver_segments(s: &str) -> Option<(u64, u64, u64, String)> {
    let mut parts = s.splitn(2, |c: char| c == '-' || (!c.is_ascii_digit() && c != '.'));
    let version_part = parts.next()?;
    let segments: Vec<&str> = version_part.split('.').collect();
    if segments.len() < 3 {
        return None;
    }
    let major = segments[0].parse::<u64>().ok()?;
    let minor = segments[1].parse::<u64>().ok()?;
    let patch = segments[2].parse::<u64>().ok()?;
    let rest = s[version_part.len()..].to_string();
    Some((major, minor, patch, rest))
}

/// Compare two pre-release suffixes for semver ordering.
///
/// Per semver, a release (empty pre-release) is NEWER/GREATER than any
/// pre-release of the same numeric version (e.g. `7.0.0 > 7.0.0-SNAPSHOT`).
/// Among two pre-releases, compare their identifiers lexicographically.
fn compare_pre_release(a: &str, b: &str) -> std::cmp::Ordering {
    match (a.is_empty(), b.is_empty()) {
        (true, true) => std::cmp::Ordering::Equal,
        // a is a release, b is a pre-release => a is newer (greater)
        (true, false) => std::cmp::Ordering::Greater,
        // a is a pre-release, b is a release => b is newer (a is less)
        (false, true) => std::cmp::Ordering::Less,
        // both are pre-releases => compare lexicographically
        (false, false) => a.cmp(b),
    }
}

/// Remove old version directories from the cache base, keeping only the
/// current version (and at most one previous). Also removes leftover `.part`
/// and `.sha256` temp files. Safe: never deletes outside the cache dir;
/// tolerates concurrent runs.
pub fn prune_old_versions(cache_base: &Path, current_version: &str) {
    let entries = match fs::read_dir(cache_base) {
        Ok(e) => e,
        Err(_) => return, // cache dir might not exist yet
    };

    // Collect version directories (entries that are directories and look like
    // version strings — we just check they are dirs and not the current one).
    let mut version_dirs: Vec<(PathBuf, (u64, u64, u64, String))> = Vec::new();

    for entry in entries.flatten() {
        let path = entry.path();

        // Clean up stale .part and .sha256 temp files at the cache root level.
        if let Some(ext) = path.extension().and_then(|e| e.to_str()) {
            if ext == "part" || ext == "sha256" {
                let _ = fs::remove_file(&path);
                continue;
            }
        }

        if !path.is_dir() {
            continue;
        }

        let dir_name = match path.file_name().and_then(|n| n.to_str()) {
            Some(n) => n.to_string(),
            None => continue,
        };

        if dir_name == current_version {
            // Clean up .part files inside the current version dir too.
            clean_part_files(&path);
            continue;
        }

        // Safety: only remove dirs that are children of cache_base.
        if path.parent() != Some(cache_base) {
            continue;
        }

        // H7: parse semver for comparison; skip non-version directories.
        if let Some(semver) = parse_semver_segments(&dir_name) {
            version_dirs.push((path, semver));
        }
    }

    // H7: sort by semver descending (newest first) using numeric comparison.
    // When numeric cores are equal, a release (empty pre-release) is NEWER
    // than a pre-release (e.g. 7.0.0 > 7.0.0-SNAPSHOT).
    version_dirs.sort_by(|a, b| {
        let (a_maj, a_min, a_pat, ref a_rest) = a.1;
        let (b_maj, b_min, b_pat, ref b_rest) = b.1;
        (b_maj, b_min, b_pat)
            .cmp(&(a_maj, a_min, a_pat))
            .then_with(|| compare_pre_release(b_rest, a_rest))
    });

    // Keep at most one previous version (the newest one).
    for (path, _) in version_dirs.iter().skip(1) {
        let _ = fs::remove_dir_all(path);
    }
}

/// Remove `.part` temp files from a directory.
fn clean_part_files(dir: &Path) {
    if let Ok(entries) = fs::read_dir(dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.extension().is_some_and(|e| e == "part") {
                let _ = fs::remove_file(&path);
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Server handle
// ---------------------------------------------------------------------------

/// A handle to a running MockServer process.
///
/// When dropped, the process is killed (SIGKILL on Unix, TerminateProcess on
/// Windows).
pub struct ServerHandle {
    child: Child,
    port: u16,
}

impl ServerHandle {
    /// The port the server was started on.
    pub fn port(&self) -> u16 {
        self.port
    }

    /// Stop the server by killing the process.
    pub fn stop(&mut self) -> LauncherResult<()> {
        self.child.kill().map_err(LauncherError::Io)?;
        let _ = self.child.wait();
        Ok(())
    }

    /// Wait for the child process to exit, returning its exit status.
    pub fn wait(&mut self) -> LauncherResult<std::process::ExitStatus> {
        self.child.wait().map_err(LauncherError::Io)
    }
}

impl Drop for ServerHandle {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

// ---------------------------------------------------------------------------
// Public API: start / ensure_launcher
// ---------------------------------------------------------------------------

/// Ensure the launcher binary is available and return its path.
///
/// This is a convenience wrapper around [`ensure_binary`] that uses the
/// crate's compiled-in version.
pub fn ensure_launcher() -> LauncherResult<PathBuf> {
    ensure_binary(VERSION, &EnsureOptions::default())
}

/// Download (if needed) and start a MockServer on the given port.
///
/// Returns a [`ServerHandle`] that can be used to stop the server. The
/// process inherits stdout/stderr by default so logs are visible.
pub fn start(port: u16) -> LauncherResult<ServerHandle> {
    start_with_version(VERSION, port, &EnsureOptions::default())
}

/// Start a MockServer of the given version on the given port.
pub fn start_with_version(
    version: &str,
    port: u16,
    opts: &EnsureOptions,
) -> LauncherResult<ServerHandle> {
    let launcher = ensure_binary(version, opts)?;

    // No shell on any platform. On Windows the .bat launcher runs through
    // cmd.exe with a command line passed to CreateProcess unchanged (`raw_arg`),
    // because the standard argument quoting is not the one cmd.exe follows.
    #[cfg(target_os = "windows")]
    let mut cmd = {
        use std::os::windows::process::CommandExt;
        let launcher = launcher.to_str().ok_or_else(|| {
            LauncherError::Io(io::Error::new(
                io::ErrorKind::InvalidInput,
                format!("launcher path is not valid Unicode: {}", launcher.display()),
            ))
        })?;
        let port = port.to_string();
        let comspec = windows_comspec(std::env::var_os("ComSpec"), std::env::var_os("SystemRoot"))?;
        let mut c = Command::new(comspec);
        c.raw_arg(windows_cmd_args(launcher, &["-serverPort", &port])?);
        // CREATE_NO_WINDOW suppresses the console window for background use.
        c.creation_flags(0x08000000);
        c
    };

    #[cfg(not(target_os = "windows"))]
    let mut cmd = {
        let mut c = Command::new(&launcher);
        c.arg("-serverPort").arg(port.to_string());
        c
    };

    cmd.stdout(Stdio::inherit());
    cmd.stderr(Stdio::inherit());

    let child = cmd.spawn().map_err(LauncherError::Io)?;
    Ok(ServerHandle { child, port })
}

/// The absolute path of cmd.exe: `%ComSpec%`, else `%SystemRoot%\System32\cmd.exe`,
/// or an error if neither is set.
#[cfg_attr(not(target_os = "windows"), allow(dead_code))]
fn windows_comspec(
    comspec: Option<std::ffi::OsString>,
    system_root: Option<std::ffi::OsString>,
) -> LauncherResult<PathBuf> {
    match (comspec, system_root) {
        (Some(comspec), _) if !comspec.is_empty() => Ok(PathBuf::from(comspec)),
        (_, Some(root)) if !root.is_empty() => {
            let mut path = root;
            path.push("\\System32\\cmd.exe");
            Ok(PathBuf::from(path))
        }
        _ => Err(LauncherError::Io(io::Error::new(
            io::ErrorKind::NotFound,
            "cmd.exe not found: neither %ComSpec% nor %SystemRoot% is set",
        ))),
    }
}

/// Build the cmd.exe arguments (everything after the program name) that run
/// the `.bat` launcher. Each part is double-quoted so cmd.exe takes `&`, `|`,
/// `<`, `>`, `^` and parentheses literally; `/d` skips AutoRun and `/v:off`
/// keeps `!` literal. cmd.exe expands `%NAME%` even inside quotes, so a line
/// with two or more `%` is refused, as is any `"`, line break or NUL.
#[cfg_attr(not(target_os = "windows"), allow(dead_code))]
fn windows_cmd_args(launcher: &str, args: &[&str]) -> LauncherResult<String> {
    // Errors name the part, never its value, which may be a secret.
    let mut quoted = Vec::with_capacity(args.len() + 1);
    for (i, part) in std::iter::once(launcher)
        .chain(args.iter().copied())
        .enumerate()
    {
        if part.contains(['"', '\r', '\n', '\0']) {
            let name = if i == 0 {
                "the launcher path".to_string()
            } else {
                format!("argument {}", i - 1)
            };
            return Err(LauncherError::Io(io::Error::new(
                io::ErrorKind::InvalidInput,
                format!(
                    "{name} cannot be passed through cmd.exe safely (contains a double quote, a line break or NUL)"
                ),
            )));
        }
        // Double a trailing run of backslashes so it cannot escape the closing
        // quote when the launched program splits its command line.
        let trailing = part.len() - part.trim_end_matches('\\').len();
        quoted.push(format!("\"{part}{}\"", "\\".repeat(trailing)));
    }
    let line = quoted.join(" ");
    if line.matches('%').count() > 1 {
        return Err(LauncherError::Io(io::Error::new(
            io::ErrorKind::InvalidInput,
            "the launcher path and arguments contain more than one %, which cmd.exe would expand as an \
             environment variable reference (%NAME%); remove the % characters to run on Windows",
        )));
    }
    Ok(format!("/d /v:off /s /c \"{line}\""))
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::path::PathBuf;
    use std::sync::Mutex;

    #[test]
    fn windows_cmd_args_quote_each_part_so_cmd_metacharacters_stay_literal() {
        let line = windows_cmd_args(
            r"C:\My Cache (x86)\9.0.0-SNAPSHOT\mockserver.bat",
            &["-serverPort", "1080", "a&b|c<d>e^f!g"],
        )
        .unwrap();
        assert_eq!(
            line,
            r#"/d /v:off /s /c ""C:\My Cache (x86)\9.0.0-SNAPSHOT\mockserver.bat" "-serverPort" "1080" "a&b|c<d>e^f!g"""#
        );
    }

    #[test]
    fn windows_cmd_args_keep_a_single_percent_and_double_a_trailing_backslash_run() {
        let line =
            windows_cmd_args(r"C:\c\mockserver.bat", &["-Dx=100%", r"C:\dir\", r"a\b"]).unwrap();
        assert_eq!(
            line,
            r#"/d /v:off /s /c ""C:\c\mockserver.bat" "-Dx=100%" "C:\dir\\" "a\b"""#
        );
    }

    #[test]
    fn windows_cmd_args_refuse_a_quote_line_break_or_nul_without_echoing_it() {
        for bad in ["\"", "\r", "\n", "\0"] {
            let arg = format!("secret{bad}");
            let err = windows_cmd_args(r"C:\c\mockserver.bat", &["ok", &arg])
                .unwrap_err()
                .to_string();
            assert!(
                err.contains("argument 1 cannot be passed through cmd.exe safely")
                    && !err.contains("secret"),
                "argument containing {bad:?}: {err}"
            );
            let launcher = format!(r"C:\secret{bad}\mockserver.bat");
            let err = windows_cmd_args(&launcher, &[]).unwrap_err().to_string();
            assert!(
                err.contains("the launcher path cannot be passed through cmd.exe safely")
                    && !err.contains("secret"),
                "launcher containing {bad:?}: {err}"
            );
        }
    }

    #[test]
    fn windows_cmd_args_refuse_two_percent_signs_without_echoing_the_line() {
        let err = windows_cmd_args(r"C:\c%\mockserver.bat", &["-Dx=secret%PATH"])
            .unwrap_err()
            .to_string();
        assert!(
            err.contains("more than one %") && !err.contains("secret"),
            "{err}"
        );
    }

    #[test]
    fn windows_comspec_prefers_comspec_then_system_root_then_fails() {
        let os = |s: &str| Some(std::ffi::OsString::from(s));
        assert_eq!(
            windows_comspec(os(r"D:\cmd.exe"), os(r"C:\Windows")).unwrap(),
            PathBuf::from(r"D:\cmd.exe")
        );
        assert_eq!(
            windows_comspec(os(""), os(r"C:\Windows")).unwrap(),
            PathBuf::from(r"C:\Windows\System32\cmd.exe")
        );
        let err = windows_comspec(None, None).unwrap_err().to_string();
        assert!(err.contains("cmd.exe not found"), "{err}");
    }

    // Global mutex to serialize tests that mutate environment variables.
    // Rust's test harness runs tests in parallel within the same process,
    // and env vars are process-global, so we must serialize access.
    static ENV_MUTEX: Mutex<()> = Mutex::new(());

    // Helper: create a temp directory that is cleaned up when dropped.
    struct TempDir(PathBuf);

    impl TempDir {
        fn new(prefix: &str) -> Self {
            let dir = std::env::temp_dir().join(format!(
                "mockserver-test-{}-{}",
                prefix,
                std::process::id()
            ));
            let _ = fs::remove_dir_all(&dir);
            fs::create_dir_all(&dir).unwrap();
            Self(dir)
        }

        fn path(&self) -> &Path {
            &self.0
        }
    }

    impl Drop for TempDir {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    // -----------------------------------------------------------------------
    // EnvGuard — temporarily set or unset an env var, restore on drop.
    // -----------------------------------------------------------------------

    struct EnvGuard {
        key: String,
        original: Option<String>,
    }

    impl EnvGuard {
        fn new(key: &str, value: Option<&str>) -> Self {
            let original = std::env::var(key).ok();
            match value {
                Some(v) => std::env::set_var(key, v),
                None => std::env::remove_var(key),
            }
            Self {
                key: key.to_string(),
                original,
            }
        }
    }

    impl Drop for EnvGuard {
        fn drop(&mut self) {
            match &self.original {
                Some(v) => std::env::set_var(&self.key, v),
                None => std::env::remove_var(&self.key),
            }
        }
    }

    // -----------------------------------------------------------------------
    // Version validation (H1)
    // -----------------------------------------------------------------------

    #[test]
    fn test_validate_version_valid() {
        assert!(validate_version("7.0.0").is_ok());
        assert!(validate_version("6.1.0").is_ok());
        assert!(validate_version("12.345.678").is_ok());
        assert!(validate_version("1.2.3-beta.1").is_ok());
        assert!(validate_version("1.2.3-rc1").is_ok());
        assert!(validate_version("1.2.3.SNAPSHOT").is_ok());
    }

    #[test]
    fn test_validate_version_rejects_path_separators() {
        assert!(validate_version("../../../etc/passwd").is_err());
        assert!(validate_version("7.0.0/../../bad").is_err());
        assert!(validate_version("7.0.0\\..\\bad").is_err());
    }

    #[test]
    fn test_validate_version_rejects_invalid_format() {
        assert!(validate_version("").is_err());
        assert!(validate_version("abc").is_err());
        assert!(validate_version("7.0").is_err());
        assert!(validate_version("7").is_err());
        assert!(validate_version("7.0.0-").is_err()); // trailing dash with nothing after
    }

    // -----------------------------------------------------------------------
    // Platform / arch detection
    // -----------------------------------------------------------------------

    #[test]
    fn test_resolve_platform_returns_valid_triple() {
        let p = resolve_platform().unwrap();
        assert!(
            ["linux", "darwin", "windows"].contains(&p.os_name),
            "unexpected OS: {}",
            p.os_name
        );
        assert!(
            ["x86_64", "aarch64"].contains(&p.arch),
            "unexpected arch: {}",
            p.arch
        );
        if p.os_name == "windows" {
            assert_eq!(p.ext, "zip");
        } else {
            assert_eq!(p.ext, "tar.gz");
        }
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn test_resolve_platform_macos() {
        let p = resolve_platform().unwrap();
        assert_eq!(p.os_name, "darwin");
        assert_eq!(p.ext, "tar.gz");
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn test_resolve_platform_linux() {
        let p = resolve_platform().unwrap();
        assert_eq!(p.os_name, "linux");
        assert_eq!(p.ext, "tar.gz");
    }

    #[cfg(target_arch = "aarch64")]
    #[test]
    fn test_resolve_platform_aarch64() {
        let p = resolve_platform().unwrap();
        assert_eq!(p.arch, "aarch64");
    }

    #[cfg(target_arch = "x86_64")]
    #[test]
    fn test_resolve_platform_x86_64() {
        let p = resolve_platform().unwrap();
        assert_eq!(p.arch, "x86_64");
    }

    // -----------------------------------------------------------------------
    // Bundle naming
    // -----------------------------------------------------------------------

    #[test]
    fn test_bundle_base_name() {
        let b = bundle_base_name("7.0.0").unwrap();
        let platform = resolve_platform().unwrap();
        assert_eq!(
            b.name,
            format!("mockserver-7.0.0-{}-{}", platform.os_name, platform.arch)
        );
        assert_eq!(b.ext, platform.ext);
    }

    #[test]
    fn test_bundle_base_name_custom_version() {
        let b = bundle_base_name("6.1.0").unwrap();
        assert!(b.name.starts_with("mockserver-6.1.0-"));
    }

    // -----------------------------------------------------------------------
    // Cache path resolution
    // -----------------------------------------------------------------------

    #[test]
    fn test_cache_dir_default_has_mockserver_binaries() {
        let _lock = ENV_MUTEX.lock().unwrap();
        let _guard = EnvGuard::new("MOCKSERVER_BINARY_CACHE", None);
        let dir = cache_dir();
        let path_str = dir.to_string_lossy();
        assert!(
            path_str.ends_with("mockserver/binaries")
                || path_str.ends_with("mockserver\\binaries"),
            "cache_dir should end with mockserver/binaries, got: {path_str}"
        );
    }

    #[test]
    fn test_cache_dir_respects_env_override() {
        let _lock = ENV_MUTEX.lock().unwrap();
        let _guard = EnvGuard::new("MOCKSERVER_BINARY_CACHE", Some("/custom/path"));
        let dir = cache_dir();
        assert_eq!(dir, PathBuf::from("/custom/path"));
    }

    #[cfg(not(target_os = "windows"))]
    #[test]
    fn test_cache_dir_respects_xdg_cache_home() {
        let _lock = ENV_MUTEX.lock().unwrap();
        let _guard1 = EnvGuard::new("MOCKSERVER_BINARY_CACHE", None);
        let _guard2 = EnvGuard::new("XDG_CACHE_HOME", Some("/tmp/xdg-test-cache"));
        let dir = cache_dir();
        assert_eq!(
            dir,
            PathBuf::from("/tmp/xdg-test-cache/mockserver/binaries")
        );
    }

    // -----------------------------------------------------------------------
    // Asset URL
    // -----------------------------------------------------------------------

    #[test]
    fn test_asset_url_default() {
        let _lock = ENV_MUTEX.lock().unwrap();
        let _guard = EnvGuard::new("MOCKSERVER_BINARY_BASE_URL", None);
        let url = asset_url("7.0.0", "mockserver-7.0.0-darwin-aarch64.tar.gz");
        assert_eq!(
            url,
            "https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-7.0.0/mockserver-7.0.0-darwin-aarch64.tar.gz"
        );
    }

    #[test]
    fn test_asset_url_custom_base() {
        let _lock = ENV_MUTEX.lock().unwrap();
        let _guard = EnvGuard::new(
            "MOCKSERVER_BINARY_BASE_URL",
            Some("https://mirror.internal/releases/"),
        );
        let url = asset_url("7.0.0", "mockserver-7.0.0-linux-x86_64.tar.gz");
        assert_eq!(
            url,
            "https://mirror.internal/releases/mockserver-7.0.0-linux-x86_64.tar.gz"
        );
    }

    #[test]
    fn test_asset_url_strips_trailing_slashes() {
        let _lock = ENV_MUTEX.lock().unwrap();
        let _guard = EnvGuard::new(
            "MOCKSERVER_BINARY_BASE_URL",
            Some("https://mirror.internal///"),
        );
        let url = asset_url("7.0.0", "file.tar.gz");
        assert_eq!(url, "https://mirror.internal/file.tar.gz");
    }

    // -----------------------------------------------------------------------
    // SNAPSHOT vs Release URL selection
    // -----------------------------------------------------------------------

    #[test]
    fn test_is_snapshot() {
        assert!(is_snapshot("7.0.0-SNAPSHOT"));
        assert!(is_snapshot("7.0.0-snapshot"));
        assert!(is_snapshot("7.0.0-Snapshot"));
        assert!(is_snapshot("7.1.0-SNAPSHOT"));
        assert!(!is_snapshot("7.0.0"));
        assert!(!is_snapshot("7.0.0-beta.1"));
        assert!(!is_snapshot("7.0.0-rc.1"));
    }

    #[test]
    fn test_asset_url_snapshot_uses_cdn() {
        let _lock = ENV_MUTEX.lock().unwrap();
        let _guard = EnvGuard::new("MOCKSERVER_BINARY_BASE_URL", None);
        let url = asset_url(
            "7.1.0-SNAPSHOT",
            "mockserver-7.1.0-SNAPSHOT-darwin-aarch64.tar.gz",
        );
        assert_eq!(
            url,
            "https://downloads.mock-server.com/mockserver-7.1.0-SNAPSHOT/mockserver-7.1.0-SNAPSHOT-darwin-aarch64.tar.gz"
        );
    }

    #[test]
    fn test_asset_url_release_uses_github() {
        let _lock = ENV_MUTEX.lock().unwrap();
        let _guard = EnvGuard::new("MOCKSERVER_BINARY_BASE_URL", None);
        let url = asset_url("7.0.0", "mockserver-7.0.0-darwin-aarch64.tar.gz");
        assert_eq!(
            url,
            "https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-7.0.0/mockserver-7.0.0-darwin-aarch64.tar.gz"
        );
    }

    #[test]
    fn test_asset_url_env_override_wins_over_snapshot() {
        let _lock = ENV_MUTEX.lock().unwrap();
        let _guard = EnvGuard::new(
            "MOCKSERVER_BINARY_BASE_URL",
            Some("https://custom-mirror.example.com/bins"),
        );
        let url = asset_url(
            "7.1.0-SNAPSHOT",
            "mockserver-7.1.0-SNAPSHOT-linux-x86_64.tar.gz",
        );
        assert_eq!(
            url,
            "https://custom-mirror.example.com/bins/mockserver-7.1.0-SNAPSHOT-linux-x86_64.tar.gz"
        );
    }

    // -----------------------------------------------------------------------
    // Launcher path
    // -----------------------------------------------------------------------

    #[test]
    fn test_launcher_path_unix() {
        let dir = PathBuf::from("/cache/7.0.0");
        let path = launcher_path(&dir, "mockserver-7.0.0-darwin-aarch64");
        if cfg!(target_os = "windows") {
            assert!(
                path.ends_with("bin/mockserver.bat")
                    || path.ends_with("bin\\mockserver.bat")
            );
        } else {
            assert_eq!(
                path,
                PathBuf::from(
                    "/cache/7.0.0/mockserver-7.0.0-darwin-aarch64/bin/mockserver"
                )
            );
        }
    }

    // -----------------------------------------------------------------------
    // SHA-256 verification
    // -----------------------------------------------------------------------

    #[test]
    fn test_sha256_bytes_known_value() {
        // SHA-256 of "hello\n" = known constant.
        let digest = sha256_bytes(b"hello\n");
        assert_eq!(
            digest,
            "5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03"
        );
    }

    #[test]
    fn test_sha256_file_matches_sha256_bytes() {
        let tmp = TempDir::new("sha256");
        let file = tmp.path().join("test-data.bin");
        let data = b"MockServer binary launcher test content";
        fs::write(&file, data).unwrap();

        let file_digest = sha256_file(&file).unwrap();
        let mem_digest = sha256_bytes(data);
        assert_eq!(file_digest, mem_digest);
    }

    #[test]
    fn test_sha256_verification_correct_checksum() {
        let tmp = TempDir::new("sha-ok");
        let archive_data = b"fake-archive-content-12345";
        let archive_path = tmp.path().join("archive.tar.gz");
        fs::write(&archive_path, archive_data).unwrap();

        let expected = sha256_bytes(archive_data);
        let actual = sha256_file(&archive_path).unwrap();
        assert_eq!(expected, actual, "checksum should match");
    }

    #[test]
    fn test_sha256_verification_wrong_checksum() {
        let tmp = TempDir::new("sha-bad");
        let archive_data = b"correct content";
        let archive_path = tmp.path().join("archive.tar.gz");
        fs::write(&archive_path, archive_data).unwrap();

        let wrong = sha256_bytes(b"DIFFERENT content");
        let actual = sha256_file(&archive_path).unwrap();
        assert_ne!(wrong, actual, "checksums should differ");
    }

    // -----------------------------------------------------------------------
    // MOCKSERVER_SKIP_BINARY_DOWNLOAD behaviour
    // -----------------------------------------------------------------------

    #[test]
    fn test_skip_download_env_fails_when_no_cached_binary() {
        let _lock = ENV_MUTEX.lock().unwrap();
        let tmp = TempDir::new("skip-dl");
        let _guard_cache =
            EnvGuard::new("MOCKSERVER_BINARY_CACHE", Some(tmp.path().to_str().unwrap()));
        let _guard_skip = EnvGuard::new("MOCKSERVER_SKIP_BINARY_DOWNLOAD", Some("1"));
        let _guard_url = EnvGuard::new("MOCKSERVER_BINARY_BASE_URL", None);

        let result = ensure_binary("99.99.99", &EnsureOptions::default());
        assert!(result.is_err());
        let err = result.unwrap_err();
        let msg = format!("{err}");
        assert!(
            msg.contains("MOCKSERVER_SKIP_BINARY_DOWNLOAD"),
            "error should mention env var: {msg}"
        );
    }

    #[test]
    fn test_skip_download_returns_cached_binary() {
        let _lock = ENV_MUTEX.lock().unwrap();
        let tmp = TempDir::new("skip-ok");
        let version = "99.99.99";
        let meta = bundle_base_name(version).unwrap();

        // Pre-seed the cache with a fake launcher.
        let dir = tmp.path().join(version);
        let launcher = launcher_path(&dir, &meta.name);
        fs::create_dir_all(launcher.parent().unwrap()).unwrap();
        fs::write(&launcher, "#!/bin/sh\necho mock").unwrap();

        let _guard_cache =
            EnvGuard::new("MOCKSERVER_BINARY_CACHE", Some(tmp.path().to_str().unwrap()));
        let _guard_skip = EnvGuard::new("MOCKSERVER_SKIP_BINARY_DOWNLOAD", Some("1"));

        let result = ensure_binary(version, &EnsureOptions::default());
        assert!(result.is_ok());
        assert_eq!(result.unwrap(), launcher);
    }

    // -----------------------------------------------------------------------
    // Semver parsing for pruning (H7)
    // -----------------------------------------------------------------------

    #[test]
    fn test_parse_semver_segments_valid() {
        assert_eq!(
            parse_semver_segments("7.0.0"),
            Some((7, 0, 0, String::new()))
        );
        assert_eq!(
            parse_semver_segments("12.345.678"),
            Some((12, 345, 678, String::new()))
        );
        assert_eq!(
            parse_semver_segments("1.2.3-beta.1"),
            Some((1, 2, 3, "-beta.1".to_string()))
        );
    }

    #[test]
    fn test_parse_semver_segments_invalid() {
        assert!(parse_semver_segments("abc").is_none());
        assert!(parse_semver_segments("7.0").is_none());
        assert!(parse_semver_segments("").is_none());
    }

    // -----------------------------------------------------------------------
    // Pre-release comparison (semver ordering)
    // -----------------------------------------------------------------------

    #[test]
    fn test_compare_pre_release_release_is_greater_than_snapshot() {
        // A release (empty pre-release) is NEWER than its -SNAPSHOT.
        assert_eq!(
            compare_pre_release("", "-SNAPSHOT"),
            std::cmp::Ordering::Greater,
            "release (empty) should sort above -SNAPSHOT"
        );
    }

    #[test]
    fn test_compare_pre_release_snapshot_is_less_than_release() {
        assert_eq!(
            compare_pre_release("-SNAPSHOT", ""),
            std::cmp::Ordering::Less,
            "-SNAPSHOT should sort below release (empty)"
        );
    }

    #[test]
    fn test_compare_pre_release_both_empty() {
        assert_eq!(
            compare_pre_release("", ""),
            std::cmp::Ordering::Equal,
        );
    }

    #[test]
    fn test_compare_pre_release_both_pre_releases() {
        // Among two pre-releases, compare lexicographically.
        assert_eq!(
            compare_pre_release("-alpha", "-beta"),
            std::cmp::Ordering::Less,
        );
        assert_eq!(
            compare_pre_release("-beta", "-alpha"),
            std::cmp::Ordering::Greater,
        );
        assert_eq!(
            compare_pre_release("-rc1", "-rc1"),
            std::cmp::Ordering::Equal,
        );
    }

    // -----------------------------------------------------------------------
    // Cache pruning
    // -----------------------------------------------------------------------

    #[test]
    fn test_prune_removes_old_versions_keeps_current_and_one_previous() {
        let tmp = TempDir::new("prune");
        let base = tmp.path();

        // Create three "version" directories.
        let v1 = base.join("1.0.0");
        let v2 = base.join("2.0.0");
        let v3 = base.join("3.0.0");
        fs::create_dir_all(&v1).unwrap();
        fs::write(v1.join("marker"), "v1").unwrap();
        fs::create_dir_all(&v2).unwrap();
        fs::write(v2.join("marker"), "v2").unwrap();
        fs::create_dir_all(&v3).unwrap();
        fs::write(v3.join("marker"), "v3").unwrap();

        // Also create a stale .part file.
        fs::write(base.join("something.part"), "temp").unwrap();

        // Current version is 3.0.0.
        prune_old_versions(base, "3.0.0");

        // 3.0.0 should exist (current).
        assert!(v3.exists(), "current version should be kept");
        // 2.0.0 should exist (most recent previous by semver, not mtime).
        assert!(v2.exists(), "one previous version should be kept");
        // 1.0.0 should be gone.
        assert!(!v1.exists(), "older versions should be pruned");
        // .part file should be gone.
        assert!(
            !base.join("something.part").exists(),
            ".part files should be cleaned"
        );
    }

    #[test]
    fn test_prune_semver_aware_not_lexicographic() {
        // H7: 10.0.0 > 9.0.0 > 2.0.0, not "10.0.0" < "2.0.0" < "9.0.0"
        let tmp = TempDir::new("prune-semver");
        let base = tmp.path();

        fs::create_dir_all(base.join("2.0.0")).unwrap();
        fs::create_dir_all(base.join("9.0.0")).unwrap();
        fs::create_dir_all(base.join("10.0.0")).unwrap();

        // Current is 11.0.0 (not present as dir).
        prune_old_versions(base, "11.0.0");

        // Newest previous by semver is 10.0.0 — it should be kept.
        assert!(
            base.join("10.0.0").exists(),
            "semver-newest previous (10.0.0) should be kept"
        );
        // 9.0.0 and 2.0.0 should be pruned.
        assert!(
            !base.join("9.0.0").exists(),
            "9.0.0 should be pruned"
        );
        assert!(
            !base.join("2.0.0").exists(),
            "2.0.0 should be pruned"
        );
    }

    #[test]
    fn test_prune_with_single_old_version_keeps_it() {
        let tmp = TempDir::new("prune-single");
        let base = tmp.path();

        let v1 = base.join("1.0.0");
        let v2 = base.join("2.0.0");
        fs::create_dir_all(&v1).unwrap();
        fs::write(v1.join("marker"), "v1").unwrap();
        fs::create_dir_all(&v2).unwrap();
        fs::write(v2.join("marker"), "v2").unwrap();

        prune_old_versions(base, "2.0.0");

        assert!(v2.exists(), "current version should be kept");
        assert!(v1.exists(), "single previous version should be kept");
    }

    #[test]
    fn test_prune_cleans_part_files_in_current_version_dir() {
        let tmp = TempDir::new("prune-part");
        let base = tmp.path();

        let current = base.join("5.0.0");
        fs::create_dir_all(&current).unwrap();
        fs::write(current.join("archive.tar.gz.part"), "partial").unwrap();
        fs::write(current.join("real-file.txt"), "keep").unwrap();

        prune_old_versions(base, "5.0.0");

        assert!(
            !current.join("archive.tar.gz.part").exists(),
            ".part inside current should be cleaned"
        );
        assert!(
            current.join("real-file.txt").exists(),
            "non-.part files should be kept"
        );
    }

    #[test]
    fn test_prune_cleans_sha256_temp_files() {
        let tmp = TempDir::new("prune-sha");
        let base = tmp.path();

        fs::create_dir_all(base.join("5.0.0")).unwrap();
        fs::write(base.join("archive.tar.gz.sha256"), "temp").unwrap();

        prune_old_versions(base, "5.0.0");

        assert!(
            !base.join("archive.tar.gz.sha256").exists(),
            ".sha256 files at cache root should be cleaned"
        );
    }

    #[test]
    fn test_prune_empty_cache_is_safe() {
        let tmp = TempDir::new("prune-empty");
        // Just verify it does not panic.
        prune_old_versions(tmp.path(), "1.0.0");
    }

    #[test]
    fn test_prune_nonexistent_cache_dir_is_safe() {
        let nonexistent = PathBuf::from("/tmp/mockserver-test-nonexistent-dir-12345");
        // Should not panic.
        prune_old_versions(&nonexistent, "1.0.0");
    }

    #[test]
    fn test_prune_with_many_old_versions() {
        let tmp = TempDir::new("prune-many");
        let base = tmp.path();

        // Create five old versions and one current.
        for i in 1..=5 {
            let v = base.join(format!("{i}.0.0"));
            fs::create_dir_all(&v).unwrap();
            fs::write(v.join("marker"), format!("v{i}")).unwrap();
        }
        let current = base.join("6.0.0");
        fs::create_dir_all(&current).unwrap();

        prune_old_versions(base, "6.0.0");

        assert!(current.exists(), "current must be kept");
        // Only the newest of the 5 old versions should remain (by semver: 5.0.0).
        assert!(
            base.join("5.0.0").exists(),
            "newest previous version should be kept"
        );
        // All others should be gone.
        for i in 1..=4 {
            let v = base.join(format!("{i}.0.0"));
            assert!(!v.exists(), "version {i}.0.0 should be pruned");
        }
    }

    #[test]
    fn test_prune_keeps_release_over_snapshot() {
        // When current is something else, and both 7.0.0 and 7.0.0-SNAPSHOT
        // exist as previous versions, pruning should keep the release (7.0.0)
        // because it is newer than 7.0.0-SNAPSHOT by semver rules.
        let tmp = TempDir::new("prune-rel-snap");
        let base = tmp.path();

        let release = base.join("7.0.0");
        let snapshot = base.join("7.0.0-SNAPSHOT");
        let current = base.join("8.0.0");
        fs::create_dir_all(&release).unwrap();
        fs::write(release.join("marker"), "release").unwrap();
        fs::create_dir_all(&snapshot).unwrap();
        fs::write(snapshot.join("marker"), "snapshot").unwrap();
        fs::create_dir_all(&current).unwrap();

        prune_old_versions(base, "8.0.0");

        assert!(current.exists(), "current version should be kept");
        assert!(
            release.exists(),
            "release 7.0.0 should be kept (newer than SNAPSHOT)"
        );
        assert!(
            !snapshot.exists(),
            "7.0.0-SNAPSHOT should be pruned (older than release)"
        );
    }

    #[test]
    fn test_prune_release_never_deleted_in_favour_of_snapshot() {
        // Regression test: with only a release and its SNAPSHOT as old versions,
        // the release must be the one retained (maxPrevious=1).
        let tmp = TempDir::new("prune-no-snap-win");
        let base = tmp.path();

        let release = base.join("6.1.0");
        let snapshot = base.join("6.1.0-SNAPSHOT");
        fs::create_dir_all(&release).unwrap();
        fs::create_dir_all(&snapshot).unwrap();

        prune_old_versions(base, "7.0.0");

        assert!(
            release.exists(),
            "release 6.1.0 must be kept, not its SNAPSHOT"
        );
        assert!(
            !snapshot.exists(),
            "6.1.0-SNAPSHOT must be pruned when release exists"
        );
    }

    // -----------------------------------------------------------------------
    // Hermetic download+SHA256+extract tests via local HTTP server (H8)
    // -----------------------------------------------------------------------

    /// Helper: spin up a tiny HTTP server that serves files from a directory.
    /// Returns (base_url, server_guard). The server shuts down on drop.
    struct TestServer {
        base_url: String,
        server: tiny_http::Server,
        serve_dir: PathBuf,
    }

    impl TestServer {
        fn start(serve_dir: PathBuf) -> Self {
            let server =
                tiny_http::Server::http("127.0.0.1:0").expect("failed to start test HTTP server");
            let addr = server.server_addr().to_ip().unwrap();
            let base_url = format!("http://127.0.0.1:{}", addr.port());
            TestServer {
                base_url,
                server,
                serve_dir,
            }
        }

        /// Serve requests in a background thread. Returns a join handle.
        fn serve_in_background(self) -> (String, std::thread::JoinHandle<()>) {
            let base_url = self.base_url.clone();
            let handle = std::thread::spawn(move || {
                // Serve up to 10 requests then exit (enough for any test).
                for _ in 0..10 {
                    let request = match self.server.recv_timeout(
                        std::time::Duration::from_secs(10),
                    ) {
                        Ok(Some(r)) => r,
                        _ => break,
                    };

                    let url_path = request.url().to_string();
                    // Strip leading '/' to get the filename.
                    let file_name = url_path.trim_start_matches('/');
                    let file_path = self.serve_dir.join(file_name);

                    if file_path.exists() {
                        let data = fs::read(&file_path).unwrap();
                        let response = tiny_http::Response::from_data(data);
                        let _ = request.respond(response);
                    } else {
                        let response = tiny_http::Response::from_string("Not Found")
                            .with_status_code(tiny_http::StatusCode(404));
                        let _ = request.respond(response);
                    }
                }
            });
            (base_url, handle)
        }
    }

    /// Build a minimal tar.gz fixture containing `<bundle_name>/bin/mockserver`.
    fn build_fixture_archive(
        work_dir: &Path,
        bundle_name: &str,
    ) -> (PathBuf, String) {
        let archive_root = work_dir.join("build");
        let bin_dir = archive_root.join(bundle_name).join("bin");
        fs::create_dir_all(&bin_dir).unwrap();
        fs::write(bin_dir.join("mockserver"), "#!/bin/sh\necho mock").unwrap();

        let ext = if cfg!(target_os = "windows") {
            "zip"
        } else {
            "tar.gz"
        };
        let archive_file = format!("{bundle_name}.{ext}");
        let archive_path = work_dir.join(&archive_file);

        let status = Command::new("tar")
            .args([
                "-czf",
                &archive_path.to_string_lossy(),
                "-C",
                &archive_root.to_string_lossy(),
                bundle_name,
            ])
            .status()
            .unwrap();
        assert!(status.success(), "tar create should succeed");

        let checksum = sha256_file(&archive_path).unwrap();
        (archive_path, checksum)
    }

    #[test]
    fn test_ensure_binary_download_and_verify_sha256() {
        // Hermetic test: local HTTP server serves the fixture archive + sha256.
        let _lock = ENV_MUTEX.lock().unwrap();
        let tmp = TempDir::new("ensure-http-ok");
        let version = "99.0.0";
        let meta = bundle_base_name(version).unwrap();
        let archive_file = format!("{}.{}", meta.name, meta.ext);

        let serve_dir = tmp.path().join("serve");
        fs::create_dir_all(&serve_dir).unwrap();

        let (archive_path, checksum) = build_fixture_archive(tmp.path(), &meta.name);
        fs::copy(&archive_path, serve_dir.join(&archive_file)).unwrap();
        fs::write(
            serve_dir.join(format!("{archive_file}.sha256")),
            format!("{checksum}  {archive_file}\n"),
        )
        .unwrap();

        let server = TestServer::start(serve_dir);
        let (base_url, _handle) = server.serve_in_background();

        let cache = tmp.path().join("cache");
        let _guard_cache =
            EnvGuard::new("MOCKSERVER_BINARY_CACHE", Some(cache.to_str().unwrap()));
        let _guard_skip = EnvGuard::new("MOCKSERVER_SKIP_BINARY_DOWNLOAD", None);
        let _guard_url = EnvGuard::new("MOCKSERVER_BINARY_BASE_URL", Some(&base_url));

        let result = ensure_binary(version, &EnsureOptions { verbose: true });
        assert!(result.is_ok(), "ensure_binary should succeed: {result:?}");
        let launcher = result.unwrap();
        assert!(launcher.exists(), "launcher should exist after download+extract");
        assert!(
            fs::metadata(&launcher).unwrap().len() > 0,
            "launcher should be non-empty"
        );
    }

    #[test]
    fn test_ensure_binary_sha256_mismatch_fails_and_cleans_up() {
        // Serve a valid archive but a WRONG sha256 checksum.
        let _lock = ENV_MUTEX.lock().unwrap();
        let tmp = TempDir::new("ensure-http-bad-sha");
        let version = "99.0.1";
        let meta = bundle_base_name(version).unwrap();
        let archive_file = format!("{}.{}", meta.name, meta.ext);

        let serve_dir = tmp.path().join("serve");
        fs::create_dir_all(&serve_dir).unwrap();

        let (archive_path, _checksum) = build_fixture_archive(tmp.path(), &meta.name);
        fs::copy(&archive_path, serve_dir.join(&archive_file)).unwrap();
        // Write a WRONG checksum.
        fs::write(
            serve_dir.join(format!("{archive_file}.sha256")),
            "0000000000000000000000000000000000000000000000000000000000000000  file\n",
        )
        .unwrap();

        let server = TestServer::start(serve_dir);
        let (base_url, _handle) = server.serve_in_background();

        let cache = tmp.path().join("cache");
        let _guard_cache =
            EnvGuard::new("MOCKSERVER_BINARY_CACHE", Some(cache.to_str().unwrap()));
        let _guard_skip = EnvGuard::new("MOCKSERVER_SKIP_BINARY_DOWNLOAD", None);
        let _guard_url = EnvGuard::new("MOCKSERVER_BINARY_BASE_URL", Some(&base_url));

        let result = ensure_binary(version, &EnsureOptions::default());
        assert!(result.is_err(), "should fail on checksum mismatch");
        let msg = format!("{}", result.unwrap_err());
        assert!(msg.contains("checksum mismatch"), "error message: {msg}");

        // .part file should have been cleaned up.
        let ver_dir = cache.join(version);
        let part_file = PathBuf::from(format!(
            "{}.part",
            ver_dir.join(&archive_file).display()
        ));
        assert!(
            !part_file.exists(),
            ".part file should be cleaned up on failure"
        );
    }

    #[test]
    fn test_ensure_binary_empty_sha256_fails() {
        // Serve a valid archive but an EMPTY sha256 checksum file.
        let _lock = ENV_MUTEX.lock().unwrap();
        let tmp = TempDir::new("ensure-http-empty-sha");
        let version = "99.0.2";
        let meta = bundle_base_name(version).unwrap();
        let archive_file = format!("{}.{}", meta.name, meta.ext);

        let serve_dir = tmp.path().join("serve");
        fs::create_dir_all(&serve_dir).unwrap();

        let (archive_path, _checksum) = build_fixture_archive(tmp.path(), &meta.name);
        fs::copy(&archive_path, serve_dir.join(&archive_file)).unwrap();
        // Empty checksum file.
        fs::write(
            serve_dir.join(format!("{archive_file}.sha256")),
            "",
        )
        .unwrap();

        let server = TestServer::start(serve_dir);
        let (base_url, _handle) = server.serve_in_background();

        let cache = tmp.path().join("cache");
        let _guard_cache =
            EnvGuard::new("MOCKSERVER_BINARY_CACHE", Some(cache.to_str().unwrap()));
        let _guard_skip = EnvGuard::new("MOCKSERVER_SKIP_BINARY_DOWNLOAD", None);
        let _guard_url = EnvGuard::new("MOCKSERVER_BINARY_BASE_URL", Some(&base_url));

        let result = ensure_binary(version, &EnsureOptions::default());
        assert!(result.is_err(), "should fail on empty checksum");
        let msg = format!("{}", result.unwrap_err());
        assert!(
            msg.contains("empty or unparseable"),
            "error message: {msg}"
        );
    }

    #[test]
    fn test_ensure_binary_missing_bundle_404_gives_actionable_error() {
        // Serve an EMPTY directory so every asset 404s — simulates a release
        // tag that ships no bundle for this version.
        let _lock = ENV_MUTEX.lock().unwrap();
        let tmp = TempDir::new("ensure-http-404");
        let version = "99.9.9";

        let serve_dir = tmp.path().join("serve");
        fs::create_dir_all(&serve_dir).unwrap();

        let server = TestServer::start(serve_dir);
        let (base_url, _handle) = server.serve_in_background();

        let cache = tmp.path().join("cache");
        let _guard_cache =
            EnvGuard::new("MOCKSERVER_BINARY_CACHE", Some(cache.to_str().unwrap()));
        let _guard_skip = EnvGuard::new("MOCKSERVER_SKIP_BINARY_DOWNLOAD", None);
        let _guard_url = EnvGuard::new("MOCKSERVER_BINARY_BASE_URL", Some(&base_url));

        let result = ensure_binary(version, &EnsureOptions::default());
        assert!(result.is_err(), "should fail when no bundle exists");
        let msg = format!("{}", result.unwrap_err());
        assert!(msg.contains(version), "should name the version: {msg}");
        assert!(
            msg.contains("no MockServer release bundle"),
            "should explain no bundle: {msg}"
        );
        assert!(
            msg.contains("docker run mockserver/mockserver:mockserver-99.9.9"),
            "should suggest Docker: {msg}"
        );
        assert!(
            msg.contains("org.mock-server:mockserver-netty:99.9.9"),
            "should suggest Maven Central jar: {msg}"
        );
    }

    // -----------------------------------------------------------------------
    // Version constant
    // -----------------------------------------------------------------------

    #[test]
    fn test_version_is_valid_and_non_empty() {
        // VERSION is derived from Cargo.toml at compile time via env!();
        // validate_version also rejects an empty value.
        assert!(
            validate_version(VERSION).is_ok(),
            "VERSION should be a valid semver: {VERSION}"
        );
    }

    // -----------------------------------------------------------------------
    // Integration test — skipped when no real bundle available
    // -----------------------------------------------------------------------

    #[test]
    #[ignore] // requires a real released binary to be downloadable
    fn test_ensure_binary_live_download() {
        let _lock = ENV_MUTEX.lock().unwrap();
        let tmp = TempDir::new("live-dl");
        let _guard =
            EnvGuard::new("MOCKSERVER_BINARY_CACHE", Some(tmp.path().to_str().unwrap()));
        let _guard_skip = EnvGuard::new("MOCKSERVER_SKIP_BINARY_DOWNLOAD", None);
        let _guard_url = EnvGuard::new("MOCKSERVER_BINARY_BASE_URL", None);

        let result = ensure_binary(
            VERSION,
            &EnsureOptions { verbose: true },
        );
        match result {
            Ok(path) => {
                assert!(path.exists());
                assert!(fs::metadata(&path).unwrap().len() > 0);
            }
            Err(e) => {
                // If the release doesn't exist yet, that's expected.
                eprintln!("Live download failed (expected if no release): {e}");
            }
        }
    }
}

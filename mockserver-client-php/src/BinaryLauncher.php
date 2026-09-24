<?php

declare(strict_types=1);

namespace MockServer;

use MockServer\Exception\BinaryInstallException;

/**
 * On-demand MockServer binary launcher.
 *
 * Downloads the self-contained, JVM-less MockServer bundle (a jlink runtime +
 * the server + a `mockserver` launcher) for the current platform from the
 * GitHub Release, verifies its SHA-256, caches it per-user, and launches it.
 * No Java installation and no Docker required.
 *
 * This is the PHP implementation of the on-demand-binary pattern (a la
 * esbuild / Playwright) for the MockServer client libraries, faithfully
 * mirroring the reference at mockserver-node/downloadBinary.js.
 *
 * Environment overrides:
 *   MOCKSERVER_BINARY_BASE_URL       mirror host for the release assets (corporate / air-gapped)
 *   MOCKSERVER_BINARY_CACHE          cache directory (default: per-OS user cache)
 *   MOCKSERVER_SKIP_BINARY_DOWNLOAD  fail instead of downloading (air-gapped CI with a pre-seeded cache)
 *
 * @example Basic usage
 *   $launcher = new BinaryLauncher();
 *   $handle = $launcher->start(1080);
 *   // ... run tests ...
 *   $handle->stop();
 */
class BinaryLauncher
{
    private const REPO = 'mock-server/mockserver-monorepo';

    /**
     * Default MockServer version matching the repo release.
     * Updated during releases to stay in sync with the published binary.
     */
    private const DEFAULT_VERSION = '8.0.0';

    /**
     * Maximum number of previous version directories to retain during pruning.
     */
    private const MAX_PREVIOUS_VERSIONS = 1;

    /**
     * Strict version pattern: semver core (X.Y.Z) with optional pre-release suffix.
     * No path separators, no '..' sequences.
     */
    private const VERSION_PATTERN = '/^[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.]+)?$/';

    private string $version;

    /** @var callable(string): void */
    private $logger;

    /**
     * @param string|null $version MockServer version (default: DEFAULT_VERSION)
     * @param callable(string): void|null $logger Optional logging callback
     */
    public function __construct(?string $version = null, ?callable $logger = null)
    {
        $v = $version ?? self::DEFAULT_VERSION;
        self::validateVersion($v);
        $this->version = $v;
        $this->logger = $logger ?? static function (string $message): void {
            // silent by default
        };
    }

    // -----------------------------------------------------------------
    // Platform resolution
    // -----------------------------------------------------------------

    /**
     * Map PHP's platform/arch detection to the bundle's {os}-{arch} naming
     * and archive extension.
     *
     * @return array{osName: string, arch: string, ext: string}
     * @throws BinaryInstallException On unsupported platform/architecture
     */
    public static function resolvePlatform(): array
    {
        $osFamily = PHP_OS_FAMILY;
        $machine = php_uname('m');

        $osName = match ($osFamily) {
            'Linux' => 'linux',
            'Darwin' => 'darwin',
            'Windows' => 'windows',
            default => throw new BinaryInstallException("Unsupported platform: {$osFamily}"),
        };

        $ext = $osName === 'windows' ? 'zip' : 'tar.gz';

        $arch = match (true) {
            in_array($machine, ['x86_64', 'amd64'], true) => 'x86_64',
            in_array($machine, ['arm64', 'aarch64'], true) => 'aarch64',
            default => throw new BinaryInstallException("Unsupported architecture: {$machine}"),
        };

        return ['osName' => $osName, 'arch' => $arch, 'ext' => $ext];
    }

    /**
     * Compute the bundle base name for a given version.
     *
     * @return array{name: string, ext: string}
     */
    public static function bundleBaseName(string $version): array
    {
        $platform = self::resolvePlatform();

        return [
            'name' => "mockserver-{$version}-{$platform['osName']}-{$platform['arch']}",
            'ext' => $platform['ext'],
        ];
    }

    // -----------------------------------------------------------------
    // Cache directory
    // -----------------------------------------------------------------

    /**
     * Resolve the cache base directory following the cross-client contract:
     * MOCKSERVER_BINARY_CACHE > (Windows: LOCALAPPDATA > ~/AppData/Local) >
     * (Unix: XDG_CACHE_HOME > ~/.cache) then append /mockserver/binaries.
     */
    public static function cacheDir(): string
    {
        $envCache = self::getenv('MOCKSERVER_BINARY_CACHE');
        if ($envCache !== false && $envCache !== '') {
            return $envCache;
        }

        if (PHP_OS_FAMILY === 'Windows') {
            $localAppData = self::getenv('LOCALAPPDATA');
            $base = ($localAppData !== false && $localAppData !== '')
                ? $localAppData
                : self::homeDir() . DIRECTORY_SEPARATOR . 'AppData' . DIRECTORY_SEPARATOR . 'Local';
        } else {
            $xdgCache = self::getenv('XDG_CACHE_HOME');
            $base = ($xdgCache !== false && $xdgCache !== '')
                ? $xdgCache
                : self::homeDir() . DIRECTORY_SEPARATOR . '.cache';
        }

        return $base . DIRECTORY_SEPARATOR . 'mockserver' . DIRECTORY_SEPARATOR . 'binaries';
    }

    // -----------------------------------------------------------------
    // Asset URL
    // -----------------------------------------------------------------

    /**
     * Build the download URL for a release asset.
     */
    public static function assetUrl(string $version, string $file): string
    {
        $envBase = self::getenv('MOCKSERVER_BINARY_BASE_URL');
        $base = ($envBase !== false && $envBase !== '')
            ? rtrim($envBase, '/')
            : 'https://github.com/' . self::REPO . '/releases/download/mockserver-' . $version;

        return $base . '/' . $file;
    }

    // -----------------------------------------------------------------
    // Launcher path
    // -----------------------------------------------------------------

    /**
     * Compute the expected path to the launcher executable within the cache.
     */
    public static function launcherPath(string $dir, string $bundleName): string
    {
        $executable = PHP_OS_FAMILY === 'Windows' ? 'mockserver.bat' : 'mockserver';

        return $dir . DIRECTORY_SEPARATOR . $bundleName
            . DIRECTORY_SEPARATOR . 'bin' . DIRECTORY_SEPARATOR . $executable;
    }

    // -----------------------------------------------------------------
    // Ensure binary (download + verify + extract + cache)
    // -----------------------------------------------------------------

    /**
     * Ensure the platform bundle is present and return the launcher path,
     * downloading + verifying + extracting + caching on first use.
     *
     * @return string Absolute path to the launcher executable
     * @throws BinaryInstallException
     */
    public function ensureBinary(): string
    {
        $meta = self::bundleBaseName($this->version);
        $baseDir = self::cacheDir();
        $dir = $baseDir . DIRECTORY_SEPARATOR . $this->version;
        $launcher = self::launcherPath($dir, $meta['name']);

        // Reuse cached binary if present and non-empty
        if (is_file($launcher) && filesize($launcher) > 0) {
            $this->log("Using cached binary: {$launcher}");
            return $launcher;
        }

        // Fail-closed when download is disabled
        $skipDownload = self::getenv('MOCKSERVER_SKIP_BINARY_DOWNLOAD');
        if ($skipDownload !== false && $skipDownload !== '') {
            throw new BinaryInstallException(
                "MOCKSERVER_SKIP_BINARY_DOWNLOAD is set but no cached binary at {$launcher}"
            );
        }

        // Create the version directory
        if (!is_dir($dir)) {
            if (!mkdir($dir, 0755, true) && !is_dir($dir)) {
                throw new BinaryInstallException("Failed to create cache directory: {$dir}");
            }
        }

        // H1: assert version directory stays within the resolved cache base (block path traversal)
        $realBase = realpath($baseDir);
        if ($realBase === false) {
            throw new BinaryInstallException("Cache base directory does not exist: {$baseDir}");
        }
        self::assertWithinBase($dir, $realBase);

        $archiveFile = $meta['name'] . '.' . $meta['ext'];
        $archive = $dir . DIRECTORY_SEPARATOR . $archiveFile;
        $partial = $archive . '.part';
        $shaFile = $archive . '.sha256';

        try {
            // Download to a temp file and rename only after the checksum passes,
            // so an interrupted download never leaves a truncated archive.
            $url = self::assetUrl($this->version, $archiveFile);
            $this->log("Downloading {$url}");
            try {
                $this->downloadFile($url, $partial);
            } catch (BinaryInstallException $e) {
                // A 404 means the release tag exists but ships no bundle for this
                // version (or the tag does not exist). Surface actionable guidance
                // instead of an opaque HTTP error.
                if ($e->getCode() === 404) {
                    throw new BinaryInstallException(self::noBundleMessage($this->version));
                }
                throw $e;
            }

            // Verify the published SHA-256 — fail closed on missing/empty/unparseable checksum
            $shaUrl = self::assetUrl($this->version, $archiveFile . '.sha256');
            $this->downloadFile($shaUrl, $shaFile);

            $shaContent = trim(file_get_contents($shaFile) ?: '');
            // The sha256 file may contain "hash  filename" or just "hash"
            $parts = preg_split('/\s+/', $shaContent);
            $expected = $parts[0] ?? '';
            if ($expected === '') {
                throw new BinaryInstallException(
                    "Checksum file for {$meta['name']} is empty or unparseable"
                );
            }

            $actual = $this->sha256File($partial);
            if ($expected !== $actual) {
                throw new BinaryInstallException(
                    "Checksum mismatch for {$meta['name']}: expected {$expected}, got {$actual}"
                );
            }
            $this->log('Checksum verified');

            // Atomic rename — check return value (PHP's rename returns false on failure,
            // e.g. on Windows when destination exists from a prior interrupted install)
            if (!rename($partial, $archive)) {
                // Try removing the stale destination and retry
                @unlink($archive);
                if (!rename($partial, $archive)) {
                    @unlink($partial);
                    throw new BinaryInstallException(
                        "Failed to rename partial download to archive: {$archive} (file may be locked by another process)"
                    );
                }
            }

            // H3: Clean up the .sha256 file on the success path — it has served its
            // purpose (verification) and leaving it creates detritus.
            @unlink($shaFile);
        } catch (\Throwable $e) {
            // H3: clean up BOTH the .part and the .sha256 temp files on any failure
            if (is_file($partial)) {
                @unlink($partial);
            }
            if (is_file($shaFile)) {
                @unlink($shaFile);
            }
            if ($e instanceof BinaryInstallException) {
                throw $e;
            }
            throw new BinaryInstallException(
                "Failed to download MockServer binary: {$e->getMessage()}",
                (int) $e->getCode(),
                $e
            );
        }

        // Extract with the system tar: GNU tar auto-detects gzip; bsdtar
        // (macOS, Windows 10+) also handles .zip — so `tar -xf` covers all.
        $this->log("Extracting {$archive}");
        $this->extractArchive($archive, $dir);

        // Verify the launcher exists and is non-empty
        if (!is_file($launcher) || filesize($launcher) === 0) {
            throw new BinaryInstallException(
                "Launcher missing or empty after extract: {$launcher}"
            );
        }

        // Make the launcher executable on non-Windows
        if (PHP_OS_FAMILY !== 'Windows') {
            chmod($launcher, 0755);
        }

        // Prune old version directories to save disk space
        $this->pruneOldVersions();

        return $launcher;
    }

    // -----------------------------------------------------------------
    // Start / Stop
    // -----------------------------------------------------------------

    /**
     * Download (if needed) and spawn the MockServer binary on the given port.
     *
     * By default stdout/stderr are forwarded to /dev/null (NUL on Windows) to
     * prevent pipe-buffer deadlock when the MockServer process produces more
     * output than the OS pipe buffer can hold (typically 64 KB). Set
     * $captureOutput to true if you need to read stdout/stderr from the handle
     * (you MUST then periodically drain readStdout()/readStderr() to prevent
     * the process from blocking).
     *
     * @param int $port The port to start MockServer on
     * @param array<string> $extraArgs Additional CLI arguments
     * @param bool $captureOutput If true, pipe stdout/stderr for reading (caller must drain)
     * @return BinaryHandle A handle to manage (stop) the running process
     * @throws BinaryInstallException
     */
    public function start(int $port, array $extraArgs = [], bool $captureOutput = false): BinaryHandle
    {
        $launcher = $this->ensureBinary();
        $args = ['-serverPort', (string) $port, ...$extraArgs];

        $this->log("Starting MockServer on port {$port}: {$launcher} " . implode(' ', $args));

        // H4: On Windows, .bat files cannot be executed directly via CreateProcess
        // (which proc_open uses with array form). They require cmd.exe to interpret them.
        $command = [$launcher, ...$args];
        if (PHP_OS_FAMILY === 'Windows' && str_ends_with($launcher, '.bat')) {
            $command = ['cmd', '/c', $launcher, ...$args];
        }

        // H5: By default, forward stdout/stderr to /dev/null (NUL on Windows) to prevent
        // pipe-buffer deadlock. The Java MockServer process can produce significant output
        // (startup logs, verbose traffic logging) that would fill the OS pipe buffer
        // (~64 KB) and silently hang the server if not drained.
        $devNull = PHP_OS_FAMILY === 'Windows' ? 'NUL' : '/dev/null';

        if ($captureOutput) {
            $descriptorSpec = [
                0 => ['pipe', 'r'],       // stdin
                1 => ['pipe', 'w'],       // stdout
                2 => ['pipe', 'w'],       // stderr
            ];
        } else {
            $descriptorSpec = [
                0 => ['pipe', 'r'],                    // stdin
                1 => ['file', $devNull, 'w'],          // stdout -> /dev/null
                2 => ['file', $devNull, 'w'],          // stderr -> /dev/null
            ];
        }

        $process = proc_open(
            $command,
            $descriptorSpec,
            $pipes,
        );

        if (!is_resource($process)) {
            throw new BinaryInstallException(
                "Failed to start MockServer process: {$launcher}"
            );
        }

        // Close stdin — the server doesn't need it
        fclose($pipes[0]);

        $stdout = null;
        $stderr = null;
        if ($captureOutput) {
            // Set stdout and stderr to non-blocking so reads don't block indefinitely.
            // WARNING: the caller MUST periodically drain readStdout()/readStderr()
            // to prevent pipe-buffer deadlock.
            stream_set_blocking($pipes[1], false);
            stream_set_blocking($pipes[2], false);
            $stdout = $pipes[1];
            $stderr = $pipes[2];
        }

        return new BinaryHandle($process, $stdout, $stderr, $port);
    }

    /**
     * Get the version this launcher targets.
     */
    public function getVersion(): string
    {
        return $this->version;
    }

    // -----------------------------------------------------------------
    // Versioned cache pruning
    // -----------------------------------------------------------------

    /**
     * After a successful install of the current version, remove other version
     * directories under the cache base. Keeps the current version and at most
     * one previous version directory. Also removes leftover .part temp files.
     *
     * Safe: never deletes outside the cache dir; tolerates concurrent runs.
     */
    public function pruneOldVersions(): void
    {
        $baseDir = self::cacheDir();
        if (!is_dir($baseDir)) {
            return;
        }

        // Ensure the base dir path is real so we can validate children are inside it
        $realBase = realpath($baseDir);
        if ($realBase === false) {
            return;
        }

        $entries = @scandir($baseDir);
        if ($entries === false) {
            return;
        }

        $versionDirs = [];
        foreach ($entries as $entry) {
            if ($entry === '.' || $entry === '..') {
                continue;
            }

            $fullPath = $baseDir . DIRECTORY_SEPARATOR . $entry;

            // Clean up stray .part and .sha256 temp files at the base level
            if (is_file($fullPath) && (str_ends_with($entry, '.part') || str_ends_with($entry, '.sha256'))) {
                $realFile = realpath($fullPath);
                if ($realFile !== false && str_starts_with($realFile, $realBase . DIRECTORY_SEPARATOR)) {
                    @unlink($fullPath);
                }
                continue;
            }

            // Collect version directories (skip the current version)
            if (is_dir($fullPath) && $entry !== $this->version) {
                $versionDirs[$entry] = $fullPath;
            }
        }

        // Clean up leftover .part and .sha256 files inside ALL version directories
        // (including the current version and kept previous versions). This is where
        // interrupted downloads actually leave detritus.
        $this->cleanTempFilesInDir(
            $baseDir . DIRECTORY_SEPARATOR . $this->version,
            $realBase
        );
        foreach ($versionDirs as $versionPath) {
            $this->cleanTempFilesInDir($versionPath, $realBase);
        }

        if (count($versionDirs) <= self::MAX_PREVIOUS_VERSIONS) {
            return;
        }

        // Sort version strings descending (semver-aware via version_compare) so we keep the most recent previous
        uksort($versionDirs, static function (string $a, string $b): int {
            return version_compare($b, $a);
        });

        $kept = 0;
        foreach ($versionDirs as $versionName => $versionPath) {
            if ($kept < self::MAX_PREVIOUS_VERSIONS) {
                $kept++;
                continue;
            }

            // Safety: ensure the path is really inside the cache dir
            $realPath = realpath($versionPath);
            if ($realPath === false || !str_starts_with($realPath, $realBase . DIRECTORY_SEPARATOR)) {
                continue;
            }

            $this->log("Pruning old version cache: {$versionName}");
            self::removeDirectory($versionPath);
        }
    }

    // -----------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------

    /**
     * Build a clear, actionable error message for a missing release bundle.
     *
     * Explains that no downloadable bundle exists for the version and lists the
     * concrete alternatives. The wording is kept consistent across all client
     * languages.
     */
    private static function noBundleMessage(string $version): string
    {
        return "no MockServer release bundle is published for version {$version} "
            . "(no downloadable asset at the GitHub release tag 'mockserver-{$version}'). "
            . 'Use a MockServer version that ships self-contained bundles, '
            . "or run MockServer via Docker (docker run mockserver/mockserver:mockserver-{$version}), "
            . "or use the Maven Central jar (org.mock-server:mockserver-netty:{$version}).";
    }

    /**
     * Validate that a version string is safe (no path traversal, strict semver-ish pattern).
     *
     * @throws BinaryInstallException If the version is invalid
     */
    private static function validateVersion(string $version): void
    {
        if (!preg_match(self::VERSION_PATTERN, $version)) {
            throw new BinaryInstallException(
                "Invalid version string: '{$version}' — must match X.Y.Z with optional pre-release suffix"
            );
        }
        if (str_contains($version, '..') || str_contains($version, '/') || str_contains($version, '\\')) {
            throw new BinaryInstallException(
                "Invalid version string: '{$version}' — must not contain path separators or '..'"
            );
        }
    }

    /**
     * Assert that a resolved path is within the expected base directory.
     * Uses realpath when the path exists, otherwise walks parent directories.
     *
     * @throws BinaryInstallException If the path escapes the base directory
     */
    private static function assertWithinBase(string $path, string $resolvedBase): void
    {
        // Try realpath first (works when path exists)
        $real = realpath($path);
        if ($real !== false) {
            if ($real !== $resolvedBase && !str_starts_with($real, $resolvedBase . DIRECTORY_SEPARATOR)) {
                throw new BinaryInstallException(
                    "Path traversal detected: {$path} resolves outside the cache base"
                );
            }
            return;
        }

        // For not-yet-existing paths, resolve the closest existing parent
        $parent = dirname($path);
        $parentReal = realpath($parent);
        if ($parentReal === false) {
            // Walk up until we find an existing ancestor
            $current = $parent;
            while ($current !== dirname($current)) {
                $current = dirname($current);
                $parentReal = realpath($current);
                if ($parentReal !== false) {
                    break;
                }
            }
        }
        if ($parentReal !== false) {
            if ($parentReal !== $resolvedBase && !str_starts_with($parentReal, $resolvedBase . DIRECTORY_SEPARATOR)) {
                throw new BinaryInstallException(
                    "Path traversal detected: {$path} resolves outside the cache base"
                );
            }
        }
    }

    /**
     * Download a URL to a local file.
     *
     * @throws BinaryInstallException
     */
    protected function downloadFile(string $url, string $dest): void
    {
        $contextOptions = [];

        // Honour proxy env vars
        $proxy = null;
        if (str_starts_with($url, 'https://')) {
            $proxy = self::getenv('HTTPS_PROXY') ?: self::getenv('https_proxy') ?: null;
        }
        if ($proxy === null || $proxy === false) {
            $proxy = self::getenv('HTTP_PROXY') ?: self::getenv('http_proxy') ?: null;
        }
        if ($proxy !== null && $proxy !== false && $proxy !== '') {
            $contextOptions['http']['proxy'] = $proxy;
            $contextOptions['http']['request_fulluri'] = true;
        }

        // Honour custom CA bundle
        $caBundle = self::getenv('CURL_CA_BUNDLE')
            ?: self::getenv('SSL_CERT_FILE')
            ?: null;
        if ($caBundle !== null && $caBundle !== false && $caBundle !== '') {
            $contextOptions['ssl']['cafile'] = $caBundle;
        }

        // Follow redirects (GitHub releases redirect to S3)
        $contextOptions['http']['follow_location'] = true;
        $contextOptions['http']['max_redirects'] = 10;
        // H6: set connect + read timeout to avoid indefinite hangs (seconds)
        $contextOptions['http']['timeout'] = 300;

        $context = stream_context_create($contextOptions);

        // Capture $http_response_header (magic variable set by fopen with http wrapper)
        $source = @fopen($url, 'rb', false, $context);
        if ($source === false) {
            // $http_response_header is set if the connection succeeded but returned an error status
            $status = isset($http_response_header[0]) ? $http_response_header[0] : '(connection failed)';
            // Pass the HTTP status code (if any) as the exception code so callers
            // can distinguish a 404 (no bundle published) from other errors.
            $code = 0;
            if (isset($http_response_header[0]) && preg_match('/\s([4-5]\d{2})\s/', $http_response_header[0], $m)) {
                $code = (int) $m[1];
            }
            throw new BinaryInstallException("Failed to download {$url}: {$status}", $code);
        }

        // Check HTTP status code even on successful fopen (some configurations may not fail on 4xx/5xx)
        if (isset($http_response_header[0])) {
            $statusLine = $http_response_header[0];
            if (preg_match('/\s([4-5]\d{2})\s/', $statusLine, $m)) {
                fclose($source);
                // Pass the HTTP status code as the exception code so callers can
                // distinguish a 404 (no bundle published) from other errors.
                throw new BinaryInstallException(
                    "Failed to download {$url}: {$statusLine}",
                    (int) $m[1]
                );
            }
        }

        $destStream = @fopen($dest, 'wb');
        if ($destStream === false) {
            fclose($source);
            throw new BinaryInstallException("Failed to open destination file: {$dest}");
        }

        try {
            $written = stream_copy_to_stream($source, $destStream);
            if ($written === false) {
                throw new BinaryInstallException("Failed to write to: {$dest}");
            }
        } finally {
            fclose($destStream);
            fclose($source);
        }
    }

    /**
     * Compute the SHA-256 hex digest of a file.
     */
    protected function sha256File(string $path): string
    {
        $hash = hash_file('sha256', $path);
        if ($hash === false) {
            throw new BinaryInstallException("Failed to compute SHA-256 of: {$path}");
        }

        return $hash;
    }

    /**
     * Extract an archive using the system tar command.
     *
     * H3: Guards against path-traversal attacks in malicious archives.
     * Before extraction, lists all archive members and fails closed if any
     * resolved path escapes the target directory (e.g. entries containing
     * '../' sequences).
     *
     * @throws BinaryInstallException
     */
    protected function extractArchive(string $archive, string $targetDir): void
    {
        // Step 1: Pre-scan archive members for path-escape attempts.
        // List all entries and verify none would escape the target directory.
        $listCommand = sprintf(
            'tar -tf %s 2>&1',
            escapeshellarg($archive)
        );

        $members = [];
        $listExitCode = 0;
        exec($listCommand, $members, $listExitCode);

        if ($listExitCode !== 0) {
            throw new BinaryInstallException(
                "Cannot list archive members (tar exit {$listExitCode}): " . implode("\n", $members)
            );
        }

        $realTarget = realpath($targetDir);
        if ($realTarget === false) {
            throw new BinaryInstallException(
                "Target directory does not exist: {$targetDir}"
            );
        }

        foreach ($members as $member) {
            $member = trim($member);
            if ($member === '') {
                continue;
            }

            // Reject any member containing '..' path components
            // (normalised check: split on / and look for literal '..' segments)
            $segments = explode('/', str_replace('\\', '/', $member));
            foreach ($segments as $segment) {
                if ($segment === '..') {
                    throw new BinaryInstallException(
                        "Archive path traversal detected: member '{$member}' contains '..' — aborting extraction"
                    );
                }
            }

            // Reject absolute paths (after stripping leading /)
            $cleaned = ltrim($member, '/');
            $resolvedMember = $realTarget . DIRECTORY_SEPARATOR . $cleaned;

            // Normalise without requiring the file to exist: resolve the parent
            // directory and check the resulting path prefix
            $parentDir = dirname($resolvedMember);
            // The parent may not exist yet (nested dirs), but it must be under targetDir
            // when fully expanded. We use a simple string check after normalisation.
            $normalisedParent = self::normalisePath($parentDir);
            $normalisedTarget = self::normalisePath($realTarget);
            if (!str_starts_with($normalisedParent . DIRECTORY_SEPARATOR, $normalisedTarget . DIRECTORY_SEPARATOR)
                && $normalisedParent !== $normalisedTarget) {
                throw new BinaryInstallException(
                    "Archive path traversal detected: member '{$member}' resolves outside target directory"
                );
            }
        }

        // Step 2: Extract (safe — all members verified)
        $command = sprintf(
            'tar -xf %s -C %s 2>&1',
            escapeshellarg($archive),
            escapeshellarg($targetDir)
        );

        $output = [];
        $exitCode = 0;
        exec($command, $output, $exitCode);

        if ($exitCode !== 0) {
            throw new BinaryInstallException(
                "Extraction failed (tar exit {$exitCode}): " . implode("\n", $output)
            );
        }
    }

    /**
     * Normalise a path by resolving '.' and '..' segments without requiring the path to exist.
     * This is a pure string operation — no filesystem access.
     */
    private static function normalisePath(string $path): string
    {
        $isAbsolute = str_starts_with($path, DIRECTORY_SEPARATOR);
        $parts = explode(DIRECTORY_SEPARATOR, $path);
        $resolved = [];

        foreach ($parts as $part) {
            if ($part === '' || $part === '.') {
                continue;
            }
            if ($part === '..') {
                if (count($resolved) > 0 && end($resolved) !== '..') {
                    array_pop($resolved);
                } elseif (!$isAbsolute) {
                    $resolved[] = '..';
                }
                continue;
            }
            $resolved[] = $part;
        }

        $result = implode(DIRECTORY_SEPARATOR, $resolved);

        return $isAbsolute ? DIRECTORY_SEPARATOR . $result : $result;
    }

    /**
     * Get an environment variable (wrapped for testability).
     *
     * @return string|false
     */
    protected static function getenv(string $name): string|false
    {
        return \getenv($name);
    }

    /**
     * Get the user's home directory.
     */
    private static function homeDir(): string
    {
        $home = \getenv('HOME');
        if ($home !== false && $home !== '') {
            return $home;
        }

        // Windows fallback
        $homeDrive = \getenv('HOMEDRIVE');
        $homePath = \getenv('HOMEPATH');
        if ($homeDrive !== false && $homePath !== false) {
            return $homeDrive . $homePath;
        }

        $userProfile = \getenv('USERPROFILE');
        if ($userProfile !== false && $userProfile !== '') {
            return $userProfile;
        }

        return '~';
    }

    /**
     * Log a message via the configured logger callback.
     */
    private function log(string $message): void
    {
        ($this->logger)($message);
    }

    /**
     * Remove leftover .part and .sha256 temp files inside a version directory.
     * This is where interrupted downloads actually leave detritus.
     */
    private function cleanTempFilesInDir(string $dir, string $resolvedBase): void
    {
        if (!is_dir($dir)) {
            return;
        }

        $entries = @scandir($dir);
        if ($entries === false) {
            return;
        }

        foreach ($entries as $entry) {
            if ($entry === '.' || $entry === '..') {
                continue;
            }
            $fullPath = $dir . DIRECTORY_SEPARATOR . $entry;
            if (is_file($fullPath) && (str_ends_with($entry, '.part') || str_ends_with($entry, '.sha256'))) {
                $realFile = realpath($fullPath);
                if ($realFile !== false && str_starts_with($realFile, $resolvedBase . DIRECTORY_SEPARATOR)) {
                    @unlink($fullPath);
                }
            }
        }
    }

    /**
     * Recursively remove a directory. Best-effort, tolerates concurrent modification.
     */
    private static function removeDirectory(string $dir): void
    {
        if (!is_dir($dir)) {
            return;
        }

        $entries = @scandir($dir);
        if ($entries === false) {
            return;
        }

        foreach ($entries as $entry) {
            if ($entry === '.' || $entry === '..') {
                continue;
            }
            $path = $dir . DIRECTORY_SEPARATOR . $entry;
            if (is_dir($path)) {
                self::removeDirectory($path);
            } else {
                @unlink($path);
            }
        }

        @rmdir($dir);
    }
}

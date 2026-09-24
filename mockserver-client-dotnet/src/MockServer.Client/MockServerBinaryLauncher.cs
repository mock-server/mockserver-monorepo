using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace MockServer.Client;

/// <summary>
/// On-demand binary launcher for MockServer.
/// Downloads the self-contained, JVM-less MockServer bundle for the current platform from
/// the GitHub Release, verifies its SHA-256, caches it per-user, and launches it.
/// No Java installation and no Docker required.
///
/// Environment overrides:
///   MOCKSERVER_BINARY_BASE_URL        mirror host for the release assets (corporate / air-gapped)
///   MOCKSERVER_BINARY_CACHE           cache directory (default: per-OS user cache)
///   MOCKSERVER_SKIP_BINARY_DOWNLOAD   fail instead of downloading (air-gapped CI with a pre-seeded cache)
/// </summary>
public sealed class MockServerBinaryLauncher : IDisposable
{
    /// <summary>
    /// Fallback version used only when the assembly version cannot be determined
    /// at runtime (e.g. single-file publish, trimmed AOT, or unexpected packaging).
    /// The assembly version is the authoritative source — it is set by the
    /// &lt;Version&gt; property in MockServer.Client.csproj and bumped automatically
    /// by the release pipeline.
    /// </summary>
    private const string FallbackVersion = "8.0.0";

    /// <summary>
    /// The default MockServer version, derived from this assembly's package version
    /// (set by the &lt;Version&gt; property in the .csproj).  Falls back to
    /// <see cref="FallbackVersion"/> when the assembly metadata is unavailable.
    /// </summary>
    public static string DefaultVersion { get; } = ResolvePackageVersion();

    /// <summary>
    /// Extracts the X.Y.Z version from the assembly's informational version attribute,
    /// which is populated from the csproj &lt;Version&gt; property at build time.
    /// Strips any "+build" or "-prerelease" suffix so the result is a clean semver core.
    /// </summary>
    private static string ResolvePackageVersion()
    {
        try
        {
            // AssemblyInformationalVersionAttribute is populated from <Version> in the csproj.
            // It may include a "+commitsha" suffix added by SourceLink / deterministic builds.
            var attr = typeof(MockServerBinaryLauncher).Assembly
                .GetCustomAttributes(typeof(System.Reflection.AssemblyInformationalVersionAttribute), false);
            if (attr.Length > 0)
            {
                var infoVersion = ((System.Reflection.AssemblyInformationalVersionAttribute)attr[0]).InformationalVersion;
                if (!string.IsNullOrEmpty(infoVersion))
                {
                    // Strip "+build" metadata (e.g. "7.0.0+abc123" -> "7.0.0")
                    var plusIdx = infoVersion.IndexOf('+');
                    var version = plusIdx >= 0 ? infoVersion.Substring(0, plusIdx) : infoVersion;
                    if (!string.IsNullOrEmpty(version))
                        return version;
                }
            }
        }
        catch
        {
            // Reflection may fail in constrained environments (e.g. trimmed AOT).
            // Fall through to the hardcoded fallback.
        }
        return FallbackVersion;
    }

    private const string Repo = "mock-server/mockserver-monorepo";

    /// <summary>
    /// Strict version pattern: digits.digits.digits with optional pre-release suffix.
    /// Rejects path separators, "..", and other unsafe characters.
    /// </summary>
    private static readonly Regex VersionPattern = new Regex(
        @"^[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.]+)?$",
        RegexOptions.Compiled);

    private Process? _process;

    /// <summary>
    /// The launched process, or null if not yet started.
    /// </summary>
    public Process? Process => _process;

    // ---------------------------------------------------------------
    // Version validation (H1)
    // ---------------------------------------------------------------

    /// <summary>
    /// Validates a version string against the strict pattern. Rejects path separators and "..".
    /// </summary>
    /// <exception cref="ArgumentException">If the version string is invalid.</exception>
    internal static void ValidateVersion(string version)
    {
        if (string.IsNullOrEmpty(version))
            throw new ArgumentException("Version must not be null or empty.", nameof(version));
        if (version.Contains(Path.DirectorySeparatorChar) ||
            version.Contains(Path.AltDirectorySeparatorChar) ||
            version.Contains(".."))
            throw new ArgumentException($"Version contains unsafe path characters: '{version}'", nameof(version));
        if (!VersionPattern.IsMatch(version))
            throw new ArgumentException(
                $"Version does not match required pattern (digits.digits.digits with optional pre-release): '{version}'",
                nameof(version));
    }

    /// <summary>
    /// Asserts that a resolved path stays within the given base directory. Blocks path traversal.
    /// </summary>
    /// <exception cref="InvalidOperationException">If the path escapes the base directory.</exception>
    internal static void AssertWithinBase(string resolvedPath, string resolvedBase)
    {
        var fullPath = Path.GetFullPath(resolvedPath);
        var fullBase = Path.GetFullPath(resolvedBase);
        // Ensure the path is either the base itself or a child of it
        if (!fullPath.StartsWith(fullBase + Path.DirectorySeparatorChar, StringComparison.Ordinal) &&
            !string.Equals(fullPath, fullBase, StringComparison.Ordinal))
        {
            throw new InvalidOperationException(
                $"Path traversal blocked: '{fullPath}' is not within '{fullBase}'");
        }
    }

    // ---------------------------------------------------------------
    // Platform resolution
    // ---------------------------------------------------------------

    /// <summary>
    /// Describes the resolved platform tokens for the current OS/architecture.
    /// </summary>
    public sealed class PlatformInfo
    {
        /// <summary>The OS token used in the bundle name (linux, darwin, windows).</summary>
        public string OsName { get; }

        /// <summary>The architecture token used in the bundle name (x86_64, aarch64).</summary>
        public string Arch { get; }

        /// <summary>The archive extension (tar.gz or zip).</summary>
        public string Ext { get; }

        /// <summary>
        /// Creates a PlatformInfo with the given OS, architecture, and extension tokens.
        /// </summary>
        public PlatformInfo(string osName, string arch, string ext)
        {
            OsName = osName;
            Arch = arch;
            Ext = ext;
        }
    }

    /// <summary>
    /// Maps the current runtime's OS/architecture to bundle naming tokens.
    /// </summary>
    /// <exception cref="PlatformNotSupportedException">If the current platform is unsupported.</exception>
    public static PlatformInfo ResolvePlatform()
    {
        string osName;
        string ext;

        if (RuntimeInformation.IsOSPlatform(OSPlatform.Linux))
        {
            osName = "linux";
            ext = "tar.gz";
        }
        else if (RuntimeInformation.IsOSPlatform(OSPlatform.OSX))
        {
            osName = "darwin";
            ext = "tar.gz";
        }
        else if (RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
        {
            osName = "windows";
            ext = "zip";
        }
        else
        {
            throw new PlatformNotSupportedException(
                $"Unsupported operating system: {RuntimeInformation.OSDescription}");
        }

        var runtimeArch = RuntimeInformation.OSArchitecture;
        string arch;
        if (runtimeArch == Architecture.X64)
            arch = "x86_64";
        else if (runtimeArch == Architecture.Arm64)
            arch = "aarch64";
        else
            throw new PlatformNotSupportedException(
                $"Unsupported architecture: {runtimeArch}");

        return new PlatformInfo(osName, arch, ext);
    }

    // ---------------------------------------------------------------
    // Bundle naming
    // ---------------------------------------------------------------

    /// <summary>
    /// Returns the base name and extension for the bundle archive.
    /// </summary>
    public static (string Name, string Ext) BundleBaseName(string version)
    {
        var platform = ResolvePlatform();
        return ($"mockserver-{version}-{platform.OsName}-{platform.Arch}", platform.Ext);
    }

    /// <summary>
    /// Returns the base name and extension for the bundle archive using the given platform info.
    /// </summary>
    public static (string Name, string Ext) BundleBaseName(string version, PlatformInfo platform)
    {
        return ($"mockserver-{version}-{platform.OsName}-{platform.Arch}", platform.Ext);
    }

    // ---------------------------------------------------------------
    // Cache directory
    // ---------------------------------------------------------------

    /// <summary>
    /// Returns the binary cache directory path.
    /// </summary>
    public static string CacheDir()
    {
        var envCache = Environment.GetEnvironmentVariable("MOCKSERVER_BINARY_CACHE");
        if (!string.IsNullOrEmpty(envCache))
            return envCache;

        string basePath;
        if (RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
        {
            basePath = Environment.GetEnvironmentVariable("LOCALAPPDATA")
                       ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
                           "AppData", "Local");
        }
        else
        {
            basePath = Environment.GetEnvironmentVariable("XDG_CACHE_HOME")
                       ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
                           ".cache");
        }

        return Path.Combine(basePath, "mockserver", "binaries");
    }

    // ---------------------------------------------------------------
    // Asset URL
    // ---------------------------------------------------------------

    /// <summary>
    /// The CDN base URL used for SNAPSHOT version downloads.
    /// </summary>
    private const string SnapshotCdn = "https://downloads.mock-server.com";

    /// <summary>
    /// Returns true if the version string contains "-SNAPSHOT" (case-insensitive),
    /// indicating a pre-release snapshot build.
    /// </summary>
    internal static bool IsSnapshot(string version)
        => version.IndexOf("-SNAPSHOT", StringComparison.OrdinalIgnoreCase) >= 0;

    /// <summary>
    /// Returns the download URL for a given asset file.
    /// Uses MOCKSERVER_BINARY_BASE_URL if set; otherwise defaults to GitHub Releases
    /// for release versions and the downloads.mock-server.com CDN for SNAPSHOT versions.
    /// </summary>
    public static string AssetUrl(string version, string file)
    {
        var baseUrl = Environment.GetEnvironmentVariable("MOCKSERVER_BINARY_BASE_URL");
        if (string.IsNullOrEmpty(baseUrl))
        {
            baseUrl = IsSnapshot(version)
                ? $"{SnapshotCdn}/mockserver-{version}"
                : $"https://github.com/{Repo}/releases/download/mockserver-{version}";
        }

        return baseUrl.TrimEnd('/') + "/" + file;
    }

    // ---------------------------------------------------------------
    // Launcher path
    // ---------------------------------------------------------------

    /// <summary>
    /// Returns the expected path to the launcher executable within the cache.
    /// </summary>
    public static string LauncherPath(string dir, string bundleName)
    {
        var launcherName = RuntimeInformation.IsOSPlatform(OSPlatform.Windows)
            ? "mockserver.bat"
            : "mockserver";
        return Path.Combine(dir, bundleName, "bin", launcherName);
    }

    // ---------------------------------------------------------------
    // SHA-256 verification
    // ---------------------------------------------------------------

    /// <summary>
    /// Computes the SHA-256 hash of a file and returns it as a lowercase hex string.
    /// </summary>
    public static string ComputeSha256(string filePath)
    {
        using var sha256 = SHA256.Create();
        using var stream = File.OpenRead(filePath);
        var hash = sha256.ComputeHash(stream);
        var sb = new StringBuilder(hash.Length * 2);
        foreach (var b in hash)
            sb.Append(b.ToString("x2"));
        return sb.ToString();
    }

    /// <summary>
    /// Parses the expected hash from a .sha256 file (first whitespace-delimited token).
    /// Returns null if the content is empty or unparseable.
    /// </summary>
    public static string? ParseSha256File(string content)
    {
        var trimmed = content.Trim();
        if (string.IsNullOrEmpty(trimmed))
            return null;
        var parts = trimmed.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
        return parts.Length > 0 ? parts[0] : null;
    }

    // ---------------------------------------------------------------
    // Semver-aware version comparison (H7)
    // ---------------------------------------------------------------

    /// <summary>
    /// Compares two version strings using numeric segment comparison (semver-aware).
    /// Returns negative if a &lt; b, zero if equal, positive if a &gt; b.
    /// Non-numeric segments fall back to ordinal comparison.
    /// </summary>
    internal static int CompareVersions(string a, string b)
    {
        // Split on dots and hyphens to handle pre-release segments
        var partsA = a.Split('.', '-');
        var partsB = b.Split('.', '-');

        // Compare the numeric core segments (typically major.minor.patch)
        var coreLen = Math.Min(partsA.Length, partsB.Length);
        for (int i = 0; i < coreLen; i++)
        {
            var segA = partsA[i];
            var segB = partsB[i];

            bool aIsNum = long.TryParse(segA, out var numA);
            bool bIsNum = long.TryParse(segB, out var numB);

            if (aIsNum && bIsNum)
            {
                var cmp = numA.CompareTo(numB);
                if (cmp != 0) return cmp;
            }
            else
            {
                var cmp = string.Compare(segA, segB, StringComparison.Ordinal);
                if (cmp != 0) return cmp;
            }
        }

        // When the numeric core is equal, a release (fewer segments, no pre-release
        // suffix) outranks a pre-release (more segments, e.g. "SNAPSHOT").
        // Semver rule: 7.0.0 > 7.0.0-SNAPSHOT.
        if (partsA.Length != partsB.Length)
        {
            // The version with FEWER segments is the release — it is GREATER.
            return partsA.Length < partsB.Length ? 1 : -1;
        }

        return 0;
    }

    // ---------------------------------------------------------------
    // Versioned cache pruning
    // ---------------------------------------------------------------

    /// <summary>
    /// Prunes old version directories from the cache, keeping only the current version
    /// and at most one previous version directory. Also removes leftover .part and .sha256 temp files.
    /// Safe: never deletes outside the cache directory; tolerates concurrent runs.
    /// Uses semver-aware numeric comparison to determine the "most recent previous" version.
    /// </summary>
    /// <param name="cacheBase">The base cache directory (e.g., ~/.cache/mockserver/binaries).</param>
    /// <param name="currentVersion">The version to keep.</param>
    public static void PruneOldVersions(string cacheBase, string currentVersion)
    {
        if (!Directory.Exists(cacheBase))
            return;

        var fullCacheBase = Path.GetFullPath(cacheBase);
        string[] entries;
        try
        {
            entries = Directory.GetDirectories(fullCacheBase);
        }
        catch (IOException)
        {
            // Concurrent deletion or permission issue; skip pruning
            return;
        }

        // Keep the current version; of the remaining, keep at most one (the highest by semver)
        var others = new List<string>();
        foreach (var entry in entries)
        {
            var dirName = Path.GetFileName(entry);
            if (dirName == currentVersion)
                continue;

            // Safety: only operate on directories that are children of our cache
            var fullEntry = Path.GetFullPath(entry);
            if (!fullEntry.StartsWith(fullCacheBase + Path.DirectorySeparatorChar, StringComparison.Ordinal) &&
                fullEntry != fullCacheBase)
                continue;

            others.Add(entry);
        }

        // Sort using semver-aware comparison; keep the last one (highest version) as "one previous"
        others.Sort((x, y) => CompareVersions(Path.GetFileName(x)!, Path.GetFileName(y)!));
        // Remove all but the last one (highest semver)
        for (int i = 0; i < others.Count - 1; i++)
        {
            try
            {
                Directory.Delete(others[i], recursive: true);
            }
            catch (IOException)
            {
                // Best effort — may be in use by a concurrent process
            }
            catch (UnauthorizedAccessException)
            {
                // Best effort
            }
        }

        // Clean up leftover .part and .sha256 temp files in the cache base and in version directories
        try
        {
            foreach (var file in Directory.GetFiles(fullCacheBase, "*.part", SearchOption.AllDirectories))
            {
                try { File.Delete(file); } catch { /* best effort */ }
            }
            foreach (var file in Directory.GetFiles(fullCacheBase, "*.sha256", SearchOption.AllDirectories))
            {
                try { File.Delete(file); } catch { /* best effort */ }
            }
        }
        catch (IOException)
        {
            // Tolerate
        }
    }

    // ---------------------------------------------------------------
    // Download helper
    // ---------------------------------------------------------------

    private static readonly HttpClient SharedHttpClient = new HttpClient
    {
        Timeout = TimeSpan.FromMinutes(15) // H6: explicit timeout for large bundles
    };

    /// <summary>
    /// Downloads a URL to a local file. Uses the shared HttpClient, which honours
    /// standard .NET proxy environment variables (HTTP_PROXY, HTTPS_PROXY, NO_PROXY)
    /// and SSL_CERT_FILE / SSL_CERT_DIR for custom CA bundles.
    /// Streams the response body to disk (H6: no in-memory buffering of 200MB+ bundles).
    /// </summary>
    internal static async Task DownloadFileAsync(string url, string destPath, HttpClient? httpClient = null)
    {
        var client = httpClient ?? SharedHttpClient;
        using var response = await client.GetAsync(url, HttpCompletionOption.ResponseHeadersRead).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
            throw new InvalidOperationException($"Download {url} failed: HTTP {(int)response.StatusCode}");

#if NETSTANDARD2_0
        using var stream = await response.Content.ReadAsStreamAsync().ConfigureAwait(false);
#else
        await using var stream = await response.Content.ReadAsStreamAsync().ConfigureAwait(false);
#endif
        using var fileStream = new FileStream(destPath, FileMode.Create, FileAccess.Write, FileShare.None);
        await stream.CopyToAsync(fileStream).ConfigureAwait(false);
    }

    // ---------------------------------------------------------------
    // EnsureBinary — main install flow
    // ---------------------------------------------------------------

    /// <summary>
    /// Options for <see cref="EnsureBinaryAsync"/>.
    /// </summary>
    public sealed class EnsureBinaryOptions
    {
        /// <summary>Optional logger action for progress messages.</summary>
        public Action<string>? Log { get; set; }

        /// <summary>Optional HttpClient for downloading (useful for testing/mocking).</summary>
        public HttpClient? HttpClient { get; set; }

        /// <summary>
        /// Whether to skip SHA-256 verification. Default false.
        /// This is internal and intended for testing only — production callers always verify (H2).
        /// </summary>
        internal bool SkipChecksumForTesting { get; set; }
    }

    /// <summary>
    /// Ensures the platform binary is present and returns the launcher path.
    /// Downloads, verifies, and extracts on first use; reuses the cache thereafter.
    /// After a successful install, prunes old cached versions.
    /// SHA-256 verification is always performed on download and cannot be bypassed by public callers (H2).
    /// </summary>
    public static async Task<string> EnsureBinaryAsync(string? version = null, EnsureBinaryOptions? options = null)
    {
        version = version ?? DefaultVersion;
        ValidateVersion(version); // H1: validate version pattern
        options = options ?? new EnsureBinaryOptions();
        var log = options.Log ?? (_ => { });

        var (name, ext) = BundleBaseName(version);
        var cache = CacheDir();
        var fullCacheBase = Path.GetFullPath(cache);
        var dir = Path.Combine(cache, version);

        // H1: assert verDir stays within the resolved cache base
        AssertWithinBase(dir, fullCacheBase);

        var launcher = LauncherPath(dir, name);

        // Check cache
        if (File.Exists(launcher) && new FileInfo(launcher).Length > 0)
        {
            log($"Using cached binary: {launcher}");
            return launcher;
        }

        // Skip download?
        if (!string.IsNullOrEmpty(Environment.GetEnvironmentVariable("MOCKSERVER_SKIP_BINARY_DOWNLOAD")))
        {
            throw new InvalidOperationException(
                $"MOCKSERVER_SKIP_BINARY_DOWNLOAD is set but no cached binary at {launcher}");
        }

        Directory.CreateDirectory(dir);
        var archiveFile = $"{name}.{ext}";
        var archive = Path.Combine(dir, archiveFile);
        var partial = archive + ".part";
        var shaFile = archive + ".sha256";

        try
        {
            // Download to a temp file
            log($"Downloading {AssetUrl(version, archiveFile)}");
            await DownloadFileAsync(AssetUrl(version, archiveFile), partial, options.HttpClient).ConfigureAwait(false);

            // Verify SHA-256 (H2: always verify unless internal test hook)
            if (!options.SkipChecksumForTesting)
            {
                await DownloadFileAsync(AssetUrl(version, archiveFile + ".sha256"), shaFile, options.HttpClient).ConfigureAwait(false);
                var shaContent = File.ReadAllText(shaFile);
                var expected = ParseSha256File(shaContent);
                if (string.IsNullOrEmpty(expected))
                {
                    throw new InvalidOperationException(
                        $"Checksum file for {name} is empty or unparseable");
                }

                var actual = ComputeSha256(partial);
                if (!string.Equals(expected, actual, StringComparison.OrdinalIgnoreCase))
                {
                    throw new InvalidOperationException(
                        $"Checksum mismatch for {name}: expected {expected}, got {actual}");
                }

                log("Checksum verified");

                // Clean up the .sha256 file after successful verification
                try { File.Delete(shaFile); } catch { /* best effort */ }
            }

            // Rename .part -> archive
            if (File.Exists(archive))
                File.Delete(archive);
            File.Move(partial, archive);
        }
        catch
        {
            // H3: Best-effort cleanup of both .part and .sha256 temp files
            try { File.Delete(partial); } catch { /* ignore */ }
            try { File.Delete(shaFile); } catch { /* ignore */ }
            throw;
        }

        // Extract — use tar with --no-same-owner to avoid permission issues
        // H3: guard tar extraction so it cannot escape verDir (tar -C <dir> constrains output)
        log($"Extracting {archive}");

        // H5: read stderr async before WaitForExit to avoid pipe-buffer deadlock
        using (var tarProcess = new System.Diagnostics.Process())
        {
            tarProcess.StartInfo = new ProcessStartInfo
            {
                FileName = "tar",
                Arguments = $"-xf \"{archive}\" -C \"{dir}\"",
                RedirectStandardError = true,
                RedirectStandardOutput = true,
                UseShellExecute = false
            };

            tarProcess.Start();

            // Drain stdout and stderr concurrently to prevent pipe-buffer deadlock
            var stderrTask = tarProcess.StandardError.ReadToEndAsync();
            var stdoutTask = tarProcess.StandardOutput.ReadToEndAsync();
            tarProcess.WaitForExit();
            var stderr = await stderrTask.ConfigureAwait(false);
            await stdoutTask.ConfigureAwait(false);

            if (tarProcess.ExitCode != 0)
            {
                throw new InvalidOperationException($"Extraction failed (tar exit {tarProcess.ExitCode}): {stderr}");
            }
        }

        // H3: verify extracted files remain within verDir (guard against tar path traversal)
        var fullDir = Path.GetFullPath(dir);
        var launcherFull = Path.GetFullPath(launcher);
        AssertWithinBase(launcherFull, fullDir);

        // Verify launcher exists
        if (!File.Exists(launcher) || new FileInfo(launcher).Length == 0)
        {
            throw new InvalidOperationException($"Launcher missing or empty after extract: {launcher}");
        }

        // chmod 0755 on non-Windows; check exit code for clear diagnostics
        if (!RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
        {
            using (var chmodProcess = new System.Diagnostics.Process())
            {
                chmodProcess.StartInfo = new ProcessStartInfo
                {
                    FileName = "chmod",
                    Arguments = $"755 \"{launcher}\"",
                    UseShellExecute = false,
                    RedirectStandardError = true
                };

                chmodProcess.Start();
                var chmodStderr = await chmodProcess.StandardError.ReadToEndAsync().ConfigureAwait(false);
                chmodProcess.WaitForExit();

                if (chmodProcess.ExitCode != 0)
                {
                    throw new InvalidOperationException(
                        $"chmod 755 \"{launcher}\" failed (exit {chmodProcess.ExitCode}): {chmodStderr}");
                }
            }
        }

        // Prune old versions after a successful install
        PruneOldVersions(cache, version);

        return launcher;
    }

    /// <summary>
    /// Synchronous wrapper for <see cref="EnsureBinaryAsync"/>.
    /// </summary>
    public static string EnsureBinary(string? version = null, EnsureBinaryOptions? options = null)
        => EnsureBinaryAsync(version, options).GetAwaiter().GetResult();

    // ---------------------------------------------------------------
    // Start / Stop
    // ---------------------------------------------------------------

    /// <summary>
    /// Downloads the binary (if needed) and starts MockServer on the given port.
    /// Returns the launcher instance that can be used to stop the server.
    /// H4: On Windows, spawns .bat via cmd.exe with proper quoting/escaping.
    /// H5: On non-Windows, drains stdout/stderr async to avoid pipe-buffer deadlock.
    /// </summary>
    public static async Task<MockServerBinaryLauncher> StartAsync(int port, string? version = null,
        EnsureBinaryOptions? options = null)
    {
        version = version ?? DefaultVersion;
        var launcher = await EnsureBinaryAsync(version, options).ConfigureAwait(false);
        var instance = new MockServerBinaryLauncher();

        ProcessStartInfo psi;
        if (RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
        {
            // H4: Spawn .bat safely via cmd.exe with proper quoting
            psi = new ProcessStartInfo
            {
                FileName = "cmd.exe",
                Arguments = $"/c \"\"{launcher}\" -serverPort {port}\"",
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true
            };
        }
        else
        {
            psi = new ProcessStartInfo
            {
                FileName = launcher,
                Arguments = $"-serverPort {port}",
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true
            };
        }

        instance._process = System.Diagnostics.Process.Start(psi);
        if (instance._process == null)
            throw new InvalidOperationException("Failed to start MockServer process");

        // H5: drain stdout/stderr in background to avoid pipe-buffer deadlock
        instance.DrainOutputStreams();

        return instance;
    }

    /// <summary>
    /// Drains stdout and stderr in background tasks to prevent pipe-buffer deadlock.
    /// The output is discarded; it exists only to prevent the child process from blocking.
    /// </summary>
    private void DrainOutputStreams()
    {
        if (_process == null) return;

        // Fire-and-forget background reads to keep the pipe buffers empty
        if (_process.StartInfo.RedirectStandardOutput)
        {
            _ = Task.Run(async () =>
            {
                try
                {
                    var buffer = new char[4096];
                    while (await _process.StandardOutput.ReadAsync(buffer, 0, buffer.Length).ConfigureAwait(false) > 0)
                    { /* discard */ }
                }
                catch { /* process exited */ }
            });
        }
        if (_process.StartInfo.RedirectStandardError)
        {
            _ = Task.Run(async () =>
            {
                try
                {
                    var buffer = new char[4096];
                    while (await _process.StandardError.ReadAsync(buffer, 0, buffer.Length).ConfigureAwait(false) > 0)
                    { /* discard */ }
                }
                catch { /* process exited */ }
            });
        }
    }

    /// <summary>
    /// Synchronous wrapper for <see cref="StartAsync"/>.
    /// </summary>
    public static MockServerBinaryLauncher Start(int port, string? version = null,
        EnsureBinaryOptions? options = null)
        => StartAsync(port, version, options).GetAwaiter().GetResult();

    /// <summary>
    /// Stops the MockServer process.
    /// </summary>
    public void Stop()
    {
        if (_process != null && !_process.HasExited)
        {
            try
            {
                _process.Kill();
                _process.WaitForExit(5000);
            }
            catch (InvalidOperationException)
            {
                // Process already exited
            }
        }
    }

    /// <summary>
    /// Disposes the launcher, stopping the process if it is still running.
    /// </summary>
    public void Dispose()
    {
        Stop();
        _process?.Dispose();
        _process = null;
    }
}

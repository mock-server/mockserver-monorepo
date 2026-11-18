/*
 * mockserver-node — on-demand binary launcher
 * http://mock-server.com
 *
 * Downloads the self-contained, JVM-less MockServer bundle (a jlink runtime +
 * the server + a `mockserver` launcher) for the current platform from the
 * GitHub Release, verifies its SHA-256, caches it per-user, and launches it.
 * No Java installation and no Docker required.
 *
 * This is the reference implementation of the on-demand-binary pattern (a la
 * esbuild / Playwright) for the MockServer client libraries.
 *
 * Environment overrides:
 *   MOCKSERVER_BINARY_BASE_URL        mirror host for the release assets (corporate / air-gapped)
 *   MOCKSERVER_BINARY_CACHE           cache directory (default: per-OS user cache)
 *   MOCKSERVER_SKIP_BINARY_DOWNLOAD   fail instead of downloading (air-gapped CI with a pre-seeded cache)
 *   NODE_EXTRA_CA_CERTS               extra CA bundle for TLS-inspecting proxies (honoured natively by Node)
 *   HTTPS_PROXY / HTTP_PROXY          used if an https/http proxy agent module is installed (else use a mirror)
 *
 * Licensed under the Apache License, Version 2.0
 */
'use strict';

var fs = require('fs');
var os = require('os');
var path = require('path');
var crypto = require('crypto');
var child_process = require('child_process');
var spawn = child_process.spawn;
var spawnSync = child_process.spawnSync;
var followRedirects = require('follow-redirects');

var REPO = 'mock-server/mockserver-monorepo';
var SNAPSHOT_CDN = 'https://downloads.mock-server.com';

/**
 * Strict semver-ish version pattern. Rejects path separators, '..', and
 * four-segment versions (e.g. 1.2.3.4). Pre-release must start with a hyphen,
 * followed by dot-separated alphanumeric identifiers.
 * Accepts: 1.2.3, 1.2.3-beta.1, 1.2.3-SNAPSHOT, 7.0.0-rc.2
 * Rejects: 1.2.3.4, v1.2.3, ../evil
 * @type {RegExp}
 */
var VERSION_PATTERN = /^[0-9]+\.[0-9]+\.[0-9]+([-][0-9A-Za-z]+([.][0-9A-Za-z]+)*)?$/;

/**
 * Build a clear, actionable error message for a missing release bundle.
 * Explains that no downloadable bundle exists for the version and lists the
 * concrete alternatives. The wording is kept consistent across all client languages.
 * @param {string} version
 * @returns {string}
 */
function noBundleMessage(version) {
  return 'no MockServer release bundle is published for version ' + version +
    " (no downloadable asset at the GitHub release tag 'mockserver-" + version + "'). " +
    'Use a MockServer version that ships self-contained bundles, ' +
    'or run MockServer via Docker (docker run mockserver/mockserver:mockserver-' + version + '), ' +
    'or use the Maven Central jar (org.mock-server:mockserver-netty:' + version + ').';
}

// ---------- H1: version validation ----------

/**
 * Validate a version string and reject path-traversal attempts.
 * @param {string} version
 * @throws {Error} if the version is invalid
 */
function validateVersion(version) {
  if (!version || typeof version !== 'string') {
    throw new Error('version must be a non-empty string, got: ' + version);
  }
  if (!VERSION_PATTERN.test(version)) {
    throw new Error('invalid version format (must match semver): ' + version);
  }
  // Belt-and-suspenders: reject any path separator or '..'
  if (version.indexOf('/') !== -1 || version.indexOf('\\') !== -1 || version.indexOf('..') !== -1) {
    throw new Error('version must not contain path separators or "..": ' + version);
  }
}

/**
 * Assert that a resolved path is within the resolved base directory (H1 path-traversal guard).
 * @param {string} resolvedChild
 * @param {string} resolvedBase
 * @throws {Error} if the child escapes the base
 */
function assertWithinBase(resolvedChild, resolvedBase) {
  // Ensure the base ends with a separator so we don't match partial directory names
  var normalizedBase = resolvedBase.endsWith(path.sep) ? resolvedBase : resolvedBase + path.sep;
  if (resolvedChild !== resolvedBase && !resolvedChild.startsWith(normalizedBase)) {
    throw new Error('path traversal blocked: ' + resolvedChild + ' is outside ' + resolvedBase);
  }
}

// ---------- platform detection ----------

/**
 * Map Node's platform/arch to the bundle's {os}-{arch} naming + archive type.
 * @returns {{ osName: string, arch: string, ext: string }}
 */
function resolvePlatform() {
  var p = process.platform;
  var a = process.arch;
  var osName, ext;
  if (p === 'linux') { osName = 'linux'; ext = 'tar.gz'; }
  else if (p === 'darwin') { osName = 'darwin'; ext = 'tar.gz'; }
  else if (p === 'win32') { osName = 'windows'; ext = 'zip'; }
  else { throw new Error('unsupported platform: ' + p); }
  var arch;
  if (a === 'x64') { arch = 'x86_64'; }
  else if (a === 'arm64') { arch = 'aarch64'; }
  else { throw new Error('unsupported architecture: ' + a); }
  return { osName: osName, arch: arch, ext: ext };
}

/**
 * Compute the base name (without extension) and extension for a version.
 * @param {string} version
 * @returns {{ name: string, ext: string }}
 */
function bundleBaseName(version) {
  var plat = resolvePlatform();
  return { name: 'mockserver-' + version + '-' + plat.osName + '-' + plat.arch, ext: plat.ext };
}

// ---------- cache directory ----------

/**
 * Resolve the cache base directory.
 * Order: MOCKSERVER_BINARY_CACHE env > platform default.
 * @returns {string}
 */
function cacheDir() {
  if (process.env.MOCKSERVER_BINARY_CACHE) { return process.env.MOCKSERVER_BINARY_CACHE; }
  var base = process.platform === 'win32' ?
    (process.env.LOCALAPPDATA || path.join(os.homedir(), 'AppData', 'Local')) :
    (process.env.XDG_CACHE_HOME || path.join(os.homedir(), '.cache'));
  return path.join(base, 'mockserver', 'binaries');
}

// ---------- URL helpers ----------

/**
 * Remove every trailing occurrence of a single character, in linear time
 * (a /c+$/ regex backtracks quadratically on a long run of c not at the end).
 * @param {string} s
 * @param {string} c  a single character
 * @returns {string}
 */
function trimTrailing(s, c) {
  var end = s.length;
  while (end > 0 && s.charAt(end - 1) === c) { end--; }
  return s.slice(0, end);
}

/**
 * Return true if version contains '-SNAPSHOT' (case-insensitive).
 * @param {string} version
 * @returns {boolean}
 */
function isSnapshot(version) {
  return version.toUpperCase().indexOf('-SNAPSHOT') !== -1;
}

/**
 * Build the download URL for an asset file within a version's release.
 * Uses MOCKSERVER_BINARY_BASE_URL if set; otherwise defaults to GitHub Releases
 * for release versions and the downloads.mock-server.com CDN for SNAPSHOT versions.
 * @param {string} version
 * @param {string} file  asset filename
 * @returns {string}
 */
function assetUrl(version, file) {
  var base = process.env.MOCKSERVER_BINARY_BASE_URL;
  if (!base) {
    base = isSnapshot(version) ?
      (SNAPSHOT_CDN + '/mockserver-' + version) :
      ('https://github.com/' + REPO + '/releases/download/mockserver-' + version);
  }
  return trimTrailing(base, '/') + '/' + file;
}

/**
 * Compute the path to the launcher binary within a version directory.
 * @param {string} dir    version directory
 * @param {string} name   bundle base name (without extension)
 * @returns {string}
 */
function launcherPath(dir, name) {
  return path.join(dir, name, 'bin', process.platform === 'win32' ? 'mockserver.bat' : 'mockserver');
}

// ---------- H6: streaming HTTP download with timeouts ----------

/**
 * Optional proxy agent — only if the user has installed one; otherwise a mirror
 * (MOCKSERVER_BINARY_BASE_URL) is the recommended corporate path.
 * @param {string} targetUrl
 * @returns {Object|undefined}
 */
function proxyAgent(targetUrl) {
  var proxy = targetUrl.indexOf('https:') === 0 ?
    (process.env.HTTPS_PROXY || process.env.https_proxy) :
    (process.env.HTTP_PROXY || process.env.http_proxy);
  if (!proxy) { return undefined; }
  try {
    var HttpsProxyAgent = require('https-proxy-agent').HttpsProxyAgent;
    return new HttpsProxyAgent(proxy);
  } catch (e) {
    return undefined; // not installed
  }
}

/**
 * Download a URL to a local file, streaming to disk (H6: never buffer the full body).
 * Sets connect timeout (30s) and read/socket timeout (5 min).
 * @param {string} url
 * @param {string} dest  destination file path
 * @returns {Promise<void>}
 */
function download(url, dest) {
  return new Promise(function (resolve, reject) {
    // Support file:// URLs for hermetic testing
    if (url.indexOf('file://') === 0) {
      var filePath = decodeURIComponent(url.slice(7));
      // On Windows, file:///C:/path -> C:/path (strip leading slash before drive letter)
      if (process.platform === 'win32' && filePath.charAt(0) === '/' && filePath.charAt(2) === ':') {
        filePath = filePath.slice(1);
      }
      try {
        fs.copyFileSync(filePath, dest);
        resolve();
      } catch (e) {
        reject(new Error('download ' + url + ' failed: ' + e.message));
      }
      return;
    }

    var lib = url.indexOf('https:') === 0 ? followRedirects.https : followRedirects.http;
    var opts = {
      agent: proxyAgent(url),
      timeout: 30000 // H6: connect timeout (30s)
    };
    var req = lib.get(url, opts, function (res) {
      if (res.statusCode < 200 || res.statusCode >= 300) {
        res.resume(); // drain
        var httpErr = new Error('download ' + url + ' failed: HTTP ' + res.statusCode);
        // Tag 404 so callers can distinguish "no bundle published" from other
        // transport errors and emit actionable guidance.
        httpErr.statusCode = res.statusCode;
        reject(httpErr);
        return;
      }
      var out = fs.createWriteStream(dest);
      res.on('error', reject);
      out.on('error', reject);
      res.pipe(out);
      out.on('finish', function () { out.close(function () { resolve(); }); });
    });
    // H6: socket-level read timeout (5 min for large bundles)
    req.on('socket', function (socket) {
      socket.setTimeout(300000);
      socket.on('timeout', function () { req.destroy(new Error('download timed out: ' + url)); });
    });
    req.on('timeout', function () { req.destroy(new Error('connect timed out: ' + url)); });
    req.on('error', reject);
  });
}

// ---------- SHA-256 verification ----------

/**
 * Compute the SHA-256 hex digest of a file by streaming (H6: no full-file buffer).
 * @param {string} file
 * @returns {Promise<string>}
 */
function sha256(file) {
  return new Promise(function (resolve, reject) {
    var hash = crypto.createHash('sha256');
    fs.createReadStream(file)
      .on('data', function (d) { hash.update(d); })
      .on('error', reject)
      .on('end', function () { resolve(hash.digest('hex')); });
  });
}

// ---------- H7: semver-aware version comparison ----------

/**
 * Parse a version string into numeric segments for comparison.
 * Handles pre-release suffixes: 1.2.3-beta.1 -> [1, 2, 3, 'beta', 1]
 * @param {string} v
 * @returns {Array}
 */
function parseVersionSegments(v) {
  // Split on dots and hyphens
  var parts = v.split(/[.-]/);
  return parts.map(function (s) {
    var n = parseInt(s, 10);
    return isNaN(n) ? s : n;
  });
}

/**
 * Compare two version strings with semver-aware numeric segment comparison (H7).
 * Returns negative if a < b, positive if a > b, 0 if equal.
 * @param {string} a
 * @param {string} b
 * @returns {number}
 */
function compareVersions(a, b) {
  var sa = parseVersionSegments(a);
  var sb = parseVersionSegments(b);
  // Per semver, a version with fewer segments (no pre-release) outranks one with
  // a pre-release suffix when the numeric parts are equal. Split into core (first
  // 3 numeric segments) and pre-release (remaining segments).
  var coreLen = 3;
  var len = Math.max(sa.length, sb.length);
  for (var i = 0; i < len; i++) {
    var va = i < sa.length ? sa[i] : undefined;
    var vb = i < sb.length ? sb[i] : undefined;
    // Beyond core segments: if one version has ended, it is the release (no
    // pre-release tag) and per semver outranks the one with a pre-release.
    if (i >= coreLen) {
      if (va === undefined && vb === undefined) { return 0; }
      // The version that ended (no pre-release) is HIGHER per semver
      if (va === undefined) { return 1; }  // a is release, b has pre-release -> a > b
      if (vb === undefined) { return -1; } // b is release, a has pre-release -> a < b
    }
    // Pad missing core segments with 0
    if (va === undefined) { va = 0; }
    if (vb === undefined) { vb = 0; }
    // Both numbers: numeric compare
    if (typeof va === 'number' && typeof vb === 'number') {
      if (va !== vb) { return va - vb; }
    } else if (typeof va === 'number' && typeof vb !== 'number') {
      // Number vs string: number (release-like segment) outranks string (pre-release label)
      return 1;
    } else if (typeof va !== 'number' && typeof vb === 'number') {
      return -1;
    } else {
      // Both strings: lexicographic compare of pre-release labels
      var cmp = String(va).localeCompare(String(vb));
      if (cmp !== 0) { return cmp; }
    }
  }
  return 0;
}

// ---------- Contract #7: versioned-cache pruning ----------

/**
 * Remove old version directories from the cache, keeping the current version and
 * at most `maxPrevious` older versions. Also removes leftover .part and .sha256
 * temp files at the base level.
 *
 * Safety: never deletes outside the resolved cache base; tolerates concurrent runs
 * (swallows ENOENT/ENOTEMPTY). Non-version entries (files/dirs that don't match
 * VERSION_PATTERN) are left untouched.
 *
 * @param {string} base        the cache base directory
 * @param {string} currentVer  the version to keep (must match VERSION_PATTERN)
 * @param {number} [maxPrevious=1]  how many previous versions to keep (default: 1)
 */
function pruneOldVersions(base, currentVer, maxPrevious) {
  if (maxPrevious === undefined || maxPrevious === null) { maxPrevious = 1; }

  // Resolve the base to an absolute path to prevent any symlink trickery
  var resolvedBase;
  try {
    resolvedBase = fs.realpathSync(base);
  } catch (e) {
    // Base doesn't exist — nothing to prune
    return;
  }

  var entries;
  try {
    entries = fs.readdirSync(resolvedBase);
  } catch (e) {
    return; // can't list — nothing to prune
  }

  // Collect version directories and temp files
  var versionDirs = [];
  var tempFiles = [];

  entries.forEach(function (entry) {
    var entryPath = path.join(resolvedBase, entry);
    // Clean up leftover .part and .sha256 temp files at the base level
    if (entry.endsWith('.part') || entry.endsWith('.sha256')) {
      tempFiles.push(entryPath);
      return;
    }
    // Only consider entries matching VERSION_PATTERN (ignoring non-version dirs/files)
    if (!VERSION_PATTERN.test(entry)) { return; }
    // Skip the current version
    if (entry === currentVer) { return; }
    try {
      var st = fs.statSync(entryPath);
      if (st.isDirectory()) {
        versionDirs.push(entry);
      }
    } catch (e) {
      // stat failed (e.g. ENOENT from concurrent delete) — skip
    }
  });

  // Sort versions descending (newest first) using semver-aware comparison (H7)
  versionDirs.sort(function (a, b) { return compareVersions(b, a); });

  // Keep the newest `maxPrevious` versions, remove the rest
  var toRemove = versionDirs.slice(maxPrevious);

  toRemove.forEach(function (ver) {
    var dirPath = path.join(resolvedBase, ver);
    // Safety: assert the directory is within the resolved base
    try {
      assertWithinBase(path.resolve(dirPath), resolvedBase);
    } catch (e) {
      return; // skip anything that would escape the base
    }
    try {
      fs.rmSync(dirPath, { recursive: true, force: true });
    } catch (e) {
      // Tolerate ENOENT/ENOTEMPTY from concurrent runs
      if (e.code !== 'ENOENT' && e.code !== 'ENOTEMPTY') {
        // Log but don't fail — pruning is best-effort
      }
    }
  });

  // Clean up leftover temp files
  tempFiles.forEach(function (f) {
    try { fs.unlinkSync(f); } catch (e) { /* best effort */ }
  });
}

// ---------- ensureBinary ----------

/**
 * Ensure the platform bundle is present and return the launcher path,
 * downloading + verifying + extracting + caching on first use.
 *
 * SHA-256 verification is ALWAYS performed — there is no opt-out (H2).
 * For hermetic testing, use MOCKSERVER_BINARY_BASE_URL=file:// pointing to
 * local fixture files (archive + <archive>.sha256).
 *
 * @param {string} version  semver version string
 * @param {Object} [opts]
 * @param {Function} [opts.log]  logging callback (defaults to no-op)
 * @returns {Promise<string>}  path to the launcher binary
 */
async function ensureBinary(version, opts) {
  opts = opts || {};
  var log = opts.log || function () {};

  // H1: validate version before any filesystem operations
  validateVersion(version);

  var meta = bundleBaseName(version);
  var base = cacheDir();
  var dir = path.join(base, version);

  // H1: assert verDir stays within cache base (even after symlink resolution)
  var resolvedBase = path.resolve(base);
  var resolvedDir = path.resolve(dir);
  assertWithinBase(resolvedDir, resolvedBase);

  var launcher = launcherPath(dir, meta.name);

  if (fs.existsSync(launcher) && fs.statSync(launcher).size > 0) {
    log('Using cached binary: ' + launcher);
    return launcher;
  }
  if (process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD) {
    throw new Error('MOCKSERVER_SKIP_BINARY_DOWNLOAD is set but no cached binary at ' + launcher);
  }

  fs.mkdirSync(dir, { recursive: true });
  var archiveFile = meta.name + '.' + meta.ext;
  var archive = path.join(dir, archiveFile);
  var partial = archive + '.part';
  var shaFile = archive + '.sha256';
  try {
    // Download to a temp file and rename only after the checksum passes, so an
    // interrupted download never leaves a truncated archive that looks complete.
    log('Downloading ' + assetUrl(version, archiveFile));
    try {
      await download(assetUrl(version, archiveFile), partial);
    } catch (e) {
      // A 404 means the release tag exists but ships no bundle for this version
      // (or the tag does not exist). Surface actionable guidance instead of an
      // opaque HTTP error.
      if (e && e.statusCode === 404) {
        throw new Error(noBundleMessage(version));
      }
      throw e;
    }

    // H2: SHA-256 verification is mandatory — always fail-closed
    await download(assetUrl(version, archiveFile + '.sha256'), shaFile);
    var shaContent = fs.readFileSync(shaFile, 'utf8').trim().split(/\s+/)[0];
    if (!shaContent) {
      throw new Error('checksum file for ' + meta.name + ' is empty or unparseable');
    }
    var actual = await sha256(partial);
    if (shaContent !== actual) {
      throw new Error('checksum mismatch for ' + meta.name + ': expected ' + shaContent + ', got ' + actual);
    }
    log('Checksum verified');

    fs.renameSync(partial, archive);
  } catch (e) {
    // H3: clean up BOTH .part and .sha256 on any failure
    try { fs.unlinkSync(partial); } catch (ignore) { /* best effort */ }
    try { fs.unlinkSync(shaFile); } catch (ignore) { /* best effort */ }
    throw e;
  }

  // Extract with the system tar: GNU tar auto-detects gzip; bsdtar (macOS,
  // Windows 10+) also handles .zip — so `tar -xf` covers every bundle type.
  // H3: archive path-traversal is already prevented without an extra flag —
  // GNU tar strips leading "/" and refuses ".." members by default (you must
  // pass -P/--absolute-names to opt out), and bsdtar behaves the same. The
  // `--no-absolute-filenames` long option is NOT recognised by GNU tar 1.34/1.35
  // (the CI image), so passing it aborts extraction with exit 64.
  log('Extracting ' + archive);
  var tarArgs = ['-xf', archive, '-C', dir];
  var r = spawnSync('tar', tarArgs, { stdio: 'pipe' });
  if (r.error) { throw new Error('could not run tar (is it installed?): ' + r.error.message); }
  if (r.status !== 0) {
    var stderr = r.stderr ? r.stderr.toString().trim() : '';
    throw new Error('extraction failed (tar exit ' + r.status + ')' + (stderr ? ': ' + stderr : ''));
  }
  if (!(fs.existsSync(launcher) && fs.statSync(launcher).size > 0)) {
    throw new Error('launcher missing or empty after extract: ' + launcher);
  }
  if (process.platform !== 'win32') { fs.chmodSync(launcher, 0o755); }

  // Contract #7: prune old version directories after successful install
  try {
    pruneOldVersions(base, version, 1);
  } catch (e) {
    // Pruning is best-effort; don't fail the install
    log('Warning: pruning old versions failed: ' + (e.message || e));
  }

  return launcher;
}

// ---------- H4: launch without a shell ----------

/**
 * How the bundle builder (scripts/build-binary-bundle.sh) writes each
 * launcher's java line: the java path, the -D options baked in at build time
 * (the analytics settings), the MOCKSERVER_JAVA_OPTS reference, then -XX flags
 * MOCKSERVER_JAVA_OPTS must not undo (-XX:-UseBASE64Intrinsics, CVE-2026-114938).
 */
var WINDOWS_LAUNCHER = { java: '"%DIR%\\runtime\\bin\\java.exe"', javaOpts: '%MOCKSERVER_JAVA_OPTS%', exe: 'java.exe' };
var POSIX_LAUNCHER = { java: 'exec "$DIR/runtime/bin/java"', javaOpts: '${MOCKSERVER_JAVA_OPTS:-}', exe: 'java' };
var BAKED_OPTION = /^-D[^\s"'%&|<>^$`;]+$/;
var BAKED_TRAILING_OPTION = /^-XX:[+-][A-Za-z0-9]+$/;

function isBakedOption(option) { return BAKED_OPTION.test(option); }
function isBakedTrailingOption(option) { return BAKED_TRAILING_OPTION.test(option); }

/**
 * Read the JVM options baked into a bundle's launcher script: those between the
 * java path and the MOCKSERVER_JAVA_OPTS reference on its java line, and those
 * between that reference and -jar (older bundles have none there).
 * @param {string} launcher
 * @param {{ java: string, javaOpts: string }} format
 * @returns {{ before: string[], after: string[] }}
 * @throws {Error} if the file is not the launcher the bundle builder writes
 */
function bakedJavaOptions(launcher, format) {
  var lines = fs.readFileSync(launcher, 'utf8').split('\n');
  for (var i = 0; i < lines.length; i++) {
    var line = lines[i].trim();
    var end = line.indexOf(format.javaOpts);
    if (line.indexOf(format.java) === 0 && end !== -1) {
      var before = line.slice(format.java.length, end).split(/\s+/).filter(Boolean);
      var rest = line.slice(end + format.javaOpts.length);
      var jar = rest.indexOf(' -jar ');
      var after = jar === -1 ? null : rest.slice(0, jar).split(/\s+/).filter(Boolean);
      if (after && before.every(isBakedOption) && after.every(isBakedTrailingOption)) {
        return { before: before, after: after };
      }
      break;
    }
  }
  throw new Error(launcher + ' is not a recognised MockServer launcher');
}

/**
 * Split MOCKSERVER_JAVA_OPTS into JVM arguments: whitespace separates them, and
 * a run inside double or single quotes is kept together with the quotes
 * removed, so -Dx="a b" gives -Dx=a b. Nothing else is interpreted: no
 * variables, wildcards or escapes. An unclosed quote runs to the end.
 * @param {string} value
 * @returns {string[]}
 */
function splitJavaOpts(value) {
  var options = [];
  var current = '';
  var inOption = false;
  var quote = null;
  for (var i = 0; i < value.length; i++) {
    var c = value.charAt(i);
    if (quote) {
      if (c === quote) { quote = null; } else { current += c; }
    } else if (c === '"' || c === '\'') {
      quote = c;
      inOption = true;
    } else if (/\s/.test(c)) {
      if (inOption) { options.push(current); current = ''; inOption = false; }
    } else {
      current += c;
      inOption = true;
    }
  }
  if (inOption) { options.push(current); }
  return options;
}

/**
 * Quote one argument so java.exe reads it back unchanged (the C runtime's
 * rules): backslashes double only before a quote, and every argument is quoted
 * so java.exe never expands * or ? in it as a file wildcard.
 * @param {string} arg
 * @returns {string}
 */
function quoteWindowsArg(arg) {
  var out = '"';
  var backslashes = 0;
  for (var i = 0; i < arg.length; i++) {
    var c = arg.charAt(i);
    if (c === '\\') {
      backslashes++;
    } else {
      out += '\\'.repeat(c === '"' ? backslashes * 2 + 1 : backslashes) + c;
      backslashes = 0;
    }
  }
  return out + '\\'.repeat(backslashes * 2) + '"';
}

/**
 * Find an environment variable's key; Windows ignores case in variable names.
 * @param {Object} env
 * @param {string} name
 * @param {boolean} windows
 * @returns {string|undefined}
 */
function envKey(env, name, windows) {
  return Object.keys(env).find(function (k) { return windows ? k.toUpperCase() === name : k === name; });
}

/**
 * Do what the bundle's launcher script (bin/mockserver or bin/mockserver.bat)
 * does, without a shell: run the bundled java on the jar with the script's
 * baked -D options, then MOCKSERVER_JAVA_OPTS (see splitJavaOpts), then the
 * script's baked -XX flags, then the caller's arguments, and set
 * MOCKSERVER_LAUNCHER unless the caller set it.
 * @param {string} launcher  path to the launcher script
 * @param {string[]} args
 * @param {Object} env  the child's environment
 * @param {boolean} windows
 * @returns {{ command: string, args: string[], env: Object }}
 */
function bundleLaunch(launcher, args, env, windows) {
  var format = windows ? WINDOWS_LAUNCHER : POSIX_LAUNCHER;
  var bundleDir = path.dirname(path.dirname(launcher));
  var javaOptsKey = envKey(env, 'MOCKSERVER_JAVA_OPTS', windows);
  var javaOpts = javaOptsKey ? splitJavaOpts(String(env[javaOptsKey])) : [];
  var childEnv = Object.assign({}, env);
  var launcherKey = envKey(env, 'MOCKSERVER_LAUNCHER', windows);
  // the .bat sets it only when undefined; the sh script also when empty
  if (!launcherKey || (!windows && !env[launcherKey])) {
    childEnv.MOCKSERVER_LAUNCHER = path.basename(launcher, '.bat');
  }
  var baked = bakedJavaOptions(launcher, format);
  return {
    command: path.join(bundleDir, 'runtime', 'bin', format.exe),
    args: baked.before.concat(javaOpts, baked.after, ['-jar', path.join(bundleDir, 'lib', 'mockserver.jar')],
      args.map(String)),
    env: childEnv
  };
}

// ---------- runBinary ----------

/**
 * Download (if needed) and spawn the binary with the given args.
 * Returns a Promise that resolves to the child process.
 *
 * H4: No shell ever runs: the bundled java runs directly (see bundleLaunch),
 * since the launcher scripts need sh or cmd.exe, and a spawnOptions.shell
 * setting is overridden.
 * H5: Uses stdio: 'inherit' by default to avoid pipe-buffer deadlock.
 *
 * @param {string} version
 * @param {string[]} [args]
 * @param {Object} [opts]
 * @param {Function} [opts.log]
 * @param {Object} [opts.spawnOptions]
 * @returns {Promise<ChildProcess>}
 */
function runBinary(version, args, opts) {
  opts = opts || {};
  args = args || [];
  return ensureBinary(version, opts).then(function (launcher) {
    var spawnOpts = Object.assign({ stdio: 'inherit' }, opts.spawnOptions || {}, { shell: false });
    var windows = process.platform === 'win32';
    var launch = bundleLaunch(launcher, args, spawnOpts.env || process.env, windows);
    spawnOpts.env = launch.env;

    if (windows) {
      return spawn(launch.command, launch.args.map(quoteWindowsArg), Object.assign(spawnOpts, {
        argv0: quoteWindowsArg(launch.command),
        windowsVerbatimArguments: true
      }));
    }

    return spawn(launch.command, launch.args, spawnOpts);
  });
}

var internal = {
  validateVersion: validateVersion,
  compareVersions: compareVersions,
  parseVersionSegments: parseVersionSegments,
  assertWithinBase: assertWithinBase,
  quoteWindowsArg: quoteWindowsArg,
  bundleLaunch: bundleLaunch,
  splitJavaOpts: splitJavaOpts,
  trimTrailing: trimTrailing,
  isSnapshot: isSnapshot,
  download: download,
  sha256: sha256,
  VERSION_PATTERN: VERSION_PATTERN
};

module.exports = {
  // Public API
  resolvePlatform: resolvePlatform,
  bundleBaseName: bundleBaseName,
  cacheDir: cacheDir,
  assetUrl: assetUrl,
  ensureBinary: ensureBinary,
  runBinary: runBinary,
  pruneOldVersions: pruneOldVersions,

  // Exported for testing only (not part of the public contract)
  _internal: internal
};

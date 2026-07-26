import * as fs from "fs";
import * as os from "os";
import * as path from "path";

/**
 * Guards the dependency resolution this module pins through `overrides`.
 *
 * CVE-2026-14257 (GHSA-mh99-v99m-4gvg) is a denial of service in
 * brace-expansion <= 5.0.7, patched only in 5.0.8. The obvious remedy — a blanket
 * `"brace-expansion": "^5.0.8"` override — is WRONG here: 5.x changed the CommonJS
 * export from a callable function to an object (`{ expand, EXPANSION_MAX, ... }`),
 * while minimatch 3.x/5.x/9.x all call it as `expand(pattern)`. Forcing 5.0.8 under
 * those minimatch versions makes every BRACE pattern throw
 * "TypeError: expand is not a function", which crashes `archiver.glob()` — the path
 * testcontainers uses to copy files into a container.
 *
 * That breakage is invisible to the rest of the suite because minimatch
 * short-circuits patterns containing no "{", so plain globs keep working and
 * `npm audit` reports a clean tree. These tests exercise a brace pattern
 * specifically, so the silent half of that failure mode cannot come back.
 */
describe("dependency integrity (unit)", () => {
  const bracePattern = "*.{txt,md}";

  it("expands brace patterns through every runtime minimatch copy", () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const archiverUtilsMinimatch = require("archiver-utils/node_modules/minimatch");
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const readdirGlobMinimatch = require("readdir-glob/node_modules/minimatch");

    for (const [name, mod] of [
      ["archiver-utils", archiverUtilsMinimatch],
      ["readdir-glob", readdirGlobMinimatch],
    ] as [string, { minimatch: (f: string, p: string) => boolean }][]) {
      expect([name, mod.minimatch("c.md", bracePattern)]).toEqual([name, true]);
      expect([name, mod.minimatch("a.txt", bracePattern)]).toEqual([name, true]);
      expect([name, mod.minimatch("skip.bin", bracePattern)]).toEqual([name, false]);
    }
  });

  it("bounds brace expansion so a pathological pattern cannot exhaust memory", () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const braceExpansion = require("brace-expansion");
    const expand: (p: string) => string[] =
      typeof braceExpansion === "function" ? braceExpansion : braceExpansion.expand;

    // 2^20 expansions if unbounded; the patched release caps the result instead.
    const expanded = expand("{a,b}".repeat(20));
    expect(expanded.length).toBeLessThan(2 ** 20);
  });

  it("archives files selected by a brace glob pattern", async () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const archiver = require("archiver");

    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "mockserver-archive-"));
    for (const file of ["a.txt", "b.txt", "c.md", "skip.bin"]) {
      fs.writeFileSync(path.join(dir, file), "contents");
    }
    const target = path.join(dir, "out.tar");

    const bytesWritten = await new Promise<number>((resolve, reject) => {
      const output = fs.createWriteStream(target);
      const archive = archiver("tar");
      archive.on("error", reject);
      output.on("close", () => resolve(fs.statSync(target).size));
      archive.pipe(output);
      archive.glob(bracePattern, { cwd: dir });
      archive.finalize().catch(reject);
    });

    expect(bytesWritten).toBeGreaterThan(0);
  });
});

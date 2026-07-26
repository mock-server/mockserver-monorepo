/**
 * Unit test for MockServer VS Code extension.
 * Tests command registration without requiring a running VS Code instance.
 * Uses a minimal stub of the vscode API.
 */

import * as assert from "assert";

// Stub the vscode module before importing the extension
interface Disposable {
    dispose(): void;
}

interface Subscription {
    push(...items: Disposable[]): void;
}

interface FakeContext {
    subscriptions: Disposable[];
    extension: { packageJSON: { version: string } };
    extensionUri: { toString(): string };
    globalStorageUri: { fsPath: string };
}

const registeredCommands: Map<string, Function> = new Map();
const outputLines: string[] = [];
const configValues: Record<string, unknown> = {};

// Build a minimal vscode stub
const vscodeStub = {
    workspace: {
        getConfiguration(_section?: string) {
            return {
                get<T>(key: string): T | undefined {
                    return configValues[key] as T | undefined;
                },
            };
        },
        registerTextDocumentContentProvider(_scheme: string, _provider: any): Disposable {
            return { dispose() {} };
        },
        openTextDocument(_uri: any) {
            return Promise.resolve({ getText: () => "{}" });
        },
        fs: {
            readFile(_uri: any) { return Promise.resolve(new Uint8Array([0, 97, 115, 109])); },
        },
    },
    commands: {
        registerCommand(id: string, handler: Function): Disposable {
            registeredCommands.set(id, handler);
            return { dispose() { registeredCommands.delete(id); } };
        },
        executeCommand(_id: string, ..._args: any[]) { return Promise.resolve(undefined); },
    },
    languages: {
        registerCodeLensProvider(_selector: any, _provider: any): Disposable {
            return { dispose() {} };
        },
        registerCodeActionsProvider(_selector: any, _provider: any, _meta?: any): Disposable {
            return { dispose() {} };
        },
        registerCompletionItemProvider(_selector: any, _provider: any, ..._triggers: any[]): Disposable {
            return { dispose() {} };
        },
        createDiagnosticCollection(_name?: string) {
            return {
                set(_uri: any, _diags?: any) {},
                delete(_uri: any) {},
                clear() {},
                dispose() {},
            };
        },
    },
    DiagnosticSeverity: { Error: 0, Warning: 1, Information: 2, Hint: 3 },
    Diagnostic: class {
        code: any;
        source: any;
        constructor(public range: any, public message: string, public severity?: number) {}
    },
    CodeActionKind: { QuickFix: { value: "quickfix" } },
    CodeAction: class {
        edit: any;
        command: any;
        diagnostics: any;
        isPreferred?: boolean;
        constructor(public title: string, public kind?: any) {}
    },
    WorkspaceEdit: class {
        replace(_uri: any, _range: any, _text: string) {}
    },
    CompletionItemKind: { Value: 11 },
    CompletionItem: class {
        insertText: any;
        detail: any;
        constructor(public label: string, public kind?: number) {}
    },
    Position: class {
        constructor(public line: number, public character: number) {}
    },
    EventEmitter: class {
        event = (_listener?: any): Disposable => ({ dispose() {} });
        fire(_e?: any): void {}
        dispose(): void {}
    },
    Range: class {
        constructor(
            public startLine: number,
            public startCharacter: number,
            public endLine: number,
            public endCharacter: number
        ) {}
    },
    CodeLens: class {
        constructor(public range: any, public command?: any) {}
    },
    window: {
        createOutputChannel(_name: string) {
            return {
                appendLine(msg: string) { outputLines.push(msg); },
                show(_preserveFocus?: boolean) {},
                dispose() {},
            };
        },
        showInformationMessage(_msg: string) {},
        showErrorMessage(_msg: string) {},
        showWarningMessage(_msg: string) {},
        showOpenDialog(_options?: any) { return Promise.resolve(undefined); },
        showInputBox(_options?: any) { return Promise.resolve(undefined); },
        showQuickPick(_items?: any, _options?: any) { return Promise.resolve(undefined); },
        createWebviewPanel(_viewType: string, _title: string, _column: any, _options: any) {
            return { webview: { html: "" }, dispose() {} };
        },
        createStatusBarItem(_alignment?: any, _priority?: number) {
            return {
                text: "",
                tooltip: "",
                command: "",
                show() {},
                hide() {},
                dispose() {},
            };
        },
        registerTreeDataProvider(_viewId: string, _provider: any): Disposable {
            return { dispose() {} };
        },
        registerWebviewViewProvider(_viewId: string, _provider: any, _options?: any): Disposable {
            return { dispose() {} };
        },
    },
    StatusBarAlignment: { Left: 1, Right: 2 },
    TreeItemCollapsibleState: { None: 0, Collapsed: 1, Expanded: 2 },
    TreeItem: class {
        iconPath: any;
        tooltip: any;
        command: any;
        contextValue: any;
        constructor(public label: string, public collapsibleState?: number) {}
    },
    ThemeIcon: class {
        constructor(public id: string) {}
    },
    ViewColumn: { Active: -1 },
    env: {
        openExternal(_uri: any) { return Promise.resolve(true); },
    },
    Uri: {
        parse(value: string) { return { toString: () => value }; },
        joinPath(base: any, ...parts: string[]) {
            return { toString: () => `${base?.toString?.() ?? ""}/${parts.join("/")}` };
        },
    },
};

// Patch require to intercept 'vscode' imports
const Module = require("module");
const originalRequire = Module.prototype.require;
Module.prototype.require = function (id: string) {
    if (id === "vscode") {
        return vscodeStub;
    }
    return originalRequire.apply(this, arguments);
};

// Now import the extension (it will get our stub)
// Clear the module cache first so it picks up the stub
delete require.cache[require.resolve("../extension")];
const extension = require("../extension");

async function runTests(): Promise<void> {
    console.log("Running MockServer VS Code extension tests...\n");

    let passed = 0;
    let failed = 0;

    async function test(name: string, fn: () => void | Promise<void>): Promise<void> {
        try {
            await fn();
            console.log(`  PASS: ${name}`);
            passed++;
        } catch (e: any) {
            console.log(`  FAIL: ${name}`);
            console.log(`        ${e.message}`);
            failed++;
        }
    }

    // Setup: activate the extension
    registeredCommands.clear();
    const globalStorageDir = require("path").join(
        require("os").tmpdir(),
        `ms-globalstorage-${Date.now()}`
    );
    const fakeContext: FakeContext = {
        subscriptions: [],
        extension: { packageJSON: { version: "9.9.9" } },
        extensionUri: { toString: () => "file:///ext" },
        globalStorageUri: { fsPath: globalStorageDir },
    };
    extension.activate(fakeContext);

    await test("activate registers mockserver.start command", () => {
        assert.ok(
            registeredCommands.has("mockserver.start"),
            "mockserver.start command not registered"
        );
    });

    await test("activate registers mockserver.stop command", () => {
        assert.ok(
            registeredCommands.has("mockserver.stop"),
            "mockserver.stop command not registered"
        );
    });

    await test("activate registers mockserver.startBinary command", () => {
        assert.ok(
            registeredCommands.has("mockserver.startBinary"),
            "mockserver.startBinary command not registered"
        );
    });

    await test("activate registers mockserver.stopBinary command", () => {
        assert.ok(
            registeredCommands.has("mockserver.stopBinary"),
            "mockserver.stopBinary command not registered"
        );
    });

    // --- Binary-bundle (no-Docker) launch: pure resolution logic ---
    const binary = require("../binaryBundle");

    await test("resolveTarget maps Node platform/arch to published bundle os/arch", () => {
        assert.deepStrictEqual(binary.resolveTarget("darwin", "arm64"), { os: "darwin", arch: "aarch64" });
        assert.deepStrictEqual(binary.resolveTarget("darwin", "x64"), { os: "darwin", arch: "x86_64" });
        assert.deepStrictEqual(binary.resolveTarget("linux", "x64"), { os: "linux", arch: "x86_64" });
        assert.deepStrictEqual(binary.resolveTarget("linux", "arm64"), { os: "linux", arch: "aarch64" });
        assert.deepStrictEqual(binary.resolveTarget("win32", "x64"), { os: "windows", arch: "x86_64" });
    });

    await test("resolveTarget rejects unsupported platform/arch and windows/aarch64", () => {
        assert.throws(() => binary.resolveTarget("aix", "x64"), /No MockServer binary bundle/);
        assert.throws(() => binary.resolveTarget("linux", "ppc64"), /No MockServer binary bundle/);
        // No windows/aarch64 bundle is published.
        assert.throws(() => binary.resolveTarget("win32", "arm64"), /windows\/aarch64/);
    });

    await test("archive file name and extension follow the published asset naming", () => {
        const mac = { os: "darwin", arch: "aarch64" };
        const win = { os: "windows", arch: "x86_64" };
        assert.strictEqual(binary.archiveExtension("darwin"), ".tar.gz");
        assert.strictEqual(binary.archiveExtension("windows"), ".zip");
        assert.strictEqual(binary.bundleBaseName("7.3.0", mac), "mockserver-7.3.0-darwin-aarch64");
        assert.strictEqual(binary.archiveFileName("7.3.0", mac), "mockserver-7.3.0-darwin-aarch64.tar.gz");
        assert.strictEqual(binary.archiveFileName("7.3.0", win), "mockserver-7.3.0-windows-x86_64.zip");
    });

    await test("downloadUrl and checksumUrl point at the GitHub release asset", () => {
        const t = { os: "linux", arch: "x86_64" };
        assert.strictEqual(
            binary.downloadUrl("7.3.0", t),
            "https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-7.3.0/mockserver-7.3.0-linux-x86_64.tar.gz"
        );
        assert.strictEqual(binary.checksumUrl("7.3.0", t), binary.downloadUrl("7.3.0", t) + ".sha256");
    });

    await test("javaExecutableRelativePath is runtime/bin/java (java.exe on windows)", () => {
        const path = require("path");
        assert.strictEqual(binary.javaExecutableRelativePath("linux"), path.join("runtime", "bin", "java"));
        assert.strictEqual(binary.javaExecutableRelativePath("darwin"), path.join("runtime", "bin", "java"));
        assert.strictEqual(binary.javaExecutableRelativePath("windows"), path.join("runtime", "bin", "java.exe"));
    });

    await test("jarRelativePath is lib/mockserver.jar", () => {
        const path = require("path");
        assert.strictEqual(binary.jarRelativePath(), path.join("lib", "mockserver.jar"));
    });

    await test("bundleJavaAndJar resolves the java executable + jar from a bundle root (no launcher/shell)", () => {
        const path = require("path");
        const posix = binary.bundleJavaAndJar("/opt/ms", "linux");
        assert.strictEqual(posix.javaExecutable, path.join("/opt/ms", "runtime", "bin", "java"));
        assert.strictEqual(posix.jarPath, path.join("/opt/ms", "lib", "mockserver.jar"));
        // Windows resolves java.exe (still spawned directly — never via cmd.exe).
        const win = binary.bundleJavaAndJar("C:\\ms", "windows");
        assert.strictEqual(win.javaExecutable, path.join("C:\\ms", "runtime", "bin", "java.exe"));
        assert.strictEqual(win.jarPath, path.join("C:\\ms", "lib", "mockserver.jar"));
    });

    await test("serverArgs uses the backward-compatible -serverPort flag", () => {
        assert.deepStrictEqual(binary.serverArgs(2080), ["-serverPort", "2080"]);
    });

    await test("parseSha256 extracts the digest from a sha256sum sidecar", () => {
        const line = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08  mockserver-7.3.0-linux-x86_64.tar.gz\n";
        assert.strictEqual(
            binary.parseSha256(line),
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        );
        assert.throws(() => binary.parseSha256("not a checksum"), /SHA-256/);
    });

    await test("resolveConfiguredBundleRoot: directory is the root; a launcher file's root is its grandparent", () => {
        const path = require("path");
        // A directory is the bundle root as-is.
        assert.strictEqual(binary.resolveConfiguredBundleRoot("/opt/ms", true), "/opt/ms");
        // A launcher FILE (…/bin/mockserver) → root is the grandparent (…/opt/ms).
        assert.strictEqual(
            binary.resolveConfiguredBundleRoot(path.join("/opt/ms", "bin", "mockserver"), false),
            "/opt/ms"
        );
        assert.strictEqual(
            binary.resolveConfiguredBundleRoot(path.join("/opt/ms", "bin", "mockserver.bat"), false),
            "/opt/ms"
        );
    });

    await test("cachedBundleDir nests under bundles/<base> in global storage", () => {
        const path = require("path");
        const t = { os: "darwin", arch: "aarch64" };
        assert.strictEqual(
            binary.cachedBundleDir("/store", "7.3.0", t),
            path.join("/store", "bundles", "mockserver-7.3.0-darwin-aarch64")
        );
    });

    await test("parseJavaOpts splits on whitespace like the launcher's unquoted expansion", () => {
        assert.deepStrictEqual(binary.parseJavaOpts(undefined), []);
        assert.deepStrictEqual(binary.parseJavaOpts(""), []);
        assert.deepStrictEqual(binary.parseJavaOpts("   "), []);
        assert.deepStrictEqual(binary.parseJavaOpts("-Xmx512m"), ["-Xmx512m"]);
        assert.deepStrictEqual(
            binary.parseJavaOpts("  -Xmx512m   -Dfoo=bar\t-Dbaz=qux "),
            ["-Xmx512m", "-Dfoo=bar", "-Dbaz=qux"]
        );
    });

    await test("isValidVersion accepts release-shaped versions and rejects injection", () => {
        assert.strictEqual(binary.isValidVersion("7.3.0"), true);
        assert.strictEqual(binary.isValidVersion("7.3.0-SNAPSHOT_1"), true);
        assert.strictEqual(binary.isValidVersion("7.3.0/../etc"), false);
        assert.strictEqual(binary.isValidVersion("7 3"), false);
        assert.strictEqual(binary.isValidVersion(""), false);
        assert.strictEqual(binary.isValidVersion("../../evil"), false);
    });

    await test("checksumAbortReason is fail-closed: verified, mismatch, and missing-sidecar cases", () => {
        const digest = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        // sidecar present + digests match → proceed
        assert.strictEqual(binary.checksumAbortReason(digest, digest, false), undefined);
        // digests match is case-insensitive
        assert.strictEqual(binary.checksumAbortReason(digest.toUpperCase(), digest, false), undefined);
        // sidecar present + mismatch → abort (even with consent flag set)
        assert.ok(binary.checksumAbortReason(digest, "deadbeef", true), "mismatch must abort");
        // sidecar absent + NO consent → abort (fail-closed)
        const noSidecar = binary.checksumAbortReason(undefined, undefined, false);
        assert.ok(noSidecar && /could not be verified/i.test(noSidecar), "missing sidecar without consent must abort");
        // sidecar absent + explicit consent → proceed
        assert.strictEqual(binary.checksumAbortReason(undefined, undefined, true), undefined);
    });

    await test("firstUnsafeArchiveEntry flags absolute and path-traversal entries", () => {
        assert.strictEqual(
            binary.firstUnsafeArchiveEntry(["mockserver-7.3.0-linux-x86_64/bin/mockserver", "runtime/lib/x.so"]),
            undefined
        );
        assert.strictEqual(binary.firstUnsafeArchiveEntry(["/etc/passwd"]), "/etc/passwd");
        assert.strictEqual(binary.firstUnsafeArchiveEntry(["../../etc/passwd"]), "../../etc/passwd");
        assert.strictEqual(binary.firstUnsafeArchiveEntry(["ok/../../evil"]), "ok/../../evil");
        assert.strictEqual(binary.firstUnsafeArchiveEntry(["C:\\Windows\\system32"]), "C:\\Windows\\system32");
        assert.strictEqual(binary.firstUnsafeArchiveEntry(["", "  ", "safe/dir/file"]), undefined);
    });

    await test("sha256File computes the digest of a real file", async () => {
        const fs = require("fs");
        const os = require("os");
        const path = require("path");
        const tmp = path.join(os.tmpdir(), `ms-sha-${Date.now()}.bin`);
        fs.writeFileSync(tmp, "");
        try {
            // SHA-256 of the empty string.
            assert.strictEqual(
                await binary.sha256File(tmp),
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
            );
        } finally {
            fs.rmSync(tmp, { force: true });
        }
    });

    // --- Binary launch spawns the bundled java DIRECTLY (no cmd.exe / no shell) ---
    // These drive the real mockserver.startBinary handler with child_process.spawn
    // patched, and assert the resolved command is the bundled java executable with
    // `-jar <jar>` + server args — and crucially that cmd.exe is NEVER spawned. This
    // is the behaviour that clears CodeQL js/shell-command-constructed-from-input:
    // a bundle path is never handed to a shell.
    {
        const fs = require("fs");
        const os = require("os");
        const path = require("path");
        const child_process = require("child_process");
        const target = binary.resolveTarget(process.platform, process.arch);

        // Lay out a minimal bundle tree (java executable + jar) under `root`.
        function layoutBundle(root: string): { java: string; jar: string } {
            const j = binary.bundleJavaAndJar(root, target.os);
            fs.mkdirSync(path.dirname(j.javaExecutable), { recursive: true });
            fs.mkdirSync(path.dirname(j.jarPath), { recursive: true });
            fs.writeFileSync(j.javaExecutable, "");
            fs.writeFileSync(j.jarPath, "");
            return { java: j.javaExecutable, jar: j.jarPath };
        }

        // Run startBinary with spawn captured; returns the single spawn call.
        async function runStartBinaryCapturingSpawn(): Promise<{ cmd: string; args: string[]; opts: any }> {
            const originalSpawn = child_process.spawn;
            const calls: Array<{ cmd: string; args: string[]; opts: any }> = [];
            child_process.spawn = (cmd: string, args: string[], opts: any) => {
                calls.push({ cmd, args, opts });
                return {
                    stdout: { on() {} },
                    stderr: { on() {} },
                    on() {},
                    kill() {},
                    pid: 4242,
                };
            };
            try {
                await registeredCommands.get("mockserver.startBinary")!();
            } finally {
                child_process.spawn = originalSpawn;
            }
            // Reset the tracked process so the next call isn't blocked by "already running".
            extension.deactivate();
            assert.strictEqual(calls.length, 1, `expected exactly one spawn, got ${calls.length}`);
            return calls[0];
        }

        await test("startBinary (configured directory) spawns bundled java directly with -jar, never cmd.exe", async () => {
            const root = path.join(os.tmpdir(), `ms-bundle-dir-${Date.now()}`);
            const { java, jar } = layoutBundle(root);
            configValues["binaryPath"] = root;
            configValues["port"] = 3080;
            let call: any;
            try {
                call = await runStartBinaryCapturingSpawn();
            } finally {
                delete configValues["binaryPath"];
                delete configValues["port"];
                fs.rmSync(root, { recursive: true, force: true });
            }
            assert.notStrictEqual(call.cmd, "cmd.exe", "must NOT spawn cmd.exe");
            assert.ok(!/cmd(\.exe)?$/i.test(call.cmd), `command must be java, not a shell: ${call.cmd}`);
            assert.strictEqual(call.cmd, java, "command should be the bundled java executable");
            assert.deepStrictEqual(call.args, ["-jar", jar, "-serverPort", "3080"]);
        });

        await test("startBinary (configured launcher FILE) resolves the bundle root and spawns java, not the .bat/shell", async () => {
            const root = path.join(os.tmpdir(), `ms-bundle-file-${Date.now()}`);
            const { java, jar } = layoutBundle(root);
            // Point binaryPath at the launcher file (…/bin/mockserver[.bat]) — a FILE, not a dir.
            const launcherFile = path.join(root, "bin", target.os === "windows" ? "mockserver.bat" : "mockserver");
            fs.mkdirSync(path.dirname(launcherFile), { recursive: true });
            fs.writeFileSync(launcherFile, "");
            configValues["binaryPath"] = launcherFile;
            let call: any;
            try {
                call = await runStartBinaryCapturingSpawn();
            } finally {
                delete configValues["binaryPath"];
                fs.rmSync(root, { recursive: true, force: true });
            }
            assert.notStrictEqual(call.cmd, "cmd.exe", "must NOT spawn cmd.exe");
            assert.strictEqual(call.cmd, java, "command should be the bundled java executable, not the launcher");
            assert.strictEqual(call.args[0], "-jar");
            assert.strictEqual(call.args[1], jar);
        });

        await test("startBinary (cached bundle) spawns bundled java directly, never cmd.exe", async () => {
            const cachedRoot = binary.cachedBundleDir(globalStorageDir, "9.9.9", target);
            const { java, jar } = layoutBundle(cachedRoot);
            let call: any;
            try {
                call = await runStartBinaryCapturingSpawn();
            } finally {
                fs.rmSync(globalStorageDir, { recursive: true, force: true });
            }
            assert.notStrictEqual(call.cmd, "cmd.exe", "must NOT spawn cmd.exe");
            assert.strictEqual(call.cmd, java, "command should be the cached bundle's java executable");
            assert.deepStrictEqual(call.args, ["-jar", jar, "-serverPort", "1080"]);
        });

        await test("startBinary threads MOCKSERVER_JAVA_OPTS into the java args (before -jar)", async () => {
            const root = path.join(os.tmpdir(), `ms-bundle-opts-${Date.now()}`);
            const { java, jar } = layoutBundle(root);
            configValues["binaryPath"] = root;
            const prev = process.env.MOCKSERVER_JAVA_OPTS;
            process.env.MOCKSERVER_JAVA_OPTS = "-Xmx256m -Dx=y";
            let call: any;
            try {
                call = await runStartBinaryCapturingSpawn();
            } finally {
                delete configValues["binaryPath"];
                if (prev === undefined) {
                    delete process.env.MOCKSERVER_JAVA_OPTS;
                } else {
                    process.env.MOCKSERVER_JAVA_OPTS = prev;
                }
                fs.rmSync(root, { recursive: true, force: true });
            }
            assert.strictEqual(call.cmd, java);
            assert.deepStrictEqual(call.args, ["-Xmx256m", "-Dx=y", "-jar", jar, "-serverPort", "1080"]);
        });
    }

    await test("activate registers mockserver.openDashboard command", () => {
        assert.ok(
            registeredCommands.has("mockserver.openDashboard"),
            "mockserver.openDashboard command not registered"
        );
    });

    await test("activate adds disposables to subscriptions", () => {
        // 3 commands + 1 output channel = 4
        assert.ok(
            fakeContext.subscriptions.length >= 3,
            `Expected at least 3 subscriptions, got ${fakeContext.subscriptions.length}`
        );
    });

    await test("deactivate does not throw", () => {
        assert.doesNotThrow(() => extension.deactivate());
    });

    await test("registered command handlers are functions", () => {
        for (const [id, handler] of registeredCommands) {
            assert.strictEqual(typeof handler, "function", `Handler for ${id} is not a function`);
        }
    });

    await test("openDashboard uses the configured port (default 1080)", async () => {
        let openedUrl = "";
        vscodeStub.env.openExternal = (uri: any) => {
            openedUrl = uri.toString();
            return Promise.resolve(true);
        };
        await registeredCommands.get("mockserver.openDashboard")!();
        assert.ok(
            openedUrl.includes("http://localhost:1080/mockserver/dashboard"),
            `unexpected dashboard URL: ${openedUrl}`
        );
    });

    await test("openDashboard honours a configured custom port", async () => {
        configValues["port"] = 2080;
        let openedUrl = "";
        vscodeStub.env.openExternal = (uri: any) => {
            openedUrl = uri.toString();
            return Promise.resolve(true);
        };
        await registeredCommands.get("mockserver.openDashboard")!();
        delete configValues["port"];
        assert.ok(openedUrl.includes(":2080/"), `port not honoured: ${openedUrl}`);
    });

    await test("activate registers mockserver.statusBarMenu command", () => {
        assert.ok(
            registeredCommands.has("mockserver.statusBarMenu"),
            "mockserver.statusBarMenu command not registered"
        );
    });

    await test("status-bar menu runs the selected action command", async () => {
        const originalQuickPick = vscodeStub.window.showQuickPick;
        const originalExecute = vscodeStub.commands.executeCommand;
        let executed = "";
        vscodeStub.window.showQuickPick = (items: any) => {
            // simulate picking the first action (Open Dashboard — the docked dashboard)
            return Promise.resolve(items[0]);
        };
        vscodeStub.commands.executeCommand = (id: string) => {
            executed = id;
            return Promise.resolve(undefined);
        };
        await registeredCommands.get("mockserver.statusBarMenu")!();
        vscodeStub.window.showQuickPick = originalQuickPick;
        vscodeStub.commands.executeCommand = originalExecute;
        assert.strictEqual(
            executed,
            "mockserver.openDashboardInEditor",
            `status-bar menu did not invoke the picked command (got ${executed})`
        );
    });

    await test("bundled expectation schema is present and well-formed", () => {
        const fs = require("fs");
        const path = require("path");
        const schemaPath = path.resolve(__dirname, "../../schemas/mockserver-expectation.schema.json");
        assert.ok(fs.existsSync(schemaPath), `schema not found at ${schemaPath}`);
        const schema = JSON.parse(fs.readFileSync(schemaPath, "utf8"));
        assert.ok(schema.definitions && schema.definitions.expectation, "missing expectation definition");
        // The root must be a concrete object/array union with inline properties (NOT a
        // root `oneOf`), so IntelliJ — which cannot navigate a root `oneOf` reached via
        // `$ref` — provides completion and validation. See generate-editor-expectation-schema.mjs.
        assert.ok(!schema.oneOf, "schema root must not be a bare oneOf (breaks IntelliJ completion/validation)");
        assert.ok(
            Array.isArray(schema.type) && schema.type.includes("object") && schema.type.includes("array"),
            "schema root should accept one expectation (object) or an array of them"
        );
        assert.ok(
            schema.properties && Object.keys(schema.properties).length > 0,
            "schema root should inline the expectation properties for IDE completion"
        );
        assert.ok(schema.items, "schema root should validate array elements via items");
    });

    await test("package.json activationEvents include onStartupFinished (passive surfaces light up on startup)", () => {
        const fs = require("fs");
        const path = require("path");
        const pkgPath = path.resolve(__dirname, "../../package.json");
        const pkg = JSON.parse(fs.readFileSync(pkgPath, "utf8"));
        assert.ok(Array.isArray(pkg.activationEvents), "activationEvents should be an array");
        assert.ok(
            pkg.activationEvents.includes("onStartupFinished"),
            "activationEvents should include onStartupFinished so the status bar and CodeLens appear on a fresh window"
        );
    });

    await test("activate registers mockserver.openDashboardInEditor command", () => {
        assert.ok(
            registeredCommands.has("mockserver.openDashboardInEditor"),
            "mockserver.openDashboardInEditor command not registered"
        );
    });

    await test("activate registers the load + diff + record + openapi commands", () => {
        assert.ok(registeredCommands.has("mockserver.loadExpectations"), "load command not registered");
        assert.ok(registeredCommands.has("mockserver.diffAgainstLive"), "diff command not registered");
        assert.ok(registeredCommands.has("mockserver.saveRecorded"), "record command not registered");
        assert.ok(registeredCommands.has("mockserver.generateFromOpenApi"), "openapi command not registered");
    });

    await test("activate registers the mockserver.sendRequest command", () => {
        assert.ok(registeredCommands.has("mockserver.sendRequest"), "sendRequest command not registered");
    });

    await test("activate registers the verify + delete expectation commands", () => {
        assert.ok(
            registeredCommands.has("mockserver.verifyExpectations"),
            "verifyExpectations command not registered"
        );
        assert.ok(
            registeredCommands.has("mockserver.deleteExpectations"),
            "deleteExpectations command not registered"
        );
    });

    await test("activate registers the mockserver.showDrift command", () => {
        assert.ok(registeredCommands.has("mockserver.showDrift"), "showDrift command not registered");
    });

    await test("activate registers the mockserver.showDriftDiagnostics command", () => {
        assert.ok(
            registeredCommands.has("mockserver.showDriftDiagnostics"),
            "showDriftDiagnostics command not registered"
        );
    });

    await test("activate registers the mockserver.viewRequestLog command", () => {
        assert.ok(
            registeredCommands.has("mockserver.viewRequestLog"),
            "viewRequestLog command not registered"
        );
    });

    await test("activate registers the mockserver.findByTrace command", () => {
        assert.ok(
            registeredCommands.has("mockserver.findByTrace"),
            "findByTrace command not registered"
        );
    });

    await test("activate registers the mockserver.reset command", () => {
        assert.ok(registeredCommands.has("mockserver.reset"), "reset command not registered");
    });

    await test("activate registers the mockserver.uploadWasm command", () => {
        assert.ok(registeredCommands.has("mockserver.uploadWasm"), "uploadWasm command not registered");
    });

    await test("activate registers the mockserver.listWasm command", () => {
        assert.ok(registeredCommands.has("mockserver.listWasm"), "listWasm command not registered");
    });

    await test("activate registers the mockserver.testWasm command", () => {
        assert.ok(registeredCommands.has("mockserver.testWasm"), "testWasm command not registered");
    });

    await test("activate registers the mockserver.refreshView command", () => {
        assert.ok(
            registeredCommands.has("mockserver.refreshView"),
            "refreshView command not registered"
        );
    });

    await test("openDashboardInEditor focuses the docked dashboard panel view (no editor tab)", async () => {
        const originalExecute = vscodeStub.commands.executeCommand;
        let focused = "";
        let createdPanel = false;
        const originalCreatePanel = vscodeStub.window.createWebviewPanel;
        vscodeStub.window.createWebviewPanel = (...args: any[]) => {
            createdPanel = true;
            return originalCreatePanel.apply(vscodeStub.window, args as any);
        };
        vscodeStub.commands.executeCommand = (id: string) => {
            focused = id;
            return Promise.resolve(undefined);
        };
        await registeredCommands.get("mockserver.openDashboardInEditor")!();
        vscodeStub.commands.executeCommand = originalExecute;
        vscodeStub.window.createWebviewPanel = originalCreatePanel;
        assert.strictEqual(
            focused,
            "mockserver.dashboard.focus",
            `expected the docked dashboard focus command (got ${focused})`
        );
        assert.ok(!createdPanel, "openDashboardInEditor must NOT create an editor-tab webview panel");
    });

    // --- Activity Bar actions tree provider ---
    const { MockServerActionsProvider, ACTION_GROUPS } = require("../actionsView");

    await test("actions tree yields a status leaf then the four groups at the root", () => {
        const provider = new MockServerActionsProvider(() => 1080);
        const roots = provider.getChildren();
        assert.strictEqual(roots[0].kind, "status", "first root node should be the status leaf");
        const groupLabels = roots
            .filter((n: any) => n.kind === "group")
            .map((n: any) => n.group.label);
        assert.deepStrictEqual(
            groupLabels,
            ["Server", "Author", "Inspect", "WASM"],
            `unexpected group labels: ${groupLabels.join(", ")}`
        );
    });

    await test("actions status leaf shows the configured port and reveals the docked dashboard", () => {
        const provider = new MockServerActionsProvider(() => 2080);
        const item = provider.getTreeItem({ kind: "status" });
        assert.ok(
            String(item.label).includes("localhost:2080"),
            `status label should include the configured port: ${item.label}`
        );
        assert.strictEqual(
            item.command.command,
            "mockserver.openDashboardInEditor",
            "status item should reveal the docked dashboard"
        );
    });

    await test("every actions-tree leaf command is a registered command", () => {
        const provider = new MockServerActionsProvider(() => 1080);
        for (const group of ACTION_GROUPS) {
            for (const child of provider.getChildren({ kind: "group", group })) {
                const id = child.leaf.command;
                assert.ok(
                    registeredCommands.has(id),
                    `tree leaf "${child.leaf.label}" points at unregistered command ${id}`
                );
                const treeItem = provider.getTreeItem(child);
                assert.strictEqual(treeItem.command.command, id, "leaf tree item command mismatch");
            }
        }
    });

    await test("package.json contributes the two viewsContainers and two views", () => {
        const fs = require("fs");
        const path = require("path");
        const pkgPath = path.resolve(__dirname, "../../package.json");
        const pkg = JSON.parse(fs.readFileSync(pkgPath, "utf8"));
        const containers = pkg.contributes.viewsContainers;
        assert.ok(
            containers.activitybar.some((c: any) => c.id === "mockserver"),
            "missing activitybar viewsContainer id 'mockserver'"
        );
        assert.ok(
            containers.panel.some((c: any) => c.id === "mockserverDashboard"),
            "missing panel viewsContainer id 'mockserverDashboard'"
        );
        const views = pkg.contributes.views;
        assert.ok(
            views.mockserver.some((v: any) => v.id === "mockserver.actions"),
            "missing view id 'mockserver.actions'"
        );
        const dashboardView = views.mockserverDashboard.find(
            (v: any) => v.id === "mockserver.dashboard"
        );
        assert.ok(dashboardView, "missing view id 'mockserver.dashboard'");
        assert.strictEqual(
            dashboardView.type,
            "webview",
            "the docked dashboard view must be a webview"
        );
    });

    await test("every package.json view/title menu command exists in contributes.commands", () => {
        const fs = require("fs");
        const path = require("path");
        const pkgPath = path.resolve(__dirname, "../../package.json");
        const pkg = JSON.parse(fs.readFileSync(pkgPath, "utf8"));
        const declared = new Set(pkg.contributes.commands.map((c: any) => c.command));
        const titleMenus = pkg.contributes.menus["view/title"];
        assert.ok(Array.isArray(titleMenus) && titleMenus.length > 0, "view/title menus missing");
        for (const m of titleMenus) {
            assert.strictEqual(
                m.when,
                "view == mockserver.actions",
                `view/title item ${m.command} has unexpected when: ${m.when}`
            );
            assert.ok(declared.has(m.command), `view/title command ${m.command} not declared`);
        }
    });

    // --- mockServerClient (pure REST helpers, exercised with a fake fetch) ---
    const client = require("../mockServerClient");

    await test("buildBaseUrl builds a localhost URL", () => {
        assert.strictEqual(client.buildBaseUrl(1080), "http://localhost:1080");
    });

    await test("buildDashboardWebviewHtml embeds the URL in an iframe and allows framing localhost", () => {
        const url = "http://localhost:1080/mockserver/dashboard";
        const html = client.buildDashboardWebviewHtml(url) as string;
        assert.ok(html.includes(`<iframe src="${url}"`), "iframe src should be the dashboard URL");
        assert.ok(
            /Content-Security-Policy[^>]*frame-src[^>]*http:\/\/localhost:\*/.test(html),
            "CSP should allow frame-src http://localhost:*"
        );
    });

    await test("parseExpectations accepts a single object and an array", () => {
        assert.deepStrictEqual(client.parseExpectations('{"httpRequest":{}}'), [{ httpRequest: {} }]);
        assert.strictEqual(client.parseExpectations('[{"a":1},{"b":2}]').length, 2);
    });

    await test("parseExpectations rejects empty array, invalid JSON, and non-objects", () => {
        assert.throws(() => client.parseExpectations("[]"), /empty array/);
        assert.throws(() => client.parseExpectations("{not json"), /Not valid JSON/);
        assert.throws(() => client.parseExpectations("42"), /Expected an expectation/);
    });

    await test("parseExpectations accepts JSONC (comments + trailing comma) without corrupting URLs", () => {
        const jsonc = `{
            // a forward to an http URL — the // inside the string must survive
            "httpRequest": { "path": "/x" },
            "httpForward": { "host": "http://example.com", "port": 80 },
        }`;
        const result = client.parseExpectations(jsonc) as any[];
        assert.strictEqual(result.length, 1);
        assert.strictEqual(result[0].httpForward.host, "http://example.com");
    });

    await test("loadExpectations PUTs an array and returns the count", async () => {
        let captured: any = {};
        const fakeFetch = (url: string, init: any) => {
            captured = { url, init };
            return Promise.resolve({ ok: true, status: 201, text: () => Promise.resolve("") });
        };
        const count = await client.loadExpectations("http://localhost:1080", '[{"a":1},{"b":2}]', fakeFetch);
        assert.strictEqual(count, 2);
        assert.strictEqual(captured.url, "http://localhost:1080/mockserver/expectation");
        assert.strictEqual(captured.init.method, "PUT");
        assert.ok(Array.isArray(JSON.parse(captured.init.body)), "body should be a JSON array");
    });

    await test("loadExpectations throws with status on a non-ok response", async () => {
        const fakeFetch = () =>
            Promise.resolve({ ok: false, status: 400, text: () => Promise.resolve("bad matcher") });
        await assert.rejects(
            () => client.loadExpectations("http://localhost:1080", '{"httpResponse":{}}', fakeFetch),
            /400: bad matcher/
        );
    });

    await test("retrieveActiveExpectations pretty-prints the server JSON", async () => {
        const fakeFetch = () =>
            Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve('[{"id":"x"}]') });
        const out = await client.retrieveActiveExpectations("http://localhost:1080", fakeFetch);
        assert.ok(out.includes('"id": "x"'), "expected pretty-printed JSON");
    });

    await test("retrieveRequestLog flags the empty log and GETs the requests type", async () => {
        let url = "";
        const emptyFetch = (u: string) => {
            url = u;
            return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve("[]") });
        };
        const empty = await client.retrieveRequestLog("http://localhost:1080", emptyFetch);
        assert.strictEqual(empty.empty, true);
        assert.ok(url.includes("/mockserver/retrieve"), `url should hit /mockserver/retrieve: ${url}`);
        assert.ok(url.includes("type=requests"), `url should use type=requests: ${url}`);
    });

    await test("retrieveRequestLog pretty-prints a 2-record log and flags non-empty", async () => {
        const payload = '[{"method":"GET","path":"/a"},{"method":"POST","path":"/b"}]';
        const dataFetch = () =>
            Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(payload) });
        const data = await client.retrieveRequestLog("http://localhost:1080", dataFetch);
        assert.strictEqual(data.empty, false);
        assert.ok(data.content.includes('"path": "/a"'), "expected pretty-printed JSON");
        assert.ok(data.content.includes('"path": "/b"'), "expected both records");
    });

    await test("retrieveRequestLog throws with status on a non-ok response", async () => {
        const fakeFetch = () =>
            Promise.resolve({ ok: false, status: 400, text: () => Promise.resolve("bad type") });
        await assert.rejects(
            () => client.retrieveRequestLog("http://localhost:1080", fakeFetch),
            /400: bad type/
        );
    });

    await test("extractTraceId pulls the trace id from a full traceparent", () => {
        assert.strictEqual(
            client.extractTraceId("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"),
            "4bf92f3577b34da6a3ce929d0e0e4736"
        );
    });

    await test("extractTraceId returns a bare 32-hex id as-is", () => {
        assert.strictEqual(
            client.extractTraceId("4bf92f3577b34da6a3ce929d0e0e4736"),
            "4bf92f3577b34da6a3ce929d0e0e4736"
        );
    });

    await test("extractTraceId lowercases an uppercase trace id and traceparent", () => {
        assert.strictEqual(
            client.extractTraceId("4BF92F3577B34DA6A3CE929D0E0E4736"),
            "4bf92f3577b34da6a3ce929d0e0e4736"
        );
        assert.strictEqual(
            client.extractTraceId("00-4BF92F3577B34DA6A3CE929D0E0E4736-00F067AA0BA902B7-01"),
            "4bf92f3577b34da6a3ce929d0e0e4736"
        );
    });

    await test("extractTraceId returns null for junk", () => {
        assert.strictEqual(client.extractTraceId("not-a-trace"), null);
        assert.strictEqual(client.extractTraceId("4bf92f35"), null); // too short
        assert.strictEqual(client.extractTraceId(""), null);
    });

    await test("requestMatchesTrace matches a traceparent header in the array form", () => {
        const request = {
            method: "GET",
            path: "/a",
            headers: [
                { name: "Host", values: ["localhost"] },
                { name: "traceparent", values: ["00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"] },
            ],
        };
        assert.strictEqual(client.requestMatchesTrace(request, "4bf92f3577b34da6a3ce929d0e0e4736"), true);
    });

    await test("requestMatchesTrace matches a traceparent header in the object-map form (the real server shape)", () => {
        // retrieve?type=requests serializes headers as { name: [values] }, not an array.
        const request = {
            path: "/a",
            headers: {
                Host: ["localhost"],
                traceparent: ["00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"],
            },
        };
        assert.strictEqual(client.requestMatchesTrace(request, "4bf92f3577b34da6a3ce929d0e0e4736"), true);
    });

    await test("requestMatchesTrace is false for a different trace id", () => {
        const request = {
            headers: [
                { name: "traceparent", values: ["00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-00f067aa0ba902b7-01"] },
            ],
        };
        assert.strictEqual(client.requestMatchesTrace(request, "4bf92f3577b34da6a3ce929d0e0e4736"), false);
    });

    await test("requestMatchesTrace is false when the request has no headers", () => {
        assert.strictEqual(client.requestMatchesTrace({ method: "GET", path: "/a" }, "4bf92f3577b34da6a3ce929d0e0e4736"), false);
        assert.strictEqual(client.requestMatchesTrace(null, "4bf92f3577b34da6a3ce929d0e0e4736"), false);
    });

    await test("filterRequestsByTrace returns the matching subset of a request log", () => {
        const log = JSON.stringify([
            {
                method: "GET",
                path: "/a",
                headers: [{ name: "traceparent", values: ["00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"] }],
            },
            {
                method: "POST",
                path: "/b",
                headers: [{ name: "traceparent", values: ["00-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb-00f067aa0ba902b7-01"] }],
            },
        ]);
        const result = client.filterRequestsByTrace(log, "4bf92f3577b34da6a3ce929d0e0e4736");
        assert.strictEqual(result.traceId, "4bf92f3577b34da6a3ce929d0e0e4736");
        assert.strictEqual(result.matches.length, 1);
        assert.strictEqual(result.matches[0].path, "/a");
    });

    await test("filterRequestsByTrace returns a null trace id for junk input", () => {
        const result = client.filterRequestsByTrace("[]", "not-a-trace");
        assert.strictEqual(result.traceId, null);
        assert.deepStrictEqual(result.matches, []);
    });

    await test("buildTraceUrl substitutes the {traceId} placeholder", () => {
        assert.strictEqual(
            client.buildTraceUrl("http://localhost:16686/trace/{traceId}", "4bf92f3577b34da6a3ce929d0e0e4736"),
            "http://localhost:16686/trace/4bf92f3577b34da6a3ce929d0e0e4736"
        );
    });

    await test("buildTraceUrl substitutes every {traceId} occurrence", () => {
        assert.strictEqual(
            client.buildTraceUrl("https://g/explore?traceId={traceId}&q={traceId}", "abc123"),
            "https://g/explore?traceId=abc123&q=abc123"
        );
    });

    await test("buildTraceUrl appends the trace id when the template has no placeholder", () => {
        assert.strictEqual(
            client.buildTraceUrl("http://localhost:16686/trace/", "abc"),
            "http://localhost:16686/trace/abc"
        );
    });

    await test("buildTraceUrl URL-encodes the trace id", () => {
        assert.strictEqual(
            client.buildTraceUrl("http://x/{traceId}", "a b/c"),
            "http://x/a%20b%2Fc"
        );
    });

    await test("buildTraceUrl returns null for a blank template", () => {
        assert.strictEqual(client.buildTraceUrl("", "abc"), null);
        assert.strictEqual(client.buildTraceUrl("   ", "abc"), null);
    });

    await test("resetServer PUTs /mockserver/reset", async () => {
        let captured: any = {};
        const fakeFetch = (url: string, init: any) => {
            captured = { url, init };
            return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve("") });
        };
        await client.resetServer("http://localhost:1080", fakeFetch);
        assert.ok(captured.url.includes("/mockserver/reset"), `url=${captured.url}`);
        assert.strictEqual(captured.init.method, "PUT");
    });

    await test("resetServer throws with status on a non-ok response", async () => {
        const fakeFetch = () =>
            Promise.resolve({ ok: false, status: 500, text: () => Promise.resolve("boom") });
        await assert.rejects(
            () => client.resetServer("http://localhost:1080", fakeFetch),
            /500: boom/
        );
    });

    await test("retrieveRecordedExpectations (json) flags empty and pretty-prints", async () => {
        const emptyFetch = () =>
            Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve("[]") });
        const empty = await client.retrieveRecordedExpectations("http://localhost:1080", "json", emptyFetch);
        assert.strictEqual(empty.empty, true);

        const dataFetch = (url: string) => {
            assert.ok(url.includes("type=recorded_expectations&format=json"), `url=${url}`);
            return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve('[{"id":"r"}]') });
        };
        const data = await client.retrieveRecordedExpectations("http://localhost:1080", "json", dataFetch);
        assert.strictEqual(data.empty, false);
        assert.ok(data.content.includes('"id": "r"'), "expected pretty-printed JSON");
    });

    await test("retrieveRecordedExpectations (java) returns the DSL verbatim and flags empty", async () => {
        const dsl = "new MockServerClient(...).when(request()...)";
        const javaFetch = (url: string) => {
            assert.ok(url.includes("format=java"), `url=${url}`);
            return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(dsl) });
        };
        const out = await client.retrieveRecordedExpectations("http://localhost:1080", "java", javaFetch);
        assert.strictEqual(out.content, dsl);
        assert.strictEqual(out.empty, false);

        const emptyJava = () =>
            Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve("   ") });
        const empty = await client.retrieveRecordedExpectations("http://localhost:1080", "java", emptyJava);
        assert.strictEqual(empty.empty, true);
    });

    await test("generateExpectationsFromOpenApi sends a JSON spec as an object", async () => {
        let captured: any = {};
        const fakeFetch = (url: string, init: any) => {
            captured = { url, init };
            return Promise.resolve({ ok: true, status: 201, text: () => Promise.resolve('[{"id":"op"}]') });
        };
        const out = await client.generateExpectationsFromOpenApi(
            "http://localhost:1080",
            '{"openapi":"3.0.0","paths":{}}',
            fakeFetch
        );
        assert.ok(captured.url.endsWith("/mockserver/openapi"), `url=${captured.url}`);
        const sent = JSON.parse(captured.init.body);
        assert.strictEqual(typeof sent.specUrlOrPayload, "object", "JSON spec should be sent as an object");
        assert.ok(out.includes('"id": "op"'), "expected pretty-printed expectations");
    });

    await test("generateExpectationsFromOpenApi sends a YAML spec as a string", async () => {
        let body: any = {};
        const fakeFetch = (_url: string, init: any) => {
            body = JSON.parse(init.body);
            return Promise.resolve({ ok: true, status: 201, text: () => Promise.resolve("[]") });
        };
        await client.generateExpectationsFromOpenApi("http://localhost:1080", "openapi: 3.0.0\npaths: {}", fakeFetch);
        assert.strictEqual(typeof body.specUrlOrPayload, "string", "YAML spec should be sent as a string");
    });

    await test("generateExpectationsFromOpenApi throws with status on a non-ok response", async () => {
        const fakeFetch = () =>
            Promise.resolve({ ok: false, status: 400, text: () => Promise.resolve("invalid spec") });
        await assert.rejects(
            () => client.generateExpectationsFromOpenApi("http://localhost:1080", "{}", fakeFetch),
            /400: invalid spec/
        );
    });

    await test("parseRequestSpec accepts a valid spec with headers and body", () => {
        const spec = client.parseRequestSpec(
            '{"method":"POST","path":"/api/x","headers":{"Accept":"application/json"},"body":"hi"}'
        );
        assert.strictEqual(spec.method, "POST");
        assert.strictEqual(spec.path, "/api/x");
        assert.deepStrictEqual(spec.headers, { Accept: "application/json" });
        assert.strictEqual(spec.body, "hi");
    });

    await test("parseRequestSpec rejects a missing method", () => {
        assert.throws(() => client.parseRequestSpec('{"path":"/api/x"}'), /missing a "method"/);
    });

    await test("parseRequestSpec rejects a missing path", () => {
        assert.throws(() => client.parseRequestSpec('{"method":"GET"}'), /missing a "path"/);
    });

    await test("parseRequestSpec rejects invalid JSON", () => {
        assert.throws(() => client.parseRequestSpec("{not json"), /Not valid JSON/);
    });

    await test("sendScratchRequest passes url, method, headers, body to fetch and returns status+body", async () => {
        let captured: any = {};
        const fakeFetch = (url: string, init: any) => {
            captured = { url, init };
            return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve('{"ok":true}') });
        };
        const spec = {
            method: "POST",
            path: "/api/x",
            headers: { Accept: "application/json" },
            body: "payload",
        };
        const response = await client.sendScratchRequest("http://localhost:1080", spec, fakeFetch);
        assert.strictEqual(captured.url, "http://localhost:1080/api/x");
        assert.strictEqual(captured.init.method, "POST");
        assert.deepStrictEqual(captured.init.headers, { Accept: "application/json" });
        assert.strictEqual(captured.init.body, "payload");
        assert.strictEqual(response.status, 200);
        assert.strictEqual(response.body, '{"ok":true}');
    });

    await test("toRequestDefinition maps headers to the object-map form and omits empty parts", () => {
        const full = client.toRequestDefinition({
            method: "POST",
            path: "/api/x",
            headers: { Accept: "application/json" },
            body: "hi",
        });
        assert.strictEqual(full.method, "POST");
        assert.strictEqual(full.path, "/api/x");
        assert.deepStrictEqual(full.headers, { Accept: ["application/json"] });
        assert.strictEqual(full.body, "hi");
        const minimal = client.toRequestDefinition({ method: "GET", path: "/a" });
        assert.ok(!("headers" in minimal), "empty headers should be omitted");
        assert.ok(!("body" in minimal), "absent body should be omitted");
    });

    await test("parseMatchAnalysis reports a match when any result matches", () => {
        const body = JSON.stringify({
            totalExpectations: 2,
            results: [
                { expectationId: "e1", matches: false, differences: { path: ["no"] } },
                { expectationId: "e2", matches: true },
            ],
        });
        const analysis = client.parseMatchAnalysis(body);
        assert.strictEqual(analysis.matched, true);
        assert.strictEqual(analysis.expectationId, "e2");
        assert.strictEqual(analysis.noExpectations, false);
    });

    await test("parseMatchAnalysis surfaces the closest miss with its differences", () => {
        const body = JSON.stringify({
            totalExpectations: 1,
            results: [
                {
                    expectationId: "e1",
                    matches: false,
                    differences: { path: ["expected /a but was /b"] },
                },
            ],
            closestMatch: { expectationId: "e1", matchedFields: 4, totalFields: 5 },
        });
        const analysis = client.parseMatchAnalysis(body);
        assert.strictEqual(analysis.matched, false);
        assert.strictEqual(analysis.expectationId, "e1");
        assert.strictEqual(analysis.matchedFields, 4);
        assert.deepStrictEqual(analysis.differences.path, ["expected /a but was /b"]);
    });

    await test("parseMatchAnalysis flags noExpectations when the server has none", () => {
        const analysis = client.parseMatchAnalysis(JSON.stringify({ totalExpectations: 0, results: [] }));
        assert.strictEqual(analysis.matched, false);
        assert.strictEqual(analysis.noExpectations, true);
    });

    await test("formatMatchAnalysis renders matched, no-expectations, and nearest-miss summaries", () => {
        assert.ok(
            client.formatMatchAnalysis({ matched: true, expectationId: "e2", differences: {}, noExpectations: false })
                .includes("Matched"),
            "matched summary should say Matched"
        );
        assert.ok(
            client.formatMatchAnalysis({ matched: false, differences: {}, noExpectations: true })
                .includes("No registered expectations"),
            "no-expectations summary should be explicit"
        );
        const miss = client.formatMatchAnalysis({
            matched: false,
            expectationId: "e1",
            matchedFields: 4,
            totalFields: 5,
            differences: { path: ["expected /a but was /b"] },
            noExpectations: false,
        });
        assert.ok(miss.includes("Did not match"), "miss summary should say it did not match");
        assert.ok(miss.includes("Closest: expectation e1"), "miss summary should name the closest expectation");
        assert.ok(miss.includes("path: expected /a but was /b"), "miss summary should list the field difference");
    });

    await test("analyseMatch PUTs the request definition to debugMismatch", async () => {
        let captured: any = {};
        const fakeFetch = (url: string, init: any) => {
            captured = { url, init };
            return Promise.resolve({
                ok: true,
                status: 200,
                text: () => Promise.resolve(JSON.stringify({ totalExpectations: 1, results: [{ matches: true }] })),
            });
        };
        const analysis = await client.analyseMatch(
            "http://localhost:1080",
            { method: "GET", path: "/a" },
            fakeFetch
        );
        assert.ok(captured.url.endsWith("/mockserver/debugMismatch"), `url=${captured.url}`);
        assert.strictEqual(captured.init.method, "PUT");
        assert.strictEqual(JSON.parse(captured.init.body).path, "/a");
        assert.strictEqual(analysis.matched, true);
    });

    await test("analyseMatch throws with status on a non-ok response (e.g. endpoint missing)", async () => {
        const fakeFetch = () =>
            Promise.resolve({ ok: false, status: 404, text: () => Promise.resolve("no such endpoint") });
        await assert.rejects(
            () => client.analyseMatch("http://localhost:1080", { method: "GET", path: "/a" }, fakeFetch),
            /404: no such endpoint/
        );
    });

    await test("extractRequestDefinitions pulls each expectation's httpRequest and skips request-less ones", () => {
        const text = JSON.stringify([
            { httpRequest: { method: "GET", path: "/a" }, httpResponse: { statusCode: 200 } },
            { httpResponse: { statusCode: 204 } },
            { httpRequest: { path: "/b" } },
        ]);
        const defs = client.extractRequestDefinitions(text);
        assert.strictEqual(defs.length, 2, "should extract two request definitions");
        assert.strictEqual(defs[0].path, "/a");
        assert.strictEqual(defs[1].path, "/b");
    });

    await test("verifyRequestReceived returns verified on 202 and a reason on 406", async () => {
        let captured: any = {};
        const okFetch = (url: string, init: any) => {
            captured = { url, init };
            return Promise.resolve({ ok: true, status: 202, text: () => Promise.resolve("") });
        };
        const ok = await client.verifyRequestReceived("http://localhost:1080", { path: "/a" }, okFetch);
        assert.strictEqual(ok.verified, true);
        assert.ok(captured.url.endsWith("/mockserver/verify"), `url=${captured.url}`);
        const sent = JSON.parse(captured.init.body);
        assert.deepStrictEqual(sent.httpRequest, { path: "/a" });
        assert.strictEqual(sent.times.atLeast, 1);
        assert.strictEqual(sent.times.atMost, -1, "atMost must be -1 (no upper bound) or the server always 406s");

        const failFetch = () =>
            Promise.resolve({ ok: false, status: 406, text: () => Promise.resolve("Request not found at least once") });
        const failed = await client.verifyRequestReceived("http://localhost:1080", { path: "/a" }, failFetch);
        assert.strictEqual(failed.verified, false);
        assert.ok(failed.reason.includes("not found"), "should carry the failure reason");
    });

    await test("clearExpectations clears each declared request via clear?type=expectations", async () => {
        const urls: string[] = [];
        const fakeFetch = (url: string) => {
            urls.push(url);
            return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve("") });
        };
        const text = JSON.stringify([
            { httpRequest: { path: "/a" }, httpResponse: {} },
            { httpRequest: { path: "/b" }, httpResponse: {} },
        ]);
        const count = await client.clearExpectations("http://localhost:1080", text, fakeFetch);
        assert.strictEqual(count, 2);
        assert.ok(urls.every((u) => u.includes("/mockserver/clear?type=expectations")), `urls=${urls.join(",")}`);
    });

    await test("retrieveDrift flags the empty state (count 0, no drifts)", async () => {
        const fakeFetch = () =>
            Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve('{"count":0,"drifts":[]}') });
        const out = await client.retrieveDrift("http://localhost:1080", fakeFetch);
        assert.strictEqual(out.empty, true);
        assert.strictEqual(out.count, 0);
    });

    await test("retrieveDrift summarises records and GETs /mockserver/drift", async () => {
        let captured: any = {};
        const payload = {
            count: 2,
            drifts: [
                {
                    expectationId: "exp-1",
                    driftType: "HEADER_CHANGED",
                    field: "$.status",
                    expectedValue: "active",
                    actualValue: "archived",
                    confidence: 0.9,
                    epochTimeMs: 1,
                },
                {
                    expectationId: "exp-2",
                    driftType: "FIELD_ADDED",
                    field: "$.newField",
                    confidence: 0.5,
                    epochTimeMs: 2,
                },
            ],
        };
        const fakeFetch = (url: string, init: any) => {
            captured = { url, init };
            return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify(payload)) });
        };
        const out = await client.retrieveDrift("http://localhost:1080", fakeFetch);
        assert.strictEqual(out.empty, false);
        assert.strictEqual(out.count, 2);
        assert.ok(captured.url.includes("/mockserver/drift"), `url=${captured.url}`);
        assert.strictEqual(captured.init.method, "GET");
        assert.ok(out.report.includes("HEADER_CHANGED"), "report should include the driftType");
        assert.ok(out.report.includes("$.status"), "report should include the field");
        assert.ok(out.report.includes("FIELD_ADDED"), "report should include the second driftType");
        assert.ok(out.report.includes("— "), "missing value should render as an em dash");
    });

    await test("retrieveDrift passes the limit as a query param when provided", async () => {
        let url = "";
        const fakeFetch = (u: string) => {
            url = u;
            return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve('{"count":0,"drifts":[]}') });
        };
        await client.retrieveDrift("http://localhost:1080", fakeFetch, 10);
        assert.ok(url.includes("/mockserver/drift?limit=10"), `url=${url}`);
    });

    await test("retrieveDrift throws with status on a non-ok response", async () => {
        const fakeFetch = () =>
            Promise.resolve({ ok: false, status: 404, text: () => Promise.resolve("drift not enabled") });
        await assert.rejects(
            () => client.retrieveDrift("http://localhost:1080", fakeFetch),
            /404: drift not enabled/
        );
    });

    await test("retrieveDriftRecords returns the parsed drifts array and GETs /mockserver/drift", async () => {
        let captured: any = {};
        const payload = {
            count: 2,
            drifts: [
                { expectationId: "exp-1", driftType: "STATUS", field: "$.status", confidence: 1.0, epochTimeMs: 1 },
                { expectationId: "exp-2", driftType: "SCHEMA_FIELD_ADDED", field: "$.newField", confidence: 0.5, epochTimeMs: 2 },
            ],
        };
        const fakeFetch = (url: string, init: any) => {
            captured = { url, init };
            return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify(payload)) });
        };
        const records = await client.retrieveDriftRecords("http://localhost:1080", fakeFetch);
        assert.strictEqual(records.length, 2);
        assert.strictEqual(records[0].expectationId, "exp-1");
        assert.ok(captured.url.includes("/mockserver/drift"), `url=${captured.url}`);
        assert.strictEqual(captured.init.method, "GET");
    });

    await test("retrieveDriftRecords returns [] when the server reports no drift", async () => {
        const fakeFetch = () =>
            Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve('{"count":0,"drifts":[]}') });
        const records = await client.retrieveDriftRecords("http://localhost:1080", fakeFetch);
        assert.deepStrictEqual(records, []);
    });

    await test("retrieveDriftRecords returns [] on a non-JSON body", async () => {
        const fakeFetch = () =>
            Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve("not json") });
        const records = await client.retrieveDriftRecords("http://localhost:1080", fakeFetch);
        assert.deepStrictEqual(records, []);
    });

    await test("retrieveDriftRecords throws with status on a non-ok response", async () => {
        const fakeFetch = () =>
            Promise.resolve({ ok: false, status: 404, text: () => Promise.resolve("drift not enabled") });
        await assert.rejects(
            () => client.retrieveDriftRecords("http://localhost:1080", fakeFetch),
            /404: drift not enabled/
        );
    });

    await test("mapDriftToDiagnostics anchors a record to the line of its matching expectation id", () => {
        const docText = [
            "[",
            "  {",
            '    "id": "exp-1",',
            '    "httpRequest": { "path": "/a" }',
            "  }",
            "]",
        ].join("\n");
        const records = [
            { expectationId: "exp-1", driftType: "HEADER_CHANGED", field: "$.x", confidence: 0.8, epochTimeMs: 1 },
        ];
        const diags = client.mapDriftToDiagnostics(records, docText);
        assert.strictEqual(diags.length, 1);
        assert.strictEqual(diags[0].line, 2, "should anchor to the line containing the matching id");
    });

    await test("mapDriftToDiagnostics falls back to line 0 when no expectation id matches", () => {
        const docText = '[{"id":"other"}]';
        const records = [
            { expectationId: "missing", driftType: "HEADER_CHANGED", field: "$.x", confidence: 0.8, epochTimeMs: 1 },
        ];
        const diags = client.mapDriftToDiagnostics(records, docText);
        assert.strictEqual(diags[0].line, 0, "no match should fall back to line 0");
    });

    await test("mapDriftToDiagnostics maps STATUS drift to error and SCHEMA_FIELD_ADDED to warning", () => {
        const records = [
            { expectationId: "a", driftType: "STATUS", field: "$.status", confidence: 0.2, epochTimeMs: 1 },
            { expectationId: "b", driftType: "SCHEMA_FIELD_ADDED", field: "$.newField", confidence: 0.2, epochTimeMs: 2 },
            { expectationId: "c", driftType: "HEADER_CHANGED", field: "$.x", confidence: 0.2, epochTimeMs: 3 },
        ];
        const diags = client.mapDriftToDiagnostics(records, "{}");
        assert.strictEqual(diags[0].severity, "error", "STATUS drift should be error");
        assert.strictEqual(diags[1].severity, "warning", "SCHEMA_FIELD_ADDED should be warning");
        assert.strictEqual(diags[2].severity, "info", "value change should be info");
    });

    await test("mapDriftToDiagnostics message includes the driftType and field, and — for absent values", () => {
        const records = [
            { expectationId: "a", driftType: "SCHEMA_FIELD_ADDED", field: "$.newField", confidence: 0.5, epochTimeMs: 1 },
        ];
        const diags = client.mapDriftToDiagnostics(records, "{}");
        assert.ok(diags[0].message.includes("SCHEMA_FIELD_ADDED"), "message should include driftType");
        assert.ok(diags[0].message.includes("$.newField"), "message should include field");
        assert.ok(diags[0].message.includes("—"), "absent values should render as an em dash");
        assert.ok(diags[0].message.includes("confidence 0.5"), "message should include confidence");
    });

    await test("buildWasmUploadUrl builds the modules URL with the encoded name", () => {
        assert.strictEqual(
            client.buildWasmUploadUrl("http://localhost:1080", "my rule"),
            "http://localhost:1080/mockserver/wasm/modules?name=my%20rule"
        );
    });

    await test("uploadWasmModule PUTs the raw bytes with octet-stream and the encoded name", async () => {
        let captured: any = {};
        const fakeFetch = (url: string, init: any) => {
            captured = { url, init };
            return Promise.resolve({ ok: true, status: 201, text: () => Promise.resolve("") });
        };
        const bytes = new Uint8Array([0, 97, 115, 109, 1, 2, 3]);
        await client.uploadWasmModule("http://localhost:1080", "rule one", bytes, fakeFetch);
        assert.strictEqual(captured.init.method, "PUT");
        assert.ok(captured.url.includes("/mockserver/wasm/modules"), `url=${captured.url}`);
        assert.ok(captured.url.includes("name=rule%20one"), `url should carry the encoded name: ${captured.url}`);
        assert.strictEqual(
            captured.init.headers["Content-Type"],
            "application/octet-stream",
            "should send octet-stream content type"
        );
        assert.strictEqual(captured.init.body, bytes, "should send the exact Uint8Array passed in");
    });

    await test("uploadWasmModule surfaces the 403 wasm-disabled message verbatim on a non-ok response", async () => {
        const disabled = "WASM support is disabled; set wasmEnabled=true to enable";
        const fakeFetch = () =>
            Promise.resolve({ ok: false, status: 403, text: () => Promise.resolve(disabled) });
        await assert.rejects(
            () => client.uploadWasmModule("http://localhost:1080", "r", new Uint8Array([0]), fakeFetch),
            new RegExp(`403: ${disabled}`)
        );
    });

    await test("retrieveWasmModules GETs /mockserver/wasm/modules and pretty-prints the list", async () => {
        let captured: any = {};
        const fakeFetch = (url: string, init: any) => {
            captured = { url, init };
            return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve('["ruleA","ruleB"]') });
        };
        const out = await client.retrieveWasmModules("http://localhost:1080", fakeFetch);
        assert.ok(captured.url.endsWith("/mockserver/wasm/modules"), `url=${captured.url}`);
        assert.strictEqual(captured.init.method, "GET");
        assert.ok(out.includes('"ruleA"'), "expected pretty-printed module names");
        assert.ok(out.includes('"ruleB"'), "expected both module names");
    });

    await test("retrieveWasmModules throws with status on a non-ok response", async () => {
        const fakeFetch = () =>
            Promise.resolve({ ok: false, status: 403, text: () => Promise.resolve("WASM support is disabled") });
        await assert.rejects(
            () => client.retrieveWasmModules("http://localhost:1080", fakeFetch),
            /403: WASM support is disabled/
        );
    });

    await test("testWasmModule POSTs base64 module + sample request and returns matched", async () => {
        let captured: any = {};
        const fakeFetch = (url: string, init: any) => {
            captured = { url, init };
            return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve('{"matched":true}') });
        };
        const bytes = new Uint8Array([0, 97, 115, 109, 1, 2, 3]);
        const matched = await client.testWasmModule(
            "http://localhost:1080",
            bytes,
            { method: "POST", path: "/orders", queryStringParameters: { tenant: ["acme"] }, cookies: { session: "abc123" } },
            fakeFetch
        );
        assert.strictEqual(matched, true, "should return the server's matched result");
        assert.ok(captured.url.endsWith("/mockserver/wasm/test"), `url=${captured.url}`);
        assert.strictEqual(captured.init.method, "POST");
        assert.strictEqual(captured.init.headers["Content-Type"], "application/json");
        const sent = JSON.parse(captured.init.body);
        assert.strictEqual(
            sent.module,
            Buffer.from(bytes).toString("base64"),
            "module should be base64-encoded"
        );
        assert.strictEqual(sent.request.method, "POST");
        assert.strictEqual(sent.request.path, "/orders");
        assert.deepStrictEqual(sent.request.queryStringParameters, { tenant: ["acme"] });
        assert.deepStrictEqual(sent.request.cookies, { session: "abc123" });
    });

    await test("testWasmModule returns false when the server reports no match", async () => {
        const fakeFetch = () =>
            Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve('{"matched":false}') });
        const matched = await client.testWasmModule(
            "http://localhost:1080",
            new Uint8Array([0]),
            { method: "GET", path: "/" },
            fakeFetch
        );
        assert.strictEqual(matched, false);
    });

    await test("testWasmModule surfaces the 403 wasm-disabled message verbatim on a non-ok response", async () => {
        const disabled = "WASM support is disabled; set wasmEnabled=true to enable";
        const fakeFetch = () =>
            Promise.resolve({ ok: false, status: 403, text: () => Promise.resolve(disabled) });
        await assert.rejects(
            () => client.testWasmModule("http://localhost:1080", new Uint8Array([0]), {}, fakeFetch),
            new RegExp(`403: ${disabled}`)
        );
    });

    await test("looksLikeOpenApiSpec detects specs and rejects expectations/junk", () => {
        assert.strictEqual(client.looksLikeOpenApiSpec('{"openapi":"3.0.0","paths":{}}'), true);
        assert.strictEqual(client.looksLikeOpenApiSpec('{"swagger":"2.0","paths":{}}'), true);
        assert.strictEqual(client.looksLikeOpenApiSpec("openapi: 3.0.0\npaths: {}"), true);
        assert.strictEqual(client.looksLikeOpenApiSpec('{"httpRequest":{},"httpResponse":{}}'), false);
        assert.strictEqual(client.looksLikeOpenApiSpec('[{"httpResponse":{}}]'), false);
        assert.strictEqual(client.looksLikeOpenApiSpec('{"note":"openapi is great"}'), false);
        assert.strictEqual(client.looksLikeOpenApiSpec("just text"), false);
    });

    // --- CodeLens providers ---
    const { ExpectationCodeLensProvider, ScratchRequestCodeLensProvider } = require("../codeLens");

    await test("CodeLens provider offers load + verify + diff + delete lenses", () => {
        const provider = new ExpectationCodeLensProvider();
        const lenses = provider.provideCodeLenses({ uri: { toString: () => "file:///x.mockserver.json" } });
        const commands = lenses.map((l: any) => l.command.command);
        assert.deepStrictEqual(commands, [
            "mockserver.loadExpectations",
            "mockserver.verifyExpectations",
            "mockserver.diffAgainstLive",
            "mockserver.deleteExpectations",
        ]);
    });

    await test("scratch-request CodeLens provider offers a send lens", () => {
        const provider = new ScratchRequestCodeLensProvider();
        const lenses = provider.provideCodeLenses({ uri: { toString: () => "file:///x.mockserver-request.json" } });
        assert.strictEqual(lenses.length, 1);
        assert.strictEqual(lenses[0].command.command, "mockserver.sendRequest");
    });

    // --- Phase 5: breakpoint protocol (pure, frozen WS contract) ---
    const bp = require("../breakpointProtocol");

    await test("parseInboundMessage decodes a WebSocketClientIdDTO registration", () => {
        const raw = JSON.stringify({
            type: "org.mockserver.serialization.model.WebSocketClientIdDTO",
            value: JSON.stringify({ clientId: "client-42" }),
        });
        const parsed = bp.parseInboundMessage(raw);
        assert.strictEqual(parsed.kind, "clientId");
        assert.strictEqual(parsed.clientId, "client-42");
    });

    await test("parseInboundMessage decodes a REQUEST-phase HttpRequest with breakpoint headers", () => {
        const request = {
            method: "GET",
            path: "/api/x",
            headers: {
                WebSocketCorrelationId: ["corr-1"],
                "X-MockServer-BreakpointId": ["bp-1"],
                "X-MockServer-RequestTimestamp": ["1700000000000"],
            },
        };
        const raw = JSON.stringify({ type: "org.mockserver.model.HttpRequest", value: JSON.stringify(request) });
        const parsed = bp.parseInboundMessage(raw);
        assert.strictEqual(parsed.kind, "exchange");
        assert.strictEqual(parsed.exchange.phase, "REQUEST");
        assert.strictEqual(parsed.exchange.correlationId, "corr-1");
        assert.strictEqual(parsed.exchange.breakpointId, "bp-1");
        assert.strictEqual(parsed.exchange.requestTimestamp, 1700000000000);
    });

    await test("parseInboundMessage decodes a RESPONSE-phase HttpRequestAndHttpResponse", () => {
        const pair = {
            httpRequest: { path: "/a", headers: { websocketcorrelationid: "corr-2" } },
            httpResponse: { statusCode: 200, body: "ok" },
        };
        const raw = JSON.stringify({
            type: "org.mockserver.model.HttpRequestAndHttpResponse",
            value: JSON.stringify(pair),
        });
        const parsed = bp.parseInboundMessage(raw);
        assert.strictEqual(parsed.kind, "exchange");
        assert.strictEqual(parsed.exchange.phase, "RESPONSE");
        assert.strictEqual(parsed.exchange.correlationId, "corr-2");
        assert.strictEqual(parsed.exchange.httpResponse.statusCode, 200);
    });

    await test("parseInboundMessage decodes a PausedStreamFrameDTO", () => {
        const frame = {
            correlationId: "corr-3",
            streamId: "s-1",
            sequenceNumber: 2,
            direction: "OUTBOUND",
            phase: "RESPONSE_STREAM",
            body: "aGVsbG8=",
        };
        const raw = JSON.stringify({
            type: "org.mockserver.serialization.model.PausedStreamFrameDTO",
            value: JSON.stringify(frame),
        });
        const parsed = bp.parseInboundMessage(raw);
        assert.strictEqual(parsed.kind, "streamFrame");
        assert.strictEqual(parsed.frame.streamId, "s-1");
        assert.strictEqual(parsed.frame.sequenceNumber, 2);
    });

    await test("parseInboundMessage ignores junk and unknown types", () => {
        assert.strictEqual(bp.parseInboundMessage("not json").kind, "ignored");
        assert.strictEqual(
            bp.parseInboundMessage(JSON.stringify({ type: "org.mockserver.model.Unknown", value: "{}" })).kind,
            "ignored"
        );
    });

    await test("REQUEST-phase CONTINUE replies with the original HttpRequest echoing the correlation id", () => {
        const exchange = {
            phase: "REQUEST",
            correlationId: "corr-1",
            breakpointId: "bp-1",
            requestTimestamp: null,
            httpRequest: { method: "GET", path: "/a" },
        };
        const reply = bp.buildRequestPhaseReply(exchange, { action: "CONTINUE" });
        assert.strictEqual(reply.type, "org.mockserver.model.HttpRequest");
        const value = JSON.parse(reply.value);
        assert.strictEqual(value.path, "/a");
        assert.deepStrictEqual(value.headers.WebSocketCorrelationId, ["corr-1"]);
    });

    await test("REQUEST-phase MODIFY replies with the replacement HttpRequest", () => {
        const exchange = {
            phase: "REQUEST",
            correlationId: "corr-1",
            breakpointId: null,
            requestTimestamp: null,
            httpRequest: { method: "GET", path: "/a" },
        };
        const reply = bp.buildRequestPhaseReply(exchange, {
            action: "MODIFY",
            replacement: { method: "POST", path: "/b" },
        });
        assert.strictEqual(reply.type, "org.mockserver.model.HttpRequest");
        const value = JSON.parse(reply.value);
        assert.strictEqual(value.method, "POST");
        assert.strictEqual(value.path, "/b");
        assert.deepStrictEqual(value.headers.WebSocketCorrelationId, ["corr-1"]);
    });

    await test("REQUEST-phase ABORT replies with an HttpResponse (write downstream, do not forward)", () => {
        const exchange = {
            phase: "REQUEST",
            correlationId: "corr-1",
            breakpointId: null,
            requestTimestamp: null,
            httpRequest: { method: "GET", path: "/a" },
        };
        const reply = bp.buildRequestPhaseReply(exchange, {
            action: "ABORT",
            response: { statusCode: 503, body: "nope" },
        });
        assert.strictEqual(reply.type, "org.mockserver.model.HttpResponse");
        const value = JSON.parse(reply.value);
        assert.strictEqual(value.statusCode, 503);
        assert.deepStrictEqual(value.headers.WebSocketCorrelationId, ["corr-1"]);
    });

    await test("REQUEST-phase MODIFY into a response shape (statusCode) becomes an HttpResponse ABORT", () => {
        const exchange = {
            phase: "REQUEST",
            correlationId: "c",
            breakpointId: null,
            requestTimestamp: null,
            httpRequest: { method: "GET", path: "/a" },
        };
        const reply = bp.buildRequestPhaseReply(exchange, {
            action: "MODIFY",
            replacement: { statusCode: 418 },
        });
        assert.strictEqual(reply.type, "org.mockserver.model.HttpResponse");
    });

    await test("RESPONSE-phase CONTINUE replies with the original HttpResponse echoing the correlation id", () => {
        const exchange = {
            phase: "RESPONSE",
            correlationId: "corr-2",
            breakpointId: null,
            requestTimestamp: null,
            httpRequest: { path: "/a" },
            httpResponse: { statusCode: 200, body: "ok" },
        };
        const reply = bp.buildResponsePhaseReply(exchange, { action: "CONTINUE" });
        assert.strictEqual(reply.type, "org.mockserver.model.HttpResponse");
        const value = JSON.parse(reply.value);
        assert.strictEqual(value.statusCode, 200);
        assert.deepStrictEqual(value.headers.WebSocketCorrelationId, ["corr-2"]);
    });

    await test("RESPONSE-phase MODIFY replies with the replacement HttpResponse", () => {
        const exchange = {
            phase: "RESPONSE",
            correlationId: "corr-2",
            breakpointId: null,
            requestTimestamp: null,
            httpRequest: { path: "/a" },
            httpResponse: { statusCode: 200 },
        };
        const reply = bp.buildResponsePhaseReply(exchange, {
            action: "MODIFY",
            replacement: { statusCode: 404, body: "gone" },
        });
        const value = JSON.parse(reply.value);
        assert.strictEqual(value.statusCode, 404);
        assert.strictEqual(value.body, "gone");
    });

    await test("buildResponsePhaseReply rejects ABORT (REQUEST-phase only)", () => {
        assert.throws(
            () =>
                bp.buildResponsePhaseReply(
                    { phase: "RESPONSE", correlationId: "c", breakpointId: null, requestTimestamp: null, httpRequest: {}, httpResponse: {} },
                    { action: "ABORT", response: { statusCode: 503 } }
                ),
            /ABORT is not a valid RESPONSE-phase decision/
        );
    });

    await test("buildStreamFrameReply throws when MODIFY/INJECT has no body", () => {
        const frame = { correlationId: "c", streamId: "s", sequenceNumber: 0, direction: "OUTBOUND", phase: "RESPONSE_STREAM", body: "aGk=" };
        assert.throws(() => bp.buildStreamFrameReply(frame, { action: "MODIFY" }), /Base64 body is required/);
        assert.throws(() => bp.buildStreamFrameReply(frame, { action: "INJECT" }), /Base64 body is required/);
    });

    await test("buildDecisionReply dispatches on phase", () => {
        const req = bp.buildDecisionReply(
            { phase: "REQUEST", correlationId: "c", breakpointId: null, requestTimestamp: null, httpRequest: { path: "/a" } },
            { action: "CONTINUE" }
        );
        assert.strictEqual(req.type, "org.mockserver.model.HttpRequest");
        const resp = bp.buildDecisionReply(
            { phase: "RESPONSE", correlationId: "c", breakpointId: null, requestTimestamp: null, httpRequest: {}, httpResponse: {} },
            { action: "CONTINUE" }
        );
        assert.strictEqual(resp.type, "org.mockserver.model.HttpResponse");
    });

    await test("abortAllowed is true only for REQUEST phase", () => {
        assert.strictEqual(bp.abortAllowed("REQUEST"), true);
        assert.strictEqual(bp.abortAllowed("RESPONSE"), false);
    });

    await test("buildStreamFrameReply echoes the correlation id and includes body only for MODIFY/INJECT", () => {
        const frame = { correlationId: "corr-3", streamId: "s", sequenceNumber: 0, direction: "OUTBOUND", phase: "RESPONSE_STREAM", body: "aGk=" };
        const cont = JSON.parse(bp.buildStreamFrameReply(frame, { action: "CONTINUE" }).value);
        assert.strictEqual(cont.correlationId, "corr-3");
        assert.strictEqual(cont.action, "CONTINUE");
        assert.ok(!("body" in cont), "CONTINUE must not carry a body");
        const modify = JSON.parse(bp.buildStreamFrameReply(frame, { action: "MODIFY", body: "Ynll" }).value);
        assert.strictEqual(modify.body, "Ynll");
        const drop = JSON.parse(bp.buildStreamFrameReply(frame, { action: "DROP" }).value);
        assert.ok(!("body" in drop), "DROP must not carry a body");
        const replyType = bp.buildStreamFrameReply(frame, { action: "CLOSE" }).type;
        assert.strictEqual(replyType, "org.mockserver.serialization.model.StreamFrameDecisionDTO");
    });

    await test("buildMatcherRegistrationBody requires clientId and includes skipCount only when positive", () => {
        const base = bp.buildMatcherRegistrationBody({ path: "/a" }, ["REQUEST"], "client-1");
        assert.deepStrictEqual(base, { httpRequest: { path: "/a" }, phases: ["REQUEST"], clientId: "client-1" });
        const skip = bp.buildMatcherRegistrationBody({ path: "/a" }, ["REQUEST"], "client-1", 3);
        assert.strictEqual(skip.skipCount, 3);
        const zero = bp.buildMatcherRegistrationBody({ path: "/a" }, ["REQUEST"], "client-1", 0);
        assert.ok(!("skipCount" in zero), "skipCount 0 must be omitted");
    });

    // --- Phase 5: breakpoint client (fake socket, no real network) ---
    const { BreakpointClient, buildCallbackWsUrl } = require("../breakpointClient");

    function makeFakeSocket() {
        const listeners: Record<string, ((arg?: any) => void)[]> = {};
        const sent: string[] = [];
        let closed = false;
        return {
            sent,
            isClosed: () => closed,
            emit(event: string, arg?: any) {
                (listeners[event] || []).forEach((l) => l(arg));
            },
            socket: {
                send: (data: string) => sent.push(data),
                close: () => { closed = true; },
                on: (event: string, listener: (arg?: any) => void) => {
                    (listeners[event] = listeners[event] || []).push(listener);
                },
            },
        };
    }

    await test("buildCallbackWsUrl targets the local callback websocket endpoint", () => {
        assert.strictEqual(
            buildCallbackWsUrl(1080),
            "ws://localhost:1080/_mockserver_callback_websocket"
        );
    });

    await test("BreakpointClient routes a clientId message to onClientId and exposes it", () => {
        const fake = makeFakeSocket();
        let clientId = "";
        const c = new BreakpointClient("ws://x", () => fake.socket, {
            onClientId: (id: string) => { clientId = id; },
            onExchange: () => {},
            onStreamFrame: () => {},
            onState: () => {},
        });
        c.connect();
        fake.emit("message", JSON.stringify({
            type: "org.mockserver.serialization.model.WebSocketClientIdDTO",
            value: JSON.stringify({ clientId: "abc" }),
        }));
        assert.strictEqual(clientId, "abc");
        assert.strictEqual(c.getClientId(), "abc");
    });

    await test("BreakpointClient routes a paused exchange and reply() sends an envelope over the socket", () => {
        const fake = makeFakeSocket();
        let exchange: any;
        const c = new BreakpointClient("ws://x", () => fake.socket, {
            onClientId: () => {},
            onExchange: (e: any) => { exchange = e; },
            onStreamFrame: () => {},
            onState: () => {},
        });
        c.connect();
        fake.emit("message", JSON.stringify({
            type: "org.mockserver.model.HttpRequest",
            value: JSON.stringify({ path: "/a", headers: { WebSocketCorrelationId: ["c1"] } }),
        }));
        assert.ok(exchange, "exchange handler should fire");
        c.reply(bp.buildDecisionReply(exchange, { action: "CONTINUE" }));
        assert.strictEqual(fake.sent.length, 1);
        const sent = JSON.parse(fake.sent[0]);
        assert.strictEqual(sent.type, "org.mockserver.model.HttpRequest");
    });

    await test("BreakpointClient reports connection state transitions and close()", () => {
        const fake = makeFakeSocket();
        const states: string[] = [];
        const c = new BreakpointClient("ws://x", () => fake.socket, {
            onClientId: () => {},
            onExchange: () => {},
            onStreamFrame: () => {},
            onState: (s: string) => states.push(s),
        });
        c.connect();
        fake.emit("open");
        assert.ok(states.includes("connecting"), "should report connecting");
        assert.ok(states.includes("connected"), "should report connected on open");
        c.close();
        assert.ok(fake.isClosed(), "user close should close the socket");
    });

    // --- Phase 4: code-aware usage detection (pure regex scan) ---
    const codeAware = require("../codeAware");

    await test("detectMockServerUsage finds new MockServerClient(...), @MockServerSettings, and MockServerContainer", () => {
        const src = [
            "public class T {",
            "  @MockServerSettings(ports = {1080})",
            "  void t() {",
            "    var client = new MockServerClient(\"localhost\", 1080);",
            "    MockServerContainer ms = new MockServerContainer(IMG);",
            "  }",
            "}",
        ].join("\n");
        const hits = codeAware.detectMockServerUsage(src);
        const kinds = hits.map((h: any) => h.kind);
        assert.ok(kinds.includes("client"), "should detect MockServerClient");
        assert.ok(kinds.includes("junit5"), "should detect @MockServerSettings");
        assert.ok(kinds.includes("testcontainers"), "should detect MockServerContainer");
    });

    await test("detectMockServerUsage returns one hit per line and correct zero-based lines", () => {
        const src = "x\nnew MockServerClient()\n";
        const hits = codeAware.detectMockServerUsage(src);
        assert.strictEqual(hits.length, 1);
        assert.strictEqual(hits[0].line, 1);
    });

    await test("isCodeAwareFile matches code extensions and rejects json", () => {
        assert.strictEqual(codeAware.isCodeAwareFile("/a/T.java"), true);
        assert.strictEqual(codeAware.isCodeAwareFile("/a/T.kt"), true);
        assert.strictEqual(codeAware.isCodeAwareFile("/a/x.mockserver.json"), false);
    });

    await test("CodeAwareCodeLensProvider yields a lens per usage in a code file and none for json", () => {
        const { CodeAwareCodeLensProvider } = require("../codeLens");
        const provider = new CodeAwareCodeLensProvider();
        const javaLenses = provider.provideCodeLenses({
            uri: { fsPath: "/a/T.java" },
            getText: () => "new MockServerClient()\n@MockServerSettings\n",
        });
        assert.strictEqual(javaLenses.length, 2);
        assert.strictEqual(javaLenses[0].command.command, "mockserver.openDashboardInEditor");
        const jsonLenses = provider.provideCodeLenses({
            uri: { fsPath: "/a/x.mockserver.json" },
            getText: () => "new MockServerClient()",
        });
        assert.strictEqual(jsonLenses.length, 0, "json files are not code-aware-scanned");
    });

    // --- Phase 4: drift quick-fix edit computation (pure) ---
    const driftFix = require("../driftFix");

    await test("computeDriftFixEdit swaps the stub's expected value for the upstream actual value", () => {
        const docText = [
            "[",
            "  {",
            '    "id": "exp-1",',
            '    "httpResponse": { "statusCode": 200 }',
            "  }",
            "]",
        ].join("\n");
        const record = {
            expectationId: "exp-1",
            driftType: "STATUS",
            field: "$.statusCode",
            expectedValue: 200,
            actualValue: 503,
            confidence: 1.0,
            epochTimeMs: 1,
        };
        const edit = driftFix.computeDriftFixEdit(record, docText);
        assert.ok(edit, "an edit should be computed");
        assert.strictEqual(edit.replacement, "503");
        // Applying the edit should replace the 200 literal with 503.
        const applied = docText.slice(0, edit.start) + edit.replacement + docText.slice(edit.end);
        assert.ok(applied.includes('"statusCode": 503'), `applied edit wrong: ${applied}`);
    });

    await test("computeDriftFixEdit returns null when the value cannot be located or values are absent", () => {
        const docText = '[{"id":"exp-1","httpResponse":{"statusCode":200}}]';
        assert.strictEqual(
            driftFix.computeDriftFixEdit(
                { expectationId: "exp-1", driftType: "SCHEMA_FIELD_ADDED", field: "$.x", confidence: 1, epochTimeMs: 1 },
                docText
            ),
            null,
            "no expected/actual value → no edit"
        );
        assert.strictEqual(
            driftFix.computeDriftFixEdit(
                { expectationId: "missing", driftType: "STATUS", field: "$.s", expectedValue: 999, actualValue: 503, confidence: 1, epochTimeMs: 1 },
                docText
            ),
            null,
            "unlocatable expectation/value → no edit"
        );
    });

    await test("computeDriftFixEdit scopes the value search to the matching expectation", () => {
        const docText = [
            '[{"id":"exp-1","httpResponse":{"statusCode":200}},',
            '{"id":"exp-2","httpResponse":{"statusCode":200}}]',
        ].join("\n");
        const edit = driftFix.computeDriftFixEdit(
            { expectationId: "exp-2", driftType: "STATUS", field: "$.statusCode", expectedValue: 200, actualValue: 404, confidence: 1, epochTimeMs: 1 },
            docText
        );
        assert.ok(edit, "edit for exp-2 should be found");
        // The edit must fall within the exp-2 region (after the exp-2 id), not exp-1's 200.
        assert.ok(edit.start > docText.indexOf('"exp-2"'), "edit must target exp-2's value, not exp-1's");
    });

    await test("describeDriftFix renders a readable, type-aware action title", () => {
        assert.ok(
            client.describeDriftFix({ expectationId: "a", driftType: "SCHEMA_FIELD_ADDED", field: "$.newField", actualValue: 1, confidence: 1, epochTimeMs: 1 }).includes("add $.newField")
        );
        assert.ok(
            client.describeDriftFix({ expectationId: "a", driftType: "STATUS", field: "$.statusCode", expectedValue: 200, actualValue: 503, confidence: 1, epochTimeMs: 1 }).includes("set $.statusCode to 503")
        );
    });

    await test("mapDriftToDiagnostics attaches the raw record for the quick-fix to map back", () => {
        const records = [
            { expectationId: "exp-1", driftType: "STATUS", field: "$.s", expectedValue: 200, actualValue: 503, confidence: 1, epochTimeMs: 1 },
        ];
        const diags = client.mapDriftToDiagnostics(records, '[{"id":"exp-1"}]');
        assert.strictEqual(diags[0].record.expectationId, "exp-1");
        assert.strictEqual(diags[0].record.actualValue, 503);
    });

    // --- Phase 5/6: breakpoint matcher + chaos + contract REST helpers ---
    await test("registerBreakpointMatcher PUTs the body and returns the server id+phases", async () => {
        let captured: any = {};
        const fakeFetch = (url: string, init: any) => {
            captured = { url, init };
            return Promise.resolve({ ok: true, status: 201, text: () => Promise.resolve('{"id":"bp-9","phases":["REQUEST"]}') });
        };
        const out = await client.registerBreakpointMatcher(
            "http://localhost:1080",
            bp.buildMatcherRegistrationBody({ path: "/a" }, ["REQUEST"], "client-1"),
            fakeFetch
        );
        assert.ok(captured.url.endsWith("/mockserver/breakpoint/matcher"), `url=${captured.url}`);
        assert.strictEqual(captured.init.method, "PUT");
        assert.strictEqual(JSON.parse(captured.init.body).clientId, "client-1");
        assert.strictEqual(out.id, "bp-9");
        assert.deepStrictEqual(out.phases, ["REQUEST"]);
    });

    await test("listBreakpointMatchers GETs /breakpoint/matchers and returns the matchers array", async () => {
        let url = "";
        const fakeFetch = (u: string) => {
            url = u;
            return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve('{"matchers":[{"id":"a","phases":["REQUEST"]}]}') });
        };
        const out = await client.listBreakpointMatchers("http://localhost:1080", fakeFetch);
        assert.ok(url.endsWith("/mockserver/breakpoint/matchers"), `url=${url}`);
        assert.strictEqual(out.length, 1);
        assert.strictEqual(out[0].id, "a");
    });

    await test("removeBreakpointMatcher and clearBreakpointMatchers PUT the right endpoints", async () => {
        const urls: string[] = [];
        const fakeFetch = (url: string) => {
            urls.push(url);
            return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve("") });
        };
        await client.removeBreakpointMatcher("http://localhost:1080", "id-1", fakeFetch);
        await client.clearBreakpointMatchers("http://localhost:1080", fakeFetch);
        assert.ok(urls[0].endsWith("/mockserver/breakpoint/matcher/remove"), `url0=${urls[0]}`);
        assert.ok(urls[1].endsWith("/mockserver/breakpoint/matcher/clear"), `url1=${urls[1]}`);
    });

    await test("getChaosExperimentStatus GETs the endpoint and formatChaosStatus summarises a run", async () => {
        let captured: any = {};
        const status = { name: "exp", status: "running", currentStageIndex: 1, totalStages: 3, stageElapsedMillis: 1000, stageRemainingMillis: 4000, loopIteration: 0, totalElapsedMillis: 5000 };
        const fakeFetch = (url: string, init: any) => {
            captured = { url, init };
            return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify(status)) });
        };
        const out = await client.getChaosExperimentStatus("http://localhost:1080", fakeFetch);
        assert.ok(captured.url.endsWith("/mockserver/chaosExperiment"), `url=${captured.url}`);
        assert.strictEqual(captured.init.method, "GET");
        assert.strictEqual(out.status, "running");
        const text = client.formatChaosStatus(out);
        assert.ok(text.includes("running"), "summary should include status");
        assert.ok(text.includes("Stage 2 of 3"), "summary should show 1-based stage progress");
    });

    await test("startChaosExperiment PUTs and stopChaosExperiment DELETEs the endpoint", async () => {
        const calls: any[] = [];
        const fakeFetch = (url: string, init: any) => {
            calls.push({ url, method: init?.method });
            return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve("") });
        };
        await client.startChaosExperiment("http://localhost:1080", { name: "e", stages: [] }, fakeFetch);
        await client.stopChaosExperiment("http://localhost:1080", fakeFetch);
        assert.strictEqual(calls[0].method, "PUT");
        assert.strictEqual(calls[1].method, "DELETE");
    });

    await test("runContractTest PUTs /contractTest and formatContractTestReport lists per-operation pass/fail", async () => {
        let captured: any = {};
        const report = {
            baseUrl: "http://svc:8080",
            totalOperations: 2,
            passed: 1,
            failed: 1,
            allPassed: false,
            results: [
                { operationId: "getA", method: "GET", path: "/a", statusCodeReceived: 200, passed: true, validationErrors: [] },
                { operationId: "getB", method: "GET", path: "/b", statusCodeReceived: 500, passed: false, validationErrors: ["body did not match schema"] },
            ],
        };
        const fakeFetch = (url: string, init: any) => {
            captured = { url, init };
            return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify(report)) });
        };
        const out = await client.runContractTest("http://localhost:1080", { spec: "openapi: 3.0.0", baseUrl: "http://svc:8080" }, fakeFetch);
        assert.ok(captured.url.endsWith("/mockserver/contractTest"), `url=${captured.url}`);
        assert.strictEqual(JSON.parse(captured.init.body).baseUrl, "http://svc:8080");
        const text = client.formatContractTestReport(out);
        assert.ok(text.includes("1/2 passed"), "summary should show pass count");
        assert.ok(text.includes("✗ GET /b"), "failing op should be marked");
        assert.ok(text.includes("body did not match schema"), "validation errors should be listed");
    });

    // --- Phase 6: agent-run call graph (pure transform + MCP parse) ---
    const callGraph = require("../callGraph");

    await test("toMermaid renders nodes and edges as a flowchart matching the dashboard", () => {
        const graph = {
            nodes: [
                { id: "u1", kind: "USER", label: "hello" },
                { id: "tc1", kind: "TOOL_CALL", label: "search(x)" },
            ],
            edges: [{ from: "u1", to: "tc1", kind: "INVOKES" }],
        };
        const mermaid = callGraph.toMermaid(graph);
        assert.ok(mermaid.startsWith("flowchart TD"), "should be a flowchart");
        assert.ok(mermaid.includes("u1[\"USER: hello\"]"), "message node shape");
        assert.ok(mermaid.includes("tc1(["), "tool-call node shape");
        assert.ok(mermaid.includes("u1 -->|INVOKES| tc1"), "edge with kind label");
    });

    await test("parseCallGraph is defensive about missing/typeless fields", () => {
        const g = callGraph.parseCallGraph({ nodes: [{ id: "a" }, { kind: "x" }], edges: [{ from: "a", to: "b" }, { from: "a" }] });
        assert.strictEqual(g.nodes.length, 1, "only nodes with a string id survive");
        assert.strictEqual(g.nodes[0].kind, "UNKNOWN");
        assert.strictEqual(g.edges.length, 1, "only edges with from+to survive");
        assert.strictEqual(callGraph.parseCallGraph(null), null);
    });

    await test("parseMcpToolResult unwraps result.content[0].text JSON and detects error envelopes", () => {
        const wrapped = JSON.stringify({ result: { content: [{ text: JSON.stringify({ callGraph: { nodes: [], edges: [] } }) }] } });
        const out = callGraph.parseMcpToolResult(wrapped);
        assert.ok(out && out.callGraph, "should unwrap the tool result JSON");
        assert.strictEqual(callGraph.parseMcpToolResult(JSON.stringify({ error: { message: "boom" } })), null);
        assert.strictEqual(callGraph.parseMcpToolResult("not json"), null);
    });

    await test("fetchAgentCallGraph runs the MCP init handshake then tools/call and parses the graph", async () => {
        const urls: string[] = [];
        let toolBody: any;
        const fakeFetch = (url: string, init: any) => {
            urls.push(url);
            const body = init && init.body ? JSON.parse(init.body) : {};
            if (body.method === "initialize") {
                return Promise.resolve({
                    ok: true, status: 200,
                    text: () => Promise.resolve("{}"),
                    header: (n: string) => (n === "Mcp-Session-Id" ? "sess-1" : null),
                });
            }
            if (body.method === "tools/call") {
                toolBody = body;
                return Promise.resolve({
                    ok: true, status: 200,
                    text: () => Promise.resolve(JSON.stringify({
                        result: { content: [{ text: JSON.stringify({ callGraph: { nodes: [{ id: "n1", kind: "USER", label: "hi" }], edges: [] } }) }] },
                    })),
                    header: () => null,
                });
            }
            // notifications/initialized
            return Promise.resolve({ ok: true, status: 202, text: () => Promise.resolve(""), header: () => null });
        };
        const graph = await callGraph.fetchAgentCallGraph("http://localhost:1080", { sessionId: "s" }, fakeFetch);
        assert.ok(graph, "graph should be returned");
        assert.strictEqual(graph.nodes.length, 1);
        assert.strictEqual(toolBody.params.name, "explain_agent_run");
        assert.ok(urls.every((u: string) => u.endsWith("/mockserver/mcp")), "all calls hit /mockserver/mcp");
    });

    await test("fetchAgentCallGraph throws when the MCP transport fails", async () => {
        const fakeFetch = () =>
            Promise.resolve({ ok: false, status: 404, text: () => Promise.resolve("no mcp"), header: () => null });
        await assert.rejects(
            () => callGraph.fetchAgentCallGraph("http://localhost:1080", {}, fakeFetch),
            /MCP initialize failed/
        );
    });

    // --- Phase 6: LLM authoring completion (pure) ---
    const llm = require("../llmCompletion");

    await test("isInsideLlmResponse detects the cursor inside an httpLlmResponse block", () => {
        assert.strictEqual(llm.isInsideLlmResponse('{ "httpLlmResponse": { "provider": '), true);
        assert.strictEqual(llm.isInsideLlmResponse('{ "httpLlmResponse": { "provider": "OPENAI" } }'), false);
        assert.strictEqual(llm.isInsideLlmResponse('{ "httpResponse": { '), false);
    });

    await test("llmSuggestions offers providers after provider:, models after model:, fields otherwise", () => {
        const providers = llm.llmSuggestions('{ "httpLlmResponse": { "provider": "');
        assert.ok(providers.some((s: any) => s.insertText === "OPENAI"), "should suggest providers");
        const models = llm.llmSuggestions('{ "httpLlmResponse": { "model": "');
        assert.ok(models.some((s: any) => s.insertText === "gpt-4o"), "should suggest models");
        const fields = llm.llmSuggestions('{ "httpLlmResponse": { ');
        assert.ok(fields.some((s: any) => s.insertText === "completion"), "should suggest fields");
    });

    await test("llmSuggestions offers the nested completion fields inside a completion object", () => {
        const inside = '{ "httpLlmResponse": { "provider": "OPENAI", "completion": { ';
        assert.strictEqual(llm.isInsideCompletion(inside), true);
        const fields = llm.llmSuggestions(inside).map((s: any) => s.insertText);
        for (const expected of ["text", "usage", "streaming", "stopReason"]) {
            assert.ok(fields.includes(expected), `should suggest completion.${expected}`);
        }
        // top-level-only fields must not leak into the nested object
        assert.ok(!fields.includes("provider"), "provider is not a completion field");

        // once the completion object closes, the top-level fields come back
        const after = '{ "httpLlmResponse": { "completion": { "text": "hi" }, ';
        assert.strictEqual(llm.isInsideCompletion(after), false);
        assert.ok(llm.llmSuggestions(after).some((s: any) => s.insertText === "provider"));
    });

    // Guards issue #2455: the completion catalogue invented providers (OPEN_AI, VERTEX_AI) and fields
    // (finishReason, stream, top-level usage) that the server rejects with
    // `400 incorrect expectation json format`. Asserting against the BUNDLED SCHEMA — the same one
    // MockServer validates with — is what makes that impossible to reintroduce; asserting the
    // catalogue against itself is what let it ship.
    await test("every LLM provider suggested is accepted by the bundled expectation schema", () => {
        const fs = require("fs");
        const path = require("path");
        const schema = JSON.parse(
            fs.readFileSync(path.resolve(__dirname, "../../schemas/mockserver-expectation.schema.json"), "utf8")
        );
        const allowed: string[] = schema.definitions.httpLlmResponse.properties.provider.enum;
        const unknown = llm.LLM_PROVIDERS.filter((p: string) => !allowed.includes(p));
        assert.deepStrictEqual(unknown, [], `providers the server rejects: ${unknown.join(", ")}`);
    });

    await test("every LLM field suggested is a real property of its schema object", () => {
        const fs = require("fs");
        const path = require("path");
        const schema = JSON.parse(
            fs.readFileSync(path.resolve(__dirname, "../../schemas/mockserver-expectation.schema.json"), "utf8")
        );
        const httpLlmResponse = schema.definitions.httpLlmResponse;
        const topLevel = Object.keys(httpLlmResponse.properties);
        const unknownTopLevel = llm.LLM_FIELDS.map((f: any) => f.insertText).filter(
            (f: string) => !topLevel.includes(f)
        );
        assert.deepStrictEqual(unknownTopLevel, [], `not httpLlmResponse properties: ${unknownTopLevel.join(", ")}`);

        const completionFields = Object.keys(httpLlmResponse.properties.completion.properties);
        const unknownNested = llm.LLM_COMPLETION_FIELDS.map((f: any) => f.insertText).filter(
            (f: string) => !completionFields.includes(f)
        );
        assert.deepStrictEqual(unknownNested, [], `not completion properties: ${unknownNested.join(", ")}`);
    });

    // --- Phase 5: debugger panel HTML render (pure string-building) ---
    const { renderDebuggerHtml } = require("../breakpointPanel");

    await test("renderDebuggerHtml shows the through-MockServer prerequisite when no exchanges are paused", () => {
        const html = renderDebuggerHtml({ state: "connected", clientId: "c1", items: [] });
        assert.ok(html.includes("flowing"), "should explain breakpoints fire only on traffic through MockServer");
        assert.ok(html.includes("c1"), "should show the clientId");
        assert.ok(html.includes("🟢"), "connected state dot");
    });

    await test("renderDebuggerHtml offers Abort only for REQUEST-phase exchanges", () => {
        const reqHtml = renderDebuggerHtml({
            state: "connected", clientId: "c",
            items: [{ kind: "exchange", exchange: { phase: "REQUEST", correlationId: "x", breakpointId: null, requestTimestamp: null, httpRequest: { method: "GET", path: "/a" } } }],
        });
        assert.ok(reqHtml.includes(">Abort<"), "REQUEST phase should offer Abort");
        const respHtml = renderDebuggerHtml({
            state: "connected", clientId: "c",
            items: [{ kind: "exchange", exchange: { phase: "RESPONSE", correlationId: "y", breakpointId: null, requestTimestamp: null, httpRequest: { path: "/a" }, httpResponse: { statusCode: 200 } } }],
        });
        assert.ok(!respHtml.includes(">Abort<"), "RESPONSE phase must NOT offer Abort");
    });

    await test("renderDebuggerHtml escapes HTML in request fields (no injection)", () => {
        const html = renderDebuggerHtml({
            state: "connected", clientId: "c",
            items: [{ kind: "exchange", exchange: { phase: "REQUEST", correlationId: "z", breakpointId: null, requestTimestamp: null, httpRequest: { method: "GET", path: "/<script>" } } }],
        });
        assert.ok(!html.includes("/<script>"), "raw path must be HTML-escaped");
        assert.ok(html.includes("&lt;script&gt;"), "path should appear escaped");
    });

    console.log(`\nResults: ${passed} passed, ${failed} failed, ${passed + failed} total`);
    if (failed > 0) {
        process.exit(1);
    }
}

runTests().catch((e) => {
    console.error(e);
    process.exit(1);
});

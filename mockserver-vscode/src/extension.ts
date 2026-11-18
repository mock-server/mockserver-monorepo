import * as vscode from "vscode";
import * as path from "path";
import * as fs from "fs";
import { execFileSync, execFile, spawn, ChildProcess } from "child_process";
import * as bundle from "./binaryBundle";
import {
    ExpectationCodeLensProvider,
    EXPECTATION_FILE_SELECTOR,
    ScratchRequestCodeLensProvider,
    REQUEST_FILE_SELECTOR,
    CodeAwareCodeLensProvider,
    CODE_AWARE_FILE_SELECTOR,
} from "./codeLens";
import * as client from "./mockServerClient";
import { MockServerActionsProvider } from "./actionsView";
import { MockServerDashboardViewProvider } from "./dashboardView";
import { DriftQuickFixProvider, DRIFT_DIAGNOSTIC_CODE } from "./driftQuickFix";
import { BreakpointDebuggerPanel } from "./breakpointPanel";
import { BreakpointClient, buildCallbackWsUrl, CallbackSocket } from "./breakpointClient";
import { buildMatcherRegistrationBody } from "./breakpointProtocol";
import { LlmCompletionProvider } from "./llmCompletionProvider";
import { fetchAgentCallGraph, toMermaid, FetchWithHeaders } from "./callGraph";

let outputChannel: vscode.OutputChannel;
let extensionVersion = "latest";

// The extension context, captured in activate(). Needed by the binary-bundle
// launch commands to resolve the global-storage cache directory.
let extensionContext: vscode.ExtensionContext;

// The MockServer process started from a binary bundle (no Docker), tracked so the
// Stop command can terminate it and Start can detect an already-running instance.
// undefined when no bundle process is running in this window.
let binaryProcess: ChildProcess | undefined;

// Collection backing the inline drift diagnostics shown on expectation files.
// Created in activate() so it shares the extension lifecycle.
let driftDiagnostics: vscode.DiagnosticCollection;

// Status-bar entry point. Shows the configured port and, on click, a quick-pick
// of the most useful actions. No live health polling — the label just reflects
// the configured port (read fresh when refreshed), not a probed server state.
let statusBarItem: vscode.StatusBarItem;

// Adapter from the global fetch to the small FetchLike used by mockServerClient.
const httpFetch: client.FetchLike = async (url, init) => {
    const res = await fetch(url, init);
    return { ok: res.ok, status: res.status, text: () => res.text() };
};

// Adapter that also exposes a response header (the MCP session id), for the
// agent-run call-graph fetch over POST /mockserver/mcp.
const httpFetchWithHeaders: FetchWithHeaders = async (url, init) => {
    const res = await fetch(url, init);
    return {
        ok: res.ok,
        status: res.status,
        text: () => res.text(),
        header: (name: string) => res.headers.get(name),
    };
};

// The single in-IDE debugger panel (Phase 5) and the drift quick-fix provider
// (Phase 4). Created in activate() so they share the extension lifecycle.
let debuggerPanel: BreakpointDebuggerPanel | undefined;
let driftQuickFixProvider: DriftQuickFixProvider;

// Backing store for the read-only "live expectations" documents shown in the
// diff view, exposed under the `mockserver-live` URI scheme.
const LIVE_SCHEME = "mockserver-live";
const liveContent = new Map<string, string>();
const liveContentChanged = new vscode.EventEmitter<vscode.Uri>();
const liveContentProvider: vscode.TextDocumentContentProvider = {
    onDidChange: liveContentChanged.event,
    provideTextDocumentContent(uri: vscode.Uri): string {
        return liveContent.get(uri.toString()) ?? "";
    },
};

interface MockServerConfig {
    dockerImage: string;
    containerName: string;
    port: number;
    traceUrlTemplate: string;
    // Absolute path to a locally-installed binary bundle: either the launcher
    // executable itself or the unpacked bundle root directory. Blank means "use
    // the cached download under global storage" (see the binary launch commands).
    binaryPath: string;
    // Version of the binary bundle to download/use. Blank ⇒ this extension's own
    // version, so the bundle stays in lockstep with the release.
    binaryVersion: string;
}

// Reads settings fresh on each use so changes apply without reloading the window.
// The Docker image tag defaults to the extension's OWN version, so it stays in
// lockstep with the release and never drifts from a hardcoded constant.
function getConfig(): MockServerConfig {
    const cfg = vscode.workspace.getConfiguration("mockserver");
    const configuredImage = (cfg.get<string>("dockerImage") ?? "").trim();
    const configuredPort = cfg.get<number>("port");
    const validPort =
        typeof configuredPort === "number" && configuredPort >= 1 && configuredPort <= 65535;
    return {
        dockerImage: configuredImage || `mockserver/mockserver:${extensionVersion}`,
        containerName: cfg.get<string>("containerName") || "mockserver-vscode",
        port: validPort ? configuredPort : 1080,
        traceUrlTemplate: (cfg.get<string>("traceUrlTemplate") ?? "").trim(),
        binaryPath: (cfg.get<string>("binaryPath") ?? "").trim(),
        binaryVersion: (cfg.get<string>("binaryVersion") ?? "").trim() || extensionVersion,
    };
}

// Build the status-bar item: a server glyph + the configured port, clickable to
// open the quick-pick of common actions. Kept deliberately simple (no polling).
function createStatusBarItem(): vscode.StatusBarItem {
    const item = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
    item.text = `$(server) MockServer :${getConfig().port}`;
    item.tooltip = "MockServer — click for actions";
    item.command = "mockserver.statusBarMenu";
    return item;
}

// Quick-pick of the most useful actions, invoked from the status-bar click.
// Each pick runs an existing command id — no new behaviour, just discoverability.
async function showStatusBarMenu(): Promise<void> {
    const { port } = getConfig();
    const actions: Array<{ label: string; command: string }> = [
        { label: "$(dashboard) Open Dashboard", command: "mockserver.openDashboardInEditor" },
        { label: "$(globe) Open Dashboard in Browser", command: "mockserver.openDashboard" },
        { label: "$(play) Start (Docker)", command: "mockserver.start" },
        { label: "$(rocket) Start (binary, no Docker)", command: "mockserver.startBinary" },
        { label: "$(debug-stop) Stop", command: "mockserver.stop" },
        { label: "$(debug-stop) Stop (binary)", command: "mockserver.stopBinary" },
        { label: "$(list-unordered) View Request Log", command: "mockserver.viewRequestLog" },
    ];
    const pick = await vscode.window.showQuickPick(actions, {
        placeHolder: `MockServer on port ${port}`,
    });
    if (pick) {
        await vscode.commands.executeCommand(pick.command);
    }
}

export function activate(context: vscode.ExtensionContext): void {
    outputChannel = vscode.window.createOutputChannel("MockServer");
    extensionContext = context;
    extensionVersion = (context.extension.packageJSON as { version?: string }).version ?? "latest";

    const startCmd = vscode.commands.registerCommand("mockserver.start", startMockServer);
    const stopCmd = vscode.commands.registerCommand("mockserver.stop", stopMockServer);
    const startBinaryCmd = vscode.commands.registerCommand("mockserver.startBinary", startMockServerBinary);
    const stopBinaryCmd = vscode.commands.registerCommand("mockserver.stopBinary", stopMockServerBinary);
    const dashboardCmd = vscode.commands.registerCommand("mockserver.openDashboard", openDashboard);
    const dashboardInEditorCmd = vscode.commands.registerCommand(
        "mockserver.openDashboardInEditor",
        openDashboardInEditor
    );
    const loadCmd = vscode.commands.registerCommand("mockserver.loadExpectations", loadExpectations);
    const diffCmd = vscode.commands.registerCommand("mockserver.diffAgainstLive", diffAgainstLive);
    const verifyCmd = vscode.commands.registerCommand("mockserver.verifyExpectations", verifyExpectations);
    const deleteCmd = vscode.commands.registerCommand("mockserver.deleteExpectations", deleteExpectations);
    const recordCmd = vscode.commands.registerCommand("mockserver.saveRecorded", saveRecordedExpectations);
    const openApiCmd = vscode.commands.registerCommand("mockserver.generateFromOpenApi", generateFromOpenApi);
    const sendRequestCmd = vscode.commands.registerCommand("mockserver.sendRequest", sendRequest);
    const showDriftCmd = vscode.commands.registerCommand("mockserver.showDrift", showDrift);
    const showDriftDiagnosticsCmd = vscode.commands.registerCommand(
        "mockserver.showDriftDiagnostics",
        showDriftDiagnostics
    );
    const viewRequestLogCmd = vscode.commands.registerCommand("mockserver.viewRequestLog", viewRequestLog);
    const findByTraceCmd = vscode.commands.registerCommand("mockserver.findByTrace", findByTrace);
    const viewTraceCmd = vscode.commands.registerCommand("mockserver.viewTrace", viewTrace);
    const resetCmd = vscode.commands.registerCommand("mockserver.reset", resetServer);
    const uploadWasmCmd = vscode.commands.registerCommand("mockserver.uploadWasm", uploadWasm);
    const listWasmCmd = vscode.commands.registerCommand("mockserver.listWasm", listWasm);
    const testWasmCmd = vscode.commands.registerCommand("mockserver.testWasm", testWasm);
    const statusBarMenuCmd = vscode.commands.registerCommand(
        "mockserver.statusBarMenu",
        showStatusBarMenu
    );
    // Phase 5 debugger
    const openDebuggerCmd = vscode.commands.registerCommand("mockserver.openDebugger", openDebugger);
    const addBreakpointCmd = vscode.commands.registerCommand("mockserver.addBreakpoint", addBreakpoint);
    const clearBreakpointsCmd = vscode.commands.registerCommand(
        "mockserver.clearBreakpoints",
        clearBreakpoints
    );
    // Phase 6 chaos / contract-test / call-graph
    const chaosStatusCmd = vscode.commands.registerCommand("mockserver.chaosStatus", showChaosStatus);
    const stopChaosCmd = vscode.commands.registerCommand("mockserver.stopChaos", stopChaos);
    const contractTestCmd = vscode.commands.registerCommand("mockserver.contractTest", runContractTest);
    const callGraphCmd = vscode.commands.registerCommand("mockserver.showCallGraph", showCallGraph);

    // Activity Bar "Actions" tree (the grouped buttons). The provider reads the
    // configured port fresh for its status item; refreshView re-fires its change
    // event so the status item re-reads the port after a settings change.
    const actionsProvider = new MockServerActionsProvider(() => getConfig().port);
    const actionsView = vscode.window.registerTreeDataProvider(
        "mockserver.actions",
        actionsProvider
    );
    const refreshViewCmd = vscode.commands.registerCommand("mockserver.refreshView", () =>
        actionsProvider.refresh()
    );

    // Docked dashboard in the bottom Panel (NOT an editor tab). retainContextWhenHidden
    // keeps the framed dashboard alive when the panel is hidden.
    const dashboardProvider = new MockServerDashboardViewProvider(() => getConfig().port);
    const dashboardView = vscode.window.registerWebviewViewProvider(
        MockServerDashboardViewProvider.viewType,
        dashboardProvider,
        { webviewOptions: { retainContextWhenHidden: true } }
    );

    statusBarItem = createStatusBarItem();
    statusBarItem.show();

    driftDiagnostics = vscode.languages.createDiagnosticCollection("mockserver-drift");

    const codeLensProvider = vscode.languages.registerCodeLensProvider(
        EXPECTATION_FILE_SELECTOR,
        new ExpectationCodeLensProvider()
    );
    const requestCodeLensProvider = vscode.languages.registerCodeLensProvider(
        REQUEST_FILE_SELECTOR,
        new ScratchRequestCodeLensProvider()
    );
    // Phase 4: run/inspect CodeLens above MockServer usage sites in source files.
    const codeAwareCodeLensProvider = vscode.languages.registerCodeLensProvider(
        CODE_AWARE_FILE_SELECTOR,
        new CodeAwareCodeLensProvider()
    );
    // Phase 4: drift quick-fix lightbulb on drift diagnostics.
    driftQuickFixProvider = new DriftQuickFixProvider();
    const driftCodeActionProvider = vscode.languages.registerCodeActionsProvider(
        EXPECTATION_FILE_SELECTOR,
        driftQuickFixProvider,
        { providedCodeActionKinds: DriftQuickFixProvider.providedCodeActionKinds }
    );
    // Phase 6: LLM authoring completion inside httpLlmResponse blocks.
    const llmCompletionProvider = vscode.languages.registerCompletionItemProvider(
        EXPECTATION_FILE_SELECTOR,
        new LlmCompletionProvider(),
        '"',
        ":"
    );
    // Phase 5: the in-IDE HTTP debugger panel (one per session).
    debuggerPanel = new BreakpointDebuggerPanel(context.extensionUri, (handlers) => {
        const url = buildCallbackWsUrl(getConfig().port);
        return new BreakpointClient(url, defaultSocketFactory, handlers);
    });
    const contentProvider = vscode.workspace.registerTextDocumentContentProvider(
        LIVE_SCHEME,
        liveContentProvider
    );

    context.subscriptions.push(
        startCmd,
        stopCmd,
        startBinaryCmd,
        stopBinaryCmd,
        dashboardCmd,
        dashboardInEditorCmd,
        loadCmd,
        diffCmd,
        verifyCmd,
        deleteCmd,
        recordCmd,
        openApiCmd,
        sendRequestCmd,
        showDriftCmd,
        showDriftDiagnosticsCmd,
        viewRequestLogCmd,
        findByTraceCmd,
        viewTraceCmd,
        resetCmd,
        uploadWasmCmd,
        listWasmCmd,
        testWasmCmd,
        statusBarMenuCmd,
        openDebuggerCmd,
        addBreakpointCmd,
        clearBreakpointsCmd,
        chaosStatusCmd,
        stopChaosCmd,
        contractTestCmd,
        callGraphCmd,
        refreshViewCmd,
        actionsView,
        dashboardView,
        statusBarItem,
        codeLensProvider,
        requestCodeLensProvider,
        codeAwareCodeLensProvider,
        driftCodeActionProvider,
        llmCompletionProvider,
        contentProvider,
        liveContentChanged,
        driftDiagnostics,
        outputChannel,
        { dispose: () => debuggerPanel?.dispose() }
    );
}

export function deactivate(): void {
    // Terminate a bundle-launched MockServer so it doesn't outlive the window.
    if (binaryProcess) {
        killBinaryProcess(binaryProcess);
        binaryProcess = undefined;
    }
}

async function startMockServer(): Promise<void> {
    const { dockerImage, containerName, port } = getConfig();
    // Pass arguments as an array (no shell) so a container name or image from
    // settings cannot be interpreted as a shell command.
    const runArgs = ["run", "-d", "--rm", "--name", containerName, "-p", `${port}:1080`, dockerImage];

    outputChannel.appendLine(`Starting MockServer (${dockerImage}) on port ${port}...`);
    outputChannel.show(true);

    try {
        // Check if Docker is available
        execFileSync("docker", ["info"], { stdio: "pipe" });
    } catch {
        vscode.window.showErrorMessage(
            "Docker is not running. Please start Docker Desktop and try again."
        );
        return;
    }

    // Check if container is already running
    try {
        const running = execFileSync(
            "docker",
            ["ps", "--filter", `name=${containerName}`, "--format", "{{.Names}}"],
            { encoding: "utf-8" }
        ).trim();
        if (running === containerName) {
            vscode.window.showInformationMessage(
                `MockServer is already running on port ${port}.`
            );
            return;
        }
    } catch {
        // Ignore — proceed with start
    }

    execFile("docker", runArgs, (error, stdout, stderr) => {
        if (error) {
            const msg = stderr || error.message;
            outputChannel.appendLine(`Error: ${msg}`);
            vscode.window.showErrorMessage(`Failed to start MockServer: ${msg}`);
            return;
        }
        const containerId = stdout.trim().substring(0, 12);
        outputChannel.appendLine(`MockServer started (container: ${containerId}).`);
        vscode.window.showInformationMessage(
            `MockServer started on http://localhost:${port}`
        );
    });
}

async function stopMockServer(): Promise<void> {
    const { containerName } = getConfig();
    outputChannel.appendLine("Stopping MockServer...");
    outputChannel.show(true);

    execFile("docker", ["stop", containerName], (error, _stdout, stderr) => {
        if (error) {
            if (stderr.includes("No such container") || stderr.includes("not found")) {
                vscode.window.showWarningMessage("MockServer container is not running.");
            } else {
                vscode.window.showErrorMessage(`Failed to stop MockServer: ${stderr || error.message}`);
            }
            outputChannel.appendLine(`Stop result: ${stderr || error.message}`);
            return;
        }
        outputChannel.appendLine("MockServer stopped.");
        vscode.window.showInformationMessage("MockServer stopped.");
    });
}

// ---------------------------------------------------------------------------
// Binary-bundle (no-Docker) launch
//
// For corporate machines without Docker, MockServer can be run from a
// self-contained binary bundle (a bundled Java runtime + jar + launcher). The
// bundle is either supplied locally via `mockserver.binaryPath` or downloaded
// once from the GitHub release matching the extension version and cached under
// the extension's global storage. The launched process is tracked in
// `binaryProcess` for Stop and window-close cleanup. Pure resolution logic lives
// in binaryBundle.ts; this section handles the UI, download, and process I/O.
// ---------------------------------------------------------------------------

async function startMockServerBinary(): Promise<void> {
    const cfg = getConfig();
    outputChannel.show(true);

    if (binaryProcess) {
        vscode.window.showInformationMessage(
            `MockServer (binary) is already running on port ${cfg.port}. Stop it first to restart.`
        );
        return;
    }

    let target: bundle.BundleTarget;
    try {
        target = bundle.resolveTarget(process.platform, process.arch);
    } catch (e) {
        vscode.window.showErrorMessage((e as Error).message);
        return;
    }

    let launch: bundle.BundleJava | undefined;
    try {
        launch = await resolveBinaryLaunch(cfg, target);
    } catch (e) {
        const msg = (e as Error).message;
        outputChannel.appendLine(`Error: ${msg}`);
        vscode.window.showErrorMessage(`MockServer binary launch failed: ${msg}`);
        return;
    }
    if (!launch) {
        return; // user declined the download
    }

    const args = bundle.serverArgs(cfg.port);
    outputChannel.appendLine(
        `Starting MockServer (binary) on port ${cfg.port}: ${launch.javaExecutable} -jar ${launch.jarPath} ${args.join(" ")}`
    );

    const proc = spawnBundleJava(launch, args, target.os);
    binaryProcess = proc;
    proc.stdout?.on("data", (d: Buffer) => outputChannel.append(d.toString()));
    proc.stderr?.on("data", (d: Buffer) => outputChannel.append(d.toString()));
    proc.on("error", (err) => {
        outputChannel.appendLine(`Failed to launch: ${err.message}`);
        vscode.window.showErrorMessage(`Failed to start MockServer binary: ${err.message}`);
        if (binaryProcess === proc) {
            binaryProcess = undefined;
        }
    });
    proc.on("exit", (code, signal) => {
        outputChannel.appendLine(
            `MockServer (binary) exited (code=${code ?? "null"}, signal=${signal ?? "null"}).`
        );
        if (binaryProcess === proc) {
            binaryProcess = undefined;
        }
    });
    vscode.window.showInformationMessage(`MockServer (binary) starting on http://localhost:${cfg.port}`);
}

async function stopMockServerBinary(): Promise<void> {
    outputChannel.show(true);
    if (!binaryProcess) {
        vscode.window.showWarningMessage("No MockServer binary process is running in this window.");
        return;
    }
    outputChannel.appendLine("Stopping MockServer (binary)...");
    killBinaryProcess(binaryProcess);
    binaryProcess = undefined;
    vscode.window.showInformationMessage("MockServer (binary) stopped.");
}

/**
 * Spawn the bundled Java runtime directly, replicating what the bundle launcher
 * script does (`java -jar mockserver.jar …`) WITHOUT going through a shell. On no
 * OS is any path handed to a shell interpreter: `spawn` here runs `java` via the
 * OS exec (no `cmd.exe /c`, no `shell: true`), so a bundle path can never be
 * shell-interpreted — this is what removes the command-injection surface that the
 * former `cmd.exe /c <launcher>` Windows branch created (CodeQL
 * js/shell-command-constructed-from-input).
 *
 * `MOCKSERVER_JAVA_OPTS` is honoured exactly as the launcher did (whitespace
 * word-splitting, no quote handling) since java itself does not read that env var.
 *
 * NOTE: the release launcher also bakes in dashboard-analytics `-D` system
 * properties at build time (see scripts/build-binary-bundle.sh). Those values are
 * not available to the extension, so an editor-launched instance omits them — an
 * acceptable trade-off for a locally-launched dev server. We do set
 * MOCKSERVER_LAUNCHER (which the launcher script also exports) so the CLI
 * usage/help banner still reads "mockserver ..." rather than "java -jar ...".
 */
function spawnBundleJava(launch: bundle.BundleJava, args: string[], os: bundle.BundleOs): ChildProcess {
    const javaArgs = [
        ...bundle.parseJavaOpts(process.env.MOCKSERVER_JAVA_OPTS),
        // After MOCKSERVER_JAVA_OPTS, as the launcher script places it, so MOCKSERVER_JAVA_OPTS cannot
        // undo the CVE-2026-114938 workaround for the bundled Temurin runtime.
        "-XX:-UseBASE64Intrinsics",
        "-jar",
        launch.jarPath,
        ...args,
    ];
    const env = { ...process.env, MOCKSERVER_LAUNCHER: "mockserver" };
    return spawn(launch.javaExecutable, javaArgs, os === "windows" ? { windowsHide: true, env } : { env });
}

/** Terminate the tracked bundle process (java, spawned directly — no shell wrapper). */
function killBinaryProcess(proc: ChildProcess): void {
    if (proc.pid === undefined) {
        return;
    }
    if (process.platform === "win32") {
        // The tracked pid is java.exe itself (spawned directly, no cmd wrapper);
        // taskkill /T /F still cleanly terminates it and any grandchildren.
        try {
            execFileSync("taskkill", ["/PID", String(proc.pid), "/T", "/F"], { stdio: "ignore" });
        } catch {
            // already gone
        }
    } else {
        // The POSIX launcher `exec`s java in place, so the tracked pid IS the JVM and
        // SIGTERM reaches it directly for a clean shutdown.
        try {
            proc.kill("SIGTERM");
        } catch {
            // already gone
        }
    }
}

/**
 * Resolve the bundled Java executable + app jar to run: from an explicit
 * `mockserver.binaryPath`, else the cached download, else (with the user's
 * consent) a fresh download. Returns `undefined` when the user declines the
 * download; throws with a clear message when a configured path or download is
 * broken. The launch spawns `java` directly (no launcher script, no shell).
 */
async function resolveBinaryLaunch(
    cfg: MockServerConfig,
    target: bundle.BundleTarget
): Promise<bundle.BundleJava | undefined> {
    // Reject an unsafe version before it is interpolated into any URL or path.
    if (!bundle.isValidVersion(cfg.binaryVersion)) {
        throw new Error(
            `Invalid mockserver.binaryVersion '${cfg.binaryVersion}'. ` +
                `Allowed characters: letters, digits, '.', '_', '-'.`
        );
    }

    // 1. Explicit local bundle path (launcher file or unpacked bundle directory).
    if (cfg.binaryPath) {
        let isDir = false;
        try {
            isDir = fs.statSync(cfg.binaryPath).isDirectory();
        } catch {
            // path may not exist — fall through to the existence check below
        }
        const root = bundle.resolveConfiguredBundleRoot(cfg.binaryPath, isDir);
        const java = bundle.bundleJavaAndJar(root, target.os);
        if (!fs.existsSync(java.javaExecutable) || !fs.existsSync(java.jarPath)) {
            throw new Error(
                `mockserver.binaryPath is set but no MockServer bundle was found under ${root} ` +
                    `(expected ${java.javaExecutable} and ${java.jarPath}). ` +
                    `Point it at the unpacked bundle directory or its bin/mockserver launcher.`
            );
        }
        return java;
    }

    // 2. Previously-downloaded bundle in the global-storage cache.
    const storageDir = extensionContext.globalStorageUri.fsPath;
    const cachedRoot = bundle.cachedBundleDir(storageDir, cfg.binaryVersion, target);
    const cachedJava = bundle.bundleJavaAndJar(cachedRoot, target.os);
    if (fs.existsSync(cachedJava.javaExecutable) && fs.existsSync(cachedJava.jarPath)) {
        return cachedJava;
    }

    // 3. Offer to download the matching bundle from the GitHub release.
    const archiveName = bundle.archiveFileName(cfg.binaryVersion, target);
    const choice = await vscode.window.showInformationMessage(
        `No MockServer binary bundle found for ${target.os}/${target.arch}. ` +
            `Download ${archiveName} from GitHub (no Docker required)?`,
        { modal: true },
        "Download"
    );
    if (choice !== "Download") {
        return undefined;
    }
    await downloadAndExtractBundle(cfg.binaryVersion, target, storageDir);
    if (!fs.existsSync(cachedJava.javaExecutable) || !fs.existsSync(cachedJava.jarPath)) {
        throw new Error(`Bundle downloaded but the Java runtime was not found under ${cachedRoot}.`);
    }
    return cachedJava;
}

/**
 * Download the bundle archive from the GitHub release, verify its published
 * SHA-256 sidecar, and unpack it into the global-storage cache. Uses VS Code's
 * global `fetch`, which honours the editor's `http.proxy` / system-proxy settings,
 * so it works behind a corporate proxy without extra configuration.
 *
 * Checksum verification is FAIL-CLOSED: a digest mismatch always aborts, and a
 * sidecar that cannot be fetched aborts too UNLESS the user explicitly confirms
 * installing without verification (the second-consent prompt). This matters behind
 * a TLS-inspection proxy, where the small `.sha256` fetch can fail while the large
 * archive succeeds — silently skipping the check there would defeat the point.
 */
async function downloadAndExtractBundle(
    version: string,
    target: bundle.BundleTarget,
    storageDir: string
): Promise<void> {
    const bundlesDir = path.join(storageDir, "bundles");
    fs.mkdirSync(bundlesDir, { recursive: true });
    const archiveName = bundle.archiveFileName(version, target);
    const archivePath = path.join(bundlesDir, archiveName);
    const url = bundle.downloadUrl(version, target);
    const sumUrl = bundle.checksumUrl(version, target);

    await vscode.window.withProgress(
        {
            location: vscode.ProgressLocation.Notification,
            title: `MockServer: ${archiveName}`,
            cancellable: false,
        },
        async (progress) => {
            progress.report({ message: "downloading..." });
            outputChannel.appendLine(`Downloading ${url}`);
            const res = await fetch(url);
            if (!res.ok) {
                throw new Error(`Download failed: HTTP ${res.status} for ${url}`);
            }
            fs.writeFileSync(archivePath, Buffer.from(await res.arrayBuffer()));

            // Fetch the published checksum sidecar.
            progress.report({ message: "verifying checksum..." });
            let expected: string | undefined;
            try {
                const sumRes = await fetch(sumUrl);
                if (sumRes.ok) {
                    expected = bundle.parseSha256(await sumRes.text());
                }
            } catch {
                // treated below as "sidecar unavailable" → fail-closed unless consented
            }
            const actual = expected !== undefined ? await bundle.sha256File(archivePath) : undefined;

            // When the sidecar could not be fetched, require a second, explicit consent
            // before installing unverified — otherwise abort.
            let consentWithoutChecksum = false;
            if (expected === undefined) {
                const answer = await vscode.window.showWarningMessage(
                    `Could not verify the checksum of ${archiveName}: the .sha256 sidecar could not be ` +
                        `fetched (common behind a TLS-inspection proxy). Install without checksum verification?`,
                    { modal: true },
                    "Install anyway"
                );
                consentWithoutChecksum = answer === "Install anyway";
            }
            const abortReason = bundle.checksumAbortReason(expected, actual, consentWithoutChecksum);
            if (abortReason) {
                fs.rmSync(archivePath, { force: true });
                throw new Error(`${archiveName}: ${abortReason}`);
            }
            outputChannel.appendLine(
                expected !== undefined ? "Checksum verified." : "Installing without checksum verification (user-confirmed)."
            );

            progress.report({ message: "extracting..." });
            const destBundleDir = bundle.cachedBundleDir(storageDir, version, target);
            fs.rmSync(destBundleDir, { recursive: true, force: true }); // clear any stale partial
            await extractArchive(archivePath, bundlesDir, target.os);
            fs.rmSync(archivePath, { force: true });
            if (target.os !== "windows") {
                // We launch the bundled `java` directly, so re-assert ITS exec bit
                // defensively (tar preserves it, but be safe).
                try {
                    fs.chmodSync(bundle.bundleJavaAndJar(destBundleDir, target.os).javaExecutable, 0o755);
                } catch {
                    // ignore
                }
            }
            outputChannel.appendLine("Bundle ready.");
        }
    );
}


/**
 * Unpack the downloaded archive into `destDir`. The download OS always matches
 * the current machine, so `.tar.gz` uses `tar` (present on macOS/Linux) and the
 * Windows `.zip` uses PowerShell's Expand-Archive.
 */
function extractArchive(archivePath: string, destDir: string, os: bundle.BundleOs): Promise<void> {
    return new Promise((resolve, reject) => {
        let cmd: string;
        let args: string[];
        let env = process.env;
        if (os === "windows") {
            // Pass the paths through the environment and read them back as `$env:...`
            // inside the script, so no path is ever concatenated into the command
            // string — this removes the shell-injection surface entirely (a path with
            // a quote or `;` cannot break out) rather than relying on quoting.
            cmd = "powershell.exe";
            args = [
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "Expand-Archive -LiteralPath $env:MOCKSERVER_ARCHIVE_PATH " +
                    "-DestinationPath $env:MOCKSERVER_DEST_DIR -Force",
            ];
            env = { ...process.env, MOCKSERVER_ARCHIVE_PATH: archivePath, MOCKSERVER_DEST_DIR: destDir };
        } else {
            // Guard against tar-slip before extracting: list the entries and reject any
            // absolute or `..`-escaping path. (The archive is our own checksum-verified
            // GitHub release, so this is defence in depth.)
            try {
                const listing = execFileSync("tar", ["-tzf", archivePath], { encoding: "utf-8" });
                const unsafe = bundle.firstUnsafeArchiveEntry(listing.split("\n"));
                if (unsafe) {
                    throw new Error(`Refusing to extract archive with unsafe path entry: ${unsafe}`);
                }
            } catch (e) {
                reject(e instanceof Error ? e : new Error(String(e)));
                return;
            }
            cmd = "tar";
            args = ["-xzf", archivePath, "-C", destDir];
        }
        execFile(cmd, args, { env }, (error, _stdout, stderr) => {
            if (error) {
                reject(new Error(`Failed to extract bundle: ${stderr || error.message}`));
                return;
            }
            resolve();
        });
    });
}

async function openDashboard(): Promise<void> {
    const { port } = getConfig();
    const url = `http://localhost:${port}/mockserver/dashboard`;
    const opened = await vscode.env.openExternal(vscode.Uri.parse(url));
    if (!opened) {
        vscode.window.showErrorMessage(`Failed to open dashboard at ${url}`);
    }
}

// Reveal the docked dashboard: focus the `mockserver.dashboard` WebviewView in
// the bottom Panel (NOT an editor tab). VS Code auto-generates a `<viewId>.focus`
// command for every contributed view; executing it shows and focuses the panel
// view, resolving its WebviewViewProvider on first reveal. Keeps the
// external-browser command (`mockserver.openDashboard`) available separately.
async function openDashboardInEditor(): Promise<void> {
    await vscode.commands.executeCommand("mockserver.dashboard.focus");
}

// Resolve the expectation file a CodeLens/command should act on: the explicit
// URI passed by the CodeLens, else the active editor's document.
function resolveTargetUri(uri?: vscode.Uri): vscode.Uri | undefined {
    return uri ?? vscode.window.activeTextEditor?.document.uri;
}

async function loadExpectations(uri?: vscode.Uri): Promise<void> {
    const target = resolveTargetUri(uri);
    if (!target) {
        vscode.window.showErrorMessage("No expectation file is open.");
        return;
    }
    const { port } = getConfig();
    try {
        const doc = await vscode.workspace.openTextDocument(target);
        const text = doc.getText();
        if (client.looksLikeOpenApiSpec(text)) {
            vscode.window.showWarningMessage(
                "MockServer: this looks like an OpenAPI/Swagger spec, not an expectation. " +
                "Use \"Generate Expectations From OpenAPI Spec\" instead."
            );
            return;
        }
        const count = await client.loadExpectations(client.buildBaseUrl(port), text, httpFetch);
        vscode.window.showInformationMessage(
            `Loaded ${count} expectation(s) into MockServer on port ${port}.`
        );
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to load expectations — ${(e as Error).message}`);
    }
}

async function diffAgainstLive(uri?: vscode.Uri): Promise<void> {
    const target = resolveTargetUri(uri);
    if (!target) {
        vscode.window.showErrorMessage("No expectation file is open.");
        return;
    }
    const { port } = getConfig();
    try {
        const live = await client.retrieveActiveExpectations(client.buildBaseUrl(port), httpFetch);
        const liveUri = vscode.Uri.parse(`${LIVE_SCHEME}:/active-expectations.json`);
        liveContent.set(liveUri.toString(), live);
        liveContentChanged.fire(liveUri);
        await vscode.commands.executeCommand(
            "vscode.diff",
            liveUri,
            target,
            `MockServer live ↔ ${path.basename(target.fsPath)}`
        );
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to diff against live — ${(e as Error).message}`);
    }
}

// Verify that every request declared in the expectation file was received by the
// running server (each at least once), via PUT /mockserver/verify. Reports the
// first unmet verification's reason, or success when all are satisfied. Useful
// after a test run to confirm the mocked endpoints were actually exercised.
async function verifyExpectations(uri?: vscode.Uri): Promise<void> {
    const target = resolveTargetUri(uri);
    if (!target) {
        vscode.window.showErrorMessage("No expectation file is open.");
        return;
    }
    const { port } = getConfig();
    try {
        const doc = await vscode.workspace.openTextDocument(target);
        const definitions = client.extractRequestDefinitions(doc.getText());
        if (definitions.length === 0) {
            vscode.window.showInformationMessage(
                "No request definitions found in this file to verify."
            );
            return;
        }
        const baseUrl = client.buildBaseUrl(port);
        for (let i = 0; i < definitions.length; i++) {
            const { verified, reason } = await client.verifyRequestReceived(
                baseUrl,
                definitions[i],
                httpFetch
            );
            if (!verified) {
                vscode.window.showWarningMessage(
                    `MockServer: declared request ${i + 1} of ${definitions.length} was not received — ` +
                        `${reason.split("\n")[0]}`
                );
                return;
            }
        }
        vscode.window.showInformationMessage(
            `Verified: all ${definitions.length} declared request(s) were received.`
        );
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to verify — ${(e as Error).message}`);
    }
}

// Remove from the running server the expectations declared in this file (matched by
// each declared request), via PUT /mockserver/clear?type=expectations. Confirms
// first so a stray CodeLens click cannot silently delete live state.
async function deleteExpectations(uri?: vscode.Uri): Promise<void> {
    const target = resolveTargetUri(uri);
    if (!target) {
        vscode.window.showErrorMessage("No expectation file is open.");
        return;
    }
    const choice = await vscode.window.showWarningMessage(
        "Delete these expectations from the running MockServer?",
        { modal: true },
        "Delete"
    );
    if (choice !== "Delete") {
        return; // user cancelled
    }
    const { port } = getConfig();
    try {
        const doc = await vscode.workspace.openTextDocument(target);
        const count = await client.clearExpectations(client.buildBaseUrl(port), doc.getText(), httpFetch);
        if (count === 0) {
            vscode.window.showInformationMessage(
                "No request definitions found in this file to delete."
            );
            return;
        }
        vscode.window.showInformationMessage(
            `Cleared ${count} expectation matcher(s) from MockServer on port ${port}.`
        );
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to delete expectations — ${(e as Error).message}`);
    }
}

// Offer to write generated/recorded content to a new workspace file (so the user
// can keep the artifact in the repo), defaulting the name and extension to the
// suggested values. Returns true when a file was written. Falls back to nothing
// (the caller already opened an editor tab) when there is no workspace folder or
// the user cancels.
async function offerToSaveToWorkspace(
    suggestedName: string,
    content: string
): Promise<boolean> {
    const folders = vscode.workspace.workspaceFolders;
    const defaultUri =
        folders && folders.length > 0
            ? vscode.Uri.joinPath(folders[0].uri, suggestedName)
            : undefined;
    const dest = await vscode.window.showSaveDialog({
        defaultUri,
        saveLabel: "Save to Workspace",
    });
    if (!dest) {
        return false; // user cancelled — the editor tab is still open
    }
    await vscode.workspace.fs.writeFile(dest, Buffer.from(content, "utf8"));
    const saved = await vscode.workspace.openTextDocument(dest);
    await vscode.window.showTextDocument(saved);
    return true;
}

async function saveRecordedExpectations(): Promise<void> {
    const pick = await vscode.window.showQuickPick(
        [
            { label: "JSON", description: "Expectation JSON (loadable as *.mockserver.json)", format: "json" as const },
            { label: "Java", description: "MockServerClient Java DSL", format: "java" as const },
        ],
        { placeHolder: "Format for recorded expectations" }
    );
    if (!pick) {
        return; // user cancelled
    }
    const { port } = getConfig();
    try {
        const recorded = await client.retrieveRecordedExpectations(
            client.buildBaseUrl(port),
            pick.format,
            httpFetch
        );
        if (recorded.empty) {
            vscode.window.showInformationMessage(
                "MockServer has not recorded any expectations yet. Recorded expectations are generated " +
                "from traffic the server proxies or forwards to a real upstream."
            );
            return;
        }
        const doc = await vscode.workspace.openTextDocument({
            content: recorded.content,
            language: pick.format === "java" ? "java" : "json",
        });
        await vscode.window.showTextDocument(doc);
        // Record-to-code: offer to drop the artifact straight into the repo as a
        // *.mockserver.json (JSON) or *.java (Java DSL) workspace file.
        const suggestedName =
            pick.format === "java" ? "RecordedExpectations.java" : "recorded.mockserver.json";
        await offerToSaveToWorkspace(suggestedName, recorded.content);
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to retrieve recorded expectations — ${(e as Error).message}`);
    }
}

async function generateFromOpenApi(uri?: vscode.Uri): Promise<void> {
    const target = resolveTargetUri(uri);
    if (!target) {
        vscode.window.showErrorMessage("Open an OpenAPI/Swagger spec file first.");
        return;
    }
    const { port } = getConfig();
    try {
        const doc = await vscode.workspace.openTextDocument(target);
        const text = doc.getText();
        if (!client.looksLikeOpenApiSpec(text)) {
            vscode.window.showWarningMessage(
                "MockServer: the active editor doesn't look like an OpenAPI/Swagger spec (no top-level " +
                "\"openapi\" or \"swagger\" field). Open your spec file and run this again."
            );
            return;
        }
        const generated = await client.generateExpectationsFromOpenApi(
            client.buildBaseUrl(port),
            text,
            httpFetch
        );
        if (generated.trim() === "[]") {
            vscode.window.showInformationMessage(
                "The OpenAPI spec produced no expectations (no operations matched)."
            );
            return;
        }
        const out = await vscode.workspace.openTextDocument({ content: generated, language: "json" });
        await vscode.window.showTextDocument(out);
        // Contract→stub: offer to write the generated expectations into a new
        // *.mockserver.json workspace file (schema-validated, loadable, in-repo).
        const baseName = path.basename(target.fsPath).replace(/\.(json|ya?ml)$/i, "");
        const saved = await offerToSaveToWorkspace(`${baseName || "openapi"}.mockserver.json`, generated);
        if (!saved) {
            vscode.window.showInformationMessage(
                "Generated MockServer expectations from the OpenAPI spec. Save as a *.mockserver.json file to keep them."
            );
        }
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to generate from OpenAPI — ${(e as Error).message}`);
    }
}

async function sendRequest(uri?: vscode.Uri): Promise<void> {
    const target = resolveTargetUri(uri);
    if (!target) {
        vscode.window.showErrorMessage("Open a *.mockserver-request.json file first.");
        return;
    }
    const { port } = getConfig();
    try {
        const doc = await vscode.workspace.openTextDocument(target);
        const spec = client.parseRequestSpec(doc.getText());
        const baseUrl = client.buildBaseUrl(port);
        const response = await client.sendScratchRequest(baseUrl, spec, httpFetch);
        // Also ask the server whether the request matched a registered expectation,
        // and — on a miss — the nearest-miss reason. Best-effort: a server without the
        // debugMismatch endpoint must not break the (already-useful) response view.
        let analysis: client.MatchAnalysis | undefined;
        try {
            analysis = await client.analyseMatch(baseUrl, spec, httpFetch);
        } catch {
            analysis = undefined;
        }
        const out = await vscode.workspace.openTextDocument({
            content: formatScratchResponse(response, analysis),
            language: "text",
        });
        await vscode.window.showTextDocument(out);
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to send request — ${(e as Error).message}`);
    }
}

async function showDrift(): Promise<void> {
    const { port } = getConfig();
    try {
        const drift = await client.retrieveDrift(client.buildBaseUrl(port), httpFetch);
        if (drift.empty) {
            vscode.window.showInformationMessage(
                "No drift detected. Drift is recorded when MockServer proxies traffic to a real " +
                "upstream and a matching stub expectation differs from the real response."
            );
            return;
        }
        const out = await vscode.workspace.openTextDocument({
            content: drift.report,
            language: "text",
        });
        await vscode.window.showTextDocument(out);
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to retrieve drift report — ${(e as Error).message}`);
    }
}

// Map the pure DriftDiagnostic severity strings to the vscode enum.
function toDiagnosticSeverity(
    severity: "error" | "warning" | "info"
): vscode.DiagnosticSeverity {
    switch (severity) {
        case "error":
            return vscode.DiagnosticSeverity.Error;
        case "warning":
            return vscode.DiagnosticSeverity.Warning;
        default:
            return vscode.DiagnosticSeverity.Information;
    }
}

// Surface the server's drift records as inline diagnostics on the open
// expectation file: each drift attaches to the line of its matching expectation
// (by id), so a developer sees "the real upstream differs from this stub" right
// in the *.mockserver.json file. Re-running refreshes; no drift clears them.
async function showDriftDiagnostics(uri?: vscode.Uri): Promise<void> {
    const target = resolveTargetUri(uri);
    if (!target) {
        vscode.window.showErrorMessage("No expectation file is open.");
        return;
    }
    const { port } = getConfig();
    try {
        const doc = await vscode.workspace.openTextDocument(target);
        const records = await client.retrieveDriftRecords(client.buildBaseUrl(port), httpFetch);
        if (records.length === 0) {
            driftDiagnostics.delete(target);
            driftQuickFixProvider.clearRecords(target);
            vscode.window.showInformationMessage(
                "No drift detected. Drift is recorded when MockServer proxies traffic to a real " +
                "upstream and a matching stub expectation differs from the real response."
            );
            return;
        }
        const mapped = client.mapDriftToDiagnostics(records, doc.getText());
        // Tag each diagnostic with the drift code so the quick-fix provider finds
        // it, and remember the record per line so the lightbulb can map it back to
        // the JSON node (Phase 4 "update stub to match upstream").
        const lineToRecord = new Map<number, client.DriftRecord>();
        const diagnostics = mapped.map((d) => {
            const lineLength = doc.lineAt(d.line).text.length;
            const range = new vscode.Range(d.line, 0, d.line, lineLength);
            const diagnostic = new vscode.Diagnostic(range, d.message, toDiagnosticSeverity(d.severity));
            diagnostic.code = DRIFT_DIAGNOSTIC_CODE;
            diagnostic.source = "MockServer";
            lineToRecord.set(d.line, d.record);
            return diagnostic;
        });
        driftDiagnostics.set(target, diagnostics);
        driftQuickFixProvider.setRecords(target, lineToRecord);
        vscode.window.showInformationMessage(
            `${diagnostics.length} drift record(s) shown as diagnostics. Use the lightbulb to update a stub to match upstream.`
        );
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to retrieve drift — ${(e as Error).message}`);
    }
}

// Open the server's received-request log in a new JSON editor tab. When the
// server has not recorded any requests yet, say so rather than opening an empty
// tab.
async function viewRequestLog(): Promise<void> {
    const { port } = getConfig();
    try {
        const log = await client.retrieveRequestLog(client.buildBaseUrl(port), httpFetch);
        if (log.empty) {
            vscode.window.showInformationMessage("No requests recorded yet.");
            return;
        }
        const doc = await vscode.workspace.openTextDocument({
            content: log.content,
            language: "json",
        });
        await vscode.window.showTextDocument(doc);
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to retrieve request log — ${(e as Error).message}`);
    }
}

// Distributed-trace correlation: prompt for a W3C trace id (or a full traceparent),
// fetch the server's received-request log, and open the subset of requests that
// belong to that trace — every hop one trace produced — in a new JSON editor tab.
async function findByTrace(): Promise<void> {
    const input = await vscode.window.showInputBox({
        prompt: "Trace id (32 hex) or full traceparent",
        placeHolder: "4bf92f3577b34da6a3ce929d0e0e4736",
        validateInput: (value) => {
            if (value.trim().length === 0) {
                return "Enter a trace id or traceparent.";
            }
            return client.extractTraceId(value) === null
                ? "Enter a 32-hex trace id or a full W3C traceparent."
                : undefined;
        },
    });
    if (input === undefined) {
        return; // user cancelled
    }
    const { port } = getConfig();
    try {
        const log = await client.retrieveRequestLog(client.buildBaseUrl(port), httpFetch);
        const { traceId, matches } = client.filterRequestsByTrace(log.content, input);
        if (traceId === null) {
            vscode.window.showWarningMessage(
                "Enter a 32-hex trace id or a full W3C traceparent."
            );
            return;
        }
        if (matches.length === 0) {
            vscode.window.showInformationMessage(`No requests found for trace ${traceId}.`);
            return;
        }
        const doc = await vscode.workspace.openTextDocument({
            content: JSON.stringify(matches, null, 2) + "\n",
            language: "json",
        });
        await vscode.window.showTextDocument(doc);
        vscode.window.showInformationMessage(
            `${matches.length} request(s) found for trace ${traceId}.`
        );
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to find requests by trace — ${(e as Error).message}`);
    }
}

// Trace-correlation: prompt for a W3C trace id (or full traceparent), resolve the
// 32-hex trace id, and open the correlated trace in the configured trace-backend
// (Jaeger/Tempo/Grafana) by substituting it into `mockserver.traceUrlTemplate`. When
// no template is configured, degrades gracefully to surfacing the bare trace id with
// a copy affordance and a shortcut to configure the template.
async function viewTrace(): Promise<void> {
    const input = await vscode.window.showInputBox({
        prompt: "Trace id (32 hex) or full traceparent to open in your trace backend",
        placeHolder: "4bf92f3577b34da6a3ce929d0e0e4736",
        validateInput: (value) => {
            if (value.trim().length === 0) {
                return "Enter a trace id or traceparent.";
            }
            return client.extractTraceId(value) === null
                ? "Enter a 32-hex trace id or a full W3C traceparent."
                : undefined;
        },
    });
    if (input === undefined) {
        return; // user cancelled
    }
    const traceId = client.extractTraceId(input);
    if (traceId === null) {
        vscode.window.showWarningMessage("Enter a 32-hex trace id or a full W3C traceparent.");
        return;
    }
    const { traceUrlTemplate } = getConfig();
    const url = client.buildTraceUrl(traceUrlTemplate, traceId);
    if (url === null) {
        // No backend configured: surface the trace id and offer to copy it or set a template.
        const choice = await vscode.window.showInformationMessage(
            `Trace ${traceId}. Set "mockserver.traceUrlTemplate" (e.g. http://localhost:16686/trace/{traceId}) to open traces in your backend.`,
            "Copy Trace ID",
            "Configure Template"
        );
        if (choice === "Copy Trace ID") {
            await vscode.env.clipboard.writeText(traceId);
        } else if (choice === "Configure Template") {
            await vscode.commands.executeCommand(
                "workbench.action.openSettings",
                "mockserver.traceUrlTemplate"
            );
        }
        return;
    }
    try {
        const opened = await vscode.env.openExternal(vscode.Uri.parse(url));
        if (!opened) {
            vscode.window.showWarningMessage(`MockServer: could not open the trace URL — ${url}`);
        }
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to open the trace URL — ${(e as Error).message}`);
    }
}

// Reset the running server (clear all expectations and the request log) after a
// modal confirmation, so a stray Command Palette pick can't wipe state silently.
async function resetServer(): Promise<void> {
    const choice = await vscode.window.showWarningMessage(
        "Reset MockServer? This clears all expectations and logs.",
        { modal: true },
        "Reset"
    );
    if (choice !== "Reset") {
        return; // user cancelled
    }
    const { port } = getConfig();
    try {
        await client.resetServer(client.buildBaseUrl(port), httpFetch);
        vscode.window.showInformationMessage("MockServer reset.");
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to reset — ${(e as Error).message}`);
    }
}

// Upload a compiled .wasm custom-rule module to the running server so it can be
// referenced by name in an expectation body matcher. Prompts for the file and a
// module name (defaulting to the file's basename), then PUTs the raw bytes. The
// server's "WASM support is disabled" 403 message surfaces verbatim on error.
async function uploadWasm(): Promise<void> {
    const picked = await vscode.window.showOpenDialog({
        canSelectMany: false,
        filters: { WebAssembly: ["wasm"] },
        openLabel: "Upload WASM Module",
    });
    if (!picked || picked.length === 0) {
        return; // user cancelled
    }
    const fileUri = picked[0];
    const defaultName = path.basename(fileUri.fsPath, ".wasm");
    const name = await vscode.window.showInputBox({
        prompt: "Name to register the WASM module under",
        value: defaultName,
        validateInput: (value) =>
            value.trim().length === 0 ? "Enter a non-empty module name." : undefined,
    });
    if (name === undefined || name.trim().length === 0) {
        return; // user cancelled or gave an empty name
    }
    const moduleName = name.trim();
    const { port } = getConfig();
    try {
        const bytes = await vscode.workspace.fs.readFile(fileUri);
        await client.uploadWasmModule(client.buildBaseUrl(port), moduleName, bytes, httpFetch);
        vscode.window.showInformationMessage(
            `Uploaded WASM module "${moduleName}". Reference it in an expectation body matcher as ` +
            `{ "type": "WASM", "moduleName": "${moduleName}" }.`
        );
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to upload WASM module — ${(e as Error).message}`);
    }
}

// Open the names of the WASM custom-rule modules registered on the server in a new
// JSON editor tab (or say so when none are registered).
async function listWasm(): Promise<void> {
    const { port } = getConfig();
    try {
        const modules = await client.retrieveWasmModules(client.buildBaseUrl(port), httpFetch);
        if (modules.trim() === "" || modules.trim() === "[]") {
            vscode.window.showInformationMessage("No WASM modules are registered on the server.");
            return;
        }
        const doc = await vscode.workspace.openTextDocument({
            content: modules,
            language: "json",
        });
        await vscode.window.showTextDocument(doc);
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to list WASM modules — ${(e as Error).message}`);
    }
}

// Test a compiled .wasm custom-rule module against a sample request without uploading it
// or creating an expectation. Prompts for the .wasm file and a sample method + path, then
// POSTs to /mockserver/wasm/test and reports whether the module matched. Handy for quick
// local iteration on a rule. The server's "WASM support is disabled" 403 surfaces verbatim.
async function testWasm(): Promise<void> {
    const picked = await vscode.window.showOpenDialog({
        canSelectMany: false,
        filters: { WebAssembly: ["wasm"] },
        openLabel: "Test WASM Rule",
    });
    if (!picked || picked.length === 0) {
        return; // user cancelled
    }
    const fileUri = picked[0];
    const method = await vscode.window.showInputBox({
        prompt: "Sample request method to test the WASM rule against",
        value: "POST",
    });
    if (method === undefined) {
        return; // user cancelled
    }
    const requestPath = await vscode.window.showInputBox({
        prompt: "Sample request path to test the WASM rule against",
        value: "/",
    });
    if (requestPath === undefined) {
        return; // user cancelled
    }
    const sampleBody = await vscode.window.showInputBox({
        prompt: "Sample request body (optional)",
        value: "",
    });
    if (sampleBody === undefined) {
        return; // user cancelled
    }
    const { port } = getConfig();
    try {
        const bytes = await vscode.workspace.fs.readFile(fileUri);
        const request: client.WasmTestRequest = {
            method: method.trim(),
            path: requestPath.trim(),
        };
        if (sampleBody.length > 0) {
            request.body = sampleBody;
        }
        const matched = await client.testWasmModule(client.buildBaseUrl(port), bytes, request, httpFetch);
        if (matched) {
            vscode.window.showInformationMessage(
                `WASM rule matched ${request.method} ${request.path}.`
            );
        } else {
            vscode.window.showWarningMessage(
                `WASM rule did not match ${request.method} ${request.path}.`
            );
        }
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to test WASM module — ${(e as Error).message}`);
    }
}

// Render a scratch response as a small text summary: the match analysis (whether
// the request matched a registered expectation, and if not the nearest miss) when
// available, then `HTTP <status>` and the body, pretty-printed when it is JSON.
function formatScratchResponse(
    response: client.ScratchResponse,
    analysis?: client.MatchAnalysis
): string {
    let body = response.body;
    try {
        body = JSON.stringify(JSON.parse(body), null, 2);
    } catch {
        // not JSON — show the body verbatim
    }
    const header = analysis ? client.formatMatchAnalysis(analysis) + "\n\n" : "";
    return `${header}HTTP ${response.status}\n\n${body}`;
}

// ---------------------------------------------------------------------------
// Phase 5 — the in-IDE HTTP debugger over the breakpoint callback WebSocket.
// ---------------------------------------------------------------------------

// The runtime socket factory wraps a `ws` WebSocket in the CallbackSocket shape
// the BreakpointClient needs. `ws` is required lazily so the extension module
// loads even in environments (e.g. the unit-test harness) that never connect.
const defaultSocketFactory = (url: string): CallbackSocket => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const WebSocket = require("ws") as new (address: string) => {
        send(data: string): void;
        close(): void;
        on(event: string, listener: (arg?: unknown) => void): void;
    };
    const ws = new WebSocket(url);
    return {
        send: (data) => ws.send(data),
        close: () => ws.close(),
        on: (event, listener) => ws.on(event, (arg?: unknown) => listener(arg)),
    };
};

// Reveal the debugger panel and open the callback WebSocket. Surfaces the
// prerequisite (breakpoints fire only on traffic THROUGH MockServer) in the panel.
async function openDebugger(): Promise<void> {
    debuggerPanel?.reveal();
}

// Prompt for a request matcher (method + path regex) and the phases to pause,
// then register a breakpoint matcher tied to the debugger's callback-WS clientId.
async function addBreakpoint(): Promise<void> {
    if (!debuggerPanel) {
        return;
    }
    // The debugger panel must be connected first so it has a clientId to own the matcher.
    debuggerPanel.reveal();
    if (!debuggerPanel.isConnected() || !debuggerPanel.getClientId()) {
        vscode.window.showWarningMessage(
            "MockServer debugger is connecting. Try adding the breakpoint again in a moment — " +
            "the debugger needs an active callback WebSocket to own the breakpoint."
        );
        return;
    }
    const pathRegex = await vscode.window.showInputBox({
        prompt: "Breakpoint path matcher (regex)",
        placeHolder: "/api/.*",
        value: "/.*",
        validateInput: (v) => (v.trim().length === 0 ? "Enter a path matcher (e.g. /api/.*)" : undefined),
    });
    if (pathRegex === undefined) {
        return;
    }
    const method = await vscode.window.showInputBox({
        prompt: "Method matcher (optional, e.g. GET — leave blank for any)",
        placeHolder: "GET",
    });
    if (method === undefined) {
        return;
    }
    const phasePick = await vscode.window.showQuickPick(
        [
            { label: "Request only", phases: ["REQUEST"] },
            { label: "Request + Response", phases: ["REQUEST", "RESPONSE"] },
            { label: "Response only", phases: ["RESPONSE"] },
            { label: "Request + Response + Stream frames", phases: ["REQUEST", "RESPONSE", "RESPONSE_STREAM"] },
        ],
        { placeHolder: "Phases to pause at" }
    );
    if (!phasePick) {
        return;
    }
    const httpRequest: Record<string, unknown> = { path: pathRegex };
    if (method.trim().length > 0) {
        httpRequest.method = method.trim();
    }
    const body = buildMatcherRegistrationBody(httpRequest, phasePick.phases, debuggerPanel.getClientId()!);
    const { port } = getConfig();
    try {
        const result = await client.registerBreakpointMatcher(client.buildBaseUrl(port), body, httpFetch);
        vscode.window.showInformationMessage(
            `Breakpoint registered (${result.phases.join(", ")}). Drive traffic THROUGH MockServer to hit it.`
        );
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to register breakpoint — ${(e as Error).message}`);
    }
}

// Clear all registered breakpoint matchers from the server.
async function clearBreakpoints(): Promise<void> {
    const { port } = getConfig();
    try {
        await client.clearBreakpointMatchers(client.buildBaseUrl(port), httpFetch);
        vscode.window.showInformationMessage("Cleared all MockServer breakpoint matchers.");
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to clear breakpoints — ${(e as Error).message}`);
    }
}

// ---------------------------------------------------------------------------
// Phase 6 — chaos panel, contract-test runner, agent-run call graph.
// ---------------------------------------------------------------------------

// Show the current chaos-experiment status in an editor tab.
async function showChaosStatus(): Promise<void> {
    const { port } = getConfig();
    try {
        const status = await client.getChaosExperimentStatus(client.buildBaseUrl(port), httpFetch);
        const doc = await vscode.workspace.openTextDocument({
            content: client.formatChaosStatus(status),
            language: "text",
        });
        await vscode.window.showTextDocument(doc);
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to get chaos status — ${(e as Error).message}`);
    }
}

// Stop and clear the current chaos experiment (after a modal confirmation).
async function stopChaos(): Promise<void> {
    const choice = await vscode.window.showWarningMessage(
        "Stop and clear the current MockServer chaos experiment?",
        { modal: true },
        "Stop"
    );
    if (choice !== "Stop") {
        return;
    }
    const { port } = getConfig();
    try {
        await client.stopChaosExperiment(client.buildBaseUrl(port), httpFetch);
        vscode.window.showInformationMessage("Chaos experiment stopped.");
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to stop chaos — ${(e as Error).message}`);
    }
}

// Run an OpenAPI contract test: the active OpenAPI spec file against a service URL.
async function runContractTest(uri?: vscode.Uri): Promise<void> {
    const target = resolveTargetUri(uri);
    if (!target) {
        vscode.window.showErrorMessage("Open an OpenAPI/Swagger spec file first.");
        return;
    }
    const doc = await vscode.workspace.openTextDocument(target);
    const spec = doc.getText();
    if (!client.looksLikeOpenApiSpec(spec)) {
        vscode.window.showWarningMessage(
            "The active editor doesn't look like an OpenAPI/Swagger spec. Open your spec and try again."
        );
        return;
    }
    const serviceUrl = await vscode.window.showInputBox({
        prompt: "Base URL of the service under test",
        placeHolder: "http://localhost:8080",
        validateInput: (v) =>
            /^https?:\/\//.test(v.trim()) ? undefined : "Enter an http(s):// base URL.",
    });
    if (serviceUrl === undefined) {
        return;
    }
    const { port } = getConfig();
    try {
        const report = await client.runContractTest(
            client.buildBaseUrl(port),
            { spec, baseUrl: serviceUrl.trim() },
            httpFetch
        );
        const out = await vscode.workspace.openTextDocument({
            content: client.formatContractTestReport(report),
            language: "text",
        });
        await vscode.window.showTextDocument(out);
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: contract test failed — ${(e as Error).message}`);
    }
}

// Fetch the agent-run call graph (via the explain_agent_run MCP tool) and render
// it as a Mermaid flowchart in a new Markdown editor tab (VS Code's built-in
// Markdown preview renders Mermaid where the Markdown Preview Mermaid extension is
// present; the fenced source is always readable).
async function showCallGraph(): Promise<void> {
    const sessionId = await vscode.window.showInputBox({
        prompt: "Agent run / session id to explain (leave blank for the latest run)",
        placeHolder: "session-123",
    });
    if (sessionId === undefined) {
        return;
    }
    const args: Record<string, unknown> = {};
    if (sessionId.trim().length > 0) {
        args.sessionId = sessionId.trim();
    }
    const { port } = getConfig();
    try {
        const graph = await fetchAgentCallGraph(
            client.buildBaseUrl(port),
            args,
            httpFetchWithHeaders
        );
        if (!graph || graph.nodes.length === 0) {
            vscode.window.showInformationMessage(
                "No agent-run call graph available. Proxy or mock an LLM agent run first."
            );
            return;
        }
        const markdown = `# MockServer agent-run call graph\n\n\`\`\`mermaid\n${toMermaid(graph)}\n\`\`\`\n`;
        const doc = await vscode.workspace.openTextDocument({ content: markdown, language: "markdown" });
        await vscode.window.showTextDocument(doc);
        await vscode.commands.executeCommand("markdown.showPreview", doc.uri);
    } catch (e) {
        vscode.window.showErrorMessage(`MockServer: failed to fetch call graph — ${(e as Error).message}`);
    }
}

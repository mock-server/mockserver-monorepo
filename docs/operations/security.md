# Security

MockServer's security scanning, vulnerability management, and the security posture of released and pre-release artifacts.

## Overview

MockServer is a **development and testing tool** -- it is not designed for production deployment. Its security posture reflects this: the project invests heavily in automated scanning and dependency management to keep the supply chain clean, while deliberately retaining certain capabilities (like SSRF forwarding and trust-all TLS) that are essential for testing but would be vulnerabilities in a production service.

Users who need to lock down a MockServer deployment can harden these capabilities through configuration. The consumer-facing [**API Security: Configuration Hardening**](https://www.mock-server.com/mock_server/control_plane_authorisation.html#configuration_hardening) guide ([source](../../jekyll-www.mock-server.com/mock_server/control_plane_authorisation.html)) documents the recommended property values -- loopback binding, SSRF blocking, upstream TLS validation, the TLS protocol floor, response-template class restrictions, request-parsing limits, and control-plane authentication (mTLS/JWT).

See [SECURITY.md](../../SECURITY.md) for the full security policy, including intentional security behaviours and vulnerability reporting.

**Control-plane mTLS trusts only the certificate the client proved.** `MTLSAuthenticationHandler` authenticates the end-entity client certificate alone, builds its PKIX path to `controlPlaneTLSMutualAuthenticationCAChain` with the other presented certificates as untrusted intermediates, and rejects a CA, self-signed or trust-anchor certificate. Releases 5.13.0 to 9.1.0 accepted any presented certificate that validated, so a client could authenticate by appending the public CA certificate to its own. When the data plane does not require mTLS the TLS layer accepts any client certificate, so this handler is the real check, and it never trusts the bundled CA: a `controlPlaneTLSMutualAuthenticationCAChain` containing it is refused. See [tls-and-security.md](../code/tls-and-security.md#control-plane-mtls).

**Data-plane mTLS never trusts the bundled CA.** With `tlsMutualAuthenticationRequired` or a `tlsMutualAuthenticationCertificateChain`, releases up to 9.0.0 always added MockServer's own CA to the client-certificate trust, and by default that is the bundled CA whose private key ships in the jar, so anyone could mint a client certificate that passed mTLS and satisfied `ClientCertificateMatcher` criteria. `DataPlaneMutualTlsTrust` now leaves the bundled CA out (with a chain set), or refuses the set-up at start, on `PUT /mockserver/configuration` (`400`) and on any runtime TLS-context rebuild (no chain, or a chain containing it, compared by key material). The startup-only opt-in `tlsMutualAuthenticationAllowBundledCa` restores the old behaviour for throwaway tests and logs a WARN whenever the bundled CA becomes trusted: at start, on a `PUT` that makes it trusted, and on each TLS-context rebuild that trusts it. Covers the TCP and HTTP/3 listeners. See [tls-and-security.md](../code/tls-and-security.md#incoming-connection-mtls). <!-- version-ref:keep -->

## Control-plane audit logging

When MockServer runs as shared infrastructure, an opt-in audit log records *who changed mock state* (control-plane mutations: who/what/when/where/outcome) so changes are accountable. It is off by default and is **not** data-plane traffic logging. Enable it with `controlPlaneAuditEnabled` and retrieve it via `GET /mockserver/audit`. See [event-system.md](../code/event-system.md#control-plane-audit-log) and [configuration-reference.md](../code/configuration-reference.md#controlplaneauditenabled-controlplaneauditmaxentries-controlplaneauditreads).

Security-relevant properties of the audit log:

- **Records no bodies and no headers.** Each entry stores only redacted, structural metadata (method, control-plane path with the query string dropped, logical operation, source address, best-effort principal, outcome). It can never become a sink for request payloads or credential headers.
- **Redaction (by omission).** The path's query string is stripped, and no header or body is ever stored, so there is no credential-bearing free text to scrub; the `summary` field is unused (`null`) in v1. The best-effort principal parser reads only the JWT `sub` claim (or the mTLS subject CN) and never stores the raw bearer token; it is used only when the authentication handler supplied no verified principal. (If a non-null `summary` is added later it must be scrubbed via `FixtureRedactor`'s default sensitive set + `***REDACTED***` at that point.)
- **Default-off and fail-soft.** When disabled, control-plane operations behave byte-for-byte identically. The audit emit is wrapped in `try/catch` and can never throw into the request path.
- **Verified principal when one is available, otherwise best-effort.** When control-plane OIDC authentication passes, the entry carries the verified `sub` (`verified-oidc`); when control-plane mTLS passes and OIDC supplied no principal, it carries the validated client certificate's subject DN (`verified-mtls`). Otherwise the principal is read from an unverified JWT `sub` (no signature verification) or the first client certificate's CN (source `jwt` / `mtls`), else `anonymous`; do not treat those as authenticated subjects.

## Static Analysis: CodeQL

GitHub's CodeQL semantic analysis runs automatically on:
- Every push to `master`
- Every pull request targeting `master`
- Weekly (Tuesdays at 22:00 UTC)

CodeQL scans **eight languages** in the monorepo with the `security-extended` query suite. Each language is a separate matrix job with its own code scanning category, `.github/workflows/codeql-analysis.yml:analyze/language:<language>`, so a language's alerts open and close independently of the others. The category is set explicitly because it must stay byte-identical to the one Java, JavaScript, Python and Ruby already use: the action's default would now include the matrix `build-mode` value, and a changed category orphans the existing alerts.

| Language | Build mode | Scope |
|----------|-----------|-------|
| Java | `manual` (Maven `package`) | `mockserver/` (server, core, clients, integrations) |
| JavaScript | `none` | `mockserver-ui/`, `mockserver-client-node/`, `mockserver-node/` |
| Python | `none` | `mockserver-client-python/` |
| Ruby | `none` | `mockserver-client-ruby/` |
| C# | `none` | `mockserver-client-dotnet/`, `mockserver-testcontainers/dotnet/`, `examples/dotnet/` |
| Go | `autobuild` | the Go modules (directories with a `go.mod`): `mockserver-client-go/`, `mockserver-testcontainers/go/`, `examples/go/`, `examples/wasm/go/` |
| Rust | `none` | `mockserver-client-rust/`, `mockserver-testcontainers/rust/`, `examples/rust/`, `examples/wasm/` |
| Actions | `none` | `.github/workflows/` and `.github/actions/` (workflow injection, untrusted checkout, excessive permissions) |

Results appear in the [GitHub Security tab](https://github.com/mock-server/mockserver-monorepo/security/code-scanning). CodeQL detects issues including SQL injection, path traversal, insecure deserialization, cross-site scripting, and other OWASP Top 10 categories. The C#, Go, Rust and Actions languages and the `security-extended` suite were added in October 2026, so their first runs raise a batch of alerts that predate the change.

**Workflow:** [`.github/workflows/codeql-analysis.yml`](../../.github/workflows/codeql-analysis.yml)

## Static Analysis: zizmor (GitHub Actions)

[zizmor](https://docs.zizmor.sh/) audits the workflows, the composite action and `dependabot.yml` under `.github/` for template injection, excessive permissions, credential persistence, unpinned actions, Dependabot cooldown gaps and similar CI supply-chain risks. It runs on every push to `master`, every pull request targeting `master`, and weekly (Wednesdays at 05:23 UTC).

- **Where alerts appear:** the [code scanning alerts](https://github.com/mock-server/mockserver-monorepo/security/code-scanning) list, under the tool `zizmor` and the category `zizmor`. They are triaged, dismissed and closed exactly like CodeQL alerts.
- **Findings do not fail the job.** zizmor runs with `--no-exit-codes`, so findings become alerts rather than a red run. An error (unparseable workflow, bad flag) still fails the job.
- **Offline and hash-pinned.** zizmor runs with `--offline`, so it makes no network calls and needs no token. It is installed with `pip install --require-hashes` from [`.github/zizmor/requirements.txt`](../../.github/zizmor/requirements.txt), which pins the version and the sha256 of every published file. To upgrade, take the new version's hashes from `https://pypi.org/pypi/zizmor/<version>/json`.
- **No third-party actions.** The workflow uses only `actions/checkout` and `github/codeql-action/upload-sarif`, both pinned to the same SHAs as the CodeQL workflow, so the GitHub-owned-only actions allowlist is unchanged.

To run it locally: `python3 -m venv .tmp/zizmor && .tmp/zizmor/bin/pip install --require-hashes --only-binary=:all: --no-deps -r .github/zizmor/requirements.txt && .tmp/zizmor/bin/zizmor --offline .github/`.

**Workflow:** [`.github/workflows/zizmor.yml`](../../.github/workflows/zizmor.yml)

## AI agent configuration scan

A small in-repo scanner checks the AI agent configuration that every Claude Code and opencode session loads. Its rules are deterministic, with no model judgement, and it reports to the Security tab as code scanning alerts under the category `agent-config`. A finding fails the job, and the SARIF is still uploaded. Third-party AI-config scanners were rejected: each one either uploads repository content, needs a vendor token, or is too immature.

The scanner is **closed-world and fail-closed**. Anything it cannot fully understand is a finding, never a silent pass:
- a file under an AI-tool configuration root that no check claims;
- a key in a parsed configuration file that no check handles;
- a symlink, YAML outside a small subset, or JSON that fails strict parsing;
- an import it cannot follow, a launcher it does not recognise, or a file that is too large.

An internal error on one file becomes an `agent-config/scanner-error` result for that file, and the scan continues. A fatal error still writes SARIF and exits 2.

- **Scanner:** [`scripts/security/agent_config_scan.py`](../../scripts/security/agent_config_scan.py). It uses the Python 3 standard library only, makes no network calls, never follows a link, and writes SARIF 2.1.0.
- **Policy:** [`scripts/security/agent-config-policy.json`](../../scripts/security/agent-config-policy.json) holds the scope, the allowlists, the permission baselines and every exception. Both files are covered by CODEOWNERS (`/scripts/security/**`), so changing what the scanner accepts needs owner review.
- **Scope by discovery:** tracked and untracked-but-not-ignored files are in scope when they are found in one of these places. Matching ignores case.
  - Under any path segment named like an AI-tool configuration root: `.claude`, `.opencode`, `.cursor`, `.windsurf`, `.continue`, `.codex`, `.gemini`, `.aider*`, `.roo`, `.kiro`, `.amazonq`, `.junie` or `.clinerules`.
  - In files named `CLAUDE.md`, `CLAUDE.local.md`, `AGENTS.md`, `AGENT.md`, `SKILL.md`, `GEMINI.md`, `.mcp.json`, `opencode.json(c)`, `.cursorrules`, `.windsurfrules`, `.rules` or `.goosehints`, at any depth.
  - At `.github/copilot-instructions.md`, `.github/instructions/**`, `.github/prompts/**`, `.github/agents/**`, `.github/chatmodes/**`, `.vscode/mcp.json` or `.vscode/settings.json`.
  - In VS Code workspace files (`*.code-workspace`) and dev container configuration (`.devcontainer/devcontainer.json`, `.devcontainer/**/devcontainer.json` and `.devcontainer.json`), at any depth.
- **Claimed files:** every discovered file must be claimed by a check. The checks claim Claude settings, opencode config, MCP files (`.mcp.json`, `.cursor/mcp.json`, `.vscode/mcp.json`), VS Code settings, workspace and dev container files, markdown, `.cursorrules`/`.windsurfrules`, and opencode plugin or tool code. Anything else must match a `knownFiles` glob with a reason of 10 or more characters. A path that spells a known name with different case, such as `.Claude/`, `claude.md` or `.claude/Commands/`, is a finding, because macOS loads it as the canonical name. It is also checked exactly as the canonical file would be.
- **VS Code settings (closed world):** every key is first normalised the way VS Code reads it. Nested objects are flattened to dotted keys, and `[lang]` override blocks are flattened in their own scope. So `"mcp.servers"`, `{"mcp": {"servers": ...}}` and `{"Mcp": ...}` all reach the same checks, and a setting spelled twice is unparseable-config. Then each key is handled as follows:
  - Any key that auto-approves or auto-runs agent actions (`*autoApprove*`, `*allowedCommands*`, `*alwaysAllow*`, `*dangerous*`, `*skipPermission*` and similar) is permission-drift when it is switched on.
  - `mcp.servers` goes through the MCP rules.
  - Other keys must be in `vscodeSettings.allowedKeys` (with a reason) or `exactKeys`, or be editor-only settings listed in `inertKeys` or `inertKeyPrefixes` with a reason. The inert prefixes are `editor.`, `files.`, `search.`, `workbench.` and a few display prefixes. Only exact `terminal.integrated` font keys are inert, because the env, profile and shell-argument keys run code.
  - The inert lists never apply to an AI namespace (`vscodeSettings.aiKeyPrefixes`: Copilot, `chat.`, `mcp`, Roo, Cline, Claude Code, Amazon Q, Continue, Gemini Code Assist, Codeium, Windsurf, Kilo Code, Cody, Codex, Cursor).
  - Anything else is unrecognised.
- **Workspace and dev container files (closed world):** both are parsed as JSONC.
  - A `*.code-workspace` file's `settings` object goes through the VS Code settings rules above, including `mcp` servers, auto-approve keys and the inert lists. `extensions.recommendations` is inert, because it only suggests extensions. Each `folders` entry needs a relative `path` to a directory that holds tracked or unignored files, so its `.vscode` settings and MCP servers are scanned like any other in-scope location. A `uri`, an absolute path, a path outside the repository, or a folder that is missing or gitignored is an external-reference, because VS Code loads that folder's `.vscode` settings and MCP servers too. Any other top-level key, such as `tasks` or `launch`, is unrecognised unless `codeWorkspace.allowedKeys` lists it with a reason.
  - In a `devcontainer.json`, `customizations.vscode.settings` goes through the same VS Code settings rules. Each lifecycle command (`initializeCommand`, which runs on the host, and `onCreateCommand`, `updateContentCommand`, `postCreateCommand`, `postStartCommand` and `postAttachCommand`) must be an exact `devcontainer.commands` entry and must also pass the launcher check. That list is separate from the Claude `hooks.commands` list, so approving one never approves the other. The `image` must be pinned by an `@sha256:` digest or be an exact `devcontainer.images` entry: it runs with the workspace mounted read-write, so it can change the repository, including files such as `.git/hooks` that later run on the host. A string command is checked as `sh -c`, because a shell runs it. An argv list and each entry of an object of named commands are checked too. Other `customizations` and `customizations.vscode` keys, such as `extensions`, which installs extensions, are unrecognised. Any other top-level key must be in `devcontainer.inertKeys` with a reason. The list holds `$schema`, `name`, `forwardPorts`, `workspaceFolder`, `hostRequirements` and `waitFor`. Keys that run code or reach the host, such as `features`, `build`, `dockerComposeFile`, `runArgs`, `mounts`, `containerEnv` and `remoteEnv`, are unrecognised, so a feature reference, a Dockerfile `FROM` or a compose image can only be added with a scanner check.
- **What it does not protect:** the baseline records the current `Bash(*)` and opencode `bash: {"*": "allow"}`, so permission drift cannot detect a widening of shell access. The deny list is the only guard there, and its prefix patterns are easy to sidestep (`git push -f`, `rm -rf ./`).

| Rule id | Fires when |
|---|---|
| `agent-config/hidden-unicode` | A line contains a character that renders invisibly: any Default_Ignorable_Code_Point (zero-width, bidi controls, tags U+E0000–E0FFF, variation selectors, fillers, U+034F and others), U+2800, a line or paragraph separator, or a private-use, unassigned or control character other than tab and LF. Also: a carriage return that does not end the line, an emoji presentation selector (U+FE0E/FE0F) that does not directly follow a visible character, or an invalid UTF-8 byte. JSON, JSONC and YAML-frontmatter strings that decode, through `\u` escapes, to such a character are flagged too. A byte-order mark at the start of a file is allowed. |
| `agent-config/permission-drift` | `.claude/settings*.json` gains an `allow`, `ask` or `additionalDirectories` entry not in the baseline, loses a required `deny` entry, or sets `defaultMode: bypassPermissions`, `enableAllProjectMcpServers` or `disableAllHooks`. Also: a settings key outside `claudeSettings.allowedKeys` and the keys the scanner checks itself, or an `env` entry not in the exact baseline `env` map (such as `NODE_OPTIONS`, `LD_PRELOAD`, `PATH` or `ANTHROPIC_BASE_URL`; values are never reported). Also: the `opencode.jsonc` `permission` map differs from the baseline, a baselined read-only opencode agent loses `write: false` or similar, or an extra `opencode.json(c)` is not in `opencode.extraFiles`. Also: a `.claude/agents/*.md` agent gains a tool or drops its `tools` list, any agent sets `permissionMode: bypassPermissions`, or a frontmatter `tools`, `permission` or `allowed-tools` key appears in any file other than a Claude agent's own `tools` list without a `frontmatterAllow` entry. |
| `agent-config/hook-not-allowlisted` | A dev container lifecycle command not in `devcontainer.commands`, a Claude hook command, a helper command (`statusLine`, `apiKeyHelper`, `awsAuthRefresh`, `awsCredentialExport`, `otelHeadersHelper`), an opencode `experimental.hook` command or an opencode `plugin` package is not an exact allowlist entry. Also: a Claude hook that is not a plain `{type: "command", command, timeout}` object (for example a `prompt` or `agent` hook); an opencode `experimental.hook` event that differs from `opencode.experimentalHook`; an opencode `formatter` or `lsp` entry that is not an exact `opencode.formatter`/`opencode.lsp` entry (its command also goes through the launcher check); or a file under `.opencode/plugin(s)/` or `.opencode/tool(s)/` is not on the plugin-file allowlist. Also: an `enabledPlugins` entry or an `extraKnownMarketplaces` source not on the allowlist (plugins ship their own hooks and MCP servers), and a `hooks` key in any frontmatter. The message notes risky shapes such as `curl`, `\| sh`, `eval` or `base64`. |
| `agent-config/mcp-not-allowlisted` | An MCP server in `.mcp.json`, `.vscode/mcp.json`, Claude settings or `opencode.jsonc` is not identical to an `mcp.allowedServers` entry. The entry is the server's whole JSON object, so `env`, `cwd`, `headers` and the exact argv must all match. Also: a non-https URL, an `enabledMcpjsonServers` entry not on the allowlist, and an `mcpServers` or `mcp-servers` key in any frontmatter. A server container that is not an object is unparseable-config. |
| `agent-config/unpinned-package` | An MCP server or opencode plugin runs an `npx`/`bunx`/`pnpm dlx`/`yarn dlx`/`npm exec`/`uvx`/`pipx run`/`uv tool run` package without an exact version, uses `latest`, or pipes a downloaded script into a shell. A `docker`/`podman run` whose image argument has no `@sha256:` digest, or that uses an option the scanner does not know, is flagged too, as is a dev container `image` without a digest that is not a `devcontainer.images` entry. The launchers `env`, `sh -c`/`bash -c`, `cmd /c` and a command string with spaces are unwrapped first. Dev container lifecycle commands go through the same check. A leaf command that is none of these and not an `mcp.localCommands` entry is flagged: `exec`, `sudo`, `deno`, `docker --context`, a login shell, and shell substitution such as `$(...)` all fail closed. Allowlisting does not exempt an entry from this rule. |
| `agent-config/secret` | An AWS access key id, GitHub token, Anthropic or OpenAI key, Slack token, Google API key, Buildkite API token, or a private key block (header followed by key data) appears. There is deliberately no generic entropy rule. The matched value is never written to the output or the SARIF. |
| `agent-config/codeowners-coverage` | A scanned or referenced file, or one of the policy's `requiredOwnedPaths` (the scanner, its policy and its workflow), is not owned by the last matching `.github/CODEOWNERS` rule. Reported as a warning, and it still fails the job. |
| `agent-config/unparseable-config` | A settings, `.mcp.json`, `opencode.json(c)`, `*.code-workspace` or `devcontainer.json` file fails strict parsing, or one of its checked values has the wrong shape; duplicate keys and `NaN`/`Infinity` count as failures. Also: frontmatter outside the supported subset, or a file larger than `maxFileBytes` (1 MiB). The supported subset is a block mapping of plain `key:` entries whose values are plain or quoted scalars, `>`/`\|` block scalars, `- item` lists or one-level `[a, b]` lists. Flow mappings, `?` keys, tags, anchors, aliases, merge keys, nested mappings, unclosed frontmatter and a language fence such as `---json`, `---yaml`, `---js` or `+++` are all failures. Every in-scope markdown file with frontmatter is parsed. |
| `agent-config/symlink` | Any symbolic link (file or directory, on disk or git mode 120000), submodule (mode 160000) or nested repository anywhere in the repository. Examples are a `.claude` directory that points elsewhere, or a parent directory of AI configuration. A path is exempt only when `linkExceptions` lists it with a reason of 10 or more characters. |
| `agent-config/unrecognised` | A discovered file that no check claims and `knownFiles` does not list with a reason, or a case variant of a known name. Also a key that no check handles and the policy does not list with a reason: the top level of Claude settings (`claudeSettings.allowedKeys`) and of opencode config (`opencode.allowedKeys`, or `opencode.exactKeys` for keys compared exactly, such as `share`, pinned to `manual` because `auto` publishes sessions); the keys of each opencode `agent.<name>` (`opencode.agentAllowedKeys`); every frontmatter key in agent, command, skill and rule markdown (`frontmatterKnownKeys`; only `name`, `description`, `argument-hint`, `license` and `color` are inert by default); keys inside `permissions`, `hooks` (event names and group keys), `statusLine` and opencode `experimental` (`opencode.experimentalAllowedKeys`); the top level of MCP files; the top level of `*.code-workspace` files (`codeWorkspace.allowedKeys`), their `extensions` and `folders` entries; and the top level of `devcontainer.json` (`devcontainer.inertKeys`), its `customizations` and `customizations.vscode`. |
| `agent-config/scanner-error` | The scanner hit an unexpected shape or an internal error on a file. The rest of the scan continues. |
| `agent-config/external-reference` | An `@import` in a `CLAUDE*.md`, `AGENTS.md` or `GEMINI.md` file (followed recursively through imported files), an opencode `instructions` entry, a `{file:...}` reference or a `*.code-workspace` folder cannot be followed. That is the case for a URL, an absolute or `~` path, a path outside the repository, a glob that matches nothing, or a target that does not exist. Targets inside the repository are scanned instead. A token inside a code span or fenced block is followed when it resolves to a file and is otherwise ignored, so ambiguous markdown cannot hide an import. A deliberate reference, or a mention such as a GitHub handle, can be listed in `referenceExceptions` for that file with a reason. |

Prompt-injection phrase heuristics such as "ignore previous instructions" are deliberately not checked: there is no low-false-positive textual signature for them. They are left to review under `.opencode/rules/untrusted-input.md`.

**Changing a baseline.** To add an allow entry, hook, plugin or MCP server, add the exact string or object to the policy file in the same change. The policy diff is then the reviewable record of the widening. Each result carries a `partialFingerprints` entry, `agentConfigFinding/v1`, derived from the rule, the file and the offending text but not the line number. `upload-sarif` also adds its own line-content hash, `primaryLocationLineHash`. GitHub does not document which key it uses to match alerts, so whether an alert survives a line move unchanged is unverified.

**Not covered.** Prompt-injection wording (see above); the content of opencode plugin files and of `knownFiles` (only the file list is reviewed); values nested under a top-level key listed in `allowedKeys` (list a key there only when it cannot run code or redirect traffic, otherwise use `exactKeys`); and configuration outside the repository, such as `~/.claude`.

**Self-test.** Before scanning, the workflow runs `scripts/security/tests/`, which builds a temporary git repository for each case and proves every rule both fires and stays quiet. Fixtures are generated at test time: invisible characters are written as escapes and credential-shaped strings are assembled by concatenation, so no committed file contains them.

To run it locally: `python3 scripts/security/agent_config_scan.py` prints `path:line:col: [rule] message` and exits 0 (clean), 1 (findings) or 2 (the scan could not run, such as an empty scope, an exclusion without a reason, or no git). Add `--sarif out.sarif` to write SARIF. To run the self-test: `python3 -I -m unittest discover -s scripts/security/tests -p 'test_*.py'`.

**Workflow:** [`.github/workflows/agent-config-scan.yml`](../../.github/workflows/agent-config-scan.yml)

## Dependency Scanning: Dependabot

Dependabot monitors **8 package ecosystems** across the monorepo for outdated and vulnerable dependencies:

| Ecosystem | Directory(ies) | PR Limit |
|-----------|----------------|:--------:|
| Maven | `/mockserver` | 20 |
| Maven | `/examples/java` | 10 |
| Maven | `/mockserver/mockserver-maven-plugin` | 10 |
| npm | `/mockserver-ui`, `/mockserver-client-node`, `/mockserver-node`, `/.opencode` | 10 |
| pip | `/mockserver-client-python` | 10 |
| Bundler | `/mockserver-client-ruby`, `/jekyll-www.mock-server.com` | 10 |
| GitHub Actions | `/` | 10 |
| Docker | `/docker` + subdirs, `/docker_build/*` | 10 |
| Terraform | `/terraform/*` | 10 |

The Docker and Terraform directory columns are summarised; Dependabot has no glob support, so each directory is listed explicitly in [`.github/dependabot.yml`](../../.github/dependabot.yml) (10 Docker dirs, 4 Terraform dirs). When you add a new Docker/Terraform directory, add it there too or it will not be scanned.

Dependabot runs **daily** and opens pull requests for version updates and security patches. Minor and patch updates are **grouped per ecosystem** (e.g. `maven-minor-and-patch`) so related bumps land in a single PR instead of many.

### Namespace Migration Status

The `javax` → `jakarta` namespace migration is **complete** (Spring 7, Spring Boot 4, Tomcat 11, Jetty 12, Jersey 4, jakarta.* artifacts at EE 10+). The Dependabot ignore list no longer carries jakarta-related blocks. JDK-namespace `javax.*` (e.g. `javax.net.ssl`, `javax.xml.*`, `javax.script.*`, `javax.annotation.Nullable` JSR-305) remains unchanged — those classes ship with the JDK and stay `javax`.

See the Java compatibility policy in [AGENTS.md](../../AGENTS.md#java-compatibility-policy).

### Version Ceilings (Java 17 Floor)

MockServer targets **Java 17** as the minimum supported runtime. Some dependencies drop Java 17 support in newer major lines, so they are pinned below the version that requires a newer JDK. These ceilings are enforced in `.github/dependabot.yml` with explicit `versions: [">=X.0.0"]` ignore entries (stricter than ignoring only `version-update:semver-major`, and applied in **every** Maven block that references the dependency).

| Dependency | Ceiling | Reason |
|------------|---------|--------|
| `com.puppycrawl.tools:checkstyle` | `< 13.0.0` (stay on 12.x) | checkstyle 13.x is compiled for Java 21 (class file version 65.0) and fails to load under Java 17 — see the CodeQL `Analyze (java)` build, which runs on Java 17 |
| `org.infinispan:infinispan-core` | `< 15.0.0` (stay on 14.0.x) | Infinispan 15.x requires Java 21+. The 14.0.x line is the last to support Java 17. Used only by `mockserver-state-infinispan`. |
| `com.graphql-java:graphql-java` | `< 27.0.0` (precautionary) | graphql-java through 26.x ships Java 11 bytecode and runs fine on Java 17 (verified by `mockserver-core` build/tests on the Java 17 target). The ceiling is precautionary: a future major (27.x) could raise the runtime floor, so it must be manually verified on Java 17 before adoption. Pulls in `com.graphql-java:java-dataloader` (6.0.0); ANTLR4 runtime and `reactive-streams` arrive transitively. Used by `org.mockserver.graphql.*` for schema-driven GraphQL response synthesis. |
| `io.github.classgraph:classgraph` | `< 6.0.0` (stay on 5.x) | classgraph 6.0.0 ships Java 25 bytecode (class file version 69.0), so the Java 17 compiler rejects it with "class file has wrong version 69.0, should be 61.0". The 5.x line has the same API as 6.x and is built for Java 17. Used only by `FilePath.expandFilePathGlobs` to expand classpath globs in initialisation-file paths. |

**When raising the Java floor:** remove the corresponding ceiling here and the matching ignore entries in `.github/dependabot.yml`, then let the dependency upgrade. The Dependabot ignore does not block **manual** version bumps in `pom.xml` — keep this table in mind when hand-editing dependency versions.

### Native / Platform Dependencies

Dependencies that interact with the OS kernel or native libraries, added for specific platform features. These are safe on all platforms — callers detect availability at runtime and fall through when unavailable.

| Dependency | Version | Module | Purpose | Java 17 compatible |
|------------|---------|--------|---------|:---:|
| `net.java.dev.jna:jna` | `${jna.version}` (5.19.0) | `mockserver-netty` | JNA-based `getsockopt(SO_ORIGINAL_DST)` for transparent proxy original-destination resolution (`SoOriginalDstResolver`). O(1) socket option read, tried before the O(n) conntrack table scan. | Yes (supports Java 8+) |
| `io.netty:netty-transport-classes-epoll` | `${netty.version}` | `mockserver-core`, `mockserver-netty` | Pure-Java API classes for `EpollSocketChannel`, `EpollEventLoopGroup`, `EpollServerSocketChannel`, and `Epoll.isAvailable()` — needed at compile time by `NettyTransport` (transport selection) and `SoOriginalDstResolver` (fd extraction). No native classifier (the `.so` is only needed at runtime on Linux). | Yes (follows Netty BOM) |
| `io.netty:netty-transport-native-epoll` (classifier: `linux-x86_64`) | `${netty.version}` | `mockserver-netty` (runtime) | Native JNI library that activates `Epoll.isAvailable()` on Linux x86_64. Bundled in the distribution jar-with-dependencies and Docker images. Inert on non-Linux platforms. | Yes (follows Netty BOM) |
| `io.netty:netty-transport-native-epoll` (classifier: `linux-aarch_64`) | `${netty.version}` | `mockserver-netty` (runtime) | Native JNI library that activates `Epoll.isAvailable()` on Linux aarch64 (ARM64). Bundled in the distribution jar-with-dependencies and Docker images. Inert on non-Linux/non-ARM platforms. | Yes (follows Netty BOM) |
| `io.netty:netty-codec-http3` | `${netty.version}` | `mockserver-netty` (compile) | HTTP/3 codec for experimental QUIC support (graduated from the Netty incubator into mainline Netty 4.2). Transitively pulls `netty-codec-native-quic` with native classifiers for linux-x86_64, linux-aarch_64, osx-x86_64, osx-aarch_64, windows-x86_64. The native artifact contains a BoringSSL JNI binding. The natives are EXCLUDED from the default `jar-with-dependencies` and ship in the `jar-with-dependencies-http3` classifier instead; the codec CLASSES remain bundled everywhere, so nothing fails to load. Fail-fast at runtime: with `http3Port` set and the native absent, start-up fails with an `IllegalStateException` naming the fixes (it previously logged a warning and started without the listener). The published Docker images run the shaded jar, whose relocated Netty cannot load the stock native; the shaded jar bundles the native under the relocated name, and the standard image also installs it in `/usr/lib` (the `-http3` tags are a deprecated alias of it). | Yes (follows Netty BOM) |
| `org.xerial.snappy:snappy-java` | `1.1.10.7` | `mockserver-core` | Raw Snappy **block** compression for the Prometheus Remote-Write push body (`SnappyBlock` / `PrometheusRemoteWriteExporter`) — remote write mandates the block (not framed) format. Ships a self-extracting JNI native for common platforms with a pure-Java fallback; **not** shade-relocated (relocation would break the native resource path). Only exercised when `prometheusRemoteWriteEnabled=true`. | Yes (targets Java 7+) |

### Embedded Data Grid Dependencies (Optional Module)

Dependencies introduced by `mockserver-state-infinispan`, which provides the Infinispan-backed `StateBackend` for clustered MockServer state. This module is **optional** -- it is not pulled into `mockserver-core` or any other module. Its transitive dependencies enter CodeQL/Dependabot scan scope only when the module is included in the reactor build.

| Dependency | Version | Module | Purpose | Java 17 compatible |
|------------|---------|--------|---------|:---:|
| `org.infinispan:infinispan-core` | 14.0.35.Final | `mockserver-state-infinispan` | Embedded (non-server) Infinispan cache manager for LOCAL and clustered KV stores. Provides the `StateBackend` implementation when `stateBackend=infinispan` is configured. | Yes (14.0.x line targets Java 11+; 15.x raises to Java 21) |
| `org.jgroups:jgroups` | (transitive of infinispan-core) | `mockserver-state-infinispan` | Cluster transport for Infinispan. In LOCAL mode (default, `clusterEnabled=false`), no JGroups transport is started. In clustered mode (`clusterEnabled=true`), JGroups provides the SHARED_LOOPBACK in-JVM transport (for testing) or TCP transport for multi-host clustering. The default built-in JGroups stack (`jgroups-loopback.xml`) uses SHARED_LOOPBACK, which does not open network sockets; custom multi-host stacks use TCP bound to loopback by default. | Yes |
| `org.infinispan.protostream:protostream` | (transitive of infinispan-core) | `mockserver-state-infinispan` | Protocol Buffers serialization framework used internally by Infinispan. Not used directly by MockServer's clustered wire format (which uses `JavaSerializationMarshaller` with an explicit allow-list). | Yes |

**JGroups network security note:** In LOCAL mode (default, `clusterEnabled=false`), JGroups does not open any network listeners. In clustered mode (`clusterEnabled=true`), the default built-in JGroups stack uses `SHARED_LOOPBACK` transport (in-process, no network I/O), suitable for embedded testing. For multi-host clustering, users must provide a custom JGroups stack via the `clusterTransportConfig` property pointing to a JGroups XML file with a real transport (TCP/UDP) and appropriate discovery protocol (TCPPING, DNS_PING, etc.). The TCP transport should be configured with explicit bind addresses and firewall rules appropriate to the deployment environment.

**Infinispan serialization allow-list (P0 security gate -- RESOLVED in Phase 2c):** The Phase 2b LOCAL-mode backend used `global.serialization().allowList().addRegexp(".*")` -- a wildcard that permits deserialization of any class. This was safe in LOCAL mode because caches are heap-only with no network marshalling. Phase 2c **resolves this P0 gate** by configuring the clustered path with:

1. `JavaSerializationMarshaller` as the explicit marshaller (instead of ProtoStream, to handle the generic `VersionedWrapper<V>` types without per-type proto schema definitions)
2. An **explicit package allow-list** restricted to exactly the types that cross the wire:
   - `org.mockserver.state.infinispan.*` (VersionedWrapper)
   - `org.mockserver.state.*` (ExpectationEntry, Blob)
   - `org.mockserver.mock.*`, `org.mockserver.model.*`, `org.mockserver.matchers.*` (domain model)
   - `com.fasterxml.jackson.*` (ObjectNode for CRUD entities)
   - `java.lang.*`, `java.util.*`, `java.time.*`, `[B` (JDK types, byte arrays)

The `ExpectationEntry` uses custom `writeObject`/`readObject` to serialize the `Expectation` as its JSON string (via `ExpectationDTO`), avoiding the need for the entire domain model to implement `Serializable`. The LOCAL-mode path retains the `".*"` wildcard because heap-only storage never deserializes untrusted bytes.

### Kotlin DSL Dependencies (Optional Module)

`mockserver-client-kotlin` is the only Kotlin module. Nothing else in the reactor depends on it, so the Kotlin
artifacts never reach `mockserver-core`, the Java client, the server jars or the shaded `*-no-dependencies` artifacts.

| Dependency | Version | Module | Purpose | Java 17 compatible |
|------------|---------|--------|---------|:---:|
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.4.20 | `mockserver-client-kotlin` (compile) | Kotlin standard library; compile scope because the DSL's public API uses stdlib types (lambdas, `kotlin.time.Duration`). Brings `org.jetbrains:annotations:13.0`. | Yes (Java 8 bytecode) |
| `org.jetbrains.kotlin:kotlin-maven-plugin` | 2.4.20 | `mockserver-client-kotlin` (build) | Kotlin compiler for Maven, run in-process (`kotlin.compiler.daemon=false`) with `jvmTarget` 17. | Yes (runs on JDK 17) |

Both come from the module's `kotlin.version` property, so a Dependabot bump moves them together.

**Ceiling: CodeQL's supported Kotlin range (`< 2.4.21`).** The CodeQL Java/Kotlin extractor supports only a range
of Kotlin compiler versions (Kotlin 1.8.0 to 2.4.20 when this module was added), and the CodeQL workflow compiles
this module with the rest of `mockserver/`; a newer Kotlin can leave the module unanalysed without failing anything.
The `/mockserver` block of `.github/dependabot.yml` therefore ignores `org.jetbrains.kotlin:*` `>=2.4.21`. To raise
it, check the new version against the
[supported languages page](https://codeql.github.com/docs/codeql-overview/supported-languages-and-frameworks/) and
the CodeQL bundle pinned in `.github/workflows/codeql-analysis.yml`, then move the ignore and `kotlin.version`
together.

### Cloud Blob Store Dependencies (Optional Modules)

Dependencies introduced by the cloud blob store modules, which provide durable `BlobStore` implementations for S3, GCS, and Azure Blob Storage. Each module is **optional** -- it is not pulled into `mockserver-core` or any other module. mockserver-core has **zero** compile-time or runtime dependencies on any cloud SDK; the cloud modules self-register via reflection when present on the classpath. Their transitive dependencies enter CodeQL/Dependabot scan scope only when the module is included in the reactor build.

| Dependency | Version | Module | Purpose | Java 17 compatible |
|------------|---------|--------|---------|:---:|
| `software.amazon.awssdk:s3` | 2.31.9 | `mockserver-blob-s3` | AWS SDK v2 S3 client for the S3-backed `BlobStore`. Supports any S3-compatible store (MinIO, LocalStack) via endpoint override. | Yes (targets Java 8+) |
| `com.google.cloud:google-cloud-storage` | 2.49.0 | `mockserver-blob-gcs` | Google Cloud Storage client for the GCS-backed `BlobStore`. Supports fake-gcs-server for testing. | Yes (targets Java 8+) |
| `com.azure:azure-storage-blob` | 12.35.2 | `mockserver-blob-azure` | Azure Blob Storage client for the Azure-backed `BlobStore`. Supports Azurite emulator for testing. Pulls `azure-core:1.60.0` and `azure-core-http-netty:1.16.8` → `reactor-netty-http`/`reactor-netty-core` `1.2.18` (matched pair, fixes the reactor-netty chained-redirect credential-leak advisory patched in `1.2.8`). The module's `dependencyManagement` pins `io.projectreactor:reactor-core` (currently `3.8.7`; azure-core and reactor-netty both declare `3.7.19`), which keeps maven-enforcer `DependencyConvergence` green. | Yes (targets Java 8+; the whole stack is Java-8 bytecode) |

**Dependency isolation:** Each cloud SDK lives exclusively in its own module. The `mockserver-core` dependency tree contains no AWS, Google Cloud, or Azure artifacts. This is enforced by the module structure: core depends only on its own SPI interfaces (`BlobStore`, `BlobStoreFactory`), and cloud modules register their implementations via `StateBackendFactory.registerBlobStoreFactory()` at startup, discovered by reflection when `blobStoreType` is configured.

### Test Dependencies (Docker-Gated)

Test-scoped dependencies used for Docker-gated integration tests. These are never bundled in released artifacts.

| Dependency | Version | Module | Purpose |
|------------|---------|--------|---------|
| `org.testcontainers:testcontainers` | 1.21.4 | `mockserver-async`, `mockserver-blob-s3`, `mockserver-blob-gcs`, `mockserver-blob-azure`, `mockserver-netty` (test) | Core Testcontainers API for Docker-gated integration tests |
| `org.testcontainers:kafka` | 1.21.4 | `mockserver-async` (test) | Kafka container module for live-broker integration tests |
| `org.postgresql:postgresql` | 42.7.13 | `mockserver-netty` (test) | PostgreSQL JDBC driver: drives a real PostgreSQL session through MockServer's binary proxying (`PostgresThroughMockServerIntegrationTest`), including the in-band TLS upgrade, SCRAM channel binding and binary expectations answering part of a session. Targets Java 8+ |

The Testcontainers version (1.21.4) is aligned with the existing `mockserver-testcontainers` module. Note that `mockserver-testcontainers` depends on `org.testcontainers:testcontainers` (and its transitive `docker-java-*` 3.4.2 artifacts) at **compile scope** — not test scope — because its public `MockServerContainer` extends Testcontainers' `GenericContainer`; consumers of `mockserver-testcontainers` therefore resolve Testcontainers 1.21.4 transitively (overridable via their own dependency management), and these artifacts are in CodeQL/Dependabot scan scope for that module. The 1.20.6 to 1.21.4 bump was required to fix `DockerClientFactory.isDockerAvailable()` returning false on Docker Desktop 4.67+ / Engine 29.x / API 1.54 — the bundled docker-java 3.4.1 in 1.20.6 got a 400 on the info endpoint; 1.21.4 bundles docker-java 3.4.2 and includes explicit fixes for recent Docker Engine API changes. MQTT integration tests use a `GenericContainer` with `eclipse-mosquitto:2.0` (no additional Testcontainers module needed). The PostgreSQL proxying test likewise uses a `GenericContainer` with the official `postgres` image. Transparent-proxy end-to-end tests (`SoOriginalDstEndToEndIntegrationTest`, `TproxyEndToEndIntegrationTest`, `EbpfOriginalDestinationEndToEndIntegrationTest`) use the Docker CLI directly (via `ProcessBuilder`) to build and run NET_ADMIN / privileged containers for iptables REDIRECT/TPROXY rule setup and BPF map operations — they do not use Testcontainers.

### Test Dependencies (gRPC Client)

Test-scoped only, and never bundled in released artifacts. Added so the gRPC wire contract is driven by a **real gRPC client** rather than only asserted at handler level — the gap that let issue #2419 ship, where 34 green handler-level tests coexisted with a 1-in-4 real-client success rate.

| Dependency | Version | Module | Purpose |
|------------|---------|--------|---------|
| `io.grpc:grpc-netty-shaded` | 1.70.0 | `mockserver-netty` (test) | gRPC transport for `GrpcUnaryClientIntegrationTest`, including its concurrent-call cases |
| `io.grpc:grpc-stub` | 1.70.0 | `mockserver-netty` (test) | `ClientCalls.blockingUnaryCall` |
| `io.grpc:grpc-protobuf` | 1.70.0 | `mockserver-netty` (test) | `ProtoUtils.marshaller` for `DynamicMessage`, so the tests need no protoc-generated stubs |

`grpc-netty-shaded` is used in preference to `grpc-netty` deliberately: grpc-java targets Netty 4.1.x while MockServer runs Netty 4.2, and the shaded artifact bundles its own relocated Netty so the test client can neither be broken by, nor break, the server's Netty version. These artifacts pull `com.google.api.grpc:proto-google-common-protos` transitively; all are test scope in a single module. **Note that `grpc-netty-shaded` bundles a *relocated* copy of Netty inside its own jar, which Dependabot, CodeQL and Snyk cannot see into — it is effectively unscannable rather than narrowly scanned.** That is acceptable here only because it is test-scoped and never reaches a released artifact; it would not be acceptable at compile scope. If a Netty CVE is announced, the shaded copy must be checked manually by bumping `grpc.version` rather than relying on tooling to flag it.

### Test Dependencies (DNS Client)

Test-scoped only, and never bundled in released artifacts. Added for the same reason as the gRPC client above: the DNS wire contract was asserted against MockServer's own model objects and an internal cache, never against bytes a resolver would receive. `DnsRequestHandlerTest` built its `EmbeddedChannel` without the `DatagramDnsResponseEncoder` that `MockServer#bindDnsPort` installs and never called `readOutbound()`, so no test had ever produced a single DNS byte — concealing six RFC 1035 conformance defects, including a label-length bug that turned into a compression pointer and corrupted whole packets.

| Dependency | Version | Module | Purpose |
|------------|---------|--------|---------|
| `dnsjava:dnsjava` | 3.6.3 | `mockserver-netty` (test) | Independent DNS message parser for `DnsRequestHandlerWireTest` — parses MockServer's emitted datagrams exactly as a real resolver would (rcode, header flags, per-record RDATA, TXT character-string splitting) |

dnsjava is a mature pure-Java resolver library. Its only non-optional compile-scope dependency is `org.slf4j:slf4j-api`, which MockServer already depends on; `net.java.dev.jna:jna` and `jna-platform` are declared `<optional>true</optional>` and so are not pulled transitively (MockServer already manages `jna` for `SoOriginalDstResolver` in any case). It targets Java 8+, so it sits comfortably under the Java 17 floor. It is used only to *decode* MockServer's output in assertions — it is never on a production code path and never bundled into a released artifact.

### Test Dependencies (OIDC Provider Conformance)

Test-scoped only, and never bundled in released artifacts. Added for the same reason as the gRPC client above: the mock OIDC provider's conformance was asserted only against MockServer's own model objects, which is how `/introspect` came to report `active: true` for arbitrary tokens — and `/userinfo` came to serve claims to unauthenticated callers — without a single test failing.

| Dependency | Version | Module | Purpose |
|------------|---------|--------|---------|
| `com.nimbusds:oauth2-oidc-sdk` | 11.23.1 | `mockserver-core` (test) | Independent, spec-driven parsing/validation of the discovery document (`OIDCProviderMetadata.parse`) and introspection responses (`TokenIntrospectionResponse.parse`) in `OidcDiscoveryCallbackTest` and `OidcIntrospectionCallbackTest` |

This is the same code path Spring Security, Nimbus-based resource servers and pac4j use, so a response that parses here is one a real relying party accepts. It shares the `com.nimbusds` group with the already-present compile-scoped `nimbus-jose-jwt` (used to sign and verify tokens) but is a separate artifact and stays test scope — it must not become a compile dependency, since the provider itself deliberately implements OIDC rather than delegating to an SDK. Pulls `com.nimbusds:content-type` and `com.nimbusds:lang-tag` transitively; all test scope in a single module. Requires Java 11+, so it is within the Java 17 floor with no ceiling needed.
### Test Dependencies (Streaming Protocol Clients)

Test-scoped only, and never bundled in released artifacts. Added for the same reason as the gRPC client above — so WebSocket and SSE wire conformance is driven by **independent implementations of the RFCs** rather than asserted against MockServer's own objects, or against Netty, the library the server itself is built on. That gap is what let the #2419 family of "the stream looks healthy and delivers nothing" defects ship: mocked WebSockets answered no PING and echoed no CLOSE, and SSE data containing a lone CR was truncated, all while handler-level tests were green.

| Dependency | Version | Module | Purpose |
|------------|---------|--------|---------|
| `org.java-websocket:Java-WebSocket` | 1.6.0 | `mockserver-netty` (test) | Non-Netty WebSocket client for RFC 6455 control-frame conformance (PING/PONG keepalive, CLOSE handling) in `ThirdPartyStreamingClientConformanceIntegrationTest` |
| `com.squareup.okhttp3:okhttp-sse` | 5.3.2 | `mockserver-netty` (test) | Real WHATWG event-stream parser, so SSE framing is verified by a client that actually parses it; also used to PUT raw control-plane JSON |

`Java-WebSocket` has **no transitive dependencies**. `okhttp-sse` pulls `com.squareup.okhttp3:okhttp-jvm` 5.3.2, `com.squareup.okio:okio-jvm` 3.16.4 and `org.jetbrains.kotlin:kotlin-stdlib` 2.2.21, all at test scope in this one module — note that MockServer otherwise deliberately keeps okhttp and kotlin-stdlib **out** of the shaded jar (the OTLP exporter excludes the okhttp sender for exactly this reason), so their presence here must stay test-scoped. Unlike `grpc-netty-shaded` these are ordinary unshaded artifacts, so Dependabot, CodeQL and Snyk can see and scan all of them normally.

The `<okhttp-sse.version>` property is named for the artifact it actually manages: only `okhttp-sse` is version-managed, and `okhttp-jvm`/`okio-jvm`/`kotlin-stdlib` arrive transitively at whatever versions that release pins. If a CVE lands in okhttp, okio or kotlin-stdlib, bump `okhttp-sse.version` rather than expecting a direct pin to exist.

### Maven Dependency Graph Submission

**Dependabot vulnerability *alerts* are computed from the dependency graph submitted to GitHub — not from the `pom.xml` manifests directly.** (Dependabot's security-update *pull requests* are a separate mechanism that *does* read manifests; do not conflate the two.) A stale graph therefore produces **both** phantom alerts (advisories against versions the project no longer uses) **and** missed real ones (a genuine new CVE never surfaced because the graph does not reflect what the project actually resolves).

For most languages GitHub builds the graph by statically indexing manifest files. For Maven the accurate, transitive graph comes instead from **dependency submission**: a workflow resolves the full Maven tree and POSTs it to the dependency-graph API.

**The monorepo layout requires an *explicit* submission workflow.** GitHub's managed "Automatic Dependency Submission (Maven)" only discovers a Maven project at the **repository root**. When the Java project moved into the `mockserver/` subfolder (commit `386750356`, 2026-05-05) there was no longer a root `pom.xml`, and managed submission silently stopped — its last run was `2026-05-05T04:58Z`, ~17 minutes before the move. The graph then froze at a pre-move, **pre-Spring-7** snapshot. The concrete cost of that staleness:

- **Phantom alerts:** 20 Spring advisories bounded at `<= 5.3.39` stayed open against a tree actually running Spring 7, and had to be manually triaged and dismissed as inaccurate.
- **Missed fixes:** the `log4j-api` `2.25.5` and `jsoup` `1.23.1` pins in `mockserver/pom.xml` `dependencyManagement` were not reflected, so alerts for those (e.g. #528, #525) stayed open even though the fixed versions were already resolved.

[`.github/workflows/dependency-submission.yml`](../../.github/workflows/dependency-submission.yml) restores accurate submission using the official [`advanced-security/maven-dependency-submission-action`](https://github.com/advanced-security/maven-dependency-submission-action) (SHA-pinned, as all workflow actions are). It submits exactly the two Maven projects Dependabot is configured for in `.github/dependabot.yml`, each under a distinct `correlator` so the two snapshots coexist rather than overwrite each other:

| Project | `correlator` | Notes |
|---------|--------------|-------|
| `mockserver/` | `mockserver-reactor` | The aggregator reactor. Inter-module `SNAPSHOT` deps resolve in-reactor with no prior `install`. **`examples/java` is no longer a reactor module** (removed from `mockserver/pom.xml` so `/mockserver` is a self-contained directory for Dependabot's grouped updates), so its dependencies are not in this graph — intentional, as `examples/java` is sample code that is no longer published to Maven Central (it is no longer in the release reactor, so `mvn deploy -P release` never reaches it; `skipPublishing=true` in its POM guards a future re-add), so it needs no vulnerability-alert submission. Its dependency-*update* proposals are still covered by the `/examples/java` Dependabot block. |
| `mockserver/mockserver-maven-plugin/` | `mockserver-maven-plugin` | A **separate** build (not a reactor module). It depends on `mockserver-netty` and `mockserver-integration-testing` at the unreleased `${project.version}` `SNAPSHOT`, so its tree only resolves after those reactor modules are `install`ed into `~/.m2` — the job does a targeted `-pl mockserver-netty,mockserver-integration-testing -am install` first. |

The workflow is **path-gated to `mockserver/**/pom.xml`** on pushes to `master` (plus `workflow_dispatch` for on-demand re-submission after a pin lands) because resolving a Maven tree is not free — only a pom change can alter the graph.

**Recommendation on the managed submission:** it is already **inert** (it has found nothing to submit since the move) and cannot be disabled via a repo file — it is a GitHub-managed dynamic workflow. Leave it as-is; because it discovers only a root `pom.xml` that no longer exists, it will not fight the explicit workflow above. Should a root `pom.xml` ever be reintroduced, disable it under **Settings → Code security → Automatic dependency submission** to avoid two mechanisms submitting overlapping Maven graphs.

**Verified locally; one thing can only be verified once it runs on `master`.** Resolving `mockserver/pom.xml` locally produces a tree containing `log4j-api:2.25.5` (compile) and `jsoup:1.23.1` (test), confirming that once submitted these alerts clear. The actual submission to the dependency-graph API — and the resulting recomputation of alerts — can only be confirmed after the workflow first runs on `master`.

## Vulnerability Scanning: Snyk

Snyk provides a second layer of vulnerability scanning, independent of Dependabot:

- **PR status checks:** Two Snyk integrations (`security/snyk (mockserver)` and `security/snyk (jamesdbloom)`) run on every pull request
- **Dashboard:** [app.snyk.io/org/mockserver/projects](https://app.snyk.io/org/mockserver/projects)
- **Policy file:** [`.snyk`](../../.snyk) documents any vulnerability IDs that are explicitly ignored, along with the rationale and a review date

The `.snyk` policy file has an `exclude:` entry intended to skip the example project (sample code, not published to Maven Central) — but its effectiveness is unverified: the key is a bare directory/module name (`mockserver-examples`), that directory was relocated to `examples/java` by `d3cfa9aaf` in June 2026, and the exclusion has never been validated with a real `snyk test` run. Treat example-project findings as in-scope until a real `snyk test` run proves otherwise. As of the Java 17 / Jakarta EE 10 modernisation the ignore list is **empty** — the Java-11-era ignores (which suppressed ~20 Spring/Jetty/Boot/OkHttp/Reactor CVEs whose only fix required Java 17+) were removed once those vulnerable versions left the dependency tree. Vulnerabilities are now resolved through normal upgrades; add a new, dated ignore only when a deliberate constraint genuinely blocks a fix.

### Renewing Snyk ignores

When an ignore **is** added, give it a dated `expires:` (convention: 3 months out) so it cannot silently outlive its rationale. Renewal is a manual checkpoint: before the expiry date, re-run the Snyk scan, confirm the constraint still holds, and either remove the ignore (if the fix is now available) or refresh the `expires:` date with an updated reason. An empty ignore list (the current state) needs no renewal.

See [Snyk Security](snyk-security.md) for the full triage workflow, CLI commands, and vulnerability status by module.

## CI/CD Infrastructure Security

Controls applied to the Buildkite build infrastructure and release pipeline. See [AWS Infrastructure](../infrastructure/aws-infrastructure.md) and [CI/CD](../infrastructure/ci-cd.md) for full details.

### Least-Privilege CI Secrets

Each agent queue receives only the Secrets Manager policies it actually consumes — the single `buildkite-read-build-secrets` policy has been replaced by per-secret, per-queue policies:

| Queue | IAM policies attached | Rationale |
|-------|----------------------|-----------|
| `default` | `read-build-secrets-default` (API token + Sonatype), `read-dockerhub-secret`, `ecr-public-push` | Snapshot Docker push on master |
| `trigger` | `read-buildkite-api-token` | Trigger polling only needs API token |
| `perf` | `read-buildkite-api-token`, `perf-results` | Commit guard + S3 results |
| `release` | `read-build-secrets-release`, `read-release-secrets`, `read-dockerhub-secret`, `ecr-public-push`, `release-website-tfstate` | Full release publishing |

The `dependency-cache` policy is detached from all queues (runtime wiring reverted pending cache-integrity implementation).

### Release Secret Hygiene (File-based, not env vars)

Release scripts write secrets to `0600` files under `.tmp/` (volume-mounted into Docker containers) rather than passing them as `docker run -e` flags. This prevents secrets appearing in `/proc/1/environ` or `docker inspect` output on the agent host.

### Released Image Signing

All release Docker images (Docker Hub + ECR) are cosign-signed by digest after push, using the same key stored in `mockserver-release/cosign-key`. This allows consumers to verify image provenance. The release Docker step runs on the **release** queue (the only queue granted `read_release_secrets`, which includes the cosign key) and auto-installs a checksum-pinned cosign binary; signing is non-fatal if the key is unavailable. See [Docker](../infrastructure/docker.md#verifying-image-signatures) for the verification command.

### CloudTrail Audit Events

The CloudTrail trail (`mockserver-management-trail`) uses advanced event selectors to capture:
- All management events (which include every Secrets Manager API call — `GetSecretValue` on `mockserver-build/` and `mockserver-release/` secrets is logged here)
- All S3 object-level **data** events on the Terraform state bucket (`mockserver-terraform-state/`)

Secrets Manager is not a supported CloudTrail data-event resource type, so secret access is audited via management events rather than a dedicated data-event selector (an `AWS::SecretsManager::Secret` data selector is rejected by the CloudTrail API with `InvalidEventSelectorsException`).

### GuardDuty Alerting

GuardDuty findings with severity ≥ 7 (HIGH and CRITICAL) trigger an EventBridge rule that forwards to the existing `buildkite-mockserver-alerts` SNS topic. The SNS topic is encrypted with `alias/aws/sns`.

### KMS Encryption at Rest

| Resource | CMK |
|----------|-----|
| Terraform state bucket | `alias/mockserver-terraform-state` (bootstrap CMK, rotation enabled) |
| AWS Config delivery bucket | CloudTrail CMK (`alias/mockserver-cloudtrail`, shared) |
| SNS alerts topic | `alias/aws/sns` (AWS-managed) |

### VPC Flow Logs

VPC flow logs are set to `traffic_type = ALL` on all four VPCs (default, trigger, release, perf queues), capturing both accepted and rejected traffic. Previously only REJECT traffic was logged.

### Tfstate Lock Scoping

The `buildkite-release-website-tfstate` IAM policy grants `s3:DeleteObject` only on `website/terraform.tfstate.tflock` (the S3-native lock file), not on the state file itself. GetObject/PutObject on the state file are separate statements. This prevents accidental or malicious deletion of the live state.

## AI Security Review

In addition to automated scanning, every code change receives a security-focused review as part of the [AI-assisted development process](ai-assisted-development.md):

### Dedicated Security Auditor Agent

A specialist `security-auditor` AI agent performs targeted security reviews with a checklist covering:

- **Secrets & credentials** -- hardcoded tokens, API keys, connection strings, leaked PEM/JKS content, `.env` files
- **Input validation** -- untrusted data paths, missing bounds checks, charset assumptions
- **Injection prevention** -- command injection, LDAP injection, XSS, XXE, SSRF
- **Network security** -- TLS defaults, certificate validation, cipher suite selection, hostname verification
- **Java-specific** -- unsafe deserialization, `Runtime.exec()` usage, weak random, information leakage
- **Netty-specific** -- malformed request handling, ByteBuf release, pipeline state, WebSocket validation
- **Dependencies** -- known CVEs, version pinning, transitive dependency risk

### Security Lens in Every Code Review

The Review Constitution (applied to every commit, not just security-flagged ones) includes an **Insecurity lens** based on STRIDE threat modelling with 13 security principles, including MockServer-specific rules:

- TLS certificate validation must be explicit
- Control plane must be protectable
- Template injection must be prevented
- CORS headers must not weaken security

## GitHub Security Features

The repository uses several GitHub security features:

| Feature | Status | Purpose |
|---------|--------|---------|
| [Code scanning (CodeQL)](https://github.com/mock-server/mockserver-monorepo/security/code-scanning) | Active | Static analysis for vulnerabilities |
| [Code scanning (zizmor)](https://github.com/mock-server/mockserver-monorepo/security/code-scanning?query=tool%3Azizmor) | Active | Static analysis of GitHub Actions workflows |
| [Dependabot alerts](https://github.com/mock-server/mockserver-monorepo/security/dependabot) | Active | Vulnerable dependency detection |
| [Dependabot security updates](https://github.com/mock-server/mockserver-monorepo/security/dependabot) | Active | Automatic PRs for security fixes |
| [Dependency graph](https://github.com/mock-server/mockserver-monorepo/network/dependencies) | Active | Transitive dependency visibility |
| [Security advisories](https://github.com/mock-server/mockserver-monorepo/security/advisories) | Active | Private vulnerability reporting |
| Secret scanning | Active | Detects committed secrets and raises secret scanning alerts |
| Secret scanning push protection | Active | Blocks a push that contains a recognised secret before it reaches the repository |

## SNAPSHOT and Pre-Release Versions

### What are SNAPSHOT versions?

In the Maven ecosystem, a `-SNAPSHOT` suffix (e.g., `5.16.0-SNAPSHOT`) indicates the **in-development** version of the next release. SNAPSHOT artifacts are published to the Sonatype snapshots repository and represent the latest state of the `master` branch.

### Security status of pre-release artifacts

**SNAPSHOT and pre-release versions may contain unresolved security advisories.** This applies to:

| Artifact | Pre-Release Identifier | Registry |
|----------|----------------------|----------|
| Java JARs | `-SNAPSHOT` suffix (e.g., `5.16.0-SNAPSHOT`) | Maven Central snapshots |
| Docker images | `latest` and `SNAPSHOT` tags | Docker Hub |
| Node.js packages | Published only at release time | npm |
| Python package | Published only at release time | PyPI |
| Ruby gem | Published only at release time | RubyGems |

**At formal release time**, all known security issues are resolved to the extent technically possible. This means:

1. All Dependabot and Snyk alerts with available patches are addressed
2. Dependencies are updated to their latest compatible versions
3. Any new CodeQL findings are reviewed and resolved
4. The Snyk policy file's ignore expiry dates are reviewed and renewed only when a deliberate constraint still prevents a fix

**Between releases**, the `master` branch and SNAPSHOT artifacts may temporarily carry unresolved advisories -- for example, when a new CVE is published against a dependency but the fix has not yet been integrated.

### Base Image OS CVEs (Expected Baseline)

Container scanners (Trivy, Grype, the ArtifactHub Helm security report) will always list a residual set of CVEs against the Debian OS packages (`libc6`, `libexpat1`, `zlib1g`, `libuuid1`, `libpng16`, `liblcms2-2`, `libbz2-1.0`) baked into the distroless base image (`gcr.io/distroless/java25` for most variants, `gcr.io/distroless/java-base-debian12` for the standard/local and AOT images). These belong to the JRE base layer, not to MockServer code or its Maven dependencies, and are **expected** — most carry `Fixed in: -` (no upstream Debian patch yet), so they cannot be remediated at build time regardless of the JRE/Java version. The pinned base-image digests are kept current automatically by Dependabot's `docker` ecosystem, so fixes are adopted as soon as distroless rebuilds. See [Docker → Base Image CVE Baseline](../infrastructure/docker.md#base-image-cve-baseline) for the full triage guide.

### Go Standard Library in the Images (Health-Check Probe)

Every server image ships `/mockserver-healthcheck`, a static Go binary built from `docker/healthcheck/` with the Go toolchain pinned in the Dockerfiles' `healthcheck` stage (`golang:1.27-alpine@sha256:…`, currently go1.27.x). Scanners that read Go build info (Trivy, Grype, Docker Scout) will therefore report the Go standard library at that version — often as `stdlib` — alongside the JRE and Debian packages. The probe uses only `net`, `bufio`, `strconv`, `strings`, `os` and `time` to make one plain-HTTP request to `localhost`, so most Go stdlib advisories (crypto/TLS, HTTP server, parsers) are unreachable. To clear a reported one, bump the golang tag/digest in all eight Dockerfiles together. Dependabot's `docker` ecosystem has a `healthcheck-golang` group (`patterns: ["golang"]`, `group-by: dependency-name`, no `update-types` filter) meant to bump all eight in one PR. Dependabot's grouping of Docker digest bumps has been inconsistent in this repo, though, so a bump may still arrive as several one-directory PRs. `.buildkite/scripts/steps/docker-validate-sync.sh` therefore only **warns** when the eight digests differ (it still fails if any image drops the digest pin); merge the per-directory PRs together, or close them and push one commit that moves all eight. The group is not a `-minor-and-patch` group, so these PRs are never auto-merged.

### Recommendations for Consumers

- **For maximum security:** Pin to a specific release version (e.g., `9.1.0`), not `latest` or `SNAPSHOT`
- **For Renovate/Dependabot users:** Configure version constraints to only match release versions, not SNAPSHOTs
- **For Docker users:** Use versioned tags (e.g., `mockserver/mockserver:9.1.0`) rather than `latest`
- **Subscribe to releases:** Watch the [GitHub releases page](https://github.com/mock-server/mockserver-monorepo/releases) for new versions with resolved security issues

## Vulnerability Reporting

To report a security vulnerability in MockServer, use:
- **GitHub Security Advisories:** https://github.com/mock-server/mockserver-monorepo/security/advisories/new
- **Email:** Contact the maintainers through GitHub

Do not open public issues for security vulnerabilities. See [SECURITY.md](../../SECURITY.md) for full reporting guidelines.

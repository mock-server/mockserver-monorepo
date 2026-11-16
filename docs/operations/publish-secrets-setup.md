# Publish Secrets Setup Runbook

## TL;DR

Five new release channels soft-skip because their AWS Secrets Manager secrets
do not exist yet. This runbook explains how to obtain each credential, store it
under the expected secret name and JSON key, and verify the channel went live
after the next release.

All secrets live in the `mockserver-build` AWS account, region `eu-west-2`,
under the `mockserver-release/` prefix. Once created, apply the Terraform
snippet from `docs/operations/publish-secrets-terraform-snippet.md` to grant
the release-queue IAM policy access.

## Secret Inventory

| Secret ID | JSON Key | Components | Registry |
|---|---|---|---|
| `mockserver-release/nuget` | `api_key` | `client-dotnet.sh`, `tc-dotnet.sh` | NuGet.org |
| `mockserver-release/crates` | `token` | `client-rust.sh`, `tc-rust.sh` | crates.io |
| `mockserver-release/vsce` | `token` | `vscode.sh` | VS Code Marketplace |
| `mockserver-release/ovsx` | `token` | `vscode.sh` | Open VSX Registry |
| `mockserver-release/jetbrains` | `token` | `jetbrains.sh` | JetBrains Marketplace |
| `mockserver-build/postman-api-key` | `api_key` | `postman-collection.sh` | Postman API Network |

## 1. NuGet (mockserver-release/nuget)

### Obtain the token

1. Sign in to [nuget.org](https://www.nuget.org/) with the MockServer account.
2. Go to **Account Settings > API Keys**.
3. Click **Create** and configure:
   - **Key name:** `mockserver-release`
   - **Expiration:** 30 days. Since 17 August 2026 nuget.org caps new keys at
     30 days, and older keys with a longer lifetime stopped working on
     1 November 2026
     ([announcement](https://devblogs.microsoft.com/dotnet/strengthening-nuget-supply-chain-security-reducing-api-key-lifetime/);
     release 9.3.0 failed both NuGet pushes with
     `403 The specified API key has expired`). Set a reminder to rotate. <!-- version-ref:keep -->
   - **Package owner:** select the account/org that owns `MockServerClient`
   - **Glob pattern:** `*` (or scope to the two specific package IDs
     `MockServerClient` and `MockServer.Testcontainers`). The Testcontainers
     module publishes as `MockServer.Testcontainers`, NOT `Testcontainers.MockServer`:
     the `Testcontainers.*` ID prefix is NuGet-reserved by the Testcontainers org,
     so a push under it 403s regardless of the key (build #53). A `MockServer.*`
     glob matches `MockServer.Testcontainers` but NOT `MockServerClient` (no dot),
     so scope must list both ids (or use `*`).
   - **Scopes:** Push (push new packages and package versions). "Push new
     packages" is required the first time `MockServer.Testcontainers` is published.
4. Click **Create** and copy the API key immediately (it is shown only once).

### Store the secret (first time only)

```bash
aws secretsmanager create-secret \
  --region eu-west-2 \
  --name mockserver-release/nuget \
  --description "NuGet.org API key for publishing MockServerClient and MockServer.Testcontainers" \
  --secret-string '{"api_key":"<PASTE_NUGET_API_KEY>"}'
```

### Rotate the key

Rotate at least every 30 days, and before any release where the key expires
within 10 days. Create a new key as in **Obtain the token** (same glob and
scopes), then replace the secret value. Run this from the repository root. The
key goes through a private file, so it never appears on a command line or in
shell history, and the subshell keeps `umask 077` and the key out of your shell:

```bash
(
  umask 077
  mkdir -p .tmp
  read -rs NUGET_API_KEY   # paste the new key, then press Enter
  printf '{"api_key":"%s"}' "$NUGET_API_KEY" > .tmp/nuget-secret.json
  aws secretsmanager put-secret-value \
    --profile mockserver-build \
    --region eu-west-2 \
    --secret-id mockserver-release/nuget \
    --secret-string file://.tmp/nuget-secret.json
  rm -f .tmp/nuget-secret.json
)
```

Then confirm nuget.org accepts the new key for both package ids, and delete
the old key on nuget.org:

```bash
AWS_PROFILE=mockserver-build scripts/release/check-release-credentials.sh
```

The `NuGet (.NET client / TC)` row must read `VALID`. Run from a laptop, other
rows such as `website-role` read `INDETERMINATE`; that is expected off the
release queue.

### How the release preflight checks the key

`scripts/release/check-release-credentials.sh` (the release preflight
credential gate) runs the same handshake the `dotnet nuget push` client uses
for symbol packages, once per package id:

1. `POST https://www.nuget.org/api/v2/package/create-verification-key/{id}/{version}`
   with the API key. nuget.org answers 200 only if the key is unexpired and
   push-scoped, and 403 once it has expired. It returns a one-day verification key.
2. `GET https://www.nuget.org/api/v2/verifykey/{id}/{version}` with that
   verification key. This returns 200 only if the key's owner may push the id,
   and nuget.org deletes the verification key on use.

Nothing is uploaded. An expired or revoked key shows as `REJECTED` with a
pointer back here, and a key that cannot push one of the ids shows as
`DENIED`. If nuget.org sends an `X-NuGet-Warning` header on this endpoint (for
example "Your API key expires in 3 days"), the row also shows a `WARNING`; it is
not yet confirmed that nuget.org sends that header here. The check
cannot see the key's glob pattern, so keep both ids in it. NuGet is a
soft-fail release channel, so these results are advisory: they do not fail
the gate, but they appear as a warning annotation on the preflight build.

### Post-release verification

`verify.sh` runs two soft checks:

```
.NET Client (soft):
  https://api.nuget.org/v3-flatcontainer/mockserverclient/<VERSION>/mockserverclient.<VERSION>.nupkg

MockServer.Testcontainers (NuGet, soft):
  https://api.nuget.org/v3-flatcontainer/mockserver.testcontainers/<VERSION>/mockserver.testcontainers.<VERSION>.nupkg
```

Both should return HTTP 200 after NuGet indexing completes (typically < 15 min).

---

## 2. crates.io (mockserver-release/crates)

### Obtain the token

1. Sign in to [crates.io](https://crates.io/) with the MockServer GitHub account.
2. Go to **Account Settings > API Tokens**.
3. Click **New Token** and configure:
   - **Name:** `mockserver-release`
   - **Scopes:** select **publish-update** (publish new crates and new versions
     of existing crates). If the crates `mockserver-client` and
     `testcontainers-mockserver` have not been published before, also select
     **publish-new**.
   - **Crate scope:** leave blank (all crates) or restrict to
     `mockserver-client` and `testcontainers-mockserver`.
4. Click **Generate Token** and copy it immediately.

### Store the secret

```bash
aws secretsmanager create-secret \
  --region eu-west-2 \
  --name mockserver-release/crates \
  --description "crates.io API token for publishing mockserver-client and testcontainers-mockserver Rust crates" \
  --secret-string '{"token":"<PASTE_CRATES_IO_TOKEN>"}'
```

### Post-release verification

`verify.sh` runs two soft checks:

```
Rust Client (soft):
  https://crates.io/api/v1/crates/mockserver-client/<VERSION>

testcontainers-mockserver (crates.io, soft):
  https://crates.io/api/v1/crates/testcontainers-mockserver/<VERSION>
```

Both should return HTTP 200 (crates.io indexes within seconds).

---

## 3. VS Code Marketplace (mockserver-release/vsce)

### Obtain the token

1. Sign in to [Azure DevOps](https://dev.azure.com/) with the MockServer
   account (the VS Code Marketplace uses Azure DevOps PATs for publisher
   authentication).
2. Click the **User Settings** icon (top-right) > **Personal access tokens**.
3. Click **New Token** and configure:
   - **Name:** `mockserver-vsce-release`
   - **Organization:** select **All accessible organizations**
   - **Expiration:** Custom (up to 1 year)
   - **Scopes:** click **Show all scopes**, then check **Marketplace > Manage**
4. Click **Create** and copy the token.
5. If a publisher identity does not exist yet, create one:
   ```bash
   npx vsce create-publisher mockserver
   npx vsce login mockserver
   ```

### Store the secret

```bash
aws secretsmanager create-secret \
  --region eu-west-2 \
  --name mockserver-release/vsce \
  --description "VS Code Marketplace PAT (Azure DevOps) for publishing mockserver-vscode extension" \
  --secret-string '{"token":"<PASTE_AZURE_DEVOPS_PAT>"}'
```

### Post-release verification

`verify.sh` runs a soft check:

```
VS Code extension (soft):
  https://marketplace.visualstudio.com/items?itemName=mockserver.mockserver
```

The page should exist and return HTTP 200. The specific version is not checked
in the URL (the Marketplace item page always shows the latest version), so
visually confirm the listed version matches the release.

---

## 4. Open VSX (mockserver-release/ovsx)

### Obtain the token

1. Sign in to the [Open VSX Registry](https://open-vsx.org/) with GitHub.
2. Go to **Settings > Access Tokens**.
3. Click **Generate Token** and configure:
   - **Description:** `mockserver-release`
   - No scope restrictions (Open VSX tokens are full-access per namespace).
4. Copy the token.
5. If a namespace has not been claimed yet, create one:
   ```bash
   npx ovsx create-namespace mockserver -p <TOKEN>
   ```

### Store the secret

```bash
aws secretsmanager create-secret \
  --region eu-west-2 \
  --name mockserver-release/ovsx \
  --description "Open VSX Registry PAT for publishing mockserver-vscode extension" \
  --secret-string '{"token":"<PASTE_OPEN_VSX_TOKEN>"}'
```

### Post-release verification

There is no separate `verify.sh` check for Open VSX. Manually confirm at:

```
https://open-vsx.org/extension/mockserver/mockserver
```

The extension page should show the released version.

---

## 5. JetBrains Marketplace (mockserver-release/jetbrains)

### Obtain the token

1. Sign in to the [JetBrains Marketplace](https://plugins.jetbrains.com/)
   with the MockServer JetBrains account.
2. Go to the plugin page > **Edit** (if the plugin is already registered) or
   **Upload plugin** (for the first publication).
3. Navigate to **Profile > Personal Access Tokens** (or visit
   `https://plugins.jetbrains.com/author/me/tokens`).
4. Click **Generate Token** and configure:
   - **Name:** `mockserver-release`
   - **Expiration:** 1 year (set a calendar reminder to rotate)
5. Copy the token.

The `jetbrains.sh` component passes the token via the `JETBRAINS_TOKEN`
environment variable to `./gradlew publishPlugin`. The Gradle IntelliJ Plugin
reads `JETBRAINS_TOKEN` (or the `token` property in `intellijPlatform.publishing`)
to authenticate the upload.

### Store the secret

```bash
aws secretsmanager create-secret \
  --region eu-west-2 \
  --name mockserver-release/jetbrains \
  --description "JetBrains Marketplace token for publishing mockserver-jetbrains plugin" \
  --secret-string '{"token":"<PASTE_JETBRAINS_TOKEN>"}'
```

### Post-release verification

`verify.sh` runs a soft check:

```
JetBrains plugin (soft):
  https://plugins.jetbrains.com/plugin/com.mock-server.mockserver
```

The page should return HTTP 200. Visually confirm the version matches the
release.

---

## 6. Postman (mockserver-build/postman-api-key)

Drives `scripts/release/components/postman-collection.sh`, which republishes the
generated control-plane collection to the public Postman workspace each release.
Note the **`mockserver-build/` prefix** (not `mockserver-release/`) — the key is a
build/publishing credential reused across releases.

### Obtain the token

Log in to [postman.com](https://www.postman.com) with the maintainer account →
**Settings → API keys → Generate API Key**. A default full-access key is sufficient
(it needs to create/update collections in the `MockServer` public workspace).

### Store the secret

```bash
aws secretsmanager create-secret \
  --region eu-west-2 \
  --name mockserver-build/postman-api-key \
  --description "Postman API key for publishing the MockServer public collection" \
  --secret-string '{"api_key":"PMAK-..."}'
```

### Post-release verification

`verify.sh` runs a soft reachability check against the public "Run in Postman"
endpoint for the collection. The collection should report all control-plane
endpoints; confirm the request count in the workspace matches the spec.

---

## Rotation

Rotate the NuGet key at least every 30 days (see
[Rotate the key](#rotate-the-key)). Rotate the other tokens annually. When rotating:

1. Generate a new token from the registry.
2. Update the secret value:
   ```bash
   aws secretsmanager put-secret-value \
     --region eu-west-2 \
     --secret-id mockserver-release/<name> \
     --secret-string '{"<key>":"<NEW_TOKEN>"}'
   ```
3. Run a `--dry-run` release to confirm the secret loads without error.

## Terraform IAM Grants

After creating the secrets, apply the Terraform changes described in
[publish-secrets-terraform-snippet.md](publish-secrets-terraform-snippet.md)
so the release-queue Buildkite agents can read them.

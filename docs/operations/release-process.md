# Release Process

> Read [release-principles.md](release-principles.md) first if you're modifying anything in this pipeline. The principles are load-bearing: ignore them and you'll re-create the tight CI coupling we just removed.

## Operator runbook

The end-to-end checklist a release manager follows. **Use this every release.** Everything below assumes a healthy `master` branch.

### 1. Decide the version

Run the `/prepare-release` slash command from this repo. It inspects `changelog.md`, `mockserver/pom.xml`, and the latest `mockserver-X.Y.Z` git tag, then recommends:

- `release-version` (e.g. `7.5.0`)
- `next-version` (e.g. `6.1.1-SNAPSHOT`)
- `old-version` (e.g. `6.0.0` — auto-derived, you don't need to type it on the form)
- `release-type` (almost always `full`)
- `create-versioned-site` (`yes` for major/minor, `no` for patch)

The skill applies SemVer rules:

| Trigger in `## [Unreleased]` | Bump |
|---|---|
| Any bullet prefixed `BREAKING:` | **Major** |
| Bullets under `### Added` or `### Changed` | **Minor** |
| Only `### Fixed` bullets | **Patch** |
| Empty/vague | **Block** — don't release |

If you want to override the recommendation, fine — but be deliberate about it.

### 2. Validate locally (optional but recommended)

```bash
./scripts/release/test-all.sh --quick
```

Runs every component in dry-run mode locally. Takes ~5 min (Maven Central / maven-plugin / javadoc are skipped under `--quick`). The full run (~25 min) is `./scripts/release/test-all.sh`. Working tree must be clean afterwards — if you see modifications, that's a bug in a dry-run, fix it before triggering CI.

### 3. Trigger a dry-run on Buildkite (recommended for major releases)

Open https://buildkite.com/mockserver/mockserver-release → "New Build" and fill in:

| Field | Value |
|---|---|
| Branch | `master` |
| Commit | (latest on master) |
| Release Version | from step 1 |
| Next SNAPSHOT Version | from step 1 |
| Release Type | `Full Release (all steps)` |
| Create Versioned Site? | `Yes` for major/minor, `No` for patch |
| **Dry Run?** | **`Yes — build/validate only, skip publish`** |

The dry-run exercises every step inside the actual Buildkite container images. Treat it as the final gate before publishing. It still requires the TOTP block step (there is no longer a downstream-approval gate — see step 7).

### 4. Trigger the real release

Same form as step 3, but flip **Dry Run? → `No — actually publish`**.

After the form, Buildkite immediately hits a `block` step asking for a 6-digit TOTP. The token expires every 30 s; if your agent fleet is cold-starting, you may have to wait ~1 min for an agent to come up before the TOTP step actually runs. The verifier accepts ±5 minutes of clock skew, so a slow start is forgiven.

### 5. Manual gate 1 — enter the TOTP

The TOTP seed lives in Secrets Manager under `mockserver-release/totp-seed`. Use the same authenticator app you set up for previous releases. If you've lost the seed, rotate it: generate a new seed (e.g. `python3 -c "import secrets, base64; print(base64.b32encode(secrets.token_bytes(20)).decode())"`), update the secret in AWS Secrets Manager, and re-enroll it in your authenticator (issuer `MockServer Release`).

After this gate the pipeline runs `Prepare` (pom bump + tag + push) and then `Maven Central` (mvn deploy + Sonatype publish + sync wait). Maven Central typically takes 15–25 min.

### 6. Watch Maven Central

Open these URLs in tabs while step 5's job runs:

- **Live deployment state at Sonatype:** https://central.sonatype.com/publishing/deployments
  - States transition: `VALIDATING` → `VALIDATED` → `PUBLISHING` → `PUBLISHED`
  - `FAILED` means the pipeline will abort and surface the reason
- **Canonical "is it live?" check** (returns 200 once synced): https://repo1.maven.org/maven2/org/mock-server/mockserver-netty/<release-version>/
  - The pipeline polls this URL itself; you can watch the same thing in your browser
- **Central artifact view** (what end users see): https://central.sonatype.com/artifact/org.mock-server/mockserver-netty/<release-version>

### 7. Downstream publish (automatic — no gate)

There is no second manual approval. The `Maven Central` step's sync wait already polls repo1 until the main MockServer artifacts (`mockserver-netty`, `mockserver-client-java`, `mockserver-core`, `mockserver-junit-jupiter`) for this release are live — all modules publish in one Central deployment and sync together, so that poll *is* the gate. As soon as it succeeds the pipeline proceeds automatically (the timeout is a generous ~2h, so a lagging sync won't fail the release).

Versioned Site and Update Version References run first (sequentially), then the remaining channels publish in parallel: Maven Plugin, Docker, npm, Helm, Javadoc, SwaggerHub, Website, JSON Schema, PyPI, RubyGems, GitHub Release.

### 8. Verify the publishes

| Channel | Verification |
|---|---|
| Docker Hub | https://hub.docker.com/r/mockserver/mockserver/tags — `<release-version>`, `<release-version>-graaljs`, and `latest` should appear; cosign signatures attached to each image digest |
| npm — mockserver-node | https://www.npmjs.com/package/mockserver-node |
| npm — mockserver-client-node | https://www.npmjs.com/package/mockserver-client-node |
| PyPI | https://pypi.org/project/mockserver-client/ |
| RubyGems | https://rubygems.org/gems/mockserver-client |
| RubyGems — testcontainers-mockserver (soft) | https://rubygems.org/gems/testcontainers-mockserver |
| Packagist — mockserver-testcontainers (PHP, soft) | https://packagist.org/packages/mock-server/mockserver-testcontainers |
| GitHub Release | https://github.com/mock-server/mockserver/releases |
| Helm chart | https://www.mock-server.com/index.yaml — should list the new version |
| Versioned docs site (major/minor only) | `https://<release-version-with-dash>.mock-server.com` — e.g. `6-1.mock-server.com` |
| Website | https://www.mock-server.com — version pin in the footer should match |
| Homebrew/homebrew-core (a few hours later — bumped by BrewTestBot) | https://formulae.brew.sh/api/formula/mockserver.json → `.versions.stable` should equal `<release-version>` |
| Homebrew tap (`mock-server/tap`) | `brew info mock-server/tap/mockserver` — version should equal `<release-version>` |
| Scoop | `scoop bucket add mockserver https://github.com/mock-server/scoop-mockserver && scoop info mockserver` — or view `mockserver.json` directly in the bucket repo |
| winget | https://github.com/microsoft/winget-pkgs/tree/master/manifests/m/MockServer/MockServer — PR for `<release-version>` should appear, or `winget show MockServer.MockServer` after it merges |
| Chocolatey | https://community.chocolatey.org/packages/mockserver — `<release-version>` should appear (moderation may take hours to days) |
| SDKMAN! | `sdk list mockserver` — `<release-version>` should be listed and marked as default |
| asdf / mise | `asdf list all mockserver` — `<release-version>` should appear |

### 9. Homebrew homebrew-core — fully automated, no action required

The `mockserver` formula in `Homebrew/homebrew-core` is bumped automatically by **BrewTestBot** (Homebrew's own automation account). This is a JAR-based formula that depends on an OpenJDK installation. A separate, complementary Homebrew tap formula (`mock-server/tap/mockserver`) installs the self-contained bundle instead — it is published by the pipeline's own `homebrew` step, listed in §11 below. The chain for homebrew-core:

1. The Maven release publishes a `mockserver-netty-<version>-brew-tar.tar` artifact to Maven Central (built and signed by `maven-central.sh`'s `-P release` profile, same lifecycle as the regular jars).
2. The Homebrew formula has a `livecheck` block pointing at Maven Search (`https://search.maven.org/remotecontent?filepath=org/mock-server/mockserver-netty/maven-metadata.xml`). BrewTestBot's scheduled livecheck picks up the new version, computes the URL + SHA256, and opens a PR against `Homebrew/homebrew-core`.
3. Homebrew CI builds bottles (pre-compiled binaries) for the supported macOS/Linux targets. A Homebrew maintainer reviews and merges the BrewTestBot PR once all checks pass.
4. End-users running `brew upgrade mockserver` get the new version.

The whole cycle from Maven Central publish → live on Homebrew typically takes a few hours. No human action is required, and no MockServer-side script needs to invoke `brew`. The only thing the release pipeline has to keep doing is publishing the `*-brew-tar.tar` artifact (see `mockserver/pom.xml`'s `release` profile).

If a bump ever does not happen within a day or two of release, check:
- The `mockserver-netty-<version>-brew-tar.tar` artifact is actually present at `https://repo1.maven.org/maven2/org/mock-server/mockserver-netty/<version>/` (canonical mirror) and resolvable via `https://search.maven.org/remotecontent?filepath=org/mock-server/mockserver-netty/<version>/mockserver-netty-<version>-brew-tar.tar` (the URL livecheck actually polls).
- The `mockserver.rb` formula in `homebrew-core` still has a `livecheck` block (`gh api repos/Homebrew/homebrew-core/contents/Formula/m/mockserver.rb`).
- BrewTestBot's recent activity on this formula: `gh search prs --repo Homebrew/homebrew-core --author BrewTestBot mockserver`.
- Whether the BrewTestBot PR is open but unmerged: a Homebrew maintainer needs to approve and merge.

If a manual bump is genuinely required (e.g. the bot is broken), `brew bump-formula-pr --strict --version=<release-version> mockserver` from a workstation with `brew` and an authenticated `gh` CLI will open the PR by hand.

### 10. Postman & Bruno collections — automated, no action required

The Postman and Bruno collections are now **part of the pipeline**. The `postman-collection`
component (`scripts/release/components/postman-collection.sh`, a `soft_fail` step in the parallel
publish group) regenerates both collections from the OpenAPI spec
(`jekyll-www.mock-server.com/mockserver-openapi.yaml` — the single source of truth, version-stamped
by `prepare.sh`), validates the examples, and republishes the Postman collection to the public
workspace via the Postman API (key in Secrets Manager at `mockserver-build/postman-api-key`). Bruno
is git-native — the committed `.bru` files *are* the published collection.

**To change a request or add an endpoint:** edit the OpenAPI spec, then run
`python3 scripts/collections/generate_collections.py` and commit the regenerated
`examples/postman/**` + `examples/bruno/**`. The release component's drift guard fails if the
committed collections are out of sync with the spec. Validate locally with
`python3 scripts/collections/test_collections.py` (starts a MockServer in Docker and fires every
example).

### 11. Package-manager channels — automated, soft_fail

Six CLI distribution channels publish the **self-contained jlink bundles** uploaded
by the `binary` component. They run in the `:package: Package Managers` Buildkite
group (after the Binary Bundles step) as `soft_fail` steps — a channel failure
never blocks the release. Each channel also skips itself gracefully when its
prerequisite is not yet configured.

| Channel | Activation prerequisite | Details |
|---|---|---|
| Homebrew tap | `github.com/mock-server/homebrew-tap` exists + `mockserver-release/github-token` has write access | Renders `Formula/mockserver.rb` from four darwin/linux checksums and pushes to the tap repo. Distinct from homebrew-core (§9). |
| Scoop | `github.com/mock-server/scoop-mockserver` exists + `mockserver-release/github-token` has write access | Renders `mockserver.json` from the Windows bundle checksum and pushes to the bucket repo. Windows only. |
| winget | `mockserver-release/winget-github-token` set + `wingetcreate` on a Windows agent | Renders manifest and opens a PR on `microsoft/winget-pkgs`. Windows only. |
| Chocolatey | `mockserver-release/chocolatey-api-key` set + `choco` on a Windows/mono agent | Renders nuspec + install script, packs `.nupkg`, pushes to `community.chocolatey.org`. Windows only. Moderation may take hours to days. |
| SDKMAN! | `mockserver-release/sdkman-vendor` set (`consumer-key` + `consumer-token`) + `mockserver` registered as a candidate (one-time) | Calls Vendor API to register all five platforms, set default, and announce. |
| asdf / mise | `github.com/mock-server/asdf-mockserver` exists + `mockserver-release/github-token` has write access + plugin registered in asdf index (one-time) | Syncs `packaging/asdf/bin/` to the plugin repo; no per-release artifact push needed. |

Each channel's full prerequisites and manual fallback are documented in
`packaging/<channel>/release-component.md`.

**Local dry-run for any channel:**

```bash
RELEASE_VERSION=99.99.0 ./scripts/release/components/<name>.sh --dry-run
```

Renders the manifest with a placeholder checksum and skips all external writes.

### 12. Announce (optional)

If this is a notable release, post to:

- mockserver Slack / Discord (if you have one)
- The `mock-server` GitHub Discussions / Releases page (the GitHub Release notes are auto-generated from the changelog by the `github.sh` component)
- Twitter / Mastodon / etc.

---

## Architecture

```
scripts/release/
├── _lib.sh                       # shared functions: logging, dry-run, AWS,
│                                 # git, docker wrapper, version helpers
├── release.sh                    # orchestrator (prepare → update-version-references → components → finalize)
├── prepare.sh                    # validate + bump pom + tag + push
├── update-version-references.sh  # commit + push changelog / _config.yml /
│                                 # package.json / etc. so the parallel
│                                 # publish group reads the new version
├── finalize.sh                   # SNAPSHOT bump + deploy to Sonatype
├── preflight.sh                  # verify host has docker + bash + git + jq …
└── components/                   # one script per deployable artifact
    ├── maven-central.sh          # build + sign + Sonatype + publish + wait
    ├── maven-plugin.sh           # mockserver-maven-plugin release
    ├── docker.sh                 # multi-arch Docker Hub + ECR Public
    ├── npm.sh                    # mockserver-node + mockserver-client-node
    ├── pypi.sh                   # mockserver-client-python
    ├── rubygems.sh               # mockserver-client (Ruby)
    ├── helm.sh                   # Helm chart (OCI: GHCR + legacy HTTP: S3)
    ├── javadoc.sh                # Javadoc to S3
    ├── website.sh                # Jekyll site
    ├── schema.sh                 # JSON Schema
    ├── swaggerhub.sh             # OpenAPI spec to SwaggerHub (see versioning note below)
    ├── github.sh                 # GitHub Release
    ├── binary.sh                 # per-platform jlink bundles (GitHub Release assets)
    ├── versioned-site.sh         # X-Y.mock-server.com Terraform
    ├── scoop.sh                  # Scoop bucket (Windows — mock-server/scoop-mockserver)
    ├── winget.sh                 # winget PR (Windows — microsoft/winget-pkgs)
    ├── chocolatey.sh             # Chocolatey package (Windows — community.chocolatey.org)
    ├── homebrew.sh               # Homebrew tap formula (macOS/Linux — mock-server/homebrew-tap)
    ├── sdkman.sh                 # SDKMAN! Vendor API (all platforms)
    ├── asdf.sh                   # asdf/mise plugin repo sync + discoverability check
    ├── client-{go,dotnet,rust,php}.sh   # polyglot MockServer client publishers (soft_fail)
    ├── tc-{node,python,dotnet,go,rust}.sh  # Testcontainers module publishers (soft_fail)
    ├── tc-ruby.sh                # Testcontainers module -> RubyGems (self-bumps version.rb)
    └── tc-php.sh                 # Testcontainers module -> Packagist (subtree-split mirror)

.buildkite/scripts/
├── release-runner.sh             # Buildkite adapter (meta-data → env vars)
└── release-verify-totp.sh        # Buildkite-only TOTP gate

.buildkite/release-pipeline.yml   # flat list of steps; each step is one
                                  # release-runner.sh invocation
```

### SwaggerHub versioning convention

`scripts/release/components/swaggerhub.sh` enforces that **both** the SwaggerHub registry label and the uploaded spec body's `info.version` field use the `major.minor.x` form (e.g. `7.0.x`), never the full patch version. The script:

1. Asserts that the on-disk spec's `info.version` matches `RELEASE_VERSION` exactly (ensures `prepare.sh` bumped it correctly).
2. Derives `API_VERSION="${MAJOR}.${MINOR}.x"` from `RELEASE_VERSION`.
3. Creates a throwaway copy of the spec with `info.version` rewritten to `API_VERSION` and uploads that copy — the on-disk spec keeps the full patch version.

Uploading under the full patch version (e.g. `7.0.0`) rather than `7.0.x` leaves every `mockserver_api_version`-based link in the website 404ing, because `_config.yml` uses the `.x` form and all SwaggerHub links on the site reference it.

## How a release happens

### Step-by-step, locally

```bash
# 1. Verify your machine has the required host tools.
./scripts/release/preflight.sh

# 2. Run the entire pipeline in dry-run mode. Builds everything, but skips
#    every external write (npm publish, twine upload, S3 sync, gh release
#    create, git push, etc.).
./scripts/release/release.sh --version 7.5.0 --dry-run

# 3. Run a single component.
./scripts/release/components/npm.sh --dry-run        # exits with `RELEASE_VERSION` unset
RELEASE_VERSION=7.5.0 ./scripts/release/components/npm.sh --dry-run

# 4. Run only a few components.
./scripts/release/release.sh --version 7.5.0 --only=npm,pypi --dry-run

# 5. Skip components.
./scripts/release/release.sh --version 7.5.0 --skip=docker --dry-run
```

DRY_RUN defaults to `true` unless you pass `--execute`. **Locally you almost never want `--execute`** — that publishes for real.

### Step-by-step, on Buildkite

The same scripts run; the only difference is the wrapper:

1. Operator triggers the `mockserver-release` pipeline.
2. The input step collects: release version, next SNAPSHOT, type, versioned-site flag.
3. The TOTP block prompts for a 6-digit code; `release-verify-totp.sh` validates it.
4. Each subsequent step calls `.buildkite/scripts/release-runner.sh <stage>`, which:
   1. Reads Buildkite meta-data and exports it as `RELEASE_VERSION`, `NEXT_VERSION`, etc.
   2. Sets `DRY_RUN=false` (Buildkite releases for real).
   3. `exec`s the matching script under `scripts/release/`.

The release scripts themselves see only env vars. They have no idea Buildkite exists.

## Disaster recovery

Buildkite outage on release day? No problem. From a developer machine with `docker`, `aws`, `git`, `jq`, `python3`, and `bash`:

```bash
# Authenticate to AWS (for Secrets Manager + S3)
aws sso login --profile mockserver-build

# Run the same scripts the CI would have run
RELEASE_VERSION=7.5.0 \
NEXT_VERSION=6.1.1-SNAPSHOT \
RELEASE_TYPE=full \
CREATE_VERSIONED_SITE=yes \
./scripts/release/release.sh --execute
```

Every component runs in the same pinned Docker image whether you're on a laptop or a CI agent. There is no implicit CI state to recreate.

## Switching CI providers

This pipeline assumes nothing about Buildkite. If you want to run it on GitHub Actions, write a 30-line `.github/workflows/release.yml` that:

1. Receives release inputs (`workflow_dispatch` with `release_version` etc.).
2. Exports them as env vars.
3. Calls `./scripts/release/release.sh --execute` (or one component at a time, with explicit job dependencies).

Same for any other CI provider. The release scripts don't change.

## The contract

Release scripts (`scripts/release/*`) read these env vars:

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `RELEASE_VERSION` | yes | — | The version being released (X.Y.Z) |
| `NEXT_VERSION` | no | `RELEASE_VERSION` patch+1 -SNAPSHOT | Next dev version |
| `OLD_VERSION` | no | latest `mockserver-X.Y.Z` tag | Previous release |
| `RELEASE_TYPE` | no | `full` | One of: full, maven-only, docker-only, post-maven |
| `CREATE_VERSIONED_SITE` | no | `no` | `yes` for major/minor releases |
| `DRY_RUN` | no | `true` | `false` to actually publish |
| `AWS_PROFILE` | no | (not set) | Used outside CI for Secrets Manager auth |

No other vars are read. **No `BUILDKITE_*` lookups happen in release scripts** — that's the whole point.

## Version-bearing files updated by the release pipeline

The release pipeline writes every version-bearing file in the repo, so contributors should not maintain version numbers by hand. The list is exhaustive — if a release ever leaves one of these files stale, it is a pipeline bug.

| File | Updated by | What gets set |
|------|------------|---------------|
| `mockserver/pom.xml` and every child pom (~34 files — the full mockserver/ subtree, excluding `target/`) | `prepare.sh` (`update_pom_versions` in `_lib.sh`) | `<version>` and `<parent><version>` from `SNAPSHOT` → `RELEASE_VERSION` |
| `mockserver/pom.xml` and child poms (re-bump) | `finalize.sh` (`update_pom_versions`) | `<version>` and `<parent><version>` from `RELEASE_VERSION` → `NEXT_VERSION` (the next `-SNAPSHOT`) |
| `changelog.md` | `update-version-references.sh` | Promote `## [Unreleased]` to `## [RELEASE_VERSION] - YYYY-MM-DD` and re-open an empty `## [Unreleased]` |
| `jekyll-www.mock-server.com/_config.yml` | `update-version-references.sh` | `mockserver_version`, `mockserver_api_version`, `mockserver_snapshot_version` |
| `mockserver/mockserver-core/src/main/resources/org/mockserver/openapi/mock-server-openapi-embedded-model.yaml` | `prepare.sh` | OpenAPI `version:` field |
| `mockserver-node/package.json` | `update-version-references.sh` | `version`, and the embedded `mockserver-netty-<version>-jar-with-dependencies.jar` URL |
| `mockserver-client-node/package.json` | `update-version-references.sh` | `version`, and `devDependencies["mockserver-node"]` |
| `mockserver-client-python/pyproject.toml` | `update-version-references.sh` | `version = "…"` |
| `mockserver-client-ruby/lib/mockserver/version.rb` | `update-version-references.sh` | `VERSION = '…'` |
| `mockserver-client-ruby/README.md` | `update-version-references.sh` | All occurrences of the old version literal |
| `helm/mockserver/Chart.yaml` | `components/helm.sh` | `version:` and `appVersion:` (must match app version per Helm policy) |
| All `*.html`, `*.md`, `*.yaml`, `*.yml`, `*.json`, `*.txt` outside `target/`, `node_modules/`, `helm/charts/`, `.tmp/`, and the changelog | `update-version-references.sh` (general find-and-replace) | Old version literal → new version literal; old API version → new API version |
| `terraform/website/terraform.tfvars` | `components/versioned-site.sh` | Append `"<MINOR>.<PATCH>" = { bucket_name = "…" }` and update `latest_version = "<SUBDOMAIN>"` |

**Sanity check before promoting a dry-run**: `git diff` should show every file in the table above changed exactly once. If `git diff --name-only | wc -l` is wildly larger than this table suggests, something is rewriting more than expected; if smaller, a version-bearing file may have been added without wiring it into `update-version-references.sh` (or `prepare.sh` for OpenAPI / pom files).

## Dry-run behaviour by component

| Component | Dry-run does | Dry-run skips |
|---|---|---|
| `prepare` | Validate inputs, show pom diff | pom write, git commit, tag, push |
| `maven-central` | `mvn clean install` (build + test) | Sonatype upload, publish, sync wait |
| `maven-plugin` | Build core + verify plugin | tag, deploy, push |
| `docker` | `docker buildx build` (local `--load`, amd64 only) | `--push` to Docker Hub + ECR, cosign signing by digest |
| `npm` | `npm install`, grunt build | `git push tag`, `npm publish` (uses `--dry-run`) |
| `pypi` | `python -m build`, `twine check` | `twine upload` |
| `rubygems` | `gem build` | `gem push` |
| `tc-ruby` | `gem build` (self-bumps `version.rb`) | `gem push` (skips if `mockserver-build/rubygems` secret absent) |
| `tc-php` | Validate `composer.json` | subtree split + push to `mock-server/mockserver-testcontainers-php` mirror (skips if mirror repo not provisioned) |
| `helm` | `helm lint`, `helm package` | `helm push` to `oci://ghcr.io/mock-server/charts`, S3 upload, commit/push |
| `javadoc` | `mvn javadoc:aggregate` | S3 sync |
| `website` | `bundle install`, `jekyll build` | S3 sync, CloudFront invalidation |
| `schema` | jq-generate self-contained schemas | S3 sync |
| `swaggerhub` | Validate spec file | POST to SwaggerHub |
| `github` | Extract changelog notes, print preview | `gh release create` |
| `versioned-site` | `terraform plan` | `terraform apply`, S3 mirror |
| `update-version-references` | Show diff of version-reference rewrite | git push |
| `finalize` | Show pom version-bump diff | git push, mvn deploy snapshot |
| `binary` | Build all five platform jlink bundles | `gh release upload` (asset push to GitHub Release) |
| `scoop` | Fetch checksum, render manifest, print it | Push to `mock-server/scoop-mockserver` bucket repo |
| `winget` | Fetch checksum, render manifest, print it | `wingetcreate` PR submission to `microsoft/winget-pkgs` |
| `chocolatey` | Fetch checksum, render nuspec + install script, print them | `choco pack` + `choco push` |
| `homebrew` | Fetch four checksums, render formula, print it | Push `Formula/mockserver.rb` to `mock-server/homebrew-tap` |
| `sdkman` | Print the five Vendor API calls that would be made | `POST /release` × 5, `PUT /default`, `POST /announce` |
| `asdf` | Report what would be synced/verified; skip clone and push | Clone `mock-server/asdf-mockserver`, sync `bin/`, push if drifted; check release discoverability |

## Pinned Docker images

All toolchain calls run inside these images. Defined in `scripts/release/_lib.sh`:

```bash
MAVEN_IMAGE=maven:3.9.9-eclipse-temurin-17
NODE_IMAGE=node:20-bookworm
RUBY_IMAGE=ruby:3.2-bookworm
HELM_IMAGE=alpine/helm:3.16.2
GH_IMAGE=maniator/gh:v2.62.0
PYTHON_IMAGE=python:3.12-slim-bookworm
TERRAFORM_IMAGE=hashicorp/terraform:1.15
```

Override any of them by exporting the corresponding env var. Change them in `_lib.sh` to update for everyone.

## Common operations

### Retry semantics — what a Buildkite Retry actually reruns

A Buildkite job **Retry** button re-executes the step's script from the **build's pinned commit** — whichever commit was at `HEAD` when the build was triggered. That means:

| Change type | Retry sufficient? |
|---|---|
| Config / data files (secrets, YAML, env vars) | Yes — the script re-reads them at runtime |
| Script logic in `scripts/release/*.sh` or `_lib.sh` | **No** — the pinned commit's version of the script runs, not your local edits |

If you need a script-logic fix to take effect, you must **trigger a fresh build** from the commit that includes the fix.

**Half-published release recovery:** when a release half-publishes (for example, Maven Central artifacts are already live but downstream steps failed), use `RELEASE_TYPE=post-maven` to skip the Maven Central step on the fresh build and complete only the remaining publish steps:

```bash
# On Buildkite — trigger a new build with:
#   Release Type → "Post Maven (skip maven, publish remaining)"
#   Release Version → same version as the failed build

# Locally:
RELEASE_VERSION=7.5.0 RELEASE_TYPE=post-maven ./scripts/release/release.sh --execute
```

`RELEASE_TYPE=post-maven` is wired into each component via the `skip_unless_release_type` guard in `scripts/release/_lib.sh` — components that already published (such as `maven-central`) check this flag and exit early, while remaining components run normally.

### Re-run a single component after a partial-pipeline failure

If, say, the Maven Central step succeeded but `npm` failed:

```bash
# On Buildkite: open the build, click Retry on the failed step. The
# release-runner.sh adapter re-reads meta-data and re-invokes.

# Locally:
RELEASE_VERSION=7.5.0 ./scripts/release/components/npm.sh --execute
```

### Reproduce a CI failure locally

```bash
# Pull the same env vars Buildkite was using (or set them by hand) and run
# the same script.
RELEASE_VERSION=7.5.0 \
NEXT_VERSION=6.1.1-SNAPSHOT \
./scripts/release/components/maven-central.sh --dry-run
```

That reproduces what the agent was doing, in the same Docker image, on your laptop.

### Add a new deployable component

1. Create `scripts/release/components/<name>.sh` following the pattern of an existing component.
2. Wire it into the orchestrator: add `<name>` to `ALL_COMPONENTS` in `release.sh`.
3. Add a step to `.buildkite/release-pipeline.yml` that runs `.buildkite/scripts/release-runner.sh <name>`.
4. Test with `RELEASE_VERSION=X.Y.Z ./scripts/release/components/<name>.sh --dry-run`.

## For agents / LLMs reading this in a future session

If you're modifying this pipeline, **respect the principles** in [release-principles.md](release-principles.md). In particular:

- Do NOT add `buildkite-agent meta-data get` or any `BUILDKITE_*` env-var reads to a script under `scripts/release/`. If a release script needs information that currently comes from Buildkite meta-data, plumb it through as a regular env var via the adapter.
- Do NOT call any tool natively if it has an upstream Docker image. The whole pipeline relies on language toolchains being containerised so the agents stay minimal and the scripts stay portable.
- Do NOT introduce dynamic pipeline generation (the previous design did this and we explicitly removed it). The Buildkite YAML is meant to be flat and obvious.
- DO add `--dry-run` support to every new component. The smoke-test pattern (`RELEASE_VERSION=99.99.0 ./scripts/release/components/<name>.sh --dry-run`) is how operators sanity-check changes locally before triggering CI.
- DO write each component as one self-contained file: build, package, sign, publish — all in one place. No splitting across multiple steps.

When in doubt, ask: "could a human ship this release from their laptop with just `docker`, `aws`, `git`, and `bash` installed?" If the answer is no, you've broken a principle.

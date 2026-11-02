---
name: release-management
description: >
  Prepares a MockServer release by recommending the release version from
  Semantic Versioning rules and `changelog.md`, checking release readiness,
  and listing the exact Buildkite release parameters. Use when users say
  "prepare release", "release version", "run the release pipeline",
  "which version should we release", or need to verify changelog and secret
  readiness before triggering the release pipeline.
---

# Prepare a MockServer Release

Use this workflow before triggering the `mockserver-release` Buildkite pipeline.

## Required Inputs

Inspect these sources every time:

- `changelog.md` — especially the `## [Unreleased]` section
- `mockserver/pom.xml` — current development `-SNAPSHOT` version
- `.buildkite/release-pipeline.yml` — supported release parameters and automated steps
- `docs/operations/release-process.md` — operator-facing release guidance
- Git tags matching `mockserver-X.Y.Z` — latest numeric release is the authoritative `old-version`

If AWS access is available, also probe the required publishing credentials for
**liveness** — not mere presence — with
`scripts/release/check-release-credentials.sh` (read-only; never prints secret
values). Presence is not validity: MockServer 8.0.0 half-published because a
three-month-old npm token still *existed* (the old presence check passed) but
could no longer authenticate, so `npm publish` failed with 401 after the version
bumps had already been pushed. The probe calls each registry's own identity /
whoami / login-token endpoint and reports per-credential validity.

This same probe now also runs **automatically** as a hard gate in the release
preflight pipeline (`.buildkite/release-preflight-pipeline.yml`), on the
**release queue** — the only queue holding the `mockserver-release/*` grants, so
the only place `website-role` and the other release credentials can actually be
proven (dispatched via `release-runner.sh check-credentials`). That gate fails
the preflight build on any REJECTED / MALFORMED / ABSENT (exit 1) **or**
INDETERMINATE (exit 2) required credential, and is deliberately not `soft_fail`.
Running it **by hand before you start a release is still worthwhile** — it is the
fastest way to catch a dead or rotated credential without waiting for (or holding
up) a preflight build, and it works the same off-CI.

## Version Recommendation Rules

Recommend the next release from the latest numeric `mockserver-X.Y.Z` tag, not from the current `-SNAPSHOT` alone.

Apply these rules in order:

1. **Major release** — if any unreleased changelog bullet is explicitly marked `BREAKING:`
2. **Minor release** — if there are meaningful bullets under `Added` or `Changed` and no `BREAKING:` markers
3. **Patch release** — if there are only meaningful bullets under `Fixed`
4. **Block release** — if the unreleased changelog is empty, vague, or not ready to publish

Definitions:

- A "meaningful bullet" is a non-empty `- ...` entry
- `BREAKING:` should appear at the start of an unreleased bullet when a major version bump is intended

Derived values:

- `release-version`: recommended SemVer release
- `next-version`: `release-version` patch increment plus `-SNAPSHOT`
- `old-version`: latest numeric `mockserver-X.Y.Z` tag
- `release-type`: `full` unless the user explicitly wants a partial rerun
- `create-versioned-site`: `yes` for major or minor releases, `no` for patch releases

## Readiness Checks

Validate each of these before declaring the release ready:

1. `changelog.md` has meaningful unreleased bullets
2. `changelog.md` does not already contain a section for the proposed `release-version`
3. `mockserver/pom.xml` is currently on an `X.Y.Z-SNAPSHOT` version
4. The pipeline supports the required outputs for this release:
   - Maven Central core artifacts
   - `mockserver-maven-plugin`
   - Docker Hub and AWS ECR Public images
   - `mockserver-node`
   - `mockserver-client`
   - Helm
   - Javadoc
   - SwaggerHub / OpenAPI
   - website
   - JSON Schema
   - PyPI
   - RubyGems
   - GitHub Release
5. Required publishing credentials pass the **liveness probe**, not just a
   presence check. This is enforced automatically by the credential gate in the
   release preflight pipeline `.buildkite/release-preflight-pipeline.yml` (release queue), and you can run
   `scripts/release/check-release-credentials.sh` (read-only) by hand for the
   same result before starting a release. Require a clean result for every
   credential a *non-soft-fail* release step publishes with. The probe classifies
   each into one of:
   - **VALID** — authenticated successfully against the registry's own endpoint.
   - **VALID(SHAPE)** — well-formed and usable as far as can be checked, but the
     registry exposes no read-only identity endpoint, so liveness cannot be fully
     proven (PyPI token, RubyGems push-scoped key, TOTP seed).
   - **REJECTED** — present but the registry refused it (the 8.0.0 npm case). A
     release readiness = **fail**.
   - **DENIED** — authenticated, but the credential lacks a capability the
     release needs (e.g. a GitHub PAT that can read the repo but not push). For
     a required credential this is a **fail**; for an advisory capability (the
     Actions-variables check) it is a non-blocking warning. Note a GHCR token
     without `write:packages` surfaces as **REJECTED**, not DENIED — for a
     required credential both are a fail, so the verdict is the same.
   - **MALFORMED / ABSENT** — present but missing a field, or not configured.
     A release readiness = **fail**.
   - **INDETERMINATE** — the probe could not run (no AWS session, tool missing,
     caller not trusted). Report this as its own outcome — **never as pass** — and
     resolve it before releasing. (Reporting "secrets: pass" from a probe that
     silently failed is exactly the mistake an expired SSO token once caused.)

   Required (a bad credential turns the release red): `mockserver-build/sonatype`,
   `mockserver-release/gpg-key`, `mockserver-release/npm-token`,
   `mockserver-release/github-token`, `mockserver-release/dockerhub` (the release
   copy — **not** `mockserver-build/dockerhub`, which is only the SNAPSHOT-push
   credential), `mockserver-release/ghcr-token`, `mockserver-release/cosign-key`,
   `mockserver-build/pypi`, `mockserver-build/rubygems`,
   `mockserver-release/website-role`, `mockserver-release/totp-seed`.

   Advisory only (soft-fail channels — reported but never block the release):
   `mockserver-build/postman-api-key`, `mockserver-release/swaggerhub`,
   `mockserver-release/crates`, `mockserver-release/nuget`,
   `mockserver-release/vsce`, `mockserver-release/ovsx`,
   `mockserver-release/jetbrains`, `mockserver-release/mcp-dns-key`,
   `mockserver-release/dashboard-analytics`, and the GitHub PAT's
   **Actions-variables** capability (a `DENIED` here only affects the Dependabot
   release-in-flight gate, not publication; fix by adding "Variables: read and
   write" to the fine-grained PAT).
6. A **perf run measured the release commit** and its compare passed (or every
   metric it flagged is accepted). Perf runs are on demand
   (`local.perf_schedules_enabled = false` in
   `terraform/buildkite-pipelines/perf-schedules.tf`), so no scheduled run does
   this. Snapshots publish nightly, not per merge, so first publish the release
   commit's `:snapshot` images (unless the nightly already did) and wait for that
   build to pass:
   `bk build create -p mockserver-java -b master -c <full 40-char release SHA> -m "publish snapshot for <release-version> perf run" -e PUBLISH_SNAPSHOT=true`.
   It must end `passed`, not `skipped` (a newer master build can supersede it
   while queued); re-create it if it was skipped.
   Then start the perf run:
   `bk build create -p mockserver-performance-test -b master -c <full 40-char release SHA> -m "[perf-run] pre-release <release-version>"`.
   Confirm its `run + sample` job reaches `running` and the build finishes. If its
   compare step is red, investigate what it flagged: fix a real regression, re-run
   when the flag is noise, or record a reviewed acceptance in
   `mockserver-performance-test/perf-accepted-regressions.json`.
   Then `scripts/release/check-perf-preflight.sh` must report PASS. It also runs as
   a hard gate in the release preflight pipeline, where a missing, unfinished or
   unaccepted red run blocks as STALE, UNVERIFIED or BREACH. For UNVERIFIED, fix
   the fault the compare step's error annotation names (a presence gate, a missing
   budget entry and so on), then run again. Details:
   `docs/operations/release-process.md` → *Release preflight performance gate*.

## Output Format

Return a concise release-preparation report with these sections:

### Recommendation

- `release-version`
- `next-version`
- `old-version`
- `release-type`
- `create-versioned-site`

### Rationale

- Explain why the bump is major, minor, or patch
- Cite the relevant changelog bullets and release tag comparison

### Readiness

- `changelog`: pass/fail with reason
- `version state`: pass/fail with current snapshot version
- `credentials`: the per-credential liveness result from
  `scripts/release/check-release-credentials.sh` — report each required
  credential's outcome (VALID / VALID(SHAPE) / REJECTED / MALFORMED / ABSENT /
  INDETERMINATE), not a single "secrets: pass". Overall = **fail** if any
  required credential is REJECTED / MALFORMED / ABSENT; **inconclusive** (resolve
  before releasing) if any required credential is INDETERMINATE; **pass** only
  when every required credential is VALID or VALID(SHAPE). Note any advisory
  (soft-fail) credential that is not VALID, but it does not change the verdict.
- `pipeline coverage`: pass/fail with any remaining gaps
- `perf run`: pass only when a `[perf-run]` build measured the release commit
  and `check-perf-preflight.sh` reports PASS; otherwise fail, with the
  `bk build create` command from readiness check 6

### Manual Follow-up

Steps outside — or not guaranteed by — the automated pipeline:

- **Homebrew** — **not** a manual step; do not report it as one. The only Homebrew channel
  is the `mockserver` formula in `Homebrew/homebrew-core` (JAR-based), which **BrewTestBot**
  bumps from the `*-brew-tar.tar` artifact on Maven Central, typically within a few hours
  (`docs/operations/release-process.md` §9). MockServer publishes no tap of its own.

  Only intervene if BrewTestBot has not opened a PR a day or two after release — §9 lists what
  to check, and `brew bump-formula-pr --strict --version=<release-version> mockserver` is the
  fallback for a broken bot, not the normal path.
- **SwaggerHub** — the `swaggerhub` pipeline step is `soft_fail: true`, so a failure
  never blocks the release. Publishing via the SwaggerHub Registry API needs
  account / API-plan access the pipeline cannot guarantee — the write endpoint can
  return `403`/`404` even with a valid key on an account without it. The step is kept
  in the pipeline so it publishes automatically if that access is ever in place; but
  if it soft-fails, upload the spec manually: open https://app.swaggerhub.com, go to
  the `jamesdbloom/mock-server-openapi` API, and add the new version from
  `mockserver/mockserver-core/src/main/resources/org/mockserver/openapi/mock-server-openapi-embedded-model.yaml`
  (the web UI needs no API key). This may become fully automatic if SwaggerHub
  changes that API behaviour.

### Monitor & Verify

Always include this section verbatim so the operator can watch the release land. Substitute the chosen `release-version` into the URLs:

- **Sonatype Central Portal (live deployment status):** https://central.sonatype.com/publishing/deployments
- **Central Portal artifact view:** https://central.sonatype.com/artifact/org.mock-server/mockserver-netty/<release-version>
- **Live at Maven Central (canonical "released" signal — returns 200 once synced):** https://repo1.maven.org/maven2/org/mock-server/mockserver-netty/<release-version>/
- **Maven Central search (browse all org.mock-server artifacts):** https://central.sonatype.com/search?namespace=org.mock-server&sort=published
- **Docker Hub tags:** https://hub.docker.com/r/mockserver/mockserver/tags
- **npm — mockserver-node:** https://www.npmjs.com/package/mockserver-node
- **npm — mockserver-client-node:** https://www.npmjs.com/package/mockserver-client-node
- **PyPI:** https://pypi.org/project/mockserver-client/
- **RubyGems:** https://rubygems.org/gems/mockserver-client
- **GitHub Releases:** https://github.com/mock-server/mockserver/releases
- **Buildkite pipeline:** https://buildkite.com/mockserver/mockserver-release

## Notes

- Prefer explicit evidence from the repo over assumptions
- If the changelog suggests a major bump but no `BREAKING:` marker exists, call that out and ask the user whether the release should be treated as breaking before finalising the recommendation
- If the user asks to run a partial rerun, keep `release-version` and `old-version` consistent with the already-published release and explain which downstream steps will be skipped

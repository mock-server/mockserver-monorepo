# Release 9.0.0 Follow-ups

**Outcome.** Two rows remain: one fix in review (F2) and the release-time step for the 9.0.0
security advisory (F6). F1, F3, F4, F5, F7 and F8 have landed. Each row is a self-contained change. This file
is deleted once every row is closed. The Netty pull request and issue (backlog rows 91a and 540a)
stay in [performance-measurement-backlog.md](performance-measurement-backlog.md) for after 9.0.0.

## What remains

| # | Item | Blocked on |
|---|---|---|
| F2 | OpenAPI spec fetchers other than the import path still run a blocking fetch on the event loop | a change (§2) |
| F6 | Publish security advisory GHSA-jg86-hv4g-8h78 (`forwardProxyBlockPrivateNetworks` bypass, fixed in 9.0.0) | the 9.0.0 release (§2) |

## Detail

| # | Item | Context | Next |
|---|---|---|---|
| F2 | OpenAPI spec fetchers other than the import path still run a blocking fetch on the event loop | Found 2026-10-10 reviewing the library fix (E2E-LIB-14), which moved the dashboard/REST OpenAPI URL import off the event loop after a self-served spec stalled about one import in six for ~60 s. Other callers of the spec fetch (for example `PUT /mockserver/openapi` with a URL, `loadScenario/generateFromOpenAPI`, MCP tools) may still fetch on an event-loop thread. | List every caller of the spec fetch, move each blocking fetch off the event loop, and add a single-event-loop test for each that fetches a spec served by the same MockServer. |
| F6 | Publish security advisory GHSA-jg86-hv4g-8h78 (`forwardProxyBlockPrivateNetworks` bypass, fixed in 9.0.0) | The draft advisory is prepared: CVSS 3.1 `AV:N/AC:H/PR:N/UI:N/S:C/C:L/I:N/A:N` (4.0 Medium), CWE-918, affected `org.mock-server:mockserver-netty` `< 9.0.0`, patched `9.0.0`; the fix is d0e0c521b. | When 9.0.0 is published, confirm the patched version, request a CVE if wanted, and publish the advisory (owner approval). |

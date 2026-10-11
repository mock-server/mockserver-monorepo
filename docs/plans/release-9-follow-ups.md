# Release 9.0.0 Follow-ups

**Outcome.** One row remains: the release-time step for the 9.0.0 security advisory (F6). F1 to
F5, F7 and F8 have landed. Each row is a self-contained change. This file
is deleted once every row is closed. The Netty pull request and issue (backlog rows 91a and 540a)
stay in [performance-measurement-backlog.md](performance-measurement-backlog.md) for after 9.0.0.

## What remains

| # | Item | Blocked on |
|---|---|---|
| F6 | Publish security advisory GHSA-jg86-hv4g-8h78 (`forwardProxyBlockPrivateNetworks` bypass, fixed in 9.0.0) | the 9.0.0 release (§2) |

## Detail

| # | Item | Context | Next |
|---|---|---|---|
| F6 | Publish security advisory GHSA-jg86-hv4g-8h78 (`forwardProxyBlockPrivateNetworks` bypass, fixed in 9.0.0) | The draft advisory is prepared: CVSS 3.1 `AV:N/AC:H/PR:N/UI:N/S:C/C:L/I:N/A:N` (4.0 Medium), CWE-918, affected `org.mock-server:mockserver-netty` `< 9.0.0`, patched `9.0.0`; the fix is d0e0c521b. | When 9.0.0 is published, confirm the patched version, request a CVE if wanted, and publish the advisory (owner approval). |

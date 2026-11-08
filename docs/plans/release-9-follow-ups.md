# Release 9.0.0 Follow-ups

**Outcome.** One row remains open. F10 is done: 9.1.0 shipped 2026-10-21 (`changelog.md`) and the
website note landed in ebbd86ee2 (2026-11-01). F6's advisory is already published
(GHSA-jg86-hv4g-8h78, 2026-10-19) with no CVE requested — the owner has not yet decided whether to
request one, so this row now just tracks that decision. F1 to F5 and F7 to F9 have landed. Each
row is a self-contained change. This file is deleted once every row is closed. The Netty pull
request and issue (backlog rows 91a and 540a) stay in
[performance-measurement-backlog.md](performance-measurement-backlog.md) for after 9.0.0.

## What remains

| # | Item | Context | Blocked on | Next |
|---|---|---|---|---|
| F6 | Decide whether to request a CVE for security advisory GHSA-jg86-hv4g-8h78 (`forwardProxyBlockPrivateNetworks` bypass, fixed in 9.0.0) | CVSS 3.1 `AV:N/AC:H/PR:N/UI:N/S:C/C:L/I:N/A:N` (4.0 Medium), CWE-918, affected `org.mock-server:mockserver-netty` `< 9.0.0`, patched `9.0.0`; the fix is d0e0c521b. The advisory was published 2026-10-19 (`gh api repos/mock-server/mockserver-monorepo/security-advisories/GHSA-jg86-hv4g-8h78`: `state: published`, `published_at: 2026-10-19T18:43:52Z`) with no CVE requested; `cve_id` is still `null` and `updated_at` has not moved since publish, so no request is in flight. The owner has not yet decided whether to request one. | An owner decision on whether to request a CVE | Owner decides whether to request a CVE for the published advisory. If yes, request it via the advisory's GitHub UI or `gh api -X POST .../cve`, then poll `--jq .cve_id` until set. |
| ~~F10~~ | ~~Note the before/after action change on the website~~ — **done** | Since 60f29b79e (9.1.0, released 2026-10-21 per `changelog.md`), chaining the singular `withBeforeAction` / `withAfterAction` adds actions instead of replacing the previous one. Documented in ebbd86ee2 (2026-11-01), which updated `jekyll-www.mock-server.com/mock_server/before_and_after_actions.html` to describe both the 9.1.0+ append behaviour and the pre-9.1.0 replace behaviour. | — | — |

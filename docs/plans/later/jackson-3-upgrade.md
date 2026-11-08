# Jackson 2 → Jackson 3 Upgrade — Blocked Upstream

**Status:** Blocked, but one layer has cleared — `swagger-core` shipped its Jackson 3 GA release,
`3.0.0`, on 2026-10-28 (confirmed on Maven Central: `tools.jackson.core:jackson-databind` /
`tools.jackson.dataformat:jackson-dataformat-yaml` in its POM). `swagger-parser` is now the sole
blocker: its maintainers posted on 2026-11-04 that they have **started** the Jackson 3 migration
(tracking issue [swagger-parser#2261](https://github.com/swagger-api/swagger-parser/issues/2261)),
but there is still no PR, snapshot, or release — the `upgrade-to-jackson3` branch is unchanged
since 2026-09-07 (one commit, `c987f220b`). Our own side of the migration is understood and
mechanical; nothing is blocked on MockServer.
**Created:** 2026-07-19
**Last verified against upstream:** 2026-11-08
**Scope:** Migrate MockServer's own serialization from `com.fasterxml.jackson.*` (Jackson 2) to `tools.jackson.*` (Jackson 3).
**Driver:** Spring Boot 4 ships Jackson 3, so consumers increasingly run Jackson 3 in their applications. Requested in [#1970](https://github.com/mock-server/mockserver-monorepo/issues/1970) and [#2426](https://github.com/mock-server/mockserver-monorepo/issues/2426) (closed as duplicate).

## The blocker

`swagger-parser` backs OpenAPI mock loading — a core MockServer feature we cannot drop — and has no Jackson 3 release. It depends on `swagger-core`, which has now shipped Jackson 3 (`3.0.0` GA, 2026-10-28), so `swagger-parser` alone gates us.

**Upstream trackers to watch:**

| Tracker | State (2026-11-08) |
|---------|--------------------|
| [swagger-parser#2261](https://github.com/swagger-api/swagger-parser/issues/2261) — "Upgrade to Jackson 3" | Open. On 2026-11-04, maintainer `daniel-kmiecik` posted that work on the migration has **started**, following swagger-core's own Jackson 3 upgrade, with progress updates and a snapshot to be shared as milestones land — no PR, snapshot or release yet. The `upgrade-to-jackson3` branch (one commit, `c987f220b`, 2026-09-07) is unchanged since the last check. This is the tracker that actually gates us. |
| [swagger-core#4991](https://github.com/swagger-api/swagger-core/issues/4991) — "Support Jackson 3.x release line" | Resolved in practice: superseded by the `3.0.0` branch work, now GA (see below). |
| swagger-core `3.0.0` | **Shipped GA 2026-10-28.** Confirmed on Maven Central: the `3.0.0` POM declares `tools.jackson.core:jackson-databind` and `tools.jackson.dataformat:jackson-dataformat-yaml` (Jackson 3), with `com.fasterxml.jackson.core:jackson-annotations` (Jackson 2 namespace, as expected — annotations stayed on the old coordinates even in Jackson 3). This blocker has cleared. |

**Versions at last check (2026-11-08):**

| Library | Latest | Jackson |
|---------|--------|---------|
| `io.swagger.parser.v3:swagger-parser` | 2.1.48 | `com.fasterxml` (2.x) only — unchanged |
| `io.swagger.core.v3:swagger-core` | 3.0.0 | `tools.jackson` (3.x) — **now GA, released 2026-10-28** |
| `tools.jackson.core:jackson-core` | 3.2.3 | — |
| Our pin (`mockserver/pom.xml` `jackson.version`) | 2.22.3 | — |

`swagger-parser` 2.1.48 still depends on the Jackson-2 `swagger-core` 2.x line, not the new 3.0.0, so MockServer's dependency tree is unaffected by swagger-core's release until swagger-parser itself moves.

**Rejected workaround:** `io.github.vpelikh` publishes a Jackson 3 fork of the swagger-core stack, but it ships no `swagger-parser` artifact, so swapping core alone still leaves the parser dragging the whole Jackson 2 tree in — does not unblock us on its own. (Its work did not go to waste: swagger-core's own `3.0.0` branch, PR #5314, is built on top of it.)

## What is *not* blocking

- **Java version.** 7.0.0 moved to Java 17, clearing Jackson 3's Java 17 floor. This was the stated blocker in #1970 and no longer applies — swagger-parser is now the *only* one.
- **Two Jackson namespaces coexisting.** Already proven in production code: `com.networknt:json-schema-validator` 3.x (currently 3.0.6) runs on `tools.jackson` inside MockServer today — migrated in commit `2449bcab1` — alongside our Jackson 2 usage. Different Maven coordinates and different Java packages mean they cannot collide.
- **Consumers on Jackson 3.** For the same reason, MockServer depending on Jackson 2 does not prevent a Spring Boot 4 application from using Jackson 3. Reports of `ClassNotFoundException: com.fasterxml.jackson.databind.JsonSerializer` are Jackson 2 being *excluded* by a BOM, not a genuine incompatibility.

## Our side of the work, when unblocked

Mechanical, with no exotic API surface to redesign:

1. Namespace migration `com.fasterxml.jackson.*` → `tools.jackson.*` across the codebase (~361 files at the time of the #1970 investigation).
2. Update `jackson.version` / `jackson-annotations.version` in `mockserver/pom.xml` to the Jackson 3 coordinates. Note Jackson 3 keeps annotations on `com.fasterxml.jackson.core:jackson-annotations` — that split is expected, not a mistake.
3. Revisit the shade relocations in `mockserver/pom.xml` — `com.fasterxml` → `shaded_package.com.fasterxml` and `tools.jackson` → `shaded_package.tools.jackson` both already exist; the balance between them shifts.
4. Drop the Jackson 2/3 bridging currently needed in `JsonSchemaValidator` (string-based `getSchema` / `validate` calls exist purely to stop Jackson 2 `JsonNode` crossing into Jackson 3 APIs — see `2449bcab1`). Once everything is Jackson 3 this can go back to node-based calls.
5. Re-check `mockserver-client-java`'s parser exclusions and the `dependencyConvergence` guarantees established in 7.1.0 (#1970) — the transitive tree changes shape.

Major-version-worthy: it changes the transitive dependency coordinates of every published artifact.

## Re-check trigger

Re-evaluate when **swagger-parser publishes a Jackson 3 line**. Cheapest probe:

```bash
curl -s https://repo1.maven.org/maven2/io/swagger/parser/v3/swagger-parser/maven-metadata.xml | grep latest
# then fetch that version's POM and grep for tools.jackson
```

Watch [swagger-parser#2261](https://github.com/swagger-api/swagger-parser/issues/2261) — nothing else moves until that does.

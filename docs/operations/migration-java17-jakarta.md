# Migrating to MockServer 6.x — Java 17 & Jakarta EE 10

MockServer 6.x is a **breaking platform modernisation**. The minimum runtime is now **Java 17**, the `javax.*` → `jakarta.*` namespace migration is complete, and the dependency stack moved onto current major lines (Spring 7, Spring Boot 4, Tomcat 11, Jetty 12, Jersey 4, Netty 4.2). This guide covers the consumer-visible changes and how to adapt.

> This is the consumer-facing upgrade guide. For the internal rationale and the full dependency bump list, see the `## [6.0.0]` section of [changelog.md](../../changelog.md) (where the platform modernisation shipped) and [docs/operations/security.md](security.md).

## At a glance

| Area | 5.15.x (and earlier) | 6.x |
|------|----------------------|-----|
| Minimum Java runtime | Java 11 | **Java 17** |
| Servlet / Jakarta EE | Servlet 4 / `javax.servlet.*` | **Servlet 6 / Jakarta EE 10 / `jakarta.servlet.*`** |
| Spring Framework / Boot | 5.3.x / 2.7.x | **7.x / 4.x** |
| Servlet containers | Tomcat 9, Jetty 9.4, WildFly ≤ 26 | **Tomcat 11+, Jetty 12+, WildFly 32+** |
| Shaded artifact coordinates | `:shaded` classifier | **`-no-dependencies` artifact** (removed in 6.0.0) |
| JavaScript templating | Nashorn or GraalJS (JSR-223) | **GraalVM Polyglot** |

## 1. Raise your Java runtime to 17

Published 6.x artifacts are Java 17 bytecode and **will not load on a Java 11 JVM**. This applies to every consumption mode — the JAR, the Docker image, the WARs, the Maven plugin, and the client embedded in your tests.

- **Can't move to Java 17 yet?** Pin to the last Java 11-compatible line, `5.15.x`. Note it no longer receives security updates — see the [security policy](../../SECURITY.md).

The official Docker image ships its own Java 26 runtime, so containerised users need no JVM change.

## 2. Servlet-container deployment (WAR users only)

If you deploy `mockserver-war` or `mockserver-proxy-war` into a servlet container, you now need a **Servlet 6 / Jakarta EE 10** host: **Tomcat 11+, Jetty 12+, WildFly 32+**, or equivalent. Servlet 5 / Jakarta EE 9 and earlier containers are no longer supported. (WildFly 27+ supports the EE 10 Core Profile, but WildFly 32+ is the first line to carry the full EE 10 Web Profile including Servlet 6 — use 32+ for WAR hosting.)

The servlet runtime contract is otherwise unchanged for consumers already on `jakarta.servlet.*`. If your surrounding code still imports `javax.servlet.*`, update those imports to `jakarta.servlet.*`.

## 3. Embedded / Spring users

- **Spring 7 / Boot 4**: if your test code shares a Spring context with MockServer's Spring integration, it must be Spring 7 / Boot 4 compatible (which itself requires Java 17).
- **`jakarta.*` namespace**: any direct use of the Jakarta EE APIs that moved namespace — `jakarta.ws.rs.*`, `jakarta.inject.*`, `jakarta.persistence.*`, and the Jakarta Annotations formerly at `javax.annotation.*` (JSR-250: `@Resource`, `@PostConstruct`, …) — must use the `jakarta.*` equivalents. Two categories are **unchanged**: (1) JDK-namespace `javax.*` classes (`javax.net.ssl`, `javax.xml.*`, `javax.script.*`) that ship with the JDK; and (2) **JSR-305 nullability annotations** (`javax.annotation.Nullable` / `@Nonnull` from `com.google.code.findbugs:jsr305`) — these share the `javax.annotation` package name but are a separate, unrelated artifact from Jakarta Annotations and stay `javax`.
- **Cookie values**: Servlet 6 preserves RFC 6265 surrounding double-quotes on cookie values. MockServer's request decoder strips them, so cookie semantics are unchanged for clients.

## 4. Shaded artifact coordinates (changed in 6.0.0)

The `<classifier>shaded</classifier>` form was **removed in 6.0.0**. Replace it with the dedicated `-no-dependencies` artifact:

```diff
- <artifactId>mockserver-netty</artifactId>
- <version>9.2.0</version>
- <classifier>shaded</classifier>
+ <artifactId>mockserver-netty-no-dependencies</artifactId>
+ <version>9.2.0</version>
```

(Substitute the MockServer version you are upgrading to for `9.2.0`.) The replacement produces the same shaded bytes — only the coordinates change. The same switch applies to every artifact that previously offered a `shaded` classifier: `mockserver-client-java`, `mockserver-netty`, `mockserver-junit-rule`, `mockserver-junit-jupiter`, and `mockserver-spring-test-listener`.

## 5. JavaScript response templates

`JavaScriptTemplateEngine` now uses the **GraalVM Polyglot API** directly instead of Nashorn / the JSR-223 (`javax.script`) bridge, which GraalJS 25.x dropped. Template syntax (`Java.type(...)`, the class deny-list security policy) is unchanged.

If a downstream consumer previously relied on **Nashorn arriving transitively** through MockServer, add `org.openjdk.nashorn:nashorn-core` to your own dependencies, or migrate to the GraalVM Polyglot API directly.

## 6. TLS in WAR test scaffolding (rare)

If you configured TLS for an embedded Tomcat in your own WAR test harness via the removed `Connector.setAttribute("keystoreFile"/"keystorePass"/…)` API, migrate to the Tomcat 11 `SSLHostConfig` + `SSLHostConfigCertificate` pattern. The four WAR/proxy-war integration test classes in this repository show the working shape.

## Quick checklist

- [ ] Test/runtime JVM is **Java 17+**
- [ ] (WAR only) servlet container is **Tomcat 11+ / Jetty 12+ / WildFly 32+**
- [ ] Any surrounding `javax.{servlet,annotation,ws.rs,inject,persistence}` imports moved to `jakarta.*`
- [ ] `:shaded` classifier dependencies switched to `-no-dependencies` artifacts
- [ ] (If you relied on transitive Nashorn) `nashorn-core` added explicitly, or migrated to GraalVM Polyglot
- [ ] Shared Spring context (if any) upgraded to Spring 7 / Boot 4

# MockServer Maven Plugin

> A Maven plugin to start, stop and fork [MockServer](https://mock-server.com/) from Maven builds

[![Build status](https://badge.buildkite.com/9005b0b9e44f14184673967663e7a424f9cdd1278c9c74a96c.svg?branch=master&style=square&theme=slack)](https://buildkite.com/mockserver/mockserver-maven-plugin)
[![Maven Central](https://img.shields.io/maven-central/v/org.mock-server/mockserver-maven-plugin.svg)](https://central.sonatype.com/artifact/org.mock-server/mockserver-maven-plugin)

## Documentation

For usage guide please see: [www.mock-server.com](https://www.mock-server.com/)

## Quick Start

Add the plugin to your `pom.xml`:

```xml
<plugin>
    <groupId>org.mock-server</groupId>
    <artifactId>mockserver-maven-plugin</artifactId>
    <version>9.2.0</version>
    <configuration>
        <serverPort>1080</serverPort>
    </configuration>
    <executions>
        <execution>
            <id>process-test-classes</id>
            <phase>process-test-classes</phase>
            <goals>
                <goal>start</goal>
            </goals>
        </execution>
        <execution>
            <id>verify</id>
            <phase>verify</phase>
            <goals>
                <goal>stop</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

## Goals

| Goal | Description |
|------|-------------|
| `start` | Start MockServer, blocking until it is ready |
| `stop` | Stop a running MockServer instance |
| `run` | Run MockServer and block (useful for manual testing) |
| `runForked` | Start MockServer in a forked JVM (does not require a Maven project) |
| `stopForked` | Stop a forked MockServer instance |

## Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| `serverPort` | *(none)* | HTTP/HTTPS port for MockServer (e.g. `1080`) |
| `proxyRemotePort` | *(none)* | Enables port forwarding mode |
| `proxyRemoteHost` | `localhost` | Host for port forwarding (requires `proxyRemotePort`) |
| `logLevel` | `INFO` | MockServer log level |
| `pipeLogToConsole` | `false` | Write MockServer logs to Maven console |
| `initializationClass` | *(none)* | Expectation initializer class |
| `initializationJson` | *(none)* | Path to JSON expectation initialization file |
| `timeout` | *(none)* | How long `run` goal blocks (milliseconds) |
| `skip` | `false` | Skip plugin execution |

## Community, Issues & Contributing

See the [main MockServer README](../README.md) for community links, how to report issues, and contribution guidelines.

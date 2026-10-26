# mockserver-node 

> Node module and grunt plugin to start and stop [MockServer](https://mock-server.com/) and [MockServer](https://mock-server.com/) proxy

[![Build status](https://badge.buildkite.com/84d4f1ca00ee6639c1825ea31f0dcd50bd73088571813a219b.svg?style=square&theme=slack)](https://buildkite.com/mockserver/mockserver-node)

[![NPM](https://nodei.co/npm/mockserver-node.png?downloads=true&stars=true)](https://nodei.co/npm/mockserver-node/) 

# Community

* Roadmap:&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;<a href="https://github.com/orgs/mock-server/projects/1"><img height="20px" src="https://mock-server.com/images/GitHub_Logo-md.png" alt="GitHub Project"></a>
* Feature Requests:&nbsp;&nbsp;&nbsp;<a href="https://github.com/mock-server/mockserver-monorepo/issues"><img height="20px" src="https://mock-server.com/images/GitHub_Logo-md.png" alt="Github Issues"></a>
* Issues / Bugs:&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;<a href="https://github.com/mock-server/mockserver-monorepo/issues"><img height="20px" src="https://mock-server.com/images/GitHub_Logo-md.png" alt="Github Issues"></a>
* Discussions:&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;<a href="https://github.com/mock-server/mockserver-monorepo/discussions"><img height="20px" src="https://mock-server.com/images/GitHub_Logo-md.png" alt="GitHub Discussions"></a>

## Getting Started

This node module can be used to start and stop [MockServer](https://mock-server.com/) and the [MockServer](https://mock-server.com/) proxy as a node module or as a Grunt plugin.  More information about the [MockServer](https://mock-server.com/) can be found at [mock-server.com](https://mock-server.com/). 

You may install this plugin / node module with the following command:

```shell
npm install mockserver-node --save-dev
```

## Run without Java or Docker (binary bundle)

The `mockserver` command downloads a self-contained MockServer bundle (a trimmed
Java runtime + the server + a launcher) for your platform on first use and runs
it — **no Java installation and no Docker required**:

```shell
npx -p mockserver-node mockserver run -p 1080
npx -p mockserver-node mockserver --openapi ./petstore.yaml -p 1080
npx -p mockserver-node mockserver --help
```

The bundle is fetched from the MockServer GitHub Release, verified against its
published SHA-256, and cached per-user. Environment overrides:

| Variable | Purpose |
|---|---|
| `MOCKSERVER_BINARY_BASE_URL` | mirror host for the release assets (corporate / air-gapped) |
| `MOCKSERVER_BINARY_CACHE` | cache directory (default: per-OS user cache) |
| `MOCKSERVER_SKIP_BINARY_DOWNLOAD` | fail instead of downloading (use with a pre-seeded cache) |
| `NODE_EXTRA_CA_CERTS` | extra CA bundle for TLS-inspecting proxies (honoured by Node) |
| `HTTPS_PROXY` / `HTTP_PROXY` | used when an https/http proxy agent module is installed (otherwise set a mirror) |

> The SHA-256 is co-hosted with the archive, so it verifies download **integrity** (no corruption/truncation) but not **provenance**; a future release will add cosign signatures for provenance.

The bundle exposes the full MockServer CLI (`run`, `proxy`, `openapi`, `version`,
`help`). This is the reference implementation of the on-demand-binary pattern for
the MockServer client libraries.

## Node Module

To start or stop the MockServer from any Node.js code you need to import this module using `require('mockserver-node')` as follows:

```js
var mockserver = require('mockserver-node');
```

The package includes TypeScript typings, which use Node's own types and so need `@types/node` in the project. It is a CommonJS module, so in TypeScript import it with `import mockserver = require('mockserver-node')`, or with `import mockserver from 'mockserver-node'` when `esModuleInterop` is on. The option types are `mockserver.StartServerOptions` and `mockserver.StopServerOptions`.

From an ES module (`.mjs`, `.mts`, or a package with `"type": "module"`) named imports work, `import { start_mockserver, stop_mockserver } from 'mockserver-node'`, as do `import * as` and the default import, `import mockserver from 'mockserver-node'`. A module other than the package itself can be imported with or without its extension: `import { downloadJar } from 'mockserver-node/downloadJar'`.

The package's `exports` map names every path that can be imported or required: the package itself, `index`, `downloadJar` and `downloadBinary`, each with and without `.js`, and `package.json`. Any other path inside the package fails with `ERR_PACKAGE_PATH_NOT_EXPORTED`. The `mockserver` command and the Grunt tasks (`grunt.loadNpmTasks('mockserver-node')`) are found by their files, not through the map, and work as before.

Then you can use either the `start_mockserver` or `stop_mockserver` functions as follows:

```js
mockserver.start_mockserver({
                serverPort: 1080,
                trace: true
            });

// do something

mockserver.stop_mockserver({
                serverPort: 1080
            });
```

The MockServer uses port unification to support HTTP, HTTPS, SOCKS, HTTP CONNECT, Port Forwarding Proxying on the same port. A client can then connect to the single port with both HTTP and HTTPS as the socket will automatically detected SSL traffic and decrypt it when required.

### Requirements

`start_mockserver` runs MockServer with the `java` command, so Java 17 or later must be installed and its `bin` directory must be on the `PATH` of the Node.js process.  `JAVA_HOME` is not used, and there is no option for the location of `java`.  The `mockserver` command described above needs no Java.

### When a start fails

`start_mockserver` returns a promise.  It is rejected with an `Error`, and the same message is printed to stderr, when:

* there is no `java` on the `PATH` (`error.code` is `ENOENT`), or the one found cannot be run (`EACCES`): the message says where `java` was looked for
* `java` exits with a failing status, or is ended by a signal, before MockServer is ready, for example when `jvmOptions` holds an option it does not accept: the start fails at once and the message ends with the last lines the process printed (`error.exitCode` and `error.signal` are set)
* MockServer does not become ready in the time `startupRetries` allows

A failed start never ends the calling process, and leaves it free to carry on or to exit (unless a JVM was left waiting for a debugger, see `javaDebugPort`).  Handle the rejection to fail your own script, and call `stop_mockserver` afterwards if you wish: it resolves when nothing is running.

`stop_mockserver` rejects with the status code when MockServer answers the stop request with a status other than 2xx or 404, for example `401` when control-plane authentication is on or `500` when the stop fails inside MockServer. A MockServer this process launched is stopped whatever the answer.

```js
mockserver.start_mockserver({serverPort: 1080}).then(function () {
    // MockServer is ready
}, function (error) {
    process.exitCode = 1;
});
```

## Grunt Plugin

If you haven't used [Grunt](http://gruntjs.com/) before, be sure to check out the [Getting Started](http://gruntjs.com/getting-started) guide, as it explains how to create a [Gruntfile](http://gruntjs.com/sample-gruntfile) as well as install and use Grunt plugins.

In your project's Gruntfile, add a section named `start_mockserver` and `stop_mockserver` to the data object passed into `grunt.initConfig()`.

The following example will result in a both a MockServer and a MockServer Proxy being started on ports `1080` and `1090`.

```js
grunt.initConfig({
    start_mockserver: {
        options: {
            serverPort: 1080,
            trace: true
        }
    },
    stop_mockserver: {
        options: {
            serverPort: 1080
        }
    }
});

grunt.loadNpmTasks('mockserver-node');
```

## Request Log

**Note:** The request log will only be captured in MockServer if the log level is `INFO` (or more verbose, i.e. `DEBUG` or `TRACE`) therefore to capture the request log and use the `/retrieve` endpoint ensure either the option `trace: true` or the command line switch `--verbose` is set.

### Options

#### options.serverPort
Type: `Integer`
Default value: `undefined`

The HTTP, HTTPS, SOCKS and HTTP CONNECT port(s) for both mocking and proxying requests.  Port unification is used to support all protocols for proxying and mocking on the same port(s). Supports comma separated list for binding to multiple ports.

#### options.proxyRemotePort
Type: `Integer`
Default value: `undefined` 

Optionally enables port forwarding mode. When specified all requests received will be forwarded to the specified port, unless they match an expectation.

#### options.proxyRemoteHost
Type: `String`
Default value: `undefined`  

Specified the host to forward all proxy requests to when port forwarding mode has been enabled using the `proxyRemotePort` option.  This setting is ignored unless `proxyRemotePort` has been specified. If no value is provided for `proxyRemoteHost` when `proxyRemotePort` has been specified, `proxyRemoteHost` will default to `"localhost"`.

#### options.artifactoryHost
Type: `String` 
Default value: `oss.sonatype.org`

This value specifies the name of the artifact repository host.

#### options.artifactoryPath
Type: `String` 
Default value: `/content/repositories/releases/org/mock-server/mockserver-netty/`

This value specifies the path to the artifactory leading to the mockserver-netty jar with dependencies.

#### options.mockServerVersion
Type: `String` 
Default value: `9.2.0`

This value specifies the artifact version of MockServer to download.

**Note:** It is also possible to specify a SNAPSHOT version to get the latest unreleased changes.

#### options.jarPath
Type: `String`
Default value: `undefined`

Absolute or relative path to a pre-provisioned `mockserver-netty` jar-with-dependencies. When set, this exact jar is launched and **no download is attempted** — the `mockServerVersion`, `artifactoryHost` and `artifactoryPath` options are ignored. A path that does not point at an existing file is a hard error: MockServer will **not** silently fall back to downloading a released jar, so a missing or mis-built jar fails loudly rather than quietly running a different version.

Use this for air-gapped or corporate environments where the jar is provisioned separately, or to launch a locally-built jar for development.

The environment variable `MOCKSERVER_JAR_PATH` sets the same behaviour; the `jarPath` option takes precedence over it when both are given.

```js
start_mockserver({
    serverPort: 1080,
    jarPath: "./mockserver-netty-7.5.1-SNAPSHOT-jar-with-dependencies.jar"
});
```

#### options.verbose
Type: `Boolean`
Default value: `false`

This value indicates whether the MockServer logs should be written to the console.  In addition to logging additional output from the grunt task this options also sets the logging level of the MockServer to [**DEBUG**](https://www.mock-server.com/mock_server/debugging_issues.html). At **DEBUG** all matcher results, including when specific matchers fail (such as HeaderMatcher) are written to the log. The MockServer logs are written to ```mockserver.log``` in the current directory.  

**Note:** It is also possible to use the ```--verbose``` command line switch to enabled verbose level logging from the command line.

#### options.trace
Type: `Boolean`
Default value: `false`

This value sets the logging level of the MockServer to [**TRACE**](https://www.mock-server.com/mock_server/debugging_issues.html). At **TRACE** level (in addition to **INFO** level information) all matcher results, including when specific matchers fail (such as HeaderMatcher) are written to the log. The MockServer logs are written to ```mockserver.log``` in the current directory. 

#### options.javaDebugPort
Type: `Integer`
Default value: `undefined`

This value indicates whether Java debugging should be enabled and if so which port the debugger should listen on.  When this options is provided the following additional option is passed to the JVM:
 
```bash
"-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=" + javaDebugPort
```  

Note that `suspend=y` is used so the MockServer will pause until the debugger is attached.  The grunt task will wait 50 seconds for the debugger to be attached before it exits with a failure status.  The paused JVM is left running when that happens, so a debugger can still be attached to it; stop it yourself when you are done.
  
#### options.runForked
Type: `Boolean`
Default value: `false`

If the calling process meets an uncaught exception, the MockServer it launched is ended so that it is not left running once the process has gone.  The exception itself is left alone: Node.js reports it and exits with a failing status, or your own `uncaughtException` handler deals with it.  The server is ended even when your own handler, or a test runner, deals with the exception and the process carries on. Only the `java` process the launcher started is signalled: if the `java` on your `PATH` is a wrapper script that does not `exec` the JVM, the JVM is left running.  Set this option to `true` to leave MockServer running instead.

#### options.jvmOptions
Type: `String`
Default value: `undefined`

This value allows any system properties to be passed to the JVM that runs MockServer, for example:
 
```js
start_mockserver: {
    options: {
        serverPort: 1080,
        jvmOptions: "-Dmockserver.enableCORSForAllResponses=true"
    }
}
```  

#### options.startupRetries
Type: `Integer`
Default value if javaDebugPort is not set: `110`
Default value if javaDebugPort is set: `500`

This value indicates the how many times we will call the check to confirm if the mock server started up correctly. It will default to 110 which will take about 11 seconds to complete, this is normally long enough for the server to startup. The server can take longer to start up if Java debugging is enabled so this will default to 500. The default will, in some cases, need to be overridden as the JVM may take longer to start up on some architectures,  e.g. Mac seems to take a little longer.

The checks are 100 milliseconds apart, and each one waits up to 2 seconds for an answer.  A check that gets no answer in that time uses up 20 further retries, so a server that accepts connections and never answers makes the start give up after about `startupRetries` × 100 milliseconds plus 2 seconds, much as one that refuses connections does.  When the start gives up it fails with a message saying so and stops the JVM it launched, unless `javaDebugPort` is set.

## Contributing
In lieu of a formal styleguide, take care to maintain the existing coding style. Add unit tests for any new or changed functionality. Lint and test your code using [Grunt](http://gruntjs.com/).

## Changelog

All notable and significant changes are detailed in the [MockServer changelog](https://github.com/mock-server/mockserver-monorepo/blob/master/changelog.md) 

---

Task submitted by [James D Bloom](https://blog.jamesdbloom.com)

## AI Assistant Integration

MockServer includes a built-in [MCP](https://modelcontextprotocol.io) (Model Context Protocol) server that enables AI coding assistants to create expectations, verify requests, and debug HTTP traffic programmatically.

- **MCP Endpoint:** `http://localhost:1080/mockserver/mcp`
- **AI Documentation:** [llms.txt](https://www.mock-server.com/llms.txt)
- **Setup Guide:** [AI Integration](https://www.mock-server.com/mock_server/ai_mcp_setup.html)

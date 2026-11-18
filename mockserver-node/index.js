/*
 * mockserver
 * http://mock-server.com
 *
 * Copyright (c) 2014 James Bloom
 * Licensed under the Apache License, Version 2.0
 */

(function () {

    var mockServer;
    // Bounded ring buffer of the launched MockServer's stdout+stderr, and its exit status once it dies.
    // Non-verbose starts used to route MockServer stdout to 'ignore', discarding the exact
    // "SEVERE ... certificate does not verify with supplied key" line that was needed to diagnose a
    // dynamic-CA generation race. We now always capture the output (bounded, so a long-running server
    // cannot grow it without limit) so a failed startup / readiness check can surface the tail.
    var mockServerOutput = '';
    var mockServerExit;
    // Measured in JS string length (UTF-16 code units), not bytes: String.slice works in code units, and
    // this is only a diagnostic-tail bound, not an exact byte budget. The cap is approximate at the edges
    // (a multibyte UTF-8 sequence straddling a chunk boundary can render as one replacement character in
    // the tail) but the buffer is bounded either way, which is all this needs to guarantee.
    var MAX_CAPTURED_OUTPUT_CHARS = 65536;

    function appendCapturedOutput(chunk) {
        mockServerOutput += chunk.toString();
        if (mockServerOutput.length > MAX_CAPTURED_OUTPUT_CHARS) {
            mockServerOutput = mockServerOutput.slice(mockServerOutput.length - MAX_CAPTURED_OUTPUT_CHARS);
        }
    }

    function capturedOutputTail(maxLines) {
        var lines = mockServerOutput.split(/\r?\n/).filter(function (line) {
            return line.length > 0;
        });
        if (maxLines && lines.length > maxLines) {
            lines = lines.slice(lines.length - maxLines);
        }
        return lines.join('\n');
    }

    // KNOWN LIMITATION: logLevel, artifactoryHost, artifactoryPath and
    // mockServerVersion below are all module-level, and start_mockserver
    // overwrites each of them permanently from its options rather than treating
    // them as per-call values. Two concurrent starts in one process therefore
    // share one version, one repository and one log level - the second can
    // launch the first's jar - and any later call that omits an option silently
    // inherits the last explicit one. This is the in-process form of the
    // cross-process race that resolveJarPath and downloadJar fix; fixing it
    // means threading all four through as locals, which changes behaviour
    // callers may be relying on today.
    var logLevel;
    var artifactoryHost = 'repo1.maven.org';
    var artifactoryPath = '/maven2/org/mock-server/mockserver-netty/';
    var mockServerVersion = require('./package.json').version;
    var Q = require('q');
    var http = require('http');
    var fs = require('fs');
    var path = require('path');

    /**
     * Resolve the MockServer jar for a specific version to an absolute path.
     *
     * The jar is looked for in a fixed set of known directories rather than with
     * a recursive wildcard. A recursive glob matches any copy anywhere beneath
     * the working directory, so it can pick a copy belonging to something else
     * entirely, and the path it returns is not guaranteed to still exist by the
     * time java is asked to open it. Every candidate below is checked for
     * existence, and a missing jar is reported rather than handed to java as an
     * undefined argument.
     *
     * Candidates, in priority order:
     *   1. this package's own directory - where downloadJar stores the jar
     *   2. node_modules/mockserver-node below the working directory - an install
     *      that this module was not itself loaded from
     *   3. the working directory - where releases before 7.4.1 wrote the jar
     *
     * @param {string} jarName file name of the jar for the version being launched
     * @returns {string} absolute path to an existing jar
     * @throws {Error} if no candidate directory holds the jar
     */
    function resolveJarPath(jarName) {
      var candidates = [
        path.join(__dirname, jarName),
        path.join(process.cwd(), 'node_modules', 'mockserver-node', jarName),
        path.join(process.cwd(), jarName)
      ];
      for (var i = 0; i < candidates.length; i++) {
        if (fs.existsSync(candidates[i])) {
          return candidates[i];
        }
      }
      throw new Error('Unable to find ' + jarName + ', looked in: ' + candidates.join(', '));
    }

    /**
     * Resolve an explicitly-configured jar (the jarPath option or the
     * MOCKSERVER_JAR_PATH environment variable) to an absolute path, failing
     * loudly if nothing usable is there.
     *
     * Unlike resolveJarPath / downloadJar, this deliberately does NOT fall back
     * to a downloaded release: a caller that named a specific jar wants THAT jar,
     * and silently downloading a published version instead would mask a
     * missing/mis-built artifact - in CI, the very failure this exists to catch
     * (a build step that should have produced the jar but did not). A missing
     * path, or one that is not a regular file, is therefore a hard error.
     *
     * @param {string} configuredPath the path as configured
     * @param {string} source human-readable origin, used in the log/error message
     * @param {boolean} log whether to log the resolved path
     * @returns {string} absolute path to the existing jar file
     * @throws {Error} if the path does not resolve to an existing regular file
     */
    function resolveExplicitJarPath(configuredPath, source, log) {
      var absolute = path.resolve(configuredPath);
      var stats;
      try {
        stats = fs.statSync(absolute);
      } catch (missing) {
        throw new Error('The MockServer jar configured via ' + source + ' ("' + configuredPath +
          '") does not exist at ' + absolute + ' - refusing to fall back to downloading a release');
      }
      if (!stats.isFile()) {
        throw new Error('The MockServer jar configured via ' + source + ' ("' + configuredPath +
          '") is not a regular file at ' + absolute + ' - refusing to fall back to downloading a release');
      }
      if (log) {
        console.log('Using MockServer jar from ' + source + ': ' + absolute);
      }
      return absolute;
    }

    function defer() {
      var promise = (global.protractor && protractor.promise.USE_PROMISE_MANAGER !== false)
        ? protractor.promise
        : Q;
      var deferred = promise.defer();
  
      if (deferred.fulfill && !deferred.resolve) {
        deferred.resolve = deferred.fulfill;
      }
      return deferred;
    }
  
    // The launcher's own requests each use their own connection: a pooled one is shared with the caller's
    // requests, and bytes a caller wrote unframed on it would be read as the start of the launcher's request.
    function controlRequest(request, callback) {
      request.agent = new http.Agent();
      return http.request(request, callback);
    }

    var POLL_INTERVAL_MILLIS = 100;
    var POLL_REQUEST_TIMEOUT_MILLIS = 2000;

    function secondsSince(millis) {
      return ((Date.now() - millis) / 1000).toFixed(1);
    }

    // Sends one poll request and calls done(error, response) exactly once. A poll not fully answered within
    // the timeout is ended with error.timedOut set. The timeout runs by the clock, not on an idle connection,
    // so an answer that trickles in cannot outlast it. Returns a function that ends the poll without calling done.
    function pollOnce(request, done) {
      var settled = false;
      var timer;

      function settle(error, response) {
        if (!settled) {
          settled = true;
          clearTimeout(timer);
          done(error, response);
        }
      }

      var req = controlRequest(request);

      timer = setTimeout(function () {
        var error = new Error('no answer to "' + request.method + ' ' + request.path + '" within ' +
          (POLL_REQUEST_TIMEOUT_MILLIS / 1000) + ' seconds');
        error.code = 'ETIMEDOUT';
        error.timedOut = true;
        settle(error);
        req.destroy();
      }, POLL_REQUEST_TIMEOUT_MILLIS);

      req.once('response', function (response) {
        var body = '';

        response.on('data', function (chunk) {
          body += chunk;
        });

        response.on('error', settle);

        response.on('end', function () {
          settle(undefined, {
            statusCode: response.statusCode,
            body: body
          });
        });
      });

      req.on('error', settle);

      req.end();

      return function abandon() {
        settled = true;
        clearTimeout(timer);
        req.destroy();
      };
    }

    // Polls until decided(error, response) is true or the retries run out, then calls finished(true, response)
    // or finished(false, error) with the last poll's error if it had one. A poll that timed out took the time
    // of many retries and is charged for them, so a server that accepts and never answers is given up on about
    // as soon as one that refuses. Only the count limits other polls: a slow host must not shorten the wait.
    // Returns a function that ends the polling without calling finished.
    function pollUntil(request, retries, waitingMessage, decided, finished) {
      var abandonPoll;
      var nextAttempt;

      function attempt(retriesLeft) {
        abandonPoll = pollOnce(request, function (error, response) {
          if (decided(error, response)) {
            finished(true, response);
            return;
          }
          if (error && error.timedOut) {
            retriesLeft -= POLL_REQUEST_TIMEOUT_MILLIS / POLL_INTERVAL_MILLIS;
          }
          if (retriesLeft > 0) {
            nextAttempt = setTimeout(function () {
              if (waitingMessage) {
                console.log(waitingMessage + " retries remaining: " + retriesLeft);
              }
              attempt(retriesLeft - 1);
            }, POLL_INTERVAL_MILLIS);
          } else {
            finished(false, error);
          }
        });
      }

      attempt(retries);

      return function cancel() {
        clearTimeout(nextAttempt);
        abandonPoll();
      };
    }

    // ready as soon as any answer arrives: calls finished(true, response), or finished(false, error) with the
    // last poll's error; returns a function that ends the check without calling finished
    function checkStarted(request, retries, verbose, finished) {
      return pollUntil(request, retries, verbose && "waiting for MockServer to start", function (error) {
        return !error;
      }, finished);
    }

    // stopped once a connection fails; a server that still accepts one, answering or not, has not stopped
    function checkStopped(request, retries, verbose) {
      var deferred = defer();
      var since = Date.now();

      pollUntil(request, retries, verbose && "waiting for MockServer to stop", function (error) {
        return !!error && !error.timedOut;
      }, function (stopped) {
        if (stopped) {
          deferred.resolve();
        } else {
          if (verbose) {
            console.log("MockServer failed to stop");
          }
          deferred.reject(new Error('MockServer is still accepting connections on port ' + request.port + ' ' +
            secondsSince(since) + ' seconds after it was asked to stop'));
        }
      });

      return deferred.promise;
    }

    // The rejection for a start whose readiness polls ran out. Ends the launched process, unless it was
    // started suspended for a debugger (javaDebugPort): that one is waiting to be attached to by hand.
    function failedStart(launched, lastError, port, since, javaDebugPort, verbose) {
      if (verbose) {
        console.log("MockServer failed to start");
      }
      var message = 'MockServer did not become ready on port ' + port + ' within ' +
        secondsSince(since) + ' seconds (' + (lastError.message || lastError.code) + '); its java process ';
      if (launched.exitCode !== null || launched.signalCode !== null) {
        message += 'had already exited (code=' + launched.exitCode + ', signal=' + launched.signalCode + ')';
      } else if (javaDebugPort) {
        message += '(pid ' + launched.pid + ') was left running because "javaDebugPort" is set: it is waiting ' +
          'for a debugger to attach on port ' + javaDebugPort;
      } else {
        launched.kill();
        message += '(pid ' + launched.pid + ') was stopped';
      }
      // also printed, with the server's last output: a start that is not verbose has shown none of it
      console.error(message);
      var tail = capturedOutputTail(30);
      if (tail) {
        console.error("last MockServer output:\n" + tail);
      }
      var error = new Error(message);
      error.code = lastError.code;
      error.cause = lastError;
      return error;
    }

    var COULD_NOT_START = 'MockServer could not be started: ';

    function javaLookup() {
      var javaHome = process.env.JAVA_HOME;
      return 'The launcher runs "java" from the PATH of this process (PATH=' + (process.env.PATH || '') +
        '); it does not use JAVA_HOME (' + (javaHome ? 'set to ' + javaHome : 'not set') +
        ') and has no option for the location of java.';
    }

    // The rejection for a start whose java process could not be launched at all.
    function failedLaunch(spawnError) {
      var message;
      if (spawnError.code === 'ENOENT') {
        message = COULD_NOT_START + 'no "java" command was found. ' + javaLookup() + ' Install Java 17 or later ' +
          'and add its bin directory to PATH, or use the "mockserver" command of this package, which needs no Java.';
      } else if (spawnError.code === 'EACCES') {
        message = COULD_NOT_START + 'permission was denied to run a "java" found on the PATH of this process (' +
          spawnError.message + '); check that it is an executable file. ' + javaLookup();
      } else {
        message = COULD_NOT_START + 'running "java" failed (' + spawnError.message + '). ' + javaLookup();
      }
      var error = new Error(message);
      error.code = spawnError.code;
      error.cause = spawnError;
      return error;
    }

    // The rejection for a start whose java process ended in failure before MockServer answered.
    function exitedBeforeReady(code, signal, port) {
      var message = COULD_NOT_START + 'its java process ' +
        (signal ? 'was ended by signal ' + signal : 'exited with status ' + code) +
        ' before MockServer became ready on port ' + port;
      var tail = capturedOutputTail(30);
      if (tail) {
        message += '; its last output:\n' + tail;
      }
      var error = new Error(message);
      error.exitCode = code;
      error.signal = signal;
      return error;
    }

    // printed as well as rejected with: a caller that does not handle the rejection is told of nothing else
    function reported(error) {
      console.error(error.message);
      return error;
    }

    // The launched processes to end if the calling process meets an uncaught exception: every one started
    // without runForked. 'uncaughtExceptionMonitor' leaves the exception, and the status the process exits
    // with, to the caller and to Node.
    var endedWithCaller = new Set();
    var watchingForUncaughtException = false;

    function endWithCaller(launched) {
      endedWithCaller.add(launched);
      launched.once('exit', function () {
        endedWithCaller.delete(launched);
      });
      if (!watchingForUncaughtException) {
        watchingForUncaughtException = true;
        process.on('uncaughtExceptionMonitor', function () {
          endedWithCaller.forEach(function (running) {
            running.kill();
          });
        });
      }
    }

    var STOP_REQUEST_TIMEOUT_MILLIS = 10000;

    function sendRequest(request) {
      var deferred = defer();
  
      var callback = function (response) {
        var body = '';
  
        if (response.statusCode < 200 || response.statusCode >= 300) {
          deferred.reject(response.statusCode);
        }
  
        response.on('data', function (chunk) {
          body += chunk;
        });
  
        response.on('end', function () {
          deferred.resolve({
            statusCode: response.statusCode,
            headers: response.headers,
            body: body
          });
        });
      };
  
      var req = controlRequest(request, callback);
  
      req.once('error', function (err) {
        deferred.reject(err);
      });

      req.setTimeout(STOP_REQUEST_TIMEOUT_MILLIS, function () {
        req.destroy(new Error('MockServer did not answer "' + request.method + ' ' + request.path + '" within ' +
          (STOP_REQUEST_TIMEOUT_MILLIS / 1000) + ' seconds'));
      });
  
      req.end();
  
      return deferred.promise;
    }
  
    function stop_mockserver(options) {
      var port;
      var deferred = defer();
  
      if (options && options.serverPort) {
        if (options.serverPort) {
          port = port || options.serverPort;
        }
        if (options.verbose) {
          console.log('Using port \'' + port + '\' to stop MockServer and MockServer Proxy');
        }
        sendRequest({
          method: 'PUT',
          host: "localhost",
          path: "/stop",
          port: port
        }).then(
          function () {
            if (mockServer) {
              mockServer.kill();
            }
            checkStopped({
              method: 'PUT',
              host: "localhost",
              path: "/reset",
              port: port
            }, 100, options && options.verbose).then(function () { // wait for 10 seconds
              deferred.resolve();
            }, function (error) {
              deferred.reject(error);
            });
          },
          function (err) {
            // however the stop request failed, the launched process must not outlive it: left running, the
            // child process keeps the calling Node process from exiting
            try {
              if (mockServer) {
                mockServer.kill();
              }
            } catch (e) {
            }
            if ((err && err.code === "ECONNREFUSED") || err === 404) {
              deferred.resolve();
            } else {
              deferred.reject(err);
            }
          }
        );
  
      } else {
        deferred.reject("Please specify \"serverPort\", for example: \"stop_mockserver({ serverPort: 1080 })\"");
      }
      return deferred.promise;
    }
  
    function start_mockserver(options) {
      var port;
      var deferred = defer();
  
      if (!(options && options.serverPort)) {
        deferred.reject('Please specify "serverPort", for example: "start_mockserver({ serverPort: 1080 })"');
        return deferred.promise;
      }
  
      if ((options.systemProperties)) {
        deferred.reject('The option "systemProperties" was renamed to "jvmOptions" in 5.4.1. Please migrate to the new option name');
        return deferred.promise;
      }
  
      if (options.artifactoryHost) {
        artifactoryHost = options.artifactoryHost;
      }
  
      if (options.artifactoryPath) {
        artifactoryPath = options.artifactoryPath;
      }
  
      if (options.mockServerVersion) {
        mockServerVersion = options.mockServerVersion;
      }
  
      if (options.trace) {
        logLevel = 'TRACE';
        options.verbose = true;
      } else if (options.verbose) {
        logLevel = 'DEBUG';
      }
  
      var startupRetries = options.startupRetries || (options.javaDebugPort ? 500 : 110);
      var settled = false;
      var stopCheckingStarted;
      var launched;

      // true for the first outcome of this start only, and ends the readiness check: a start settles once
      function firstOutcome() {
        if (settled) {
          return false;
        }
        settled = true;
        if (stopCheckingStarted) {
          stopCheckingStarted();
        }
        return true;
      }

      // An explicitly-provided jar (the jarPath option or the MOCKSERVER_JAR_PATH
      // environment variable) is used as-is and short-circuits the download
      // entirely. This is how an air-gapped / corporate user runs a jar they
      // provisioned themselves, and how CI launches a jar freshly built from the
      // tree instead of a published release. A configured path that is missing is
      // a hard error (resolveExplicitJarPath) - never a silent fall-back to a
      // download, which would hide the intended jar being absent.
      var explicitJarPath = options.jarPath || process.env.MOCKSERVER_JAR_PATH;
      var jarReady;
      if (explicitJarPath) {
        jarReady = Q.try(function () {
          return resolveExplicitJarPath(
            explicitJarPath,
            options.jarPath ? 'jarPath option' : 'MOCKSERVER_JAR_PATH',
            logLevel || options.verbose);
        });
      } else {
        // double check the jar has already been downloaded, then resolve the jar
        // for the specific version being launched - a wildcard version would match
        // every downloaded version and push an array, which spawn joins with a
        // comma into an invalid "a.jar,b.jar" path.
        jarReady = require('./downloadJar').downloadJar(mockServerVersion, artifactoryHost, artifactoryPath, logLevel).then(function () {
          return resolveJarPath('mockserver-netty-' + mockServerVersion + '-jar-with-dependencies.jar');
        });
      }

      jarReady.then(function (jarFile) {

        var spawn = require('child_process').spawn;
        // -XX:-UseBASE64Intrinsics works around CVE-2026-114938 in the user's own JDK; it comes before
        // jvmOptions so a user can still deliberately re-enable the intrinsic there.
        var commandLineOptions = ['-Dfile.encoding=UTF-8', '-XX:-UseBASE64Intrinsics'];
        if (options.initializationJsonPath) {
          commandLineOptions.push('-Dmockserver.initializationJsonPath=' + options.initializationJsonPath);
        }
        if (options.javaDebugPort) {
          commandLineOptions.push('-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=' + options.javaDebugPort);
        }
        
        if (options.jvmOptions) {
          if (Array.isArray(options.jvmOptions)) {
            commandLineOptions.push(...options.jvmOptions);
          } else {
            commandLineOptions.push(...options.jvmOptions.split(' '));
          }
        }
        commandLineOptions.push('-jar');
        commandLineOptions.push(jarFile);
        if (options.serverPort) {
          commandLineOptions.push("-serverPort");
          commandLineOptions.push(options.serverPort);
          port = port || options.serverPort;
        }
        if (options.proxyRemotePort) {
          commandLineOptions.push("-proxyRemotePort");
          commandLineOptions.push(options.proxyRemotePort);
        }
        if (options.proxyRemoteHost) {
          commandLineOptions.push("-proxyRemoteHost");
          commandLineOptions.push(options.proxyRemoteHost);
        }
        if (logLevel) {
          commandLineOptions.push("-logLevel");
          commandLineOptions.push(logLevel);
        }
        if (options.verbose) {
          console.log('Running \'java ' + commandLineOptions.join(' ') + '\'');
        }
        // Always pipe stdout+stderr so we can capture them into the bounded ring buffer. Preserve the
        // previous surfacing behaviour: stdout is echoed to the parent only when verbose, stderr is
        // always echoed (as it was when routed directly to process.stderr).
        mockServerOutput = '';
        mockServerExit = undefined;
        launched = spawn('java', commandLineOptions, {
          stdio: ['ignore', 'pipe', 'pipe']
        });
        mockServer = launched;
        // Without a listener a failure to launch java, or to signal it later, is an uncaught exception in
        // the calling process. Attached before anything else can throw: the failure is reported a tick later.
        launched.on('error', function (processError) {
          if (firstOutcome()) {
            deferred.reject(reported(failedLaunch(processError)));
          } else {
            console.error('MockServer java process: ' + processError.message);
          }
        });
        // a launch that fails for want of file descriptors has no output streams
        if (launched.stdout) {
          launched.stdout.on('data', function (chunk) {
            appendCapturedOutput(chunk);
            if (options.verbose) {
              process.stdout.write(chunk);
            }
          });
        }
        if (launched.stderr) {
          launched.stderr.on('data', function (chunk) {
            appendCapturedOutput(chunk);
            process.stderr.write(chunk);
          });
        }
        launched.once('exit', function (code, signal) {
          mockServerExit = { code: code, signal: signal };
        });
        // 'close' follows 'exit' once all of the output has been read. A process that exits with status 0
        // is left to the readiness check, as one that hands over to a server it started would be.
        launched.once('close', function (code, signal) {
          if (code !== 0 && firstOutcome()) {
            deferred.reject(reported(exitedBeforeReady(code, signal, port)));
          }
        });
        if (!options.runForked && launched.pid) {
          endWithCaller(launched);
        }

        var since = Date.now();
        stopCheckingStarted = checkStarted({
          method: 'PUT',
          host: "localhost",
          path: "/mockserver/retrieve?type=ACTIVE_EXPECTATIONS",
          port: port
        }, startupRetries, options.verbose, function (ready, result) {
          if (!firstOutcome()) {
            return;
          }
          if (ready) {
            deferred.resolve(result);
          } else {
            deferred.reject(failedStart(launched, result, port, since, options.javaDebugPort, options.verbose));
          }
        });

      }).then(undefined, function (error) {
        if (firstOutcome()) {
          // Whatever was thrown after the launch, the launched process must not outlive the failed start.
          // Only one with a pid: until the 'error' of a failed launch is emitted, a signal sent to that
          // child goes to the process group of the caller.
          if (launched && launched.pid) {
            launched.kill();
          }
          deferred.reject(error);
        }
      });
  
      return deferred.promise;
    }
  
    // Diagnostics for readiness probes (e.g. test/waitForTlsReady.js): the launched child process, its
    // exit status once it has died (undefined while running), and the captured stdout+stderr tail.
    function getMockServerProcess() {
      return mockServer;
    }

    function getMockServerExit() {
      return mockServerExit;
    }

    function getMockServerOutput(maxLines) {
      return capturedOutputTail(maxLines);
    }

    // values are identifiers only: Node reads an ES module importer's names from this literal and
    // stops at the first value that is not one
    module.exports = {
      start_mockserver: start_mockserver,
      stop_mockserver: stop_mockserver,
      getMockServerProcess: getMockServerProcess,
      getMockServerExit: getMockServerExit,
      getMockServerOutput: getMockServerOutput
    };
  })();
  

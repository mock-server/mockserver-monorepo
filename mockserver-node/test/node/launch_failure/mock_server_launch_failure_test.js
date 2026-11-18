/*
 * mockserver
 * http://mock-server.com
 *
 * Copyright (c) 2014 James Bloom
 * Licensed under the Apache License, Version 2.0
 */

'use strict';

var test = require('node:test');
var assert = require('node:assert');
var fs = require('fs');
var http = require('http');
var net = require('net');
var os = require('os');
var path = require('path');
var spawn = require('child_process').spawn;

// Each test runs calling_script.js in a process of its own, as a script that calls the launcher would
// run, with a PATH that holds only that test's stand-in for java (or none). What the launcher does to
// the process that calls it - its exit status, whether it runs on, whether it can end - shows only there.
var CALLING_SCRIPT = path.join(__dirname, 'calling_script.js');
// a start with the default retries polls for 11 seconds: a calling script still running after this many
// milliseconds was kept alive by the launcher
var CALLER_ENDS_WITHIN_MILLIS = 8000;

var EXITS_WITH_USAGE_ERROR = [
    '#!/bin/sh',
    'echo "printed to stdout before failing"',
    'echo "Unrecognized option: --bad" >&2',
    'echo "Error: Could not create the Java Virtual Machine." >&2',
    'exit 1',
    ''
].join('\n');
var ENDS_ITSELF_WITH_A_SIGNAL = [
    '#!/bin/sh',
    'kill -KILL $$',
    ''
].join('\n');
// never listens, and ends by itself if a failing test leaves it behind
var RUNS_ON = [
    '#!/bin/sh',
    'exec /bin/sleep 120',
    ''
].join('\n');

// writes each argument on a line of "args" beside itself, then fails like a JVM refusing its options
var RECORDS_ITS_ARGUMENTS = [
    '#!/bin/sh',
    'printf \'%s\\n\' "$@" > "${0%/*}/args"',
    'exit 1',
    ''
].join('\n');

var directory;

function writeJava(name, content, mode) {
    fs.mkdirSync(path.join(directory, name));
    fs.writeFileSync(path.join(directory, name, 'java'), content, {mode: mode});
}

test.before(function () {
    directory = fs.mkdtempSync(path.join(os.tmpdir(), 'mockserver-node-launch-failure-'));
    fs.mkdirSync(path.join(directory, 'no-java'));
    writeJava('not-executable', '', 0o644);
    writeJava('exits-with-usage-error', EXITS_WITH_USAGE_ERROR, 0o755);
    writeJava('ends-itself-with-a-signal', ENDS_ITSELF_WITH_A_SIGNAL, 0o755);
    writeJava('runs-on', RUNS_ON, 0o755);
    writeJava('records-its-arguments', RECORDS_ITS_ARGUMENTS, 0o755);
});

test.after(function () {
    fs.rmSync(directory, {recursive: true, force: true});
});

function pathWith(name) {
    return path.join(directory, name);
}

// a port nothing listens on, so connections to it are refused
function unusedPort() {
    return new Promise(function (resolve) {
        var server = net.createServer();
        server.listen(0, function () {
            var port = server.address().port;
            server.close(function () {
                resolve(port);
            });
        });
    });
}

function startStub(respond) {
    var sockets = [];
    var server = http.createServer(respond);
    server.on('connection', function (socket) {
        sockets.push(socket);
    });
    return new Promise(function (resolve) {
        server.listen(0, function () {
            resolve({
                port: server.address().port,
                close: function () {
                    sockets.forEach(function (socket) {
                        socket.destroy();
                    });
                    server.close();
                }
            });
        });
    });
}

function answer(request, response) {
    response.writeHead(200, {'Connection': 'close', 'Content-Length': 0});
    response.end();
}

function hangUp(request) {
    request.socket.destroy();
}

// Runs the calling script with nothing in its environment but the PATH and JAVA_HOME given, and resolves
// once it has ended and its output has been read. A script still running after the time allowed is killed.
// The script leads a process group of its own, so a signal sent to its group by mistake ends it and not
// this test run.
function runCallingScript(scenario, environment) {
    var began = Date.now();
    return new Promise(function (resolve) {
        var stdout = '';
        var stderr = '';
        var outlived = false;
        var caller = spawn(process.execPath, [CALLING_SCRIPT, JSON.stringify(scenario)], {
            env: environment,
            detached: true,
            stdio: ['ignore', 'pipe', 'pipe']
        });
        var timer = setTimeout(function () {
            outlived = true;
            caller.kill('SIGKILL');
        }, CALLER_ENDS_WITHIN_MILLIS);
        caller.stdout.on('data', function (chunk) {
            stdout += chunk;
        });
        caller.stderr.on('data', function (chunk) {
            stderr += chunk;
        });
        caller.once('close', function (status, signal) {
            clearTimeout(timer);
            resolve({
                ended: outlived ? 'killed: still running after ' + CALLER_ENDS_WITHIN_MILLIS + 'ms' : 'by itself',
                status: status,
                signal: signal,
                events: stdout.split('\n').filter(Boolean).map(function (line) {
                    try {
                        return JSON.parse(line);
                    } catch (notAnEvent) {
                        return {event: 'printed to stdout', line: line};
                    }
                }),
                stderr: stderr,
                millis: Date.now() - began
            });
        });
    });
}

function optionsFor(port, extraOptions) {
    // any existing file stands for the jar: the stand-ins for java never read it
    return Object.assign({serverPort: port, jarPath: CALLING_SCRIPT}, extraOptions);
}

function named(events, name) {
    return events.filter(function (event) {
        return event.event === name;
    });
}

// A process that has ended is still there to signal, as a zombie, until its parent collects it, and in a
// container nothing may collect one whose parent has gone: Linux shows that state in /proc.
function isRunning(pid) {
    try {
        process.kill(pid, 0);
    } catch (error) {
        return false;
    }
    try {
        var stat = fs.readFileSync('/proc/' + pid + '/stat', 'utf8');
        return stat.slice(stat.lastIndexOf(')') + 2).charAt(0) !== 'Z';
    } catch (noProc) {
        return true;
    }
}

function pause(millis) {
    return new Promise(function (resolve) {
        setTimeout(resolve, millis);
    });
}

async function endedWithin(pid, millis) {
    var deadline = Date.now() + millis;
    while (isRunning(pid) && Date.now() < deadline) {
        await pause(20);
    }
    return isRunning(pid) ? 'still running' : 'ended';
}

function killIfRunning(pid) {
    if (pid && isRunning(pid)) {
        process.kill(pid, 'SIGKILL');
    }
}

// What every rejected start must leave behind it: one rejection, a stop that resolves, a calling script
// that runs on, ends by itself with nothing keeping it alive, and exits with the status it chose.
// Returns the rejection as the calling script saw it.
function rejectedOnceAndCallerRanOn(run) {
    var rejected = named(run.events, 'start')[0];
    assert.deepStrictEqual(run.events, [
        {event: 'calling start'},
        rejected,
        {event: 'stop', state: 'resolved'},
        {event: 'later work ran'},
        {event: 'nothing left to do'}
    ]);
    assert.strictEqual(rejected.state, 'rejected');
    assert.strictEqual(run.ended, 'by itself');
    assert.strictEqual(run.status, 3, "the status the calling script set when its start was rejected");
    assert.strictEqual(run.signal, null);
    return rejected;
}

[
    {name: 'by default', extraOptions: {}},
    {name: 'with runForked', extraOptions: {runForked: true}}
].forEach(function (variant) {

    test('start rejects, naming where java was looked for, when there is no java on the PATH, ' + variant.name, async function () {
        var port = await unusedPort();
        var environment = {PATH: pathWith('no-java')};

        var run = await runCallingScript({options: optionsFor(port, variant.extraOptions)}, environment);

        var rejected = rejectedOnceAndCallerRanOn(run);
        assert.strictEqual(rejected.message, 'MockServer could not be started: no "java" command was found. The ' +
            'launcher runs "java" from the PATH of this process (PATH=' + environment.PATH + '); it does not use ' +
            'JAVA_HOME (not set) and has no option for the location of java. Install Java 17 or later and add its ' +
            'bin directory to PATH, or use the "mockserver" command of this package, which needs no Java.');
        assert.strictEqual(rejected.code, 'ENOENT');
        assert.strictEqual(run.stderr, rejected.message + '\n', "printed once, and nothing else");
    });

    test('start rejects when the java on the PATH is not executable, ' + variant.name, async function () {
        var port = await unusedPort();
        var environment = {PATH: pathWith('not-executable')};

        var run = await runCallingScript({options: optionsFor(port, variant.extraOptions)}, environment);

        var rejected = rejectedOnceAndCallerRanOn(run);
        assert.strictEqual(rejected.message, 'MockServer could not be started: permission was denied to run a ' +
            '"java" found on the PATH of this process (spawn java EACCES); check that it is an executable file. The ' +
            'launcher runs "java" from the PATH of this process (PATH=' + environment.PATH + '); it does not use ' +
            'JAVA_HOME (not set) and has no option for the location of java.');
        assert.strictEqual(rejected.code, 'EACCES');
        assert.strictEqual(run.stderr, rejected.message + '\n', "printed once, and nothing else");
    });

    test('start rejects at once, with the last output of java, when java exits with a failing status, ' + variant.name, async function () {
        var port = await unusedPort();
        var javaStderr = 'Unrecognized option: --bad\nError: Could not create the Java Virtual Machine.\n';

        var run = await runCallingScript({options: optionsFor(port, variant.extraOptions)}, {PATH: pathWith('exits-with-usage-error')});

        var rejected = rejectedOnceAndCallerRanOn(run);
        var heading = 'MockServer could not be started: its java process exited with status 1 before MockServer ' +
            'became ready on port ' + port + '; its last output:\n';
        assert.strictEqual(rejected.message.slice(0, heading.length), heading);
        // stdout and stderr are read from two pipes, so which is seen first is not fixed
        var lastOutput = rejected.message.slice(heading.length);
        assert.ok(lastOutput.includes(javaStderr.trim()), lastOutput);
        assert.ok(lastOutput.includes('printed to stdout before failing'), lastOutput);
        assert.strictEqual(rejected.exitCode, 1);
        assert.strictEqual(rejected.signal, null);
        assert.strictEqual(run.stderr, javaStderr + rejected.message + '\n', "what java printed to stderr, then the rejection once");
    });
});

test('start passes -XX:-UseBASE64Intrinsics to java before jvmOptions, so a user can still override it', async function () {
    var port = await unusedPort();

    var run = await runCallingScript({options: optionsFor(port, {jvmOptions: ['-Xmx64m', '-XX:+UseBASE64Intrinsics']})},
        {PATH: pathWith('records-its-arguments')});

    rejectedOnceAndCallerRanOn(run);
    var args = fs.readFileSync(path.join(directory, 'records-its-arguments', 'args'), 'utf8').split('\n').slice(0, -1);
    assert.deepStrictEqual(args, ['-Dfile.encoding=UTF-8', '-XX:-UseBASE64Intrinsics', '-Xmx64m', '-XX:+UseBASE64Intrinsics',
        '-jar', CALLING_SCRIPT, '-serverPort', String(port)]);
});

test('start rejects at once when java is ended by a signal before the server is ready', async function () {
    var port = await unusedPort();

    var run = await runCallingScript({options: optionsFor(port)}, {PATH: pathWith('ends-itself-with-a-signal')});

    var rejected = rejectedOnceAndCallerRanOn(run);
    assert.strictEqual(rejected.message, 'MockServer could not be started: its java process was ended by signal ' +
        'SIGKILL before MockServer became ready on port ' + port);
    assert.strictEqual(rejected.exitCode, null);
    assert.strictEqual(rejected.signal, 'SIGKILL');
    assert.strictEqual(run.stderr, rejected.message + '\n', "printed once, and nothing else");
});

// the launch itself is a stand-in here: see calling_script.js
test('start rejects when the calling process has too few file descriptors left to launch java', async function () {
    var port = await unusedPort();
    var environment = {PATH: pathWith('runs-on')};

    var run = await runCallingScript({options: optionsFor(port), spawnFailsForWantOfFileDescriptors: true}, environment);

    var rejected = rejectedOnceAndCallerRanOn(run);
    assert.strictEqual(rejected.message, 'MockServer could not be started: running "java" failed (spawn java ' +
        'EMFILE). The launcher runs "java" from the PATH of this process (PATH=' + environment.PATH + '); it does ' +
        'not use JAVA_HOME (not set) and has no option for the location of java.');
    assert.strictEqual(rejected.code, 'EMFILE');
    assert.strictEqual(run.stderr, rejected.message + '\n', "printed once, and nothing else");
});

test('start ends the process it launched when it fails after the launch', async function () {
    var launchedPid;

    try {
        // not a port: asking it whether the server is ready throws, once java has been launched
        var run = await runCallingScript({options: optionsFor(99999), noStop: true}, {PATH: pathWith('runs-on')});

        var rejected = named(run.events, 'start')[0];
        launchedPid = rejected && rejected.pid;
        assert.deepStrictEqual(run.events, [
            {event: 'calling start'},
            rejected,
            {event: 'later work ran'},
            {event: 'nothing left to do'}
        ]);
        assert.strictEqual(rejected.state, 'rejected');
        assert.strictEqual(rejected.code, 'ERR_SOCKET_BAD_PORT');
        assert.strictEqual(run.ended, 'by itself');
        assert.strictEqual(run.status, 3);
        assert.strictEqual(await endedWithin(launchedPid, 5000), 'ended', "the launched process");
    } finally {
        killIfRunning(launchedPid);
    }
});

test('start signals nothing when it fails after a launch that itself failed', async function () {
    // no java, and not a port: java is still to be reported as not launched when the readiness request throws
    var run = await runCallingScript({options: optionsFor(99999), noStop: true}, {PATH: pathWith('no-java')});

    assert.strictEqual(run.signal, null, "the calling script was not signalled");
    var rejected = named(run.events, 'start')[0];
    assert.deepStrictEqual(run.events, [
        {event: 'calling start'},
        rejected,
        {event: 'later work ran'},
        {event: 'nothing left to do'}
    ]);
    assert.strictEqual(rejected.state, 'rejected');
    assert.strictEqual(rejected.code, 'ERR_SOCKET_BAD_PORT');
    assert.strictEqual(run.stderr, 'MockServer java process: spawn java ENOENT\n');
    assert.strictEqual(run.ended, 'by itself');
    assert.strictEqual(run.status, 3);
});

test('start says that JAVA_HOME is set and not used when there is no java on the PATH', async function () {
    var port = await unusedPort();
    var environment = {PATH: pathWith('no-java'), JAVA_HOME: pathWith('runs-on')};

    var run = await runCallingScript({options: optionsFor(port)}, environment);

    var rejected = named(run.events, 'start')[0];
    assert.strictEqual(rejected.state, 'rejected');
    assert.ok(rejected.message.includes('it does not use JAVA_HOME (set to ' + environment.JAVA_HOME + ') and has no option'), rejected.message);
    assert.strictEqual(run.ended, 'by itself');
    assert.strictEqual(run.status, 3);
});

test('start rejects once, and the calling script can end, when the server never becomes ready', async function () {
    var stub = await startStub(hangUp);
    var launchedPid;

    try {
        var run = await runCallingScript({options: optionsFor(stub.port, {startupRetries: 3})}, {PATH: pathWith('runs-on')});

        var rejected = named(run.events, 'start');
        assert.strictEqual(rejected.length, 1);
        assert.strictEqual(rejected[0].state, 'rejected');
        assert.match(rejected[0].message, new RegExp('^MockServer did not become ready on port ' + stub.port + ' within [\\d.]+ seconds \\(socket hang up\\); its java process \\(pid (\\d+)\\) was stopped$'));
        launchedPid = Number(/pid (\d+)/.exec(rejected[0].message)[1]);
        // stopping the stand-in ends it with a signal, which must not be reported as a second failure
        assert.strictEqual(run.stderr, rejected[0].message + '\n', "printed once, and nothing else");
        assert.strictEqual(run.ended, 'by itself');
        assert.strictEqual(run.status, 3);
        assert.strictEqual(await endedWithin(launchedPid, 5000), 'ended', "the launched process");
    } finally {
        stub.close();
        killIfRunning(launchedPid);
    }
});

test('an uncaught exception in the calling script ends the launched process and fails the script', async function () {
    var stub = await startStub(answer);
    var launchedPid;

    try {
        var run = await runCallingScript({options: optionsFor(stub.port), thenThrow: true}, {PATH: pathWith('runs-on')});

        var started = named(run.events, 'start');
        assert.strictEqual(started.length, 1);
        assert.strictEqual(started[0].state, 'resolved');
        launchedPid = started[0].pid;
        assert.strictEqual(run.ended, 'by itself');
        assert.strictEqual(run.status, 1, "the status Node gives an uncaught exception");
        assert.match(run.stderr, /Error: thrown by the calling script\n\s+at /, "Node reports the exception");
        assert.deepStrictEqual(named(run.events, 'nothing left to do'), [], "the exception ended the script");
        assert.strictEqual(await endedWithin(launchedPid, 5000), 'ended', "the launched process");
    } finally {
        stub.close();
        killIfRunning(launchedPid);
    }
});

test('an uncaught exception in the calling script leaves a process launched with runForked running', async function () {
    var stub = await startStub(answer);
    var launchedPid;

    try {
        var run = await runCallingScript({options: optionsFor(stub.port, {runForked: true}), thenThrow: true}, {PATH: pathWith('runs-on')});

        var started = named(run.events, 'start');
        assert.strictEqual(started.length, 1);
        assert.strictEqual(started[0].state, 'resolved');
        launchedPid = started[0].pid;
        assert.strictEqual(run.status, 1, "the status Node gives an uncaught exception");
        assert.match(run.stderr, /Error: thrown by the calling script\n\s+at /, "Node reports the exception");
        assert.strictEqual(await endedWithin(launchedPid, 500), 'still running', "the launched process");
    } finally {
        stub.close();
        killIfRunning(launchedPid);
    }
});

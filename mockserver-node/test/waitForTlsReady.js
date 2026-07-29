module.exports = (function () {

    var tls = require('tls');

    /**
     * Wait until the server can complete a TLS handshake on the given port.
     *
     * start_mockserver only proves the HTTP control plane is answering - it polls
     * "PUT /mockserver/retrieve" over plain HTTP. When MockServer is started with
     * dynamicallyCreateCertificateAuthorityCertificate=true it still has to generate
     * a CA key pair and a leaf certificate before it can serve TLS on that same
     * (port-unified) port. A HTTPS request issued in that window is closed mid
     * handshake, surfacing as ECONNRESET "Client network socket disconnected before
     * secure TLS connection was established".
     *
     * Waiting for an actual handshake to succeed gates the test on the condition it
     * really depends on, rather than retrying the assertions themselves.
     *
     * Certificate validation is deliberately left ON. The question being asked is
     * "has the server got as far as serving TLS", not "do we trust its certificate",
     * so a certificate the client cannot verify - which is exactly what a freshly
     * generated CA produces - still answers it: the server presented a certificate,
     * therefore it is serving TLS. Only a connection that dies without ever
     * producing one counts as not-ready. That keeps this probe from having to
     * disable certificate checking.
     */
    return function (host, port, timeoutMs) {
        // Generous on purpose. Waiting costs nothing when the server is healthy - a ready server
        // completes the handshake on the first attempt in milliseconds - so the only thing this
        // number decides is how much CI contention is tolerated before a slow start is called a
        // failure. 30s was too tight: it went green five builds running and then failed on a loaded
        // agent, which is the same flake in a new disguise rather than a real fault.
        var limit = timeoutMs || 120000;
        var start = Date.now();
        var deadline = start + limit;
        var attempts = 0;

        return new Promise(function (resolve, reject) {
            function attempt() {
                var settled = false;
                attempts++;
                var socket = tls.connect({
                    host: host,
                    port: port
                });

                // a handshake that stalls must not hold the whole wait open
                socket.setTimeout(2000);

                function succeed() {
                    if (settled) {
                        return;
                    }
                    settled = true;
                    socket.destroy();
                    var elapsed = Date.now() - start;
                    // Surface a slow start rather than hiding it in a passing test: the failure mode
                    // this guard exists for is readiness creeping towards the limit, and a green run
                    // that took 40s is the warning that the next one will not be.
                    if (elapsed > 5000) {
                        console.error('# TLS on port ' + port + ' took ' + elapsed + 'ms (' + attempts + ' attempts) to become ready');
                    }
                    resolve();
                }

                function retry(error) {
                    if (settled) {
                        return;
                    }
                    settled = true;
                    socket.destroy();
                    if (Date.now() >= deadline) {
                        // Report the attempt count as well - "one attempt that hung" and "hundreds
                        // that were refused" are different faults and the message should say which.
                        reject(new Error('MockServer did not serve TLS on port ' + port +
                            ' within ' + limit + 'ms (' + attempts + ' attempts over ' +
                            (Date.now() - start) + 'ms): ' + error.message));
                    } else {
                        setTimeout(attempt, 100);
                    }
                }

                // a trusted certificate completes the handshake outright
                socket.once('secureConnect', succeed);

                socket.once('error', function (error) {
                    // node attaches the peer certificate to certificate-verification
                    // failures; either way, having one means TLS was served
                    var certificate = error.cert || socket.getPeerCertificate();
                    if (certificate && Object.keys(certificate).length > 0) {
                        succeed();
                    } else {
                        retry(error);
                    }
                });

                socket.once('timeout', function () {
                    retry(new Error('TLS handshake timed out'));
                });
            }

            attempt();
        });
    };
})();

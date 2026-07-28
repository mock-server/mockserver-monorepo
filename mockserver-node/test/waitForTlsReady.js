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
        var limit = timeoutMs || 30000;
        var deadline = Date.now() + limit;

        return new Promise(function (resolve, reject) {
            function attempt() {
                var settled = false;
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
                    resolve();
                }

                function retry(error) {
                    if (settled) {
                        return;
                    }
                    settled = true;
                    socket.destroy();
                    if (Date.now() >= deadline) {
                        reject(new Error('MockServer did not serve TLS on port ' + port +
                            ' within ' + limit + 'ms: ' + error.message));
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

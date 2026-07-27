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
     */
    return function (host, port, timeoutMs) {
        var limit = timeoutMs || 30000;
        var deadline = Date.now() + limit;

        return new Promise(function (resolve, reject) {
            function attempt() {
                var settled = false;
                var socket = tls.connect({
                    host: host,
                    port: port,
                    rejectUnauthorized: false
                });

                // a handshake that stalls must not hold the whole wait open
                socket.setTimeout(2000);

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

                socket.once('secureConnect', function () {
                    if (settled) {
                        return;
                    }
                    settled = true;
                    socket.end();
                    resolve();
                });
                socket.once('error', retry);
                socket.once('timeout', function () {
                    retry(new Error('TLS handshake timed out'));
                });
            }

            attempt();
        });
    };
})();

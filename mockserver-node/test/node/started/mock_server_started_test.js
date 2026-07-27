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
var mockserver = require(__dirname + '/../../..');
var sendRequest = require(__dirname + '/../../sendRequest.js');
var waitForTlsReady = require(__dirname + '/../../waitForTlsReady.js');

function checkFileExists(path) {
    try {
        assert.ok(fs.existsSync(path), "check '" + path + "' exists");
    } catch (err) {
        console.error(err);
        assert.ok(false, "failed check if '" + path + "' exists: " + err);
    }
}

test('should allow expectation to be set up', async function () {
    var port = 1081;

    await mockserver.start_mockserver({serverPort: port});
    try {
        var response = await sendRequest("PUT", "localhost", port, "/expectation", {
            'httpRequest': {
                'path': '/somePath'
            },
            'httpResponse': {
                'statusCode': 202,
                'body': JSON.stringify({name: 'first_body'})
            }
        });
        assert.strictEqual(response.statusCode, 201, "allows expectation to be setup");

        var matchResponse = await sendRequest("GET", "localhost", port, "/somePath");
        assert.strictEqual(matchResponse.statusCode, 202, "expectation matched successfully");
    } finally {
        await mockserver.stop_mockserver({serverPort: port});
    }
});

test('allow multiple system properties to be specified in single string', async function () {
    var port = 1082;

    await mockserver.start_mockserver({
        serverPort: port,
        jvmOptions: '-Dmockserver.dynamicallyCreateCertificateAuthorityCertificate=true -Dmockserver.directoryToSaveDynamicSSLCertificate=./tmp/' + port
    });
    try {
        await waitForTlsReady("localhost", port);

        var response = await sendRequest("PUT", "localhost", port, "/expectation", {
            'httpRequest': {
                'path': '/somePath'
            },
            'httpResponse': {
                'statusCode': 202,
                'body': JSON.stringify({name: 'first_body'})
            }
        }, "https");
        assert.strictEqual(response.statusCode, 201, "allows expectation to be setup");

        var matchResponse = await sendRequest("GET", "localhost", port, "/somePath", undefined, "https");
        assert.strictEqual(matchResponse.statusCode, 202, "expectation matched successfully");

        checkFileExists('./tmp/' + port + '/PKCS8CertificateAuthorityPrivateKey.pem');
    } finally {
        await mockserver.stop_mockserver({serverPort: port});
    }
});

test('allow multiple system properties to be specified as array', async function () {
    var port = 1083;

    await mockserver.start_mockserver({
        serverPort: port,
        jvmOptions: ['-Dmockserver.dynamicallyCreateCertificateAuthorityCertificate=true', '-Dmockserver.directoryToSaveDynamicSSLCertificate=./tmp/' + port]
    });
    try {
        await waitForTlsReady("localhost", port);

        var response = await sendRequest("PUT", "localhost", port, "/expectation", {
            'httpRequest': {
                'path': '/somePath'
            },
            'httpResponse': {
                'statusCode': 202,
                'body': JSON.stringify({name: 'first_body'})
            }
        }, "https");
        assert.strictEqual(response.statusCode, 201, "allows expectation to be setup");

        var matchResponse = await sendRequest("GET", "localhost", port, "/somePath", undefined, "https");
        assert.strictEqual(matchResponse.statusCode, 202, "expectation matched successfully");

        checkFileExists('./tmp/' + port + '/PKCS8CertificateAuthorityPrivateKey.pem');
    } finally {
        await mockserver.stop_mockserver({serverPort: port});
    }
});

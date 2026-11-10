package org.mockserver.netty;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.authentication.AuthenticationHandler;
import org.mockserver.authentication.jwt.JWKGenerator;
import org.mockserver.authentication.jwt.JWTGenerator;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.keys.AsymmetricKeyGenerator;
import org.mockserver.keys.AsymmetricKeyPair;
import org.mockserver.keys.AsymmetricKeyPairAlgorithm;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.model.HttpResponse;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.test.TempFileWriter;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.model.HttpRequest.request;

/**
 * End-to-end guard for the {@code PUT /mockserver/configuration} route.
 *
 * <p>This is the route that made the defect dangerous rather than merely wrong: an operator hardening a
 * running shared/CI instance issued a {@code PUT}, received {@code 200} with the updated configuration
 * echoed back, saw {@code true} on a subsequent {@code GET}, and reasonably concluded the control plane
 * was locked. It was fully open — including the recorded request log, which in proxy mode holds real
 * captured credentials — because the authentication handler chain had been built once at server bootstrap
 * and nothing rebuilt it.
 *
 * <p>The core-module {@code ControlPlaneAuthenticationRouteDenialTest} covers the system-property,
 * {@code Configuration} and DTO routes; this covers the HTTP endpoint itself.
 */
public class ControlPlaneAuthenticationRuntimePutTest {

    private Configuration configuration;
    private HttpState httpState;
    private HttpRequestHandler httpRequestHandler;
    private LifeCycle server;

    @Before
    public void setUp() {
        resetStaticControlPlaneProperties();
        // ONE shared Configuration instance, as the running server has: the PUT mutates it and the
        // control-plane gate reads it
        configuration = Configuration.configuration();
        server = mock(MockServer.class);
        when(server.getScheduler()).thenReturn(mock(Scheduler.class));
        httpState = new HttpState(configuration, new MockServerLogger(), mock(Scheduler.class));
        httpRequestHandler = new HttpRequestHandler(configuration, server, httpState, null);
    }

    @After
    public void tearDown() {
        httpState.stop();
        resetStaticControlPlaneProperties();
    }

    /**
     * A control-plane response closes the channel, so every request needs a FRESH EmbeddedChannel — the
     * handler and the state/configuration it reads are shared across them, exactly as on a real server
     * where one handler serves many connections.
     */
    private HttpResponse exchange(org.mockserver.model.HttpRequest request) {
        EmbeddedChannel embeddedChannel = new EmbeddedChannel(httpRequestHandler);
        try {
            embeddedChannel.writeInbound(request);
            return embeddedChannel.readOutbound();
        } finally {
            embeddedChannel.finishAndReleaseAll();
        }
    }

    private static void resetStaticControlPlaneProperties() {
        ConfigurationProperties.controlPlaneJWTAuthenticationRequired(false);
        ConfigurationProperties.controlPlaneTLSMutualAuthenticationRequired(false);
        ConfigurationProperties.controlPlaneOidcAuthenticationRequired(false);
    }

    private HttpResponse put(String configurationJson) {
        return exchange(request("/mockserver/configuration")
            .withMethod("PUT")
            .withBody(configurationJson));
    }

    @Test
    public void aPutCannotSwitchControlPlaneAuthenticationOn() {
        HttpResponse putResponse = put("{\"controlPlaneJWTAuthenticationRequired\": true}");

        // the endpoint answers, but the value is fixed at startup: it is ignored and the value in force reported
        assertThat(putResponse.getStatusCode(), is(200));
        assertThat(putResponse.getBodyAsString(), matchesPattern("(?s).*\"controlPlaneJWTAuthenticationRequired\"\\s*:\\s*false.*"));
        assertThat(get("/mockserver/configuration").getBodyAsString(), matchesPattern("(?s).*\"controlPlaneJWTAuthenticationRequired\"\\s*:\\s*false.*"));
        assertThat(configuration.controlPlaneJWTAuthenticationRequired(), is(false));
        assertThat(httpState.getControlPlaneAuthenticationHandler(), is(nullValue()));
    }

    @Test
    public void anAuthenticatedPutCannotSwitchControlPlaneAuthenticationOffOrRebuildItsHandler() {
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        configuration
            .controlPlaneJWTAuthenticationJWKSource(TempFileWriter.write(new JWKGenerator().generateJWK(keyPair)))
            .controlPlaneJWTAuthenticationRequired(true);
        String token = new JWTGenerator(keyPair).generateJWT();
        AuthenticationHandler handlerInForce = httpState.getControlPlaneAuthenticationHandler();

        AsymmetricKeyPair attackerKeyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        HttpResponse response = exchange(request("/mockserver/configuration")
            .withMethod("PUT")
            .withHeader("Authorization", "Bearer " + token)
            .withBody("{\"controlPlaneJWTAuthenticationRequired\": false, \"controlPlaneJWTAuthenticationJWKSource\": \""
                + TempFileWriter.write(new JWKGenerator().generateJWK(attackerKeyPair)).replace("\\", "\\\\") + "\", "
                + "\"controlPlaneOidcAuthenticationRequired\": true, \"controlPlaneAuthorizationEnabled\": true}"));

        assertThat(response.getStatusCode(), is(200));
        assertThat(configuration.controlPlaneJWTAuthenticationRequired(), is(true));
        assertThat("the derived handler must not be rebuilt by a PUT", httpState.getControlPlaneAuthenticationHandler(), is(sameInstance(handlerInForce)));
        assertThat(httpState.evaluateControlPlaneAuthentication(request("/mockserver/retrieve")).isAllowed(), is(false));
        assertThat("a token signed by the key the PUT tried to install must not be accepted",
            httpState.evaluateControlPlaneAuthentication(request("/mockserver/retrieve")
                .withHeader("Authorization", "Bearer " + new JWTGenerator(attackerKeyPair).generateJWT())).isAllowed(), is(false));
    }

    @Test
    public void aFurtherUnauthenticatedPutIsItselfRefusedAndCannotReopenTheControlPlane() {
        configuration.controlPlaneJWTAuthenticationRequired(true);
        assertThat(httpState.evaluateControlPlaneAuthentication(request()).isAllowed(), is(false));

        // /configuration is itself a gated control-plane endpoint, so once the lock is on, a further
        // UNAUTHENTICATED reconfiguration is refused outright rather than applied
        HttpResponse putResponse = put("{\"maxExpectations\": 4321}");
        assertThat("a configuration PUT with no credentials must be refused once the control plane is locked",
            putResponse.getStatusCode(), is(401));

        assertThat("a refused configuration PUT must not re-open the control plane",
            httpState.evaluateControlPlaneAuthentication(request()).isAllowed(), is(false));
    }

    @Test
    public void theLockCannotBeLiftedByAnUnauthenticatedPut() {
        configuration.controlPlaneJWTAuthenticationRequired(true);
        assertThat(httpState.evaluateControlPlaneAuthentication(request()).isAllowed(), is(false));

        // an unauthenticated caller must NOT be able to turn control-plane authentication back off — the
        // endpoint that would do so is itself gated, and an authenticated PUT cannot either (see above).
        HttpResponse putResponse = put("{\"controlPlaneJWTAuthenticationRequired\": false}");
        assertThat(putResponse.getStatusCode(), is(401));

        assertThat("an unauthenticated PUT must not be able to unlock the control plane",
            httpState.evaluateControlPlaneAuthentication(request()).isAllowed(), is(false));
    }

    @Test
    public void aRejectedAuthenticatedPutMustLeaveControlPlaneAuthenticationEnforced() {
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        configuration
            .controlPlaneJWTAuthenticationJWKSource(TempFileWriter.write(new JWKGenerator().generateJWK(keyPair)))
            .controlPlaneJWTAuthenticationRequired(true);
        String token = new JWTGenerator(keyPair).generateJWT();

        // each body supplies JWT off with a field applyTo rejects; the whole PUT is refused and changes nothing
        for (String rejectedPut : new String[]{
            "{\"controlPlaneJWTAuthenticationRequired\": false, \"globalResponseDelayMillis\": -1}",
            "{\"controlPlaneJWTAuthenticationRequired\": false, \"tlsMutualAuthenticationCertificateChain\": \"/no/such/chain.pem\"}"
        }) {
            HttpResponse response = exchange(request("/mockserver/configuration")
                .withMethod("PUT")
                .withHeader("Authorization", "Bearer " + token)
                .withBody(rejectedPut));

            assertThat(rejectedPut, response.getStatusCode(), is(400));
            assertThat("a rejected PUT must change nothing: " + rejectedPut,
                httpState.evaluateControlPlaneAuthentication(request("/mockserver/retrieve")).isAllowed(), is(false));
            assertThat(rejectedPut, configuration.controlPlaneJWTAuthenticationRequired(), is(true));
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Control-plane GETs served directly by HttpRequestHandler must be gated like their siblings
    // ------------------------------------------------------------------------------------------------

    private HttpResponse get(String path) {
        return exchange(request(path).withMethod("GET"));
    }

    @Test
    public void metricsEndpointMustDenyWhenControlPlaneAuthenticationIsRequired() {
        // /metrics had NO authentication gate, alone among its neighbours. That leaks more than it looks:
        // metric cardinality scales with the number of configured expectations, so an unauthenticated
        // caller can infer the expectation surface of a running instance.
        configuration.controlPlaneJWTAuthenticationRequired(true);

        HttpResponse response = get("/mockserver/metrics");
        assertThat("GET /mockserver/metrics must be refused once control-plane authentication is required",
            response.getStatusCode(), is(401));
    }

    @Test
    public void http3StatusEndpointMustDenyWhenControlPlaneAuthenticationIsRequired() {
        configuration.controlPlaneJWTAuthenticationRequired(true);

        HttpResponse response = get("/mockserver/http3status");

        assertThat("GET /mockserver/http3status must be refused once control-plane authentication is required",
            response.getStatusCode(), is(401));
    }

    @Test
    public void metricsAndHttp3StatusMustStillDenyAfterAFurtherRuntimePut() {
        configuration.controlPlaneJWTAuthenticationRequired(true);
        assertThat(get("/mockserver/metrics").getStatusCode(), is(401));

        // a further unauthenticated reconfiguration is refused and must not re-open either endpoint
        assertThat(put("{\"maxExpectations\": 4321}").getStatusCode(), is(401));

        assertThat(get("/mockserver/metrics").getStatusCode(), is(401));
        assertThat(get("/mockserver/http3status").getStatusCode(), is(401));
    }

    @Test
    public void metricsAndHttp3StatusStayOpenByDefault() {
        // pins the DEFAULT posture: control-plane authentication is opt-in, so an instance that has not
        // enabled it keeps serving both endpoints unauthenticated. A change that closed them by default
        // would break every existing Prometheus scrape config, and must not happen silently.
        configuration.metricsEnabled(true);
        httpRequestHandler = new HttpRequestHandler(configuration, server, httpState, null);
        assertThat(get("/mockserver/metrics").getStatusCode(), is(200));
        assertThat(get("/mockserver/http3status").getStatusCode(), is(200));
    }

    /**
     * The netty-module half of the credential-comparison guard (the core module cannot see this class).
     *
     * <p>Fails if the {@code CONNECT} proxy-authentication path stops delegating to
     * {@code ProxyAuthenticationValidator} — i.e. if it goes back to
     * {@code request.containsHeader(PROXY_AUTHORIZATION, ...)}, whose comparison is case-insensitive
     * (base64 is not) and short-circuiting (a timing channel).
     */
    @Test
    public void connectProxyAuthenticationMustUseTheConstantTimeValidator() throws Exception {
        assertThat("Ingress proxy-authentication check must compare credentials "
                + "through ProxyAuthenticationValidator (exact, constant-time), not containsHeader",
            referencedClassNames("org.mockserver.netty.unification.ProxyAuthenticationHandler"),
            hasItem("org/mockserver/authentication/ProxyAuthenticationValidator"));
    }

    /**
     * Read the class constant pool and return every referenced class name. Deliberately dependency-free.
     */
    private static List<String> referencedClassNames(String className) throws IOException {
        String resource = "/" + className.replace('.', '/') + ".class";
        try (InputStream inputStream = ControlPlaneAuthenticationRuntimePutTest.class.getResourceAsStream(resource)) {
            if (inputStream == null) {
                throw new IOException("class not on classpath: " + className);
            }
            DataInputStream classFile = new DataInputStream(inputStream);
            classFile.readInt();
            classFile.readUnsignedShort();
            classFile.readUnsignedShort();
            int constantPoolCount = classFile.readUnsignedShort();
            String[] utf8Constants = new String[constantPoolCount];
            int[] classNameIndexes = new int[constantPoolCount];
            for (int i = 1; i < constantPoolCount; i++) {
                int tag = classFile.readUnsignedByte();
                switch (tag) {
                    case 1:
                        utf8Constants[i] = classFile.readUTF();
                        break;
                    case 7:
                        classNameIndexes[i] = classFile.readUnsignedShort();
                        break;
                    case 8:
                    case 16:
                    case 19:
                    case 20:
                        classFile.readUnsignedShort();
                        break;
                    case 15:
                        classFile.readUnsignedByte();
                        classFile.readUnsignedShort();
                        break;
                    case 5:
                    case 6:
                        classFile.readLong();
                        i++;
                        break;
                    default:
                        classFile.readInt();
                        break;
                }
            }
            List<String> referenced = new ArrayList<>();
            for (int i = 1; i < constantPoolCount; i++) {
                if (classNameIndexes[i] != 0) {
                    referenced.add(utf8Constants[classNameIndexes[i]]);
                }
            }
            return referenced;
        }
    }
}

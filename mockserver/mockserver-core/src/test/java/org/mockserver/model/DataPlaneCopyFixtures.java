package org.mockserver.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockserver.matchers.MatchType;

import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.mockserver.model.Header.header;
import static org.mockserver.model.NottableString.string;
import static org.mockserver.model.Parameter.param;

/**
 * Requests and responses with every field set, one for each concrete body, each built afresh on every call so a
 * test can compare a fixture with an untouched twin.
 */
final class DataPlaneCopyFixtures {

    private DataPlaneCopyFixtures() {
    }

    /**
     * One builder per body shape: every concrete {@link Body}, and both storage forms of the bodies that hold bytes
     * either in one array or in segments.
     */
    static Map<String, Supplier<Body<?>>> bodies() {
        Map<String, Supplier<Body<?>>> bodies = new LinkedHashMap<>();
        bodies.put("BinaryBody", () -> flags(new BinaryBody(bytes(40), MediaType.APPLICATION_OCTET_STREAM)));
        bodies.put("BinaryBody (segmented)", () -> flags(BinaryBody.fromSegmentedBytes(segmented(3000), MediaType.APPLICATION_OCTET_STREAM)));
        bodies.put("StringBody", () -> {
            StringBody body = new StringBody("some text", null, true, MediaType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));
            body.getValue();
            return flags(body);
        });
        bodies.put("StringBody (segmented)", () -> {
            StringBody body = StringBody.fromSegmentedBytes(segmented(3000), MediaType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));
            body.getValue();
            return flags(body);
        });
        bodies.put("JsonBody", () -> {
            JsonBody body = new JsonBody("{\"a\":1}", null, MediaType.parse("application/vnd.example+json; charset=utf-8"), MatchType.STRICT, true);
            body.get("a");
            return flags(body);
        });
        bodies.put("XmlBody", () -> {
            XmlBody body = new XmlBody("<a>1</a>", MediaType.parse("text/xml; charset=utf-8"));
            body.getValue();
            return flags(body);
        });
        bodies.put("RegexBody", () -> flags(new RegexBody("some.*")));
        bodies.put("FuzzyBody", () -> flags(new FuzzyBody("fuzzy", 0.5, true)));
        bodies.put("JsonRpcBody", () -> flags(new JsonRpcBody("method", "{\"type\":\"object\"}")));
        bodies.put("WasmBody", () -> flags(new WasmBody("module")));
        bodies.put("XmlSchemaBody", () -> flags(new XmlSchemaBody("<xs:schema/>", "file:/schema.xsd")));
        bodies.put("JsonPathBody", () -> flags(new JsonPathBody("$.a", NottableDateTimeString.after("now -1 days"))));
        bodies.put("XPathBody", () -> flags(new XPathBody("/a", mutableMap("p", "urn:p"), NottableDateTimeString.before("now"))));
        bodies.put("JsonSchemaBody", () -> {
            Map<String, ParameterStyle> styles = new LinkedHashMap<>();
            styles.put("a", ParameterStyle.FORM);
            return flags(new JsonSchemaBody("{\"type\":\"object\"}").withParameterStyles(styles));
        });
        bodies.put("GraphQLBody", () -> flags(new GraphQLBody("{ a }", "Op", "{\"type\":\"object\"}")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_EXACT)
            .withFields(new ArrayList<>(List.of("a", "b")))
            .withSchema("type Query { a: String }")));
        bodies.put("ParameterBody", () -> flags(new ParameterBody(parameters("p"))));
        bodies.put("MultipartBody", () -> flags(new MultipartBody(parameters("field"), parameters("file"), parameters("type"))));
        bodies.put("AllOfBody", () -> flags(new AllOfBody(new ArrayList<>(List.of(flags(new RegexBody("a.*")), flags(new ParameterBody(parameters("q"))))))));
        bodies.put("ResponseMatchingBody", () -> {
            ResponseMatchingBody body = new ResponseMatchingBody(Not.not(new ParameterBody(parameters("r"))));
            body.withOptional(true);
            Not.not(body);
            return body;
        });
        bodies.put("LogEntryBody", () -> flags(new LogEntryBody(json("{\"logged\":[1,2]}"))));
        bodies.put("FileBody", () -> flags(new FileBody("some/file.json", MediaType.APPLICATION_JSON, HttpTemplate.TemplateType.VELOCITY).withTemplatedFilePath(true)));
        Map<String, Supplier<Body<?>>> perturbed = new LinkedHashMap<>();
        bodies.forEach((name, body) -> perturbed.put(name, () -> DeepCopyWalker.perturbDefaultPrimitives(body.get())));
        return perturbed;
    }

    /**
     * The shapes the remote address takes: the text; the holder the transport shares across a connection, with the
     * peer's address and its bytes once derived; and a holder with only the bytes.
     */
    static Map<String, Supplier<Object>> remoteAddresses() {
        Map<String, Supplier<Object>> remoteAddresses = new LinkedHashMap<>();
        remoteAddresses.put("text", () -> "10.0.0.1:52000");
        remoteAddresses.put("received", () -> {
            HttpRequest.RemoteAddress remoteAddress = new HttpRequest.RemoteAddress("10.0.0.2:52001", inetAddress());
            new HttpRequest().withReceivedRemoteAddress(remoteAddress).getRemoteAddressBytes();
            return remoteAddress;
        });
        remoteAddresses.put("bytes only", () -> remoteAddressOf(new HttpRequest().withRemoteAddress("10.0.0.3:52002").withRemoteAddressBytes(new byte[]{10, 0, 0, 3})));
        return remoteAddresses;
    }

    static HttpRequest request(Body<?> body, Object remoteAddress) {
        HttpRequest request = new HttpRequest()
            .withMethod("POST")
            .withPath("/some/path")
            .withPathParameters(parameters("id"))
            .withQueryStringParameters(parameters("q"))
            .withBody(body)
            .withOriginalBody(bytes(30))
            .withHeaders(headers("x-request"))
            .withCookies(new Cookies(new Cookie("session", "abc")))
            .withKeepAlive(true)
            .withSecure(true)
            .withRespondBeforeBody(true)
            .withProtocol(Protocol.HTTP_2)
            .withStreamId(7)
            .withClientCertificateChain(new ArrayList<>(List.of(certificate())))
            .withClientCertificate(ClientCertificate.clientCertificate().withSubject("CN=client").withIssuer("CN=issuer").withFingerprintSha256("ab:cd"))
            .withJwt(Jwt.jwt().withHeader("x-token").withScheme("Token").withClaims(mutableNottableMap("sub", "user")).withIssuer("issuer").withAudience("audience").withAlgorithm("RS256"))
            .withClientIp("10.0.0.0/8")
            .withSocketAddress("upstream.example", 8443, SocketAddress.Scheme.HTTPS)
            .withLocalAddress("127.0.0.1:1080")
            .withPathGroups(new ArrayList<>(List.of("/some/path", "path")))
            .withNamedPathGroups(mutableMap("name", "path"))
            .setLoadGenerated(true)
            .withMaskedCredentialValues(new ArrayList<>(List.of("secret")))
            .withUndecodableQueryString("%zz")
            .markControlPlaneRequest();
        request.withNot(false);
        request.withLogCorrelationId("correlation-id").withReceivedTimestamp(1_700_000_000_000L);
        assignField(request, "remoteAddress", remoteAddress);
        request.withHeader("content-encoding", "gzip").markBodyAsReceived();
        return DeepCopyWalker.perturbDefaultPrimitives(request);
    }

    static HttpResponse response(BodyWithContentType<?> body) {
        return DeepCopyWalker.perturbDefaultPrimitives(responseWithoutRecovery(body)
            .withRecoverAfter(RecoverAfter.recoverAfter(2)
                .withFailResponse(responseWithoutRecovery(flags(new StringBody("down", MediaType.TEXT_PLAIN))))
                .withIdempotencyHeader("x-idempotency")));
    }

    private static HttpResponse responseWithoutRecovery(BodyWithContentType<?> body) {
        HttpResponse response = new HttpResponse()
            .withStatusCode(201)
            .withStatusCodeRange("2XX")
            .withReasonPhrase("Created")
            .withBody(body)
            .withGenerateFromSchema("{\"type\":\"object\"}")
            .withHeaders(headers("x-response"))
            .withTrailers(headers("x-trailer"))
            .withCookies(new Cookies(new Cookie("session", "abc")))
            .withConnectionOptions(ConnectionOptions.connectionOptions()
                .withSuppressContentLengthHeader(true)
                .withContentLengthHeaderOverride(10)
                .withSuppressConnectionHeader(true)
                .withChunkSize(5)
                .withChunkDelay(delay(1))
                .withKeepAliveOverride(true)
                .withCloseSocket(true)
                .withCloseSocketDelay(delay(2)))
            .withStreamId(9)
            .withTiming(timing())
            .withStreamingBody(new StreamingBody(1024))
            .withDelay(delay(3))
            .withPrimary(true);
        response.setExpectationId("expectation-id");
        return response;
    }

    private static Delay delay(long value) {
        DelayDistribution distribution = new DelayDistribution();
        assignField(distribution, "type", DelayDistribution.Type.LOG_NORMAL);
        for (String field : new String[]{"min", "max", "median", "p99", "mean", "stdDev"}) {
            assignField(distribution, field, value + field.length());
        }
        return new Delay(TimeUnit.MILLISECONDS, value, distribution, "$!request.path", HttpTemplate.TemplateType.VELOCITY);
    }

    static Parameters parameters(String name) {
        Parameters parameters = new Parameters(param(name, "one", "two"), param(name + "-other", "three"));
        parameters.withKeyMatchStyle(KeyMatchStyle.MATCHING_KEY);
        return parameters.withRawParameterString(name + "=one&" + name + "=two&" + name + "-other=three");
    }

    static Headers headers(String name) {
        return new Headers(header(name, "one", "two"), header("content-type", "application/json")).withKeyMatchStyle(KeyMatchStyle.MATCHING_KEY);
    }

    static Object remoteAddressOf(HttpRequest request) {
        return getField(request, "remoteAddress");
    }

    static Object getField(Object target, String name) {
        try {
            java.lang.reflect.Field field = findField(target.getClass(), name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    static void assignField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field field = findField(target.getClass(), name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static java.lang.reflect.Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // look in the superclass
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static <B extends Body<?>> B flags(B body) {
        body.withOptional(true);
        return Not.not(body);
    }

    private static Timing timing() {
        return Timing.timing()
            .withRequestStartedMillis(1L)
            .withConnectionEstablishedMillis(2L)
            .withResponseReceivedMillis(3L)
            .withConnectionTimeInMillis(4L)
            .withTimeToFirstByteInMillis(5L)
            .withTotalTimeInMillis(6L)
            .withInjectedChaosLatencyMillis(7L)
            .withInjectedDelayMillis(8L)
            .withBreakpointHeldMillis(9L);
    }

    static byte[] bytes(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) ('a' + i % 26);
        }
        return bytes;
    }

    private static SegmentedBytes segmented(int length) {
        SegmentedBytes segmentedBytes = new SegmentedBytes(1 << 20);
        segmentedBytes.write(bytes(length), 0, length);
        return segmentedBytes;
    }

    private static Map<String, String> mutableMap(String key, String value) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    private static Map<String, NottableString> mutableNottableMap(String key, String value) {
        Map<String, NottableString> map = new LinkedHashMap<>();
        map.put(key, string(value));
        return map;
    }

    private static Object json(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static InetAddress inetAddress() {
        try {
            return InetAddress.getByAddress(new byte[]{10, 0, 0, 2});
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static X509Certificate certificate() {
        try (InputStream pem = DataPlaneCopyFixtures.class.getClassLoader().getResourceAsStream("org/mockserver/socket/CertificateAuthorityCertificate.pem")) {
            Certificate certificate = CertificateFactory.getInstance("X.509").generateCertificate(pem);
            return X509Certificate.x509Certificate()
                .withCertificate(certificate)
                .withIssuerDistinguishedName("CN=issuer")
                .withSubjectDistinguishedName("CN=subject")
                .withSerialNumber("1234")
                .withSignatureAlgorithmName("SHA256withRSA");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}

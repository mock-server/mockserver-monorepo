package org.mockserver.model;

import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.DataPlaneCopies.acceptFilteredRequest;
import static org.mockserver.model.DataPlaneCopies.acceptTransformedResponse;
import static org.mockserver.model.DataPlaneCopies.changed;
import static org.mockserver.model.DataPlaneCopies.copyForFilter;
import static org.mockserver.model.DataPlaneCopies.copyForTransformer;
import static org.mockserver.model.DeepCopyWalker.describeFailures;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonBody.json;

/**
 * The result rules the data-plane hooks are built on, run without a server: what a hook can change, what it cannot,
 * and when its result counts as a change.
 */
public class DataPlaneCopiesTest {

    private final Configuration configuration = configuration().logLevel(Level.WARN);
    private final MockServerLogger mockServerLogger = new MockServerLogger(configuration, DataPlaneCopiesTest.class);
    private final List<HttpState> states = new ArrayList<>();

    @After
    public void stopStates() {
        states.forEach(HttpState::stop);
    }

    // mutation isolation

    @Test
    public void shouldLeaveTheRequestUnchangedWhateverIsDoneToEveryMemberOfItsCopy() {
        for (Map.Entry<String, Supplier<Body<?>>> body : DataPlaneCopyFixtures.bodies().entrySet()) {
            for (Map.Entry<String, Supplier<Object>> remoteAddress : DataPlaneCopyFixtures.remoteAddresses().entrySet()) {
                Supplier<HttpRequest> fixture = () -> DataPlaneCopyFixtures.request(body.getValue().get(), remoteAddress.getValue().get());
                HttpRequest original = fixture.get();
                String before = original.toString();

                DeepMutator.mutateEverything(copyForFilter(original));

                String description = body.getKey() + " with a " + remoteAddress.getKey() + " remote address";
                assertSameValues(description, original, fixture.get());
                assertThat(description, original, is(fixture.get()));
                assertThat(description, original.toString(), is(before));
            }
        }
    }

    @Test
    public void shouldLeaveTheResponseUnchangedWhateverIsDoneToEveryMemberOfItsCopy() {
        for (Map.Entry<String, Supplier<Body<?>>> body : DataPlaneCopyFixtures.bodies().entrySet()) {
            if (body.getValue().get() instanceof BodyWithContentType) {
                Supplier<HttpResponse> fixture = () -> DataPlaneCopyFixtures.response((BodyWithContentType<?>) body.getValue().get());
                HttpResponse original = fixture.get();
                String before = original.toString();

                DeepMutator.mutateEverything(copyForTransformer(original));

                assertSameValues(body.getKey(), original, fixture.get());
                assertThat(body.getKey(), original, is(fixture.get()));
                assertThat(body.getKey(), original.toString(), is(before));
            }
        }
    }

    @Test
    public void shouldLeaveTheStoredExpectationAndTheNextResponseUnchangedWhenTheServedResponsesCopyIsMutated() {
        HttpState httpState = httpState();
        httpState.add(new Expectation(request("/stored")).withId("stored").thenRespond(storedResponse()));
        HttpActionHandler actionHandler = new HttpActionHandler(configuration, null, httpState, null, null);

        HttpResponse served = serve(actionHandler, request("/stored").withMethod("GET"));
        DeepMutator.mutateEverything(copyForTransformer(served));
        HttpResponse next = serve(actionHandler, request("/stored").withMethod("GET"));

        assertThat(next, is(served));
        assertThat(next.getBodyAsString(), is(storedResponse().getBodyAsString()));
        assertThat(next.getHeader("x-stored"), contains("one", "two"));
        HttpResponse stored = httpState.getRequestMatchers().retrieveActiveExpectations(null).get(0).getHttpResponse();
        HttpResponse expected = storedResponse();
        expected.setExpectationId("stored");
        assertThat(stored, is(expected));
        assertSameValues("stored expectation", stored, expected);
    }

    @Test
    public void shouldChangeTheNextResponseWhenTheServedResponseItselfIsMutated() {
        HttpState httpState = httpState();
        httpState.add(new Expectation(request("/stored")).withId("stored").thenRespond(storedResponse()));
        HttpActionHandler actionHandler = new HttpActionHandler(configuration, null, httpState, null, null);

        HttpResponse served = serve(actionHandler, request("/stored").withMethod("GET"));
        DeepMutator.mutateEverything(served);
        HttpResponse next = serve(actionHandler, request("/stored").withMethod("GET"));

        // what a hook given the served response itself, rather than a copy, could do to the next one
        assertThat(DeepCopyWalker.sameValues(next, storedResponse()), is(false));
    }

    // re-stamping the fields the transport owns

    @Test
    public void shouldRestampEachFieldTheTransportOwns() {
        Map<String, Consumer<HttpRequest>> edits = new java.util.LinkedHashMap<>();
        edits.put("streamId", request -> request.withStreamId(99));
        edits.put("keepAlive", request -> request.withKeepAlive(false));
        edits.put("secure", request -> request.withSecure(false));
        edits.put("protocol", request -> request.withProtocol(Protocol.HTTP_1_1));
        edits.put("clientCertificateChain", request -> request.withClientCertificateChain(null));
        edits.put("clientCertificateChain in place", request -> request.getClientCertificateChain().clear());
        edits.put("clientCertificate", request -> request.withClientCertificate(ClientCertificate.clientCertificate().withSubject("CN=other")));
        edits.put("jwt", request -> request.withJwt(Jwt.jwt().withIssuer("other")));
        edits.put("clientIp", request -> request.withClientIp("192.168.0.0/16"));
        edits.put("remoteAddress", request -> request.withRemoteAddress("192.0.2.1:1"));
        edits.put("remoteAddress bytes", request -> request.withRemoteAddressBytes(new byte[]{(byte) 192, 0, 2, 1}));
        edits.put("localAddress", request -> request.withLocalAddress("192.0.2.2:2"));
        edits.put("logCorrelationId", request -> request.withLogCorrelationId("other"));
        edits.put("receivedTimestamp", request -> request.withReceivedTimestamp(1L));
        edits.put("respondBeforeBody", request -> request.withRespondBeforeBody(true));
        edits.put("undecodableQueryString", request -> request.withUndecodableQueryString(null));
        edits.put("loadGenerated", request -> request.setLoadGenerated(false));
        edits.put("controlPlaneRequest", request -> DataPlaneCopyFixtures.assignField(request, "controlPlaneRequest", false));
        edits.put("maskedCredentialValues", request -> request.withMaskedCredentialValues(null));

        Map<String, Function<HttpRequest, Object>> fields = new java.util.LinkedHashMap<>();
        fields.put("streamId", HttpRequest::getStreamId);
        fields.put("keepAlive", HttpRequest::isKeepAlive);
        fields.put("secure", HttpRequest::isSecure);
        fields.put("protocol", HttpRequest::getProtocol);
        fields.put("clientCertificateChain", HttpRequest::getClientCertificateChain);
        fields.put("clientCertificate", HttpRequest::getClientCertificate);
        fields.put("jwt", HttpRequest::getJwt);
        fields.put("clientIp", HttpRequest::getClientIp);
        fields.put("remoteAddress", HttpRequest::getRemoteAddress);
        fields.put("remoteAddress bytes", request -> java.util.Arrays.toString(request.getRemoteAddressBytes()));
        fields.put("localAddress", HttpRequest::getLocalAddress);
        fields.put("logCorrelationId", HttpRequest::getLogCorrelationId);
        fields.put("receivedTimestamp", HttpRequest::getReceivedTimestamp);
        fields.put("respondBeforeBody", HttpRequest::getRespondBeforeBody);
        fields.put("undecodableQueryString", HttpRequest::getUndecodableQueryString);
        fields.put("loadGenerated", HttpRequest::isLoadGenerated);
        fields.put("controlPlaneRequest", HttpRequest::isControlPlaneRequest);
        fields.put("maskedCredentialValues", HttpRequest::getMaskedCredentialValues);

        for (Map.Entry<String, Consumer<HttpRequest>> edit : edits.entrySet()) {
            HttpRequest received = received();
            HttpRequest result = copyForFilter(received);
            edit.getValue().accept(result);

            HttpRequest accepted = acceptFilteredRequest(result, received, received);

            for (Map.Entry<String, Function<HttpRequest, Object>> field : fields.entrySet()) {
                assertThat("after a filter changed " + edit.getKey() + ", " + field.getKey(), field.getValue().apply(accepted), is(field.getValue().apply(received())));
            }
            assertThat("a change only to " + edit.getKey() + " is no change", changed(accepted, received), is(false));
        }
    }

    @Test
    public void shouldNotShareTheRestampedMembersWithTheReceivedRequest() {
        HttpRequest received = received();
        received.getRemoteAddressBytes();

        HttpRequest accepted = acceptFilteredRequest(copyForFilter(received), received, received);

        assertThat(accepted.getClientCertificateChain(), not(sameInstance(received.getClientCertificateChain())));
        assertThat(accepted.getClientCertificateChain().get(0), not(sameInstance(received.getClientCertificateChain().get(0))));
        assertThat(accepted.getMaskedCredentialValues(), not(sameInstance(received.getMaskedCredentialValues())));
        assertThat(DataPlaneCopyFixtures.remoteAddressOf(accepted), not(sameInstance(DataPlaneCopyFixtures.remoteAddressOf(received))));
        assertThat(accepted.getRemoteAddressBytes(), is(received.getRemoteAddressBytes()));
    }

    @Test
    public void shouldLetAFilterChangeTheForwardHostAndPortButNotWhetherOverTls() {
        HttpRequest overTls = received();
        HttpRequest plain = received().withSecure(false);
        HttpRequest toPlainHttp = copyForFilter(overTls).withSocketAddress("elsewhere.example", 9000, SocketAddress.Scheme.HTTP);
        HttpRequest toHttps = copyForFilter(plain).withSocketAddress("elsewhere.example", 9443, SocketAddress.Scheme.HTTPS);
        HttpRequest noScheme = copyForFilter(overTls).withSocketAddress(new SocketAddress().withHost("elsewhere.example").withPort(443));

        HttpRequest acceptedToPlainHttp = acceptFilteredRequest(toPlainHttp, overTls, overTls);
        HttpRequest acceptedToHttps = acceptFilteredRequest(toHttps, plain, plain);
        HttpRequest acceptedNoScheme = acceptFilteredRequest(noScheme, overTls, overTls);

        assertThat(acceptedToPlainHttp.getSocketAddress(), is(new SocketAddress().withHost("elsewhere.example").withPort(9000).withScheme(SocketAddress.Scheme.HTTPS)));
        assertThat("the client connected over TLS", acceptedToPlainHttp.isSecure(), is(true));
        assertThat(changed(acceptedToPlainHttp, overTls), is(true));
        assertThat(acceptedToHttps.getSocketAddress(), is(new SocketAddress().withHost("elsewhere.example").withPort(9443).withScheme(SocketAddress.Scheme.HTTP)));
        assertThat("the client connected in cleartext", acceptedToHttps.isSecure(), is(false));
        assertThat("a TLS request is never forwarded in cleartext", acceptedNoScheme.isSecure(), is(true));
        assertThat(acceptedNoScheme.getSocketAddress().getScheme(), is(SocketAddress.Scheme.HTTPS));
    }

    @Test
    public void shouldRestampSecureWhenTheFilterLeftTheSocketAddressAsReceived() {
        HttpRequest received = received().withSocketAddress("upstream.example", 80, SocketAddress.Scheme.HTTP);
        HttpRequest result = copyForFilter(received).withSecure(false);

        assertThat(acceptFilteredRequest(result, received, received).isSecure(), is(true));
    }

    // proceed, proceedWith and fail-open

    @Test
    public void shouldContinueWithTheInputUnchangedOnProceedAfterTheFilterEditedItsCopyInPlace() {
        HttpRequest input = received();
        Headers inputHeaders = input.getHeaders();
        HttpRequest copy = copyForFilter(input);
        copy.getHeaders().withEntry("x-added", "1");
        copy.getQueryStringParameters().withEntry("added", "1");
        copy.getCookies().withEntry("added", "1");

        // proceed(): the chain continues with the filter's input, so the edits to its copy are discarded
        HttpRequest next = input;

        assertThat(next, is(received()));
        assertSameValues("after proceed", next, received());
        assertThat(next.getHeaders(), sameInstance(inputHeaders));
        assertThat(next.containsHeader("x-added"), is(false));

        // proceedWith(copy): the chain continues with the edited copy
        HttpRequest accepted = acceptFilteredRequest(copy, input, input);
        assertThat(changed(accepted, input), is(true));
        assertThat(accepted.getFirstHeader("x-added"), is("1"));
        assertThat(accepted.getFirstQueryStringParameter("added"), is("1"));
    }

    @Test
    public void shouldFailOpenWithThePristineInputOfAFilterThatEditedItsCopyThenFailed() {
        HttpRequest input = received();
        Headers inputHeaders = input.getHeaders();
        Body<?> inputBody = input.getBody();
        HttpRequest copy = copyForFilter(input);

        HttpRequest next;
        try {
            DeepMutator.mutateEverything(copy);
            throw new IllegalStateException("filter failed");
        } catch (IllegalStateException failed) {
            next = input;
        }

        assertThat(next, is(received()));
        assertSameValues("fail-open input", next, received());
        assertThat(next.getHeaders(), sameInstance(inputHeaders));
        assertThat(next.getBody(), sameInstance(inputBody));
    }

    @Test
    public void shouldFailOpenWithThePristineInputOfATransformerThatEditedItsCopyThenFailed() {
        HttpResponse input = storedResponse();
        HttpResponse copy = copyForTransformer(input);

        HttpResponse written;
        try {
            DeepMutator.mutateEverything(copy);
            throw new IllegalStateException("transformer failed");
        } catch (IllegalStateException failed) {
            written = input;
        }

        assertThat(written, is(storedResponse()));
        assertSameValues("fail-open input", written, storedResponse());
    }

    // change detection

    @Test
    public void shouldSeeNoChangeFromAnIdentityFilterOrTransformerWhateverTheBody() {
        for (Map.Entry<String, Supplier<Body<?>>> body : DataPlaneCopyFixtures.bodies().entrySet()) {
            for (Map.Entry<String, Supplier<Object>> remoteAddress : DataPlaneCopyFixtures.remoteAddresses().entrySet()) {
                HttpRequest input = DataPlaneCopyFixtures.request(body.getValue().get(), remoteAddress.getValue().get());

                HttpRequest accepted = acceptFilteredRequest(copyForFilter(input), input, input);

                String description = body.getKey() + " with a " + remoteAddress.getKey() + " remote address";
                assertThat(description, changed(accepted, input), is(false));
                assertThat(description, accepted.isBodyAsReceived(), is(true));
                assertThat(description, accepted.getOriginalBody(), is(input.getOriginalBody()));
                assertThat(description, accepted.toString(), is(input.toString()));
            }
            if (body.getValue().get() instanceof BodyWithContentType) {
                HttpResponse input = DataPlaneCopyFixtures.response((BodyWithContentType<?>) body.getValue().get());

                HttpResponse accepted = acceptTransformedResponse(copyForTransformer(input), input);

                assertThat(body.getKey(), changed(accepted, input), is(false));
                assertThat(body.getKey(), accepted.toString(), is(input.toString()));
            }
        }
    }

    @Test
    public void shouldSeeAChangeMadeInPlaceAndClearTheRecordOfTheBodyAsReceived() {
        HttpRequest input = DataPlaneCopyFixtures.request(DataPlaneCopyFixtures.bodies().get("ParameterBody").get(), "10.0.0.1:1");
        HttpRequest copy = copyForFilter(input);
        ((ParameterBody) copy.getBody()).getValue().withEntry("added", "1");

        HttpRequest accepted = acceptFilteredRequest(copy, input, input);

        assertThat(changed(accepted, input), is(true));
        assertThat(accepted.isBodyAsReceived(), is(false));
        assertThat(accepted.getOriginalBody(), nullValue());
        assertThat(accepted.getBodyAsOriginalRawBytes(), is(accepted.getBodyAsRawBytes()));
    }

    @Test
    public void shouldKeepTheRecordOfTheBodyAsReceivedWhenTheBodyIsReplacedByAnEqualOne() {
        HttpRequest input = DataPlaneCopyFixtures.request(DataPlaneCopyFixtures.bodies().get("JsonBody").get(), "10.0.0.1:1");
        HttpRequest copy = copyForFilter(input);
        copy.withBody(copy.getBody().copy());
        assertThat("a new body instance is no longer the body as received", copy.isBodyAsReceived(), is(false));

        HttpRequest accepted = acceptFilteredRequest(copy, input, input);

        assertThat(changed(accepted, input), is(false));
        assertThat(accepted.isBodyAsReceived(), is(true));
        assertThat(accepted.getOriginalBody(), is(input.getOriginalBody()));
        assertThat(accepted.getOriginalBody(), not(sameInstance(input.getOriginalBody())));
    }

    @Test
    public void shouldResetAHashCachedBeforeAnInPlaceEditOfANestedMember() {
        HttpRequest input = request("/path").withMethod("POST").withBody(new ParameterBody(new Parameters(Parameter.param("a", "1"))));
        HttpRequest copy = copyForFilter(input);
        Parameters parameters = ((ParameterBody) copy.getBody()).getValue();
        parameters.withEntry("b", "2");
        copy.hashCode();
        // reverted in place: the content is the input's again, but the hashes cached after the first edit are stale
        parameters.remove("b");
        assertThat("the stale hash makes the copy look changed", copy.equals(input), is(false));

        HttpRequest accepted = acceptFilteredRequest(copy, input, input);

        assertThat(changed(accepted, input), is(false));
        assertThat(accepted.hashCode(), is(input.hashCode()));
        assertThat(accepted.getBody().hashCode(), is(input.getBody().hashCode()));
        assertThat(DataPlaneCopies.normalise(copy).hashCode(), is(input.hashCode()));
    }

    @Test
    public void shouldDropATextViewCachedBeforeTheBytesWereEditedInPlace() {
        HttpRequest input = request("/path").withMethod("POST").withBody(json("{\"a\":1}"));
        HttpRequest copy = copyForFilter(input);
        JsonBody body = (JsonBody) copy.getBody();
        body.getValue();
        body.getRawBytes()[5] = '2';

        HttpRequest accepted = acceptFilteredRequest(copy, input, input);

        assertThat(accepted.getBodyAsString(), is("{\"a\":2}"));
        assertThat(changed(accepted, input), is(true));
    }

    @Test
    public void shouldKeepATextTheBytesCannotGiveBack() {
        StringBody blank = new StringBody("   ");
        StringBody unencodable = new StringBody("café", MediaType.parse("text/plain; charset=us-ascii"));

        assertThat(blank.copy().getValue(), is(blank.getValue()));
        assertThat(blank.copy(), is(blank));
        assertThat(unencodable.copy().getValue(), is(unencodable.getValue()));
        assertThat(unencodable.copy(), is(unencodable));
    }

    @Test
    public void shouldShowATransformerNoStreamingBodyAndRestoreItAfterwards() {
        StreamingBody streamingBody = new StreamingBody(1024);
        HttpResponse input = storedResponse().withStreamingBody(streamingBody);
        HttpResponse copy = copyForTransformer(input);
        assertThat(copy.getStreamingBody(), nullValue());

        HttpResponse accepted = acceptTransformedResponse(copy.withHeader("x-transformed", "1"), input);

        assertThat(accepted.getStreamingBody(), sameInstance(streamingBody));
        assertThat(changed(accepted, input), is(true));
        assertThat(accepted, not(sameInstance(copy)));
    }

    private HttpRequest received() {
        try {
            HttpRequest received = request("/received")
                .withMethod("POST")
                .withBody("body")
                .withHeader("x-header", "one")
                .withQueryStringParameter("q", "1")
                .withCookie("c", "1")
                .withKeepAlive(true)
                .withSecure(true)
                .withProtocol(Protocol.HTTP_2)
                .withStreamId(3)
                .withClientCertificateChain(new ArrayList<>(List.of(X509Certificate.x509Certificate().withSubjectDistinguishedName("CN=client"))))
                .withLocalAddress("127.0.0.1:1080")
                .withReceivedRemoteAddress(new HttpRequest.RemoteAddress("10.0.0.2:5000", InetAddress.getByAddress(new byte[]{10, 0, 0, 2})))
                .withRespondBeforeBody(false)
                .withUndecodableQueryString("%zz")
                .setLoadGenerated(true)
                .markControlPlaneRequest()
                .withMaskedCredentialValues(new ArrayList<>(List.of("secret")));
            received.withLogCorrelationId("correlation-id").withReceivedTimestamp(123L);
            return received;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static HttpResponse storedResponse() {
        return response()
            .withStatusCode(200)
            .withHeader("x-stored", "one", "two")
            .withCookie("stored", "cookie")
            .withTrailers(new Header("x-trailer", "t"))
            .withBody(json("{\"stored\":[1,2,3]}"));
    }

    private HttpState httpState() {
        HttpState httpState = new HttpState(configuration, mockServerLogger, new Scheduler(configuration, mockServerLogger, true));
        states.add(httpState);
        return httpState;
    }

    private HttpResponse serve(HttpActionHandler actionHandler, HttpRequest request) {
        CapturingResponseWriter writer = new CapturingResponseWriter(configuration, mockServerLogger);
        actionHandler.processAction(request, writer, null, Set.of("localhost", "127.0.0.1"), false, true);
        assertThat(writer.response, not(nullValue()));
        return writer.response;
    }

    private static void assertSameValues(String description, Object actual, Object expected) {
        List<String> failures = new DeepCopyWalker(DeepCopyWalker.Mode.EQUAL).walk(expected, actual, description).failures();
        assertThat(describeFailures(failures), failures.isEmpty(), is(true));
    }

    private static final class CapturingResponseWriter extends ResponseWriter {
        private HttpResponse response;

        private CapturingResponseWriter(Configuration configuration, MockServerLogger mockServerLogger) {
            super(configuration, mockServerLogger);
        }

        @Override
        public void sendResponse(HttpRequest request, HttpResponse response) {
            this.response = response;
        }
    }
}

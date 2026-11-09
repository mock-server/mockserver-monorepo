package org.mockserver.inprocess;

import org.junit.After;
import org.junit.Test;
import org.mockserver.extension.ResponseTransformer;
import org.mockserver.extension.TransformContext;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.HttpTemplate;
import org.mockserver.socket.PortFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.Expectation.when;
import static org.mockserver.model.BinaryResponse.binaryResponse;
import static org.mockserver.model.FileBody.file;
import static org.mockserver.model.GrpcStreamResponse.grpcStreamResponse;
import static org.mockserver.model.HttpChaosProfile.httpChaosProfile;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.HttpSseResponse.sseResponse;
import static org.mockserver.model.HttpTemplate.template;
import static org.mockserver.model.HttpWebSocketResponse.webSocketResponse;
import static org.mockserver.model.SseEvent.sseEvent;
import static org.mockserver.model.WebSocketMessage.webSocketMessage;

/**
 * Each response MockServer makes itself reaches a response transformer with {@link TransformContext#isGenerated()}
 * true, and an expectation's own response with it false; the transformer records it in {@code x-generated}.
 * The rate-limit cases are in {@link InProcessMockServerGeneratedRateLimitResponseTest}, which runs sequentially.
 */
public class InProcessMockServerGeneratedResponseTest {

    private InProcessMockServer mockServer;

    public static class GeneratedRecorder implements ResponseTransformer {
        @Override
        public String name() {
            return "generated-recorder";
        }

        @Override
        public HttpResponse transform(HttpRequest request, HttpResponse response, TransformContext context) {
            return response.withHeader("x-generated", String.valueOf(context.isGenerated()));
        }
    }

    @After
    public void close() {
        if (mockServer != null) {
            mockServer.close();
        }
    }

    private void start(boolean templateFailureAsServerError) {
        mockServer = new InProcessMockServer(configuration()
            .templateFailureAsServerError(templateFailureAsServerError)
            .extensions(GeneratedRecorder.class));
    }

    @Test
    public void shouldNotMarkAnExpectationsOwnResponse() {
        start(true);
        mockServer.upsert(when(request("/generated/mocked")).thenRespond(response("mocked body")));

        HttpResponse response = mockServer.handle(request("/generated/mocked"));

        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getFirstHeader("x-generated"), is("false"));
    }

    @Test
    public void shouldMarkTheNoMatchNotFound() {
        start(true);

        assertGenerated(mockServer.handle(request("/generated/missing")), 404);
    }

    @Test
    public void shouldMarkAChaosErrorResponse() {
        start(true);
        mockServer.upsert(when(request("/generated/chaos"))
            .thenRespond(response("replaced by chaos"))
            .withChaos(httpChaosProfile().withErrorStatus(503).withErrorProbability(1.0)));

        assertGenerated(mockServer.handle(request("/generated/chaos")), 503);
    }

    @Test
    public void shouldMarkAFailedTemplateAnsweredWithAServerError() {
        start(true);
        mockServer.upsert(when(request("/generated/template")).thenRespond(template(HttpTemplate.TemplateType.VELOCITY, "#foreach(")));

        assertGenerated(mockServer.handle(request("/generated/template")), 500);
    }

    @Test
    public void shouldMarkAFailedTemplateAnsweredWithNotFound() {
        start(false);
        mockServer.upsert(when(request("/generated/template")).thenRespond(template(HttpTemplate.TemplateType.VELOCITY, "#foreach(")));

        assertGenerated(mockServer.handle(request("/generated/template")), 404);
    }

    @Test
    public void shouldMarkTheFailureForAResponseFileThatCannotBeRead() {
        start(true);
        mockServer.upsert(when(request("/generated/file")).thenRespond(response().withBody(file("does/not/exist/in-process-generated.json"))));

        assertGenerated(mockServer.handle(request("/generated/file")), 500);
    }

    @Test
    public void shouldMarkTheNotImplementedForABinaryActionMatchedByAnHttpRequest() {
        start(true);
        mockServer.upsert(when(request("/generated/binary")).thenRespondWithBinary(binaryResponse(new byte[]{1, 2, 3})));

        assertGenerated(mockServer.handle(request("/generated/binary")), 501);
    }

    @Test
    public void shouldMarkTheAnswerForAnSseActionWithNoConnection() {
        start(true);
        mockServer.upsert(when(request("/generated/sse")).thenRespondWithSse(sseResponse().withEvent(sseEvent().withData("first"))));

        assertGenerated(mockServer.handle(request("/generated/sse")), 501);
    }

    @Test
    public void shouldMarkTheAnswerForAWebSocketActionWithNoConnection() {
        start(true);
        mockServer.upsert(when(request("/generated/websocket")).thenRespondWithWebSocket(webSocketResponse().withMessage(webSocketMessage("first"))));

        assertGenerated(mockServer.handle(request("/generated/websocket")), 501);
    }

    @Test
    public void shouldMarkTheAnswerForAGrpcStreamActionWithNoConnection() {
        start(true);
        mockServer.upsert(when(request("/generated/grpc-stream")).thenRespondWithGrpcStream(grpcStreamResponse()));

        assertGenerated(mockServer.handle(request("/generated/grpc-stream")), 501);
    }

    @Test
    public void shouldMarkTheAnswerForAForwardThatFails() {
        start(true);
        // nothing listens on a free port, so the forward is refused
        mockServer.upsert(when(request("/generated/forward")).thenForward(forward().withHost("127.0.0.1").withPort(PortFactory.findFreePort())));

        assertGenerated(mockServer.handle(request("/generated/forward")), 502);
    }

    static void assertGenerated(HttpResponse response, int status) {
        assertThat(response.getStatusCode(), is(status));
        assertThat("status " + response.getStatusCode() + " " + response.getBodyAsString(), response.getFirstHeader("x-generated"), is("true"));
    }
}

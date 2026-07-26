package org.mockserver.netty.integration.mock;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.action.ExpectationResponseCallback;
import org.mockserver.model.FileBody;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.MediaType;
import org.mockserver.netty.MockServer;
import org.mockserver.scheduler.Scheduler;

import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpClassCallback.callback;
import static org.mockserver.model.HttpOverrideForwardedRequest.forwardOverriddenRequest;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.HttpTemplate.template;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Proves a {@code FILE} response body with NO {@code templateType} serves the file CONTENTS over a real
 * socket on the action paths that do NOT go through {@code HttpResponseActionHandler} — issue #2450.
 *
 * <p>A {@link FileBody}'s value IS its {@code filePath}, so anything that writes the body generically
 * put the PATH on the wire. The reported case — a static {@code httpResponse} with a FILE body — was
 * fixed first; three other ways to produce the very same body bypass that path:</p>
 * <ul>
 *   <li>a <b>class callback</b> (and equally an object/WebSocket callback) returning the response,</li>
 *   <li>a <b>response template</b> whose rendered JSON declares {@code "type": "FILE"},</li>
 *   <li>a forward <b>{@code responseOverride}</b>.</li>
 * </ul>
 *
 * <p>{@code FileBodyMaterialiser}, invoked from the two response-write funnels in
 * {@code HttpActionHandler}, closed the first two. The {@code responseOverride} case needed one thing
 * more: the override replaces the BODY but the response still carried the UPSTREAM response's
 * {@code Content-Length}, so the correct file contents were then truncated on the wire to the length of
 * the upstream body they replaced. That is asserted here because it is only observable end-to-end —
 * the response object is correct at every layer above the encoder, and only a real client reading a
 * real socket sees the short read.</p>
 *
 * <p>These tests drive a REAL server and assert on the bytes the client actually receives, so they fail
 * closed if any of these paths regresses back to emitting the path or to a mismatched framing — neither
 * of which unit coverage of the DTO/serializer, or of the action handlers, can catch.</p>
 */
public class FileBodyVerbatimNonStaticActionsIntegrationTest {

    private static final String TEXT_FILE = "org/mockserver/netty/filebody/verbatim_file_body.xml";
    private static final String BINARY_FILE = "org/mockserver/netty/filebody/verbatim_binary_body.png";

    /**
     * The fixture deliberately contains a Mustache placeholder. With no template engine configured it
     * must arrive UNPROCESSED — that is what "serve file verbatim" means, and it also proves the body
     * really came from the file rather than from any templating step.
     */
    private static final String TEXT_FILE_CONTENTS = "<tag>hello{{ request.path }}</tag>";

    /** Not valid UTF-8: charset-decoding these bytes would silently corrupt them. */
    private static final byte[] BINARY_FILE_CONTENTS =
        {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0x00, (byte) 0xff, (byte) 0xfe, 0x01, (byte) 0x80, 0x7f, (byte) 0xc3, 0x28};

    private static MockServer mockServer;
    private static MockServer upstreamServer;
    private static MockServerClient mockServerClient;
    private static MockServerClient upstreamClient;
    private static NettyHttpClient httpClient;
    private static EventLoopGroup clientEventLoopGroup;

    /** Returns a FILE body with NO templateType — the case the issue reports. */
    public static class FileBodyCallback implements ExpectationResponseCallback {
        @Override
        public HttpResponse handle(HttpRequest httpRequest) {
            return response()
                .withStatusCode(200)
                .withBody(new FileBody(TEXT_FILE, MediaType.parse("application/xml")));
        }
    }

    /** Returns a FILE body referencing a BINARY file, again with NO templateType. */
    public static class BinaryFileBodyCallback implements ExpectationResponseCallback {
        @Override
        public HttpResponse handle(HttpRequest httpRequest) {
            return response()
                .withStatusCode(200)
                .withBody(new FileBody(BINARY_FILE, MediaType.parse("image/png")));
        }
    }

    @BeforeClass
    public static void startServerAndClient() {
        clientEventLoopGroup = new NioEventLoopGroup(3, new Scheduler.SchedulerThreadFactory(
            FileBodyVerbatimNonStaticActionsIntegrationTest.class.getSimpleName() + "-eventLoop"));
        httpClient = new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, null, false);
        upstreamServer = new MockServer();
        upstreamClient = new MockServerClient("localhost", upstreamServer.getLocalPort());
        mockServer = new MockServer();
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
    }

    @AfterClass
    public static void stopServerAndClient() {
        stopQuietly(mockServerClient);
        stopQuietly(upstreamClient);
        stopQuietly(mockServer);
        stopQuietly(upstreamServer);
        if (clientEventLoopGroup != null) {
            clientEventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
        }
    }

    @Test
    public void classCallbackServesFileContentsNotFilePath() throws Exception {
        // given - a class callback returning a FILE body with no templateType
        mockServerClient
            .when(request().withPath("/callback/text"))
            .respond(callback().withCallbackClass(FileBodyCallback.class));

        // when
        HttpResponse response = send("/callback/text");

        // then - the file CONTENTS arrive, with the Mustache placeholder unprocessed
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), is(TEXT_FILE_CONTENTS));
    }

    @Test
    public void classCallbackServesBinaryFileBytesIntact() throws Exception {
        // given - a class callback returning a FILE body for a file that is not valid UTF-8
        mockServerClient
            .when(request().withPath("/callback/binary"))
            .respond(callback().withCallbackClass(BinaryFileBodyCallback.class));

        // when
        HttpResponse response = send("/callback/binary");

        // then - the exact bytes survive the round trip
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsRawBytes(), is(BINARY_FILE_CONTENTS));
    }

    @Test
    public void responseTemplateServesFileContentsNotFilePath() throws Exception {
        // given - a response template whose rendered response declares a FILE body with no templateType.
        // The template renders the response envelope; the FILE body inside it is NOT template-rendered.
        mockServerClient
            .when(request().withPath("/template"))
            .respond(
                template(org.mockserver.model.HttpTemplate.TemplateType.MUSTACHE)
                    .withTemplate(
                        "{" +
                            "'statusCode': 200," +
                            "'body': { 'type': 'FILE', 'filePath': '" + TEXT_FILE + "', 'contentType': 'application/xml' }" +
                            "}"
                    )
            );

        // when
        HttpResponse response = send("/template");

        // then
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), is(TEXT_FILE_CONTENTS));
    }

    @Test
    public void forwardResponseOverrideServesFileContentsNotFilePath() throws Exception {
        // given - an upstream that responds with something the override will replace wholesale
        upstreamClient
            .when(request().withPath("/upstream"))
            .respond(response().withStatusCode(200).withBody("from upstream"));

        // and - a forward whose responseOverride carries a FILE body with no templateType
        mockServerClient
            .when(request().withPath("/override"))
            .forward(
                forwardOverriddenRequest(
                    request()
                        .withPath("/upstream")
                        .withHeader(HOST.toString(), "localhost:" + upstreamServer.getLocalPort()),
                    response()
                        .withStatusCode(200)
                        .withBody(new FileBody(TEXT_FILE, MediaType.parse("application/xml")))
                )
            );

        // when
        HttpResponse response = send("/override");

        // then
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), is(TEXT_FILE_CONTENTS));
    }

    private HttpResponse send(String path) throws Exception {
        return httpClient.sendRequest(
            request()
                .withMethod("GET")
                .withHeader(HOST.toString(), "localhost:" + mockServer.getLocalPort())
                .withPath(path)
        ).get(20, TimeUnit.SECONDS);
    }
}

package org.mockserver.mock.action.http;

import com.google.protobuf.Descriptors;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.configuration.Configuration;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcForwardTranslator;
import org.mockserver.grpc.GrpcJsonMessageConverter;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpForward;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Protocol;

import java.net.InetSocketAddress;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Verifies that {@link HttpForwardActionHandler} (the matched {@code FORWARD} path), once wired with a
 * {@link GrpcProtoDescriptorStore}, re-encodes a decoded gRPC request to protobuf frames for the upstream
 * call and decodes the upstream framed response back to JSON — the record/replay round-trip.
 */
public class HttpForwardActionHandlerGrpcTest {

    private static final String SERVICE = "com.example.grpc.GreetingService";

    private NettyHttpClient mockHttpClient;
    private HttpForwardActionHandler handler;
    private GrpcProtoDescriptorStore store;
    private GrpcJsonMessageConverter converter;
    private Descriptors.MethodDescriptor greetingMethod;

    @Before
    public void setUp() {
        mockHttpClient = mock(NettyHttpClient.class);
        store = new GrpcProtoDescriptorStore(new MockServerLogger());
        store.loadDescriptorSetFromPath(Paths.get("src/test/resources/grpc/greeting.dsc"));
        converter = store.getConverter();
        greetingMethod = store.getMethod(SERVICE, "Greeting");
        handler = new HttpForwardActionHandler(mock(MockServerLogger.class), Configuration.configuration(), mockHttpClient);
        handler.setGrpcDescriptorStore(store);
    }

    private HttpRequest decodedGrpcRequest() {
        return request()
            .withMethod("POST")
            .withPath("/" + SERVICE + "/Greeting")
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .withHeader(GrpcForwardTranslator.SERVICE_HEADER, SERVICE)
            .withHeader(GrpcForwardTranslator.METHOD_HEADER, "Greeting")
            .withBody("{\"name\":\"Tom\"}");
    }

    private HttpResponse upstreamFramedResponse() {
        byte[] framed = GrpcFrameCodec.encode(converter.toProtobuf("{\"greeting\":\"Hello Tom\"}", greetingMethod.getOutputType()));
        return response()
            .withStatusCode(200)
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .withHeader(GrpcStatusMapper.GRPC_STATUS_HEADER, "0")
            .withBody(framed);
    }

    @Test
    public void shouldReEncodeGrpcRequestToProtobufBeforeUpstreamCall() throws Exception {
        CompletableFuture<HttpResponse> future = CompletableFuture.completedFuture(upstreamFramedResponse());
        when(mockHttpClient.sendRequest(any(HttpRequest.class), any(InetSocketAddress.class))).thenReturn(future);

        handler.handle(forward().withHost("upstream").withPort(9090).withScheme(HttpForward.Scheme.HTTP), decodedGrpcRequest())
            .getHttpResponse().get();

        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).sendRequest(sent.capture(), any(InetSocketAddress.class));
        HttpRequest upstreamRequest = sent.getValue();

        // request forwarded upstream must be gRPC-framed protobuf over HTTP/2, with helper headers stripped
        assertThat(upstreamRequest.getFirstHeader("content-type"), is(GrpcStatusMapper.GRPC_CONTENT_TYPE));
        assertThat(upstreamRequest.getProtocol(), is(Protocol.HTTP_2));
        assertThat(upstreamRequest.getHeader("te"), contains("trailers"));
        assertThat(upstreamRequest.getFirstHeader(GrpcForwardTranslator.SERVICE_HEADER), is(emptyOrNullString()));
        List<byte[]> messages = GrpcFrameCodec.decode(upstreamRequest.getBodyAsRawBytes());
        assertThat(messages, hasSize(1));
        assertThat(converter.toJson(messages.get(0), greetingMethod.getInputType()), containsString("Tom"));
    }

    @Test
    public void shouldDecodeUpstreamFramedResponseBackToJson() throws Exception {
        CompletableFuture<HttpResponse> future = CompletableFuture.completedFuture(upstreamFramedResponse());
        when(mockHttpClient.sendRequest(any(HttpRequest.class), any(InetSocketAddress.class))).thenReturn(future);

        HttpResponse response = handler
            .handle(forward().withHost("upstream").withPort(9090).withScheme(HttpForward.Scheme.HTTP), decodedGrpcRequest())
            .getHttpResponse().get();

        // response (used for both the client write and the recorded FORWARDED_REQUEST) is decoded JSON,
        // re-stamped so GrpcToHttpResponseHandler can re-frame it, and carries the upstream status
        // through verbatim as a numeric grpc-status (NOT converted to a status-name, which would
        // collapse any code outside the GrpcStatusCode enum to UNKNOWN)
        assertThat(response.getBodyAsString(), containsString("Hello Tom"));
        assertThat(response.getFirstHeader(GrpcForwardTranslator.SERVICE_HEADER), is(SERVICE));
        assertThat(response.getFirstHeader(GrpcForwardTranslator.METHOD_HEADER), is("Greeting"));
        assertThat(response.getFirstHeader(GrpcStatusMapper.GRPC_STATUS_HEADER), is("0"));
    }

    @Test
    public void shouldForwardAnUndecodedGrpcCallOverHttp2WithTeTrailers() throws Exception {
        CompletableFuture<HttpResponse> future = CompletableFuture.completedFuture(upstreamFramedResponse());
        when(mockHttpClient.sendRequest(any(HttpRequest.class), any(InetSocketAddress.class))).thenReturn(future);
        HttpForwardActionHandler withoutDescriptors =
            new HttpForwardActionHandler(mock(MockServerLogger.class), Configuration.configuration(), mockHttpClient);
        byte[] frame = GrpcFrameCodec.encode(converter.toProtobuf("{\"name\":\"Tom\"}", greetingMethod.getInputType()));
        HttpRequest rawGrpcCall = request()
            .withMethod("POST")
            .withPath("/" + SERVICE + "/Greeting")
            .withProtocol(Protocol.HTTP_2)
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .withHeader("te", "trailers")
            .withBody(frame);

        withoutDescriptors.handle(forward().withHost("upstream").withPort(9090).withScheme(HttpForward.Scheme.HTTPS), rawGrpcCall)
            .getHttpResponse().get();

        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).sendRequest(sent.capture(), any(InetSocketAddress.class));
        HttpRequest upstreamRequest = sent.getValue();
        assertThat(upstreamRequest.getProtocol(), is(Protocol.HTTP_2));
        assertThat(upstreamRequest.getHeader("te"), contains("trailers"));
        assertThat(upstreamRequest.getBodyAsRawBytes(), is(frame));
    }

    @Test
    public void shouldLeavePlainHttpForwardUnchangedWhenStoreSet() throws Exception {
        // a non-gRPC request must be forwarded byte-for-byte (no re-encoding) even though the store is wired
        CompletableFuture<HttpResponse> future = CompletableFuture.completedFuture(response().withBody("pong"));
        when(mockHttpClient.sendRequest(any(HttpRequest.class), any(InetSocketAddress.class))).thenReturn(future);

        HttpRequest plain = request().withMethod("GET").withPath("/ping");
        HttpResponse response = handler
            .handle(forward().withHost("upstream").withPort(80).withScheme(HttpForward.Scheme.HTTP), plain)
            .getHttpResponse().get();

        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).sendRequest(sent.capture(), any(InetSocketAddress.class));
        assertThat(sent.getValue().getBodyAsString(), is(emptyOrNullString()));
        assertThat(response.getBodyAsString(), is("pong"));
        assertThat(response.getFirstHeader(GrpcForwardTranslator.SERVICE_HEADER), is(emptyOrNullString()));
    }
}

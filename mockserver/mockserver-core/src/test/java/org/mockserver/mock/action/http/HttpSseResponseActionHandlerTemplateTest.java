package org.mockserver.mock.action.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpContent;
import org.junit.Test;
import org.mockserver.llm.StreamingFormat;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpSseResponse;
import org.mockserver.model.HttpTemplate;
import org.mockserver.model.SseEvent;
import org.mockserver.scheduler.Scheduler;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * T1.2: SSE event-data response templating.
 * <p>
 * Drives {@link HttpSseResponseActionHandler} over an {@link EmbeddedChannel} and asserts that an
 * event's {@code data} payload is rendered as a response template against the triggering request when
 * a {@code templateType} is set, and is emitted verbatim (static) when it is not. Events have no delay
 * so the handler runs synchronously on the calling thread (no scheduler interaction).
 */
public class HttpSseResponseActionHandlerTemplateTest {

    private final HttpSseResponseActionHandler handler =
        new HttpSseResponseActionHandler(new MockServerLogger(HttpSseResponseActionHandlerTemplateTest.class), mock(Scheduler.class), configuration());

    private String drainOutbound(EmbeddedChannel channel) {
        StringBuilder sb = new StringBuilder();
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            if (outbound instanceof HttpContent) {
                HttpContent content = (HttpContent) outbound;
                sb.append(content.content().toString(StandardCharsets.UTF_8));
                content.release();
            }
        }
        return sb.toString();
    }

    @Test
    public void shouldRenderEventDataWithVelocityTemplate() {
        // given
        ChannelInboundHandlerAdapter dummy = new ChannelInboundHandlerAdapter();
        EmbeddedChannel channel = new EmbeddedChannel(dummy);
        ChannelHandlerContext ctx = channel.pipeline().context(dummy);

        HttpSseResponse sseResponse = HttpSseResponse.sseResponse()
            .withTemplateType(HttpTemplate.TemplateType.VELOCITY)
            .withEvent(SseEvent.sseEvent().withData("Hello $jsonPath.find(\"$.name\")"));

        // when
        handler.handle(sseResponse, ctx, request().withBody("{\"name\": \"Alice\"}"));

        // then
        assertThat(drainOutbound(channel), containsString("data: Hello Alice"));
    }

    @Test
    public void shouldEmitEventDataVerbatimWhenNoTemplateType() {
        // given
        ChannelInboundHandlerAdapter dummy = new ChannelInboundHandlerAdapter();
        EmbeddedChannel channel = new EmbeddedChannel(dummy);
        ChannelHandlerContext ctx = channel.pipeline().context(dummy);

        HttpSseResponse sseResponse = HttpSseResponse.sseResponse()
            .withEvent(SseEvent.sseEvent().withData("Hello $jsonPath.find(\"$.name\")"));

        // when
        handler.handle(sseResponse, ctx, request().withBody("{\"name\": \"Alice\"}"));

        // then — static, unrendered
        String out = drainOutbound(channel);
        assertThat(out, containsString("data: Hello $jsonPath.find(\"$.name\")"));
        assertThat(out, not(containsString("Hello Alice")));
    }

    @Test
    public void shouldRenderCommentWithVelocityTemplate() {
        // given
        ChannelInboundHandlerAdapter dummy = new ChannelInboundHandlerAdapter();
        EmbeddedChannel channel = new EmbeddedChannel(dummy);
        ChannelHandlerContext ctx = channel.pipeline().context(dummy);

        HttpSseResponse sseResponse = HttpSseResponse.sseResponse()
            .withTemplateType(HttpTemplate.TemplateType.VELOCITY)
            .withEvent(SseEvent.sseEvent().withComment(" for $jsonPath.find(\"$.name\")"));

        // when
        handler.handle(sseResponse, ctx, request().withBody("{\"name\": \"Alice\"}"));

        // then
        assertThat(drainOutbound(channel), containsString(": for Alice\n\n"));
    }

    @Test
    public void shouldWriteNothingForACommentOnlyEventInANonSseFormat() {
        // given
        ChannelInboundHandlerAdapter dummy = new ChannelInboundHandlerAdapter();
        EmbeddedChannel channel = new EmbeddedChannel(dummy);
        ChannelHandlerContext ctx = channel.pipeline().context(dummy);

        HttpSseResponse sseResponse = HttpSseResponse.sseResponse()
            .withEvent(SseEvent.sseEvent().withData("{\"n\":1}"))
            .withEvent(SseEvent.sseEvent().withComment("hb"))
            .withEvent(SseEvent.sseEvent().withData("{\"n\":2}"));

        // when
        handler.handle(sseResponse, ctx, request(), StreamingFormat.NDJSON);
        channel.runPendingTasks();

        // then
        assertThat(drainOutbound(channel), is("{\"n\":1}\n{\"n\":2}\n"));
    }

    @Test
    public void shouldSkipALongRunOfUndelayedCommentOnlyEventsWithoutGrowingTheStack() {
        // given
        ChannelInboundHandlerAdapter dummy = new ChannelInboundHandlerAdapter();
        EmbeddedChannel channel = new EmbeddedChannel(dummy);
        ChannelHandlerContext ctx = channel.pipeline().context(dummy);
        HttpSseResponse sseResponse = HttpSseResponse.sseResponse();
        for (int i = 0; i < 50_000; i++) {
            sseResponse.withEvent(SseEvent.sseEvent().withComment("hb"));
        }
        sseResponse.withEvent(SseEvent.sseEvent().withData("{\"n\":1}"));

        // when
        handler.handle(sseResponse, ctx, request(), StreamingFormat.NDJSON);
        channel.runPendingTasks();

        // then
        assertThat(drainOutbound(channel), is("{\"n\":1}\n"));
    }
}

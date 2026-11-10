package org.mockserver.mock.action.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.llm.StreamingFormat;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Delay;
import org.mockserver.model.HttpSseResponse;
import org.mockserver.model.SseEvent;
import org.mockserver.scheduler.Scheduler;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * The end of an SSE stream: {@code closeDelay} is the wait after the last event before the stream ends, and
 * {@code keepStreamOpen} keeps the stream open after its last event. The scheduler is a mock, so a test runs the
 * close itself when the wait would have elapsed.
 */
public class HttpSseResponseActionHandlerCloseDelayTest {

    private final Scheduler scheduler = mock(Scheduler.class);
    private final HttpSseResponseActionHandler handler =
        new HttpSseResponseActionHandler(new MockServerLogger(HttpSseResponseActionHandlerCloseDelayTest.class), scheduler, configuration());
    private final ChannelInboundHandlerAdapter dummy = new ChannelInboundHandlerAdapter();
    private final EmbeddedChannel channel = new EmbeddedChannel(dummy);
    private final ChannelHandlerContext ctx = channel.pipeline().context(dummy);
    private final AtomicInteger responseEnded = new AtomicInteger();

    /** The outbound writes after the head: each chunk's text, and "END" for the last chunk. */
    private List<String> drainBody() {
        List<String> writes = new ArrayList<>();
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            if (outbound instanceof HttpResponse) {
                continue;
            }
            if (outbound instanceof LastHttpContent) {
                writes.add("END");
            } else if (outbound instanceof HttpContent) {
                writes.add(((HttpContent) outbound).content().toString(StandardCharsets.UTF_8));
            }
            ((HttpContent) outbound).release();
        }
        return writes;
    }

    private void handle(HttpSseResponse sseResponse, StreamingFormat format) {
        handler.handle(sseResponse, ctx, request("/events").withKeepAlive(true), format, responseEnded::incrementAndGet);
    }

    private Runnable scheduledClose(Delay closeDelay) {
        ArgumentCaptor<Runnable> close = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(close.capture(), eq(false), eq(closeDelay));
        return close.getValue();
    }

    @Test
    public void shouldEndTheStreamOnlyAfterTheCloseDelay() {
        Delay closeDelay = Delay.milliseconds(1500);

        handle(HttpSseResponse.sseResponse()
            .withEvent(SseEvent.sseEvent().withData("last"))
            .withCloseDelay(closeDelay), StreamingFormat.SSE);

        assertThat("the event is sent at once and the stream stays open", drainBody(), contains("data: last\n\n"));
        assertThat(responseEnded.get(), is(0));

        scheduledClose(closeDelay).run();

        assertThat(drainBody(), contains("END"));
        assertThat(responseEnded.get(), is(1));
        assertThat("keep-alive is unaffected by the wait", channel.isOpen(), is(true));
    }

    @Test
    public void shouldEndTheStreamAtOnceWithoutTouchingTheSchedulerWhenThereIsNoCloseDelay() {
        handle(HttpSseResponse.sseResponse().withEvent(SseEvent.sseEvent().withData("last")), StreamingFormat.SSE);

        assertThat(drainBody(), contains("data: last\n\n", "END"));
        assertThat(responseEnded.get(), is(1));
        verify(scheduler, never()).schedule(any(Runnable.class), anyBoolean(), any(Delay[].class));
    }

    @Test
    public void shouldWaitTheCloseDelayAfterTheHeadWhenThereAreNoEvents() {
        Delay closeDelay = Delay.seconds(2);

        handle(HttpSseResponse.sseResponse().withCloseDelay(closeDelay), StreamingFormat.SSE);

        assertThat(drainBody(), is(empty()));
        scheduledClose(closeDelay).run();
        assertThat(drainBody(), contains("END"));
    }

    @Test
    public void shouldSendTheClosingBracketOfAJsonArrayOnlyWhenTheStreamEnds() {
        Delay closeDelay = Delay.milliseconds(700);

        handle(HttpSseResponse.sseResponse()
            .withEvent(SseEvent.sseEvent().withData("{\"a\":1}"))
            .withCloseDelay(closeDelay), StreamingFormat.JSON_ARRAY);

        assertThat(drainBody(), contains("[", "{\"a\":1}"));
        scheduledClose(closeDelay).run();
        assertThat(drainBody(), contains("]", "END"));
    }

    @Test
    public void shouldEndSilentlyWhenTheClientLeavesDuringTheCloseDelay() {
        Delay closeDelay = Delay.milliseconds(1500);
        handle(HttpSseResponse.sseResponse()
            .withEvent(SseEvent.sseEvent().withData("last"))
            .withCloseDelay(closeDelay), StreamingFormat.SSE);
        drainBody();

        channel.close();
        scheduledClose(closeDelay).run();

        assertThat(drainBody(), is(empty()));
        assertThat("a response the client left is not ended", responseEnded.get(), is(0));
    }

    @Test
    public void shouldKeepTheStreamOpenAfterTheLastEventWhenKeepStreamOpenIsSet() {
        handle(HttpSseResponse.sseResponse()
            .withEvent(SseEvent.sseEvent().withData("last"))
            .withCloseDelay(Delay.milliseconds(100))
            .withKeepStreamOpen(true), StreamingFormat.SSE);

        assertThat(drainBody(), contains("data: last\n\n"));
        assertThat(responseEnded.get(), is(0));
        assertThat(channel.isOpen(), is(true));
        verify(scheduler, never()).schedule(any(Runnable.class), anyBoolean(), any(Delay[].class));
    }

    @Test
    public void shouldEndTheStreamWhenKeepStreamOpenIsFalse() {
        handle(HttpSseResponse.sseResponse()
            .withEvent(SseEvent.sseEvent().withData("last"))
            .withKeepStreamOpen(false), StreamingFormat.SSE);

        assertThat(drainBody(), contains("data: last\n\n", "END"));
    }
}

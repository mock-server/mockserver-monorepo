package org.mockserver.netty.responsewriter;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.extension.DataPlaneHooks;
import org.mockserver.extension.ExtensionRegistry;
import org.mockserver.extension.FilterContext;
import org.mockserver.extension.FilterResult;
import org.mockserver.extension.RequestFilter;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.scheduler.Scheduler;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * When the continuation after a request filter throws, its exchange is answered with a failure at once; a response
 * the continuation had already scheduled, written later, must then be dropped and counted, never sent, or on a
 * pipelined HTTP/1.1 connection it would be read as the answer to the next request.
 */
public class TransformingNettyResponseWriterAfterFailureTest {

    private final MockServerLogger mockServerLogger = new MockServerLogger(TransformingNettyResponseWriterAfterFailureTest.class);
    private ExtensionRegistry registry;
    private DataPlaneHooks hooks;
    private EmbeddedChannel channel;
    private TransformingNettyResponseWriter writer;

    public static class Proceeds implements RequestFilter {
        @Override
        public String name() {
            return "proceeds";
        }

        @Override
        public FilterResult filter(HttpRequest request, FilterContext context) {
            return FilterResult.proceed();
        }
    }

    @Before
    public void createWriter() {
        registry = ExtensionRegistry.start(configuration().extensions(Proceeds::new), mockServerLogger);
        hooks = registry.dataPlaneHooks();
        ChannelInboundHandlerAdapter handler = new ChannelInboundHandlerAdapter();
        channel = new EmbeddedChannel(handler);
        ChannelHandlerContext ctx = channel.pipeline().context(handler);
        writer = new TransformingNettyResponseWriter(configuration(), mockServerLogger, ctx, mock(Scheduler.class), null, hooks);
    }

    @After
    public void stop() {
        channel.finishAndReleaseAll();
        registry.stop();
    }

    @Test
    public void shouldDropAndCountAWriteScheduledBeforeTheContinuationThrew() {
        // kept alive, so the failure response does not close the channel the late write would go to
        HttpRequest request = request("/a").withKeepAlive(true);
        writer.markDataPlane(request);

        writer.failUnanswered(new IllegalStateException("the continuation threw"));
        writer.writeResponse(request, response().withStatusCode(200).withBody("scheduled before the throw"), false);
        channel.runPendingTasks();

        assertThat(writtenStatuses(), contains(500));
        assertThat(hooks.droppedWriteCount(), is(1L));
    }

    private List<Integer> writtenStatuses() {
        List<Integer> statuses = new ArrayList<>();
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            if (outbound instanceof HttpResponse) {
                statuses.add(((HttpResponse) outbound).getStatusCode());
            }
            ReferenceCountUtil.release(outbound);
        }
        return statuses;
    }
}

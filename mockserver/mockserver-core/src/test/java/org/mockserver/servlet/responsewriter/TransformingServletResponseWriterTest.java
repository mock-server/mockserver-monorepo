package org.mockserver.servlet.responsewriter;

import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.extension.ExtensionRegistry;
import org.mockserver.extension.ResponseTransformer;
import org.mockserver.extension.TransformContext;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.TransformingWriter;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * The servlet writer tells a response transformer whether the request was proxied as its servlet decided for that
 * request, read when the response is transformed rather than when the writer was built.
 */
public class TransformingServletResponseWriterTest {

    private final Configuration configuration = configuration().logLevel(Level.ERROR);
    private final MockServerLogger mockServerLogger = new MockServerLogger(configuration, TransformingServletResponseWriterTest.class);
    private final Scheduler scheduler = new Scheduler(configuration, mockServerLogger);
    private ExtensionRegistry registry;

    public static class ProxyFlag implements ResponseTransformer {
        @Override
        public String name() {
            return "proxy-flag";
        }

        @Override
        public HttpResponse transform(HttpRequest request, HttpResponse response, TransformContext context) {
            return response.withHeader("x-proxy-request", String.valueOf(context.isProxyRequest()));
        }
    }

    @After
    public void stop() {
        if (registry != null) {
            registry.stop();
        }
        scheduler.shutdown();
    }

    @Test
    public void shouldReadTheProxyFlagWhenTheResponseIsTransformed() {
        registry = ExtensionRegistry.start(configuration().extensions(ProxyFlag.class), mockServerLogger);

        assertThat(transformedWith(true, false), is("false"));
        assertThat(transformedWith(false, true), is("true"));
    }

    private String transformedWith(boolean whenBuilt, boolean whenWritten) {
        AtomicBoolean proxying = new AtomicBoolean(whenBuilt);
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        TransformingServletResponseWriter writer = new TransformingServletResponseWriter(configuration, mockServerLogger, servletResponse, scheduler, registry.dataPlaneHooks(), mockServerLogger, proxying::get);
        HttpRequest request = (HttpRequest) request("/mocked").withMethod("GET").withKeepAlive(true);
        TransformingWriter.markDataPlane(writer, request);
        proxying.set(whenWritten);

        writer.writeResponse(request, response("mocked"), false);

        assertThat(servletResponse.getStatus(), is(200));
        return servletResponse.getHeader("x-proxy-request");
    }
}

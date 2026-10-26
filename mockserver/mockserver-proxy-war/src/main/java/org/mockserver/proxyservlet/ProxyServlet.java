package org.mockserver.proxyservlet;

import com.google.common.collect.ImmutableSet;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.mockserver.authentication.ControlPlaneAuthenticationHandlerFactory;
import org.mockserver.configuration.Configuration;
import org.mockserver.extension.ExtensionRegistry;
import org.mockserver.log.MockServerEventLog;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.HttpServletRequestToMockServerHttpRequestDecoder;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.model.HttpRequest;
import org.mockserver.proxyconfiguration.ProxyConfiguration;
import org.mockserver.responsewriter.ControlPlaneFailureResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.PortBindingSerializer;
import org.mockserver.servlet.ServletDataPlaneAuthentication;
import org.mockserver.servlet.responsewriter.ServletResponseWriter;
import org.mockserver.socket.tls.NettySslContextFactory;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Collections;
import java.util.List;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.rtsp.RtspResponseStatuses.NOT_IMPLEMENTED;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.HttpState.PATH_PREFIX;
import static org.mockserver.model.PortBinding.portBinding;
import static org.mockserver.proxyconfiguration.ProxyConfiguration.proxyConfiguration;

/**
 * @author jamesdbloom
 */
@SuppressWarnings("FieldMayBeFinal")
public class ProxyServlet extends HttpServlet implements ServletContextListener {

    private Configuration configuration;
    private MockServerLogger mockServerLogger;
    // generic handling
    private HttpState httpStateHandler;
    private Scheduler scheduler;
    // serializers
    private PortBindingSerializer portBindingSerializer;
    // mappers
    private HttpServletRequestToMockServerHttpRequestDecoder httpServletRequestToMockServerRequestDecoder;
    // mockserver
    private HttpActionHandler actionHandler;
    private final EventLoopGroup workerGroup;
    private final ExtensionRegistry extensionRegistry;

    @SuppressWarnings("WeakerAccess")
    public ProxyServlet() {
        this(configuration());
    }

    ProxyServlet(Configuration configuration) {
        this.configuration = configuration;
        ControlPlaneAuthenticationHandlerFactory.requireUsableControlPlaneMutualTls(this.configuration);
        // read before anything is allocated: an invalid combination (SOCKS with HTTP) throws
        List<ProxyConfiguration> proxyConfigurations = proxyConfiguration(this.configuration);
        this.mockServerLogger = new MockServerLogger(MockServerEventLog.class);
        ControlPlaneAuthenticationHandlerFactory.requireUsableControlPlaneBasicAuthentication(this.configuration, this.mockServerLogger);
        // extensions start before anything else is built, so a refused start leaks no thread or event loop
        this.extensionRegistry = this.configuration.extensionsConfigured() ? ExtensionRegistry.start(this.configuration, this.mockServerLogger) : null;
        Scheduler createdScheduler = null;
        HttpState createdHttpState = null;
        EventLoopGroup createdWorkerGroup = null;
        try {
            this.httpServletRequestToMockServerRequestDecoder = new HttpServletRequestToMockServerHttpRequestDecoder(this.configuration, this.mockServerLogger);
            this.scheduler = createdScheduler = new Scheduler(this.configuration, mockServerLogger);
            this.httpStateHandler = createdHttpState = new HttpState(this.configuration, mockServerLogger, this.scheduler, this.extensionRegistry);
            this.mockServerLogger = httpStateHandler.getMockServerLogger();
            this.portBindingSerializer = new PortBindingSerializer(mockServerLogger);
            this.workerGroup = createdWorkerGroup = new NioEventLoopGroup(configuration.nioEventLoopThreadCount(), new Scheduler.SchedulerThreadFactory(this.getClass().getSimpleName() + "-eventLoop"));
            this.actionHandler = new HttpActionHandler(this.configuration, () -> workerGroup, httpStateHandler, proxyConfigurations, new NettySslContextFactory(this.configuration, this.mockServerLogger, true));
            if (this.extensionRegistry != null) {
                // a WAR has no ports of its own to report
                this.extensionRegistry.notifyStarted(Collections.emptyList());
            }
        } catch (Throwable throwable) {
            stopRefusedStart(createdScheduler, createdWorkerGroup, createdHttpState, this.extensionRegistry);
            throw throwable;
        }
    }

    private static void stopRefusedStart(Scheduler scheduler, EventLoopGroup workerGroup, HttpState httpState, ExtensionRegistry extensionRegistry) {
        if (scheduler != null) {
            scheduler.shutdown();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 750, MILLISECONDS).syncUninterruptibly();
        }
        if (httpState != null) {
            httpState.stop();
        }
        if (extensionRegistry != null) {
            extensionRegistry.stop();
        }
    }

    @Override
    public void destroy() {
        shutdown();
    }

    @Override
    public void contextInitialized(ServletContextEvent sce) {

    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        shutdown();
    }

    private void shutdown() {
        // first: the scheduler and the worker group stop below, before httpStateHandler.stop() would answer a pending
        // request filter or response transformer call with its 503
        this.httpStateHandler.stopDataPlaneHooks();
        this.scheduler.shutdown();
        if (!this.workerGroup.isShuttingDown()) {
            this.workerGroup.shutdownGracefully(100, 750, MILLISECONDS).syncUninterruptibly();
        }
        this.httpStateHandler.stop();
        if (this.extensionRegistry != null) {
            this.extensionRegistry.stop();
        }
    }

    @Override
    public void service(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) {

        ResponseWriter responseWriter = new ServletResponseWriter(configuration, new MockServerLogger(), httpServletResponse);
        HttpRequest request = null;
        boolean dataPlane = false;
        try {

            request = httpServletRequestToMockServerRequestDecoder.mapHttpServletRequestToMockServerRequest(httpServletRequest);
            final String hostHeader = request.getFirstHeader(HOST.toString());
            if (isNotBlank(hostHeader)) {
                scheduler.submit(() -> configuration.addSubjectAlternativeName(hostHeader));
            }

            if (!httpStateHandler.handle(request, responseWriter, true)) {

                if (request.getPath().getValue().equals("/_mockserver_callback_websocket")) {

                    responseWriter.writeResponse(request, NOT_IMPLEMENTED, "ExpectationResponseCallback, ExpectationForwardCallback or ExpectationForwardAndResponseCallback is not supported by MockServer deployed as a WAR", "text/plain");

                } else if (request.matches("PUT", PATH_PREFIX + "/status", "/status") ||
                    isNotBlank(configuration.livenessHttpGetPath()) && request.matches("GET", configuration.livenessHttpGetPath())) {

                    responseWriter.writeResponse(request, OK, portBindingSerializer.serialize(portBinding(httpServletRequest.getLocalPort())), "application/json");

                } else if (request.matches("PUT", PATH_PREFIX + "/bind", "/bind")) {

                    responseWriter.writeResponse(request, NOT_IMPLEMENTED);

                } else if (request.matches("PUT", PATH_PREFIX + "/stop", "/stop")) {

                    responseWriter.writeResponse(request, NOT_IMPLEMENTED);

                } else {

                    dataPlane = true;
                    // authenticate before anything that can match an expectation (and so use up Times or move a
                    // scenario); a data-plane hook such as the request filter runs after this
                    if (!ServletDataPlaneAuthentication.isAuthenticated(configuration, mockServerLogger, httpStateHandler, httpServletRequest, request, responseWriter)) {
                        return;
                    }
                    String portExtension = "";
                    if (!(httpServletRequest.getLocalPort() == 443 && httpServletRequest.isSecure() || httpServletRequest.getLocalPort() == 80)) {
                        portExtension = ":" + httpServletRequest.getLocalPort();
                    }
                    actionHandler.processAction(request, responseWriter, null, ImmutableSet.of(
                        httpServletRequest.getLocalAddr() + portExtension,
                        "localhost" + portExtension,
                        "127.0.0.1" + portExtension
                    ), true, true);

                }
            }
        } catch (Throwable throwable) {
            if (request == null) {
                request = httpServletRequestToMockServerRequestDecoder.mapUndecodableServletRequest(httpServletRequest);
                // as HttpRequestHandler classifies a request: the liveness path is control plane too
                dataPlane = !HttpState.isControlPlanePathCandidate(request.getPath().getValue())
                    && !(isNotBlank(configuration.livenessHttpGetPath()) && request.matches("GET", configuration.livenessHttpGetPath()));
            }
            if (dataPlane) {
                // answered as a mock response, as HttpRequestHandler answers a data-plane failure
                String message = ControlPlaneFailureResponse.logUnexpectedFailure(mockServerLogger, request, throwable);
                responseWriter.writeResponse(request, ControlPlaneFailureResponse.dataPlaneFailureResponse(message), false);
            } else {
                ControlPlaneFailureResponse.write(mockServerLogger, responseWriter, request, throwable);
            }
        }
    }
}

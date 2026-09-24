package org.mockserver.netty;

import com.google.common.collect.ImmutableList;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.socket.DatagramChannel;
import io.netty.handler.codec.dns.DatagramDnsQueryDecoder;
import io.netty.handler.codec.dns.DatagramDnsResponseEncoder;
import org.mockserver.configuration.Configuration;
import org.mockserver.lifecycle.ExpectationsListener;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.log.model.LogEntry;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.netty.dns.DnsRequestHandler;
import org.mockserver.netty.http3.Http3Server;
import org.mockserver.proxyconfiguration.ProxyConfiguration;
import org.mockserver.socket.NettyTransport;
import org.mockserver.socket.tls.NettySslContextFactory;
import org.slf4j.event.Level;

import javax.annotation.Nullable;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static java.util.Collections.singletonList;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.SERVER_CONFIGURATION;
import static org.mockserver.mock.action.http.HttpActionHandler.REMOTE_SOCKET;
import static org.mockserver.netty.HttpRequestHandler.PROXYING;
import static org.mockserver.proxyconfiguration.ProxyConfiguration.proxyConfiguration;

/**
 * @author jamesdbloom
 */
public class MockServer extends LifeCycle {

    private InetSocketAddress remoteSocket;
    private volatile org.mockserver.netty.mcp.McpSessionManager mcpSessionManager;
    private volatile io.netty.channel.Channel dnsChannel;
    private volatile Http3Server http3Server;

    /**
     * Start the instance using the ports provided
     *
     * @param localPorts the local port(s) to use, use 0 or no vararg values to specify any free port
     */
    public MockServer(final Integer... localPorts) {
        this(null, proxyConfiguration(configuration()), localPorts);
    }

    /**
     * Start the instance using the ports provided
     *
     * @param localPorts the local port(s) to use, use 0 or no vararg values to specify any free port
     */
    public MockServer(final Configuration configuration, final Integer... localPorts) {
        this(configuration, proxyConfiguration(configuration), localPorts);
    }

    /**
     * Start the instance using the ports provided configuring forwarded or proxied requests to go via an additional proxy
     *
     * @param proxyConfiguration the proxy configuration to send requests forwarded or proxied by MockServer via another proxy
     * @param localPorts         the local port(s) to use, use 0 or no vararg values to specify any free port
     */
    public MockServer(final ProxyConfiguration proxyConfiguration, final Integer... localPorts) {
        this(null, ImmutableList.of(proxyConfiguration), localPorts);
    }

    /**
     * Start the instance using the ports provided configuring forwarded or proxied requests to go via an additional proxy
     *
     * @param proxyConfigurations the proxy configuration to send requests forwarded or proxied by MockServer via another proxy
     * @param localPorts          the local port(s) to use, use 0 or no vararg values to specify any free port
     */
    public MockServer(final Configuration configuration, final List<ProxyConfiguration> proxyConfigurations, final Integer... localPorts) {
        super(configuration);
        createServerBootstrap(configuration, proxyConfigurations, localPorts);

        // wait to start
        getLocalPort();
    }

    /**
     * Start the instance using the ports provided
     *
     * @param remotePort the port of the remote server to connect to
     * @param remoteHost the hostname of the remote server to connect to (if null defaults to "localhost")
     * @param localPorts the local port(s) to use
     */
    public MockServer(final Integer remotePort, @Nullable final String remoteHost, final Integer... localPorts) {
        this(null, proxyConfiguration(configuration()), remoteHost, remotePort, localPorts);
    }

    /**
     * Start the instance using the ports provided
     *
     * @param remotePort the port of the remote server to connect to
     * @param remoteHost the hostname of the remote server to connect to (if null defaults to "localhost")
     * @param localPorts the local port(s) to use
     */
    public MockServer(final Configuration configuration, final Integer remotePort, @Nullable final String remoteHost, final Integer... localPorts) {
        this(configuration, proxyConfiguration(configuration), remoteHost, remotePort, localPorts);
    }

    /**
     * Start the instance using the ports provided configuring forwarded or proxied requests to go via an additional proxy
     *
     * @param localPorts the local port(s) to use
     * @param remoteHost the hostname of the remote server to connect to (if null defaults to "localhost")
     * @param remotePort the port of the remote server to connect to
     */
    public MockServer(final Configuration configuration, final ProxyConfiguration proxyConfiguration, @Nullable String remoteHost, final Integer remotePort, final Integer... localPorts) {
        this(configuration, ImmutableList.of(proxyConfiguration), remoteHost, remotePort, localPorts);
    }

    /**
     * Start the instance using the ports provided configuring forwarded or proxied requests to go via an additional proxy
     *
     * @param localPorts the local port(s) to use
     * @param remoteHost the hostname of the remote server to connect to (if null defaults to "localhost")
     * @param remotePort the port of the remote server to connect to
     */
    public MockServer(final Configuration configuration, final List<ProxyConfiguration> proxyConfigurations, @Nullable String remoteHost, final Integer remotePort, final Integer... localPorts) {
        super(configuration);
        if (remotePort == null) {
            throw new IllegalArgumentException("You must specify a remote hostname");
        }
        if (isBlank(remoteHost)) {
            remoteHost = "localhost";
        }

        remoteSocket = new InetSocketAddress(remoteHost, remotePort);
        if (proxyConfigurations != null && mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(SERVER_CONFIGURATION)
                    .setLogLevel(Level.INFO)
                    .setMessageFormat("using proxy configuration for forwarded requests:{}")
                    .setArguments(proxyConfigurations)
            );
        }
        createServerBootstrap(configuration, proxyConfigurations, localPorts);

        // wait to start
        getLocalPort();
    }

    private void createServerBootstrap(Configuration configuration, final List<ProxyConfiguration> proxyConfigurations, final Integer... localPorts) {
        if (configuration == null) {
            configuration = configuration();
        }

        // FIRST, before anything is bound. This depends only on configuration, so there is no reason to
        // discover it late - and discovering it late is expensive: the throw escapes the constructor, so
        // the caller never gets a reference and can never call stop(). Anything already allocated is
        // orphaned for the life of the JVM. That is survivable for the CLI (the JVM exits) but not for
        // the embedded users - MockServerRule, the JUnit 5 extension, Spring - where a long-lived JVM
        // would leak a listening socket per failed construction, on what this change deliberately makes
        // a COMMON user state. stop() still runs because LifeCycle's constructor has already built the
        // boss/worker event-loop groups by the time we get here; hoisting alone would not release those.
        Integer configuredHttp3Port = configuration.http3Port();
        if (configuredHttp3Port != null && configuredHttp3Port > 0) {
            try {
                requireQuicNative(configuredHttp3Port);
            } catch (Throwable throwable) {
                stop();
                throw throwable;
            }
        }

        List<Integer> portBindings = singletonList(0);
        if (localPorts != null && localPorts.length > 0) {
            portBindings = Arrays.asList(localPorts);
        }

        final NettySslContextFactory nettyServerSslContextFactory = new NettySslContextFactory(configuration, mockServerLogger, true);
        final NettySslContextFactory nettyClientSslContextFactory = new NettySslContextFactory(configuration, mockServerLogger, false);
        // The control-plane authentication handler is deliberately NOT constructed here. HttpState derives
        // it from the live Configuration and rebuilds it whenever the auth-relevant configuration changes
        // (see ControlPlaneAuthenticationHandlerFactory), so enabling control-plane authentication AFTER
        // startup — via a system property, a Configuration setter, a ConfigurationDTO, or
        // PUT /mockserver/configuration — actually reaches the enforcement point. Building it once here
        // meant a runtime enable returned 200 and echoed "true" while the handler stayed null, and a null
        // handler means "authenticated": the control plane reported itself locked but was fully open.
        MockServerUnificationInitializer initializer = new MockServerUnificationInitializer(configuration, MockServer.this, httpState, new HttpActionHandler(configuration, this::getForwardClientEventLoopGroup, httpState, proxyConfigurations, nettyClientSslContextFactory), nettyServerSslContextFactory);
        this.mcpSessionManager = initializer.getMcpSessionManager();
        serverServerBootstrap = new ServerBootstrap()
            .group(bossGroup, workerGroup)
            // Accept-queue depth, configurable via mockserver.soBacklog. The 1024 default is
            // deliberate: a deeper queue admits connections the server may not be able to
            // serve, so it can act as backpressure rather than as a ceiling. Raising it also
            // needs net.core.somaxconn raised to match.
            .option(ChannelOption.SO_BACKLOG, configuration.soBacklog())
            .channel(NettyTransport.serverSocketChannelClassFor(bossGroup))
            .childOption(ChannelOption.AUTO_READ, true)
            .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
            .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(8 * 1024, 32 * 1024))
            .childHandler(initializer)
            .childAttr(REMOTE_SOCKET, remoteSocket)
            .childAttr(PROXYING, remoteSocket != null);

        // Apply IP_TRANSPARENT socket option when TPROXY mode is enabled
        org.mockserver.netty.proxy.MockServerIpTransparentHelper.applyIfEnabled(serverServerBootstrap, configuration);

        try {
            bindServerPorts(portBindings);
        } catch (Throwable throwable) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(SERVER_CONFIGURATION)
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception binding to port(s) " + portBindings)
                    .setThrowable(throwable)
            );
            stop();
            throw throwable;
        }

        if (Boolean.TRUE.equals(configuration.dnsEnabled())) {
            try {
                bindDnsPort(configuration);
            } catch (Throwable throwable) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(SERVER_CONFIGURATION)
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("exception binding DNS port - DNS mocking disabled")
                        .setThrowable(throwable)
                );
            }
        }

        // start HTTP/3 (QUIC) server when configured (http3Port > 0). Availability was already
        // established at the top of this method, before any port was bound.
        Integer http3Port = configuration.http3Port();
        if (http3Port != null && http3Port > 0) {
            startHttp3Server(configuration, initializer.getActionHandler(), http3Port, this.mcpSessionManager);
        }

        // Register the AsyncAPI control-plane if mockserver-async is on the classpath.
        // Uses reflection to avoid a hard compile-time dependency — when the module is
        // absent the endpoint gracefully responds 501 (Not Implemented).
        try {
            Class<?> asyncCp = Class.forName("org.mockserver.async.controlplane.AsyncApiControlPlaneImpl");
            java.lang.reflect.Method register = asyncCp.getMethod("registerIfAvailable", Configuration.class);
            register.invoke(null, configuration);
        } catch (ClassNotFoundException ignored) {
            // mockserver-async not on classpath — AsyncAPI endpoints will return 501
        } catch (Exception e) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(SERVER_CONFIGURATION)
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("failed to register AsyncAPI control-plane: " + e.getMessage())
            );
        }

        startedServer(getLocalPorts());
    }

    /**
     * Fail fast, and usefully, when HTTP/3 is switched on without the QUIC native library present.
     * <p>
     * The default standalone jar and the default Docker image deliberately omit the QUIC natives:
     * they are the largest single item in the jar (~11 MiB across five platforms) for a feature that
     * is experimental and does nothing unless {@code http3Port} is set. The QUIC <em>classes</em> are
     * still bundled, so this check can run at all — without them the failure would be a
     * {@code NoClassDefFoundError} from deep inside server start-up, naming a Netty class and giving
     * the reader nothing to act on.
     * <p>
     * Setting {@code http3Port} is an explicit request for HTTP/3, so refusing to start is the honest
     * response: starting without it would leave a server that silently ignores the port it was told
     * to listen on.
     * <p>
     * Every route the message names works today. There is deliberately no mention of an {@code -http3}
     * image tag: none is published yet, and a remedy a reader cannot follow is worse than one fewer
     * option. Containers are pointed at {@code /libs} instead, which is already on the classpath in
     * every image variant.
     */
    private void requireQuicNative(int http3Port) {
        // Http3Server.isQuicAvailable() already wraps the Netty call in catch(Throwable): loading a
        // native can fail with an Error, not just return false, and an Error escaping here would
        // replace the actionable message below with a raw UnsatisfiedLinkError.
        if (Http3Server.isQuicAvailable()) {
            return;
        }
        Throwable cause;
        try {
            cause = io.netty.handler.codec.quic.Quic.unavailabilityCause();
        } catch (Throwable t) {
            cause = t;
        }
        throw new IllegalStateException(quicUnavailableMessage(http3Port), cause);
    }

    /**
     * The message is the whole point of the failure, so it is built here rather than inline: the throw
     * itself can only be reached on a platform without the QUIC native, which is no CI agent we have, so
     * an inline message would ship with nothing executing that reads it. Package-private so a test can
     * assert the remedies it names without needing the native to be absent.
     */
    static String quicUnavailableMessage(int http3Port) {
        return "HTTP/3 was enabled (http3Port=" + http3Port + ") but the QUIC native library is not available. "
            + "MockServer mocks HTTP/1.1 and HTTP/2 out of the box; HTTP/3 is experimental and its native "
            + "binaries ship separately so every other user does not pay for them. To enable it, use the "
            + "artifact that carries them. Standalone jar: use the 'jar-with-dependencies-http3' "
            + "classifier. Maven or Gradle: add io.netty:netty-codec-native-quic with the classifier for "
            + "your platform, at the same Netty version as the rest of the server. Container: mount "
            + "netty-codec-native-quic-<version>-linux-<arch>.jar into /libs, which is already on the "
            + "server's classpath. Alternatively remove http3Port to run without HTTP/3.";
    }

    public InetSocketAddress getRemoteAddress() {
        return remoteSocket;
    }

    public MockServer registerListener(ExpectationsListener expectationsListener) {
        super.registerListener(expectationsListener);
        return this;
    }

    private void bindDnsPort(Configuration configuration) {
        int dnsPort = configuration.dnsPort() != null ? configuration.dnsPort() : 0;
        DnsRequestHandler dnsHandler = new DnsRequestHandler(mockServerLogger, httpState);
        Bootstrap dnsBootstrap = new Bootstrap()
            .group(workerGroup)
            .channel(NettyTransport.datagramChannelClassFor(workerGroup))
            .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
            .handler(new ChannelInitializer<DatagramChannel>() {
                @Override
                protected void initChannel(DatagramChannel ch) {
                    ch.pipeline()
                        .addLast(new DatagramDnsQueryDecoder())
                        .addLast(new DatagramDnsResponseEncoder())
                        .addLast(dnsHandler);
                }
            });
        dnsChannel = dnsBootstrap.bind(dnsPort).syncUninterruptibly().channel();
        int boundPort = ((InetSocketAddress) dnsChannel.localAddress()).getPort();
        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(SERVER_CONFIGURATION)
                    .setLogLevel(Level.INFO)
                    .setMessageFormat("DNS mock server started on port: {}")
                    .setArguments(boundPort)
            );
        }
    }

    public int getDnsPort() {
        if (dnsChannel != null && dnsChannel.localAddress() instanceof InetSocketAddress) {
            return ((InetSocketAddress) dnsChannel.localAddress()).getPort();
        }
        return -1;
    }

    /**
     * Returns the bound HTTP/3 (QUIC) UDP port, or -1 if the HTTP/3 server is
     * not running.
     */
    public int getHttp3Port() {
        Http3Server server = http3Server;
        return server != null ? server.getPort() : -1;
    }

    /**
     * Returns the current number of active HTTP/3 (QUIC) connections,
     * or 0 if the HTTP/3 server is not running.
     */
    public int getHttp3ActiveConnectionCount() {
        Http3Server server = http3Server;
        return server != null ? server.getActiveConnectionCount() : 0;
    }

    /**
     * Callers MUST have passed {@link #requireQuicNative(int)} first. This used to log a warning and
     * return when the native was missing, silently leaving the configured HTTP/3 port unserved; that
     * check now lives in {@code requireQuicNative}, which fails start-up instead. The assertion keeps
     * the ordering machine-enforced rather than comment-enforced, so a future second call site cannot
     * quietly reinstate the silent-disable behaviour.
     */
    private void startHttp3Server(Configuration configuration, HttpActionHandler actionHandler, int http3Port, org.mockserver.netty.mcp.McpSessionManager mcpSessionMgr) {
        if (!Http3Server.isQuicAvailable()) {
            throw new AssertionError("startHttp3Server reached without requireQuicNative - HTTP/3 would have been silently disabled");
        }
        try {
            Http3Server server = new Http3Server(configuration, mockServerLogger, httpState, actionHandler, MockServer.this, mcpSessionMgr);
            int boundPort = server.start(http3Port);
            this.http3Server = server;
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(SERVER_CONFIGURATION)
                    .setLogLevel(Level.INFO)
                    .setMessageFormat("HTTP/3 (QUIC) server started on UDP port: {}")
                    .setArguments(boundPort)
            );
        } catch (Throwable throwable) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(SERVER_CONFIGURATION)
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("exception starting HTTP/3 server on port {} - HTTP/3 disabled")
                    .setArguments(http3Port)
                    .setThrowable(throwable)
            );
        }
    }

    @Override
    public CompletableFuture<String> stopAsync() {
        if (http3Server != null) {
            http3Server.stop();
            http3Server = null;
        }
        if (dnsChannel != null) {
            dnsChannel.close();
        }
        if (mcpSessionManager != null) {
            mcpSessionManager.shutdown();
        }
        return super.stopAsync();
    }

}

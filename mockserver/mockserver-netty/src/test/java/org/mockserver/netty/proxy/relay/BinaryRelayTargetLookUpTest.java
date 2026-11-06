package org.mockserver.netty.proxy.relay;

import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoop;
import org.junit.After;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.model.BinaryMessage;
import org.mockserver.proxyconfiguration.ForwardTargetBlockedException;
import org.mockserver.socket.ChannelReadPause;
import org.slf4j.event.Level;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockserver.mock.action.http.HttpActionHandler.REMOTE_SOCKET;
import static org.mockserver.netty.proxy.relay.BinaryRelayHarness.isReading;

/**
 * A relayed connection whose target is a name not yet resolved has the name looked up off the event loop, which
 * serves other connections too, and connects to the address found. The client is held, and what it sends kept, until
 * then.
 */
public class BinaryRelayTargetLookUpTest {

    private static final InetSocketAddress BY_NAME = InetSocketAddress.createUnresolved("db.example.com", 5432);

    private final BinaryRelayHarness relay = new BinaryRelayHarness(true);
    private final List<CompletableFuture<BinaryMessage>> responses = new ArrayList<>();

    @After
    public void releaseBuffers() {
        relay.finish();
    }

    private void clientConnectionTo(InetSocketAddress target) {
        relay.clientConnection();
        relay.client.attr(REMOTE_SOCKET).set(target);
    }

    /** The scheduler mock keeps what it is handed: the look-up first, then any listener call. */
    private void lookUpRuns() {
        relay.runNextListenerCall();
    }

    @Test
    public void shouldLookTheNameUpOffTheEventLoopAndConnectToTheAddressFound() throws Exception {
        InetSocketAddress found = new InetSocketAddress(InetAddress.getByAddress("db.example.com", new byte[]{10, 0, 0, 5}), 5432);
        when(relay.httpClient.lookUpBinaryRelayTarget(BY_NAME, false)).thenReturn(found);
        clientConnectionTo(BY_NAME);

        relay.clientSends("first");

        verify(relay.httpClient, never()).lookUpBinaryRelayTarget(any(InetSocketAddress.class), anyBoolean());
        assertThat("handed to the scheduler, not run on the event loop", relay.listenerCalls, hasSize(1));
        assertThat(relay.upstreamConnections, is(0));
        assertThat("the client is not read while the name is looked up", isReading(relay.client), is(false));

        relay.clientSends("second");
        lookUpRuns();

        verify(relay.httpClient).connectBinaryRelay(any(EventLoop.class), eq(found), eq(false), any(ChannelHandler.class));
        assertThat(relay.upstreamConnections, is(1));
        assertThat("what was sent meanwhile, in order", relay.receivedByUpstream(), is("firstsecond"));
        assertThat(isReading(relay.client), is(true));
        assertThat("looked up once", relay.listenerCalls, hasSize(0));
    }

    @Test
    public void shouldLookAnUpstreamProxyGivenByNameUpOffTheEventLoopAndTunnelThroughTheAddressFound() throws Exception {
        InetSocketAddress target = new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 0, 0, 5}), 5432);
        InetSocketAddress proxyFound = new InetSocketAddress(InetAddress.getByAddress("proxy.example.com", new byte[]{10, 0, 0, 9}), 1080);
        when(relay.httpClient.binaryRelayTunnelProxyNeedsLookUp(target, false)).thenReturn(true);
        when(relay.httpClient.lookUpBinaryRelayTunnelProxy(target, false)).thenReturn(proxyFound);
        clientConnectionTo(target);

        relay.clientSends("first");

        verify(relay.httpClient, never()).lookUpBinaryRelayTunnelProxy(any(InetSocketAddress.class), anyBoolean());
        assertThat("handed to the scheduler, not run on the event loop", relay.listenerCalls, hasSize(1));
        assertThat(relay.upstreamConnections, is(0));
        assertThat("the client is not read while the proxy is looked up", isReading(relay.client), is(false));

        lookUpRuns();

        verify(relay.httpClient).connectBinaryRelay(any(EventLoop.class), eq(target), eq(false), any(ChannelHandler.class), eq(proxyFound));
        verify(relay.httpClient, never()).lookUpBinaryRelayTarget(any(InetSocketAddress.class), anyBoolean());
        assertThat(relay.upstreamConnections, is(1));
        assertThat(relay.receivedByUpstream(), is("first"));
        assertThat(isReading(relay.client), is(true));
    }

    @Test
    public void shouldCloseTheClientAsAFailedConnectWhenTheUpstreamProxyLookUpOutlastsTheConnectionTimeout() throws Exception {
        relay.configuration.socketConnectionTimeoutInMillis(500L);
        InetSocketAddress target = new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 0, 0, 5}), 5432);
        when(relay.httpClient.binaryRelayTunnelProxyNeedsLookUp(target, false)).thenReturn(true);
        clientConnectionTo(target);
        relay.clientSends("request");

        relay.timePasses(500);

        assertThat(relay.client.isOpen(), is(false));
        assertThat("no hold is left behind", ChannelReadPause.holds(relay.client), is(0));
        List<LogEntry> warnings = relay.logged(Level.WARN);
        assertThat(warnings, hasSize(1));
        assertThat(String.valueOf(warnings.get(0).getArguments()[2]), containsString("looking up the upstream proxy took longer than socketConnectionTimeoutInMillis"));
        lookUpRuns();
        assertThat("the lookup's late answer is ignored", relay.upstreamConnections, is(0));
    }

    @Test
    public void shouldDeliverWhatTheClientSentWhenItClosesWhileTheNameIsLookedUp() throws Exception {
        InetSocketAddress found = new InetSocketAddress(InetAddress.getByAddress("db.example.com", new byte[]{10, 0, 0, 5}), 5432);
        when(relay.httpClient.lookUpBinaryRelayTarget(BY_NAME, false)).thenReturn(found);
        clientConnectionTo(BY_NAME);
        relay.clientSends("last words");

        relay.client.close();
        lookUpRuns();

        assertThat(relay.receivedByUpstream(), is("last words"));
    }

    @Test
    public void shouldCloseTheClientAsAFailedConnectWhenTheNameHasNoAddress() throws Exception {
        relay.configuration.binaryProxyListener((binaryRequest, binaryResponse, serverAddress, clientAddress) -> responses.add(binaryResponse));
        when(relay.httpClient.lookUpBinaryRelayTarget(BY_NAME, false)).thenThrow(new UnknownHostException("db.example.com"));
        clientConnectionTo(BY_NAME);
        relay.clientSends("request");

        lookUpRuns();

        assertThat(relay.client.isOpen(), is(false));
        assertThat(relay.upstreamConnections, is(0));
        List<LogEntry> warnings = relay.logged(Level.WARN);
        assertThat(warnings, hasSize(1));
        assertThat(warnings.get(0).getMessageFormat(), containsString("unable to connect to:"));
        relay.runNextListenerCall();
        assertThat("the message's response fails", responses.get(0).isCompletedExceptionally(), is(true));
    }

    @Test
    public void shouldRefuseTheConnectionWhenTheAddressFoundIsBlocked() throws Exception {
        relay.configuration.binaryProxyListener((binaryRequest, binaryResponse, serverAddress, clientAddress) -> responses.add(binaryResponse));
        when(relay.httpClient.lookUpBinaryRelayTarget(BY_NAME, false)).thenThrow(new ForwardTargetBlockedException("Forward to loopback address blocked: db.example.com"));
        clientConnectionTo(BY_NAME);
        relay.clientSends("request");

        lookUpRuns();

        assertThat(relay.client.isOpen(), is(false));
        assertThat(relay.upstreamConnections, is(0));
        List<LogEntry> warnings = relay.logged(Level.WARN);
        assertThat(warnings, hasSize(1));
        assertThat("a refusal, as when the target given was already resolved", warnings.get(0).getMessageFormat(), containsString("which cannot be forwarded to:"));
        assertThat(String.valueOf(warnings.get(0).getArguments()[2]), containsString("loopback address blocked"));
        relay.runNextListenerCall();
        assertThat("the message's response fails", responses.get(0).isCompletedExceptionally(), is(true));
    }

    @Test
    public void shouldCloseTheClientAsAFailedConnectWhenTheLookUpOutlastsTheConnectionTimeout() throws Exception {
        relay.configuration.socketConnectionTimeoutInMillis(500L);
        relay.configuration.binaryProxyListener((binaryRequest, binaryResponse, serverAddress, clientAddress) -> responses.add(binaryResponse));
        when(relay.httpClient.lookUpBinaryRelayTarget(BY_NAME, false)).thenReturn(new InetSocketAddress(InetAddress.getByAddress("db.example.com", new byte[]{10, 0, 0, 5}), 5432));
        clientConnectionTo(BY_NAME);
        relay.clientSends("request");

        relay.timePasses(499);
        assertThat("not before the timeout", relay.client.isOpen(), is(true));
        relay.timePasses(1);

        assertThat(relay.client.isOpen(), is(false));
        assertThat("no hold is left behind", ChannelReadPause.holds(relay.client), is(0));
        List<LogEntry> warnings = relay.logged(Level.WARN);
        assertThat(warnings, hasSize(1));
        assertThat(warnings.get(0).getMessageFormat(), containsString("unable to connect to:"));
        assertThat(String.valueOf(warnings.get(0).getArguments()[2]), containsString("socketConnectionTimeoutInMillis"));
        // the lookup's late answer is ignored
        lookUpRuns();
        assertThat(relay.upstreamConnections, is(0));
        relay.runNextListenerCall();
        assertThat("the message's response fails", responses.get(0).isCompletedExceptionally(), is(true));
    }

    @Test
    public void shouldCloseTheClientAndReleaseItsHoldWhenTheLookUpCannotBeScheduled() {
        doThrow(new RejectedExecutionException("stopping")).when(relay.scheduler).scheduleLocalCallback(any(Runnable.class), anyBoolean());
        clientConnectionTo(BY_NAME);

        relay.clientSends("request");

        assertThat(relay.client.isOpen(), is(false));
        assertThat("no hold is left behind", ChannelReadPause.holds(relay.client), is(0));
        assertThat(relay.upstreamConnections, is(0));
        assertThat(relay.logged(Level.WARN), hasSize(1));
    }

    @Test
    public void shouldLookNothingUpForATargetAlreadyResolved() {
        relay.clientConnection();

        relay.clientSends("request");

        assertThat(relay.listenerCalls, hasSize(0));
        assertThat(relay.receivedByUpstream(), is("request"));
    }
}

package org.mockserver.netty.proxy.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.socksx.v5.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.scheduler.Scheduler;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * EmbeddedChannel tests for {@link Socks5ProxyHandler} verifying:
 * - Initial request with NO_AUTH returns NO_AUTH response and adds Socks5CommandRequestDecoder
 * - Initial request with PASSWORD when credentials configured returns PASSWORD response
 * - Initial request with PASSWORD when no credentials configured falls back to NO_AUTH
 * - Password auth with correct credentials returns SUCCESS
 * - Password auth with wrong credentials returns FAILURE and closes channel
 * - CONNECT command triggers pipeline replacement with Socks5ConnectHandler
 * - Non-CONNECT command returns COMMAND_UNSUPPORTED and closes channel
 * - Unrecognized SOCKS5 message type closes channel
 * - exceptionCaught closes the channel
 */
public class Socks5ProxyHandlerTest {

    private Configuration configuration;
    private MockServerLogger mockServerLogger;
    private LifeCycle server;

    @Before
    public void setUp() {
        configuration = configuration();
        mockServerLogger = new MockServerLogger();
        server = mock(LifeCycle.class);
        when(server.getScheduler()).thenReturn(mock(Scheduler.class));
    }

    private EmbeddedChannel createChannel() {
        return new EmbeddedChannel(
            new Socks5InitialRequestDecoder(),
            Socks5ServerEncoder.DEFAULT,
            new Socks5ProxyHandler(configuration, mockServerLogger, server)
        );
    }

    private void safeClose(EmbeddedChannel channel) {
        if (channel != null) {
            try {
                channel.checkException();
            } catch (Exception ignored) {
            }
            try {
                channel.finishAndReleaseAll();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    public void shouldRespondWithNoAuthWhenNoAuthOffered() {
        // given - SOCKS5 initial request with NO_AUTH method
        EmbeddedChannel channel = createChannel();
        try {
            byte[] initialRequest = new byte[]{
                0x05,       // SOCKS5
                0x01,       // 1 auth method
                0x00        // NO_AUTH
            };

            // when
            channel.writeInbound(Unpooled.wrappedBuffer(initialRequest));

            // then - should respond with SOCKS5 NO_AUTH
            ByteBuf response = channel.readOutbound();
            assertThat("should write initial response", response, is(notNullValue()));
            byte[] responseBytes = ByteBufUtil.getBytes(response);
            response.release();

            assertThat("version should be 0x05", responseBytes[0], is((byte) 0x05));
            assertThat("auth method should be NO_AUTH (0x00)", responseBytes[1], is((byte) 0x00));

            // and - Socks5CommandRequestDecoder should be in the pipeline
            assertThat("should add command request decoder",
                channel.pipeline().get(Socks5CommandRequestDecoder.class), is(notNullValue()));

            // and - initial request decoder should be removed
            assertThat("should remove initial request decoder",
                channel.pipeline().get(Socks5InitialRequestDecoder.class), is(nullValue()));
        } finally {
            safeClose(channel);
        }
    }

    @Test
    public void shouldRespondWithPasswordAuthWhenCredentialsConfigured() {
        // given - credentials are set
        configuration.proxyAuthenticationUsername("testuser");
        configuration.proxyAuthenticationPassword("testpass");

        EmbeddedChannel channel = createChannel();
        try {
            byte[] initialRequest = new byte[]{
                0x05,       // SOCKS5
                0x01,       // 1 auth method
                0x02        // PASSWORD
            };

            // when
            channel.writeInbound(Unpooled.wrappedBuffer(initialRequest));

            // then - should respond with PASSWORD auth method
            ByteBuf response = channel.readOutbound();
            assertThat("should write initial response", response, is(notNullValue()));
            byte[] responseBytes = ByteBufUtil.getBytes(response);
            response.release();

            assertThat("version should be 0x05", responseBytes[0], is((byte) 0x05));
            assertThat("auth method should be PASSWORD (0x02)", responseBytes[1], is((byte) 0x02));

            // and - password auth decoder should be in the pipeline
            assertThat("should add password auth decoder",
                channel.pipeline().get(Socks5PasswordAuthRequestDecoder.class), is(notNullValue()));
        } finally {
            safeClose(channel);
        }
    }

    @Test
    public void shouldFallBackToNoAuthWhenPasswordOfferedButNoCredentialsConfigured() {
        // given - NO credentials configured, client offers PASSWORD
        EmbeddedChannel channel = createChannel();
        try {
            byte[] initialRequest = new byte[]{
                0x05,       // SOCKS5
                0x02,       // 2 auth methods
                0x00,       // NO_AUTH
                0x02        // PASSWORD
            };

            // when
            channel.writeInbound(Unpooled.wrappedBuffer(initialRequest));

            // then - should respond with NO_AUTH (falls back since no credentials)
            ByteBuf response = channel.readOutbound();
            assertThat("should write initial response", response, is(notNullValue()));
            byte[] responseBytes = ByteBufUtil.getBytes(response);
            response.release();

            assertThat("version should be 0x05", responseBytes[0], is((byte) 0x05));
            assertThat("auth method should be NO_AUTH (0x00)", responseBytes[1], is((byte) 0x00));

            // and - command decoder should be present (not password decoder)
            assertThat("should add command request decoder",
                channel.pipeline().get(Socks5CommandRequestDecoder.class), is(notNullValue()));
        } finally {
            safeClose(channel);
        }
    }

    @Test
    public void shouldAuthenticateWithCorrectPassword() {
        // given - credentials are set and initial handshake with PASSWORD auth
        configuration.proxyAuthenticationUsername("testuser");
        configuration.proxyAuthenticationPassword("testpass");

        EmbeddedChannel channel = createChannel();
        try {
            // step 1: initial request
            channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{
                0x05, 0x01, 0x02  // SOCKS5, 1 method, PASSWORD
            }));
            ByteBuf initialResponse = channel.readOutbound();
            assertThat(initialResponse, is(notNullValue()));
            initialResponse.release();

            // step 2: password auth with correct credentials
            // Format: version(1) + username_len(1) + username + password_len(1) + password
            byte[] username = "testuser".getBytes();
            byte[] password = "testpass".getBytes();
            byte[] authRequest = new byte[1 + 1 + username.length + 1 + password.length];
            authRequest[0] = 0x01; // subnegotiation version
            authRequest[1] = (byte) username.length;
            System.arraycopy(username, 0, authRequest, 2, username.length);
            authRequest[2 + username.length] = (byte) password.length;
            System.arraycopy(password, 0, authRequest, 3 + username.length, password.length);

            // when
            channel.writeInbound(Unpooled.wrappedBuffer(authRequest));

            // then - should respond with auth SUCCESS
            ByteBuf authResponse = channel.readOutbound();
            assertThat("should write auth response", authResponse, is(notNullValue()));
            byte[] authResponseBytes = ByteBufUtil.getBytes(authResponse);
            authResponse.release();

            assertThat("subneg version should be 0x01", authResponseBytes[0], is((byte) 0x01));
            assertThat("status should be SUCCESS (0x00)", authResponseBytes[1], is((byte) 0x00));

            // and - command decoder should now be present
            assertThat("should replace password decoder with command decoder",
                channel.pipeline().get(Socks5CommandRequestDecoder.class), is(notNullValue()));
            assertThat("should remove password auth decoder",
                channel.pipeline().get(Socks5PasswordAuthRequestDecoder.class), is(nullValue()));

            // and - channel should still be active
            assertTrue("channel should remain active after successful auth", channel.isActive());
        } finally {
            safeClose(channel);
        }
    }

    @Test
    public void shouldRejectWrongPasswordAndCloseChannel() {
        // given - credentials are set
        configuration.proxyAuthenticationUsername("testuser");
        configuration.proxyAuthenticationPassword("testpass");

        EmbeddedChannel channel = createChannel();
        try {
            // step 1: initial request
            channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{
                0x05, 0x01, 0x02
            }));
            ByteBuf initialResponse = channel.readOutbound();
            assertThat(initialResponse, is(notNullValue()));
            initialResponse.release();

            // step 2: password auth with WRONG credentials
            byte[] username = "testuser".getBytes();
            byte[] wrongPassword = "wrongpass".getBytes();
            byte[] authRequest = new byte[1 + 1 + username.length + 1 + wrongPassword.length];
            authRequest[0] = 0x01;
            authRequest[1] = (byte) username.length;
            System.arraycopy(username, 0, authRequest, 2, username.length);
            authRequest[2 + username.length] = (byte) wrongPassword.length;
            System.arraycopy(wrongPassword, 0, authRequest, 3 + username.length, wrongPassword.length);

            // when
            channel.writeInbound(Unpooled.wrappedBuffer(authRequest));

            // then - should respond with auth FAILURE
            ByteBuf authResponse = channel.readOutbound();
            assertThat("should write auth failure response", authResponse, is(notNullValue()));
            byte[] authResponseBytes = ByteBufUtil.getBytes(authResponse);
            authResponse.release();

            assertThat("subneg version should be 0x01", authResponseBytes[0], is((byte) 0x01));
            assertThat("status should be FAILURE (0xff)", authResponseBytes[1], is((byte) 0xff));

            // and - channel should be closed
            channel.runPendingTasks();
            assertFalse("channel should be closed after auth failure", channel.isActive());
        } finally {
            safeClose(channel);
        }
    }

    @Test
    public void shouldRejectWrongUsernameAndCloseChannel() {
        // given - credentials are set; the constant-time username comparison must still reject a
        // mismatching username exactly as before (behaviour-preserving after the timing-safe compare)
        configuration.proxyAuthenticationUsername("testuser");
        configuration.proxyAuthenticationPassword("testpass");

        EmbeddedChannel channel = createChannel();
        try {
            // step 1: initial request
            channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{
                0x05, 0x01, 0x02
            }));
            ByteBuf initialResponse = channel.readOutbound();
            assertThat(initialResponse, is(notNullValue()));
            initialResponse.release();

            // step 2: password auth with WRONG username but correct password
            byte[] wrongUsername = "wronguser".getBytes();
            byte[] password = "testpass".getBytes();
            byte[] authRequest = new byte[1 + 1 + wrongUsername.length + 1 + password.length];
            authRequest[0] = 0x01;
            authRequest[1] = (byte) wrongUsername.length;
            System.arraycopy(wrongUsername, 0, authRequest, 2, wrongUsername.length);
            authRequest[2 + wrongUsername.length] = (byte) password.length;
            System.arraycopy(password, 0, authRequest, 3 + wrongUsername.length, password.length);

            // when
            channel.writeInbound(Unpooled.wrappedBuffer(authRequest));

            // then - should respond with auth FAILURE
            ByteBuf authResponse = channel.readOutbound();
            assertThat("should write auth failure response", authResponse, is(notNullValue()));
            byte[] authResponseBytes = ByteBufUtil.getBytes(authResponse);
            authResponse.release();

            assertThat("subneg version should be 0x01", authResponseBytes[0], is((byte) 0x01));
            assertThat("status should be FAILURE (0xff)", authResponseBytes[1], is((byte) 0xff));

            // and - channel should be closed
            channel.runPendingTasks();
            assertFalse("channel should be closed after auth failure", channel.isActive());
        } finally {
            safeClose(channel);
        }
    }

    @Test
    public void shouldReplaceProxyHandlerOnConnectCommand() {
        // given - complete initial handshake first
        EmbeddedChannel channel = createChannel();
        try {
            channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{
                0x05, 0x01, 0x00  // SOCKS5, 1 method, NO_AUTH
            }));
            ByteBuf initialResponse = channel.readOutbound();
            assertThat(initialResponse, is(notNullValue()));
            initialResponse.release();

            // when - CONNECT command to 127.0.0.1:443
            byte[] connectCommand = new byte[]{
                0x05,                       // SOCKS5
                0x01,                       // CONNECT
                0x00,                       // reserved
                0x01,                       // IPv4 address type
                0x7f, 0x00, 0x00, 0x01,     // 127.0.0.1
                0x01, (byte) 0xBB           // port 443
            };
            channel.writeInbound(Unpooled.wrappedBuffer(connectCommand));
            channel.runPendingTasks();

            // then - Socks5ProxyHandler should be removed from the pipeline
            // (forwardConnection replaces it with Socks5ConnectHandler, which then
            // attempts Bootstrap.connect and fails in an EmbeddedChannel context)
            assertThat("Socks5ProxyHandler should be removed",
                channel.pipeline().get(Socks5ProxyHandler.class), is(nullValue()));
        } finally {
            safeClose(channel);
        }
    }

    @Test
    public void shouldRejectUnsupportedCommandType() {
        // given - complete initial handshake
        EmbeddedChannel channel = createChannel();
        try {
            channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{
                0x05, 0x01, 0x00  // SOCKS5, 1 method, NO_AUTH
            }));
            ByteBuf initialResponse = channel.readOutbound();
            assertThat(initialResponse, is(notNullValue()));
            initialResponse.release();

            // when - BIND command (0x02) which is unsupported
            byte[] bindCommand = new byte[]{
                0x05,                       // SOCKS5
                0x02,                       // BIND (unsupported)
                0x00,                       // reserved
                0x01,                       // IPv4 address type
                0x7f, 0x00, 0x00, 0x01,     // 127.0.0.1
                0x00, 0x50                  // port 80
            };
            channel.writeInbound(Unpooled.wrappedBuffer(bindCommand));

            // then - should respond with COMMAND_UNSUPPORTED
            ByteBuf response = channel.readOutbound();
            assertThat("should write command response", response, is(notNullValue()));
            byte[] responseBytes = ByteBufUtil.getBytes(response);
            response.release();

            assertThat("version should be 0x05", responseBytes[0], is((byte) 0x05));
            assertThat("status should be COMMAND_UNSUPPORTED (0x07)", responseBytes[1], is((byte) 0x07));

            // and - channel should be closed
            channel.runPendingTasks();
            assertFalse("channel should be closed after unsupported command", channel.isActive());
        } finally {
            safeClose(channel);
        }
    }

    @Test
    public void shouldCloseChannelOnException() {
        EmbeddedChannel channel = createChannel();
        try {
            // given
            assertTrue("channel should be active initially", channel.isActive());

            // when
            channel.pipeline().fireExceptionCaught(new RuntimeException("simulated error"));
            channel.runPendingTasks();

            // then
            assertFalse("channel should be closed on exception", channel.isActive());
        } finally {
            safeClose(channel);
        }
    }

    @Test
    public void shouldRespondWithUnacceptedWhenNoMatchingAuthMethod() {
        // given - client only offers an unsupported auth method (GSSAPI = 0x01)
        // but NO_AUTH is not in the list
        // NOTE: The handler always selects NO_AUTH or PASSWORD from the offered methods.
        // When neither is present, it returns UNACCEPTED.
        // However, the Socks5InitialRequestDecoder requires valid method values.
        // We'll test with only GSSAPI (0x01) which is not NO_AUTH or PASSWORD.
        EmbeddedChannel channel = createChannel();
        try {
            byte[] initialRequest = new byte[]{
                0x05,       // SOCKS5
                0x01,       // 1 auth method
                0x01        // GSSAPI (not supported by handler)
            };

            // when
            channel.writeInbound(Unpooled.wrappedBuffer(initialRequest));

            // then - handler falls back to NO_AUTH requirement, but since GSSAPI != NO_AUTH,
            // the stream filter finds no match and returns UNACCEPTED
            ByteBuf response = channel.readOutbound();
            assertThat("should write initial response", response, is(notNullValue()));
            byte[] responseBytes = ByteBufUtil.getBytes(response);
            response.release();

            assertThat("version should be 0x05", responseBytes[0], is((byte) 0x05));
            assertThat("auth method should be UNACCEPTED (0xff)", responseBytes[1], is((byte) 0xff));
        } finally {
            safeClose(channel);
        }
    }

    @Test
    public void shouldRefuseNoAuthWhenCredentialsConfigured() {
        configuration.proxyAuthenticationUsername("testuser");
        configuration.proxyAuthenticationPassword("testpass");

        EmbeddedChannel channel = createChannel();
        try {
            // a greeting offering only NO_AUTH, followed in the same read by a CONNECT to 127.0.0.1:80
            channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{
                0x05, 0x01, 0x00,
                0x05, 0x01, 0x00, 0x01, 0x7f, 0x00, 0x00, 0x01, 0x00, 0x50
            }));

            assertThat("NO ACCEPTABLE METHODS", greetingReply(channel), is(new byte[]{0x05, (byte) 0xff}));
            channel.runPendingTasks();
            assertFalse("channel should be closed after refusal", channel.isActive());
            assertThat("no command decoder", channel.pipeline().get(Socks5CommandRequestDecoder.class), is(nullValue()));
            assertThat("the proxy handler is not replaced by a connect handler", channel.pipeline().get(Socks5ConnectHandler.class), is(nullValue()));
            assertThat("nothing sent after the greeting reaches the handlers behind", channel.readInbound(), is(nullValue()));
        } finally {
            safeClose(channel);
        }
    }

    @Test
    public void shouldChoosePasswordOverNoAuthWhenCredentialsConfigured() {
        configuration.proxyAuthenticationUsername("testuser");
        configuration.proxyAuthenticationPassword("testpass");

        EmbeddedChannel channel = createChannel();
        try {
            channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{0x05, 0x02, 0x00, 0x02}));

            assertThat("USERNAME/PASSWORD chosen", greetingReply(channel), is(new byte[]{0x05, 0x02}));
            assertThat(channel.pipeline().get(Socks5PasswordAuthRequestDecoder.class), is(notNullValue()));
            assertThat(channel.pipeline().get(Socks5CommandRequestDecoder.class), is(nullValue()));
            assertTrue(channel.isActive());
        } finally {
            safeClose(channel);
        }
    }

    @Test
    public void shouldDiscardWhatFollowsAWrongPassword() {
        configuration.proxyAuthenticationUsername("testuser");
        configuration.proxyAuthenticationPassword("testpass");

        EmbeddedChannel channel = createChannel();
        try {
            channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{0x05, 0x01, 0x02}));
            assertThat(greetingReply(channel), is(new byte[]{0x05, 0x02}));

            byte[] wrong = new byte[]{0x01, 0x08, 't', 'e', 's', 't', 'u', 's', 'e', 'r', 0x05, 'w', 'r', 'o', 'n', 'g'};
            byte[] get = "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes();
            channel.writeInbound(Unpooled.wrappedBuffer(wrong, get));

            assertThat("auth FAILURE", greetingReply(channel), is(new byte[]{0x01, (byte) 0xff}));
            channel.runPendingTasks();
            assertFalse(channel.isActive());
            assertThat("nothing sent after the refused credentials reaches the handlers behind", channel.readInbound(), is(nullValue()));
        } finally {
            safeClose(channel);
        }
    }

    @Test
    public void shouldCloseAndDiscardWhenNoMethodIsAcceptableWithoutCredentials() {
        EmbeddedChannel channel = createChannel();
        try {
            byte[] greeting = new byte[]{0x05, 0x01, 0x01};
            byte[] get = "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes();
            channel.writeInbound(Unpooled.wrappedBuffer(greeting, get));

            assertThat(greetingReply(channel), is(new byte[]{0x05, (byte) 0xff}));
            channel.runPendingTasks();
            assertFalse("channel should be closed after NO ACCEPTABLE METHODS", channel.isActive());
            assertThat("nothing sent after the greeting reaches the handlers behind", channel.readInbound(), is(nullValue()));
        } finally {
            safeClose(channel);
        }
    }

    @Test
    public void shouldPassWhatFollowsTheGreetingInTheSameReadToThePasswordDecoder() {
        configuration.proxyAuthenticationUsername("testuser");
        configuration.proxyAuthenticationPassword("testpass");

        EmbeddedChannel channel = createChannel();
        try {
            byte[] greeting = new byte[]{0x05, 0x01, 0x02};
            byte[] get = "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes();
            channel.writeInbound(Unpooled.wrappedBuffer(greeting, get));

            assertThat("USERNAME/PASSWORD chosen", greetingReply(channel), is(new byte[]{0x05, 0x02}));
            assertThat("what follows the greeting is taken as credentials, which it is not", greetingReply(channel), is(new byte[]{0x01, (byte) 0xff}));
            channel.runPendingTasks();
            assertFalse(channel.isActive());
            assertThat("nothing sent after the greeting gets past the password decoder", channel.readInbound(), is(nullValue()));
        } finally {
            safeClose(channel);
        }
    }

    @Test
    public void shouldAuthenticateCredentialsSentInTheSameReadAsTheGreeting() {
        configuration.proxyAuthenticationUsername("testuser");
        configuration.proxyAuthenticationPassword("testpass");

        EmbeddedChannel channel = createChannel();
        try {
            byte[] greeting = new byte[]{0x05, 0x01, 0x02};
            byte[] credentials = new byte[]{0x01, 0x08, 't', 'e', 's', 't', 'u', 's', 'e', 'r', 0x08, 't', 'e', 's', 't', 'p', 'a', 's', 's'};
            channel.writeInbound(Unpooled.wrappedBuffer(greeting, credentials));

            assertThat("USERNAME/PASSWORD chosen", greetingReply(channel), is(new byte[]{0x05, 0x02}));
            assertThat("auth SUCCESS, after the greeting's reply", greetingReply(channel), is(new byte[]{0x01, 0x00}));
            assertThat(channel.pipeline().get(Socks5CommandRequestDecoder.class), is(notNullValue()));
            assertThat(channel.pipeline().get(Socks5InitialRequestDecoder.class), is(nullValue()));
            assertThat(channel.readInbound(), is(nullValue()));
            assertTrue(channel.isActive());
        } finally {
            safeClose(channel);
        }
    }

    private static byte[] greetingReply(EmbeddedChannel channel) {
        ByteBuf response = channel.readOutbound();
        assertThat("should write a reply", response, is(notNullValue()));
        try {
            return ByteBufUtil.getBytes(response);
        } finally {
            response.release();
        }
    }
}

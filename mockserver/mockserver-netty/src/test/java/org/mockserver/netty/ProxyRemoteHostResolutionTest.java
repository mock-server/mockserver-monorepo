package org.mockserver.netty;

import org.junit.Test;
import org.mockserver.client.MockServerClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * A proxy remote host given as a name must be looked up for each forwarded connection, not once at startup: a startup
 * lookup made before the name resolves is held in the JVM's negative DNS cache, and one made after it resolves pins
 * the address until restart.
 */
public class ProxyRemoteHostResolutionTest {

    @Test(timeout = 30_000)
    public void remoteHostNameIsLookedUpWhenForwardingNotAtStartup() throws Exception {
        MockServer upstream = new MockServer();
        MockServer proxy = null;
        try {
            new MockServerClient("localhost", upstream.getLocalPort())
                .when(request().withPath("/upstream"))
                .respond(response().withBody("upstream_body"));

            proxy = new MockServer(upstream.getLocalPort(), "localhost", 0);

            InetSocketAddress remoteAddress = proxy.getRemoteAddress();
            assertThat("a remote host name is not looked up at startup", remoteAddress.isUnresolved(), is(true));
            assertThat(remoteAddress.getHostString(), is("localhost"));
            assertThat(remoteAddress.getPort(), is(upstream.getLocalPort()));
            assertThat("the name is looked up when the request is forwarded", get(proxy.getLocalPort(), "/upstream"), is("upstream_body"));
        } finally {
            if (proxy != null) {
                proxy.stop();
            }
            upstream.stop();
        }
    }

    @Test(timeout = 30_000)
    public void remoteHostIpLiteralIsKeptAsAnAddress() {
        MockServer proxy = new MockServer(1, "127.0.0.1", 0);
        try {
            InetSocketAddress remoteAddress = proxy.getRemoteAddress();
            assertThat(remoteAddress.isUnresolved(), is(false));
            assertThat(remoteAddress.getAddress().getHostAddress(), is("127.0.0.1"));
        } finally {
            proxy.stop();
        }
    }

    @Test(timeout = 30_000)
    public void blankRemoteHostDefaultsToLocalhostByName() {
        MockServer proxy = new MockServer(1, "", 0);
        try {
            InetSocketAddress remoteAddress = proxy.getRemoteAddress();
            assertThat(remoteAddress.isUnresolved(), is(true));
            assertThat(remoteAddress.getHostString(), is("localhost"));
        } finally {
            proxy.stop();
        }
    }

    private static String get(int port, String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://localhost:" + port + path).openConnection();
        try {
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            assertThat(connection.getResponseCode(), is(200));
            try (InputStream inputStream = connection.getInputStream()) {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                inputStream.transferTo(body);
                return body.toString(StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }
}

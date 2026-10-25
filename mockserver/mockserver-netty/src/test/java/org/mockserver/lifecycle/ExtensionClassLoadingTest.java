package org.mockserver.lifecycle;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import io.netty.handler.codec.http3.Http3Headers;
import org.junit.Assume;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.extension.Extension;
import org.mockserver.inprocess.InProcessMockServer;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpResponse;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.MockServerCaTrustTestSupport;
import org.mockserver.netty.http3.Http3TestClient;
import org.mockserver.test.ClassLoadingProbe;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.netty.http3.Http3TestServer.startWithHttp3;
import static org.mockserver.test.ClassLoadingProbe.extensionOrDataPlaneHookClasses;

/**
 * With no extension configured a server must load no {@code org.mockserver.extension} class, and so cannot have
 * asked {@code ServiceLoader} for extensions (which needs the {@code Extension} class), nor any class only a loaded
 * data-plane hook may need (see {@link ClassLoadingProbe#isExtensionOrDataPlaneHookClass}): not while it serves a
 * control-plane request, a path under {@code /mockserver/extensions/}, data-plane requests over HTTP/1.1, HTTP/2 and
 * HTTP/3 (plain and gRPC), and in-process, nor after serving an expectation to exhaustion, updating the configuration
 * and resetting, the sites the listener hooks sit on. Runs each server in a fresh JVM that logs every class it loads,
 * since this JVM has loaded the extension classes for other tests; the last two runs show the log does see them.
 */
public class ExtensionClassLoadingTest {

    @Test
    public void shouldLoadNoExtensionClassWhenNoExtensionIsConfigured() throws Exception {
        ClassLoadingProbe.Run run = ClassLoadingProbe.run(Probe.class, Probe.TCP);

        assertThat(run.output, run.completed, is(true));
        assertThat("the probe must have started a server: " + run.output, run.loadedClasses, hasItem("org.mockserver.netty.MockServer"));
        assertThat("the probe must have served HTTP/2", run.loadedClasses, hasItem("io.netty.handler.codec.http2.Http2FrameCodec"));
        assertThat(extensionOrDataPlaneHookClasses(run.loadedClasses), is(empty()));
    }

    @Test
    public void shouldLoadNoExtensionClassServingHttp3WhenNoExtensionIsConfigured() throws Exception {
        assumeQuicAvailable();
        ClassLoadingProbe.Run run = ClassLoadingProbe.run(Probe.class, Probe.HTTP3);

        assertThat(run.output, run.completed, is(true));
        assertThat("the probe must have built both HTTP/3 writers", run.loadedClasses,
            hasItems("org.mockserver.netty.http3.Http3ResponseWriter", "org.mockserver.netty.http3.Http3GrpcResponseWriter"));
        assertThat(extensionOrDataPlaneHookClasses(run.loadedClasses), is(empty()));
    }

    @Test
    public void shouldLoadNoExtensionClassInProcessWhenNoExtensionIsConfigured() throws Exception {
        ClassLoadingProbe.Run run = ClassLoadingProbe.run(Probe.class, Probe.IN_PROCESS);

        assertThat(run.output, run.completed, is(true));
        assertThat("the probe must have built an in-process writer", run.loadedClasses, hasItem("org.mockserver.inprocess.InProcessMockServer$InProcessResponseWriter"));
        assertThat(extensionOrDataPlaneHookClasses(run.loadedClasses), is(empty()));
    }

    @Test
    public void shouldSeeTheExtensionClassesLoadWhenAnExtensionIsConfigured() throws Exception {
        ClassLoadingProbe.Run run = ClassLoadingProbe.run(Probe.class, Probe.TCP, Probe.ConfiguredExtension.class.getName());

        assertThat(run.output, run.completed, is(true));
        assertThat(extensionOrDataPlaneHookClasses(run.loadedClasses), hasItem("org.mockserver.extension.ExtensionRegistry"));
        assertThat(extensionOrDataPlaneHookClasses(run.loadedClasses), hasItem("org.mockserver.extension.Extension"));
    }

    @Test
    public void shouldSeeClassesNamedAsDataPlaneHookClassesLoad() throws Exception {
        ClassLoadingProbe.Run run = ClassLoadingProbe.run(Probe.class, Probe.LOAD_HOOK_NAMED_CLASSES);

        assertThat(run.output, run.completed, is(true));
        assertThat(extensionOrDataPlaneHookClasses(run.loadedClasses), contains(
            Probe.TransformingProbeResponseWriter.class.getName(),
            Probe.DataPlaneWriters.class.getName()
        ));
    }

    private static void assumeQuicAvailable() {
        boolean available;
        try {
            available = io.netty.handler.codec.quic.Quic.isAvailable();
        } catch (Throwable notLoaded) {
            available = false;
        }
        Assume.assumeTrue("native QUIC transport not available on this platform", available);
    }

    public static class Probe {
        static final String TCP = "tcp";
        static final String HTTP3 = "http3";
        static final String IN_PROCESS = "in-process";
        static final String LOAD_HOOK_NAMED_CLASSES = "load-hook-named-classes";

        public static class ConfiguredExtension implements Extension {
            @Override
            public String name() {
                return "class-loading-probe";
            }
        }

        // named as data-plane hook classes are, to show the class-load log and its filter see them
        static class TransformingProbeResponseWriter {
        }

        static class DataPlaneWriters {
        }

        public static void main(String[] arguments) throws Exception {
            try {
                switch (arguments[0]) {
                    case TCP -> overTcp(Arrays.copyOfRange(arguments, 1, arguments.length));
                    case HTTP3 -> overHttp3();
                    case IN_PROCESS -> inProcess();
                    case LOAD_HOOK_NAMED_CLASSES -> {
                        new TransformingProbeResponseWriter();
                        new DataPlaneWriters();
                    }
                    default -> throw new IllegalArgumentException(arguments[0]);
                }
            } catch (Throwable throwable) {
                throwable.printStackTrace();
                System.exit(1);
            }
            System.out.println(ClassLoadingProbe.COMPLETED);
            System.exit(0);
        }

        private static void overTcp(String[] extensions) throws Exception {
            Configuration configuration = configuration();
            if (extensions.length > 0) {
                configuration.extensions(extensions[0]);
            }
            MockServer mockServer = new MockServer(configuration);
            int port = mockServer.getLocalPort();
            // a control-plane route, a path under /mockserver/extensions/ and a data-plane request
            send(port, "PUT", "/mockserver/clear");
            send(port, "GET", "/mockserver/extensions/class-loading-probe/x");
            send(port, "GET", "/data");
            exerciseTheListenerHookSites(port);
            overHttp2(port);
            mockServer.stop();
        }

        private static void send(int port, String method, String path) throws Exception {
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL("http://127.0.0.1:" + port + path).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(30_000);
            if (connection.getResponseCode() <= 0) {
                throw new IllegalStateException("no response to " + method + " " + path);
            }
            connection.disconnect();
        }

        // add, serve to exhaustion (postProcess removal), log a served and a not-matched response, update the
        // configuration and reset: every site a listener hook sits on
        private static void exerciseTheListenerHookSites(int port) throws Exception {
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            send(client, "http://127.0.0.1:" + port, "PUT", "/mockserver/expectation", "{\"httpRequest\": {\"path\": \"/probe\"}, \"httpResponse\": {\"body\": \"ok\"}, \"times\": {\"remainingTimes\": 1, \"unlimited\": false}}", 201);
            send(client, "http://127.0.0.1:" + port, "GET", "/probe", null, 200);
            send(client, "http://127.0.0.1:" + port, "GET", "/probe", null, 404);
            send(client, "http://127.0.0.1:" + port, "PUT", "/mockserver/configuration", "{\"maxExpectations\": 500}", 200);
            send(client, "http://127.0.0.1:" + port, "PUT", "/mockserver/reset", "", 200);
        }

        // a matched response, a 404 and a control-plane-classified path no route serves, over TLS-negotiated HTTP/2
        private static void overHttp2(int port) throws Exception {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_2)
                .sslContext(MockServerCaTrustTestSupport.caTrustingSslContext())
                .build();
            String base = "https://localhost:" + port;
            send(client, base, "PUT", "/mockserver/expectation", "{\"httpRequest\": {\"path\": \"/h2\"}, \"httpResponse\": {\"body\": \"ok\"}}", 201);
            java.net.http.HttpResponse<Void> matched = send(client, base, "GET", "/h2", null, 200);
            if (matched.version() != java.net.http.HttpClient.Version.HTTP_2) {
                throw new IllegalStateException("negotiated " + matched.version() + " not HTTP/2");
            }
            send(client, base, "GET", "/h2-unmatched", null, 404);
            send(client, base, "GET", "/mockserver/x", null, 404);
        }

        private static java.net.http.HttpResponse<Void> send(java.net.http.HttpClient client, String base, String method, String path, String body, int expectedStatus) throws Exception {
            java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + path))
                .timeout(java.time.Duration.ofSeconds(30))
                .method(method, body != null ? java.net.http.HttpRequest.BodyPublishers.ofString(body) : java.net.http.HttpRequest.BodyPublishers.noBody());
            java.net.http.HttpResponse<Void> response = client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != expectedStatus) {
                throw new IllegalStateException(method + " " + path + " returned " + response.statusCode() + " not " + expectedStatus);
            }
            return response;
        }

        @SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
        private static void overHttp3() throws Exception {
            MockServer mockServer = startWithHttp3(configuration().http3MaxIdleTimeout(30000L).attemptToProxyIfNoMatchingExpectation(false));
            NioEventLoopGroup clientGroup = new NioEventLoopGroup(1);
            try (Http3TestClient client = Http3TestClient.open(clientGroup, mockServer)) {
                send(java.net.http.HttpClient.newHttpClient(), "http://127.0.0.1:" + mockServer.getLocalPort(), "PUT", "/mockserver/expectation", "{\"httpRequest\": {\"path\": \"/h3\"}, \"httpResponse\": {\"body\": \"ok\"}}", 201);
                expectStatus(client.send(http3Request(mockServer, "GET", "/h3")), 200);
                expectStatus(client.send(http3Request(mockServer, "GET", "/h3-unmatched")), 404);
                expectStatus(client.send(http3Request(mockServer, "GET", "/mockserver/x")), 404);
                // gRPC with no descriptors loaded passes through to matching on the gRPC writer
                expectStatus(client.send(http3Request(mockServer, "POST", "/probe.Service/Method").set("content-type", "application/grpc").set("te", "trailers")), 200);
            } finally {
                clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(10, TimeUnit.SECONDS);
                mockServer.stop();
            }
        }

        private static Http3Headers http3Request(MockServer mockServer, String method, String path) {
            return new DefaultHttp3Headers()
                .method(method)
                .scheme("https")
                .authority("127.0.0.1:" + mockServer.getHttp3Port())
                .path(path);
        }

        private static void expectStatus(Http3TestClient.Exchange exchange, int expectedStatus) throws Exception {
            if (exchange.status() != expectedStatus) {
                throw new IllegalStateException("HTTP/3 returned " + exchange.status() + " not " + expectedStatus);
            }
        }

        // a matched response, a 404, a control-plane-classified path no route serves, verification and a reset
        private static void inProcess() {
            try (InProcessMockServer mockServer = new InProcessMockServer(configuration())) {
                mockServer.upsert(new Expectation(request("/in-process")).thenRespond(response("ok")));
                expectStatus(mockServer.handle(request("/in-process")), 200);
                expectStatus(mockServer.handle(request("/in-process-unmatched")), 404);
                expectStatus(mockServer.handle(request("/mockserver/x")), 404);
                mockServer.verify(request("/in-process"));
                mockServer.reset();
            }
        }

        private static void expectStatus(HttpResponse response, int expectedStatus) {
            if (response.getStatusCode() != expectedStatus) {
                throw new IllegalStateException("in-process returned " + response.getStatusCode() + " not " + expectedStatus);
            }
        }
    }
}

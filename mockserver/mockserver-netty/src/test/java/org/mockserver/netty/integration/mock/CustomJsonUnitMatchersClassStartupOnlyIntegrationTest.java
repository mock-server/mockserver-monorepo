package org.mockserver.netty.integration.mock;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.CustomJsonUnitMatcherProvider;
import org.mockserver.model.HttpResponse;
import org.mockserver.netty.MockServer;
import org.mockserver.scheduler.Scheduler;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonBody.json;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Which class supplies custom json-unit matchers is decided at startup: {@code PUT /mockserver/configuration} cannot
 * change {@code customJsonUnitMatchersClass} on a running server, so a control-plane caller cannot make the server load
 * and construct a class it names. The server started with the class is the control that proves the same expectation
 * does match when the class is given at startup.
 */
public class CustomJsonUnitMatchersClassStartupOnlyIntegrationTest {

    private static final String CUSTOM_MATCHER_BODY = "{ \"price\": \"${json-unit.matches:largerThan100}\" }";

    private static MockServer defaultServer;
    private static MockServerClient defaultServerClient;
    private static MockServer classAtStartupServer;
    private static MockServerClient classAtStartupClient;
    private static NettyHttpClient httpClient;
    private static EventLoopGroup clientEventLoopGroup;

    @BeforeClass
    public static void startServersAndClient() {
        clientEventLoopGroup = new NioEventLoopGroup(3, new Scheduler.SchedulerThreadFactory(
            CustomJsonUnitMatchersClassStartupOnlyIntegrationTest.class.getSimpleName() + "-eventLoop"));
        httpClient = new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, null, false);

        defaultServer = new MockServer();
        defaultServerClient = new MockServerClient("localhost", defaultServer.getLocalPort());

        classAtStartupServer = new MockServer(configuration().customJsonUnitMatchersClass(StartupMatcherProvider.class.getName()));
        classAtStartupClient = new MockServerClient("localhost", classAtStartupServer.getLocalPort());
    }

    @AfterClass
    public static void stopServersAndClient() {
        stopQuietly(defaultServerClient);
        stopQuietly(classAtStartupClient);
        stopQuietly(defaultServer);
        stopQuietly(classAtStartupServer);
        if (clientEventLoopGroup != null) {
            clientEventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
        }
    }

    @Test
    public void shouldNotLetAConfigurationUpdateLoadACustomJsonUnitMatchersClass() throws Exception {
        // when
        String updated = defaultServerClient.updateConfiguration("{ \"customJsonUnitMatchersClass\": \"" + PutMatcherProvider.class.getName() + "\" }");
        defaultServerClient
            .when(request().withPath("/custom-matcher").withBody(json(CUSTOM_MATCHER_BODY)))
            .respond(response().withStatusCode(200).withBody("matched"));
        HttpResponse response = send(defaultServer, "/custom-matcher");

        // then - the named class is never constructed and the server still reports the value in force
        assertThat("unexpected response: " + response, response.getStatusCode(), is(404));
        assertThat(PutMatcherProvider.CONSTRUCTED.get(), is(false));
        assertThat(updated, not(containsString(PutMatcherProvider.class.getName())));
        assertThat(defaultServerClient.retrieveConfiguration(), not(containsString(PutMatcherProvider.class.getName())));
    }

    @Test
    public void shouldLoadACustomJsonUnitMatchersClassGivenAtStartup() throws Exception {
        // given
        classAtStartupClient
            .when(request().withPath("/custom-matcher").withBody(json(CUSTOM_MATCHER_BODY)))
            .respond(response().withStatusCode(200).withBody("matched"));

        // then - the same expectation matches when the class is given at startup, so the test above is not inert
        HttpResponse response = send(classAtStartupServer, "/custom-matcher");
        assertThat("unexpected response: " + response, response.getStatusCode(), is(200));
        assertThat(classAtStartupClient.retrieveConfiguration().replaceAll("\\s", ""),
            containsString("\"customJsonUnitMatchersClass\":\"" + StartupMatcherProvider.class.getName() + "\""));
        assertThat(response.getBodyAsString(), startsWith("matched"));
    }

    private HttpResponse send(MockServer server, String path) throws Exception {
        return httpClient.sendRequest(
            request()
                .withMethod("POST")
                .withHeader(HOST.toString(), "localhost:" + server.getLocalPort())
                .withPath(path)
                .withBody(json("{ \"price\": 250 }"))
        ).get(20, TimeUnit.SECONDS);
    }

    private static Map<String, Matcher<?>> largerThan100() {
        return Map.of("largerThan100", new TypeSafeMatcher<Object>() {
            @Override
            protected boolean matchesSafely(Object item) {
                try {
                    return new BigDecimal(item.toString()).compareTo(BigDecimal.valueOf(100)) > 0;
                } catch (NumberFormatException notANumber) {
                    return false;
                }
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("a number larger than 100");
            }
        });
    }

    public static class StartupMatcherProvider implements CustomJsonUnitMatcherProvider {
        @Override
        public Map<String, Matcher<?>> jsonUnitMatchers() {
            return largerThan100();
        }
    }

    public static class PutMatcherProvider implements CustomJsonUnitMatcherProvider {

        static final AtomicBoolean CONSTRUCTED = new AtomicBoolean();

        public PutMatcherProvider() {
            CONSTRUCTED.set(true);
        }

        @Override
        public Map<String, Matcher<?>> jsonUnitMatchers() {
            return largerThan100();
        }
    }
}

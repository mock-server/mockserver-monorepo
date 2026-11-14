package org.mockserver.netty.integration.mock;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.HttpTemplate;
import org.mockserver.netty.MockServer;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.templates.engine.javascript.JavaScriptTemplateEngine;

import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpTemplate.template;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Whether templates can load Java classes is decided at startup: {@code PUT /mockserver/configuration} cannot
 * change {@code velocityDisallowClassLoading}, {@code javascriptAllowedClasses} or {@code javascriptDisallowedClasses}
 * on a running server. The templates only look a class up by name; the server started with class loading enabled is
 * the control that proves the same template does load the class when the setting is given at startup.
 */
public class TemplateClassAccessStartupOnlyIntegrationTest {

    private static final String VELOCITY_CLASS_LOOKUP = "{ 'statusCode': 200, 'body': \"$!request.class.classLoader.loadClass('java.lang.Runtime').getName()\" }";
    private static final String JAVASCRIPT_CLASS_LOOKUP = "return { statusCode: 200, body: Java.type('java.lang.Runtime') ? 'class loaded' : 'no class' };";

    private static MockServer defaultServer;
    private static MockServerClient defaultServerClient;
    private static MockServer classLoadingAtStartupServer;
    private static MockServerClient classLoadingAtStartupClient;
    private static NettyHttpClient httpClient;
    private static EventLoopGroup clientEventLoopGroup;

    @BeforeClass
    public static void startServersAndClient() {
        clientEventLoopGroup = new NioEventLoopGroup(3, new Scheduler.SchedulerThreadFactory(
            TemplateClassAccessStartupOnlyIntegrationTest.class.getSimpleName() + "-eventLoop"));
        httpClient = new NettyHttpClient(configuration(), new MockServerLogger(), clientEventLoopGroup, null, false);

        defaultServer = new MockServer();
        defaultServerClient = new MockServerClient("localhost", defaultServer.getLocalPort());

        classLoadingAtStartupServer = new MockServer(configuration().velocityDisallowClassLoading(false).javascriptAllowedClasses("java.lang.Runtime"));
        classLoadingAtStartupClient = new MockServerClient("localhost", classLoadingAtStartupServer.getLocalPort());
    }

    @AfterClass
    public static void stopServersAndClient() {
        stopQuietly(defaultServerClient);
        stopQuietly(classLoadingAtStartupClient);
        stopQuietly(defaultServer);
        stopQuietly(classLoadingAtStartupServer);
        if (clientEventLoopGroup != null) {
            clientEventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
        }
    }

    @Test
    public void shouldNotLetAConfigurationUpdateLetVelocityTemplatesLoadJavaClasses() throws Exception {
        // when
        String updated = defaultServerClient.updateConfiguration("{ \"velocityDisallowClassLoading\": false }");
        defaultServerClient
            .when(request().withPath("/velocity-class-lookup"))
            .respond(template(HttpTemplate.TemplateType.VELOCITY, VELOCITY_CLASS_LOOKUP));
        HttpResponse response = send(defaultServer, "/velocity-class-lookup");

        // then - the class lookup renders nothing and the server still reports the value in force
        assertThat("unexpected response: " + response, response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), not(containsString("java.lang.Runtime")));
        assertThat(updated.replaceAll("\\s", ""), containsString("\"velocityDisallowClassLoading\":true"));
        assertThat(defaultServerClient.retrieveConfiguration().replaceAll("\\s", ""), containsString("\"velocityDisallowClassLoading\":true"));
    }

    @Test
    public void shouldNotLetAConfigurationUpdateLetJavaScriptTemplatesLoadJavaClasses() throws Exception {
        Assume.assumeTrue("GraalJS is not on the classpath", JavaScriptTemplateEngine.isPolyglotAvailable());

        // when
        defaultServerClient.updateConfiguration("{ \"javascriptAllowedClasses\": \"*\", \"javascriptDisallowedClasses\": \"java.lang.String\" }");
        defaultServerClient
            .when(request().withPath("/javascript-class-lookup"))
            .respond(template(HttpTemplate.TemplateType.JAVASCRIPT, JAVASCRIPT_CLASS_LOOKUP));
        HttpResponse response = send(defaultServer, "/javascript-class-lookup");

        // then
        assertThat(String.valueOf(response.getBodyAsString()), not(containsString("class loaded")));
        assertThat(defaultServerClient.retrieveConfiguration(), not(containsString("java.lang.String")));
    }

    @Test
    public void shouldLetStartupConfigurationLetTemplatesLoadJavaClasses() throws Exception {
        // given
        classLoadingAtStartupClient
            .when(request().withPath("/velocity-class-lookup"))
            .respond(template(HttpTemplate.TemplateType.VELOCITY, VELOCITY_CLASS_LOOKUP));

        // then - the same lookup works when class loading is enabled at startup, so the test above is not inert
        assertThat(send(classLoadingAtStartupServer, "/velocity-class-lookup").getBodyAsString(), is("java.lang.Runtime"));

        if (JavaScriptTemplateEngine.isPolyglotAvailable()) {
            classLoadingAtStartupClient
                .when(request().withPath("/javascript-class-lookup"))
                .respond(template(HttpTemplate.TemplateType.JAVASCRIPT, JAVASCRIPT_CLASS_LOOKUP));
            assertThat(send(classLoadingAtStartupServer, "/javascript-class-lookup").getBodyAsString(), is("class loaded"));
        }
    }

    private HttpResponse send(MockServer server, String path) throws Exception {
        return httpClient.sendRequest(
            request()
                .withMethod("GET")
                .withHeader(HOST.toString(), "localhost:" + server.getLocalPort())
                .withPath(path)
        ).get(20, TimeUnit.SECONDS);
    }
}

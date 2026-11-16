package org.mockserver.thin;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockserver.client.MockServerClient;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.springtest.MockServerTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

@MockServerTest("server.url=http://localhost:${mockServerPort}")
@RunWith(SpringRunner.class)
@ContextConfiguration(classes = MockServerTestThinJarTest.Config.class)
public class MockServerTestThinJarTest {

    @Configuration
    static class Config {
    }

    @Value("${server.url}")
    private String serverUrl;

    private MockServerClient mockServerClient;

    @Test
    public void servesExpectationCreatedThroughInjectedClient() throws Exception {
        mockServerClient.when(request().withPath("/thin")).respond(response().withStatusCode(201).withBody("spring"));

        HttpResponse<String> response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(serverUrl + "/thin")).build(),
            HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(201, response.statusCode());
        assertEquals("spring", response.body());
    }

    @Test
    public void listenerComesFromThinJarAndServerFromNettyNoDependencies() throws Exception {
        assertTrue(jarOf(MockServerTest.class), jarOf(MockServerTest.class).contains("mockserver-spring-test-listener-no-dependencies"));
        assertTrue(jarOf(ClientAndServer.class), jarOf(ClientAndServer.class).contains("mockserver-netty-no-dependencies"));
        Class.forName("shaded_package.org.slf4j.jul.JDK14LoggerAdapter");
        assertThrows(ClassNotFoundException.class, () -> Class.forName("io.netty.bootstrap.Bootstrap"));
    }

    private static String jarOf(Class<?> type) {
        return type.getProtectionDomain().getCodeSource().getLocation().toString();
    }
}

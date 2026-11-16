package org.mockserver.thin;

import org.junit.Rule;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.MockServerRule;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class MockServerRuleThinJarTest {

    @Rule
    public MockServerRule mockServerRule = new MockServerRule(this);

    private MockServerClient mockServerClient;

    @Test
    public void servesExpectationCreatedThroughInjectedClient() throws Exception {
        mockServerClient.when(request().withPath("/thin")).respond(response().withStatusCode(201).withBody("rule"));

        HttpResponse<String> response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + mockServerRule.getPort() + "/thin")).build(),
            HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(201, response.statusCode());
        assertEquals("rule", response.body());
    }

    @Test
    public void ruleComesFromThinJarAndServerFromNettyNoDependencies() throws Exception {
        assertTrue(jarOf(MockServerRule.class), jarOf(MockServerRule.class).contains("mockserver-junit-rule-no-dependencies"));
        assertTrue(jarOf(ClientAndServer.class), jarOf(ClientAndServer.class).contains("mockserver-netty-no-dependencies"));
        Class.forName("shaded_package.org.slf4j.jul.JDK14LoggerAdapter");
        assertThrows(ClassNotFoundException.class, () -> Class.forName("io.netty.bootstrap.Bootstrap"));
    }

    private static String jarOf(Class<?> type) {
        return type.getProtectionDomain().getCodeSource().getLocation().toString();
    }
}

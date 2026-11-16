package org.mockserver.thin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockserver.client.MockServerClient;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.jupiter.MockServerExtension;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

@ExtendWith(MockServerExtension.class)
class MockServerExtensionThinJarTest {

    @Test
    void servesExpectationCreatedThroughInjectedClient(MockServerClient client) throws Exception {
        client.when(request().withPath("/thin")).respond(response().withStatusCode(201).withBody("jupiter"));

        HttpResponse<String> response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + client.getPort() + "/thin")).build(),
            HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(201, response.statusCode());
        assertEquals("jupiter", response.body());
    }

    @Test
    void extensionComesFromThinJarAndServerFromNettyNoDependencies() throws Exception {
        assertTrue(jarOf(MockServerExtension.class).contains("mockserver-junit-jupiter-no-dependencies"), jarOf(MockServerExtension.class));
        assertTrue(jarOf(ClientAndServer.class).contains("mockserver-netty-no-dependencies"), jarOf(ClientAndServer.class));
        Class.forName("shaded_package.org.slf4j.jul.JDK14LoggerAdapter");
        assertThrows(ClassNotFoundException.class, () -> Class.forName("io.netty.bootstrap.Bootstrap"));
    }

    private static String jarOf(Class<?> type) {
        return type.getProtectionDomain().getCodeSource().getLocation().toString();
    }
}

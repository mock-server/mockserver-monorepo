package org.mockserver.cli;

import com.sun.net.httpserver.HttpServer;
import org.junit.Assume;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

/**
 * {@code SIGTERM} (as sent by {@code docker stop}) to a command-line MockServer that persists recorded traffic runs a
 * clean stop, so every exchange it proxied is in the recorded-requests NDJSON file and the recorded-expectations file
 * when the process exits. Without the shutdown hook the last buffered lines and the last coalesced write are lost.
 */
public class MainShutdownHookIntegrationTest {

    private static final int EXCHANGES = 20;

    @Test
    public void shouldWriteEverythingPersistedWhenTheProcessIsSentSigterm() throws Exception {
        Assume.assumeFalse("Process.destroy() is not SIGTERM on Windows", System.getProperty("os.name").toLowerCase().startsWith("windows"));
        Path directory = Files.createTempDirectory("mainShutdownHook");
        Path recordedRequests = directory.resolve("recordedRequests.ndjson");
        Path recordedExpectations = directory.resolve("persistedRecordedExpectations.json");
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        upstream.start();
        Process process = null;
        StringBuffer output = new StringBuffer();
        try {
            List<String> command = Arrays.asList(
                System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
                "-Dmockserver.logLevel=WARN",
                "-Dmockserver.persistRecordedRequestsToDisk=true",
                "-Dmockserver.persistedRecordedRequestsPath=" + recordedRequests,
                "-Dmockserver.persistRecordedExpectations=true",
                "-Dmockserver.persistedRecordedExpectationsPath=" + recordedExpectations,
                "-cp", System.getProperty("java.class.path"),
                Child.class.getName(),
                "-serverPort", "0",
                "-proxyRemotePort", String.valueOf(upstream.getAddress().getPort()),
                "-proxyRemoteHost", "127.0.0.1"
            );
            ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
            processBuilder.environment().remove("JAVA_TOOL_OPTIONS");
            process = processBuilder.start();
            CompletableFuture<Integer> port = readPortAndOutput(process, output);
            int mockServerPort = port.get(60, TimeUnit.SECONDS);

            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            for (int i = 0; i < EXCHANGES; i++) {
                HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + mockServerPort + "/exchange-" + i)).timeout(java.time.Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString()
                );
                assertThat(output.toString(), response.statusCode(), is(200));
            }

            // when - as docker stop does
            process.destroy();

            // then
            assertThat(output.toString(), process.waitFor(30, TimeUnit.SECONDS), is(true));
            List<String> lines = Files.readAllLines(recordedRequests, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.trim().isEmpty())
                .collect(Collectors.toList());
            assertThat(output.toString(), lines, hasSize(EXCHANGES));
            String expectations = new String(Files.readAllBytes(recordedExpectations), StandardCharsets.UTF_8);
            for (int i = 0; i < EXCHANGES; i++) {
                assertThat(lines.get(i), containsString("/exchange-" + i + "\""));
                assertThat(expectations, containsString("/exchange-" + i + "\""));
            }
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
            upstream.stop(0);
        }
    }

    private static CompletableFuture<Integer> readPortAndOutput(Process process, StringBuffer output) {
        CompletableFuture<Integer> port = new CompletableFuture<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    output.append(line).append('\n');
                    if (line.startsWith(Child.STARTED_PORT)) {
                        port.complete(Integer.parseInt(line.substring(Child.STARTED_PORT.length()).trim()));
                    }
                }
            } catch (Exception exception) {
                port.completeExceptionally(exception);
            }
            port.completeExceptionally(new IllegalStateException("MockServer exited without starting:\n" + output));
        }, "MainShutdownHookIntegrationTest-output");
        reader.setDaemon(true);
        reader.start();
        return port;
    }

    /**
     * Starts MockServer through the command-line entry point and prints the port it bound; the server's threads keep
     * the process running after this returns.
     */
    public static class Child {
        static final String STARTED_PORT = "STARTED_PORT=";

        public static void main(String[] arguments) {
            Main.main(arguments);
            List<Integer> ports = Main.getLastStartedPorts();
            System.out.println(STARTED_PORT + (ports == null || ports.isEmpty() ? "none" : ports.get(0)));
            System.out.flush();
        }
    }
}

package org.mockserver.cli;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockserver.client.MockServerClient;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.fail;
import static org.mockserver.model.Delay.seconds;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class MainShutdownHookProcessTest {
    @Rule
    public TemporaryFolder directory = new TemporaryFolder();

    @Test
    public void shouldDrainAnAcceptedRequestAndPersistItsResponseOnSigterm() throws Exception {
        Assume.assumeFalse("Process.destroy sends SIGTERM on Unix", System.getProperty("os.name").startsWith("Windows"));
        Path portFile = directory.getRoot().toPath().resolve("port");
        Path recording = directory.getRoot().toPath().resolve("traffic.ndjson");
        Path output = directory.getRoot().toPath().resolve("child.log");
        ProcessBuilder builder = new ProcessBuilder(
            System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
            "-Xmx256m", "-Dmockserver.localBoundIP=127.0.0.1", "-Dmockserver.useNativeTransport=false",
            "-Dmockserver.nioEventLoopThreadCount=2", "-Dmockserver.clientNioEventLoopThreadCount=2",
            "-Dmockserver.stopDrainMillis=20000", "-Dmockserver.logLevel=ERROR",
            "-Dmockserver.persistRecordedRequestsToDisk=true", "-Dmockserver.persistedRecordedRequestsPath=" + recording,
            "-cp", System.getProperty("java.class.path"), Probe.class.getName(), portFile.toString())
            .redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().keySet().removeIf(key -> key.startsWith("MOCKSERVER_")
            || key.equals("JAVA_TOOL_OPTIONS") || key.equals("JDK_JAVA_OPTIONS"));
        Process child = builder.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!Files.exists(portFile) && child.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            assertThat(Files.readString(output), Files.exists(portFile), is(true));
            int port = Integer.parseInt(Files.readString(portFile));
            MockServerClient client = new MockServerClient("127.0.0.1", port);
            client.when(request().withPath("/drain-before-exit"))
                .respond(response().withBody("completed during shutdown").withDelay(seconds(10)));
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            CompletableFuture<HttpResponse<String>> response = http.sendAsync(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/drain-before-exit")).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (client.retrieveRecordedRequests(request().withPath("/drain-before-exit")).length == 0) {
                if (System.nanoTime() >= deadline) {
                    fail("the child never accepted the request: " + Files.readString(output));
                }
                Thread.sleep(10);
            }
            assertThat("the request must still be running when SIGTERM arrives", response.isDone(), is(false));
            child.destroy();

            HttpResponse<String> completed = response.get(30, TimeUnit.SECONDS);
            assertThat(completed.statusCode(), is(200));
            assertThat(completed.body(), is("completed during shutdown"));
            assertThat("the child must finish its shutdown hook", child.waitFor(30, TimeUnit.SECONDS), is(true));
            assertThat(Files.readString(recording), containsString("/drain-before-exit"));
            assertThat(Files.readString(recording), containsString("completed during shutdown"));
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    public static class Probe {
        public static void main(String[] arguments) throws Exception {
            Main.main("run", "-p", "0");
            Path portFile = Path.of(arguments[0]);
            Path temporary = Files.createTempFile(portFile.getParent(), "port-", ".tmp");
            Files.writeString(temporary, Main.getLastStartedPorts().get(0).toString());
            Files.move(temporary, portFile, StandardCopyOption.ATOMIC_MOVE);
        }
    }
}

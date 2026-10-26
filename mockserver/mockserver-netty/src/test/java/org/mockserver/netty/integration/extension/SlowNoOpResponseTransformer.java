package org.mockserver.netty.integration.extension;

import org.mockserver.extension.ResponseTransformer;
import org.mockserver.extension.TransformContext;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A response transformer that waits and then returns the response it was given, so every response is written from
 * the extension pool, after the code that wrote it has moved on. A server running it must answer exactly as one
 * running no transformer. To run a whole test suite with it, pass
 * {@code -Dmockserver.extensions=org.mockserver.netty.integration.extension.SlowNoOpResponseTransformer}; the wait is
 * {@code mockserver.test.slowTransformerMillis} (default 5), and the number of responses it saw is printed when the
 * JVM exits, and appended to the file {@code mockserver.test.slowTransformerCountFile} names, if set.
 */
public class SlowNoOpResponseTransformer implements ResponseTransformer {

    private static final long WAIT_MILLIS = Long.getLong("mockserver.test.slowTransformerMillis", 5L);
    private static final AtomicLong TRANSFORMED = new AtomicLong();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            String line = "SlowNoOpResponseTransformer transformed " + TRANSFORMED.get() + " responses" + System.lineSeparator();
            System.out.print(line);
            String countFile = System.getProperty("mockserver.test.slowTransformerCountFile");
            if (countFile != null) {
                try {
                    Files.write(Paths.get(countFile), line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException ignored) {
                    // only a diagnostic
                }
            }
        }));
    }

    public static long transformed() {
        return TRANSFORMED.get();
    }

    @Override
    public String name() {
        return "slow-no-op-transformer";
    }

    @Override
    public HttpResponse transform(HttpRequest request, HttpResponse response, TransformContext context) throws InterruptedException {
        TRANSFORMED.incrementAndGet();
        TimeUnit.MILLISECONDS.sleep(WAIT_MILLIS);
        return response;
    }
}

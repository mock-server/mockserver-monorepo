package org.mockserver.extension;

import org.mockserver.configuration.Configuration;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static org.mockserver.configuration.Configuration.configuration;

/**
 * Runs {@link RequestFilter} and {@link ResponseTransformer} extensions as a server would, with no server: in the
 * server's order, on the data-plane hook pool, with the same budget, failure handling and fail-open rules, and
 * waiting for the outcome. Shipped in the {@code mockserver-core} test JAR for extension authors' unit tests.
 * <pre>
 * DataPlaneOutcome outcome = DataPlaneExtensionTestKit.filter(request().withPath("/a"), new MyFilter());
 * DataPlaneExtensionTestKit.assertProceeded(outcome);
 * </pre>
 */
public final class DataPlaneExtensionTestKit {

    public static final String CORRELATION_ID = "data-plane-test-kit";

    private final Configuration configuration;

    /**
     * @param configuration supplies {@code extensionDataPlaneTimeoutMillis} and {@code extensionDataPlaneMaxInFlight}
     */
    public DataPlaneExtensionTestKit(Configuration configuration) {
        this.configuration = configuration;
    }

    /**
     * @return a context for a plain HTTP/1.1 request from 127.0.0.1, to adjust and {@code build()}
     */
    public static FilterContext.Builder filterContext() {
        return FilterContext.builder().correlationId(CORRELATION_ID).protocol(Protocol.HTTP_1_1).remoteAddress("127.0.0.1");
    }

    /**
     * @return a context for a response an expectation answered, neither streamed nor generated
     */
    public static TransformContext transformContext() {
        return TransformContext.of(filterContext().build(), false, null, false);
    }

    public static DataPlaneOutcome filter(HttpRequest request, RequestFilter... filters) {
        return new DataPlaneExtensionTestKit(configuration()).filter(request, filterContext().build(), filters);
    }

    public static DataPlaneOutcome transform(HttpRequest request, HttpResponse response, ResponseTransformer... transformers) {
        return new DataPlaneExtensionTestKit(configuration()).transform(request, response, transformContext(), transformers);
    }

    /**
     * Runs the filters, sorted by ascending priority then name as a server sorts them, and waits for the outcome.
     */
    public DataPlaneOutcome filter(HttpRequest request, FilterContext context, RequestFilter... filters) {
        DataPlaneHooks hooks = hooks(chain(filters, ExtensionHook.REQUEST_FILTER, filter -> ((RequestFilter) filter).failOpen()), List.of());
        try {
            return hooks.filterAndWait(request, context, null);
        } finally {
            hooks.stop();
        }
    }

    /**
     * Runs the transformers, sorted by descending priority then name as a server sorts them, and waits for the
     * outcome.
     */
    public DataPlaneOutcome transform(HttpRequest request, HttpResponse response, TransformContext context, ResponseTransformer... transformers) {
        List<DataPlaneHooks.Hook> chain = chain(transformers, ExtensionHook.RESPONSE_TRANSFORMER, transformer -> ((ResponseTransformer) transformer).failOpen());
        Collections.reverse(chain);
        DataPlaneHooks hooks = hooks(List.of(), chain);
        try {
            return hooks.transformAndWait(request, response, context, null);
        } finally {
            hooks.stop();
        }
    }

    private DataPlaneHooks hooks(List<DataPlaneHooks.Hook> filters, List<DataPlaneHooks.Hook> transformers) {
        return new DataPlaneHooks(filters, transformers, configuration.extensionDataPlaneMaxInFlight(), configuration.extensionDataPlaneTimeoutMillis(), null);
    }

    private static List<DataPlaneHooks.Hook> chain(Extension[] extensions, ExtensionHook type, Function<Extension, Boolean> failOpen) {
        List<LoadedExtension> loaded = new ArrayList<>();
        for (Extension extension : extensions) {
            loaded.add(new LoadedExtension(extension, extension.name(), extension.priority(), "test"));
        }
        loaded.sort(Comparator.comparingInt(LoadedExtension::priority).thenComparing(LoadedExtension::name));
        List<DataPlaneHooks.Hook> chain = new ArrayList<>();
        for (LoadedExtension extension : loaded) {
            chain.add(new DataPlaneHooks.Hook(extension, type, failOpen.apply(extension.extension())));
        }
        return chain;
    }

    // ---- assertions, which throw AssertionError so they work under any test framework

    public static void assertProceeded(DataPlaneOutcome outcome) {
        check(outcome.isProceed() && !outcome.isFailure(), "expected the request to proceed, but was " + outcome);
    }

    public static void assertResponded(DataPlaneOutcome outcome, int statusCode) {
        check(outcome.isRespond(), "expected a filter to respond, but was " + outcome);
        check(Objects.equals(outcome.response().getStatusCode(), statusCode), "expected status " + statusCode + ", but was " + outcome);
    }

    public static void assertTransformed(DataPlaneOutcome outcome, int statusCode) {
        check(!outcome.isFailure() && outcome.response() != null, "expected a transformed response, but was " + outcome);
        check(Objects.equals(outcome.response().getStatusCode(), statusCode), "expected status " + statusCode + ", but was " + outcome);
    }

    /**
     * @param failureType one of the {@link DataPlaneOutcome} failure types, such as {@link DataPlaneOutcome#THREW}
     */
    public static void assertFailed(DataPlaneOutcome outcome, int statusCode, String failureType) {
        check(outcome.isFailure(), "expected a failure, but was " + outcome);
        check(Objects.equals(outcome.response().getStatusCode(), statusCode), "expected status " + statusCode + ", but was " + outcome);
        check(Objects.equals(outcome.failureType(), failureType), "expected failure type " + failureType + ", but was " + outcome);
    }

    /**
     * Asserts that every field the transport owns is the same on {@code filtered} as on {@code original}: the stream
     * id, keep-alive, TLS, protocol, client certificates, JWT, client and socket addresses, correlation id, received
     * timestamp, respond-before-body, the undecodable query string and the transient flags.
     */
    public static void assertTransportFieldsUnchanged(HttpRequest original, HttpRequest filtered) {
        Map<String, Object[]> fields = new LinkedHashMap<>();
        fields.put("streamId", new Object[]{original.getStreamId(), filtered.getStreamId()});
        fields.put("keepAlive", new Object[]{original.isKeepAlive(), filtered.isKeepAlive()});
        fields.put("secure", new Object[]{original.isSecure(), filtered.isSecure()});
        fields.put("protocol", new Object[]{original.getProtocol(), filtered.getProtocol()});
        fields.put("clientCertificateChain", new Object[]{original.getClientCertificateChain(), filtered.getClientCertificateChain()});
        fields.put("clientCertificate", new Object[]{original.getClientCertificate(), filtered.getClientCertificate()});
        fields.put("jwt", new Object[]{original.getJwt(), filtered.getJwt()});
        fields.put("clientIp", new Object[]{original.getClientIp(), filtered.getClientIp()});
        fields.put("remoteAddress", new Object[]{original.getRemoteAddress(), filtered.getRemoteAddress()});
        fields.put("localAddress", new Object[]{original.getLocalAddress(), filtered.getLocalAddress()});
        fields.put("logCorrelationId", new Object[]{original.getLogCorrelationId(), filtered.getLogCorrelationId()});
        fields.put("receivedTimestamp", new Object[]{original.getReceivedTimestamp(), filtered.getReceivedTimestamp()});
        fields.put("respondBeforeBody", new Object[]{original.getRespondBeforeBody(), filtered.getRespondBeforeBody()});
        fields.put("undecodableQueryString", new Object[]{original.getUndecodableQueryString(), filtered.getUndecodableQueryString()});
        fields.put("loadGenerated", new Object[]{original.isLoadGenerated(), filtered.isLoadGenerated()});
        fields.put("controlPlaneRequest", new Object[]{original.isControlPlaneRequest(), filtered.isControlPlaneRequest()});
        List<String> changed = new ArrayList<>();
        fields.forEach((name, values) -> {
            if (!Objects.equals(values[0], values[1])) {
                changed.add(name + ": " + values[0] + " -> " + values[1]);
            }
        });
        check(changed.isEmpty(), "transport-owned fields changed: " + changed);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

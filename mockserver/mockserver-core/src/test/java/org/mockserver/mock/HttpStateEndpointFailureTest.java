package org.mockserver.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.MockedStatic;
import org.mockserver.configuration.Configuration;
import org.mockserver.file.FileStore;
import org.mockserver.grpc.GrpcHealthRegistry;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.action.http.ChaosExperimentOrchestrator;
import org.mockserver.mock.action.http.ChaosProfileLibrary;
import org.mockserver.mock.action.http.GrpcChaosRegistry;
import org.mockserver.mock.action.http.LoadScenarioRegistry;
import org.mockserver.mock.action.http.PreemptionSimulator;
import org.mockserver.mock.action.http.ServiceChaosRegistry;
import org.mockserver.mock.action.http.TcpChaosRegistry;
import org.mockserver.mock.audit.AuditStore;
import org.mockserver.mock.drift.DriftStore;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.socket.tls.KeyAndCertificateFactoryFactory;
import org.mockserver.state.StateBackend;
import org.mockserver.time.TimeService;
import org.slf4j.event.Level;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.responsewriter.ControlPlaneFailureResponse.UNEXPECTED_FAILURE_MESSAGE;

/**
 * How each control-plane endpoint with its own catch-all answers what it caught: the caller's own mistake stays a
 * {@code 400} with its message, while a fault inside MockServer is a {@code 500} with a generic message naming a
 * correlation id, logged once at {@code ERROR} with the stack trace, and the fault's text never reaches the caller.
 * <p>
 * A fault is injected either through a request whose body accessors throw (the endpoint reads its body inside its
 * try block) or, for an endpoint that reads no body, through the store it lists.
 */
@RunWith(Parameterized.class)
public class HttpStateEndpointFailureTest {

    private static final String FAULT_DETAIL = "internal detail of the fault";
    private static final String UNREADABLE_JSON = "{not json";
    private static final String UNLOADABLE_SPEC = "{\"spec\":\"not a spec\",\"baseUrl\":\"http://localhost:1\"}";

    @Parameterized.Parameter
    public Case testCase;

    @Parameterized.Parameters(name = "{0}")
    public static List<Case> cases() {
        return Arrays.asList(
            // identity providers
            fault("PUT", "/mockserver/oidc"),
            badInput("PUT", "/mockserver/oidc", UNREADABLE_JSON, "Unexpected character"),
            fault("PUT", "/mockserver/saml"),
            badInput("PUT", "/mockserver/saml", UNREADABLE_JSON, "Unexpected character"),
            fault("PUT", "/mockserver/scim"),
            badInput("PUT", "/mockserver/scim", UNREADABLE_JSON, "Unexpected character"),
            // import, promote, baseline, pact
            fault("PUT", "/mockserver/import"),
            badInput("PUT", "/mockserver/import", UNREADABLE_JSON, "Unexpected character"),
            badInput("PUT", "/mockserver/import?format=har", "{}", "not a valid HAR document"),
            fault("PUT", "/mockserver/recordings/promote"),
            badInput("PUT", "/mockserver/recordings/promote", UNREADABLE_JSON, "incorrect request matcher json format"),
            fault("PUT", "/mockserver/baseline/compare"),
            badInput("PUT", "/mockserver/baseline/compare", UNREADABLE_JSON, "Unexpected character"),
            fault("PUT", "/mockserver/pact/import"),
            badInput("PUT", "/mockserver/pact/import", "{}", "not a valid Pact contract"),
            fault("PUT", "/mockserver/pact/verify"),
            badInput("PUT", "/mockserver/pact/verify", UNREADABLE_JSON, "error"),
            // CRUD resources
            fault("PUT", "/mockserver/crud"),
            badInput("PUT", "/mockserver/crud", UNREADABLE_JSON, "failed to register CRUD resource: Unexpected character"),
            badInput("PUT", "/mockserver/crud", "{\"basePath\":\"/mockserver/items\"}", "failed to register CRUD resource: basePath must not overlap"),
            // gRPC
            fault("PUT", "/mockserver/grpc/descriptors"),
            badInput("PUT", "/mockserver/grpc/descriptors", new byte[]{0x0A, 0x05, 0x01}, "failed to load gRPC descriptor: "),
            storeFault("PUT", "/mockserver/grpc/services", "grpcDescriptorStore", GrpcProtoDescriptorStore.class,
                store -> when(store.getAllServices()).thenThrow(new IllegalStateException(FAULT_DETAIL))),
            fault("PUT", "/mockserver/grpc/health"),
            badInput("PUT", "/mockserver/grpc/health", UNREADABLE_JSON, "failed to set gRPC health status"),
            // WASM
            fault("PUT", "/mockserver/wasm/modules?name=module"),
            fault("POST", "/mockserver/wasm/test"),
            badInput("POST", "/mockserver/wasm/test", UNREADABLE_JSON, "failed to test WASM module: Unexpected character"),
            // files
            fault("PUT", "/mockserver/files/store"),
            badInput("PUT", "/mockserver/files/store", "{\"name\":\"a\",\"content\":\"%%%\",\"base64\":true}", "failed to store file: Illegal base64 character"),
            fault("PUT", "/mockserver/files/retrieve"),
            badInput("PUT", "/mockserver/files/retrieve", UNREADABLE_JSON, "failed to retrieve file: Unexpected character"),
            fault("PUT", "/mockserver/files/delete"),
            badInput("PUT", "/mockserver/files/delete", UNREADABLE_JSON, "failed to delete file: Unexpected character"),
            storeFault("PUT", "/mockserver/files/list", "fileStore", FileStore.class,
                store -> when(store.listFiles()).thenThrow(new IllegalStateException(FAULT_DETAIL))),
            // request diagnostics
            fault("PUT", "/mockserver/debugMismatch"),
            badInput("PUT", "/mockserver/debugMismatch", UNREADABLE_JSON, "failed to debug request mismatch"),
            fault("PUT", "/mockserver/explainUnmatched"),
            fault("PUT", "/mockserver/diff"),
            badInput("PUT", "/mockserver/diff", UNREADABLE_JSON, "failed to diff requests"),
            // clock
            fault("PUT", "/mockserver/clock"),
            badInput("PUT", "/mockserver/clock", UNREADABLE_JSON, "failed to process clock request: Unexpected character"),
            badInput("PUT", "/mockserver/clock", "{\"action\":\"advance\",\"durationMillis\":" + Long.MAX_VALUE + "}", "'durationMillis' must be at most"),
            badInput("PUT", "/mockserver/clock", "{\"action\":\"freeze\",\"instant\":\"+1000000000-01-01T00:00:00Z\"}", "'instant' is outside the range"),
            // chaos
            fault("PUT", "/mockserver/serviceChaos"),
            badInput("PUT", "/mockserver/serviceChaos", UNREADABLE_JSON, "failed to process service chaos request: Unexpected character"),
            fault("PATCH", "/mockserver/serviceChaos"),
            badInput("PATCH", "/mockserver/serviceChaos", UNREADABLE_JSON, "failed to process service chaos patch: Unexpected character"),
            fault("PUT", "/mockserver/tcpChaos"),
            badInput("PUT", "/mockserver/tcpChaos", UNREADABLE_JSON, "failed to process TCP chaos request: Unexpected character"),
            fault("PATCH", "/mockserver/tcpChaos"),
            badInput("PATCH", "/mockserver/tcpChaos", UNREADABLE_JSON, "failed to process TCP chaos patch: Unexpected character"),
            fault("PUT", "/mockserver/grpcChaos"),
            badInput("PUT", "/mockserver/grpcChaos", UNREADABLE_JSON, "failed to process gRPC chaos request: Unexpected character"),
            fault("PATCH", "/mockserver/grpcChaos"),
            badInput("PATCH", "/mockserver/grpcChaos", UNREADABLE_JSON, "failed to process gRPC chaos patch: Unexpected character"),
            fault("PUT", "/mockserver/chaosExperiment"),
            badInput("PUT", "/mockserver/chaosExperiment", UNREADABLE_JSON, "failed to process chaos experiment request: Unexpected character"),
            fault("PUT", "/mockserver/chaosExperiment/profiles/profile"),
            badInput("PUT", "/mockserver/chaosExperiment/profiles/profile", UNREADABLE_JSON, "failed to save chaos profile: Unexpected character"),
            storeFault("GET", "/mockserver/chaosExperiment/profiles", "chaosProfileLibrary", ChaosProfileLibrary.class,
                library -> when(library.list()).thenThrow(new IllegalStateException(FAULT_DETAIL))),
            storeFault("GET", "/mockserver/chaosExperiment/profiles/profile", "chaosProfileLibrary", ChaosProfileLibrary.class,
                library -> when(library.get(anyString())).thenThrow(new IllegalStateException(FAULT_DETAIL))),
            storeFault("DELETE", "/mockserver/chaosExperiment/profiles/profile", "chaosProfileLibrary", ChaosProfileLibrary.class,
                library -> when(library.delete(anyString())).thenThrow(new IllegalStateException(FAULT_DETAIL))),
            storeFault("POST", "/mockserver/chaosExperiment/apply/profile", "chaosProfileLibrary", ChaosProfileLibrary.class,
                library -> when(library.get(anyString())).thenThrow(new IllegalStateException(FAULT_DETAIL))),
            // SLO
            fault("PUT", "/mockserver/verifySLO"),
            badInput("PUT", "/mockserver/verifySLO", UNREADABLE_JSON, "invalid SLO criteria"),
            // load scenarios
            fault("PUT", "/mockserver/loadScenario"),
            badInput("PUT", "/mockserver/loadScenario", UNREADABLE_JSON, "load scenario"),
            fault("PUT", "/mockserver/loadScenario/generateFromOpenAPI"),
            badInput("PUT", "/mockserver/loadScenario/generateFromOpenAPI", UNREADABLE_JSON, "Unexpected character"),
            fault("PUT", "/mockserver/loadScenario/generateFromRecording"),
            badInput("PUT", "/mockserver/loadScenario/generateFromRecording", UNREADABLE_JSON, "Unexpected character"),
            fault("PUT", "/mockserver/loadScenario/start"),
            badInput("PUT", "/mockserver/loadScenario/start", UNREADABLE_JSON, "failed to start load scenario(s): Unexpected character"),
            fault("PUT", "/mockserver/loadScenario/stop"),
            badInput("PUT", "/mockserver/loadScenario/stop", UNREADABLE_JSON, "failed to stop load scenario(s): Unexpected character"),
            storeFault("GET", "/mockserver/loadScenario/endpoint-failure-test", "loadScenarioRegistry", LoadScenarioRegistry.class,
                registry -> when(registry.contains(anyString())).thenThrow(new IllegalStateException(FAULT_DETAIL))),
            storeFault("DELETE", "/mockserver/loadScenario/endpoint-failure-test", "loadScenarioRegistry", LoadScenarioRegistry.class,
                registry -> when(registry.delete(anyString())).thenThrow(new IllegalStateException(FAULT_DETAIL))),
            // scenarios and cassettes
            fault("PUT", "/mockserver/scenario/scenario"),
            badInput("PUT", "/mockserver/scenario/scenario", UNREADABLE_JSON, "failed to process scenario request: Unexpected character"),
            fault("PUT", "/mockserver/cassettes"),
            badInput("PUT", "/mockserver/cassettes", UNREADABLE_JSON, "failed to register cassette: Unexpected character"),
            fault("DELETE", "/mockserver/cassettes"),
            badInput("DELETE", "/mockserver/cassettes", UNREADABLE_JSON, "failed to remove cassette: Unexpected character"),
            // breakpoint matchers
            fault("PUT", "/mockserver/breakpoint/matcher"),
            badInput("PUT", "/mockserver/breakpoint/matcher", UNREADABLE_JSON, "Unexpected character"),
            fault("PUT", "/mockserver/breakpoint/matcher/remove"),
            badInput("PUT", "/mockserver/breakpoint/matcher/remove", UNREADABLE_JSON, "Unexpected character"),
            // contract test, traffic validation and replay
            fault("PUT", "/mockserver/contractTest"),
            badInput("PUT", "/mockserver/contractTest", UNREADABLE_JSON, "Unexpected character"),
            badInput("PUT", "/mockserver/contractTest", UNLOADABLE_SPEC, "Unable to load API spec"),
            fault("PUT", "/mockserver/trafficValidate"),
            badInput("PUT", "/mockserver/trafficValidate", UNREADABLE_JSON, "Unexpected character"),
            badInput("PUT", "/mockserver/trafficValidate", UNLOADABLE_SPEC, "Unable to load API spec"),
            fault("PUT", "/mockserver/replay"),
            badInput("PUT", "/mockserver/replay", UNREADABLE_JSON, "incorrect"),
            // endpoints that read no input: every exception is a fault
            singletonFault("GET", "/mockserver/clock", TimeService.class, TimeService::now, null),
            singletonFault("GET", "/mockserver/proxyConfiguration", KeyAndCertificateFactoryFactory.class,
                () -> KeyAndCertificateFactoryFactory.createKeyAndCertificateFactory(any(), any()), null),
            singletonFault("GET", "/mockserver/serviceChaos", ServiceChaosRegistry.class, ServiceChaosRegistry::getInstance,
                registry -> when(registry.entries()).thenThrow(new IllegalStateException(FAULT_DETAIL))),
            singletonFault("GET", "/mockserver/tcpChaos", TcpChaosRegistry.class, TcpChaosRegistry::getInstance,
                registry -> when(registry.entries()).thenThrow(new IllegalStateException(FAULT_DETAIL))),
            singletonFault("GET", "/mockserver/grpcChaos", GrpcChaosRegistry.class, GrpcChaosRegistry::getInstance,
                registry -> when(registry.entries()).thenThrow(new IllegalStateException(FAULT_DETAIL))),
            singletonFault("GET", "/mockserver/grpc/health", GrpcHealthRegistry.class, GrpcHealthRegistry::getInstance,
                registry -> when(registry.entries()).thenThrow(new IllegalStateException(FAULT_DETAIL))),
            singletonFault("GET", "/mockserver/chaosExperiment", ChaosExperimentOrchestrator.class, ChaosExperimentOrchestrator::getInstance,
                orchestrator -> when(orchestrator.getStatus()).thenThrow(new IllegalStateException(FAULT_DETAIL))),
            singletonFault("GET", "/mockserver/chaosExperiment/history", ChaosExperimentOrchestrator.class, ChaosExperimentOrchestrator::getInstance,
                orchestrator -> when(orchestrator.getHistory(anyInt())).thenThrow(new IllegalStateException(FAULT_DETAIL))),
            singletonFault("DELETE", "/mockserver/chaosExperiment", ChaosExperimentOrchestrator.class, ChaosExperimentOrchestrator::getInstance,
                orchestrator -> doThrow(new IllegalStateException(FAULT_DETAIL)).when(orchestrator).stop()),
            singletonFault("GET", "/mockserver/preemption", PreemptionSimulator.class, PreemptionSimulator::getInstance,
                simulator -> when(simulator.state()).thenThrow(new IllegalStateException(FAULT_DETAIL))),
            singletonFault("GET", "/mockserver/drift", DriftStore.class, DriftStore::getInstance,
                store -> when(store.getRecent(anyInt())).thenThrow(new IllegalStateException(FAULT_DETAIL))),
            singletonFault("GET", "/mockserver/audit", AuditStore.class, AuditStore::getInstance,
                store -> when(store.getRecent(anyInt())).thenThrow(new IllegalStateException(FAULT_DETAIL))),
            storeFault("GET", "/mockserver/loadScenario", "loadScenarioRegistry", LoadScenarioRegistry.class,
                registry -> when(registry.list()).thenThrow(new IllegalStateException(FAULT_DETAIL))),
            storeFault("GET", "/mockserver/cluster", "stateBackend", StateBackend.class,
                backend -> when(backend.clusterInfo()).thenThrow(new IllegalStateException(FAULT_DETAIL))),
            // endpoints that read input
            fault("PUT", "/mockserver/preemption"),
            badInput("PUT", "/mockserver/preemption", UNREADABLE_JSON, "invalid preemption request: Unexpected character"),
            fault("PUT", "/mockserver/generateExpectation"),
            badInput("PUT", "/mockserver/generateExpectation", UNREADABLE_JSON, "failed to generate expectation: Unexpected character")
        );
    }

    private final List<LogEntry> logged = new CopyOnWriteArrayList<>();
    private HttpState httpState;
    private Scheduler scheduler;
    private AutoCloseable arranged;

    @After
    public void stop() throws Exception {
        if (arranged != null) {
            arranged.close();
        }
        if (httpState != null) {
            httpState.stop();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    public void shouldAnswerOnlyTheCallersOwnMistakeAsABadRequest() throws Exception {
        // given
        Configuration configuration = configuration().wasmEnabled(true).sloTrackingEnabled(true).loadGenerationEnabled(true);
        // asynchronous, as under Netty: spec loading, contract tests and traffic validation run on the scheduler's
        // executor, which may write the response after handle() returns
        scheduler = new Scheduler(configuration, new MockServerLogger());
        httpState = new HttpState(configuration, capturingLogger(), scheduler);
        httpState.setReplayHandler(outbound -> new CompletableFuture<>());
        if (testCase.arrange != null) {
            arranged = testCase.arrange.apply(httpState);
        }
        CapturingResponseWriter responseWriter = new CapturingResponseWriter(configuration);

        // when
        boolean handled = httpState.handle(testCase.request(), responseWriter, false);

        // then
        assertThat(handled, is(true));
        HttpResponse response = responseWriter.awaitResponse();
        assertThat("a response", response, notNullValue());
        if (testCase.clientErrorFragment == null) {
            assertThat(response.getStatusCode(), is(500));
            assertThat(response.getBodyAsString(), containsString(UNEXPECTED_FAILURE_MESSAGE));
            assertThat(response.getBodyAsString(), not(containsString(FAULT_DETAIL)));
            List<LogEntry> errors = faultErrors();
            assertThat("the fault is logged once at ERROR with its stack trace", errors, hasSize(1));
            assertThat(response.getBodyAsString(), containsString(UNEXPECTED_FAILURE_MESSAGE + errors.get(0).getCorrelationId()));
        } else {
            assertThat(response.getStatusCode(), is(400));
            assertThat(response.getBodyAsString(), containsString(testCase.clientErrorFragment));
            assertThat(response.getBodyAsString(), not(containsString(UNEXPECTED_FAILURE_MESSAGE)));
            String contentType = response.getBody() != null ? response.getBody().getContentType() : null;
            if (contentType != null && contentType.contains("json")) {
                // an exception message carries newlines and quotes; a hand-built body left them raw
                JsonNode body = new ObjectMapper().readTree(response.getBodyAsString());
                assertThat("the error body is a JSON object", body.isObject(), is(true));
            }
        }
    }

    private List<LogEntry> faultErrors() {
        return logged.stream()
            .filter(entry -> entry.getLogLevel() == Level.ERROR && entry.getThrowable() != null && FAULT_DETAIL.equals(entry.getThrowable().getMessage()))
            .collect(Collectors.toList());
    }

    private MockServerLogger capturingLogger() {
        return new MockServerLogger(HttpStateEndpointFailureTest.class) {
            @Override
            public void logEvent(LogEntry logEntry) {
                logged.add(logEntry);
            }
        };
    }

    private static Case fault(String method, String pathAndQuery) {
        return new Case(method, pathAndQuery, null, null, null);
    }

    private static Case badInput(String method, String pathAndQuery, Object body, String clientErrorFragment) {
        return new Case(method, pathAndQuery, body, clientErrorFragment, null);
    }

    private static <T> Case storeFault(String method, String pathAndQuery, String field, Class<T> type, Consumer<T> stubbing) {
        return new Case(method, pathAndQuery, "", null, httpState -> {
            T store = mock(type);
            stubbing.accept(store);
            replaceField(httpState, field, store);
            return null;
        });
    }

    /**
     * A fault in a process-wide singleton or static the endpoint reads, stubbed only on the test's thread (static
     * mocks are thread-local), where the endpoint runs. With no {@code stubbing} the static itself throws.
     */
    private static <T> Case singletonFault(String method, String pathAndQuery, Class<T> type, MockedStatic.Verification staticCall, Consumer<T> stubbing) {
        return new Case(method, pathAndQuery, "", null, httpState -> {
            MockedStatic<T> mockedStatic = mockStatic(type);
            if (stubbing == null) {
                mockedStatic.when(staticCall).thenThrow(new IllegalStateException(FAULT_DETAIL));
            } else {
                T instance = mock(type);
                stubbing.accept(instance);
                mockedStatic.when(staticCall).thenReturn(instance);
            }
            return mockedStatic;
        });
    }

    private static void replaceField(HttpState httpState, String name, Object value) {
        try {
            Field field = HttpState.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(httpState, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot replace HttpState." + name, e);
        }
    }

    private static class Case {
        private final String method;
        private final String pathAndQuery;
        private final Object body;
        private final String clientErrorFragment;
        private final Function<HttpState, AutoCloseable> arrange;

        private Case(String method, String pathAndQuery, Object body, String clientErrorFragment, Function<HttpState, AutoCloseable> arrange) {
            this.method = method;
            this.pathAndQuery = pathAndQuery;
            this.body = body;
            this.clientErrorFragment = clientErrorFragment;
            this.arrange = arrange;
        }

        private HttpRequest request() {
            String[] pathAndQuery = this.pathAndQuery.split("\\?", 2);
            HttpRequest request = body == null ? new BodyFaultRequest() : HttpRequest.request();
            request.withMethod(method).withPath(pathAndQuery[0]);
            if (pathAndQuery.length > 1) {
                String[] parameter = pathAndQuery[1].split("=", 2);
                request.withQueryStringParameter(parameter[0], parameter[1]);
            }
            if (body instanceof byte[]) {
                request.withBody((byte[]) body);
            } else if (body != null && !((String) body).isEmpty()) {
                request.withBody((String) body);
            }
            return request;
        }

        @Override
        public String toString() {
            return method + " " + pathAndQuery + (body == null ? " fault in its handling" : arrange != null ? " fault in its store" : " bad input");
        }
    }

    /**
     * A request whose body cannot be read: the endpoint's own handling throws, standing in for any fault inside
     * MockServer that the endpoint's catch-all sees.
     */
    private static class BodyFaultRequest extends HttpRequest {
        @Override
        public String getBodyAsString() {
            throw new IllegalStateException(FAULT_DETAIL);
        }

        @Override
        public String getBodyAsJsonOrXmlString() {
            throw new IllegalStateException(FAULT_DETAIL);
        }

        @Override
        public String getBodyAsText() {
            throw new IllegalStateException(FAULT_DETAIL);
        }

        @Override
        public byte[] getBodyAsRawBytes() {
            throw new IllegalStateException(FAULT_DETAIL);
        }
    }

    private static class CapturingResponseWriter extends ResponseWriter {
        private final CompletableFuture<HttpResponse> response = new CompletableFuture<>();

        private CapturingResponseWriter(Configuration configuration) {
            super(configuration, new MockServerLogger());
        }

        @Override
        public void sendResponse(HttpRequest request, HttpResponse response) {
            this.response.complete(response);
        }

        /**
         * The response, which an endpoint whose work runs on the scheduler writes after handle() returns.
         */
        private HttpResponse awaitResponse() {
            try {
                return response.get(20, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception noResponse) {
                return null;
            }
        }
    }
}

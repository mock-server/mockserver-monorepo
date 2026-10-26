package org.mockserver.extension;

import org.junit.After;
import org.junit.Test;
import org.mockserver.authentication.AuthenticationHandler;
import org.mockserver.authentication.AuthenticationResult;
import org.mockserver.authentication.authorization.ControlPlaneRole;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.server.initialize.ExpectationInitializer;
import org.slf4j.event.Level;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Extension admin routes: served under the loaded name only, behind the control-plane gate, on their own capped pool
 * with a timeout, answered exactly once.
 */
public class ExtensionAdminRoutesTest {

    private static final long WAIT_SECONDS = 30;

    private HttpState httpState;
    private Scheduler scheduler;

    /**
     * An admin extension whose handler each test supplies.
     */
    static final class TestAdmin implements AdminApiExtension, ExpectationInitializer {
        interface Handler {
            HttpResponse handle(HttpRequest request, String subPath) throws Exception;
        }

        final String name;
        volatile Handler handler = (request, subPath) -> response().withStatusCode(200).withBody("sub:" + subPath);
        volatile boolean readOnly;
        volatile boolean readOnlyThrows;
        final AtomicInteger readOnlyCalls = new AtomicInteger();
        volatile Expectation[] seeded = new Expectation[0];
        final List<String> threads = new CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger();

        TestAdmin(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public HttpResponse handle(HttpRequest request, String subPath) throws Exception {
            calls.incrementAndGet();
            threads.add(Thread.currentThread().getName());
            return handler.handle(request, subPath);
        }

        @Override
        public boolean isReadOnly(String method, String subPath) {
            readOnlyCalls.incrementAndGet();
            if (readOnlyThrows) {
                throw new IllegalStateException("classifier failed");
            }
            return readOnly;
        }

        @Override
        public Expectation[] initializeExpectations() {
            return seeded;
        }
    }

    static final class PlainExtension implements Extension {
        @Override
        public String name() {
            return "plain";
        }
    }

    static final class CapturingResponseWriter extends ResponseWriter {
        final List<HttpResponse> responses = new CopyOnWriteArrayList<>();

        CapturingResponseWriter() {
            super(configuration(), new MockServerLogger());
        }

        @Override
        public void sendResponse(HttpRequest request, HttpResponse response) {
            responses.add(response);
        }

        HttpResponse awaitResponse() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (responses.isEmpty() && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(5);
            }
            assertThat("a response was written", responses.isEmpty(), is(false));
            return responses.get(0);
        }
    }

    static final class CapturingMockServerLogger extends MockServerLogger {
        final List<String> warnings = new CopyOnWriteArrayList<>();

        CapturingMockServerLogger(Configuration configuration) {
            super(configuration, ExtensionAdminRoutesTest.class);
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            if (logEntry.getLogLevel() == Level.WARN) {
                warnings.add(String.valueOf(logEntry.getMessage()));
            }
            super.logEvent(logEntry);
        }
    }

    @After
    public void tearDown() {
        if (httpState != null) {
            httpState.stop();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @SafeVarargs
    private final HttpState start(Configuration configuration, Supplier<? extends Extension>... extensions) {
        return start(configuration, new MockServerLogger(configuration, ExtensionAdminRoutesTest.class), extensions);
    }

    @SafeVarargs
    private final HttpState start(Configuration configuration, MockServerLogger logger, Supplier<? extends Extension>... extensions) {
        if (extensions.length > 0) {
            configuration.extensions(extensions);
        }
        scheduler = new Scheduler(configuration, logger);
        ExtensionRegistry registry = configuration.extensionsConfigured() ? ExtensionRegistry.start(configuration, logger) : null;
        httpState = new HttpState(configuration, logger, scheduler, registry);
        return httpState;
    }

    private static HttpRequest get(String path) {
        return request().withMethod("GET").withPath(path);
    }

    private static Map<String, ControlPlaneRole> roles() {
        Map<String, ControlPlaneRole> mapping = new LinkedHashMap<>();
        mapping.put("editors", ControlPlaneRole.MUTATE);
        mapping.put("viewers", ControlPlaneRole.READ);
        return mapping;
    }

    private static AuthenticationHandler principalWith(Set<String> scopes) {
        return new AuthenticationHandler() {
            @Override
            public boolean controlPlaneRequestAuthenticated(HttpRequest request) {
                return true;
            }

            @Override
            public AuthenticationResult authenticate(HttpRequest request) {
                return AuthenticationResult.authenticated("tester", "verified-oidc", Map.of("sub", "tester"), scopes);
            }
        };
    }

    @Test
    public void servesARouteUnderTheExtensionNameOffTheCallingThread() throws Exception {
        TestAdmin admin = new TestAdmin("acme");
        start(configuration(), () -> admin);
        CapturingResponseWriter responseWriter = new CapturingResponseWriter();

        assertThat(httpState.handle(get("/mockserver/extensions/acme/widgets/1"), responseWriter, false), is(true));

        HttpResponse response = responseWriter.awaitResponse();
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), is("sub:/widgets/1"));
        assertThat("written as a control-plane response", response.getFirstHeader("version"), not(emptyString()));
        assertThat(admin.threads, contains(startsWith(routes().threadNamePrefix())));
        assertThat(admin.threads.get(0), not(Thread.currentThread().getName()));
    }

    @Test
    public void givesTheRootOfTheNameASubPathOfSlash() throws Exception {
        TestAdmin admin = new TestAdmin("acme");
        start(configuration(), () -> admin);
        CapturingResponseWriter responseWriter = new CapturingResponseWriter();

        httpState.handle(get("/mockserver/extensions/acme"), responseWriter, false);

        assertThat(responseWriter.awaitResponse().getBodyAsString(), is("sub:/"));
    }

    @Test
    public void answersANullResponseWith404() throws Exception {
        TestAdmin admin = new TestAdmin("acme");
        admin.handler = (request, subPath) -> null;
        start(configuration(), () -> admin);
        CapturingResponseWriter responseWriter = new CapturingResponseWriter();

        httpState.handle(get("/mockserver/extensions/acme/missing"), responseWriter, false);

        assertThat(responseWriter.awaitResponse().getStatusCode(), is(404));
    }

    @Test
    public void answersAThrowingHandlerWith500AndACorrelationId() throws Exception {
        TestAdmin admin = new TestAdmin("acme");
        admin.handler = (request, subPath) -> {
            throw new IllegalStateException("secret detail");
        };
        start(configuration(), () -> admin);
        CapturingResponseWriter responseWriter = new CapturingResponseWriter();

        httpState.handle(get("/mockserver/extensions/acme/boom"), responseWriter, false);

        HttpResponse response = responseWriter.awaitResponse();
        assertThat(response.getStatusCode(), is(500));
        assertThat(response.getBodyAsString(), containsString("correlation id"));
        assertThat(response.getBodyAsString(), not(containsString("secret detail")));
    }

    @Test
    public void leavesAnyOtherNameUnderTheExtensionsPathAlone() {
        start(configuration(), () -> new TestAdmin("acme"));

        assertThat(httpState.handle(get("/mockserver/extensions/other/x"), new CapturingResponseWriter(), false), is(false));
        assertThat(httpState.handle(get("/mockserver/extensions/acmeplus/x"), new CapturingResponseWriter(), false), is(false));
        assertThat(httpState.handle(get("/mockserver/extensions/"), new CapturingResponseWriter(), false), is(false));
        assertThat("no bare alias", httpState.handle(get("/extensions/acme/x"), new CapturingResponseWriter(), false), is(false));
    }

    @Test
    public void leavesACorsPreflightToTheControlPlanePreflightHandling() {
        TestAdmin admin = new TestAdmin("acme");
        start(configuration(), () -> admin);
        HttpRequest preflight = request().withMethod("OPTIONS").withPath("/mockserver/extensions/acme/x")
            .withHeader("Origin", "http://localhost:3000")
            .withHeader("Access-Control-Request-Method", "PUT");

        assertThat(httpState.handle(preflight, new CapturingResponseWriter(), false), is(false));
        assertThat(admin.calls.get(), is(0));
    }

    @Test
    public void routesNothingWhenNoExtensionIsLoaded() {
        start(configuration());

        assertThat(httpState.getExtensionRegistry(), nullValue());
        assertThat(httpState.handle(get("/mockserver/extensions/acme/x"), new CapturingResponseWriter(), false), is(false));
    }

    @Test
    public void buildsNoRoutesWhenNoLoadedExtensionServesThem() {
        Configuration configuration = configuration().extensions((Supplier<Extension>) PlainExtension::new);
        MockServerLogger logger = new MockServerLogger(configuration, ExtensionAdminRoutesTest.class);
        ExtensionRegistry registry = ExtensionRegistry.start(configuration, logger);
        try {
            assertThat(registry.createAdminRoutes(configuration, null, (request, writer, readOnly) -> true), nullValue());
        } finally {
            registry.stop();
        }
    }

    @Test
    public void runsInlineInAServletContainer() {
        TestAdmin admin = new TestAdmin("acme");
        start(configuration(), () -> admin);
        CapturingResponseWriter responseWriter = new CapturingResponseWriter();

        assertThat(httpState.handle(get("/mockserver/extensions/acme/x"), responseWriter, true), is(true));

        assertThat(responseWriter.responses, hasSize(1));
        assertThat(admin.threads, contains(Thread.currentThread().getName()));
    }

    @Test
    public void refusesAnUnauthenticatedCallerWithoutCallingTheExtension() throws Exception {
        TestAdmin admin = new TestAdmin("acme");
        start(configuration(), () -> admin);
        httpState.setControlPlaneAuthenticationHandler(request -> false);
        CapturingResponseWriter responseWriter = new CapturingResponseWriter();

        httpState.handle(get("/mockserver/extensions/acme/x"), responseWriter, false);

        assertThat(responseWriter.awaitResponse().getStatusCode(), is(401));
        TimeUnit.MILLISECONDS.sleep(200);
        assertThat(responseWriter.responses, hasSize(1));
        assertThat("handle is never called", admin.calls.get(), is(0));
        assertThat("isReadOnly is never called", admin.readOnlyCalls.get(), is(0));
    }

    @Test
    public void refusesAnUnauthenticatedCallerWith401EvenWhenIsReadOnlyWouldThrow() throws Exception {
        TestAdmin admin = new TestAdmin("acme");
        admin.readOnlyThrows = true;
        start(configuration().controlPlaneAuthorizationEnabled(true).controlPlaneScopeMapping(roles()), () -> admin);
        httpState.setControlPlaneAuthenticationHandler(request -> false);
        CapturingResponseWriter responseWriter = new CapturingResponseWriter();

        httpState.handle(get("/mockserver/extensions/acme/x"), responseWriter, false);

        assertThat(responseWriter.awaitResponse().getStatusCode(), is(401));
        assertThat(admin.readOnlyCalls.get(), is(0));
        assertThat(admin.calls.get(), is(0));
    }

    @Test
    public void answersAThrowingIsReadOnlyWith500ForAnAuthenticatedCaller() throws Exception {
        TestAdmin admin = new TestAdmin("acme");
        admin.readOnlyThrows = true;
        start(configuration().controlPlaneAuthorizationEnabled(true).controlPlaneScopeMapping(roles()), () -> admin);
        httpState.setControlPlaneAuthenticationHandler(principalWith(Set.of("editors")));
        CapturingResponseWriter responseWriter = new CapturingResponseWriter();

        httpState.handle(get("/mockserver/extensions/acme/x"), responseWriter, false);

        HttpResponse response = responseWriter.awaitResponse();
        assertThat(response.getStatusCode(), is(500));
        assertThat(response.getBodyAsString(), not(containsString("classifier failed")));
        assertThat(admin.readOnlyCalls.get(), is(1));
        assertThat(admin.calls.get(), is(0));
    }

    @Test
    public void asksIsReadOnlyOnceAfterAuthentication() throws Exception {
        TestAdmin admin = new TestAdmin("acme");
        start(configuration().controlPlaneAuthorizationEnabled(true).controlPlaneScopeMapping(roles()), () -> admin);
        httpState.setControlPlaneAuthenticationHandler(principalWith(Set.of("editors")));
        CapturingResponseWriter responseWriter = new CapturingResponseWriter();

        httpState.handle(get("/mockserver/extensions/acme/x"), responseWriter, false);

        assertThat(responseWriter.awaitResponse().getStatusCode(), is(200));
        assertThat(admin.readOnlyCalls.get(), is(1));
    }

    @Test
    public void appliesRealJwtControlPlaneAuthentication() throws Exception {
        org.mockserver.keys.AsymmetricKeyPair keyPair = org.mockserver.keys.AsymmetricKeyGenerator.createAsymmetricKeyPair(org.mockserver.keys.AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        TestAdmin admin = new TestAdmin("acme");
        start(configuration()
            .controlPlaneJWTAuthenticationJWKSource(org.mockserver.test.TempFileWriter.write(new org.mockserver.authentication.jwt.JWKGenerator().generateJWK(keyPair)))
            .controlPlaneJWTAuthenticationRequired(true), () -> admin);

        CapturingResponseWriter withoutToken = new CapturingResponseWriter();
        httpState.handle(get("/mockserver/extensions/acme/x"), withoutToken, false);
        assertThat(withoutToken.awaitResponse().getStatusCode(), is(401));
        assertThat(admin.calls.get(), is(0));
        assertThat(admin.readOnlyCalls.get(), is(0));

        CapturingResponseWriter withToken = new CapturingResponseWriter();
        httpState.handle(get("/mockserver/extensions/acme/x").withHeader("Authorization", "Bearer " + new org.mockserver.authentication.jwt.JWTGenerator(keyPair).generateJWT()), withToken, false);
        assertThat(withToken.awaitResponse().getStatusCode(), is(200));
        assertThat(admin.calls.get(), is(1));
    }

    @Test
    public void neverInterruptsMockServersOwnAuthenticationOrAuditWork() throws Exception {
        long timeoutMillis = 100;
        AtomicInteger interruptedDuringAuthentication = new AtomicInteger();
        CountDownLatch authenticated = new CountDownLatch(1);
        TestAdmin admin = new TestAdmin("acme");
        start(configuration().extensionAdminTimeoutMillis(timeoutMillis), () -> admin);
        httpState.setControlPlaneAuthenticationHandler(new AuthenticationHandler() {
            @Override
            public boolean controlPlaneRequestAuthenticated(HttpRequest request) {
                return true;
            }

            @Override
            public AuthenticationResult authenticate(HttpRequest request) {
                // slower than the timeout, as a JWKS fetch can be; busy-waits so an interrupt stays visible
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis * 5);
                while (System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                if (Thread.currentThread().isInterrupted()) {
                    interruptedDuringAuthentication.incrementAndGet();
                }
                authenticated.countDown();
                return AuthenticationResult.authenticated("tester", "verified-oidc", Map.of("sub", "tester"), Set.of());
            }
        });
        ExtensionAdminRoutes routes = routes();
        CapturingResponseWriter responseWriter = new CapturingResponseWriter();

        httpState.handle(get("/mockserver/extensions/acme/x"), responseWriter, false);

        assertThat(responseWriter.awaitResponse().getStatusCode(), is(500));
        assertThat(authenticated.await(WAIT_SECONDS, TimeUnit.SECONDS), is(true));
        awaitSlots(routes, 32);
        assertThat("the timeout interrupted MockServer's own gate", interruptedDuringAuthentication.get(), is(0));
        assertThat("a timed-out call does not go on to run the extension", admin.calls.get(), is(0));
        assertThat(routes.timeoutCount(), is(1L));
    }

    @Test
    public void refusesARefusedBrowserOriginBeforeTheExtension() throws Exception {
        TestAdmin admin = new TestAdmin("acme");
        start(configuration(), () -> admin);
        CapturingResponseWriter responseWriter = new CapturingResponseWriter();

        httpState.handle(get("/mockserver/extensions/acme/x").withHeader("Origin", "https://attacker.example"), responseWriter, false);

        assertThat(responseWriter.awaitResponse().getStatusCode(), is(403));
        assertThat(admin.calls.get(), is(0));
    }

    @Test
    public void needsTheMutateRoleEvenForAGet() throws Exception {
        TestAdmin admin = new TestAdmin("acme");
        start(configuration().controlPlaneAuthorizationEnabled(true).controlPlaneScopeMapping(roles()), () -> admin);
        httpState.setControlPlaneAuthenticationHandler(principalWith(Set.of("viewers")));
        CapturingResponseWriter responseWriter = new CapturingResponseWriter();

        httpState.handle(get("/mockserver/extensions/acme/x"), responseWriter, false);

        assertThat(responseWriter.awaitResponse().getStatusCode(), is(403));
        assertThat(admin.calls.get(), is(0));
    }

    @Test
    public void letsAReadOnlyRouteBeCalledWithTheReadRole() throws Exception {
        TestAdmin admin = new TestAdmin("acme");
        admin.readOnly = true;
        start(configuration().controlPlaneAuthorizationEnabled(true).controlPlaneScopeMapping(roles()), () -> admin);
        httpState.setControlPlaneAuthenticationHandler(principalWith(Set.of("viewers")));
        CapturingResponseWriter responseWriter = new CapturingResponseWriter();

        httpState.handle(request().withMethod("PUT").withPath("/mockserver/extensions/acme/x"), responseWriter, false);

        assertThat(responseWriter.awaitResponse().getStatusCode(), is(200));
    }

    @Test
    public void letsTheMutateRoleCallAnyRoute() throws Exception {
        TestAdmin admin = new TestAdmin("acme");
        start(configuration().controlPlaneAuthorizationEnabled(true).controlPlaneScopeMapping(roles()), () -> admin);
        httpState.setControlPlaneAuthenticationHandler(principalWith(Set.of("editors")));
        CapturingResponseWriter responseWriter = new CapturingResponseWriter();

        httpState.handle(request().withMethod("DELETE").withPath("/mockserver/extensions/acme/x"), responseWriter, false);

        assertThat(responseWriter.awaitResponse().getStatusCode(), is(200));
    }

    @Test
    public void refusesACallBeyondTheCapWith503() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TestAdmin admin = new TestAdmin("acme");
        admin.handler = (request, subPath) -> {
            entered.countDown();
            release.await(WAIT_SECONDS, TimeUnit.SECONDS);
            return response().withStatusCode(200);
        };
        start(configuration().extensionAdminMaxInFlight(1), () -> admin);
        try {
            CapturingResponseWriter first = new CapturingResponseWriter();
            httpState.handle(get("/mockserver/extensions/acme/slow"), first, false);
            assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS), is(true));

            CapturingResponseWriter second = new CapturingResponseWriter();
            assertThat(httpState.handle(get("/mockserver/extensions/acme/slow"), second, false), is(true));

            HttpResponse refused = second.awaitResponse();
            assertThat(refused.getStatusCode(), is(503));
            assertThat(refused.getFirstHeader("Retry-After"), is("1"));
            assertThat(scheduler.getOverloadRejectionCount(Scheduler.OverloadReason.EXTENSION_ADMIN), is(1L));
            assertThat(admin.calls.get(), is(1));

            release.countDown();
            assertThat(first.awaitResponse().getStatusCode(), is(200));
        } finally {
            release.countDown();
        }
    }

    @Test
    public void answersAHungCallWithOne500AtTheTimeoutAndInterruptsIt() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        TestAdmin admin = new TestAdmin("acme");
        admin.handler = (request, subPath) -> {
            try {
                TimeUnit.SECONDS.sleep(WAIT_SECONDS);
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
            return response().withStatusCode(200).withBody("too late");
        };
        start(configuration().extensionAdminTimeoutMillis(200L), () -> admin);
        ExtensionAdminRoutes routes = routes();
        CapturingResponseWriter responseWriter = new CapturingResponseWriter();

        httpState.handle(get("/mockserver/extensions/acme/hang"), responseWriter, false);

        HttpResponse response = responseWriter.awaitResponse();
        assertThat(response.getStatusCode(), is(500));
        assertThat(response.getBodyAsString(), containsString("correlation id"));
        assertThat("the handler was interrupted", interrupted.await(WAIT_SECONDS, TimeUnit.SECONDS), is(true));
        awaitSlots(routes, 32);
        assertThat("the late result was discarded", responseWriter.responses, hasSize(1));
        assertThat(routes.timeoutCount(), is(1L));
        assertThat(routes.lateResultCount(), is(1L));
    }

    @Test
    public void keepsThreadsAtTheCapWhenHandlersIgnoreTheInterrupt() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger();
        TestAdmin admin = new TestAdmin("acme");
        admin.handler = (request, subPath) -> {
            running.incrementAndGet();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (release.getCount() > 0 && System.nanoTime() < deadline) {
                try {
                    release.await(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // deliberately ignores the timeout's interrupt
                }
            }
            return response().withStatusCode(200);
        };
        start(configuration().extensionAdminMaxInFlight(2).extensionAdminTimeoutMillis(100L), () -> admin);
        ExtensionAdminRoutes routes = routes();
        try {
            CapturingResponseWriter first = new CapturingResponseWriter();
            CapturingResponseWriter second = new CapturingResponseWriter();
            httpState.handle(get("/mockserver/extensions/acme/stuck"), first, false);
            httpState.handle(get("/mockserver/extensions/acme/stuck"), second, false);
            assertThat(first.awaitResponse().getStatusCode(), is(500));
            assertThat(second.awaitResponse().getStatusCode(), is(500));

            CapturingResponseWriter third = new CapturingResponseWriter();
            httpState.handle(get("/mockserver/extensions/acme/stuck"), third, false);

            assertThat("timed-out calls still hold their slots", third.awaitResponse().getStatusCode(), is(503));
            assertThat(running.get(), is(2));
            assertThat(adminThreadCount(routes), lessThanOrEqualTo(2));
        } finally {
            release.countDown();
        }
        awaitSlots(routes, 2);
        assertThat(adminThreadCount(routes), lessThanOrEqualTo(2));
    }

    @Test
    public void warnsAtStartUpWhenAnExtensionTakesOverASeededExpectation() {
        TestAdmin admin = new TestAdmin("acme");
        admin.seeded = new Expectation[]{
            new Expectation(request("/mockserver/extensions/acme/status")).thenRespond(response("shadowed")),
            new Expectation(request("/elsewhere")).thenRespond(response("fine"))
        };
        // the suite runs at logLevel=ERROR, which would suppress the WARN under test
        Configuration configuration = configuration().logLevel(Level.WARN);
        CapturingMockServerLogger logger = new CapturingMockServerLogger(configuration);

        start(configuration, logger, () -> admin);

        List<String> shadowWarnings = new java.util.ArrayList<>();
        for (String warning : logger.warnings) {
            if (warning.contains("will not match because extension")) {
                shadowWarnings.add(warning);
            }
        }
        assertThat(shadowWarnings, hasSize(1));
        assertThat(shadowWarnings.get(0), containsString("/mockserver/extensions/acme/status"));
    }

    @Test
    public void decidesWhichExpectationPathsARouteTakesOver() {
        assertThat(ExtensionAdminRoutes.takesOver("/mockserver/extensions/acme", "acme"), is(true));
        assertThat(ExtensionAdminRoutes.takesOver("/mockserver/extensions/acme/a/b", "acme"), is(true));
        assertThat(ExtensionAdminRoutes.takesOver("/mockserver/extensions/.*", "acme"), is(true));
        assertThat(ExtensionAdminRoutes.takesOver("/mockserver/extensions/acmeplus/x", "acme"), is(false));
        assertThat(ExtensionAdminRoutes.takesOver("/mockserver/extensions/other/x", "acme"), is(false));
        assertThat(ExtensionAdminRoutes.takesOver("/data[", "acme"), is(false));
    }

    @Test
    public void stopsTheAdminPoolWithTheState() throws Exception {
        TestAdmin admin = new TestAdmin("acme");
        start(configuration(), () -> admin);
        ExtensionAdminRoutes routes = routes();
        CapturingResponseWriter responseWriter = new CapturingResponseWriter();
        httpState.handle(get("/mockserver/extensions/acme/x"), responseWriter, false);
        responseWriter.awaitResponse();
        assertThat(adminThreadCount(routes), is(1));

        httpState.stop();
        httpState = null;

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (adminThreadCount(routes) > 0 && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat(adminThreadCount(routes), is(0));
    }

    @Test
    public void stopNeverInterruptsMockServersOwnWorkOnACall() throws Exception {
        AtomicInteger interruptedDuringAuthentication = new AtomicInteger();
        CountDownLatch authenticating = new CountDownLatch(1);
        CountDownLatch authenticated = new CountDownLatch(1);
        TestAdmin admin = new TestAdmin("acme");
        start(configuration(), () -> admin);
        httpState.setControlPlaneAuthenticationHandler(new AuthenticationHandler() {
            @Override
            public boolean controlPlaneRequestAuthenticated(HttpRequest request) {
                return true;
            }

            @Override
            public AuthenticationResult authenticate(HttpRequest request) {
                authenticating.countDown();
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
                while (System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                if (Thread.currentThread().isInterrupted()) {
                    interruptedDuringAuthentication.incrementAndGet();
                }
                authenticated.countDown();
                return AuthenticationResult.authenticated("tester", "verified-oidc", Map.of("sub", "tester"), Set.of());
            }
        });
        CapturingResponseWriter responseWriter = new CapturingResponseWriter();
        httpState.handle(get("/mockserver/extensions/acme/x"), responseWriter, false);
        assertThat(authenticating.await(WAIT_SECONDS, TimeUnit.SECONDS), is(true));

        httpState.stop();
        httpState = null;

        assertThat(authenticated.await(WAIT_SECONDS, TimeUnit.SECONDS), is(true));
        assertThat(responseWriter.awaitResponse().getStatusCode(), is(503));
        assertThat("stop interrupted MockServer's own gate", interruptedDuringAuthentication.get(), is(0));
        assertThat("a call stopped before it reached the extension does not run it", admin.calls.get(), is(0));
    }

    @Test
    public void stopInterruptsAHandlerBlockedInExtensionCode() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        TestAdmin admin = new TestAdmin("acme");
        admin.handler = (request, subPath) -> {
            entered.countDown();
            try {
                TimeUnit.SECONDS.sleep(WAIT_SECONDS);
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
            return response().withStatusCode(200);
        };
        start(configuration(), () -> admin);
        httpState.handle(get("/mockserver/extensions/acme/x"), new CapturingResponseWriter(), false);
        assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS), is(true));

        httpState.stop();
        httpState = null;

        assertThat(interrupted.await(WAIT_SECONDS, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void answersACallAfterTheSchedulerIsShutDownWith503InsteadOfRunningItWithoutATimeout() throws Exception {
        TestAdmin admin = new TestAdmin("acme");
        start(configuration().extensionAdminMaxInFlight(1), () -> admin);
        ExtensionAdminRoutes routes = routes();
        // the scheduler discards, rather than rejects, the timeout of a call made after it is shut down
        scheduler.shutdown();

        for (int i = 0; i < 2; i++) {
            CapturingResponseWriter responseWriter = new CapturingResponseWriter();
            assertThat(httpState.handle(get("/mockserver/extensions/acme/x"), responseWriter, false), is(true));

            HttpResponse response = responseWriter.awaitResponse();
            assertThat(response.getStatusCode(), is(503));
            assertThat(response.getBodyAsString(), is("MockServer is stopping"));
            assertThat(responseWriter.responses, hasSize(1));
            awaitSlots(routes, 1);
        }
        assertThat("a call nothing could time out never reaches the extension", admin.calls.get(), is(0));
        assertThat(scheduler.getOverloadRejectionCount(Scheduler.OverloadReason.EXTENSION_ADMIN), is(0L));
    }

    @Test
    public void answersACallAfterStopWith503StoppingRatherThanOverloaded() throws Exception {
        TestAdmin admin = new TestAdmin("acme");
        start(configuration().extensionAdminMaxInFlight(1), () -> admin);
        ExtensionAdminRoutes routes = routes();
        routes.stop();

        for (int i = 0; i < 2; i++) {
            CapturingResponseWriter responseWriter = new CapturingResponseWriter();
            assertThat(httpState.handle(get("/mockserver/extensions/acme/x"), responseWriter, false), is(true));

            HttpResponse response = responseWriter.awaitResponse();
            assertThat(response.getStatusCode(), is(503));
            assertThat(response.getBodyAsString(), is("MockServer is stopping"));
            awaitSlots(routes, 1);
        }
        assertThat(admin.calls.get(), is(0));
        assertThat(scheduler.getOverloadRejectionCount(Scheduler.OverloadReason.EXTENSION_ADMIN), is(0L));
    }

    private ExtensionAdminRoutes routes() throws Exception {
        java.lang.reflect.Field field = HttpState.class.getDeclaredField("extensionAdminRoutes");
        field.setAccessible(true);
        return (ExtensionAdminRoutes) field.get(httpState);
    }

    private static void awaitSlots(ExtensionAdminRoutes routes, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (routes.availableSlots() < expected && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(5);
        }
        assertThat(routes.availableSlots(), is(expected));
    }

    private static int adminThreadCount(ExtensionAdminRoutes routes) {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith(routes.threadNamePrefix())) {
                count++;
            }
        }
        return count;
    }
}

package org.mockserver.mock.action.http;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.Attribute;
import org.junit.*;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.crud.CrudDispatcher;
import org.mockserver.model.*;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.responsewriter.StreamErrorWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.curl.HttpRequestToCurlSerializer;
import org.mockserver.templates.engine.javascript.JavaScriptTemplateEngine;
import org.mockserver.templates.engine.velocity.VelocityTemplateEngine;
import org.mockserver.time.FixedTime;
import org.mockserver.uuid.UUIDService;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertArrayEquals;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.*;
import static org.mockserver.log.model.LogEntryMessages.RECEIVED_REQUEST_MESSAGE_FORMAT;
import static org.mockserver.mock.action.http.HttpActionHandler.REMOTE_SOCKET;
import static org.mockserver.model.Delay.milliseconds;
import static org.mockserver.model.HttpClassCallback.callback;
import static org.mockserver.model.HttpError.error;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.notFoundResponse;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.HttpTemplate.template;
import static org.slf4j.event.Level.INFO;

/**
 * @author jamesdbloom
 */
public class HttpActionHandlerTest {

    private static Scheduler scheduler;
    @Mock
    private HttpResponseActionHandler mockHttpResponseActionHandler;
    @Mock
    private HttpResponseTemplateActionHandler mockHttpResponseTemplateActionHandler;
    @Mock
    private HttpResponseClassCallbackActionHandler mockHttpResponseClassCallbackActionHandler;
    @Mock
    private HttpResponseObjectCallbackActionHandler mockHttpResponseObjectCallbackActionHandler;
    @Mock
    private HttpForwardActionHandler mockHttpForwardActionHandler;
    @Mock
    private HttpForwardTemplateActionHandler mockHttpForwardTemplateActionHandler;
    @Mock
    private HttpForwardClassCallbackActionHandler mockHttpForwardClassCallbackActionHandler;
    @Mock
    private HttpForwardObjectCallbackActionHandler mockHttpForwardObjectCallbackActionHandler;
    @Mock
    private HttpOverrideForwardedRequestActionHandler mockHttpOverrideForwardedRequestActionHandler;
    @Mock
    private HttpErrorActionHandler mockHttpErrorActionHandler;
    @Mock
    private ResponseWriter mockResponseWriter;
    @Mock
    private MockServerLogger mockServerLogger;
    @Spy
    private HttpRequestToCurlSerializer httpRequestToCurlSerializer = new HttpRequestToCurlSerializer(mockServerLogger);
    @Mock
    private NettyHttpClient mockNettyHttpClient;
    private HttpState mockHttpStateHandler;
    private HttpRequest request;
    private HttpResponse response;
    private CompletableFuture<HttpResponse> responseFuture;
    private HttpRequest forwardedHttpRequest;
    private HttpForwardActionResult httpForwardActionResult;
    private Expectation expectation;
    @InjectMocks
    private HttpActionHandler actionHandler;
    private Configuration configuration;

    @ClassRule
    public static final FixedTime fixedTime = new FixedTime();

    @AfterClass
    public static void stopScheduler() {
        scheduler.shutdown();
    }

    @Before
    public void setupMocks() {
        configuration = configuration().logLevel(Level.INFO);

        mockHttpStateHandler = mock(HttpState.class);
        scheduler = spy(new Scheduler(configuration, mockServerLogger));
        when(mockHttpStateHandler.getScheduler()).thenReturn(scheduler);
        when(mockHttpStateHandler.getUniqueLoopPreventionHeaderValue()).thenReturn("MockServer_" + UUIDService.getUUID());
        when(mockHttpStateHandler.getCrudDispatcher()).thenReturn(new CrudDispatcher());
        actionHandler = new HttpActionHandler(configuration, null, mockHttpStateHandler, null, null);

        openMocks(this);
        when(mockServerLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        request = request("some_path");
        response = response("some_body").withDelay(milliseconds(0));
        responseFuture = new CompletableFuture<>();
        responseFuture.complete(response);
        forwardedHttpRequest = mock(HttpRequest.class);
        httpForwardActionResult = new HttpForwardActionResult(forwardedHttpRequest, responseFuture, null, new InetSocketAddress(1234));
        expectation = new Expectation(request).thenRespond(response);

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(response);
        when(mockHttpResponseTemplateActionHandler.handle(any(HttpTemplate.class), any(HttpRequest.class))).thenReturn(response);
        when(mockHttpResponseClassCallbackActionHandler.handle(any(HttpClassCallback.class), any(HttpRequest.class))).thenReturn(response);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(httpForwardActionResult);
        when(mockHttpForwardTemplateActionHandler.handle(any(HttpTemplate.class), any(HttpRequest.class))).thenReturn(httpForwardActionResult);
        when(mockHttpForwardClassCallbackActionHandler.handle(any(HttpClassCallback.class), any(HttpRequest.class))).thenReturn(httpForwardActionResult);
        when(mockHttpOverrideForwardedRequestActionHandler.handle(any(HttpOverrideForwardedRequest.class), any(HttpRequest.class))).thenReturn(httpForwardActionResult);
    }

    @Test
    public void shouldRejectProxyAuthenticationBeforeRequestFiltersAndResponseTransformers() {
        configuration.proxyAuthenticationUsername("recorder").proxyAuthenticationPassword("test-password");
        org.mockserver.extension.DataPlaneHooks hooks = mock(org.mockserver.extension.DataPlaneHooks.class);
        when(mockHttpStateHandler.dataPlaneHooks()).thenReturn(hooks);
        when(mockHttpStateHandler.getMockServerLogger()).thenReturn(mockServerLogger);
        HttpActionHandler handler = new HttpActionHandler(configuration, null, mockHttpStateHandler, null, null);
        ResponseWriter writer = mock(ResponseWriter.class, withSettings().extraInterfaces(org.mockserver.responsewriter.TransformingWriter.class));
        request.withHeader("Proxy-Authorization", "Basic rejected-secret");

        handler.processAction(request, writer, null, new HashSet<>(), false, true, false);

        ArgumentCaptor<HttpResponse> rejected = ArgumentCaptor.forClass(HttpResponse.class);
        verify((org.mockserver.responsewriter.TransformingWriter) writer).writeUntransformed(eq(request), rejected.capture());
        assertThat(rejected.getValue().getStatusCode(), is(407));
        assertThat(request.containsHeader("Proxy-Authorization"), is(false));
        assertThat(request.getProxyAuthenticationContext(), nullValue());
        verifyNoInteractions(hooks);
        verify(mockHttpStateHandler, never()).firstMatchingExpectation(any());
        verify(writer, never()).writeResponse(any(), any(), anyBoolean());
    }

    @Test
    public void shouldRemoveProxyCredentialsBeforeMatching() {
        configuration.proxyAuthenticationUsername("recorder").proxyAuthenticationPassword("test-password");
        request.withHeader("Host", "origin.example")
            .withHeader("Proxy-Authorization", org.mockserver.authentication.ProxyAuthenticationValidator
                .expectedProxyAuthorizationHeaderValue("recorder", "test-password"))
            .withHeader("Authorization", "Bearer origin-secret")
            .withHeader("Cookie", "session=origin-cookie");

        String expectedCredential = request.getFirstHeader("Proxy-Authorization");
        when(mockHttpStateHandler.firstMatchingExpectation(any(HttpRequest.class))).thenAnswer(invocation -> {
            HttpRequest matching = invocation.getArgument(0);
            assertThat(matching.containsHeader("Proxy-Authorization"), is(false));
            assertThat(matching.getProxyAuthenticationContext(), nullValue());
            return expectation;
        });

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        assertThat(request.containsHeader("Proxy-Authorization"), is(false));
        assertThat(request.getProxyAuthenticationContext(), nullValue());
        assertThat(request.getFirstHeader("Authorization"), is("Bearer origin-secret"));
        assertThat(request.getFirstHeader("Cookie"), is("session=origin-cookie"));
        verify(mockHttpResponseActionHandler).handle(any(HttpResponse.class), eq(request), any(RequestDefinition.class));
        ArgumentCaptor<LogEntry> logs = ArgumentCaptor.forClass(LogEntry.class);
        verify(mockServerLogger, atLeastOnce()).logEvent(logs.capture());
        assertThat(logs.getAllValues().toString(), not(containsString("Proxy-Authorization")));
        assertThat(logs.getAllValues().toString(), not(containsString(expectedCredential)));
    }

    @Test
    public void shouldSanitizeRejectedProxyCredentialsInAuditAndPreventMatchedAction() {
        configuration.proxyAuthenticationUsername("recorder").proxyAuthenticationPassword("test-password");
        request.withHeader("Host", "origin.example").withHeader("Proxy-Authorization", "Basic rejected-secret");

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        ArgumentCaptor<HttpResponse> rejected = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), rejected.capture(), eq(false));
        assertThat(rejected.getValue().getStatusCode(), is(407));
        assertThat(request.containsHeader("Proxy-Authorization"), is(false));
        assertThat(request.getProxyAuthenticationContext(), nullValue());
        verify(mockHttpStateHandler, never()).firstMatchingExpectation(any());
        verifyNoInteractions(mockHttpResponseActionHandler);
        ArgumentCaptor<LogEntry> logs = ArgumentCaptor.forClass(LogEntry.class);
        verify(mockServerLogger, atLeastOnce()).logEvent(logs.capture());
        assertThat(logs.getAllValues().toString(), not(containsString("rejected-secret")));
    }

    @Test
    public void shouldPreserveProxyAuthorizationForLocalMockMatchingWhenProxyAuthenticationIsDisabled() {
        request.withHeader("Host", "localhost:1080").withHeader("Proxy-Authorization", "Bearer mock-value");
        when(mockHttpStateHandler.firstMatchingExpectation(any(HttpRequest.class))).thenAnswer(invocation -> {
            HttpRequest matching = invocation.getArgument(0);
            assertThat(matching.getFirstHeader("Proxy-Authorization"), is("Bearer mock-value"));
            return expectation;
        });

        actionHandler.processAction(request, mockResponseWriter, null, java.util.Collections.singleton("localhost:1080"), false, true);

        verify(mockHttpStateHandler).firstMatchingExpectation(any(HttpRequest.class));
        verify(mockHttpResponseActionHandler).handle(any(HttpResponse.class), eq(request), any(RequestDefinition.class));
    }

    @Test
    public void shouldRemoveProxyCredentialsBeforeRequestFilters() {
        configuration.proxyAuthenticationUsername("recorder").proxyAuthenticationPassword("test-password");
        String credential = org.mockserver.authentication.ProxyAuthenticationValidator
            .expectedProxyAuthorizationHeaderValue("recorder", "test-password");
        request.withHeader("Proxy-Authorization", credential);
        org.mockserver.extension.DataPlaneHooks hooks = mock(org.mockserver.extension.DataPlaneHooks.class);
        when(hooks.hasRequestFilters()).thenReturn(true);
        org.mockserver.extension.DataPlaneOutcome outcome = mock(org.mockserver.extension.DataPlaneOutcome.class);
        when(outcome.isRespond()).thenReturn(true);
        when(outcome.request()).thenReturn(request);
        when(outcome.response()).thenReturn(response);
        when(hooks.filterAndWait(any(), any(), any())).thenAnswer(invocation -> {
            HttpRequest inspected = invocation.getArgument(0);
            assertThat(inspected.containsHeader("Proxy-Authorization"), is(false));
            assertThat(inspected.getProxyAuthenticationContext(), nullValue());
            return outcome;
        });
        when(mockHttpStateHandler.dataPlaneHooks()).thenReturn(hooks);
        when(mockHttpStateHandler.getMockServerLogger()).thenReturn(mockServerLogger);
        HttpActionHandler handler = new HttpActionHandler(configuration, null, mockHttpStateHandler, null, null);

        handler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true, false);

        verify(hooks).filterAndWait(any(), any(), any());
        verify(mockResponseWriter).writeResponse(eq(request), eq(response), eq(false));
        ArgumentCaptor<LogEntry> logs = ArgumentCaptor.forClass(LogEntry.class);
        verify(mockServerLogger, atLeastOnce()).logEvent(logs.capture());
        assertThat(logs.getAllValues().toString(), not(containsString(credential)));
    }

    @Test
    public void shouldRequireProxyCredentialsOnProxyOnlyListenerEvenForLocalHost() {
        configuration.proxyAuthenticationUsername("recorder").proxyAuthenticationPassword("test-password");
        request.withHeader("Host", "localhost:1080");
        java.util.Set<String> localAddresses = java.util.Collections.singleton("localhost:1080");

        actionHandler.processAction(request, mockResponseWriter, null, localAddresses, false, true, false);

        ArgumentCaptor<HttpResponse> rejected = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), rejected.capture(), eq(false));
        assertThat(rejected.getValue().getStatusCode(), is(407));
        verifyNoInteractions(mockHttpResponseActionHandler);
    }

    @Test
    public void shouldProcessResponseAction() {
        // given
        HttpResponse response = response("some_body").withDelay(milliseconds(1));
        expectation = new Expectation(request).thenRespond(response);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then
        verify(mockHttpResponseActionHandler).handle(eq(response), any(HttpRequest.class), any(RequestDefinition.class));
        verify(mockResponseWriter).writeResponse(request, this.response, false);
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request)
                .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
                .setArguments(request)
        );
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setLogLevel(INFO)
                .setType(EXPECTATION_RESPONSE)
                .setHttpRequest(request)
                .setHttpResponse(this.response)
                .setExpectationId(expectation.getAction().getExpectationId())
                .setMessageFormat("returning response:{}for request:{}for action:{}from expectation:{}")
                .setArguments(this.response, request, response, expectation.getId())
        );
        verify(scheduler).schedule(any(Runnable.class), eq(true), eq(milliseconds(0)));
        verify(scheduler).schedule(any(Runnable.class), eq(true), eq(milliseconds(0)));
    }

    @Test
    public void shouldRunBlockingBeforeActionThenPrimaryAction() {
        // given
        HttpRequest webhook = request("/before-webhook");
        expectation = new Expectation(request)
            .withBeforeActions(AfterAction.beforeAction().withHttpRequest(webhook))
            .thenRespond(response);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockNettyHttpClient.sendRequest(any(HttpRequest.class), anyLong(), any(TimeUnit.class))).thenReturn(response("webhook_ok"));

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then
        verify(mockNettyHttpClient).sendRequest(any(HttpRequest.class), anyLong(), any(TimeUnit.class));
        verify(mockHttpResponseActionHandler).handle(eq(response), any(HttpRequest.class), any(RequestDefinition.class));
        verify(mockResponseWriter).writeResponse(request, response, false);
    }

    @Test
    public void shouldFailFastWhenBlockingBeforeActionFails() {
        // given
        HttpRequest webhook = request("/before-webhook");
        expectation = new Expectation(request)
            .withBeforeActions(AfterAction.beforeAction().withHttpRequest(webhook).withFailurePolicy(FailurePolicy.FAIL_FAST))
            .thenRespond(response);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockNettyHttpClient.sendRequest(any(HttpRequest.class), anyLong(), any(TimeUnit.class))).thenThrow(new RuntimeException("downstream unavailable"));

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then the primary action is never dispatched and a 502 is returned
        verify(mockHttpResponseActionHandler, never()).handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class));
        verify(mockResponseWriter).writeResponse(eq(request), argThat(r -> r != null && r.getStatusCode() == 502), eq(false));
        // and the expectation is still post-processed so matcher state is cleaned up on abort
        verify(mockHttpStateHandler).postProcess(expectation);
    }

    @Test
    public void shouldContinueToPrimaryActionWhenBestEffortBeforeActionFails() {
        // given (failurePolicy defaults to BEST_EFFORT)
        HttpRequest webhook = request("/before-webhook");
        expectation = new Expectation(request)
            .withBeforeActions(AfterAction.beforeAction().withHttpRequest(webhook))
            .thenRespond(response);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockNettyHttpClient.sendRequest(any(HttpRequest.class), anyLong(), any(TimeUnit.class))).thenThrow(new RuntimeException("downstream unavailable"));

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then the failure is swallowed and the primary response is still returned
        verify(mockHttpResponseActionHandler).handle(eq(response), any(HttpRequest.class), any(RequestDefinition.class));
        verify(mockResponseWriter).writeResponse(request, response, false);
    }

    @Test
    public void shouldDispatchNonBlockingBeforeActionAndProceed() {
        // given
        HttpRequest webhook = request("/before-webhook");
        expectation = new Expectation(request)
            .withBeforeActions(AfterAction.beforeAction().withHttpRequest(webhook).withBlocking(false))
            .thenRespond(response);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockNettyHttpClient.sendRequest(any(HttpRequest.class))).thenReturn(responseFuture);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then a non-blocking before-action does not gate the response (no blocking timeout call)
        verify(mockNettyHttpClient, never()).sendRequest(any(HttpRequest.class), anyLong(), any(TimeUnit.class));
        verify(mockHttpResponseActionHandler).handle(eq(response), any(HttpRequest.class), any(RequestDefinition.class));
        verify(mockResponseWriter).writeResponse(request, response, false);
    }

    @Test
    public void shouldFireAfterActionWebhookAfterResponseIsWritten() {
        // given - an expectation that responds AND fires an outbound webhook after responding
        HttpRequest webhook = request("/after-webhook");
        expectation = new Expectation(request)
            .withAfterActions(AfterAction.afterAction().withHttpRequest(webhook))
            .thenRespond(response);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        CompletableFuture<HttpResponse> webhookFuture = new CompletableFuture<>();
        webhookFuture.complete(response("webhook_ok"));
        when(mockNettyHttpClient.sendRequest(any(HttpRequest.class))).thenReturn(webhookFuture);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then the primary response is returned promptly
        verify(mockHttpResponseActionHandler).handle(eq(response), any(HttpRequest.class), any(RequestDefinition.class));
        verify(mockResponseWriter).writeResponse(request, response, false);
        // and the after-action webhook is fired to the configured URL (asynchronously, after the response)
        verify(mockNettyHttpClient, timeout(5000)).sendRequest(argThat(r -> r != null && "/after-webhook".equals(r.getPath().getValue())));
        // and the webhook fired strictly after the response was written
        InOrder inOrder = inOrder(mockResponseWriter, mockNettyHttpClient);
        inOrder.verify(mockResponseWriter).writeResponse(request, response, false);
        inOrder.verify(mockNettyHttpClient).sendRequest(any(HttpRequest.class));
    }

    @Test
    public void shouldNotFailPrimaryResponseWhenAfterActionWebhookTargetUnreachable() {
        // given - the webhook target is unreachable (the async send fails)
        HttpRequest webhook = request("/after-webhook");
        expectation = new Expectation(request)
            .withAfterActions(AfterAction.afterAction().withHttpRequest(webhook))
            .thenRespond(response);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        CompletableFuture<HttpResponse> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("webhook target unreachable"));
        when(mockNettyHttpClient.sendRequest(any(HttpRequest.class))).thenReturn(failedFuture);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then the primary response is still returned (fire-and-forget: the failure does not propagate)
        verify(mockHttpResponseActionHandler).handle(eq(response), any(HttpRequest.class), any(RequestDefinition.class));
        verify(mockResponseWriter).writeResponse(request, response, false);
        // and the webhook was attempted (then its failure swallowed)
        verify(mockNettyHttpClient, timeout(5000)).sendRequest(any(HttpRequest.class));
    }

    @Test
    public void shouldDelayAfterActionWebhookByConfiguredDelay() {
        // given - an after-action webhook with an explicit delay
        HttpRequest webhook = request("/after-webhook");
        expectation = new Expectation(request)
            .withAfterActions(AfterAction.afterAction().withHttpRequest(webhook).withDelay(milliseconds(300)))
            .thenRespond(response);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        CompletableFuture<HttpResponse> webhookFuture = new CompletableFuture<>();
        webhookFuture.complete(response("webhook_ok"));
        when(mockNettyHttpClient.sendRequest(any(HttpRequest.class))).thenReturn(webhookFuture);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then the response is written promptly (not delayed by the webhook)
        verify(mockResponseWriter).writeResponse(request, response, false);
        // and the webhook is NOT fired immediately (still within the configured delay window)
        verify(mockNettyHttpClient, after(100).never()).sendRequest(any(HttpRequest.class));
        // but it does eventually fire once the delay elapses
        verify(mockNettyHttpClient, timeout(5000)).sendRequest(any(HttpRequest.class));
    }

    @Test
    public void shouldRenderAfterActionWebhookTemplateAgainstTriggeringRequest() {
        // given - an after-action whose request is a Velocity template over the triggering request
        HttpTemplate webhook = template(HttpTemplate.TemplateType.VELOCITY, "{ \"method\": \"POST\", \"path\": \"/hook$!request.path\", \"body\": \"got $!request.body\" }");
        HttpRequest triggering = request("/order").withBody("A-1");
        expectation = new Expectation(triggering)
            .withAfterActions(AfterAction.afterAction().withHttpRequestTemplate(webhook))
            .thenRespond(response);
        when(mockHttpStateHandler.firstMatchingExpectation(triggering)).thenReturn(expectation);
        when(mockHttpForwardTemplateActionHandler.resolveTemplateEngine(webhook)).thenReturn(new VelocityTemplateEngine(new MockServerLogger(), configuration));
        CompletableFuture<HttpResponse> webhookFuture = new CompletableFuture<>();
        webhookFuture.complete(response("webhook_ok"));
        when(mockNettyHttpClient.sendRequest(any(HttpRequest.class))).thenReturn(webhookFuture);

        // when
        actionHandler.processAction(triggering, mockResponseWriter, null, new HashSet<>(), false, true);

        // then the response is written and the rendered webhook is sent
        verify(mockResponseWriter).writeResponse(triggering, response, false);
        verify(mockNettyHttpClient, timeout(5000)).sendRequest(argThat(r -> r != null
            && "POST".equals(r.getMethod().getValue())
            && "/hook/order".equals(r.getPath().getValue())
            && "got A-1".equals(r.getBodyAsString())));
    }

    @Test
    public void shouldRenderJavaScriptAfterActionWebhookTemplateAgainstTriggeringRequest() {
        Assume.assumeTrue("JavaScript engine not available", JavaScriptTemplateEngine.isPolyglotAvailable());
        // given
        HttpTemplate webhook = template(HttpTemplate.TemplateType.JAVASCRIPT, "return { 'method': 'PUT', 'path': '/hook' + request.path, 'body': 'got ' + request.body };");
        HttpRequest triggering = request("/order").withBody("A-1");
        expectation = new Expectation(triggering)
            .withAfterActions(AfterAction.afterAction().withHttpRequestTemplate(webhook))
            .thenRespond(response);
        when(mockHttpStateHandler.firstMatchingExpectation(triggering)).thenReturn(expectation);
        when(mockHttpForwardTemplateActionHandler.resolveTemplateEngine(webhook)).thenReturn(new JavaScriptTemplateEngine(new MockServerLogger(), configuration));
        CompletableFuture<HttpResponse> webhookFuture = new CompletableFuture<>();
        webhookFuture.complete(response("webhook_ok"));
        when(mockNettyHttpClient.sendRequest(any(HttpRequest.class))).thenReturn(webhookFuture);

        // when
        actionHandler.processAction(triggering, mockResponseWriter, null, new HashSet<>(), false, true);

        // then
        verify(mockResponseWriter).writeResponse(triggering, response, false);
        verify(mockNettyHttpClient, timeout(5000)).sendRequest(argThat(r -> r != null
            && "PUT".equals(r.getMethod().getValue())
            && "/hook/order".equals(r.getPath().getValue())
            && "got A-1".equals(r.getBodyAsString())));
    }

    @Test
    public void shouldNotSendAfterActionWebhookWhenTemplateDoesNotRenderARequest() {
        // given
        HttpTemplate webhook = template(HttpTemplate.TemplateType.VELOCITY, "not a request");
        expectation = new Expectation(request)
            .withAfterActions(AfterAction.afterAction().withHttpRequestTemplate(webhook))
            .thenRespond(response);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardTemplateActionHandler.resolveTemplateEngine(webhook)).thenReturn(new VelocityTemplateEngine(new MockServerLogger(), configuration));

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then the primary response is unaffected, the failure is logged and nothing is sent
        verify(mockResponseWriter).writeResponse(request, response, false);
        verify(mockServerLogger, timeout(5000)).logEvent(argThat(entry -> entry != null
            && "after-action webhook not sent:{}".equals(entry.getMessageFormat())
            && String.valueOf(entry.getArguments()[0]).startsWith("VELOCITY webhook template rendered output that is not a valid request")));
        verify(mockNettyHttpClient, never()).sendRequest(any(HttpRequest.class));
    }

    @Test
    public void shouldSendRenderedBlockingBeforeActionWebhookTemplateBeforePrimaryAction() {
        // given
        HttpTemplate webhook = template(HttpTemplate.TemplateType.VELOCITY, "{ \"path\": \"/before$!request.path\" }");
        expectation = new Expectation(request)
            .withBeforeActions(AfterAction.beforeAction().withHttpRequestTemplate(webhook))
            .thenRespond(response);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardTemplateActionHandler.resolveTemplateEngine(webhook)).thenReturn(new VelocityTemplateEngine(new MockServerLogger(), configuration));
        when(mockNettyHttpClient.sendRequest(any(HttpRequest.class), anyLong(), any(TimeUnit.class))).thenReturn(response("webhook_ok"));

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then the rendered webhook is awaited before the primary response
        InOrder inOrder = inOrder(mockNettyHttpClient, mockResponseWriter);
        inOrder.verify(mockNettyHttpClient).sendRequest(argThat(r -> r != null && "/beforesome_path".equals(r.getPath().getValue())), anyLong(), any(TimeUnit.class));
        inOrder.verify(mockResponseWriter).writeResponse(request, response, false);
    }

    @Test
    public void shouldFailFastWhenBlockingBeforeActionWebhookTemplateFails() {
        // given
        HttpTemplate webhook = template(HttpTemplate.TemplateType.VELOCITY, "not a request");
        expectation = new Expectation(request)
            .withBeforeActions(AfterAction.beforeAction().withHttpRequestTemplate(webhook).withFailurePolicy(FailurePolicy.FAIL_FAST))
            .thenRespond(response);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardTemplateActionHandler.resolveTemplateEngine(webhook)).thenReturn(new VelocityTemplateEngine(new MockServerLogger(), configuration));

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then nothing is sent, the primary action is skipped and a 502 names the template failure
        verify(mockNettyHttpClient, never()).sendRequest(any(HttpRequest.class), anyLong(), any(TimeUnit.class));
        verify(mockHttpResponseActionHandler, never()).handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class));
        verify(mockResponseWriter).writeResponse(eq(request), argThat(r -> r != null && r.getStatusCode() == 502
            && r.getBodyAsString().startsWith("before-action failed: VELOCITY webhook template")), eq(false));
        verify(mockHttpStateHandler).postProcess(expectation);
    }

    @Test
    public void shouldNotReturnTemplateTextInFailFastResponseWhenWebhookTemplateEngineFails() {
        // given - a template holding a credential whose evaluation throws
        HttpTemplate webhook = template(HttpTemplate.TemplateType.VELOCITY,
            "{ \"path\": \"/hook$request.path.substring(999)\", \"headers\": { \"Authorization\": [ \"Bearer TOP-SECRET-TOKEN\" ] } }");
        expectation = new Expectation(request)
            .withBeforeActions(AfterAction.beforeAction().withHttpRequestTemplate(webhook).withFailurePolicy(FailurePolicy.FAIL_FAST))
            .thenRespond(response);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardTemplateActionHandler.resolveTemplateEngine(webhook)).thenReturn(new VelocityTemplateEngine(new MockServerLogger(), configuration));

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then the 502 names the failure without the template or the credential
        ArgumentCaptor<HttpResponse> written = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), written.capture(), eq(false));
        assertThat(written.getValue().getStatusCode(), is(502));
        assertThat(written.getValue().getBodyAsString(), containsString("before-action failed: VELOCITY webhook template failed"));
        assertThat(written.getValue().getBodyAsString(), not(containsString("TOP-SECRET-TOKEN")));
        assertThat(written.getValue().getBodyAsString(), not(containsString("Authorization")));
        verify(mockNettyHttpClient, never()).sendRequest(any(HttpRequest.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    public void shouldAnswerFailFastWhenBlockingBeforeActionWebhookTemplateHasNoTemplateType() {
        // given
        HttpTemplate webhook = new HttpTemplate(null).withTemplate("{ \"path\": \"/hook\" }");
        expectation = new Expectation(request)
            .withBeforeActions(AfterAction.beforeAction().withHttpRequestTemplate(webhook).withFailurePolicy(FailurePolicy.FAIL_FAST))
            .thenRespond(response);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then the client is answered and the expectation post-processed
        verify(mockResponseWriter).writeResponse(eq(request), argThat(r -> r != null && r.getStatusCode() == 502
            && r.getBodyAsString().contains("templateType is required")), eq(false));
        verify(mockHttpStateHandler).postProcess(expectation);
        verify(mockNettyHttpClient, never()).sendRequest(any(HttpRequest.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    public void shouldRespondWhenBestEffortBlockingBeforeActionWebhookTemplateHasNoTemplateType() {
        // given
        HttpTemplate webhook = new HttpTemplate(null).withTemplate("{ \"path\": \"/hook\" }");
        expectation = new Expectation(request)
            .withBeforeActions(AfterAction.beforeAction().withHttpRequestTemplate(webhook))
            .thenRespond(response);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then
        verify(mockResponseWriter).writeResponse(request, response, false);
    }

    @Test
    public void shouldContinueToPrimaryActionWhenBestEffortBeforeActionWebhookTemplateFails() {
        // given
        HttpTemplate webhook = template(HttpTemplate.TemplateType.VELOCITY, "not a request");
        expectation = new Expectation(request)
            .withBeforeActions(AfterAction.beforeAction().withHttpRequestTemplate(webhook))
            .thenRespond(response);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardTemplateActionHandler.resolveTemplateEngine(webhook)).thenReturn(new VelocityTemplateEngine(new MockServerLogger(), configuration));

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then
        verify(mockNettyHttpClient, never()).sendRequest(any(HttpRequest.class), anyLong(), any(TimeUnit.class));
        verify(mockResponseWriter).writeResponse(request, response, false);
    }

    @Test
    public void shouldProcessResponseTemplateAction() {
        // given
        HttpTemplate template = template(HttpTemplate.TemplateType.JAVASCRIPT, "some_template").withDelay(milliseconds(1));
        expectation = new Expectation(request).thenRespond(template);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then
        verify(mockHttpResponseTemplateActionHandler).handle(template, request);
        verify(mockResponseWriter).writeResponse(request, response, false);
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request)
                .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
                .setArguments(request)
        );
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setLogLevel(INFO)
                .setType(EXPECTATION_RESPONSE)
                .setHttpRequest(request)
                .setHttpResponse(response)
                .setExpectationId(expectation.getAction().getExpectationId())
                .setMessageFormat("returning response:{}for request:{}for action:{}from expectation:{}")
                .setArguments(response, request, template, expectation.getId())
        );
        // RESPONSE_TEMPLATE render is now dispatched via scheduleTemplateAction (dedicated bounded template
        // pool) rather than the shared scheduler.schedule path; the action delay is still carried through.
        verify(scheduler).scheduleTemplateAction(any(Runnable.class), eq(true), eq(milliseconds(1)));
        // the inner response write (writeResponseActionResponse) still uses the shared scheduler.schedule path
        verify(scheduler).schedule(any(Runnable.class), eq(true), eq(milliseconds(0)));
    }

    @Test
    public void shouldHandleResponseTemplateActionException() {
        // given
        HttpTemplate template = template(HttpTemplate.TemplateType.JAVASCRIPT, "some_template").withDelay(milliseconds(1));
        expectation = new Expectation(request).thenRespond(template);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        RuntimeException throwable = new RuntimeException("TEST_EXCEPTION");
        when(mockHttpResponseTemplateActionHandler.handle(any(HttpTemplate.class), any(HttpRequest.class))).thenThrow(throwable);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then
        verify(mockHttpResponseTemplateActionHandler).handle(template, request);
        verify(mockResponseWriter).writeResponse(request, notFoundResponse(), false);
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request)
                .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
                .setArguments(request)
        );
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(EXPECTATION_RESPONSE)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request)
                .setHttpResponse(notFoundResponse())
                .setExpectationId(expectation.getAction().getExpectationId())
                .setMessageFormat("returning response:{}for request:{}for action:{}from expectation:{}")
                .setArguments(notFoundResponse(), request, expectation.getAction(), expectation.getId())
        );
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(WARN)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request)
                .setMessageFormat(throwable.getMessage())
                .setThrowable(throwable)
        );
    }

    @Test
    public void shouldProcessResponseClassCallbackAction() {
        // given
        HttpClassCallback callback = callback("some_class").withDelay(milliseconds(1));
        expectation = new Expectation(request).thenRespond(callback);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then
        verify(mockHttpResponseClassCallbackActionHandler).handle(callback, request);
        verify(mockResponseWriter).writeResponse(request, response, false);
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request)
                .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
                .setArguments(request)
        );
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(EXPECTATION_RESPONSE)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setHttpResponse(response)
                .setExpectationId(expectation.getAction().getExpectationId())
                .setMessageFormat("returning response:{}for request:{}for action:{}from expectation:{}")
                .setArguments(response, request, callback, expectation.getId())
        );
        verify(scheduler).scheduleLocalCallback(any(Runnable.class), eq(true), eq(milliseconds(1)));
        verify(scheduler).schedule(any(Runnable.class), eq(true), eq(milliseconds(0)));
    }

    @Test
    public void shouldProcessResponseObjectCallbackAction() {
        // given
        HttpObjectCallback callback = new HttpObjectCallback().withClientId("some_request_client_id").withDelay(milliseconds(1));
        expectation = new Expectation(request).thenRespond(callback);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        ResponseWriter mockResponseWriter = mock(ResponseWriter.class);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then
        verify(mockHttpResponseObjectCallbackActionHandler).handle(any(HttpActionHandler.class), same(callback), same(request), same(mockResponseWriter), eq(true), any(Runnable.class));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request)
                .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
                .setArguments(request)
        );
    }

    @Test
    public void shouldProcessForwardAction() {
        // given
        HttpForward forward = forward()
            .withHost("localhost")
            .withPort(1090);
        expectation = new Expectation(request).thenForward(forward);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then
        verify(mockHttpForwardActionHandler).handle(forward, request);
        verify(mockResponseWriter).writeResponse(request, response, false);
        InetSocketAddress remoteAddress = httpForwardActionResult.getRemoteAddress();
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request)
                .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
                .setArguments(request)
        );
        // the LOGGED forwarded response carries the internal upstream round-trip header
        // (here 0ms — the mocked response has no Timing); the client response stays clean
        HttpResponse loggedResponse = response.clone().withHeader("x-mockserver-response-time-ms", "0");
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setLogLevel(INFO)
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request)
                .setHttpResponse(loggedResponse)
                .setExpectation(request, loggedResponse)
                .setExpectationId(expectation.getAction().getExpectationId())
                .setMessageFormat("returning response:{}for forwarded request" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}for action:{}from expectation:{}")
                .setArguments(loggedResponse, forwardedHttpRequest, "curl -v 'http://" + remoteAddress.getHostName() + ":" + remoteAddress.getPort() + "/'", expectation.getAction(), expectation.getId())
        );
        verify(httpRequestToCurlSerializer, atLeastOnce()).toCurl(forwardedHttpRequest, remoteAddress);
    }

    @Test
    public void shouldProcessForwardTemplateAction() {
        // given
        HttpTemplate template = template(HttpTemplate.TemplateType.JAVASCRIPT, "some_template");
        expectation = new Expectation(request).thenForward(template);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then
        verify(mockHttpForwardTemplateActionHandler).handle(template, request);
        verify(mockResponseWriter).writeResponse(request, response, false);
        InetSocketAddress remoteAddress = httpForwardActionResult.getRemoteAddress();
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request)
                .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
                .setArguments(request)
        );
        HttpResponse loggedResponse = response.clone().withHeader("x-mockserver-response-time-ms", "0");
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setLogLevel(INFO)
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request)
                .setHttpResponse(loggedResponse)
                .setExpectation(request, loggedResponse)
                .setExpectationId(expectation.getAction().getExpectationId())
                .setMessageFormat("returning response:{}for forwarded request" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}for action:{}from expectation:{}")
                .setArguments(loggedResponse, forwardedHttpRequest, "curl -v 'http://" + remoteAddress.getHostName() + ":" + remoteAddress.getPort() + "/'", expectation.getAction(), expectation.getId())
        );
        verify(httpRequestToCurlSerializer, atLeastOnce()).toCurl(forwardedHttpRequest, remoteAddress);
    }

    @Test
    public void shouldHandleForwardTemplateActionException() {
        // given
        HttpTemplate template = template(HttpTemplate.TemplateType.JAVASCRIPT, "some_template");
        expectation = new Expectation(request).thenForward(template);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        RuntimeException throwable = new RuntimeException("TEST_EXCEPTION");
        when(mockHttpForwardTemplateActionHandler.handle(any(HttpTemplate.class), any(HttpRequest.class))).thenThrow(throwable);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then
        verify(mockHttpForwardTemplateActionHandler).handle(template, request);
        verify(mockResponseWriter).writeResponse(request, notFoundResponse(), false);
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request)
                .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
                .setArguments(request)
        );
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(EXPECTATION_RESPONSE)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request)
                .setHttpResponse(notFoundResponse())
                .setExpectationId(expectation.getAction().getExpectationId())
                .setMessageFormat("returning response:{}for request:{}for action:{}from expectation:{}")
                .setArguments(notFoundResponse(), request, expectation.getAction(), expectation.getId())
        );
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(WARN)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request)
                .setMessageFormat(throwable.getMessage())
                .setThrowable(throwable)
        );
    }

    @Test
    public void shouldAnswerFailedResponseTemplateWithNotFoundWhenDisabled() {
        // given
        configuration.templateFailureAsServerError(false);
        HttpTemplate template = template(HttpTemplate.TemplateType.VELOCITY, "some_template");
        expectation = new Expectation(request).thenRespond(template);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        RuntimeException engineFailure = new RuntimeException("Exception: TEST_EXCEPTION transforming template", new IllegalStateException("TEST_EXCEPTION"));
        when(mockHttpResponseTemplateActionHandler.handle(any(HttpTemplate.class), any(HttpRequest.class)))
            .thenThrow(new TemplateFailureException(HttpTemplate.TemplateType.VELOCITY, "response", engineFailure));

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the 404 is unchanged and the engine's own failure, not the marker, is what is logged
        verify(mockResponseWriter).writeResponse(request, notFoundResponse(), false);
        assertThat(loggedWarningThrowables(), hasItem(sameInstance(engineFailure)));
        assertThat(loggedWarningThrowables(), not(hasItem(instanceOf(TemplateFailureException.class))));
    }

    private List<Throwable> loggedWarningThrowables() {
        ArgumentCaptor<LogEntry> logEntries = ArgumentCaptor.forClass(LogEntry.class);
        verify(mockServerLogger, atLeastOnce()).logEvent(logEntries.capture());
        return logEntries.getAllValues().stream().filter(entry -> entry.getType() == WARN).map(LogEntry::getThrowable).collect(Collectors.toList());
    }

    @Test
    public void shouldAnswerFailedResponseTemplateWithServerErrorByDefault() {
        // given
        HttpTemplate template = template(HttpTemplate.TemplateType.VELOCITY, "some_template");
        expectation = new Expectation(request).thenRespond(template);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        RuntimeException engineFailure = new RuntimeException("Exception: TEST_EXCEPTION transforming template", new IllegalStateException("TEST_EXCEPTION"));
        when(mockHttpResponseTemplateActionHandler.handle(any(HttpTemplate.class), any(HttpRequest.class)))
            .thenThrow(new TemplateFailureException(HttpTemplate.TemplateType.VELOCITY, "response", engineFailure));

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then
        HttpResponse templateError = response()
            .withStatusCode(500)
            .withReasonPhrase("Internal Server Error")
            .withHeader("x-mockserver-template-error", "true")
            .withHeader("content-type", "text/plain; charset=utf-8")
            .withBody("VELOCITY response template failed: TEST_EXCEPTION");
        verify(mockResponseWriter).writeResponse(request, templateError, false);
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(WARN)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request)
                .setMessageFormat(engineFailure.getMessage())
                .setThrowable(engineFailure)
        );
    }

    @Test
    public void shouldAnswerFailedForwardTemplateWithServerErrorByDefault() {
        // given
        HttpTemplate template = template(HttpTemplate.TemplateType.MUSTACHE, "some_template");
        expectation = new Expectation(request).thenForward(template);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        RuntimeException engineFailure = new RuntimeException("Exception: TEST_EXCEPTION transforming template", new IllegalStateException("TEST_EXCEPTION"));
        when(mockHttpForwardTemplateActionHandler.handle(any(HttpTemplate.class), any(HttpRequest.class)))
            .thenThrow(new TemplateFailureException(HttpTemplate.TemplateType.MUSTACHE, "forward", engineFailure));

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then
        HttpResponse templateError = response()
            .withStatusCode(500)
            .withReasonPhrase("Internal Server Error")
            .withHeader("x-mockserver-template-error", "true")
            .withHeader("content-type", "text/plain; charset=utf-8")
            .withBody("MUSTACHE forward template failed: TEST_EXCEPTION");
        verify(mockResponseWriter).writeResponse(request, templateError, false);
    }

    @Test
    public void shouldKeepNotFoundForFailureThatIsNotATemplate() {
        // given - templateFailureAsServerError, on by default, applies to template failures only
        HttpClassCallback callback = callback("some_class").withDelay(milliseconds(0));
        expectation = new Expectation(request).thenRespond(callback);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseClassCallbackActionHandler.handle(any(HttpClassCallback.class), any(HttpRequest.class))).thenThrow(new RuntimeException("TEST_EXCEPTION"));

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then
        verify(mockResponseWriter).writeResponse(request, notFoundResponse(), false);
    }

    @Test
    public void shouldProcessForwardClassCallbackAction() {
        // given
        HttpClassCallback callback = callback("some_class");
        expectation = new Expectation(request).thenForward(callback);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then
        verify(mockHttpForwardClassCallbackActionHandler).handle(callback, request);
        verify(mockResponseWriter).writeResponse(request, response, false);
        InetSocketAddress remoteAddress = httpForwardActionResult.getRemoteAddress();
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request)
                .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
                .setArguments(request)
        );
        HttpResponse loggedResponse = response.clone().withHeader("x-mockserver-response-time-ms", "0");
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setLogLevel(INFO)
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request)
                .setHttpResponse(loggedResponse)
                .setExpectation(request, loggedResponse)
                .setExpectationId(expectation.getAction().getExpectationId())
                .setMessageFormat("returning response:{}for forwarded request" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}for action:{}from expectation:{}")
                .setArguments(loggedResponse, forwardedHttpRequest, "curl -v 'http://" + remoteAddress.getHostName() + ":" + remoteAddress.getPort() + "/'", expectation.getAction(), expectation.getId())
        );
        verify(httpRequestToCurlSerializer, atLeastOnce()).toCurl(forwardedHttpRequest, remoteAddress);
    }

    @Test
    public void shouldProcessForwardObjectCallbackAction() {
        // given
        HttpObjectCallback callback = new HttpObjectCallback().withClientId("some_forward_client_id");
        expectation = new Expectation(request).thenForward(callback);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        ResponseWriter mockResponseWriter = mock(ResponseWriter.class);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then
        verify(mockHttpForwardObjectCallbackActionHandler).handle(any(HttpActionHandler.class), same(callback), same(request), same(mockResponseWriter), eq(true), any(Runnable.class));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request)
                .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
                .setArguments(request)
        );
    }

    @Test
    public void shouldProcessOverrideForwardedRequest() {
        // given
        HttpOverrideForwardedRequest httpOverrideForwardedRequest = new HttpOverrideForwardedRequest().withRequestOverride(request("some_overridden_path"));
        expectation = new Expectation(request).thenForward(httpOverrideForwardedRequest);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        ResponseWriter mockResponseWriter = mock(ResponseWriter.class);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then
        verify(mockHttpOverrideForwardedRequestActionHandler).handle(httpOverrideForwardedRequest, request);
        InetSocketAddress remoteAddress = httpForwardActionResult.getRemoteAddress();
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request)
                .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
                .setArguments(request)
        );
        HttpResponse loggedResponse = response.clone().withHeader("x-mockserver-response-time-ms", "0");
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setLogLevel(INFO)
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request)
                .setHttpResponse(loggedResponse)
                .setExpectation(request, loggedResponse)
                .setExpectationId(expectation.getAction().getExpectationId())
                .setMessageFormat("returning response:{}for forwarded request" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}for action:{}from expectation:{}")
                .setArguments(loggedResponse, forwardedHttpRequest, "curl -v 'http://" + remoteAddress.getHostName() + ":" + remoteAddress.getPort() + "/'", expectation.getAction(), expectation.getId())
        );
        verify(httpRequestToCurlSerializer, atLeastOnce()).toCurl(forwardedHttpRequest, remoteAddress);
    }

    @Test
    public void shouldProcessErrorAction() {
        // given
        HttpError error = error();
        expectation = new Expectation(request).thenError(error);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        ResponseWriter mockResponseWriter = mock(ResponseWriter.class);
        Runnable responseEnded = () -> {
        };
        when(mockResponseWriter.respondingDirectly()).thenReturn(responseEnded);
        ChannelHandlerContext mockChannelHandlerContext = mock(ChannelHandlerContext.class);

        // when
        actionHandler.processAction(request, mockResponseWriter, mockChannelHandlerContext, new HashSet<>(), false, true);

        // then - the error handler is given this exchange's own end, not the connection's
        verify(mockHttpErrorActionHandler).handle(error, request, mockChannelHandlerContext, responseEnded);
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request)
                .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
                .setArguments(request)
        );
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setLogLevel(INFO)
                .setType(EXPECTATION_RESPONSE)
                .setHttpRequest(request)
                .setHttpError(error)
                .setExpectationId(expectation.getAction().getExpectationId())
                .setMessageFormat("returning error:{}for request:{}for action:{}from expectation:{}")
                .setArguments(error, request, error, expectation.getId())
        );
    }

    /**
     * INC fix: prove that the ERROR-action dispatch funnel ({@code HttpActionHandler.dispatchErrorAction})
     * delegates a stream error to the active {@link ResponseWriter} when it can reset its own transport
     * stream (the HTTP/3 case, where the writer implements {@link StreamErrorWriter}), rather than going
     * through the netty {@link HttpErrorActionHandler}. This is the wiring the HTTP/3 integration test
     * (which calls Http3ResponseWriter directly) does not exercise.
     */
    @Test
    public void shouldDelegateStreamErrorToStreamErrorWriterWhenResponseWriterSupportsIt() {
        // given - an error action carrying a stream error and a ResponseWriter that can reset its stream
        HttpError error = error().withStreamError(HttpError.StreamErrorCode.REFUSED_STREAM);
        expectation = new Expectation(request).thenError(error);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        StreamErrorAwareResponseWriter streamErrorWriter = mock(StreamErrorAwareResponseWriter.class);
        ChannelHandlerContext mockChannelHandlerContext = mock(ChannelHandlerContext.class);

        // when
        actionHandler.processAction(request, streamErrorWriter, mockChannelHandlerContext, new HashSet<>(), false, true);

        // then - the reset is delegated to the writer with the configured code, NOT to HttpErrorActionHandler
        verify(streamErrorWriter).writeStreamError(0x7L);
        verify(mockHttpErrorActionHandler, never()).handle(any(HttpError.class), any(HttpRequest.class), any(ChannelHandlerContext.class));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setLogLevel(INFO)
                .setType(EXPECTATION_RESPONSE)
                .setHttpRequest(request)
                .setHttpError(error)
                .setExpectationId(expectation.getAction().getExpectationId())
                .setMessageFormat("returning error:{}for request:{}for action:{}from expectation:{}")
                .setArguments(error, request, error, expectation.getId())
        );
    }

    /**
     * INC fix (companion): when the active {@link ResponseWriter} cannot reset its own stream (it does
     * not implement {@link StreamErrorWriter} — the HTTP/1.1 and HTTP/2 cases), a stream error is handled
     * by the netty {@link HttpErrorActionHandler} (which resets the HTTP/2 stream or drops the
     * connection), not by the writer.
     */
    @Test
    public void shouldHandleStreamErrorViaHttpErrorActionHandlerWhenResponseWriterCannotResetStream() {
        // given - an error action carrying a stream error and a plain ResponseWriter (not StreamErrorWriter)
        HttpError error = error().withStreamError(HttpError.StreamErrorCode.REFUSED_STREAM);
        expectation = new Expectation(request).thenError(error);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        ResponseWriter plainResponseWriter = mock(ResponseWriter.class);
        ChannelHandlerContext mockChannelHandlerContext = mock(ChannelHandlerContext.class);

        // when
        actionHandler.processAction(request, plainResponseWriter, mockChannelHandlerContext, new HashSet<>(), false, true);

        // then - the netty HttpErrorActionHandler applies the reset/connection-drop
        verify(mockHttpErrorActionHandler).handle(eq(error), eq(request), eq(mockChannelHandlerContext), any());
    }

    @Test
    public void shouldDelegateConnectionResetToStreamErrorWriterAheadOfStreamError() {
        // given - resetConnection and a stream error on the HTTP/3-style writer: the connection reset wins
        HttpError error = error().withResetConnection(true).withStreamError(HttpError.StreamErrorCode.REFUSED_STREAM);
        expectation = new Expectation(request).thenError(error);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        StreamErrorAwareResponseWriter streamErrorWriter = mock(StreamErrorAwareResponseWriter.class);

        // when
        actionHandler.processAction(request, streamErrorWriter, mock(ChannelHandlerContext.class), new HashSet<>(), false, true);

        // then
        verify(streamErrorWriter).writeConnectionReset();
        verify(streamErrorWriter, never()).writeStreamError(anyLong());
        verify(mockHttpErrorActionHandler, never()).handle(any(HttpError.class), any(HttpRequest.class), any(ChannelHandlerContext.class), any());
    }

    @Test
    public void shouldHandleConnectionResetViaHttpErrorActionHandlerWhenResponseWriterCannotResetStream() {
        // given
        HttpError error = error().withResetConnection(true);
        expectation = new Expectation(request).thenError(error);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        ChannelHandlerContext mockChannelHandlerContext = mock(ChannelHandlerContext.class);

        // when
        actionHandler.processAction(request, mock(ResponseWriter.class), mockChannelHandlerContext, new HashSet<>(), false, true);

        // then
        verify(mockHttpErrorActionHandler).handle(eq(error), eq(request), eq(mockChannelHandlerContext), any());
    }

    /**
     * Test double that is both a {@link ResponseWriter} and a {@link StreamErrorWriter}, so Mockito can
     * mock the HTTP/3-style writer used to verify {@code dispatchErrorAction} delegation.
     */
    public static abstract class StreamErrorAwareResponseWriter extends ResponseWriter implements StreamErrorWriter {
        protected StreamErrorAwareResponseWriter() {
            super(null, null);
        }
    }

    @Test
    public void shouldProcessResponseActionWithGlobalDelay() {
        // given
        configuration.globalResponseDelayMillis(200L);
        HttpResponse response = response("some_body").withDelay(milliseconds(0));
        expectation = new Expectation(request).thenRespond(response);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - writeResponseActionResponse combines action delay (from handled response) + global delay
        verify(scheduler).schedule(any(Runnable.class), eq(true), eq(milliseconds(0)), eq(milliseconds(200)));
        configuration.globalResponseDelayMillis(null);
    }

    @Test
    public void shouldProcessResponseActionWithGlobalDelayOnly() {
        // given
        configuration.globalResponseDelayMillis(300L);
        HttpResponse response = response("some_body").withDelay(milliseconds(0));
        expectation = new Expectation(request).thenRespond(response);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - writeResponseActionResponse combines 0ms action delay + 300ms global delay
        verify(scheduler).schedule(any(Runnable.class), eq(true), eq(milliseconds(0)), eq(milliseconds(300)));
        configuration.globalResponseDelayMillis(null);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldProxyRequestsWithRemoteSocketAttribute() {
        // given
        HttpRequest request = request("request_one");

        // and - remote socket attribute
        ChannelHandlerContext mockChannelHandlerContext = mock(ChannelHandlerContext.class);
        Channel mockChannel = mock(Channel.class);
        when(mockChannelHandlerContext.channel()).thenReturn(mockChannel);
        InetSocketAddress remoteAddress = new InetSocketAddress(1090);
        Attribute<InetSocketAddress> inetSocketAddressAttribute = mock(Attribute.class);
        when(inetSocketAddressAttribute.get()).thenReturn(remoteAddress);
        when(mockChannel.attr(REMOTE_SOCKET)).thenReturn(inetSocketAddressAttribute);

        // and - netty http client
        HttpRequest requestBeingForwarded = request("request_one").withHeader(mockHttpStateHandler.getUniqueLoopPreventionHeaderName(), mockHttpStateHandler.getUniqueLoopPreventionHeaderValue());
        when(mockNettyHttpClient.sendRequest(requestBeingForwarded, remoteAddress, ConfigurationProperties.socketConnectionTimeout())).thenReturn(responseFuture);

        // when
        actionHandler.processAction(request("request_one"), mockResponseWriter, mockChannelHandlerContext, new HashSet<>(), true, true);

        // then
        verify(mockNettyHttpClient).sendRequest(requestBeingForwarded, remoteAddress, ConfigurationProperties.socketConnectionTimeout());
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request)
                .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
                .setArguments(request)
        );
        HttpResponse loggedResponse = response.clone().withHeader("x-mockserver-response-time-ms", "0");
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setLogLevel(INFO)
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request)
                .setHttpResponse(loggedResponse)
                .setExpectation(request, loggedResponse)
                .setMessageFormat("returning response:{}for forwarded request" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}")
                .setArguments(loggedResponse, request, httpRequestToCurlSerializer.toCurl(request, remoteAddress))
        );
    }

    @Test
    public void shouldReturn501ForGrpcBidiResponseInWarDeployment() {
        // given — a GRPC_BIDI_RESPONSE expectation dispatched with ctx==null (WAR/servlet)
        GrpcBidiResponse grpcBidiResponse = GrpcBidiResponse.grpcBidiResponse()
            .withStatusName("OK")
            .withMessage("{\"greeting\": \"Hello\"}");
        expectation = new Expectation(request).thenRespondWithGrpcBidi(grpcBidiResponse);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);

        // when — ctx is null (WAR deployment)
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then — should respond with 501
        verify(mockResponseWriter).writeResponse(
            eq(request),
            argThat(resp -> resp.getStatusCode() == 501
                && resp.getBodyAsString().contains("gRPC bidi streaming is not supported in WAR deployments")),
            eq(false)
        );
    }

    @Test
    public void shouldAnnounceResponsesWrittenStraightToTheChannel() {
        Expectation[] directlyWritten = {
            new Expectation(request).thenRespondWithSse(HttpSseResponse.sseResponse().withEvent(SseEvent.sseEvent().withData("event"))),
            new Expectation(request).thenRespondWithLlm(HttpLlmResponse.llmResponse().withProvider(Provider.OPENAI)
                .withCompletion(Completion.completion().withText("streamed").withStreaming(true))),
            new Expectation(request).thenRespondWithWebSocket(HttpWebSocketResponse.webSocketResponse()),
            new Expectation(request).thenRespondWithGrpcStream(GrpcStreamResponse.grpcStreamResponse().withStatusName("OK"))
        };
        for (Expectation directExpectation : directlyWritten) {
            // given
            when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(directExpectation);
            ResponseWriter responseWriter = mock(ResponseWriter.class);
            ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

            // when
            actionHandler.processAction(request, responseWriter, ctx, new HashSet<>(), false, true);

            // then - announced, so the writer can see the response end on a connection that stays open
            verify(responseWriter, description(directExpectation.getAction().getType() + " is announced")).respondingDirectly();
        }
    }

    @Test
    public void shouldRefuseWebSocketResponseOverHttp3With501() {
        // given
        HttpRequest http3Request = request("/ws").withProtocol(org.mockserver.model.Protocol.HTTP_3);
        when(mockHttpStateHandler.firstMatchingExpectation(http3Request))
            .thenReturn(new Expectation(http3Request).thenRespondWithWebSocket(HttpWebSocketResponse.webSocketResponse()));
        ResponseWriter responseWriter = mock(ResponseWriter.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        // when
        actionHandler.processAction(http3Request, responseWriter, ctx, new HashSet<>(), false, true);

        // then - answered through the writer, never handed the channel for an upgrade
        verify(responseWriter).writeResponse(
            eq(http3Request),
            argThat(resp -> resp.getStatusCode() == 501
                && resp.getBodyAsString().contains("WebSocket mocking is not supported over HTTP/3")),
            eq(false)
        );
        verify(responseWriter, never()).respondingDirectly();
        verifyNoInteractions(ctx);
    }

    @Test
    public void shouldNotAnnounceResponsesWrittenThroughTheResponseWriter() {
        // given
        ResponseWriter responseWriter = mock(ResponseWriter.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        // when
        actionHandler.processAction(request, responseWriter, ctx, new HashSet<>(), false, true);

        // then
        verify(responseWriter).writeResponse(request, this.response, false);
        verify(responseWriter, never()).respondingDirectly();
    }

    @Test
    public void shouldReturn501ForGrpcBidiResponseWhenFlagOff() {
        // given — a GRPC_BIDI_RESPONSE expectation dispatched with a non-null ctx
        // but reaching HttpActionHandler (flag off or non-multiplex transport)
        GrpcBidiResponse grpcBidiResponse = GrpcBidiResponse.grpcBidiResponse()
            .withStatusName("OK")
            .withMessage("{\"greeting\": \"Hello\"}");
        expectation = new Expectation(request).thenRespondWithGrpcBidi(grpcBidiResponse);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);

        ChannelHandlerContext mockCtx = mock(ChannelHandlerContext.class);

        // when — ctx is non-null but reaching HttpActionHandler (flag off)
        actionHandler.processAction(request, mockResponseWriter, mockCtx, new HashSet<>(), false, true);

        // then — should respond with 501 (requires multiplex pipeline)
        verify(mockResponseWriter).writeResponse(
            eq(request),
            argThat(resp -> resp.getStatusCode() == 501
                && resp.getBodyAsString().contains("gRPC bidi streaming requires the multiplex pipeline")),
            eq(false)
        );
    }

    @Test
    public void shouldAnswer501WhenAnHttpRequestMatchesABinaryOrDnsAction() {
        Expectation[] notHttp = {
            new Expectation(request).thenRespondWithBinary(org.mockserver.model.BinaryResponse.binaryResponse("binary".getBytes(java.nio.charset.StandardCharsets.UTF_8))),
            new Expectation(request).thenRespondWithDns(org.mockserver.model.DnsResponse.dnsResponse())
        };
        for (Expectation notHttpExpectation : notHttp) {
            // given
            when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(notHttpExpectation);
            ResponseWriter responseWriter = mock(ResponseWriter.class);
            String type = notHttpExpectation.getAction().getType().name();

            // when
            actionHandler.processAction(request, responseWriter, mock(ChannelHandlerContext.class), new HashSet<>(), false, true);

            // then - answered, so the exchange ends and a request pipelined behind it is not held
            verify(responseWriter, description(type + " is answered with 501")).writeResponse(
                eq(request),
                argThat(resp -> resp.getStatusCode() == 501
                    && resp.getBodyAsString().equals("the matched expectation's " + type + " action cannot answer an HTTP request")),
                eq(false)
            );
        }
    }

    // ----------------------------------------------------------------------------------------------------
    // FILE response body (issue #2450): a FileBody produced by a callback / template / forward-override must
    // be MATERIALISED (the file READ, its CONTENTS placed on the wire) by the write-path funnels, not emitted
    // as the filePath string. These tests exercise the real funnels (writeResponseActionResponse /
    // writeForwardActionResponse) with the produced response carrying a FileBody.
    // ----------------------------------------------------------------------------------------------------

    private static final String VERBATIM_XML = "org/mockserver/mock/action/verbatim_file_body.xml";
    private static final String VERBATIM_PNG = "org/mockserver/mock/action/verbatim_binary_body.png";
    private static final String MUSTACHE_JSON = "org/mockserver/templates/sample_mustache_body.json";
    private static final byte[] PNG_BYTES = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, (byte) 0xFF, (byte) 0xFE, 0x01, (byte) 0x80, 0x7F, (byte) 0xC3, 0x28};

    private HttpResponse captureWrittenResponse() {
        ArgumentCaptor<HttpResponse> captor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), captor.capture(), eq(false));
        return captor.getValue();
    }

    @Test
    public void shouldMaterialiseVerbatimFileBodyFromResponseTemplateAction() {
        // given - a RESPONSE_TEMPLATE action whose handler returns a response with a no-templateType FileBody
        HttpResponse fileBodyResponse = response().withBody(new FileBody(VERBATIM_XML, MediaType.parse("application/xml"))).withDelay(milliseconds(0));
        when(mockHttpResponseTemplateActionHandler.handle(any(HttpTemplate.class), any(HttpRequest.class))).thenReturn(fileBodyResponse);
        HttpTemplate template = template(HttpTemplate.TemplateType.JAVASCRIPT, "some_template").withDelay(milliseconds(0));
        expectation = new Expectation(request).thenRespond(template);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the FILE CONTENTS are served (as a StringBody), not the path string, and untemplated
        HttpResponse written = captureWrittenResponse();
        assertThat(written.getBody(), is(instanceOf(StringBody.class)));
        assertThat(written.getBodyAsString(), is("<tag>hello{{ request.path }}</tag>"));
        assertThat(written.getBodyAsString(), not(containsString(VERBATIM_XML)));
    }

    @Test
    public void shouldMaterialiseBinaryFileBodyFromResponseClassCallbackAction() {
        // given - a RESPONSE_CLASS_CALLBACK action whose handler returns a response with a binary FileBody
        HttpResponse fileBodyResponse = response().withBody(new FileBody(VERBATIM_PNG, MediaType.parse("image/png"))).withDelay(milliseconds(0));
        when(mockHttpResponseClassCallbackActionHandler.handle(any(HttpClassCallback.class), any(HttpRequest.class))).thenReturn(fileBodyResponse);
        HttpClassCallback callback = callback("some_class").withDelay(milliseconds(0));
        expectation = new Expectation(request).thenRespond(callback);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the raw file bytes are served intact (BinaryBody), byte-for-byte identical
        HttpResponse written = captureWrittenResponse();
        assertThat(written.getBody(), is(instanceOf(BinaryBody.class)));
        assertArrayEquals(PNG_BYTES, written.getBody().getRawBytes());
    }

    @Test
    public void shouldRenderMustacheTemplatedFileBodyFromResponseClassCallbackAction() {
        // given - a RESPONSE_CLASS_CALLBACK action whose handler returns a MUSTACHE-templated FileBody; the
        // file must be rendered against the incoming request, not served literally
        HttpResponse fileBodyResponse = response().withBody(new FileBody(MUSTACHE_JSON, MediaType.APPLICATION_JSON, HttpTemplate.TemplateType.MUSTACHE)).withDelay(milliseconds(0));
        when(mockHttpResponseClassCallbackActionHandler.handle(any(HttpClassCallback.class), any(HttpRequest.class))).thenReturn(fileBodyResponse);
        HttpClassCallback callback = callback("some_class").withDelay(milliseconds(0));
        expectation = new Expectation(request).thenRespond(callback);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - placeholders resolved from the request; no raw Mustache placeholder survives
        HttpResponse written = captureWrittenResponse();
        assertThat(written.getBody(), is(instanceOf(StringBody.class)));
        assertThat(written.getBodyAsString(), containsString("\"path\": \"some_path\""));
        assertThat(written.getBodyAsString(), not(containsString("{{")));
    }

    @Test
    public void shouldMaterialiseVerbatimFileBodyFromForwardOverride() {
        // given - a FORWARD_REPLACE (responseOverride) whose forwarded result resolves to a FileBody response
        HttpResponse fileBodyResponse = response().withBody(new FileBody(VERBATIM_XML, MediaType.parse("application/xml")));
        CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        future.complete(fileBodyResponse);
        HttpForwardActionResult fileForwardResult = new HttpForwardActionResult(forwardedHttpRequest, future, null, new InetSocketAddress(1234));
        when(mockHttpOverrideForwardedRequestActionHandler.handle(any(HttpOverrideForwardedRequest.class), any(HttpRequest.class))).thenReturn(fileForwardResult);
        HttpOverrideForwardedRequest override = new HttpOverrideForwardedRequest().withResponseOverride(fileBodyResponse);
        expectation = new Expectation(request).thenForward(override);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the FILE CONTENTS reach the wire, not the path string
        HttpResponse written = captureWrittenResponse();
        assertThat(written.getBody(), is(instanceOf(StringBody.class)));
        assertThat(written.getBodyAsString(), is("<tag>hello{{ request.path }}</tag>"));
    }

    @Test
    public void shouldMaterialiseVerbatimFileBodyOnObjectCallbackFunnel() {
        // given - the object-callback handler funnels its callback response through writeResponseActionResponse
        // (see HttpResponseObjectCallbackActionHandler); invoke that funnel directly with a FileBody response
        HttpResponse fileBodyResponse = response().withBody(new FileBody(VERBATIM_XML, MediaType.parse("application/xml")));
        HttpObjectCallback objectCallback = new HttpObjectCallback().withClientId("some_request_client_id");

        // when
        actionHandler.writeResponseActionResponse(fileBodyResponse, mockResponseWriter, request, objectCallback, true, null, null);

        // then - the FILE CONTENTS are served, not the path string
        HttpResponse written = captureWrittenResponse();
        assertThat(written.getBody(), is(instanceOf(StringBody.class)));
        assertThat(written.getBodyAsString(), is("<tag>hello{{ request.path }}</tag>"));
    }

    @Test
    public void shouldNotDoubleProcessAnAlreadyMaterialisedResponseBody() {
        // given - a plain (non-FileBody) response, as produced by the static path after HttpResponseActionHandler
        // has already materialised any FileBody; the funnel must leave it byte-for-byte untouched (no re-read)
        StringBody alreadyMaterialised = new StringBody("<tag>hello/somePath</tag>", MediaType.parse("application/xml"));
        HttpResponse response = response().withBody(alreadyMaterialised);
        HttpObjectCallback action = new HttpObjectCallback().withClientId("client");

        // when
        actionHandler.writeResponseActionResponse(response, mockResponseWriter, request, action, true, null, null);

        // then - identical body instance, unchanged
        HttpResponse written = captureWrittenResponse();
        assertThat(written.getBody(), is(instanceOf(StringBody.class)));
        assertThat(written.getBodyAsString(), is("<tag>hello/somePath</tag>"));
    }

    @Test
    public void shouldReturnCleanLoggedFiveHundredWhenResponseFileMissing() {
        // given - a callback returns a FileBody pointing at a nonexistent file
        String missingPath = "org/mockserver/mock/action/does_not_exist_" + System.nanoTime() + ".xml";
        HttpResponse fileBodyResponse = response().withBody(new FileBody(missingPath, MediaType.parse("application/xml"))).withDelay(milliseconds(0));
        when(mockHttpResponseClassCallbackActionHandler.handle(any(HttpClassCallback.class), any(HttpRequest.class))).thenReturn(fileBodyResponse);
        HttpClassCallback callback = callback("some_class").withDelay(milliseconds(0));
        expectation = new Expectation(request).thenRespond(callback);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - a single clean 500 is written (connection not left half-written), with a SAFE body that does
        // NOT leak the file path, and is not a FileBody
        HttpResponse written = captureWrittenResponse();
        assertThat(written.getStatusCode(), is(500));
        assertThat(written.getBody(), is(not(instanceOf(FileBody.class))));
        assertThat(written.getBodyAsString(), not(containsString(missingPath)));
        assertThat(written.getBodyAsString(), not(containsString("does_not_exist")));
    }

    @Test
    public void shouldReturnCleanLoggedFiveHundredWhenStaticResponseFileMissing() {
        // given - the static RESPONSE path materialises inside HttpResponseActionHandler.handle, which throws
        // FileBodyException for a missing file (before the write funnel); handleAnyException must turn that
        // into the same clean, logged 500 the funnel paths produce, not a misleading 404
        String missingPath = "org/mockserver/mock/action/does_not_exist_" + System.nanoTime() + ".xml";
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class)))
            .thenThrow(new org.mockserver.file.FileBodyException(missingPath, new RuntimeException("boom")));
        HttpResponse responseAction = response().withBody(new FileBody(missingPath, MediaType.parse("application/xml"))).withDelay(milliseconds(0));
        expectation = new Expectation(request).thenRespond(responseAction);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - a single clean 500, safe body, no path leak (not a 404)
        HttpResponse written = captureWrittenResponse();
        assertThat(written.getStatusCode(), is(500));
        assertThat(written.getBodyAsString(), not(containsString(missingPath)));
    }
}

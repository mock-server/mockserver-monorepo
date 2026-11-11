package org.mockserver.mock.action.http;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.configuration.Configuration;
import org.mockserver.extension.DataPlaneHooks;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.crud.CrudDispatcher;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.RequestDefinition;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.uuid.UUIDService;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * With proxy credentials configured, a request whose request-target is not origin-form and whose Host is another host is
 * refused with 407 before the request filter and matching; an origin-form one is refused only on the unmatched proxy path.
 */
public class HttpActionHandlerProxyAuthenticationTest {

    private static final Set<String> LOCAL_ADDRESSES = Set.of("127.0.0.1:1080", "localhost:1080");

    private Configuration configuration;
    private Scheduler scheduler;
    private HttpState httpState;
    private ResponseWriter responseWriter;
    private HttpActionHandler actionHandler;

    @Before
    public void setUp() {
        configuration = configuration().proxyAuthenticationUsername("user").proxyAuthenticationPassword("password");
        MockServerLogger mockServerLogger = new MockServerLogger(HttpActionHandlerProxyAuthenticationTest.class);
        scheduler = new Scheduler(configuration, mockServerLogger);
        httpState = mock(HttpState.class);
        when(httpState.getScheduler()).thenReturn(scheduler);
        when(httpState.getMockServerLogger()).thenReturn(mockServerLogger);
        when(httpState.getUniqueLoopPreventionHeaderName()).thenReturn("x-forwarded-by");
        when(httpState.getUniqueLoopPreventionHeaderValue()).thenReturn("MockServer_" + UUIDService.getUUID());
        when(httpState.getCrudDispatcher()).thenReturn(new CrudDispatcher());
        responseWriter = mock(ResponseWriter.class);
        actionHandler = new HttpActionHandler(configuration, null, httpState, null, null);
    }

    @After
    public void tearDown() {
        scheduler.shutdown();
    }

    private HttpResponse writtenResponse() {
        ArgumentCaptor<HttpResponse> response = ArgumentCaptor.forClass(HttpResponse.class);
        verify(responseWriter).writeResponse(any(HttpRequest.class), response.capture(), anyBoolean());
        return response.getValue();
    }

    @Test
    public void shouldRefuseAnAbsoluteFormRequestForAnotherHostBeforeMatching() {
        HttpRequest request = request("/path").withHeader("Host", "upstream:80").withAbsoluteFormRequestTarget(true);

        actionHandler.processAction(request, responseWriter, null, LOCAL_ADDRESSES, false, true);

        HttpResponse response = writtenResponse();
        assertThat(response.getStatusCode(), is(407));
        assertThat(response.getFirstHeader("Proxy-Authenticate"), startsWith("Basic realm="));
        verify(httpState, never()).firstMatchingExpectation(any(RequestDefinition.class));
    }

    @Test
    public void shouldRefuseAnAbsoluteFormRequestForAnotherHostBeforeTheRequestFilter() {
        DataPlaneHooks hooks = mock(DataPlaneHooks.class);
        when(httpState.dataPlaneHooks()).thenReturn(hooks);
        HttpActionHandler withHooks = new HttpActionHandler(configuration, null, httpState, null, null);

        withHooks.processAction(request("/path").withHeader("Host", "upstream:80").withAbsoluteFormRequestTarget(true), responseWriter, null, LOCAL_ADDRESSES, false, true);

        assertThat(writtenResponse().getStatusCode(), is(407));
        verifyNoInteractions(hooks);

        // the control: with credentials the request reaches the filter stage
        withHooks.processAction(request("/path").withHeader("Host", "upstream:80").withAbsoluteFormRequestTarget(true)
            .withHeader("Proxy-Authorization", "Basic " + Base64.getEncoder().encodeToString("user:password".getBytes(StandardCharsets.UTF_8))), mock(ResponseWriter.class), null, LOCAL_ADDRESSES, false, true);
        verify(hooks).hasRequestFilters();
    }

    @Test
    public void shouldRefuseAnUnmatchedOriginFormRequestForAnotherHostOnlyWhenItWouldBeForwarded() {
        HttpRequest request = request("/path").withHeader("Host", "upstream:80");

        actionHandler.processAction(request, responseWriter, null, LOCAL_ADDRESSES, false, true);

        verify(httpState).firstMatchingExpectation(eq(request));
        assertThat(writtenResponse().getStatusCode(), is(407));
    }
}

package org.mockserver.mock.action.http;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.crud.CrudActionHandler;
import org.mockserver.mock.crud.CrudDataStore;
import org.mockserver.model.CrudExpectationsDefinition.IdStrategy;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.servlet.responsewriter.ServletResponseWriter;
import org.slf4j.event.Level;

import java.io.PrintWriter;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.testing.AllocationMeasurement.allocatedBytes;
import static org.mockserver.testing.AllocationMeasurement.assumeC1Only;
import static org.mockserver.testing.AllocationMeasurement.assumeHttpRequestIsNotInstrumented;

/**
 * Pins what the data plane costs today with no data-plane hook loaded, so a hook added later must leave it exactly
 * as it is: the bytes {@code processAction} allocates per request on four paths, each with a new real writer as a
 * transport makes one, and the bytes and fields of each core writer. The pins are equalities measured with C1 only,
 * since C2 escape analysis could hide an added allocation; a deliberate change must update them with its reason.
 */
public class DataPlaneHooksAbsentCostTest {

    static final long MATCHED_RESPONSE_BYTES_PER_REQUEST = 1624;
    static final long NOT_FOUND_BYTES_PER_REQUEST = 4312;
    static final long CRUD_HIT_BYTES_PER_REQUEST = 2136;
    static final long UNSERVED_CONTROL_PLANE_PATH_BYTES_PER_REQUEST = 1624;
    static final long IN_PROCESS_WRITER_BYTES = 72;
    static final long SERVLET_WRITER_BYTES = 128;

    private static final int OPERATIONS = 50_000;
    private static final int WRITERS = 200_000;
    // the least of several measurements: background work can only add bytes, never remove the code's own
    private static final int ATTEMPTS = 3;
    private static final Set<String> LOCAL_ADDRESSES = Set.of("localhost", "127.0.0.1");
    private static final Class<?> IN_PROCESS_WRITER = inProcessWriterClass();
    private static final MethodHandle NEW_IN_PROCESS_WRITER = inProcessWriterConstructor();
    private static volatile Object sink;

    // a ring that holds a whole attempt's entries, with no byte bound, drained before each attempt: an entry the
    // event log drops skips allocations it would otherwise make, so a drop would make the bytes vary
    private final Configuration configuration = configuration().logLevel(Level.WARN).ringBufferSize(1 << 17).maxEventLogSizeInBytes(0L);
    private final MockServerLogger mockServerLogger = new MockServerLogger(configuration, DataPlaneHooksAbsentCostTest.class);
    private final List<HttpState> states = new ArrayList<>();

    @After
    public void stopStates() {
        states.forEach(HttpState::stop);
    }

    @Test
    public void shouldPinTheCostOfAMatchedResponse() {
        assumeC1OnlyForTheDataPlaneCost();
        HttpState httpState = httpState();
        httpState.add(new Expectation(request("/matched")).thenRespond(response("matched")));
        assertBytesPerRequest(httpState, request("/matched").withMethod("GET"), 200, MATCHED_RESPONSE_BYTES_PER_REQUEST);
    }

    @Test
    public void shouldPinTheCostOfANotFound() {
        assumeC1OnlyForTheDataPlaneCost();
        HttpState httpState = httpState();
        httpState.add(new Expectation(request("/matched")).thenRespond(response("matched")));
        assertBytesPerRequest(httpState, request("/unmatched").withMethod("GET"), 404, NOT_FOUND_BYTES_PER_REQUEST);
    }

    @Test
    public void shouldPinTheCostOfACrudHit() {
        assumeC1OnlyForTheDataPlaneCost();
        HttpState httpState = httpState();
        ObjectNode item = ObjectMapperFactory.createObjectMapper().createObjectNode().put("id", 1).put("name", "one");
        httpState.getCrudDispatcher().register("/api/items", new CrudActionHandler(new CrudDataStore("id", IdStrategy.AUTO_INCREMENT, List.of(item)), "/api/items"));
        assertBytesPerRequest(httpState, request("/api/items/1").withMethod("GET"), 200, CRUD_HIT_BYTES_PER_REQUEST);
    }

    @Test
    public void shouldPinTheCostOfAnUnservedControlPlanePathServedByACatchAll() {
        assumeC1OnlyForTheDataPlaneCost();
        HttpState httpState = httpState();
        httpState.add(new Expectation(request()).thenRespond(response("caught")));
        assertBytesPerRequest(httpState, request("/mockserver/x").withMethod("GET"), 200, UNSERVED_CONTROL_PLANE_PATH_BYTES_PER_REQUEST);
    }

    @Test
    public void shouldPinTheBytesOfAnInProcessWriter() {
        assertBytesPerWriter(this::newInProcessWriter, IN_PROCESS_WRITER_BYTES);
    }

    @Test
    public void shouldPinTheBytesOfAServletWriter() {
        HttpServletResponse servletResponse = new StubHttpServletResponse();
        assertBytesPerWriter(() -> new ServletResponseWriter(configuration, mockServerLogger, servletResponse), SERVLET_WRITER_BYTES);
    }

    @Test
    public void shouldPinTheFieldsOfEachCoreWriter() {
        List<String> base = List.of(
            "ResponseWriter.configuration:Configuration",
            "ResponseWriter.mockServerLogger:MockServerLogger",
            "ResponseWriter.corsHeaders:CORSHeaders",
            "ResponseWriter.defaultResponseHeaders:DefaultResponseHeaders"
        );
        assertThat(instanceFields(IN_PROCESS_WRITER), is(with(base, "InProcessResponseWriter.response:CompletableFuture")));
        assertThat(instanceFields(ServletResponseWriter.class), is(with(base,
            "ServletResponseWriter.httpServletResponse:HttpServletResponse",
            "ServletResponseWriter.mockServerResponseToHttpServletResponseEncoder:MockServerHttpResponseToHttpServletResponseEncoder"
        )));
    }

    private void assertBytesPerRequest(HttpState httpState, HttpRequest request, int expectedStatus, long pinned) {
        HttpActionHandler actionHandler = new HttpActionHandler(configuration, null, httpState, null, null);
        ResponseWriter writer = newInProcessWriter();
        actionHandler.processAction(request, writer, null, LOCAL_ADDRESSES, false, true);
        assertThat(responseOf(writer).getStatusCode(), is(expectedStatus));
        processActions(actionHandler, request);
        long least = Long.MAX_VALUE;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            awaitEventLogDrained(httpState);
            long dropped = httpState.getMockServerLog().getDroppedLogEventCount();
            long before = allocatedBytes();
            processActions(actionHandler, request);
            least = Math.min(least, (allocatedBytes() - before) / OPERATIONS);
            assertThat("log entries dropped while measuring", httpState.getMockServerLog().getDroppedLogEventCount() - dropped, is(0L));
        }
        assumeHttpRequestIsNotInstrumented();
        assertThat("bytes allocated per " + request.getMethod("") + " " + request.getPath() + " with no data-plane hook loaded", least, is(pinned));
    }

    private void processActions(HttpActionHandler actionHandler, HttpRequest request) {
        for (int i = 0; i < OPERATIONS; i++) {
            ResponseWriter writer = newInProcessWriter();
            actionHandler.processAction(request, writer, null, LOCAL_ADDRESSES, false, true);
            sink = writer;
        }
    }

    // before the state is built, so a run with C2 builds no 2^17-slot event log
    private static void assumeC1OnlyForTheDataPlaneCost() {
        assumeC1Only("the data plane's cost with no data-plane hook loaded");
    }

    private static void awaitEventLogDrained(HttpState httpState) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (httpState.getMockServerLog().getRingBufferOccupancy() > 0) {
            assertThat("the event log drained", System.currentTimeMillis() < deadline, is(true));
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private void assertBytesPerWriter(Supplier<ResponseWriter> newWriter, long pinned) {
        assumeC1Only("a writer's bytes");
        assertThat("bytes allocated per writer", leastBytesPerWriter(newWriter), is(pinned));
    }

    private static long leastBytesPerWriter(Supplier<ResponseWriter> newWriter) {
        newWriters(newWriter);
        long least = Long.MAX_VALUE;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            long before = allocatedBytes();
            newWriters(newWriter);
            least = Math.min(least, (allocatedBytes() - before) / WRITERS);
        }
        return least;
    }

    private static void newWriters(Supplier<ResponseWriter> newWriter) {
        for (int i = 0; i < WRITERS; i++) {
            sink = newWriter.get();
        }
    }

    private HttpState httpState() {
        HttpState httpState = new HttpState(configuration, mockServerLogger, new Scheduler(configuration, mockServerLogger, true));
        states.add(httpState);
        return httpState;
    }

    private ResponseWriter newInProcessWriter() {
        try {
            return (ResponseWriter) NEW_IN_PROCESS_WRITER.invokeExact(configuration, mockServerLogger);
        } catch (Throwable throwable) {
            throw new AssertionError(throwable);
        }
    }

    private static Class<?> inProcessWriterClass() {
        try {
            return Class.forName("org.mockserver.inprocess.InProcessMockServer$InProcessResponseWriter");
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static MethodHandle inProcessWriterConstructor() {
        try {
            Constructor<?> constructor = IN_PROCESS_WRITER.getDeclaredConstructor(Configuration.class, MockServerLogger.class);
            constructor.setAccessible(true);
            MethodHandle handle = MethodHandles.lookup().unreflectConstructor(constructor);
            return handle.asType(MethodType.methodType(ResponseWriter.class, Configuration.class, MockServerLogger.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse responseOf(ResponseWriter inProcessWriter) {
        try {
            Field response = IN_PROCESS_WRITER.getDeclaredField("response");
            response.setAccessible(true);
            return ((CompletableFuture<HttpResponse>) response.get(inProcessWriter)).getNow(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Every instance field from {@code ResponseWriter} down, as {@code DeclaringClass.name:Type}: an added field
     * changes this even when it fits in a writer's alignment padding, where the bytes per writer cannot see it.
     */
    static List<String> instanceFields(Class<?> writerType) {
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> type = writerType; type != null && type != Object.class; type = type.getSuperclass()) {
            hierarchy.add(0, type);
        }
        List<String> fields = new ArrayList<>();
        for (Class<?> type : hierarchy) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                    fields.add(type.getSimpleName() + "." + field.getName() + ":" + field.getType().getSimpleName());
                }
            }
        }
        return fields;
    }

    static List<String> with(List<String> base, String... more) {
        List<String> all = new ArrayList<>(base);
        Collections.addAll(all, more);
        return all;
    }

    /**
     * A servlet response that does nothing: the writer only holds it, and a Mockito mock would add its own
     * invocation recording to what is measured.
     */
    private static final class StubHttpServletResponse implements HttpServletResponse {
        // @formatter:off
        @Override public void addCookie(Cookie cookie) { }
        @Override public boolean containsHeader(String name) { return false; }
        @Override public String encodeURL(String url) { return url; }
        @Override public String encodeRedirectURL(String url) { return url; }
        @Override public void sendError(int sc, String msg) { }
        @Override public void sendError(int sc) { }
        @Override public void sendRedirect(String location, int sc, boolean clearBuffer) { }
        @Override public void setDateHeader(String name, long date) { }
        @Override public void addDateHeader(String name, long date) { }
        @Override public void setHeader(String name, String value) { }
        @Override public void addHeader(String name, String value) { }
        @Override public void setIntHeader(String name, int value) { }
        @Override public void addIntHeader(String name, int value) { }
        @Override public void setStatus(int sc) { }
        @Override public int getStatus() { return 200; }
        @Override public String getHeader(String name) { return null; }
        @Override public Collection<String> getHeaders(String name) { return Collections.emptyList(); }
        @Override public Collection<String> getHeaderNames() { return Collections.emptyList(); }
        @Override public String getCharacterEncoding() { return "UTF-8"; }
        @Override public String getContentType() { return null; }
        @Override public ServletOutputStream getOutputStream() { throw new UnsupportedOperationException(); }
        @Override public PrintWriter getWriter() { throw new UnsupportedOperationException(); }
        @Override public void setCharacterEncoding(String charset) { }
        @Override public void setContentLength(int len) { }
        @Override public void setContentLengthLong(long len) { }
        @Override public void setContentType(String type) { }
        @Override public void setBufferSize(int size) { }
        @Override public int getBufferSize() { return 0; }
        @Override public void flushBuffer() { }
        @Override public void resetBuffer() { }
        @Override public boolean isCommitted() { return false; }
        @Override public void reset() { }
        @Override public void setLocale(Locale loc) { }
        @Override public Locale getLocale() { return Locale.ROOT; }
        // @formatter:on
    }
}

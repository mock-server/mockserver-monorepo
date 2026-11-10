package org.mockserver.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.*;
import java.util.concurrent.TimeUnit;

public class HttpSseResponse extends Action<HttpSseResponse> {
    private int hashCode;
    private Integer statusCode;
    private Headers headers;
    private List<SseEvent> events;
    private Boolean closeConnection;
    private Delay closeDelay;
    private Boolean keepStreamOpen;
    private HttpTemplate.TemplateType templateType;

    public static HttpSseResponse sseResponse() {
        return new HttpSseResponse();
    }

    public HttpSseResponse withStatusCode(Integer statusCode) {
        this.statusCode = statusCode;
        this.hashCode = 0;
        return this;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public HttpSseResponse withHeaders(Headers headers) {
        this.headers = headers;
        this.hashCode = 0;
        return this;
    }

    public HttpSseResponse withHeader(Header header) {
        if (this.headers == null) {
            this.headers = new Headers();
        }
        this.headers.withEntry(header);
        this.hashCode = 0;
        return this;
    }

    public HttpSseResponse withHeader(String name, String... values) {
        if (this.headers == null) {
            this.headers = new Headers();
        }
        this.headers.withEntry(name, values);
        this.hashCode = 0;
        return this;
    }

    public Headers getHeaders() {
        return headers;
    }

    public HttpSseResponse withEvents(List<SseEvent> events) {
        this.events = events;
        this.hashCode = 0;
        return this;
    }

    public HttpSseResponse withEvents(SseEvent... events) {
        this.events = Arrays.asList(events);
        this.hashCode = 0;
        return this;
    }

    public HttpSseResponse withEvent(SseEvent event) {
        if (this.events == null) {
            this.events = new ArrayList<>();
        }
        this.events.add(event);
        this.hashCode = 0;
        return this;
    }

    public List<SseEvent> getEvents() {
        return events;
    }

    public HttpSseResponse withCloseConnection(Boolean closeConnection) {
        this.closeConnection = closeConnection;
        this.hashCode = 0;
        return this;
    }

    public Boolean getCloseConnection() {
        return closeConnection;
    }

    /**
     * How long to wait after the last event (or after the response head, when there are no events) before ending
     * the stream. Each event's {@link SseEvent#getDelay()} is its own wait, so this is the gap at the end of the
     * stream: a recorded stream that stayed open after its last event replays that gap. When {@code null} (the
     * default) the stream ends as soon as the last event is written.
     */
    public HttpSseResponse withCloseDelay(Delay closeDelay) {
        this.closeDelay = closeDelay;
        this.hashCode = 0;
        return this;
    }

    public HttpSseResponse withCloseDelay(TimeUnit timeUnit, long value) {
        return withCloseDelay(new Delay(timeUnit, value));
    }

    public Delay getCloseDelay() {
        return closeDelay;
    }

    /**
     * When {@code true} the stream never ends after its last event: it stays open until the client leaves or
     * MockServer stops, as a backend that keeps a stream open indefinitely would. {@link #getCloseDelay()} is then
     * ignored.
     */
    public HttpSseResponse withKeepStreamOpen(Boolean keepStreamOpen) {
        this.keepStreamOpen = keepStreamOpen;
        this.hashCode = 0;
        return this;
    }

    public Boolean getKeepStreamOpen() {
        return keepStreamOpen;
    }

    /**
     * Opt-in response templating: when set (to {@link HttpTemplate.TemplateType#VELOCITY},
     * {@link HttpTemplate.TemplateType#MUSTACHE} or {@link HttpTemplate.TemplateType#JAVASCRIPT}),
     * each event's {@code data} payload is rendered as a response template against the triggering
     * request (so {@code $!request.body}, {@code $jsonPath(...)}, the built-in helpers, the
     * {@code faker} helper and the {@code scenario} helper are all available) rather than emitted
     * verbatim. The template is rendered once per event, immediately before the event is written.
     * <p>
     * When {@code null} (the default) every event is emitted byte-for-byte unchanged, exactly as
     * before this field existed.
     */
    public HttpSseResponse withTemplateType(HttpTemplate.TemplateType templateType) {
        this.templateType = templateType;
        this.hashCode = 0;
        return this;
    }

    public HttpTemplate.TemplateType getTemplateType() {
        return templateType;
    }

    @Override
    @JsonIgnore
    public Type getType() {
        return Type.SSE_RESPONSE;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (hashCode() != o.hashCode()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        HttpSseResponse that = (HttpSseResponse) o;
        return Objects.equals(statusCode, that.statusCode) &&
            Objects.equals(headers, that.headers) &&
            Objects.equals(events, that.events) &&
            Objects.equals(closeConnection, that.closeConnection) &&
            Objects.equals(closeDelay, that.closeDelay) &&
            Objects.equals(keepStreamOpen, that.keepStreamOpen) &&
            templateType == that.templateType;
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(super.hashCode(), statusCode, headers, events, closeConnection, closeDelay, keepStreamOpen, templateType);
        }
        return hashCode;
    }
}

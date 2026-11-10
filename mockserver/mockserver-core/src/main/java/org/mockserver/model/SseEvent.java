package org.mockserver.model;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class SseEvent extends ObjectWithJsonToString {
    private int hashCode;
    private String event;
    private String data;
    private String id;
    private Integer retry;
    private String comment;
    private Delay delay;

    public static SseEvent sseEvent() {
        return new SseEvent();
    }

    public SseEvent withEvent(String event) {
        this.event = event;
        this.hashCode = 0;
        return this;
    }

    public String getEvent() {
        return event;
    }

    public SseEvent withData(String data) {
        this.data = data;
        this.hashCode = 0;
        return this;
    }

    public String getData() {
        return data;
    }

    public SseEvent withId(String id) {
        this.id = id;
        this.hashCode = 0;
        return this;
    }

    public String getId() {
        return id;
    }

    public SseEvent withRetry(Integer retry) {
        this.retry = retry;
        this.hashCode = 0;
        return this;
    }

    public Integer getRetry() {
        return retry;
    }

    /**
     * A comment written before the event's other fields: {@code :} followed by each line of the comment exactly as
     * written, so {@code "hb"} sends {@code :hb} and {@code " hb"} sends {@code : hb}. Clients ignore comments, so an
     * event with only a comment sends a heartbeat that dispatches nothing.
     */
    public SseEvent withComment(String comment) {
        this.comment = comment;
        this.hashCode = 0;
        return this;
    }

    public String getComment() {
        return comment;
    }

    public SseEvent withDelay(Delay delay) {
        this.delay = delay;
        this.hashCode = 0;
        return this;
    }

    public SseEvent withDelay(TimeUnit timeUnit, long value) {
        this.delay = new Delay(timeUnit, value);
        this.hashCode = 0;
        return this;
    }

    public Delay getDelay() {
        return delay;
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
        SseEvent sseEvent = (SseEvent) o;
        return Objects.equals(event, sseEvent.event) &&
            Objects.equals(data, sseEvent.data) &&
            Objects.equals(id, sseEvent.id) &&
            Objects.equals(retry, sseEvent.retry) &&
            Objects.equals(comment, sseEvent.comment) &&
            Objects.equals(delay, sseEvent.delay);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(event, data, id, retry, comment, delay);
        }
        return hashCode;
    }
}

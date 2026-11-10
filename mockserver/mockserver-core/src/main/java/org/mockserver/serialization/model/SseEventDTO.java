package org.mockserver.serialization.model;

import org.mockserver.model.SseEvent;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

public class SseEventDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<SseEvent> {
    private String event;
    private String data;
    private String id;
    private Integer retry;
    private String comment;
    private DelayDTO delay;

    public SseEventDTO(SseEvent sseEvent) {
        if (sseEvent != null) {
            event = sseEvent.getEvent();
            data = sseEvent.getData();
            id = sseEvent.getId();
            retry = sseEvent.getRetry();
            comment = sseEvent.getComment();
            if (sseEvent.getDelay() != null) {
                delay = new DelayDTO(sseEvent.getDelay());
            }
        }
    }

    public SseEventDTO() {
    }

    public SseEvent buildObject() {
        SseEvent sseEvent = new SseEvent();
        sseEvent.withEvent(event);
        sseEvent.withData(data);
        sseEvent.withId(id);
        sseEvent.withRetry(retry);
        sseEvent.withComment(comment);
        if (delay != null) {
            sseEvent.withDelay(delay.buildObject());
        }
        return sseEvent;
    }

    public String getEvent() {
        return event;
    }

    public SseEventDTO setEvent(String event) {
        this.event = event;
        return this;
    }

    public String getData() {
        return data;
    }

    public SseEventDTO setData(String data) {
        this.data = data;
        return this;
    }

    public String getId() {
        return id;
    }

    public SseEventDTO setId(String id) {
        this.id = id;
        return this;
    }

    public Integer getRetry() {
        return retry;
    }

    public SseEventDTO setRetry(Integer retry) {
        this.retry = retry;
        return this;
    }

    public String getComment() {
        return comment;
    }

    public SseEventDTO setComment(String comment) {
        this.comment = comment;
        return this;
    }

    public DelayDTO getDelay() {
        return delay;
    }

    public SseEventDTO setDelay(DelayDTO delay) {
        this.delay = delay;
        return this;
    }
}

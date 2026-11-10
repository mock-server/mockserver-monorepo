package org.mockserver.serialization.java;

import org.mockserver.model.HttpSseResponse;
import org.mockserver.model.SseEvent;

public class HttpSseResponseToJavaSerializer implements ToJavaSerializer<HttpSseResponse> {

    @Override
    public String serialize(int numberOfSpacesToIndent, HttpSseResponse sseResponse) {
        if (sseResponse == null) {
            return "";
        }
        return new FluentJavaBuilder(numberOfSpacesToIndent, "HttpSseResponse.sseResponse()")
            .with("withStatusCode", sseResponse.getStatusCode())
            .withHeaders(sseResponse.getHeaders())
            .withEach("withEvents", sseResponse.getEvents(), HttpSseResponseToJavaSerializer::serializeEvent)
            .with("withCloseConnection", sseResponse.getCloseConnection())
            .withDelay("withCloseDelay", sseResponse.getCloseDelay())
            .with("withKeepStreamOpen", sseResponse.getKeepStreamOpen())
            .with("withTemplateType", sseResponse.getTemplateType())
            .withDelay("withDelay", sseResponse.getDelay())
            .build();
    }

    private static String serializeEvent(int numberOfSpacesToIndent, SseEvent event) {
        return new FluentJavaBuilder(numberOfSpacesToIndent, "SseEvent.sseEvent()")
            .with("withEvent", event.getEvent())
            .with("withData", event.getData())
            .with("withId", event.getId())
            .with("withRetry", event.getRetry())
            .with("withComment", event.getComment())
            .withDelay("withDelay", event.getDelay())
            .build();
    }
}

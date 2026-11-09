package org.mockserver.model;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.testing.AllocationMeasurement.allocatedBytes;
import static org.mockserver.testing.AllocationMeasurement.assumeC1Only;

/**
 * Every request the data plane receives, clones or copies is an {@link HttpRequest}, so a field added to it costs
 * every request, whether or not the feature it serves is configured. This pins its fields and the bytes a new one
 * allocates; state only a few requests need belongs on a subclass or elsewhere, as for
 * {@link CredentialedForwardRequest}. A deliberate change must update the pins with its reason.
 */
public class HttpRequestFootprintCostTest {

    // the request and the two empty NottableStrings its method and path start as, measured with C1 only
    static final long NEW_REQUEST_BYTES = 232;

    private static final int REQUESTS = 200_000;
    // the least of several measurements: background work can only add bytes, never remove the code's own
    private static final int ATTEMPTS = 3;
    private static volatile Object sink;

    @Test
    public void shouldPinTheFieldsOfARequest() {
        assertThat(instanceFields(HttpRequest.class), is(List.of(
            "Not.hashCode:int",
            "Not.not:Boolean",
            "RequestDefinition.logCorrelationId:String",
            "RequestDefinition.receivedTimestamp:long",
            "HttpRequest.hashCode:int",
            "HttpRequest.method:NottableString",
            "HttpRequest.path:NottableString",
            "HttpRequest.pathParameters:Parameters",
            "HttpRequest.queryStringParameters:Parameters",
            "HttpRequest.body:Body",
            "HttpRequest.originalBody:byte[]",
            "HttpRequest.headers:Headers",
            "HttpRequest.cookies:Cookies",
            "HttpRequest.keepAlive:Boolean",
            "HttpRequest.secure:Boolean",
            "HttpRequest.respondBeforeBody:Boolean",
            "HttpRequest.protocol:Protocol",
            "HttpRequest.streamId:Integer",
            "HttpRequest.clientCertificateChain:List",
            "HttpRequest.clientCertificate:ClientCertificate",
            "HttpRequest.jwt:Jwt",
            "HttpRequest.clientIp:NottableString",
            "HttpRequest.socketAddress:SocketAddress",
            "HttpRequest.localAddress:String",
            "HttpRequest.remoteAddress:Object",
            "HttpRequest.convertedBodyCache:Map",
            "HttpRequest.parsedBodyCache:Object",
            "HttpRequest.pathGroups:List",
            "HttpRequest.namedPathGroups:Map",
            "HttpRequest.loadGenerated:boolean",
            "HttpRequest.controlPlaneRequest:boolean",
            "HttpRequest.maskedCredentialValues:List",
            "HttpRequest.bodyAsReceived:Body",
            "HttpRequest.contentEncodingAsReceived:List",
            "HttpRequest.undecodableQueryString:String"
        )));
    }

    @Test
    public void shouldPinTheBytesOfANewRequest() {
        assumeC1Only("a new request's bytes");
        newRequests();
        long least = Long.MAX_VALUE;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            long before = allocatedBytes();
            newRequests();
            least = Math.min(least, (allocatedBytes() - before) / REQUESTS);
        }
        assertThat("bytes allocated per new request", least, is(NEW_REQUEST_BYTES));
    }

    private static void newRequests() {
        for (int i = 0; i < REQUESTS; i++) {
            sink = new HttpRequest();
        }
    }

    /**
     * Every instance field from {@link Not} down, as {@code DeclaringClass.name:Type}: an added field changes this
     * even when it fits in the alignment padding, where the bytes per request cannot see it.
     */
    private static List<String> instanceFields(Class<?> requestType) {
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> type = requestType; type != ObjectWithJsonToString.class; type = type.getSuperclass()) {
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
}

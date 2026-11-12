package org.mockserver.grpc;

import com.google.protobuf.Descriptors;
import com.google.protobuf.ListValue;
import com.google.protobuf.TypeRegistry;
import com.google.protobuf.Value;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

/**
 * The JSON form of a gRPC response body round-trips with the number of messages intact, including
 * none (issue #2988).
 */
public class GrpcResponseBodyTest {

    private static final String SERVICE = "com.example.grpc.GreetingService";

    private GrpcJsonMessageConverter converter;
    private Descriptors.Descriptor helloResponse;

    @Before
    public void setUp() {
        GrpcProtoDescriptorStore store = new GrpcProtoDescriptorStore(new MockServerLogger());
        store.loadDescriptorSetFromPath(Paths.get("src/test/resources/grpc/greeting.dsc"));
        converter = store.getConverter();
        helloResponse = store.getMethod(SERVICE, "ListGreetings").getOutputType();
    }

    @Test
    public void shouldWriteNoMessagesAsEmptyJsonArray() {
        assertThat(GrpcResponseBody.toJson(Collections.emptyList(), helloResponse, converter), is("[]"));
    }

    @Test
    public void shouldWriteOneMessageAsJsonObject() {
        String json = GrpcResponseBody.toJson(Collections.singletonList(protobuf("one")), helloResponse, converter);

        assertThat(json.trim().charAt(0), is('{'));
        assertThat(json, containsString("one"));
    }

    @Test
    public void shouldEncodeEmptyJsonArrayAsNoFrames() {
        assertThat(GrpcResponseBody.toFrames("[]", helloResponse, converter).length, is(0));
        assertThat(GrpcResponseBody.toFrames(" \n[ ]", helloResponse, converter).length, is(0));
    }

    @Test
    public void shouldEncodeJsonObjectAsOneFrame() {
        List<byte[]> messages = GrpcFrameCodec.decode(
            GrpcResponseBody.toFrames("{\"greeting\":\"one\"}", helloResponse, converter));

        assertThat(messages, hasSize(1));
        assertThat(converter.toJson(messages.get(0), helloResponse), containsString("one"));
    }

    @Test
    public void shouldEncodeEmptyJsonObjectAsOneZeroLengthFrame() {
        byte[] frames = GrpcResponseBody.toFrames("{}", helloResponse, converter);

        assertThat(GrpcFrameCodec.decode(frames), hasSize(1));
        assertThat(frames.length, is(5));
    }

    @Test
    public void shouldRoundTripEveryMessageCount() {
        for (int count = 0; count <= 3; count++) {
            List<byte[]> messages = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                messages.add(protobuf("greeting " + i));
            }

            String json = GrpcResponseBody.toJson(messages, helloResponse, converter);
            List<byte[]> decoded = GrpcFrameCodec.decode(GrpcResponseBody.toFrames(json, helloResponse, converter));

            assertThat("message count after round trip of " + count, decoded, hasSize(count));
            for (int i = 0; i < count; i++) {
                assertThat(converter.toJson(decoded.get(i), helloResponse), containsString("greeting " + i));
            }
        }
    }

    @Test
    public void shouldEncodeJsonArrayAsOneMessageForListValueOutput() {
        Descriptors.Descriptor listValue = ListValue.getDescriptor();
        GrpcJsonMessageConverter wellKnownConverter = new GrpcJsonMessageConverter(TypeRegistry.getEmptyTypeRegistry());

        List<byte[]> messages = GrpcFrameCodec.decode(GrpcResponseBody.toFrames("[1, \"two\"]", listValue, wellKnownConverter));

        assertThat(messages, hasSize(1));
        assertThat(wellKnownConverter.toJson(messages.get(0), listValue), containsString("two"));
    }

    @Test
    public void shouldEncodeJsonArrayAsOneMessageForValueOutput() {
        Descriptors.Descriptor value = Value.getDescriptor();
        GrpcJsonMessageConverter wellKnownConverter = new GrpcJsonMessageConverter(TypeRegistry.getEmptyTypeRegistry());

        assertThat(GrpcFrameCodec.decode(GrpcResponseBody.toFrames("[]", value, wellKnownConverter)), hasSize(1));
        assertThat(GrpcFrameCodec.decode(GrpcResponseBody.toFrames("[1, 2]", value, wellKnownConverter)), hasSize(1));
    }

    private byte[] protobuf(String greeting) {
        return converter.toProtobuf("{\"greeting\":\"" + greeting + "\"}", helloResponse);
    }
}

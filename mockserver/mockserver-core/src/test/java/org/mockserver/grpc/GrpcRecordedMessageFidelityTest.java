package org.mockserver.grpc;

import com.google.protobuf.AnyProto;
import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.StructProto;
import com.google.protobuf.Timestamp;
import com.google.protobuf.TimestampProto;
import com.google.protobuf.Value;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * A message recorded from an upstream as JSON, and framed back to protobuf when it is relayed or
 * replayed, is the message the upstream sent: every field kind (scalars incl. 64-bit and bytes,
 * repeated, map, nested, oneof, enum, and the well-known Struct, Value, ListValue, Timestamp and
 * Any) survives, for zero, one and several messages, in both directions.
 */
public class GrpcRecordedMessageFidelityTest {

    private static final String SERVICE = "fidelity.RichService";

    private GrpcProtoDescriptorStore store;
    private Descriptors.Descriptor rich;
    private Descriptors.Descriptor nested;

    @Before
    public void setUp() {
        store = new GrpcProtoDescriptorStore(new MockServerLogger());
        store.loadDescriptorSet(descriptorSet().toByteArray());
        rich = store.getMethod(SERVICE, "Get").getInputType();
        nested = rich.findNestedTypeByName("Nested");
    }

    @Test
    public void shouldRelayAUnaryResponseWithEveryFieldKindUnchanged() {
        DynamicMessage message = richMessage("one", 1);

        assertThat(relayed("Get", Collections.singletonList(message)), is(Collections.singletonList(message)));
    }

    @Test
    public void shouldRelayServerStreamsOfNoneOneAndManyRichMessagesUnchanged() {
        assertThat(relayed("List", Collections.emptyList()), is(Collections.emptyList()));
        List<DynamicMessage> one = Collections.singletonList(richMessage("one", 1));
        assertThat(relayed("List", one), is(one));
        List<DynamicMessage> many = Arrays.asList(richMessage("one", 1), richMessage("two", 2), richMessage("three", 3));
        assertThat(relayed("List", many), is(many));
    }

    @Test
    public void shouldRelayAStreamOfOneValueMessageUnchanged() {
        Value value = Value.newBuilder().setListValue(ListValue.newBuilder()
            .addValues(Value.newBuilder().setNumberValue(1))
            .addValues(Value.newBuilder().setStringValue("two"))).build();
        Descriptors.Descriptor valueType = store.getMethod(SERVICE, "ListValues").getOutputType();
        DynamicMessage message = dynamicMessageOf(valueType, value.toByteArray());

        assertThat(relayed("ListValues", Collections.singletonList(message), valueType), is(Collections.singletonList(message)));
    }

    @Test
    public void shouldForwardClientStreamsOfNoneOneAndManyRichMessagesUnchanged() throws Exception {
        assertThat(forwarded("Collect", Collections.emptyList()), is(Collections.emptyList()));
        List<DynamicMessage> one = Collections.singletonList(richMessage("one", 1));
        assertThat(forwarded("Collect", one), is(one));
        List<DynamicMessage> many = Arrays.asList(richMessage("one", 1), richMessage("two", 2));
        assertThat(forwarded("Collect", many), is(many));
    }

    /**
     * Decodes the upstream frames as the forward path records them, then frames that JSON back as
     * relaying and replaying do, and parses what the client would receive.
     */
    private List<DynamicMessage> relayed(String method, List<DynamicMessage> upstreamMessages) {
        return relayed(method, upstreamMessages, rich);
    }

    private List<DynamicMessage> relayed(String method, List<DynamicMessage> upstreamMessages, Descriptors.Descriptor outputType) {
        HttpResponse upstream = response()
            .withHeader("content-type", "application/grpc")
            .withHeader("grpc-status", "0")
            .withBody(frames(upstreamMessages));
        HttpResponse recorded = GrpcForwardTranslator.decodeResponseFromUpstream(upstream, SERVICE, method, store);
        byte[] relayed = GrpcResponseBody.toFrames(recorded.getBodyAsString(), outputType, store.getConverter());
        return parse(relayed, outputType);
    }

    private List<DynamicMessage> forwarded(String method, List<DynamicMessage> clientMessages) {
        List<String> json = new ArrayList<>();
        for (DynamicMessage message : clientMessages) {
            json.add(store.getConverter().toJson(message.toByteArray(), rich));
        }
        // as GrpcToHttpRequestHandler decodes them: no body for no messages, an object for one
        String body = json.isEmpty() ? "" : json.size() == 1 ? json.get(0) : "[" + String.join(",", json) + "]";
        HttpRequest decoded = request("/" + SERVICE + "/" + method)
            .withHeader("content-type", "application/grpc")
            .withHeader(GrpcForwardTranslator.SERVICE_HEADER, SERVICE)
            .withHeader(GrpcForwardTranslator.METHOD_HEADER, method)
            .withHeader("te", "trailers")
            .withBody(body);
        HttpRequest upstreamRequest = GrpcForwardTranslator.encodeRequestForUpstream(decoded, store);
        assertThat(upstreamRequest.getHeader("te"), contains("trailers"));
        return parse(upstreamRequest.getBodyAsRawBytes(), rich);
    }

    private List<DynamicMessage> parse(byte[] frames, Descriptors.Descriptor type) {
        List<DynamicMessage> messages = new ArrayList<>();
        for (byte[] message : GrpcFrameCodec.decode(frames)) {
            messages.add(dynamicMessageOf(type, message));
        }
        return messages;
    }

    private DynamicMessage asField(String fieldName, com.google.protobuf.Message generated) {
        return dynamicMessageOf(rich.findFieldByName(fieldName).getMessageType(), generated.toByteArray());
    }

    private static DynamicMessage dynamicMessageOf(Descriptors.Descriptor type, byte[] bytes) {
        try {
            return DynamicMessage.parseFrom(type, bytes);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] frames(List<DynamicMessage> messages) {
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        for (DynamicMessage message : messages) {
            frames.writeBytes(GrpcFrameCodec.encode(message.toByteArray()));
        }
        return frames.toByteArray();
    }

    private DynamicMessage richMessage(String text, int index) {
        DynamicMessage nestedMessage = DynamicMessage.newBuilder(nested)
            .setField(nested.findFieldByName("label"), "nested " + text)
            .build();
        Descriptors.FieldDescriptor counts = rich.findFieldByName("counts");
        Descriptors.Descriptor countsEntry = counts.getMessageType();
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(rich)
            .setField(rich.findFieldByName("s"), text + " é ☃")
            .setField(rich.findFieldByName("big"), Long.MAX_VALUE - index)
            .setField(rich.findFieldByName("data"), ByteString.copyFrom(new byte[]{0, 1, (byte) 0xFF, (byte) index}))
            .addRepeatedField(rich.findFieldByName("tags"), "a" + index)
            .addRepeatedField(rich.findFieldByName("tags"), "b" + index)
            .addRepeatedField(counts, DynamicMessage.newBuilder(countsEntry)
                .setField(countsEntry.findFieldByName("key"), "k" + index)
                .setField(countsEntry.findFieldByName("value"), index)
                .build())
            .setField(rich.findFieldByName("nested"), nestedMessage)
            .setField(rich.findFieldByName("kind"), rich.findEnumTypeByName("Kind").findValueByNumber(2))
            .setField(rich.findFieldByName("ratio"), 0.25 * index)
            .setField(rich.findFieldByName("struct"), asField("struct", Struct.newBuilder()
                .putFields("flag", Value.newBuilder().setBoolValue(true).build())
                .putFields("none", Value.newBuilder().setNullValueValue(0).build())
                .build()))
            .setField(rich.findFieldByName("value"), asField("value", Value.newBuilder().setStringValue("v" + index).build()))
            .setField(rich.findFieldByName("list"), asField("list", ListValue.newBuilder()
                .addValues(Value.newBuilder().setNumberValue(index))
                .addValues(Value.newBuilder().setStringValue("x"))
                .build()))
            .setField(rich.findFieldByName("ts"), asField("ts", Timestamp.newBuilder().setSeconds(1_700_000_000L + index).setNanos(5_000).build()))
            .setField(rich.findFieldByName("any"), asField("any", com.google.protobuf.Any.newBuilder()
                .setTypeUrl("type.googleapis.com/" + nested.getFullName())
                .setValue(nestedMessage.toByteString())
                .build()));
        if (index % 2 == 0) {
            builder.setField(rich.findFieldByName("number"), index);
        } else {
            builder.setField(rich.findFieldByName("text"), "chosen " + text);
        }
        return builder.build();
    }

    private static DescriptorProtos.FileDescriptorSet descriptorSet() {
        DescriptorProto nestedType = DescriptorProto.newBuilder()
            .setName("Nested")
            .addField(field("label", 1, FieldDescriptorProto.Type.TYPE_STRING))
            .build();
        DescriptorProto countsEntry = DescriptorProto.newBuilder()
            .setName("CountsEntry")
            .addField(field("key", 1, FieldDescriptorProto.Type.TYPE_STRING))
            .addField(field("value", 2, FieldDescriptorProto.Type.TYPE_INT32))
            .setOptions(DescriptorProtos.MessageOptions.newBuilder().setMapEntry(true))
            .build();
        DescriptorProto richType = DescriptorProto.newBuilder()
            .setName("Rich")
            .addNestedType(nestedType)
            .addNestedType(countsEntry)
            .addEnumType(DescriptorProtos.EnumDescriptorProto.newBuilder()
                .setName("Kind")
                .addValue(DescriptorProtos.EnumValueDescriptorProto.newBuilder().setName("KIND_UNSPECIFIED").setNumber(0))
                .addValue(DescriptorProtos.EnumValueDescriptorProto.newBuilder().setName("KIND_ONE").setNumber(1))
                .addValue(DescriptorProtos.EnumValueDescriptorProto.newBuilder().setName("KIND_TWO").setNumber(2)))
            .addOneofDecl(DescriptorProtos.OneofDescriptorProto.newBuilder().setName("choice"))
            .addField(field("s", 1, FieldDescriptorProto.Type.TYPE_STRING))
            .addField(field("big", 2, FieldDescriptorProto.Type.TYPE_INT64))
            .addField(field("data", 3, FieldDescriptorProto.Type.TYPE_BYTES))
            .addField(field("tags", 4, FieldDescriptorProto.Type.TYPE_STRING).toBuilder().setLabel(FieldDescriptorProto.Label.LABEL_REPEATED))
            .addField(message("counts", 5, ".fidelity.Rich.CountsEntry").toBuilder().setLabel(FieldDescriptorProto.Label.LABEL_REPEATED))
            .addField(message("nested", 6, ".fidelity.Rich.Nested"))
            .addField(field("text", 7, FieldDescriptorProto.Type.TYPE_STRING).toBuilder().setOneofIndex(0))
            .addField(field("number", 8, FieldDescriptorProto.Type.TYPE_INT32).toBuilder().setOneofIndex(0))
            .addField(FieldDescriptorProto.newBuilder().setName("kind").setNumber(9)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setType(FieldDescriptorProto.Type.TYPE_ENUM).setTypeName(".fidelity.Rich.Kind"))
            .addField(message("struct", 10, ".google.protobuf.Struct"))
            .addField(message("value", 11, ".google.protobuf.Value"))
            .addField(message("list", 12, ".google.protobuf.ListValue"))
            .addField(message("ts", 13, ".google.protobuf.Timestamp"))
            .addField(message("any", 14, ".google.protobuf.Any"))
            .addField(field("ratio", 15, FieldDescriptorProto.Type.TYPE_DOUBLE))
            .build();
        DescriptorProtos.FileDescriptorProto file = DescriptorProtos.FileDescriptorProto.newBuilder()
            .setName("fidelity.proto")
            .setPackage("fidelity")
            .setSyntax("proto3")
            .addDependency("google/protobuf/struct.proto")
            .addDependency("google/protobuf/timestamp.proto")
            .addDependency("google/protobuf/any.proto")
            .addMessageType(richType)
            .addService(DescriptorProtos.ServiceDescriptorProto.newBuilder()
                .setName("RichService")
                .addMethod(method("Get", ".fidelity.Rich", false, false))
                .addMethod(method("List", ".fidelity.Rich", false, true))
                .addMethod(method("Collect", ".fidelity.Rich", true, false))
                .addMethod(DescriptorProtos.MethodDescriptorProto.newBuilder()
                    .setName("ListValues").setInputType(".fidelity.Rich").setOutputType(".google.protobuf.Value")
                    .setServerStreaming(true)))
            .build();
        return DescriptorProtos.FileDescriptorSet.newBuilder()
            .addFile(StructProto.getDescriptor().toProto())
            .addFile(TimestampProto.getDescriptor().toProto())
            .addFile(AnyProto.getDescriptor().toProto())
            .addFile(file)
            .build();
    }

    private static FieldDescriptorProto field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
            .setName(name)
            .setNumber(number)
            .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
            .setType(type)
            .build();
    }

    private static FieldDescriptorProto message(String name, int number, String typeName) {
        return FieldDescriptorProto.newBuilder()
            .setName(name)
            .setNumber(number)
            .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
            .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
            .setTypeName(typeName)
            .build();
    }

    private static DescriptorProtos.MethodDescriptorProto.Builder method(String name, String type, boolean clientStreaming, boolean serverStreaming) {
        return DescriptorProtos.MethodDescriptorProto.newBuilder()
            .setName(name)
            .setInputType(type)
            .setOutputType(type)
            .setClientStreaming(clientStreaming)
            .setServerStreaming(serverStreaming);
    }
}

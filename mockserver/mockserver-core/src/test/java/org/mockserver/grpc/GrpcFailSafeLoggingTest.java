package org.mockserver.grpc;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.model.HttpResponse;
import org.mockito.ArgumentCaptor;
import org.slf4j.event.Level;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpResponse.response;

/**
 * The three gRPC fail-safe catches — decode fallback, descriptor-directory load, and proto
 * compilation — each swallow an exception and log a WARN so the operator can diagnose it. This
 * pins that they are actually diagnosable AT global log level WARN, which is the level an operator
 * running in production is most likely to use.
 *
 * <p>The bug this guards against: {@code setType(WARN)} alone does not raise a {@link LogEntry}'s
 * level — {@code logLevel} defaults to INFO and {@link MockServerLogger} gates delivery on the
 * level, not the type — so without an explicit {@code setLogLevel(WARN)} the entry is filtered out
 * at global WARN and the fail-safe is silent. Each test configures the logger at WARN and asserts
 * the entry is both delivered and carries WARN; before the fix the delivery gate drops it and the
 * assertion fails.</p>
 */
public class GrpcFailSafeLoggingTest {

    /**
     * A logger configured at global level WARN, routing every delivered entry to a captured
     * HttpState mock. Returns the mock so the caller can capture and assert the entry.
     */
    private static HttpState warnLevelLoggerSink(MockServerLogger[] loggerOut) {
        HttpState sink = mock(HttpState.class);
        Configuration configuration = configuration().logLevel(Level.WARN).disableLogging(false);
        loggerOut[0] = new MockServerLogger(configuration, sink);
        return sink;
    }

    private static LogEntry captureSingleDeliveredEntry(HttpState sink) {
        ArgumentCaptor<LogEntry> captor = ArgumentCaptor.forClass(LogEntry.class);
        verify(sink).log(captor.capture());
        return captor.getValue();
    }

    @Test
    public void decodeFallbackIsDiagnosableAtWarn() {
        MockServerLogger[] loggerHolder = new MockServerLogger[1];
        HttpState sink = warnLevelLoggerSink(loggerHolder);
        GrpcProtoDescriptorStore store = new GrpcProtoDescriptorStore(loggerHolder[0]);
        store.loadDescriptorSetFromPath(Paths.get("src/test/resources/grpc/greeting.dsc"));

        // a well-formed gRPC frame wrapping bytes that are NOT valid protobuf for the output type
        // (0x08 is a field-1 varint tag with no value — a truncated message), so decode throws
        // into the fail-safe catch.
        byte[] frame = GrpcFrameCodec.encode(new byte[]{0x08});
        HttpResponse upstream = response()
            .withHeader(GrpcStatusMapper.GRPC_STATUS_HEADER, "0")
            .withBody(frame);

        HttpResponse decoded = GrpcForwardTranslator.decodeResponseFromUpstream(
            upstream, "com.example.grpc.GreetingService", "Greeting", store);

        // contract 1: the response is passed through unchanged (the fail-safe returns the original)
        assertThat("the fail-safe must return the upstream response unchanged",
            decoded, is(sameInstance(upstream)));

        // contract 2: the failure is not silent at global WARN
        LogEntry entry = captureSingleDeliveredEntry(sink);
        assertThat(entry.getLogLevel(), is(Level.WARN));
        assertThat(entry.getMessageFormat(), containsString("failed to decode upstream gRPC response"));
    }

    @Test
    public void descriptorDirectoryLoadFailureIsDiagnosableAtWarn() throws Exception {
        MockServerLogger[] loggerHolder = new MockServerLogger[1];
        HttpState sink = warnLevelLoggerSink(loggerHolder);
        GrpcProtoDescriptorStore store = new GrpcProtoDescriptorStore(loggerHolder[0]);

        Path dir = Files.createTempDirectory("mockserver-grpc-baddsc-");
        Path badDescriptor = dir.resolve("broken.dsc");
        Files.write(badDescriptor, "this is not a FileDescriptorSet".getBytes());

        store.loadDescriptorDirectory(dir);

        LogEntry entry = captureSingleDeliveredEntry(sink);
        assertThat(entry.getLogLevel(), is(Level.WARN));
        assertThat(entry.getMessageFormat(), containsString("failed to load gRPC descriptor"));

        Files.deleteIfExists(badDescriptor);
        Files.deleteIfExists(dir);
    }

    @Test
    public void protoCompileFailureIsDiagnosableAtWarn() throws Exception {
        MockServerLogger[] loggerHolder = new MockServerLogger[1];
        HttpState sink = warnLevelLoggerSink(loggerHolder);

        // point the compiler at a protoc that does not exist so ProcessBuilder.start() fails and the
        // per-file catch logs — no protoc install required, and the failure is deterministic.
        GrpcProtoFileCompiler compiler = new GrpcProtoFileCompiler(loggerHolder[0], "/nonexistent/mockserver-protoc");
        GrpcProtoDescriptorStore store = new GrpcProtoDescriptorStore(loggerHolder[0]);

        Path dir = Files.createTempDirectory("mockserver-grpc-proto-");
        Path protoFile = dir.resolve("service.proto");
        Files.write(protoFile, "syntax = \"proto3\";\nmessage M { string s = 1; }\n".getBytes());

        compiler.compileDirectory(dir, store);

        LogEntry entry = captureSingleDeliveredEntry(sink);
        assertThat(entry.getLogLevel(), is(Level.WARN));
        assertThat(entry.getMessageFormat(), containsString("failed to compile proto file"));

        Files.deleteIfExists(protoFile);
        Files.deleteIfExists(dir);
    }

    /**
     * Issue #2988: a {@code ListValue} response is itself a JSON array, so a stream of a different
     * number of messages cannot keep its count; that must be visible at WARN rather than silent.
     */
    @Test
    public void unpreservedMessageCountIsDiagnosableAtWarn() {
        MockServerLogger[] loggerHolder = new MockServerLogger[1];
        HttpState sink = warnLevelLoggerSink(loggerHolder);
        GrpcProtoDescriptorStore store = new GrpcProtoDescriptorStore(loggerHolder[0]);
        com.google.protobuf.DescriptorProtos.FileDescriptorProto lists = com.google.protobuf.DescriptorProtos.FileDescriptorProto.newBuilder()
            .setName("lists.proto")
            .setPackage("example.lists")
            .setSyntax("proto3")
            .addDependency("google/protobuf/struct.proto")
            .addService(com.google.protobuf.DescriptorProtos.ServiceDescriptorProto.newBuilder()
                .setName("Lists")
                .addMethod(com.google.protobuf.DescriptorProtos.MethodDescriptorProto.newBuilder()
                    .setName("Stream")
                    .setInputType(".google.protobuf.ListValue")
                    .setOutputType(".google.protobuf.ListValue")
                    .setServerStreaming(true)))
            .build();
        store.loadDescriptorSet(com.google.protobuf.DescriptorProtos.FileDescriptorSet.newBuilder()
            .addFile(com.google.protobuf.StructProto.getDescriptor().toProto())
            .addFile(lists)
            .build()
            .toByteArray());

        HttpResponse decoded = GrpcForwardTranslator.decodeResponseFromUpstream(
            response().withHeader(GrpcStatusMapper.GRPC_STATUS_HEADER, "0"), "example.lists.Lists", "Stream", store);

        assertThat(decoded.getBodyAsString(), is("[]"));
        LogEntry entry = captureSingleDeliveredEntry(sink);
        assertThat(entry.getLogLevel(), is(Level.WARN));
        assertThat(entry.getMessage(), containsString("google.protobuf.ListValue"));
    }
}

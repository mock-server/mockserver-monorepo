package org.mockserver.test;

import org.junit.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.mockserver.test.ClassLoadingProbe.extensionOrDataPlaneHookClasses;
import static org.mockserver.test.ClassLoadingProbe.isExtensionOrDataPlaneHookClass;

public class ClassLoadingProbeTest {

    @Test
    public void shouldMatchEveryExtensionAndDataPlaneHookClassInAnyPackage() {
        for (String className : List.of(
            "org.mockserver.extension.Extension",
            "org.mockserver.extension.DataPlaneHooks",
            "org.mockserver.extension.RequestFilter$FilterResult",
            "org.mockserver.mock.DataPlaneHooks",
            "org.mockserver.extension.DataPlaneCall",
            "org.mockserver.netty.responsewriter.DataPlaneWriters",
            "org.mockserver.responsewriter.CoreDataPlaneWriters",
            "org.mockserver.responsewriter.TransformingWriter",
            "org.mockserver.netty.responsewriter.TransformingNettyResponseWriter",
            "org.mockserver.netty.http3.TransformingHttp3ResponseWriter",
            "org.mockserver.netty.http3.TransformingHttp3GrpcResponseWriter",
            "org.mockserver.servlet.responsewriter.TransformingServletResponseWriter",
            "org.mockserver.inprocess.InProcessMockServer$TransformingInProcessResponseWriter",
            "org.mockserver.inprocess.InProcessMockServer$TransformingWriterState",
            "org.mockserver.netty.responsewriter.TransformingNettyResponseWriter$1",
            "org.mockserver.lifecycle.ExtensionClassLoadingTest$Probe$TransformingProbeResponseWriter",
            "com.example.DataPlaneWriters"
        )) {
            assertThat(className, isExtensionOrDataPlaneHookClass(className), is(true));
        }
    }

    @Test
    public void shouldNotMatchTheClassesTheUnloadedPathUses() {
        for (String className : List.of(
            "org.mockserver.netty.responsewriter.NettyResponseWriter",
            "org.mockserver.netty.http3.Http3ResponseWriter",
            "org.mockserver.netty.http3.Http3GrpcResponseWriter",
            "org.mockserver.servlet.responsewriter.ServletResponseWriter",
            "org.mockserver.inprocess.InProcessMockServer$InProcessResponseWriter",
            "org.mockserver.authentication.DataPlaneAuthenticationGate",
            "org.mockserver.mock.HttpState",
            "org.mockserver.extensions.Something",
            "org.mockserver.templates.TransformingTemplate",
            "org.mockserver.mock.DataPlaneHooksAbsentCostTest"
        )) {
            assertThat(className, isExtensionOrDataPlaneHookClass(className), is(false));
        }
    }

    @Test
    public void shouldKeepOnlyTheMatchingClassesInLoadOrder() {
        assertThat(extensionOrDataPlaneHookClasses(List.of(
            "org.mockserver.netty.MockServer",
            "org.mockserver.netty.responsewriter.DataPlaneWriters",
            "org.mockserver.mock.HttpState",
            "org.mockserver.extension.ExtensionRegistry"
        )), contains("org.mockserver.netty.responsewriter.DataPlaneWriters", "org.mockserver.extension.ExtensionRegistry"));
    }
}

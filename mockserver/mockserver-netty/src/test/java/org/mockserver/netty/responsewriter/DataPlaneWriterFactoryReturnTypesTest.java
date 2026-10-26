package org.mockserver.netty.responsewriter;

import org.junit.Test;
import org.mockserver.servlet.responsewriter.CoreDataPlaneWriters;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

/**
 * Every factory that builds a writer for a loaded data-plane hook returns the call site's base writer type, never a
 * {@code Transforming*} one: a handler method that calls it then verifies without loading the transforming classes,
 * which {@code ExtensionClassLoadingTest} relies on for a server with no hook loaded.
 */
public class DataPlaneWriterFactoryReturnTypesTest {

    @Test
    public void shouldReturnOnlyBaseWriterTypes() throws Exception {
        List<Method> factories = new ArrayList<>();
        for (Class<?> factoryClass : new Class<?>[]{
            DataPlaneWriters.class,
            CoreDataPlaneWriters.class,
            Class.forName("org.mockserver.inprocess.InProcessMockServer$TransformingInProcessResponseWriter")
        }) {
            for (Method method : factoryClass.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) && !method.isSynthetic()) {
                    factories.add(method);
                }
            }
        }

        List<String> transformingReturns = new ArrayList<>();
        for (Method factory : factories) {
            if (factory.getReturnType().getSimpleName().startsWith("Transforming")) {
                transformingReturns.add(factory.toString());
            }
        }
        assertThat("netty, http3, http3Grpc, servlet and the in-process create", factories.size(), is(5));
        assertThat(transformingReturns, is(empty()));
    }
}

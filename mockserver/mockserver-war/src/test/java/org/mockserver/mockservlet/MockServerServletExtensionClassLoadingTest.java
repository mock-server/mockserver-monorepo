package org.mockserver.mockservlet;

import org.junit.Test;
import org.mockserver.extension.Extension;
import org.mockserver.test.ClassLoadingProbe;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.test.ClassLoadingProbe.extensionOrDataPlaneHookClasses;

/**
 * The WAR's twin of mockserver-netty's {@code ExtensionClassLoadingTest}: with no extension configured the servlet
 * loads no extension class and no class only a loaded data-plane hook may need, while it serves a control-plane
 * request, a matched response, a 404 and a control-plane-classified path no route serves, and verifies and resets.
 * Runs the servlet in a fresh JVM that logs every class it loads; a second run with an extension configured shows
 * the log does see them.
 */
public class MockServerServletExtensionClassLoadingTest {

    @Test
    public void shouldLoadNoExtensionClassWhenNoExtensionIsConfigured() throws Exception {
        ClassLoadingProbe.Run run = ClassLoadingProbe.run(Probe.class);

        assertThat(run.output, run.completed, is(true));
        assertThat("the probe must have built a servlet writer", run.loadedClasses, hasItem("org.mockserver.servlet.responsewriter.ServletResponseWriter"));
        assertThat(extensionOrDataPlaneHookClasses(run.loadedClasses), is(empty()));
    }

    @Test
    public void shouldSeeTheExtensionClassesLoadWhenAnExtensionIsConfigured() throws Exception {
        ClassLoadingProbe.Run run = ClassLoadingProbe.run(Probe.class, Probe.ConfiguredExtension.class.getName());

        assertThat(run.output, run.completed, is(true));
        assertThat(extensionOrDataPlaneHookClasses(run.loadedClasses), hasItem("org.mockserver.extension.ExtensionRegistry"));
    }

    public static class Probe {

        public static class ConfiguredExtension implements Extension {
            @Override
            public String name() {
                return "servlet-class-loading-probe";
            }
        }

        public static void main(String[] arguments) {
            try {
                MockServerServlet servlet = new MockServerServlet(arguments.length > 0 ? configuration().extensions(arguments[0]) : configuration());
                try {
                    serve(servlet, "PUT", "/mockserver/expectation", "{\"httpRequest\": {\"path\": \"/servlet\"}, \"httpResponse\": {\"body\": \"ok\"}}", 201);
                    serve(servlet, "GET", "/servlet", null, 200);
                    serve(servlet, "GET", "/servlet-unmatched", null, 404);
                    serve(servlet, "GET", "/mockserver/x", null, 404);
                    serve(servlet, "PUT", "/mockserver/verify", "{\"httpRequest\": {\"path\": \"/servlet\"}, \"times\": {\"atLeast\": 1}}", 202);
                    serve(servlet, "PUT", "/mockserver/reset", "", 200);
                } finally {
                    servlet.destroy();
                }
            } catch (Throwable throwable) {
                throwable.printStackTrace();
                System.exit(1);
            }
            System.out.println(ClassLoadingProbe.COMPLETED);
            System.exit(0);
        }

        private static void serve(MockServerServlet servlet, String method, String path, String body, int expectedStatus) {
            MockHttpServletRequest request = new MockHttpServletRequest(method, path);
            if (body != null) {
                request.addHeader("Content-Type", "application/json; charset=utf-8");
                request.setContent(body.getBytes(StandardCharsets.UTF_8));
            }
            MockHttpServletResponse response = new MockHttpServletResponse();
            servlet.service(request, response);
            if (response.getStatus() != expectedStatus) {
                throw new IllegalStateException(method + " " + path + " returned " + response.getStatus() + " not " + expectedStatus);
            }
        }
    }
}

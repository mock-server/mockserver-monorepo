package org.mockserver.templates.engine.velocity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.model.HttpRequest.request;

/**
 * Proves that toggling {@code velocityDisallowClassLoading} takes effect on an ALREADY-CONSTRUCTED
 * {@link VelocityTemplateEngine}.
 * <p>
 * The sandbox (Velocity's {@code SecureUberspector}) can only be installed when the underlying
 * {@code VelocityEngine} is built. The engine used to be built once in the constructor and every call
 * site caches the engine instance, so enabling the setting at runtime — via a system property or a
 * {@link Configuration} setter — was completely inert:
 * templates kept being able to instantiate arbitrary Java classes even though the operator was told
 * the setting had been applied.
 * <p>
 * {@link VelocityTemplateEngineTest#shouldHandleHttpRequestsWithVelocityTemplateWithDisallowClassLoading}
 * does not catch this because it constructs a NEW engine after flipping the flag. Every assertion here
 * deliberately reuses the SAME engine instance across the flip.
 */
public class VelocityTemplateEngineRuntimeSandboxToggleTest {

    // resolves a Java class through the classloader; renders the class name when class loading is
    // allowed, and (via the quiet reference $!) renders empty when the sandbox blocks .class
    private static final String CLASS_LOADING_TEMPLATE =
        "$!request.class.classLoader.loadClass('java.lang.String').getName()";
    private static final String ORDINARY_TEMPLATE = "path is $request.path";

    private final MockServerLogger mockServerLogger = new MockServerLogger();
    private final HttpRequest request = request().withPath("/somePath").withMethod("GET");

    private Configuration configuration;
    private Boolean originalDisallowClassLoading;

    @Before
    public void createConfiguration() {
        configuration = Configuration.configuration();
        originalDisallowClassLoading = configuration.velocityDisallowClassLoading();
        configuration.velocityDisallowClassLoading(false);
    }

    @After
    public void restoreConfiguration() {
        configuration.velocityDisallowClassLoading(originalDisallowClassLoading);
    }

    @Test
    public void shouldApplySandboxWhenEnabledAfterEngineConstruction() {
        // given - an engine constructed while class loading is allowed
        VelocityTemplateEngine templateEngine = new VelocityTemplateEngine(mockServerLogger, configuration);
        assertThat(templateEngine.renderTemplate(CLASS_LOADING_TEMPLATE, request), is("java.lang.String"));

        // when - the sandbox is enabled at runtime on the SAME engine instance
        configuration.velocityDisallowClassLoading(true);

        // then - class loading is now blocked
        assertThat(templateEngine.renderTemplate(CLASS_LOADING_TEMPLATE, request), is(""));
    }

    @Test
    public void shouldRemoveSandboxWhenDisabledAfterEngineConstruction() {
        // given - an engine constructed while the sandbox is enabled
        configuration.velocityDisallowClassLoading(true);
        VelocityTemplateEngine templateEngine = new VelocityTemplateEngine(mockServerLogger, configuration);
        assertThat(templateEngine.renderTemplate(CLASS_LOADING_TEMPLATE, request), is(""));

        // when - the sandbox is disabled at runtime on the SAME engine instance
        configuration.velocityDisallowClassLoading(false);

        // then - class loading is allowed again
        assertThat(templateEngine.renderTemplate(CLASS_LOADING_TEMPLATE, request), is("java.lang.String"));
    }

    @Test
    public void shouldApplyRepeatedTogglesOnSameEngine() {
        VelocityTemplateEngine templateEngine = new VelocityTemplateEngine(mockServerLogger, configuration);

        for (int i = 0; i < 3; i++) {
            configuration.velocityDisallowClassLoading(true);
            assertThat("blocked on toggle " + i, templateEngine.renderTemplate(CLASS_LOADING_TEMPLATE, request), is(""));
            configuration.velocityDisallowClassLoading(false);
            assertThat("allowed on toggle " + i, templateEngine.renderTemplate(CLASS_LOADING_TEMPLATE, request), is("java.lang.String"));
        }
    }

    @Test
    public void shouldRenderOrdinaryTemplatesAcrossEngineRebuild() {
        // given - an ordinary template renders and is cached by the parse-once cache
        VelocityTemplateEngine templateEngine = new VelocityTemplateEngine(mockServerLogger, configuration);
        assertThat(templateEngine.renderTemplate(ORDINARY_TEMPLATE, request), is("path is /somePath"));

        // when - the flag change forces the engine (and its template repository) to be rebuilt
        configuration.velocityDisallowClassLoading(true);

        // then - the same template still renders correctly against the rebuilt engine, and keeps
        // rendering correctly once it has been re-registered in the new repository
        assertThat(templateEngine.renderTemplate(ORDINARY_TEMPLATE, request), is("path is /somePath"));
        assertThat(templateEngine.renderTemplate(ORDINARY_TEMPLATE, request), is("path is /somePath"));

        // and - after rebuilding back, rendering is still correct
        configuration.velocityDisallowClassLoading(false);
        assertThat(templateEngine.renderTemplate(ORDINARY_TEMPLATE, request), is("path is /somePath"));
    }
}

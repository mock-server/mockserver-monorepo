package org.mockserver.extension;

import org.mockserver.server.initialize.ExpectationInitializer;

/**
 * The optional capabilities an {@link Extension} can add by also implementing a hook interface. A hook can be
 * disabled for a loaded extension, with a reason, without unloading the extension (see
 * {@link ExtensionRegistry#disableHook(ExtensionHook, String)}).
 */
public enum ExtensionHook {

    /**
     * Expectations loaded once when the server starts, after the configured initialisers.
     */
    EXPECTATION_INITIALIZER("expectationInitializer", ExpectationInitializer.class),

    /**
     * Control-plane routes under {@code /mockserver/extensions/<name>/}, served on a pool of their own.
     */
    ADMIN_API("adminApi", AdminApiExtension.class),

    /**
     * Told about each request the server answered, on the extension's own listener thread.
     */
    SERVE_EVENT_LISTENER("serveEventListener", ServeEventListener.class),

    /**
     * Told each time an expectation is added, updated or removed, on the extension's own listener thread.
     */
    EXPECTATION_LIFECYCLE_LISTENER("expectationLifecycleListener", ExpectationLifecycleListener.class),

    /**
     * Told each time the configuration is updated over the control plane, on the extension's own listener thread.
     */
    SETTINGS_LISTENER("settingsListener", SettingsListener.class),

    /**
     * Helpers for response and forward templates, under {@code ext}.
     */
    TEMPLATE_HELPER("templateHelper", TemplateHelperProvider.class),

    /**
     * Sees every data-plane request before matching, on the data-plane hook pool.
     */
    REQUEST_FILTER("requestFilter", RequestFilter.class),

    /**
     * Sees every data-plane response before it is written, on the data-plane hook pool.
     */
    RESPONSE_TRANSFORMER("responseTransformer", ResponseTransformer.class);

    private final String label;
    private final Class<?> hookType;

    ExtensionHook(String label, Class<?> hookType) {
        this.label = label;
        this.hookType = hookType;
    }

    public String label() {
        return label;
    }

    public boolean isImplementedBy(Extension extension) {
        return hookType.isInstance(extension);
    }

    @Override
    public String toString() {
        return label;
    }
}

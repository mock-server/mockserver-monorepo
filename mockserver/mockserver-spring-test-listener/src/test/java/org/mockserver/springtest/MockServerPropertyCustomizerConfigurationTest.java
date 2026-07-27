package org.mockserver.springtest;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.socket.tls.ForwardProxyTLSX509CertificatesTrustManager;

import java.util.Arrays;
import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.core.Is.is;

public class MockServerPropertyCustomizerConfigurationTest {

    @Test
    public void shouldApplyStringProperty() {
        Configuration config = MockServerPropertyCustomizer.buildConfiguration(
            Collections.singletonList("mockserver.initializationClass=com.example.MyInit")
        );
        assertThat(config.initializationClass(), is("com.example.MyInit"));
    }

    @Test
    public void shouldApplyMultipleStringProperties() {
        Configuration config = MockServerPropertyCustomizer.buildConfiguration(
            Arrays.asList(
                "mockserver.initializationClass=com.example.MyInit",
                "mockserver.initializationJsonPath=/path/to/json",
                "mockserver.livenessHttpGetPath=/healthz"
            )
        );
        assertThat(config.initializationClass(), is("com.example.MyInit"));
        assertThat(config.initializationJsonPath(), is("/path/to/json"));
        assertThat(config.livenessHttpGetPath(), is("/healthz"));
    }

    @Test
    public void shouldApplyBooleanProperties() {
        Configuration config = MockServerPropertyCustomizer.buildConfiguration(
            Arrays.asList(
                "mockserver.persistExpectations=true",
                "mockserver.disableLogging=true",
                "mockserver.enableCORSForAPI=true"
            )
        );
        assertThat(config.persistExpectations(), is(true));
        assertThat(config.disableLogging(), is(true));
        assertThat(config.enableCORSForAPI(), is(true));
    }

    @Test
    public void shouldApplyIntegerProperties() {
        Configuration config = MockServerPropertyCustomizer.buildConfiguration(
            Arrays.asList(
                "mockserver.maxExpectations=100",
                "mockserver.maxLogEntries=500",
                "mockserver.corsMaxAgeInSeconds=3600"
            )
        );
        assertThat(config.maxExpectations(), is(100));
        assertThat(config.maxLogEntries(), is(500));
        assertThat(config.corsMaxAgeInSeconds(), is(3600));
    }

    @Test
    public void shouldApplyLongPropertiesWithNameMismatch() {
        Configuration config = MockServerPropertyCustomizer.buildConfiguration(
            Arrays.asList(
                "mockserver.maxFutureTimeout=5000",
                "mockserver.maxSocketTimeout=10000",
                "mockserver.socketConnectionTimeout=15000"
            )
        );
        assertThat(config.maxFutureTimeoutInMillis(), is(5000L));
        assertThat(config.maxSocketTimeoutInMillis(), is(10000L));
        assertThat(config.socketConnectionTimeoutInMillis(), is(15000L));
    }

    @Test
    public void shouldApplyLongPropertiesWithMatchingNames() {
        Configuration config = MockServerPropertyCustomizer.buildConfiguration(
            Arrays.asList(
                "mockserver.breakpointTimeoutMillis=45000",
                "mockserver.javascriptTemplateExecutionTimeout=2500"
            )
        );
        assertThat(config.breakpointTimeoutMillis(), is(45000L));
        assertThat(config.javascriptTemplateExecutionTimeout(), is(2500L));
    }

    @Test
    public void shouldApplyTemplateClassRestrictionProperties() {
        // javascriptAllowedClasses is the ONLY way to let a JavaScript template reach a Java class since
        // the default became deny-everything (GHSA-7pwj-xvc2-hfpc), so a Spring test that needs one is
        // stuck unless this property is settable the same way as the rest.
        Configuration config = MockServerPropertyCustomizer.buildConfiguration(
            Arrays.asList(
                "mockserver.javascriptAllowedClasses=java.util.UUID,java.time.*",
                "mockserver.javascriptDisallowedClasses=java.lang.Runtime",
                "mockserver.velocityDisallowClassLoading=false"
            )
        );
        assertThat(config.javascriptAllowedClasses(), is("java.util.UUID,java.time.*"));
        assertThat(config.javascriptDisallowedClasses(), is("java.lang.Runtime"));
        assertThat(config.velocityDisallowClassLoading(), is(false));
    }

    @Test
    public void shouldApplyLogLevel() {
        Configuration config = MockServerPropertyCustomizer.buildConfiguration(
            Collections.singletonList("mockserver.logLevel=WARN")
        );
        assertThat(config.logLevel(), is(org.slf4j.event.Level.WARN));
    }

    @Test
    public void shouldApplyEnumProperty() {
        Configuration config = MockServerPropertyCustomizer.buildConfiguration(
            Collections.singletonList(
                "mockserver.forwardProxyTLSX509CertificatesTrustManagerType=JVM")
        );
        assertThat(config.forwardProxyTLSX509CertificatesTrustManagerType(),
            is(ForwardProxyTLSX509CertificatesTrustManager.JVM));
    }

    @Test
    public void shouldApplyInetSocketAddressProperty() {
        Configuration config = MockServerPropertyCustomizer.buildConfiguration(
            Collections.singletonList("mockserver.forwardHttpProxy=localhost:8080")
        );
        assertThat(config.forwardHttpProxy().getHostName(), is("localhost"));
        assertThat(config.forwardHttpProxy().getPort(), is(8080));
    }

    @Test
    public void shouldApplySetProperties() {
        Configuration config = MockServerPropertyCustomizer.buildConfiguration(
            Collections.singletonList(
                "mockserver.sslSubjectAlternativeNameDomains=foo.com,bar.com,baz.com")
        );
        assertThat(config.sslSubjectAlternativeNameDomains(),
            containsInAnyOrder("foo.com", "bar.com", "baz.com"));
    }

    @Test
    public void shouldApplyMixedPropertyTypes() {
        Configuration config = MockServerPropertyCustomizer.buildConfiguration(
            Arrays.asList(
                "mockserver.initializationClass=com.example.MyInit",
                "mockserver.maxExpectations=200",
                "mockserver.persistExpectations=true",
                "mockserver.maxFutureTimeout=30000"
            )
        );
        assertThat(config.initializationClass(), is("com.example.MyInit"));
        assertThat(config.maxExpectations(), is(200));
        assertThat(config.persistExpectations(), is(true));
        assertThat(config.maxFutureTimeoutInMillis(), is(30000L));
    }

    @Test
    public void shouldReturnDefaultConfigurationForEmptyProperties() {
        Configuration config = MockServerPropertyCustomizer.buildConfiguration(
            Collections.emptyList()
        );
        assertThat(config.initializationClass(), is(""));
    }

    @Test
    public void shouldIgnoreNonMockServerProperties() {
        Configuration config = MockServerPropertyCustomizer.buildConfiguration(
            Collections.singletonList("server.url=http://localhost:8080")
        );
        assertThat(config.initializationClass(), is(""));
    }

    @Test
    public void shouldIgnoreMalformedProperties() {
        Configuration config = MockServerPropertyCustomizer.buildConfiguration(
            Collections.singletonList("mockserver.initializationClassWithNoEquals")
        );
        assertThat(config.initializationClass(), is(""));
    }

    @Test
    public void shouldHandlePropertyValueWithEquals() {
        Configuration config = MockServerPropertyCustomizer.buildConfiguration(
            Collections.singletonList("mockserver.livenessHttpGetPath=/health?check=true")
        );
        assertThat(config.livenessHttpGetPath(), is("/health?check=true"));
    }

    @Test
    public void shouldHandleUnsupportedComplexProperties() {
        Configuration config = MockServerPropertyCustomizer.buildConfiguration(
            Arrays.asList(
                "mockserver.logLevelOverrides={\"some\":\"json\"}",
                "mockserver.initializationClass=com.example.MyInit"
            )
        );
        assertThat(config.initializationClass(), is("com.example.MyInit"));
    }

    @Test
    public void shouldHandleUnknownProperties() {
        Configuration config = MockServerPropertyCustomizer.buildConfiguration(
            Arrays.asList(
                "mockserver.nonExistentProperty=someValue",
                "mockserver.initializationClass=com.example.MyInit"
            )
        );
        assertThat(config.initializationClass(), is("com.example.MyInit"));
    }

    @Test
    public void shouldParseIpv6ProxyAddress() {
        Configuration config = MockServerPropertyCustomizer.buildConfiguration(
            Collections.singletonList("mockserver.forwardHttpProxy=[::1]:8080")
        );
        assertThat(config.forwardHttpProxy().getAddress().getHostAddress(), is("0:0:0:0:0:0:0:1"));
        assertThat(config.forwardHttpProxy().getPort(), is(8080));
    }

    @Test
    public void shouldRejectInvalidBooleanValue() {
        Configuration config = MockServerPropertyCustomizer.buildConfiguration(
            Collections.singletonList("mockserver.persistExpectations=yes")
        );
        assertThat(config.persistExpectations(), is(false));
    }

    @Test
    public void shouldTrimWhitespaceInCommaSeparatedSets() {
        Configuration config = MockServerPropertyCustomizer.buildConfiguration(
            Collections.singletonList(
                "mockserver.sslSubjectAlternativeNameDomains=foo.com, bar.com , baz.com")
        );
        assertThat(config.sslSubjectAlternativeNameDomains(),
            containsInAnyOrder("foo.com", "bar.com", "baz.com"));
    }
}

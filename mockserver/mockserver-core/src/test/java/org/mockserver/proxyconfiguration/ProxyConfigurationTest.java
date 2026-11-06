package org.mockserver.proxyconfiguration;

import com.google.common.collect.ImmutableList;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;

import java.net.InetSocketAddress;
import java.util.List;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.proxyconfiguration.ProxyConfiguration.proxyConfiguration;

public class ProxyConfigurationTest {

    private InetSocketAddress originalForwardHttpProxy;
    private InetSocketAddress originalForwardHttpsProxy;
    private InetSocketAddress originalForwardSocksProxy;
    private String originalForwardProxyAuthenticationUsername;
    private String originalForwardProxyAuthenticationPassword;

    @Before
    public void recordOriginalPropertyValues() {
        originalForwardHttpProxy = ConfigurationProperties.forwardHttpProxy();
        originalForwardHttpsProxy = ConfigurationProperties.forwardHttpsProxy();
        originalForwardSocksProxy = ConfigurationProperties.forwardSocksProxy();
        originalForwardProxyAuthenticationUsername = ConfigurationProperties.forwardProxyAuthenticationUsername();
        originalForwardProxyAuthenticationPassword = ConfigurationProperties.forwardProxyAuthenticationPassword();
    }

    @After
    public void restoreOriginalPropertyValues() {
        ConfigurationProperties.forwardHttpProxy("");
        ConfigurationProperties.forwardHttpsProxy("");
        ConfigurationProperties.forwardSocksProxy("");
        if (originalForwardHttpProxy != null) {
            ConfigurationProperties.forwardHttpProxy(originalForwardHttpProxy);
        }
        if (originalForwardHttpsProxy != null) {
            ConfigurationProperties.forwardHttpsProxy(originalForwardHttpsProxy);
        }
        if (originalForwardSocksProxy != null) {
            ConfigurationProperties.forwardSocksProxy(originalForwardSocksProxy);
        }
        ConfigurationProperties.forwardProxyAuthenticationUsername(originalForwardProxyAuthenticationUsername);
        ConfigurationProperties.forwardProxyAuthenticationPassword(originalForwardProxyAuthenticationPassword);
    }

    @Test
    public void shouldConfigureForwardHttpProxy() {
        // given
        String proxyAddress = "127.0.0.1:1090";

        // when
        assertThat(ConfigurationProperties.forwardHttpProxy(), nullValue());
        ConfigurationProperties.forwardHttpProxy(proxyAddress);

        // then
        assertThat(ConfigurationProperties.forwardHttpProxy().toString(), is("/" + proxyAddress));
        assertThat(System.getProperty("mockserver.forwardHttpProxy"), is(proxyAddress));
        assertThat(proxyConfiguration(configuration()), equalTo(ImmutableList.of(proxyConfiguration(ProxyConfiguration.Type.HTTP, proxyAddress, "", ""))));
    }

    @Test
    public void shouldLeaveForwardProxyHostNamesUnresolvedForEachConnectionToLookUp() {
        ConfigurationProperties.forwardHttpProxy("localhost:1090");
        ConfigurationProperties.forwardHttpsProxy("localhost:1091");

        for (InetSocketAddress address : new InetSocketAddress[]{ConfigurationProperties.forwardHttpProxy(), ConfigurationProperties.forwardHttpsProxy()}) {
            assertThat(address + " is not looked up when it is read", address.isUnresolved(), is(true));
        }
        assertThat(ConfigurationProperties.forwardHttpProxy().getHostString(), is("localhost"));
        assertThat(ConfigurationProperties.forwardHttpProxy().getPort(), is(1090));
        List<ProxyConfiguration> proxyConfigurations = proxyConfiguration(configuration());
        assertThat(proxyConfigurations.get(0).getProxyAddress().isUnresolved(), is(true));
        assertThat(proxyConfigurations.get(1).getProxyAddress().isUnresolved(), is(true));
        assertThat(proxyConfigurations, equalTo(ImmutableList.of(
            proxyConfiguration(ProxyConfiguration.Type.HTTP, "localhost:1090", "", ""),
            proxyConfiguration(ProxyConfiguration.Type.HTTPS, "localhost:1091", "", "")
        )));

        ConfigurationProperties.forwardHttpProxy("");
        ConfigurationProperties.forwardHttpsProxy("");
        ConfigurationProperties.forwardSocksProxy("localhost:1092");
        assertThat(ConfigurationProperties.forwardSocksProxy().isUnresolved(), is(true));
    }

    @Test
    public void shouldParseForwardProxyIpLiteralsAsAddresses() {
        ConfigurationProperties.forwardHttpProxy("[::1]:1090");

        assertThat(ConfigurationProperties.forwardHttpProxy().isUnresolved(), is(false));
        assertThat(ConfigurationProperties.forwardHttpProxy().getAddress().isLoopbackAddress(), is(true));
        assertThat(proxyConfiguration(ProxyConfiguration.Type.SOCKS5, "127.0.0.1:1090").getProxyAddress().isUnresolved(), is(false));
    }

    @Test
    public void shouldLeaveProxyAddressHostNameUnresolved() {
        InetSocketAddress address = proxyConfiguration(ProxyConfiguration.Type.HTTPS, "localhost:1090").getProxyAddress();

        assertThat(address.isUnresolved(), is(true));
        assertThat(address.getHostString(), is("localhost"));
        assertThat(address.getPort(), is(1090));
    }

    @Test
    public void shouldSetForwardProxyFromAnySocketAddress() {
        ConfigurationProperties.forwardHttpProxy(InetSocketAddress.createUnresolved("proxy.example", 3128));
        assertThat(System.getProperty("mockserver.forwardHttpProxy"), is("proxy.example:3128"));

        ConfigurationProperties.forwardHttpProxy(new InetSocketAddress("localhost", 3128));
        assertThat(System.getProperty("mockserver.forwardHttpProxy"), is("localhost:3128"));

        ConfigurationProperties.forwardHttpsProxy(new InetSocketAddress("::1", 3129));
        assertThat(System.getProperty("mockserver.forwardHttpsProxy"), is("[0:0:0:0:0:0:0:1]:3129"));
        assertThat(ConfigurationProperties.forwardHttpsProxy(), is(new InetSocketAddress("::1", 3129)));

        ConfigurationProperties.forwardHttpProxy("");
        ConfigurationProperties.forwardHttpsProxy("");
        ConfigurationProperties.forwardSocksProxy(new InetSocketAddress("127.0.0.1", 3130));
        assertThat(System.getProperty("mockserver.forwardSocksProxy"), is("127.0.0.1:3130"));
    }

    @Test
    public void shouldConfigureForwardHttpsProxy() {
        // given
        String proxyAddress = "127.0.0.1:1090";

        // when
        assertThat(ConfigurationProperties.forwardHttpsProxy(), nullValue());
        ConfigurationProperties.forwardHttpsProxy(proxyAddress);

        // then
        assertThat(ConfigurationProperties.forwardHttpsProxy().toString(), is("/" + proxyAddress));
        assertThat(System.getProperty("mockserver.forwardHttpsProxy"), is(proxyAddress));
        assertThat(proxyConfiguration(configuration()), equalTo(ImmutableList.of(proxyConfiguration(ProxyConfiguration.Type.HTTPS, proxyAddress, "", ""))));
    }

    @Test
    public void shouldConfigureForwardHttpAndHttpsProxy() {
        // given
        String proxyAddress = "127.0.0.1:1090";

        // when
        assertThat(ConfigurationProperties.forwardHttpProxy(), nullValue());
        ConfigurationProperties.forwardHttpProxy(proxyAddress);
        assertThat(ConfigurationProperties.forwardHttpsProxy(), nullValue());
        ConfigurationProperties.forwardHttpsProxy(proxyAddress);

        // then
        assertThat(ConfigurationProperties.forwardHttpProxy().toString(), is("/" + proxyAddress));
        assertThat(System.getProperty("mockserver.forwardHttpProxy"), is(proxyAddress));
        assertThat(ConfigurationProperties.forwardHttpsProxy().toString(), is("/" + proxyAddress));
        assertThat(System.getProperty("mockserver.forwardHttpsProxy"), is(proxyAddress));
        assertThat(proxyConfiguration(configuration()), equalTo(ImmutableList.of(
                proxyConfiguration(ProxyConfiguration.Type.HTTP, proxyAddress, "", ""),
                proxyConfiguration(ProxyConfiguration.Type.HTTPS, proxyAddress, "", "")
        )));
    }

    @Test
    public void shouldConfigureForwardHttpsProxyWithAuthentication() {
        // given
        String proxyAddress = "127.0.0.1:1090";
        String userName = "userName";
        String password = "password";

        // when
        assertThat(ConfigurationProperties.forwardHttpsProxy(), nullValue());
        ConfigurationProperties.forwardHttpsProxy(proxyAddress);
        assertThat(ConfigurationProperties.forwardProxyAuthenticationUsername(), equalTo(""));
        ConfigurationProperties.forwardProxyAuthenticationUsername(userName);
        assertThat(ConfigurationProperties.forwardProxyAuthenticationPassword(), equalTo(""));
        ConfigurationProperties.forwardProxyAuthenticationPassword(password);

        // then
        assertThat(ConfigurationProperties.forwardHttpsProxy().toString(), is("/" + proxyAddress));
        assertThat(System.getProperty("mockserver.forwardHttpsProxy"), is(proxyAddress));
        assertThat(ConfigurationProperties.forwardProxyAuthenticationUsername(), is(userName));
        assertThat(System.getProperty("mockserver.forwardProxyAuthenticationUsername"), is(userName));
        assertThat(ConfigurationProperties.forwardProxyAuthenticationPassword(), is(password));
        assertThat(System.getProperty("mockserver.forwardProxyAuthenticationPassword"), is(password));
        assertThat(proxyConfiguration(configuration()), equalTo(ImmutableList.of(proxyConfiguration(ProxyConfiguration.Type.HTTPS, proxyAddress, userName, password))));
    }

    @Test
    public void shouldConfigureForwardSocksProxy() {
        // given
        String proxyAddress = "127.0.0.1:1090";

        // when
        assertThat(ConfigurationProperties.forwardSocksProxy(), nullValue());
        ConfigurationProperties.forwardSocksProxy(proxyAddress);

        // then
        assertThat(ConfigurationProperties.forwardSocksProxy().toString(), is("/" + proxyAddress));
        assertThat(System.getProperty("mockserver.forwardSocksProxy"), is(proxyAddress));
        assertThat(proxyConfiguration(configuration()), equalTo(ImmutableList.of(proxyConfiguration(ProxyConfiguration.Type.SOCKS5, proxyAddress, "", ""))));
    }

    @Test
    public void shouldConfigureForwardSocksProxyWithAuthentication() {
        // given
        String proxyAddress = "127.0.0.1:1090";
        String userName = "userName";
        String password = "password";

        // when
        assertThat(ConfigurationProperties.forwardSocksProxy(), nullValue());
        ConfigurationProperties.forwardSocksProxy(proxyAddress);
        assertThat(ConfigurationProperties.forwardProxyAuthenticationUsername(), equalTo(""));
        ConfigurationProperties.forwardProxyAuthenticationUsername(userName);
        assertThat(ConfigurationProperties.forwardProxyAuthenticationPassword(), equalTo(""));
        ConfigurationProperties.forwardProxyAuthenticationPassword(password);

        // then
        assertThat(ConfigurationProperties.forwardSocksProxy().toString(), is("/" + proxyAddress));
        assertThat(System.getProperty("mockserver.forwardSocksProxy"), is(proxyAddress));
        assertThat(ConfigurationProperties.forwardProxyAuthenticationUsername(), is(userName));
        assertThat(System.getProperty("mockserver.forwardProxyAuthenticationUsername"), is(userName));
        assertThat(ConfigurationProperties.forwardProxyAuthenticationPassword(), is(password));
        assertThat(System.getProperty("mockserver.forwardProxyAuthenticationPassword"), is(password));
        assertThat(proxyConfiguration(configuration()), equalTo(ImmutableList.of(proxyConfiguration(ProxyConfiguration.Type.SOCKS5, proxyAddress, userName, password))));
    }

    @Test
    public void shouldNotAllowConfigurationOfForwardHttpProxyAndSocksProxy() {
        // given
        String proxyAddress = "127.0.0.1:1090";

        // when
        assertThat(ConfigurationProperties.forwardHttpProxy(), nullValue());
        ConfigurationProperties.forwardHttpProxy(proxyAddress);
        assertThat(ConfigurationProperties.forwardSocksProxy(), nullValue());
        ConfigurationProperties.forwardSocksProxy(proxyAddress);

        // then
        assertThat(ConfigurationProperties.forwardHttpProxy().toString(), is("/" + proxyAddress));
        assertThat(System.getProperty("mockserver.forwardHttpProxy"), is(proxyAddress));
        assertThat(ConfigurationProperties.forwardSocksProxy().toString(), is("/" + proxyAddress));
        assertThat(System.getProperty("mockserver.forwardSocksProxy"), is(proxyAddress));
        IllegalArgumentException illegalArgumentException = assertThrows(IllegalArgumentException.class, () -> proxyConfiguration(configuration()));
        assertThat(illegalArgumentException.getMessage(), equalTo("Invalid proxy configuration it is not possible to configure HTTP or HTTPS proxy at the same time as a SOCKS proxy, please choose either HTTP(S) proxy OR a SOCKS proxy"));
    }

    @Test
    public void shouldNotAllowConfigurationOfForwardHttpsProxyAndSocksProxy() {
        // given
        String proxyAddress = "127.0.0.1:1090";

        // when
        assertThat(ConfigurationProperties.forwardHttpsProxy(), nullValue());
        ConfigurationProperties.forwardHttpsProxy(proxyAddress);
        assertThat(ConfigurationProperties.forwardSocksProxy(), nullValue());
        ConfigurationProperties.forwardSocksProxy(proxyAddress);

        // then
        assertThat(ConfigurationProperties.forwardHttpsProxy().toString(), is("/" + proxyAddress));
        assertThat(System.getProperty("mockserver.forwardHttpsProxy"), is(proxyAddress));
        assertThat(ConfigurationProperties.forwardSocksProxy().toString(), is("/" + proxyAddress));
        assertThat(System.getProperty("mockserver.forwardSocksProxy"), is(proxyAddress));
        IllegalArgumentException illegalArgumentException = assertThrows(IllegalArgumentException.class, () -> proxyConfiguration(configuration()));
        assertThat(illegalArgumentException.getMessage(), equalTo("Invalid proxy configuration it is not possible to configure HTTP or HTTPS proxy at the same time as a SOCKS proxy, please choose either HTTP(S) proxy OR a SOCKS proxy"));
    }

    @Test
    public void shouldNotAllowConfigurationOfForwardHttpAndHttpsProxyAndSocksProxy() {
        // given
        String proxyAddress = "127.0.0.1:1090";

        // when
        assertThat(ConfigurationProperties.forwardHttpProxy(), nullValue());
        ConfigurationProperties.forwardHttpProxy(proxyAddress);
        assertThat(ConfigurationProperties.forwardHttpsProxy(), nullValue());
        ConfigurationProperties.forwardHttpsProxy(proxyAddress);
        assertThat(ConfigurationProperties.forwardSocksProxy(), nullValue());
        ConfigurationProperties.forwardSocksProxy(proxyAddress);

        // then
        assertThat(ConfigurationProperties.forwardHttpProxy().toString(), is("/" + proxyAddress));
        assertThat(System.getProperty("mockserver.forwardHttpProxy"), is(proxyAddress));
        assertThat(ConfigurationProperties.forwardHttpsProxy().toString(), is("/" + proxyAddress));
        assertThat(System.getProperty("mockserver.forwardHttpsProxy"), is(proxyAddress));
        assertThat(ConfigurationProperties.forwardSocksProxy().toString(), is("/" + proxyAddress));
        assertThat(System.getProperty("mockserver.forwardSocksProxy"), is(proxyAddress));
        IllegalArgumentException illegalArgumentException = assertThrows(IllegalArgumentException.class, () -> proxyConfiguration(configuration()));
        assertThat(illegalArgumentException.getMessage(), equalTo("Invalid proxy configuration it is not possible to configure HTTP or HTTPS proxy at the same time as a SOCKS proxy, please choose either HTTP(S) proxy OR a SOCKS proxy"));
    }

}
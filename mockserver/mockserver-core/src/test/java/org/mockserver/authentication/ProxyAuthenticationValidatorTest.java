package org.mockserver.authentication;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.base64.Base64;
import org.junit.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.mockserver.model.HttpRequest.request;

/**
 * Guards the forward-proxy credential comparison.
 *
 * <p>The comparison used to run through {@code KeysToMultiValues.containsEntry}, whose check is
 * {@code equalsIgnoreCase} — case-INSENSITIVE (base64 is case-sensitive, so that silently widens the
 * accepted credential space) and short-circuiting (a timing side channel). {@link ConstantTimeEquals}
 * already existed to close exactly that channel, but nothing asserted that any caller used it, so both
 * proxy paths drifted off it unnoticed.
 *
 * <p>The last test in this class is the piece that was missing: it asserts the CALL SITES reference the
 * constant-time path, so a future refactor cannot quietly revert to {@code containsHeader(...)}.
 */
public class ProxyAuthenticationValidatorTest {

    private static final String USERNAME = "MockServerUser";
    private static final String PASSWORD = "MockServerPassword";

    private static String validCredential() {
        return ProxyAuthenticationValidator.expectedProxyAuthorizationHeaderValue(USERNAME, PASSWORD);
    }

    @Test
    public void shouldAcceptExactlyMatchingCredential() {
        assertThat(ProxyAuthenticationValidator.isAuthenticated(
            request().withHeader("Proxy-Authorization", validCredential()), USERNAME, PASSWORD), is(true));
    }

    @Test
    public void shouldAcceptCaseInsensitiveBasicScheme() {
        String token = validCredential().substring("Basic ".length());
        for (String scheme : new String[]{"Basic", "basic", "BASIC", "bAsIc"}) {
            assertThat(scheme, ProxyAuthenticationValidator.isAuthenticated(
                request().withHeader("Proxy-Authorization", scheme + " " + token), USERNAME, PASSWORD), is(true));
            assertThat(scheme, ProxyAuthenticationValidator.isAuthenticated(
                request().withHeader("Proxy-Authorization", scheme + " " + swapCase(token)), USERNAME, PASSWORD), is(false));
        }
    }

    @Test
    public void shouldRejectNonstandardCredentialWhitespace() {
        String token = validCredential().substring("Basic ".length());
        for (String value : new String[]{"Basic   " + token, " \tbasic  " + token + "\t "}) {
            assertThat(ProxyAuthenticationValidator.isAuthenticated(
                request().withHeader("Proxy-Authorization", value), USERNAME, PASSWORD), is(false));
        }
    }

    @Test
    public void shouldRejectMalformedOrUnsupportedCredentials() {
        String token = validCredential().substring("Basic ".length());
        for (String value : new String[]{
            "Basic", "Basic ", "Basic\t" + token, "Basic" + token, "Basic \t" + token,
            "Bearer " + token, "Digest " + token, "Bas\u0131c " + token,
            "Basic " + token + ", Basic " + token, "Basic " + token + "\n"
        }) {
            assertThat(ProxyAuthenticationValidator.isAuthenticated(
                request().withHeader("Proxy-Authorization", value), USERNAME, PASSWORD), is(false));
        }
    }

    @Test
    public void shouldEncodeCredentialIdenticallyToRfc4648Base64() {
        // validCredential() is built by the production method, so shouldAcceptExactlyMatchingCredential
        // would still pass if the encoding drifted. Pin it against an independent encoder instead: this
        // is an auth path, where a changed encoding silently changes who is authenticated.
        //
        // The credential below is deliberately chosen so its base64 contains BOTH '+' and '/', the only
        // two characters where the standard alphabet differs from the URL-safe one. With ASCII
        // credentials the two encoders agree, so this assertion would pass against either and prove
        // nothing -- verified by swapping the production call to getUrlEncoder(), which this fixture
        // catches and an ASCII one does not.
        String distinguishing = "u\u03FF";
        String distinguishingPassword = "p\u0080";
        assertThat(ProxyAuthenticationValidator.expectedProxyAuthorizationHeaderValue(distinguishing, distinguishingPassword),
            is("Basic " + encode(distinguishing + ":" + distinguishingPassword)));

        assertThat(validCredential(), is("Basic " + encode(USERNAME + ":" + PASSWORD)));
    }

    @Test
    public void shouldRejectCaseMutatedCredential() {
        // base64 is case-SENSITIVE: flipping the case of the encoded token yields a DIFFERENT credential
        // and must be rejected. Under the old equalsIgnoreCase comparison this returned true, losing
        // roughly one bit of entropy per alphabetic character in the credential.
        String valid = validCredential();
        String base64 = valid.substring("Basic ".length());
        String caseMutated = "Basic " + swapCase(base64);
        assertThat("case-mutated base64 must not be accepted — the credential differs",
            caseMutated, is(not(valid)));
        assertThat(ProxyAuthenticationValidator.isAuthenticated(
            request().withHeader("Proxy-Authorization", caseMutated), USERNAME, PASSWORD), is(false));
    }

    @Test
    public void shouldRejectUpperCasedAndLowerCasedCredential() {
        String base64 = validCredential().substring("Basic ".length());
        assertThat(ProxyAuthenticationValidator.isAuthenticated(
            request().withHeader("Proxy-Authorization", "Basic " + base64.toUpperCase()), USERNAME, PASSWORD), is(false));
        assertThat(ProxyAuthenticationValidator.isAuthenticated(
            request().withHeader("Proxy-Authorization", "Basic " + base64.toLowerCase()), USERNAME, PASSWORD), is(false));
    }

    @Test
    public void shouldRejectMissingAndWrongCredential() {
        assertThat(ProxyAuthenticationValidator.isAuthenticated(request(), USERNAME, PASSWORD), is(false));
        assertThat(ProxyAuthenticationValidator.isAuthenticated(
            request().withHeader("Proxy-Authorization", "Basic " + encode("wrong:credential")), USERNAME, PASSWORD), is(false));
        assertThat(ProxyAuthenticationValidator.isAuthenticated(
            request().withHeader("Proxy-Authorization", ""), USERNAME, PASSWORD), is(false));
    }

    @Test
    public void shouldAcceptWhenOneOfSeveralSuppliedValuesMatches() {
        assertThat(ProxyAuthenticationValidator.isAuthenticated(
            request()
                .withHeader("Proxy-Authorization", "Basic " + encode("wrong:credential"), validCredential()),
            USERNAME, PASSWORD), is(true));
    }

    @Test
    public void shouldNotEnforceWhenProxyAuthenticationNotConfigured() {
        assertThat(ProxyAuthenticationValidator.proxyAuthenticationConfigured("", ""), is(false));
        assertThat(ProxyAuthenticationValidator.proxyAuthenticationConfigured(USERNAME, ""), is(false));
        assertThat(ProxyAuthenticationValidator.proxyAuthenticationConfigured("", PASSWORD), is(false));
        assertThat(ProxyAuthenticationValidator.proxyAuthenticationConfigured(USERNAME, PASSWORD), is(true));
        // nothing configured => nothing to enforce
        assertThat(ProxyAuthenticationValidator.isAuthenticated(request(), "", ""), is(true));
    }

    // ------------------------------------------------------------------------------------------------
    // The missing guard: assert the credential-comparison CALL SITES route through the constant-time path
    // ------------------------------------------------------------------------------------------------

    /**
     * Every production class that compares a proxy credential, and the type it must delegate to.
     *
     * <p>Asserting behaviour at each call site would require standing up a proxy connection per case;
     * inspecting the compiled constant pool is a cheap, exact check that the delegation exists. If a
     * refactor reverts either site to {@code request.containsHeader(PROXY_AUTHORIZATION, ...)} — the
     * case-insensitive, short-circuiting comparison this whole class exists to prevent — the class will
     * no longer reference {@link ProxyAuthenticationValidator} and this test fails.
     */
    @Test
    public void everyProxyCredentialComparisonSiteMustUseTheConstantTimeValidator() throws Exception {
        List<String> sitesNotUsingValidator = new ArrayList<>();
        for (String site : new String[]{
            "org.mockserver.mock.action.http.HttpActionHandler",
            "org.mockserver.netty.HttpRequestHandler"
        }) {
            List<String> referencedClasses = referencedClassNames(site);
            if (referencedClasses == null) {
                // class not on this module's test classpath (mockserver-netty is a downstream module) —
                // covered by the netty-module copy of this guard rather than silently passing here
                continue;
            }
            if (!referencedClasses.contains("org/mockserver/authentication/ProxyAuthenticationValidator")) {
                sitesNotUsingValidator.add(site);
            }
        }
        assertThat("proxy credential comparison sites that no longer delegate to "
                + "ProxyAuthenticationValidator (they are likely comparing with the case-insensitive, "
                + "short-circuiting containsHeader/equalsIgnoreCase path again): " + sitesNotUsingValidator,
            sitesNotUsingValidator, is(empty()));
    }

    @Test
    public void constantTimeEqualsMustBeUsedByTheValidator() throws Exception {
        assertThat(referencedClassNames("org.mockserver.authentication.ProxyAuthenticationValidator"),
            hasItem("org/mockserver/authentication/ConstantTimeEquals"));
    }

    /**
     * Read the class constant pool and return every referenced class name, or null if the class is not on
     * the classpath. Deliberately dependency-free (no ASM) — only CONSTANT_Class entries are needed.
     */
    private static List<String> referencedClassNames(String className) throws IOException {
        String resource = "/" + className.replace('.', '/') + ".class";
        try (InputStream inputStream = ProxyAuthenticationValidatorTest.class.getResourceAsStream(resource)) {
            if (inputStream == null) {
                return null;
            }
            DataInputStream classFile = new DataInputStream(inputStream);
            classFile.readInt();   // magic
            classFile.readUnsignedShort(); // minor
            classFile.readUnsignedShort(); // major
            int constantPoolCount = classFile.readUnsignedShort();
            String[] utf8Constants = new String[constantPoolCount];
            int[] classNameIndexes = new int[constantPoolCount];
            for (int i = 1; i < constantPoolCount; i++) {
                int tag = classFile.readUnsignedByte();
                switch (tag) {
                    case 1: // CONSTANT_Utf8
                        utf8Constants[i] = classFile.readUTF();
                        break;
                    case 7: // CONSTANT_Class
                        classNameIndexes[i] = classFile.readUnsignedShort();
                        break;
                    case 8:  // String
                    case 16: // MethodType
                    case 19: // Module
                    case 20: // Package
                        classFile.readUnsignedShort();
                        break;
                    case 15: // MethodHandle
                        classFile.readUnsignedByte();
                        classFile.readUnsignedShort();
                        break;
                    case 5:  // Long
                    case 6:  // Double
                        classFile.readLong();
                        i++; // long/double take two constant pool slots
                        break;
                    default: // Fieldref/Methodref/InterfaceMethodref/NameAndType/Integer/Float/Dynamic...
                        classFile.readInt();
                        break;
                }
            }
            List<String> referenced = new ArrayList<>();
            for (int i = 1; i < constantPoolCount; i++) {
                if (classNameIndexes[i] != 0) {
                    referenced.add(utf8Constants[classNameIndexes[i]]);
                }
            }
            return referenced;
        }
    }

    private static String encode(String value) {
        ByteBuf raw = Unpooled.copiedBuffer(value, StandardCharsets.UTF_8);
        ByteBuf encoded = Base64.encode(raw, false);
        try {
            return encoded.toString(StandardCharsets.US_ASCII);
        } finally {
            raw.release();
            encoded.release();
        }
    }

    private static String swapCase(String value) {
        StringBuilder swapped = new StringBuilder(value.length());
        for (char character : value.toCharArray()) {
            if (Character.isUpperCase(character)) {
                swapped.append(Character.toLowerCase(character));
            } else if (Character.isLowerCase(character)) {
                swapped.append(Character.toUpperCase(character));
            } else {
                swapped.append(character);
            }
        }
        return swapped.toString();
    }

    private static org.hamcrest.Matcher<String> not(String value) {
        return org.hamcrest.Matchers.not(is(value));
    }
}

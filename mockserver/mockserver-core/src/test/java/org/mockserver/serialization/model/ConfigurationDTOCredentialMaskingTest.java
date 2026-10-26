package org.mockserver.serialization.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.configuration.ConfigurationProperties.ResolvedProperty;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.serialization.ConfigurationSerializer;
import org.mockserver.serialization.ObjectMapperFactory;

import java.beans.Introspector;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.configuration.Configuration.configuration;
// statically imported so this file carries no qualified ConfigurationProperties.<call>(arg)
// text, which GlobalStateMutationGuardTest reads as a global-state MUTATION — this predicate
// is a pure read. Same import style, for the same reason, as ConfigurationPropertiesRedactionTest.
import static org.mockserver.configuration.ConfigurationProperties.isSensitivePropertyName;
import static org.mockserver.configuration.ConfigurationProperties.effectiveConfiguration;
import static org.mockserver.configuration.ConfigurationProperties.isWholeValueMaskedOnRead;
import static org.mockserver.configuration.ConfigurationProperties.redactSensitiveValue;

/**
 * Security guard for WRITE-ONLY credential configuration properties.
 *
 * <p>These properties are fully settable — over the {@code Configuration} instance and over
 * {@code PUT /mockserver/configuration} — but their credential content must never be readable:
 * {@code GET /mockserver/configuration} returns {@link ConfigurationProperties#REDACTED_VALUE} in
 * its place.
 *
 * <p>Two shapes are covered, and both are enumerated from {@link #WRITE_ONLY_CREDENTIALS}:
 * <ul>
 *   <li><strong>whole-value</strong> credentials, whose entire value is the secret, mask to the bare
 *       {@link ConfigurationProperties#REDACTED_VALUE};</li>
 *   <li><strong>value-embedded</strong> credentials, which carry a secret inside a structured value
 *       (a {@code k=v} header list, a JSON document), mask <em>per header / per field</em> — the
 *       surrounding, non-secret configuration must survive intact.</li>
 * </ul>
 *
 * <p>Four distinct failures are asserted, because masking a secret is easy to get half-right:
 * <ol>
 *   <li><strong>Leak</strong> — the real secret appearing anywhere in the serialized configuration.</li>
 *   <li><strong>Over-masking</strong> — a partial mask swallowing the non-secret configuration around
 *       it, so an operator can no longer read back the headers/backends they configured.</li>
 *   <li><strong>Round-trip destruction</strong> — a client doing GET-then-PUT of the whole blob writing
 *       the literal mask back over a working credential. This is the classic bug in the masking pattern:
 *       the secret does not leak, it is silently destroyed instead.</li>
 *   <li><strong>Broken functionality</strong> — masking that also blocks the write path, leaving the
 *       credential unsettable.</li>
 * </ol>
 *
 * <p>Enumeration is reflective over {@link #WRITE_ONLY_CREDENTIALS} so adding a credential to the list
 * without masking it fails here, and masking one without listing it fails in
 * {@link ConfigurationEnforcementClassificationTest#shouldKeepTheWriteOnlyCredentialListInSyncWithTheMaskingGuard()}.
 */
public class ConfigurationDTOCredentialMaskingTest {

    /**
     * Every configuration property that is settable but whose credential content must be masked on
     * read. Kept in sync with the copy in {@link ConfigurationEnforcementClassificationTest} by a test
     * in that class.
     */
    static final Set<String> WRITE_ONLY_CREDENTIALS = new LinkedHashSet<>(Arrays.asList(
        "llmApiKey",
        "llmBackendsConfig",
        "prometheusRemoteWriteBearerToken",
        "prometheusRemoteWriteBasicAuthPassword",
        "prometheusRemoteWriteHeaders"
    ));

    private static final String REAL_SECRET_PREFIX = "sk-real-secret-do-not-leak-";

    private static final String MASK = ConfigurationProperties.REDACTED_VALUE;

    /**
     * The value-embedded credentials, each mapped to a realistic structured value that carries BOTH a
     * secret and ordinary configuration, and to the exact form {@code GET} must return for it. Asserting
     * the exact masked form (rather than "contains the mask") is what pins partial masking: an
     * implementation that redacted the whole value, dropped the other headers, or reordered the
     * document would fail.
     */
    private static final Map<String, String> EMBEDDED_REAL_VALUES = new LinkedHashMap<>();
    private static final Map<String, String> EMBEDDED_MASKED_VALUES = new LinkedHashMap<>();

    static {
        EMBEDDED_REAL_VALUES.put("llmBackendsConfig",
            "[{\"name\":\"openai\",\"provider\":\"OPENAI\",\"apiKey\":\"" + REAL_SECRET_PREFIX + "llmBackendsConfig\"},"
                + "{\"name\":\"ollama\",\"provider\":\"OLLAMA\",\"baseUrl\":\"http://localhost:11434\"}]");
        EMBEDDED_MASKED_VALUES.put("llmBackendsConfig",
            "[{\"name\":\"openai\",\"provider\":\"OPENAI\",\"apiKey\":\"" + MASK + "\"},"
                + "{\"name\":\"ollama\",\"provider\":\"OLLAMA\",\"baseUrl\":\"http://localhost:11434\"}]");
        EMBEDDED_REAL_VALUES.put("prometheusRemoteWriteHeaders",
            "Authorization=Bearer " + REAL_SECRET_PREFIX + "prometheusRemoteWriteHeaders,X-Scope-OrgID=tenant-a");
        EMBEDDED_MASKED_VALUES.put("prometheusRemoteWriteHeaders",
            "Authorization=" + MASK + ",X-Scope-OrgID=tenant-a");
    }

    private final ConfigurationSerializer serializer = new ConfigurationSerializer(new MockServerLogger());

    // ---------------------------------------------------------------------------------------------
    // 0. the REFLECTIVE guard — the only part of this class that cannot be defeated by forgetting to
    //    update it.
    //
    //    Everything below enumerates WRITE_ONLY_CREDENTIALS, a list somebody has to remember to add to.
    //    clusterFanInPeerAuthToken — a control-plane bearer token granting MUTATE on every peer node —
    //    was disclosed in clear by GET /mockserver/configuration for exactly that reason: nobody added
    //    it to the list, so nothing here ever looked at it. The guard below does not take a list —
    //    it enumerates ConfigurationDTO's own getters, asks ConfigurationProperties which NAMES are
    //    credential-shaped (the SAME predicate GET /mockserver/config and the startup log dump use, so
    //    the two endpoints cannot diverge again), and asserts the real value does not reach the wire.
    // ---------------------------------------------------------------------------------------------

    /**
     * ConfigurationDTO properties whose NAME is credential-shaped under
     * {@link ConfigurationProperties#isSensitivePropertyName(String)} but whose VALUE is not a secret,
     * so {@code GET /mockserver/configuration} deliberately returns it in clear.
     *
     * <p>This is an explicit, per-entry-justified allow-list, not a convenience: the default for a
     * credential-shaped name is to be unreadable, and adding an entry here is a reviewable security
     * decision. The reason string is asserted to be non-trivial so an entry cannot be added silently.
     */
    private static final Map<String, String> READABLE_DESPITE_CREDENTIAL_SHAPED_NAME = new LinkedHashMap<>();

    static {
        READABLE_DESPITE_CREDENTIAL_SHAPED_NAME.put("dashboardAnalyticsKey",
            "a PostHog PROJECT key: an ingest-only (write-only) token designed to be embedded in a "
                + "public web page, granting no read access to anything. The browser dashboard reads it "
                + "back from THIS endpoint to call posthog.init(...) (mockserver-ui/src/lib/analytics.ts "
                + "gatesPass/initAnalytics). The browser must receive this key for analytics to function "
                + "at all, and an ingest-only project key is designed for exactly that exposure, so "
                + "masking it would break the feature while protecting nothing. GET /mockserver/config and "
                + "the startup property-file log dump still mask it: those are diagnostic surfaces whose "
                + "output gets pasted into issues, where even a semi-public key does not belong.");
        READABLE_DESPITE_CREDENTIAL_SHAPED_NAME.put("dataPlaneApiKeyAuthenticationHeader",
            "the NAME of the header that carries the API key, not the key. The credential itself is "
                + "dataPlaneApiKeyAuthenticationValue, which is @JsonIgnore-d. Also allow-listed for the "
                + "same reason in ConfigurationPropertiesRedactionTest.KNOWN_NON_CREDENTIALS.");
        READABLE_DESPITE_CREDENTIAL_SHAPED_NAME.put("privateKeyPath",
            "a filesystem PATH to a private key, not the key material. The secret lives in that file, "
                + "which no endpoint returns, and an operator must be able to read back which key file "
                + "the server was told to use. Same reasoning as llmBackendsConfig's documented path "
                + "shape, and the same treatment as its sibling x509CertificatePath.");
        READABLE_DESPITE_CREDENTIAL_SHAPED_NAME.put("controlPlanePrivateKeyPath",
            "a filesystem PATH to the control-plane private key, not the key material — see "
                + "privateKeyPath above.");
    }

    @Test
    public void shouldNotDiscloseAnyCredentialNamedPropertyOverTheConfigurationControlPlane() throws Exception {
        List<String> properties = credentialShapedStringProperties();

        // A RATCHET, not a smoke test: a regression that stops discovering getters (a rename, a
        // return-type change) must not make this pass vacuously, and the realistic regression drops a
        // FEW properties rather than all of them. Raise this when a credential-shaped property is
        // added; NEVER lower it to make a failure go away.
        assertThat("reflection discovered fewer credential-shaped ConfigurationDTO properties than it "
                + "did when this guard was written — a getter was renamed, retyped or removed, and its "
                + "disclosure is now unguarded",
            properties.size(), greaterThanOrEqualTo(14));

        Configuration configuration = configuration();
        List<String> unsettable = new ArrayList<>();
        for (String property : properties) {
            try {
                setCredentialOn(configuration, property);
            } catch (Exception e) {
                unsettable.add(property + " (" + e + ")");
            }
        }
        assertThat("credential-shaped ConfigurationDTO properties this guard could not set on a "
                + "Configuration, so it could not prove they stay off the wire — give the property a "
                + "Configuration setter, or teach this guard how to populate it: " + unsettable,
            unsettable, is(empty()));

        // the real GET /mockserver/configuration path: Configuration -> ConfigurationDTO -> JSON
        String json = serializer.serialize(configuration);

        List<String> disclosed = new ArrayList<>();
        for (String property : properties) {
            if (json.contains(reflectiveSecretFor(property))) {
                disclosed.add(property);
            }
        }

        assertThat("credential-named configuration properties whose REAL value is returned in clear by "
                + "GET /mockserver/configuration. Make each one unreadable, using the pattern already "
                + "established in ConfigurationDTO: either annotate the getter @JsonIgnore and its setter "
                + "@JsonProperty (write-only — settable over the control plane, absent from the response, "
                + "so a GET-then-PUT cannot destroy it), or return maskCredential(...) from the getter and "
                + "add the matching isMaskedCredential(...) round-trip guard in applyTo() and "
                + "buildObject(). If the value is genuinely NOT a secret, add it to "
                + "READABLE_DESPITE_CREDENTIAL_SHAPED_NAME with the reason. Disclosed: " + disclosed,
            disclosed, is(empty()));
    }

    @Test
    public void shouldNotAllowACredentialShapedPropertyOfATypeTheDisclosureGuardCannotCheck() {
        // the guard above only populates String properties. A credential-named getter returning any
        // other text-bearing type (CharSequence, a collection or a map of them) would be enumerated by
        // nothing and serialized in clear — close that hole at the type level rather than hoping.
        List<String> unchecked = new ArrayList<>();
        for (Method getter : ConfigurationDTO.class.getMethods()) {
            String property = serializedPropertyNameOf(getter);
            if (property == null
                || getter.getReturnType().equals(String.class)
                || !isSensitivePropertyName(property)) {
                continue;
            }
            Class<?> type = getter.getReturnType();
            if (CharSequence.class.isAssignableFrom(type)
                || Collection.class.isAssignableFrom(type)
                || Map.class.isAssignableFrom(type)
                || type.isArray()) {
                unchecked.add(property + " (" + type.getSimpleName() + ")");
            }
        }

        assertThat("credential-named ConfigurationDTO properties whose type can carry text but which the "
                + "disclosure guard cannot populate, so nothing proves they are not serialized in clear — "
                + "extend credentialShapedStringProperties()/the guard to cover the type: " + unchecked,
            unchecked, is(empty()));
    }

    @Test
    public void shouldJustifyEveryEntryInTheReadableAllowList() {
        Set<String> serializedProperties = new TreeSet<>();
        for (Method getter : ConfigurationDTO.class.getMethods()) {
            String property = serializedPropertyNameOf(getter);
            if (property != null) {
                serializedProperties.add(property);
            }
        }

        List<String> unjustified = new ArrayList<>();
        for (Map.Entry<String, String> entry : READABLE_DESPITE_CREDENTIAL_SHAPED_NAME.entrySet()) {
            if (!serializedProperties.contains(entry.getKey())) {
                // the property is gone (renamed, removed, or now @JsonIgnore-d via a different getter
                // shape); a dangling exemption would silently pre-approve a future property reusing the
                // name, which is how an allow-list turns into a hole
                unjustified.add(entry.getKey() + " (no such serialized ConfigurationDTO property — "
                    + "remove the entry)");
            } else if (!isSensitivePropertyName(entry.getKey())) {
                // the property no longer trips the credential-name rule, so the exemption is dead
                // weight that would silently cover a future property of the same name
                unjustified.add(entry.getKey() + " (no longer credential-shaped — remove the entry)");
            } else if (entry.getValue() == null || entry.getValue().length() < 60) {
                unjustified.add(entry.getKey() + " (reason too thin to review)");
            }
        }

        assertThat("entries in READABLE_DESPITE_CREDENTIAL_SHAPED_NAME that are stale or unjustified — "
                + "each exemption publishes a credential-named value in clear and must carry a reason a "
                + "reviewer can check: " + unjustified,
            unjustified, is(empty()));
    }

    @Test
    public void shouldRouteEveryMaskedCredentialShapedPropertyThroughTheRoundTripGuardToo() {
        // A credential-shaped property masked with maskCredential(...) but missing from
        // WRITE_ONLY_CREDENTIALS gets the leak guard above and NOTHING else — so a GET-then-PUT could
        // still write "***REDACTED***" over the live credential. That failure is invisible until
        // outbound auth breaks, which is why it is asserted separately from the leak.
        List<String> unguarded = new ArrayList<>();
        for (Method getter : ConfigurationDTO.class.getMethods()) {
            String property = serializedPropertyNameOf(getter);
            if (property == null
                || !getter.getReturnType().equals(String.class)
                || !isSensitivePropertyName(property)
                || READABLE_DESPITE_CREDENTIAL_SHAPED_NAME.containsKey(property)
                || getter.isAnnotationPresent(JsonIgnore.class)
                || WRITE_ONLY_CREDENTIALS.contains(property)) {
                continue;
            }
            unguarded.add(property);
        }

        assertThat("credential-shaped properties that are serialized (not @JsonIgnore-d) but are absent "
                + "from WRITE_ONLY_CREDENTIALS, so no test proves a GET-then-PUT leaves the real "
                + "credential intact — add them to WRITE_ONLY_CREDENTIALS (and to the copy in "
                + "ConfigurationEnforcementClassificationTest): " + unguarded,
            unguarded, is(empty()));
    }

    @Test
    public void shouldExplainEveryHandListedCredentialTheReflectiveGuardCannotRediscover() {
        // reconciles the hand-maintained list with reflection: an entry is legitimate only because its
        // NAME betrays it (so the reflective guard covers it too) or because it carries a credential
        // EMBEDDED in an otherwise-readable structured value, which no name rule can detect
        List<String> unexplained = new ArrayList<>();
        for (String credential : WRITE_ONLY_CREDENTIALS) {
            if (!isSensitivePropertyName(credential)
                && !isEmbeddedValueCredential(credential)) {
                unexplained.add(credential);
            }
        }

        assertThat("hand-listed write-only credentials that neither have a credential-shaped name nor a "
                + "documented embedded-credential value — if the name betrays it the reflective guard "
                + "already covers it; if it embeds a credential, give it entries in EMBEDDED_REAL_VALUES "
                + "/ EMBEDDED_MASKED_VALUES so its partial masking is actually asserted: " + unexplained,
            unexplained, is(empty()));
    }

    /**
     * Every ConfigurationDTO property that Jackson serializes, whose name is credential-shaped, and
     * which is not explicitly allow-listed as readable — discovered by reflection over the DTO's own
     * getters, never from a list, because a list is what let two credentials leak.
     */
    private static List<String> credentialShapedStringProperties() {
        Set<String> properties = new TreeSet<>();
        for (Method getter : ConfigurationDTO.class.getMethods()) {
            String property = serializedPropertyNameOf(getter);
            if (property == null || !getter.getReturnType().equals(String.class)) {
                continue;
            }
            if (isSensitivePropertyName(property)
                && !READABLE_DESPITE_CREDENTIAL_SHAPED_NAME.containsKey(property)) {
                properties.add(property);
            }
        }
        return new ArrayList<>(properties);
    }

    /**
     * The JSON property name a no-arg getter contributes, or {@code null} when it contributes none —
     * an inherited {@code Object} method, or one of the {@code *RawValue} in-process accessors, which
     * are @JsonIgnore-d by construction and asserted so by
     * {@link #shouldExposeRealCredentialValuesOnlyThroughJsonIgnoredRawAccessors()}.
     */
    private static String serializedPropertyNameOf(Method getter) {
        if (getter.getParameterCount() != 0
            || getter.isSynthetic()
            || getter.getDeclaringClass().equals(Object.class)
            || !getter.getName().startsWith("get")
            || getter.getName().length() <= 3) {
            return null;
        }
        // @JsonProperty("someOtherName") renames the wire property, and BOTH guards key off the name:
        // the credential-shape rule that decides whether it is covered, and the system-property key the
        // diagnostic surfaces mask by. Deriving the name from the METHOD alone lets a rename defeat the
        // guard in either direction — a credential renamed to an innocuous wire name silently drops out,
        // and an innocuous property renamed to a credential-shaped one silently joins. Every
        // @JsonProperty in ConfigurationDTO is bare today; this makes that a property of the code rather
        // than an assumption.
        JsonProperty annotation = getter.getAnnotation(JsonProperty.class);
        String property = annotation != null && !annotation.value().isEmpty()
            ? annotation.value()
            : Introspector.decapitalize(getter.getName().substring(3));
        return property.endsWith("RawValue") ? null : property;
    }

    @Test
    public void shouldNotDiscloseTheClusterFanInPeerAuthTokenAndShouldSurviveAGetThenPut() throws Exception {
        // named regression for the leak the reflective guard above found: GET /mockserver/configuration
        // returned this control-plane bearer token verbatim, so a read-only control-plane principal
        // could lift a credential granting MUTATE on every peer node
        String token = "Bearer eyJ-cluster-fan-in-token";
        Configuration live = configuration().clusterFanInPeerAuthToken(token);

        String json = serializer.serialize(live);

        assertThat("the cluster control-plane bearer token must not be disclosed",
            json, not(containsString(token)));
        assertThat("nor should the property appear at all — it is write-only",
            json, not(containsString("clusterFanInPeerAuthToken")));

        // GET-then-PUT: the body a client echoes back simply lacks the property, so the held token stands
        applyJsonTo(json, live);
        assertThat("a GET-then-PUT round trip must not destroy the token",
            live.clusterFanInPeerAuthToken(), is(token));

        // and it is fixed at startup: the control plane cannot replace it
        Configuration target = configuration().clusterFanInPeerAuthToken("Bearer startup-token");
        applyJsonTo("{\"clusterFanInPeerAuthToken\":\"" + token + "\"}", target);
        assertThat("PUT /mockserver/configuration must not change the token",
            target.clusterFanInPeerAuthToken(), is("Bearer startup-token"));
    }

    @Test
    public void shouldKeepTheDashboardAnalyticsKeyReadableSoTheDashboardCanInitialiseAnalytics() throws Exception {
        // deliberate exemption, asserted so a well-meaning "mask everything ...Key" change fails here
        // instead of silently switching dashboard analytics off — see
        // READABLE_DESPITE_CREDENTIAL_SHAPED_NAME and mockserver-ui/src/lib/analytics.ts
        String key = "phc_project_ingest_key";
        Configuration live = configuration().dashboardAnalyticsKey(key);

        assertThat("the browser dashboard reads this back to call posthog.init(...)",
            serializer.serialize(live), containsString(key));

        Configuration target = configuration();
        applyJsonTo("{\"dashboardAnalyticsKey\":\"" + key + "\"}", target);
        assertThat(target.dashboardAnalyticsKey(), is(key));
    }

    /**
     * Put a value carrying this property's unique marker onto the Configuration.
     *
     * <p>Some credential properties are validated on write — {@code forwardProxyPrivateKey} is a PEM
     * file location and its setter rejects a value that is not a readable file. For those the marker is carried by the FILE NAME of a real temporary
     * file, so the value is accepted and the marker is still unique in the serialized output. Detected
     * by attempting the plain value first rather than by naming the properties, so a property that
     * gains or loses validation needs no change here.
     */
    private static String setCredentialOn(Configuration configuration, String property) throws Exception {
        String value = valueTheSetterAccepts(property, reflectiveSecretFor(property));
        Configuration.class.getMethod(property, String.class).invoke(configuration, value);
        return value;
    }

    /**
     * A value carrying {@code marker} that this property's setter will actually accept — the marker
     * itself, or, for a property validated as a readable file, the path of a real temporary file whose
     * NAME carries the marker. Probed rather than named per property, so a property that gains or loses
     * validation needs no change here.
     */
    private static String valueTheSetterAccepts(String property, String marker) throws Exception {
        Method setter = Configuration.class.getMethod(property, String.class);
        try {
            setter.invoke(configuration(), marker);
            return marker;
        } catch (java.lang.reflect.InvocationTargetException rejected) {
            java.io.File file = java.io.File.createTempFile(marker + "-", ".pem");
            file.deleteOnExit();
            return file.getAbsolutePath();
        }
    }

    /** Distinct from {@link #REAL_SECRET_PREFIX} so a leak report names which guard found it. */
    private static String reflectiveSecretFor(String property) {
        return "reflective-guard-secret-do-not-leak-" + property;
    }

    // ---------------------------------------------------------------------------------------------
    // 1. the real secret must never reach the wire
    // ---------------------------------------------------------------------------------------------

    @Test
    public void shouldNotSerializeAnyRealCredentialValue() throws Exception {
        Configuration configuration = configurationWithEveryCredentialSet();

        String json = serializer.serialize(configuration);

        List<String> leaked = new ArrayList<>();
        for (String credential : WRITE_ONLY_CREDENTIALS) {
            if (json.contains(realSecretFor(credential))) {
                leaked.add(credential);
            }
        }

        assertThat("write-only credentials whose REAL secret appears in the serialized configuration — "
                + "GET /mockserver/configuration would hand the secret to any reader: " + leaked,
            leaked, is(empty()));
    }

    @Test
    public void shouldSerializeTheSharedRedactionMaskInPlaceOfEachCredential() throws Exception {
        Configuration configuration = configurationWithEveryCredentialSet();

        String json = serializer.serialize(configuration);

        for (String credential : WRITE_ONLY_CREDENTIALS) {
            assertThat("credential " + credential + " should be present in the configuration JSON",
                json, containsString(credential));
        }
        assertThat("the mask must be the shared ConfigurationProperties.REDACTED_VALUE, not a locally "
                + "invented token", json, containsString(MASK));
    }

    @Test
    public void shouldMaskEachCredentialOnTheDtoGetterThatJacksonSerializes() throws Exception {
        ConfigurationDTO dto = new ConfigurationDTO(configurationWithEveryCredentialSet());

        List<String> wrong = new ArrayList<>();
        for (String credential : WRITE_ONLY_CREDENTIALS) {
            Object value = dtoGetter(credential).invoke(dto);
            if (!expectedMaskFor(credential).equals(value)) {
                wrong.add(credential + " (getter returned <" + value + "> but should return <"
                    + expectedMaskFor(credential) + ">)");
            }
        }

        assertThat("write-only credential getters that did NOT return the expected masked form — Jackson "
                + "serializes exactly these getters, so an unmasked one is a live secret leak and an "
                + "over-masked one destroys readable configuration: " + wrong,
            wrong, is(empty()));
    }

    @Test
    public void shouldNotAdvertiseAMaskForACredentialThatIsNotSet() throws Exception {
        // an unset credential must serialize as absent, not as "***REDACTED***" — otherwise the config
        // dump claims a secret exists where none does, and a GET-then-PUT would look like a real write
        Configuration configuration = configuration();
        ConfigurationDTO dto = new ConfigurationDTO(configuration);

        for (String credential : WRITE_ONLY_CREDENTIALS) {
            Object effective = configurationGetter(credential).invoke(configuration);
            if (effective != null && !"".equals(effective)) {
                // the ambient environment (a property file / env var / system property) supplies a real
                // value for this credential, so "unset" cannot be observed here — masking it IS correct
                continue;
            }
            Object masked = dtoGetter(credential).invoke(dto);
            if (isEmbeddedValueCredential(credential)) {
                assertThat("an unset structured value carries no embedded secret, so " + credential
                        + " must serialize unchanged rather than claim a secret exists", masked, is(effective));
            } else {
                assertThat("unset credential " + credential + " should mask to null, not to the mask literal",
                    masked, is(nullValue()));
            }
        }
    }

    @Test
    public void embeddedValueCredentialsAreRedactedFieldByFieldOnRead() throws Exception {
        Configuration configuration = configuration()
            .llmBackendsConfig(EMBEDDED_REAL_VALUES.get("llmBackendsConfig"))
            .prometheusRemoteWriteHeaders(EMBEDDED_REAL_VALUES.get("prometheusRemoteWriteHeaders"));

        String json = serializer.serialize(configuration);

        assertThat("the apiKey embedded in llmBackendsConfig must not be disclosed by GET /mockserver/configuration",
            json, not(containsString(REAL_SECRET_PREFIX + "llmBackendsConfig")));
        assertThat("the credential embedded in prometheusRemoteWriteHeaders must not be disclosed by "
                + "GET /mockserver/configuration", json, not(containsString(REAL_SECRET_PREFIX + "prometheusRemoteWriteHeaders")));

        // only the secret is removed — the rest of each value is ordinary configuration an operator
        // must still be able to read back
        assertThat("the non-secret backends configuration must survive redaction",
            json, containsString("http://localhost:11434"));
        assertThat("headers that are not credentials must survive redaction",
            json, containsString("X-Scope-OrgID=tenant-a"));
    }

    @Test
    public void shouldNotCorruptTheStoredValueWhenMaskingEmbeddedSecretsOnRead() throws Exception {
        // redaction is a READ-side transform: it rewrites the value handed to the reader and must never
        // write the masked form back onto the Configuration it read from. The round-trip test below
        // would still pass if it did (it re-reads through the same masking getter), so assert the
        // stored value directly.
        Configuration configuration = configuration()
            .llmBackendsConfig(EMBEDDED_REAL_VALUES.get("llmBackendsConfig"))
            .prometheusRemoteWriteHeaders(EMBEDDED_REAL_VALUES.get("prometheusRemoteWriteHeaders"));

        serializer.serialize(configuration);

        assertThat("serializing must not mutate the llmBackendsConfig held by the Configuration",
            configuration.llmBackendsConfig(), is(EMBEDDED_REAL_VALUES.get("llmBackendsConfig")));
        assertThat("serializing must not mutate the prometheusRemoteWriteHeaders held by the Configuration",
            configuration.prometheusRemoteWriteHeaders(), is(EMBEDDED_REAL_VALUES.get("prometheusRemoteWriteHeaders")));
        assertThat("the real backend key must still be usable in-process after a read",
            configuration.llmBackendsConfig(), containsString(REAL_SECRET_PREFIX + "llmBackendsConfig"));
        assertThat("the real header credential must still be usable in-process after a read",
            configuration.prometheusRemoteWriteHeaders(), containsString(REAL_SECRET_PREFIX + "prometheusRemoteWriteHeaders"));
    }

    // ---------------------------------------------------------------------------------------------
    // 2. the round-trip guard: a masked GET echoed back by a PUT must not destroy the credential
    // ---------------------------------------------------------------------------------------------

    @Test
    public void shouldNotOverwriteRealCredentialsWhenAMaskedConfigurationIsAppliedBack() throws Exception {
        Configuration live = configurationWithEveryCredentialSet();

        // GET: serialize the live configuration — every credential comes back masked
        String maskedJson = serializer.serialize(live);
        assertThat(maskedJson, containsString(MASK));

        // PUT: apply that very same body straight back, exactly as a GET-then-PUT client would
        applyJsonTo(maskedJson, live);

        List<String> destroyed = new ArrayList<>();
        for (String credential : WRITE_ONLY_CREDENTIALS) {
            Object value = configurationGetter(credential).invoke(live);
            if (!realValueFor(credential).equals(value)) {
                destroyed.add(credential + " (became <" + value + ">)");
            }
        }

        assertThat("write-only credentials clobbered by applying a masked configuration body back — the "
                + "secret did not leak, it was silently DESTROYED, which breaks outbound auth: " + destroyed,
            destroyed, is(empty()));
    }

    @Test
    public void shouldNotBuildAConfigurationCarryingTheMaskLiteralAsACredential() throws Exception {
        // the buildObject() path (deserialize of a masked body) must likewise refuse the mask literal
        Configuration live = configurationWithEveryCredentialSet();
        String maskedJson = serializer.serialize(live);

        Configuration rebuilt = serializer.deserialize(maskedJson);

        List<String> poisoned = new ArrayList<>();
        for (String credential : WRITE_ONLY_CREDENTIALS) {
            Object value = configurationGetter(credential).invoke(rebuilt);
            if (value != null && String.valueOf(value).contains(MASK)) {
                poisoned.add(credential + " (became <" + value + ">)");
            }
        }

        assertThat("configuration rebuilt from a masked body carries the literal mask as a credential — "
                + "outbound auth would send \"" + MASK + "\": " + poisoned,
            poisoned, is(empty()));
    }

    // ---------------------------------------------------------------------------------------------
    // 2a. the EDITED round trip for a WHOLE-VALUE credential: the operator typed text around the mask
    //     rather than echoing it verbatim, so the value CARRIES the mask without BEING it.
    //
    //     An equals-only round-trip guard reads that as a real new secret and writes it through, which
    //     destroys the held credential AND makes the literal "***REDACTED***" the outbound credential.
    //     That is the same defect closed for value-embedded credentials (2b/2c below), one field over,
    //     so the notion of "masked" must be the same one: CONTAINS the mask, not EQUALS it.
    //
    //     The legitimate cases must keep working, and are covered by:
    //       - unedited round trip (value is exactly the mask) leaves the credential intact —
    //         shouldNotOverwriteRealCredentialsWhenAMaskedConfigurationIsAppliedBack
    //       - a real new credential over the control plane applies —
    //         shouldStillApplyRealCredentialValuesSuppliedOverTheControlPlane (applyTo) and
    //         shouldStillBuildAFreshConfigurationCarryingARealWholeValueCredential (buildObject)
    //       - clearing a credential stays possible — shouldStillAllowAWholeValueCredentialToBeCleared
    // ---------------------------------------------------------------------------------------------

    /**
     * Each whole-value credential mapped to a realistic value that CARRIES the mask without BEING it:
     * a key prefix typed in front of it, an auth scheme prepended to it, a suffix welded onto it. None
     * of these is a real credential, so none of them may reach the live configuration.
     */
    private static final Map<String, String> MASK_CARRYING_WHOLE_VALUES = new LinkedHashMap<>();

    static {
        MASK_CARRYING_WHOLE_VALUES.put("llmApiKey", "sk-" + MASK);
        MASK_CARRYING_WHOLE_VALUES.put("prometheusRemoteWriteBearerToken", "Bearer " + MASK);
        MASK_CARRYING_WHOLE_VALUES.put("prometheusRemoteWriteBasicAuthPassword", MASK + "-rotated");
    }

    @Test
    public void shouldCoverEveryWholeValueCredentialWithAMaskCarryingInput() {
        Set<String> wholeValue = new LinkedHashSet<>(WRITE_ONLY_CREDENTIALS);
        wholeValue.removeAll(EMBEDDED_REAL_VALUES.keySet());

        assertThat("every WHOLE-VALUE write-only credential needs a mask-carrying input below, or the "
                + "guards in this section silently prove nothing about it",
            MASK_CARRYING_WHOLE_VALUES.keySet(), is(wholeValue));
    }

    @Test
    public void shouldLeaveTheHeldCredentialUntouchedWhenAWholeValueMerelyContainsTheMask() throws Exception {
        List<String> destroyed = new ArrayList<>();
        for (Map.Entry<String, String> entry : MASK_CARRYING_WHOLE_VALUES.entrySet()) {
            String credential = entry.getKey();
            Configuration live = configuration();
            Configuration.class.getMethod(credential, String.class).invoke(live, realValueFor(credential));

            applyJsonTo(bodyWith(credential, entry.getValue()), live);

            Object value = configurationGetter(credential).invoke(live);
            if (!realValueFor(credential).equals(value)) {
                destroyed.add(credential + " (supplied <" + entry.getValue() + ">, became <" + value + ">)");
            }
        }

        assertThat("a whole-value credential CARRYING the mask was written through by applyTo — the "
                + "working credential is destroyed and \"" + MASK + "\" becomes the outbound one: " + destroyed,
            destroyed, is(empty()));
    }

    @Test
    public void shouldLeaveAWholeValueCredentialUnsetWhenTheValueSuppliedCarriesTheMask() throws Exception {
        List<String> poisoned = new ArrayList<>();
        for (Map.Entry<String, String> entry : MASK_CARRYING_WHOLE_VALUES.entrySet()) {
            String credential = entry.getKey();
            ConfigurationDTO dto = ObjectMapperFactory
                .createObjectMapper()
                .readValue(bodyWith(credential, entry.getValue()), ConfigurationDTO.class);

            // buildObject() has no held configuration to fall back on, so the field must be left UNSET
            // (letting the static store resolve it) rather than pinned to the mask — or to an empty
            // value, which would shadow a property-file or environment credential just as badly
            Object value = instanceField(dto.buildObject(), credential);
            if (value != null) {
                poisoned.add(credential + " (supplied <" + entry.getValue() + ">, became <" + value + ">)");
            }
        }

        assertThat("a whole-value credential CARRYING the mask was written into a freshly built "
                + "Configuration — it must be left unset so the static store still resolves: " + poisoned,
            poisoned, is(empty()));
    }

    @Test
    public void shouldStillBuildAFreshConfigurationCarryingARealWholeValueCredential() throws Exception {
        // the other half of the guard above: refusing mask-carrying values must not refuse real ones
        List<String> notApplied = new ArrayList<>();
        for (String credential : MASK_CARRYING_WHOLE_VALUES.keySet()) {
            ConfigurationDTO dto = ObjectMapperFactory
                .createObjectMapper()
                .readValue(bodyWith(credential, realValueFor(credential)), ConfigurationDTO.class);

            Object value = instanceField(dto.buildObject(), credential);
            if (!realValueFor(credential).equals(value)) {
                notApplied.add(credential + " (was <" + value + ">)");
            }
        }

        assertThat("real whole-value credentials NOT written by buildObject — the round-trip guard has "
                + "over-reached and broken the write path: " + notApplied,
            notApplied, is(empty()));
    }

    @Test
    public void shouldStillAllowAWholeValueCredentialToBeCleared() throws Exception {
        // an empty value carries no mask, so it is a real instruction: clear the credential
        List<String> notCleared = new ArrayList<>();
        for (String credential : MASK_CARRYING_WHOLE_VALUES.keySet()) {
            Configuration live = configuration();
            Configuration.class.getMethod(credential, String.class).invoke(live, realValueFor(credential));

            applyJsonTo(bodyWith(credential, ""), live);

            Object value = instanceField(live, credential);
            if (!"".equals(value)) {
                notCleared.add(credential + " (was <" + value + ">)");
            }
        }

        assertThat("write-only credentials that could no longer be CLEARED over the control plane: "
                + notCleared, notCleared, is(empty()));
    }

    // ---------------------------------------------------------------------------------------------
    // 2b. the mixed case: only SOME fields of a structured value are masked. The masked ones must keep
    //     their real values and the edited ones must actually be written — getting either half wrong
    //     is invisible until outbound auth breaks or an edit is silently ignored.
    // ---------------------------------------------------------------------------------------------

    @Test
    public void shouldRestoreOnlyTheMaskedHeaderAndStillApplyTheEditedOnes() throws Exception {
        Configuration live = configuration().prometheusRemoteWriteHeaders(
            "Authorization=Bearer " + REAL_SECRET_PREFIX + "held,X-Scope-OrgID=tenant-a");

        // the operator edited one header and added another, leaving the masked one exactly as GET showed it
        applyJsonTo("{\"prometheusRemoteWriteHeaders\":\"Authorization=" + MASK
            + ",X-Scope-OrgID=tenant-b,X-Custom=abc\"}", live);

        assertThat(live.prometheusRemoteWriteHeaders(),
            is("Authorization=Bearer " + REAL_SECRET_PREFIX + "held,X-Scope-OrgID=tenant-b,X-Custom=abc"));
    }

    @Test
    public void shouldRefuseTheWholeListWhenAMaskedHeaderHasNoRealValueHeld() throws Exception {
        // dropping just the unresolvable header (what this did before) writes a list whose credential is
        // simply GONE — the mask does not leak, but outbound auth breaks and the PUT answers 200 OK.
        // Refusing the whole value keeps whatever is held and tells the operator.
        Configuration live = configuration().prometheusRemoteWriteHeaders("");

        applyJsonTo("{\"prometheusRemoteWriteHeaders\":\"Api-Key=" + MASK + ",X-Tenant=acme\"}", live);

        assertThat(live.prometheusRemoteWriteHeaders(), is(""));
    }

    @Test
    public void shouldResolveAMaskedHeaderWhoseNameTheOperatorReCased() throws Exception {
        // HTTP header names are case-insensitive, so re-casing one is a legitimate edit. Looking the
        // held value up case-sensitively found nothing, and the header — the credential — was dropped
        // from a list that was then written, with no warning.
        Configuration live = configuration().prometheusRemoteWriteHeaders(
            "Authorization=Bearer " + REAL_SECRET_PREFIX + "held,X-B=2");

        applyJsonTo("{\"prometheusRemoteWriteHeaders\":\"authorization=" + MASK + ",X-B=3\"}", live);

        assertThat(live.prometheusRemoteWriteHeaders(),
            containsString(REAL_SECRET_PREFIX + "held"));
        assertThat(live.prometheusRemoteWriteHeaders(),
            is("authorization=Bearer " + REAL_SECRET_PREFIX + "held,X-B=3"));
    }

    @Test
    public void shouldKeepBothCredentialsWhenTwoHeldHeaderNamesDifferOnlyInCase() throws Exception {
        // the consumer (PrometheusRemoteWriteExporter#parseHeaders) is case-SENSITIVE and applies its
        // result additively, so these are two headers and both are sent. Folding them together to
        // resolve the mask case-insensitively would restore one credential onto both names and destroy
        // the other — silently, with the PUT answering 200 OK.
        Configuration live = configuration().prometheusRemoteWriteHeaders(
            "X-Api-Key=" + REAL_SECRET_PREFIX + "A,x-api-key=" + REAL_SECRET_PREFIX + "B");

        applyJsonTo("{\"prometheusRemoteWriteHeaders\":\"X-Api-Key=" + MASK + ",x-api-key=" + MASK
            + ",X-Env=prod\"}", live);

        assertThat(live.prometheusRemoteWriteHeaders(),
            is("X-Api-Key=" + REAL_SECRET_PREFIX + "A,x-api-key=" + REAL_SECRET_PREFIX + "B,X-Env=prod"));
        assertThat("the credential held under the upper-cased name must survive",
            live.prometheusRemoteWriteHeaders(), containsString(REAL_SECRET_PREFIX + "A"));
    }

    @Test
    public void shouldResolveEachCaseSpellingSeparatelyWhenOnlyOneOfThemIsSentBack() throws Exception {
        Configuration live = configuration().prometheusRemoteWriteHeaders(
            "X-Api-Key=" + REAL_SECRET_PREFIX + "A,x-api-key=" + REAL_SECRET_PREFIX + "B");

        applyJsonTo("{\"prometheusRemoteWriteHeaders\":\"x-api-key=" + MASK + ",X-Env=prod\"}", live);

        assertThat("the exact name must select ITS value, not the other spelling's",
            live.prometheusRemoteWriteHeaders(), is("x-api-key=" + REAL_SECRET_PREFIX + "B,X-Env=prod"));
    }

    @Test
    public void shouldRefuseWhenAMaskedHeaderNameMatchesTwoHeldNamesDifferingOnlyInCase() throws Exception {
        String held = "X-Api-Key=" + REAL_SECRET_PREFIX + "A,x-api-key=" + REAL_SECRET_PREFIX + "B";
        Configuration live = configuration().prometheusRemoteWriteHeaders(held);

        // a THIRD casing matches neither exactly, and the mask could stand for either held value
        applyJsonTo("{\"prometheusRemoteWriteHeaders\":\"X-API-KEY=" + MASK + ",X-Env=prod\"}", live);

        assertThat(live.prometheusRemoteWriteHeaders(), is(held));
    }

    @Test
    public void shouldRefuseWhenTwoIncomingMaskedHeadersWouldResolveToTheSameHeldValue() throws Exception {
        // the inverse: only one spelling is held, so a case-insensitive fallback would hand the SAME
        // credential to both names — fabricating a second credential header the operator never had
        String held = "X-Api-Key=" + REAL_SECRET_PREFIX + "A";
        Configuration live = configuration().prometheusRemoteWriteHeaders(held);

        applyJsonTo("{\"prometheusRemoteWriteHeaders\":\"X-Api-Key=" + MASK + ",x-api-key=" + MASK + "\"}", live);

        assertThat(live.prometheusRemoteWriteHeaders(), is(held));
    }

    @Test
    public void shouldRefuseANewCredentialTypedOverTheMaskRatherThanWeldItToTheOldOne() throws Exception {
        // "***REDACTED***-my-new-key" reads as an operator typing a new credential over the mask.
        // Splitting it as "mask + appended text" persists OLD-REAL-my-new-key — neither the credential
        // that was held nor the one that was intended.
        Configuration live = configuration().prometheusRemoteWriteHeaders(
            "Api-Key=" + REAL_SECRET_PREFIX + "held");

        applyJsonTo("{\"prometheusRemoteWriteHeaders\":\"Api-Key=" + MASK + "-my-new-key\"}", live);

        assertThat(live.prometheusRemoteWriteHeaders(), is("Api-Key=" + REAL_SECRET_PREFIX + "held"));
        assertThat(live.prometheusRemoteWriteHeaders(), not(containsString("my-new-key")));
        assertThat(live.prometheusRemoteWriteHeaders(), not(containsString(MASK)));
    }

    @Test
    public void shouldRestoreOnlyTheMaskedBackendApiKeyAndStillApplyTheEditedOnes() throws Exception {
        Configuration live = configuration().llmBackendsConfig(
            "[{\"name\":\"openai\",\"provider\":\"OPENAI\",\"apiKey\":\"" + REAL_SECRET_PREFIX + "held\"},"
                + "{\"name\":\"azure\",\"provider\":\"OPENAI\",\"apiKey\":\"old-azure-key\"}]");

        // openai's key was only ever seen masked; azure's is rotated to a real new value
        applyJsonTo("{\"llmBackendsConfig\":\"[{\\\"name\\\":\\\"openai\\\",\\\"provider\\\":\\\"OPENAI\\\","
            + "\\\"apiKey\\\":\\\"" + MASK + "\\\"},{\\\"name\\\":\\\"azure\\\",\\\"provider\\\":\\\"OPENAI\\\","
            + "\\\"apiKey\\\":\\\"new-azure-key\\\"}]\"}", live);

        assertThat("the masked backend key must keep its held value and the rotated one must be applied",
            live.llmBackendsConfig(),
            is("[{\"name\":\"openai\",\"provider\":\"OPENAI\",\"apiKey\":\"" + REAL_SECRET_PREFIX + "held\"},"
                + "{\"name\":\"azure\",\"provider\":\"OPENAI\",\"apiKey\":\"new-azure-key\"}]"));
    }

    @Test
    public void shouldLeaveTheHeldHeaderListUntouchedWhenNoMaskedHeaderCanBeResolved() throws Exception {
        // every incoming header is masked and none is held, so nothing survives the merge. Writing the
        // empty result would PIN "" on the instance, whose getter then prefers it over the static
        // store — silently suppressing a property-file or environment value. Leave it unset instead.
        Configuration live = configuration();

        applyJsonTo("{\"prometheusRemoteWriteHeaders\":\"Api-Key=" + MASK + ",Authorization=" + MASK + "\"}", live);

        assertThat(instanceField(live, "prometheusRemoteWriteHeaders"), is(nullValue()));
    }

    @Test
    public void shouldRestoreTheCredentialTheConsumerWouldActuallyHaveUsedWhenAHeaderNameRepeats() throws Exception {
        // PrometheusRemoteWriteExporter#parseHeaders is last-wins, so "second" is the live credential;
        // restoring "first" would leave the secret un-leaked but the outbound auth silently wrong
        Configuration live = configuration().prometheusRemoteWriteHeaders("Api-Key=first,Api-Key=second");

        applyJsonTo("{\"prometheusRemoteWriteHeaders\":\"Api-Key=" + MASK + ",X-Tenant=acme\"}", live);

        assertThat(live.prometheusRemoteWriteHeaders(), is("Api-Key=second,X-Tenant=acme"));
    }

    @Test
    public void shouldMaskTheWholeOfAHeaderValueContainingAComma() throws Exception {
        // the k=v,k2=v2 format cannot escape a comma, so the tail reads as a separate entry — masking
        // only up to the comma would publish the rest of the secret
        String headers = "Authorization=Bearer " + REAL_SECRET_PREFIX + "head,TAIL-OF-THE-SECRET,X-Tenant=acme";
        Configuration live = configuration().prometheusRemoteWriteHeaders(headers);

        assertThat(new ConfigurationDTO(live).getPrometheusRemoteWriteHeaders(),
            is("Authorization=" + MASK + ",X-Tenant=acme"));
        assertThat(serializer.serialize(live), not(containsString("TAIL-OF-THE-SECRET")));

        // and the whole value, comma included, survives an unedited round trip
        applyJsonTo(serializer.serialize(live), live);
        assertThat(live.prometheusRemoteWriteHeaders(), is(headers));
    }

    @Test
    public void shouldNotWriteTheMaskAsAHeaderValueWhenAMaskedHeaderCarriesACommaTail() throws Exception {
        // an =-less segment is read as the tail of the preceding header value (the format cannot escape
        // a comma), so the incoming entry reads as Api-Key=***REDACTED***,junk — which is NOT equal to
        // the mask. Writing it verbatim would destroy the real key AND make the literal mask the
        // outbound credential: outbound auth breaks and a fake secret is persisted.
        Configuration live = configuration().prometheusRemoteWriteHeaders(
            "Api-Key=" + REAL_SECRET_PREFIX + "held,X-B=2");

        applyJsonTo("{\"prometheusRemoteWriteHeaders\":\"Api-Key=" + MASK + ",junk,X-B=2\"}", live);

        assertThat("the real credential must survive a masked header carrying a comma tail",
            live.prometheusRemoteWriteHeaders(), containsString(REAL_SECRET_PREFIX + "held"));
        assertThat("the literal mask must never be persisted as the outbound credential",
            live.prometheusRemoteWriteHeaders(), not(containsString(MASK)));
        assertThat(live.prometheusRemoteWriteHeaders(),
            is("Api-Key=" + REAL_SECRET_PREFIX + "held,junk,X-B=2"));
    }

    @Test
    public void shouldLeaveTheHeldHeaderListUntouchedWhenAMaskIsBuriedInsideAHeaderValue() throws Exception {
        // "Bearer ***REDACTED***" is not a value redaction ever produced (a masked header value is
        // replaced WHOLE), so there is nothing it can be resolved against. Writing it would persist the
        // mask; dropping just that header would destroy the credential — leave the held value alone.
        Configuration live = configuration().prometheusRemoteWriteHeaders(
            "Authorization=Bearer " + REAL_SECRET_PREFIX + "held,X-B=2");

        applyJsonTo("{\"prometheusRemoteWriteHeaders\":\"Authorization=Bearer " + MASK + ",X-B=3\"}", live);

        assertThat(live.prometheusRemoteWriteHeaders(),
            is("Authorization=Bearer " + REAL_SECRET_PREFIX + "held,X-B=2"));
        assertThat(live.prometheusRemoteWriteHeaders(), not(containsString(MASK)));
    }

    @Test
    public void shouldLeaveTheHeldBackendsDocumentUntouchedWhenAFieldMerelyContainsTheMask() throws Exception {
        // the JSON-side twin of the header case: restore matches a field value EQUAL to the mask, so a
        // value that merely contains it fell through and was written as supplied
        String held = "[{\"name\":\"openai\",\"provider\":\"OPENAI\",\"apiKey\":\"" + REAL_SECRET_PREFIX + "held\"}]";
        Configuration live = configuration().llmBackendsConfig(held);

        applyJsonTo("{\"llmBackendsConfig\":\"[{\\\"name\\\":\\\"openai\\\",\\\"provider\\\":\\\"OPENAI\\\","
            + "\\\"apiKey\\\":\\\"sk-" + MASK + "\\\"}]\"}", live);

        assertThat("the real backend key must survive a field that merely contains the mask",
            live.llmBackendsConfig(), containsString(REAL_SECRET_PREFIX + "held"));
        assertThat("the literal mask must never be persisted as a backend credential",
            live.llmBackendsConfig(), not(containsString(MASK)));
        assertThat(live.llmBackendsConfig(), is(held));
    }

    @Test
    public void shouldLeaveTheHeldBackendsDocumentUntouchedWhenTheMaskLandsInANonCredentialField() throws Exception {
        // a mask outside a credential-named field is never restored, so without a fail-closed check on
        // the merged document it would be written straight through
        String held = "[{\"name\":\"openai\",\"apiKey\":\"" + REAL_SECRET_PREFIX + "held\"}]";
        Configuration live = configuration().llmBackendsConfig(held);

        applyJsonTo("{\"llmBackendsConfig\":\"[{\\\"name\\\":\\\"openai\\\",\\\"baseUrl\\\":\\\"https://"
            + MASK + "/v1\\\",\\\"apiKey\\\":\\\"" + MASK + "\\\"}]\"}", live);

        assertThat(live.llmBackendsConfig(), not(containsString(MASK)));
        assertThat(live.llmBackendsConfig(), is(held));
    }

    @Test
    public void shouldMaskACredentialFieldWhateverItsJsonType() throws Exception {
        // a secret is not always a string: recursing past a credential-named field would publish an
        // array of tokens or a numeric key untouched
        ConfigurationDTO dto = new ConfigurationDTO(configuration().llmBackendsConfig(
            "{\"tokens\":[\"" + REAL_SECRET_PREFIX + "a\",\"" + REAL_SECRET_PREFIX + "b\"],"
                + "\"apiKey\":9876543210,\"model\":\"gpt-4\"}"));

        assertThat(dto.getLlmBackendsConfig(),
            is("{\"tokens\":\"" + MASK + "\",\"apiKey\":\"" + MASK + "\",\"model\":\"gpt-4\"}"));
    }

    @Test
    public void shouldLeaveAPartiallyMaskedValueUnsetWhenBuildingAFreshConfiguration() throws Exception {
        // buildObject() has no held configuration to restore the masked header from. Storing the
        // reduced value would PIN it, shadowing ConfigurationProperties — whereas a whole-value
        // credential is left unset so the static store resolves. Both must behave the same way.
        ConfigurationDTO dto = ObjectMapperFactory.createObjectMapper().readValue(
            "{\"prometheusRemoteWriteHeaders\":\"Api-Key=" + MASK + ",X-Tenant=acme\","
                + "\"llmBackendsConfig\":\"[{\\\"name\\\":\\\"openai\\\",\\\"apiKey\\\":\\\"" + MASK + "\\\"}]\"}",
            ConfigurationDTO.class);

        Configuration built = dto.buildObject();

        // assert the INSTANCE FIELD is unset, not the effective getter: the getter falls back to the
        // static store, so comparing it against ConfigurationProperties would read shared global state
        // twice and could false-negative if another (parallel) test mutated it in between
        assertThat("a partially-masked header list must be left unset, not pinned to its reduced form",
            instanceField(built, "prometheusRemoteWriteHeaders"), is(nullValue()));
        assertThat("a partially-masked backends document must be left unset, not pinned to its reduced form",
            instanceField(built, "llmBackendsConfig"), is(nullValue()));
    }

    /**
     * The raw value held on the {@link Configuration} instance, {@code null} when the property was
     * never set on it (so its getter falls back to {@link ConfigurationProperties}). Read reflectively
     * because {@code Configuration} exposes only the effective getter.
     */
    private static Object instanceField(Configuration configuration, String name) throws Exception {
        java.lang.reflect.Field field = Configuration.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(configuration);
    }

    @Test
    public void shouldRefuseTheWholeDocumentWhenAMaskedBackendKeyHasNoRealValueHeld() throws Exception {
        Configuration live = configuration().llmBackendsConfig("");

        applyJsonTo("{\"llmBackendsConfig\":\"[{\\\"name\\\":\\\"openai\\\",\\\"provider\\\":\\\"OPENAI\\\","
            + "\\\"apiKey\\\":\\\"" + MASK + "\\\"}]\"}", live);

        assertThat("removing the unresolvable key writes a document with the credential DELETED, which "
                + "breaks the backend as surely as writing the mask would",
            live.llmBackendsConfig(), is(""));
    }

    @Test
    public void shouldRefuseTheWholeDocumentWhenAMaskedBackendIsRenamed() throws Exception {
        // renaming a backend leaves its masked key with no counterpart to restore from. Silently
        // removing the field wrote a document in which openai-2 has no credential at all.
        String held = "[{\"name\":\"openai\",\"apiKey\":\"" + REAL_SECRET_PREFIX + "held\"}]";
        Configuration live = configuration().llmBackendsConfig(held);

        applyJsonTo("{\"llmBackendsConfig\":\"[{\\\"name\\\":\\\"openai-2\\\",\\\"apiKey\\\":\\\""
            + MASK + "\\\"}]\"}", live);

        assertThat(live.llmBackendsConfig(), is(held));
    }

    @Test
    public void shouldRefuseTheWholeDocumentWhenAnUnnamedBackendHasNoHeldCounterpart() throws Exception {
        // matched by INDEX when there is no name: a second unnamed backend has no held element, so its
        // masked key resolved to nothing and was written out as an empty object
        String held = "[{\"apiKey\":\"" + REAL_SECRET_PREFIX + "a\"}]";
        Configuration live = configuration().llmBackendsConfig(held);

        applyJsonTo("{\"llmBackendsConfig\":\"[{\\\"apiKey\\\":\\\"" + MASK + "\\\"},"
            + "{\\\"apiKey\\\":\\\"" + MASK + "\\\"}]\"}", live);

        assertThat(live.llmBackendsConfig(), is(held));
    }

    @Test
    public void shouldNotTransplantAHeldKeyOntoADifferentBackend() throws Exception {
        Configuration live = configuration().llmBackendsConfig(
            "[{\"name\":\"openai\",\"provider\":\"OPENAI\",\"apiKey\":\"" + REAL_SECRET_PREFIX + "openai\"}]");

        // a NEW backend is prepended, so index 0 no longer refers to the held one — matching by
        // position here would hand openai's key to a backend the operator never gave it to. The new
        // backend's masked key cannot be resolved either, so the whole document is refused.
        applyJsonTo("{\"llmBackendsConfig\":\"[{\\\"name\\\":\\\"anthropic\\\",\\\"provider\\\":\\\"ANTHROPIC\\\","
            + "\\\"apiKey\\\":\\\"" + MASK + "\\\"},{\\\"name\\\":\\\"openai\\\",\\\"provider\\\":\\\"OPENAI\\\","
            + "\\\"apiKey\\\":\\\"" + MASK + "\\\"}]\"}", live);

        assertThat("openai's key must never appear under anthropic",
            live.llmBackendsConfig(), not(containsString("\"anthropic\",\"provider\":\"ANTHROPIC\",\"apiKey\"")));
        assertThat(live.llmBackendsConfig(),
            is("[{\"name\":\"openai\",\"provider\":\"OPENAI\",\"apiKey\":\"" + REAL_SECRET_PREFIX + "openai\"}]"));
    }

    // ---------------------------------------------------------------------------------------------
    // 2c. values with nothing to redact must be untouched — masking must not reformat, reorder or
    //     swallow ordinary configuration, and must never throw on a value it cannot parse
    // ---------------------------------------------------------------------------------------------

    @Test
    public void shouldLeaveABackendsFilePathExactlyAsConfigured() throws Exception {
        // the DOCUMENTED shape of llmBackendsConfig is a path to a JSON file — the secrets live in that
        // file, which the configuration API never returns, so the path itself must not be masked
        String path = "/etc/mockserver/llm-backends.json";
        ConfigurationDTO dto = new ConfigurationDTO(configuration().llmBackendsConfig(path));

        assertThat(dto.getLlmBackendsConfig(), is(path));
    }

    @Test
    public void shouldLeaveAHeaderListWithoutCredentialsExactlyAsConfigured() throws Exception {
        String headers = "X-Scope-OrgID=tenant-a, X-Custom=abc";
        ConfigurationDTO dto = new ConfigurationDTO(configuration().prometheusRemoteWriteHeaders(headers));

        assertThat("a header list with no credential-bearing header must be byte-identical, spacing included",
            dto.getPrometheusRemoteWriteHeaders(), is(headers));
    }

    @Test
    public void shouldFailClosedOnAMalformedBackendsDocumentRatherThanThrowOrDisclose() throws Exception {
        // truncated JSON: it cannot be parsed, but it plainly still contains a secret
        String malformed = "[{\"name\":\"openai\",\"apiKey\":\"" + REAL_SECRET_PREFIX + "malformed\"";
        Configuration live = configuration().llmBackendsConfig(malformed);

        String json = serializer.serialize(live);

        assertThat("an unparseable backends document must be redacted whole, not disclosed",
            json, not(containsString(REAL_SECRET_PREFIX + "malformed")));
        assertThat(new ConfigurationDTO(live).getLlmBackendsConfig(), is(MASK));

        // and applying that fully-masked value back must leave the real one alone
        applyJsonTo(json, live);
        assertThat(live.llmBackendsConfig(), is(malformed));
    }

    // ---------------------------------------------------------------------------------------------
    // 3. masking must not break the write path
    // ---------------------------------------------------------------------------------------------

    @Test
    public void shouldStillApplyRealCredentialValuesSuppliedOverTheControlPlane() throws Exception {
        Configuration target = configuration();

        // a PUT body carrying REAL credential values (not the mask) must be applied in full
        ObjectNode body = ObjectMapperFactory.createObjectMapper().createObjectNode();
        for (String credential : WRITE_ONLY_CREDENTIALS) {
            body.put(credential, realValueFor(credential));
        }

        applyJsonTo(body.toString(), target);

        List<String> notApplied = new ArrayList<>();
        for (String credential : WRITE_ONLY_CREDENTIALS) {
            Object value = configurationGetter(credential).invoke(target);
            if (!realValueFor(credential).equals(value)) {
                notApplied.add(credential + " (was <" + value + ">)");
            }
        }

        assertThat("write-only credentials NOT applied from a control-plane body carrying real values — "
                + "masking has broken the write path, leaving them unsettable: " + notApplied,
            notApplied, is(empty()));
    }

    @Test
    public void shouldHonourCredentialsSetDirectlyOnTheConfigurationInstance() throws Exception {
        Configuration configuration = configurationWithEveryCredentialSet();

        for (String credential : WRITE_ONLY_CREDENTIALS) {
            assertThat("credential " + credential + " set on the Configuration instance should be readable "
                    + "in-process (masking is a wire concern, not an in-process one)",
                configuration.getClass().getMethod(credential).invoke(configuration),
                is(realValueFor(credential)));
        }
    }

    @Test
    public void shouldExposeRealCredentialValuesOnlyThroughJsonIgnoredRawAccessors() throws Exception {
        ConfigurationDTO dto = new ConfigurationDTO(configurationWithEveryCredentialSet());

        for (String credential : WRITE_ONLY_CREDENTIALS) {
            Method raw = ConfigurationDTO.class.getMethod("get"
                + Character.toUpperCase(credential.charAt(0)) + credential.substring(1) + "RawValue");
            assertThat("the raw accessor must return the real value so the functional paths keep working",
                raw.invoke(dto), is(realValueFor(credential)));
            assertThat("the raw accessor must be @JsonIgnore-d so it cannot leak through serialization",
                raw.getAnnotation(com.fasterxml.jackson.annotation.JsonIgnore.class), is(not(nullValue())));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 4. the WRITE-PATH mask guard — over the whole credential-shaped surface, not one category of it.
    //
    //    Section 2 above guards the credentials that are MASKED on read. What a property does on READ
    //    turns out to be irrelevant to the write path, and assuming otherwise produced this same bug
    //    twice over:
    //      - OMITTED properties (@JsonIgnore-d getter) were assumed safe because the property is absent
    //        from the body a GET-then-PUT client echoes back;
    //      - READABLE-but-credential-named properties (file paths, a header name, an ingest-only key —
    //        see READABLE_DESPITE_CREDENTIAL_SHAPED_NAME) were assumed safe because their GET returns
    //        the real value, so the mask never comes back from THAT endpoint either.
    //    Both wrote whatever arrived, verbatim.
    //
    //    Neither holds, because /mockserver/configuration is not the only surface an operator copies
    //    from. GET /mockserver/config, --print-config and the dashboard's Server Info tab render
    //    every credential-SHAPED name as "***REDACTED***" — asserted below rather than assumed. An
    //    operator who reads one of those, edits the settings around a credential and PUTs the result
    //    supplies the literal mask; before this guard it became the credential, breaking every call
    //    authenticated with it while the PUT answered 200 OK and logged nothing. For the two
    //    private-key paths, whose setters validate, it was worse: the PUT was abandoned HALF-APPLIED.
    //
    //    Enumeration is reflective and takes NO list, so a credential-shaped property added later is
    //    covered the day it is added. See maskRefusingCredentialProperties().
    // ---------------------------------------------------------------------------------------------

    @Test
    public void shouldRenderEveryCredentialShapedPropertyAsTheMaskOnTheDiagnosticSurfacesAPutCanBeCopiedFrom() throws Exception {
        // The premise of this whole section: the mask an operator pastes back is one MockServer itself
        // printed. Asserted over effectiveConfiguration() — the actual output of GET /mockserver/config,
        // --print-config and the dashboard's Server Info tab — rather than by calling
        // redactSensitiveValue(<DTO property name>, ...) directly, which would be near-tautological:
        // this set is FILTERED on isSensitivePropertyName and that predicate is redactSensitiveValue's
        // own first branch, so it would assert little more than "the filter filtered".
        //
        // It also closes a real gap. That surface masks by SYSTEM-PROPERTY key (mockserver.<name>)
        // while this guard enumerates DTO METHOD names. The two coincide today, but a property whose
        // system-property key diverged from its DTO property name would be masked on the diagnostic
        // surface and unguarded on the write path — reproducing this exact bug. Going through
        // effectiveConfiguration() makes that divergence a failure rather than a silent hole.
        Map<String, ResolvedProperty> byName = new LinkedHashMap<>();
        for (ResolvedProperty resolved : effectiveConfiguration()) {
            byName.put(resolved.getName(), resolved);
        }

        List<String> problems = new ArrayList<>();
        for (String credential : maskRefusingCredentialProperties()) {
            ResolvedProperty resolved = byName.get("mockserver." + credential);
            if (resolved == null) {
                problems.add(credential + " (absent from effectiveConfiguration() — its system-property "
                    + "key does not match its DTO property name, so what the diagnostic surfaces mask "
                    + "and what this guard covers have diverged)");
                continue;
            }
            // The name comes from the diagnostic output itself, not from this guard's filter, so this
            // is not the filter asserting on itself. Also driven with a value, because giving the
            // property a real one would mean writing a system property — global state this class must
            // not touch.
            if (!MASK.equals(redactSensitiveValue(resolved.getName(), "a-real-credential"))) {
                problems.add(credential + " (the surface does not mask <" + resolved.getName() + ">)");
            }
            if (!MASK.equals(resolved.getValue())) {
                // the rendered value, whether the built-in default or an ambient property file /
                // environment variable value, must be the mask
                problems.add(credential + " (value rendered as <" + resolved.getValue()
                    + "> not the mask)");
            }
            if (resolved.getDefaultValue() != null && !MASK.equals(resolved.getDefaultValue())) {
                // an overridden credential's built-in default is shown beside its value, and is the mask too
                problems.add(credential + " (default rendered as <" + resolved.getDefaultValue()
                    + "> not the mask)");
            }
        }

        assertThat("credential-shaped properties that GET /mockserver/config, --print-config and the "
                + "dashboard's Server Info tab do NOT mask, so the round trip this section guards could "
                + "not produce the mask: " + problems,
            problems, is(empty()));
    }

    @Test
    public void shouldKeepTheSilentRoundTripSetInStepWithTheGettersThatActuallyReturnTheMask() throws Exception {
        // A PUT carrying the BARE mask is refused either way; the question is whether it is refused
        // SILENTLY. Silence is right only where GET /mockserver/configuration really does hand the
        // client the bare mask — that is the blessed round trip, and warning on it would train
        // operators to ignore the warning. Everywhere else the bare mask can only have been copied out
        // of a diagnostic view or typed by hand, and silence there is what makes a destroyed credential
        // undiagnosable: refused, nothing logged, 200 OK.
        //
        // ConfigurationProperties cannot see the DTO, so it carries the set by name. This is the guard
        // that keeps the two from drifting — derived from what the getters ACTUALLY return, not a list.
        List<String> wrong = new ArrayList<>();
        for (String credential : maskRefusingCredentialProperties()) {
            Configuration configuration = configuration();
            setCredentialOn(configuration, credential);
            boolean maskedOnRead = MASK.equals(dtoGetter(credential).invoke(new ConfigurationDTO(configuration)));
            boolean treatedAsSilent = isWholeValueMaskedOnRead(credential);

            if (maskedOnRead && !treatedAsSilent) {
                wrong.add(credential + " (GET returns the bare mask, so the untouched round trip would "
                    + "now emit a warning on every config-as-code apply)");
            } else if (!maskedOnRead && treatedAsSilent) {
                wrong.add(credential + " (GET never returns the bare mask, so an incoming one is always "
                    + "operator error and must NOT be refused silently)");
            }
        }

        assertThat("ConfigurationProperties.WHOLE_VALUE_MASKED_CREDENTIAL_PROPERTIES has drifted from the "
                + "ConfigurationDTO getters that actually return the bare mask: " + wrong,
            wrong, is(empty()));
    }

    @Test
    public void shouldWarnWhenTheBareMaskIsPutOverACredentialThatIsNeverMaskedOnRead() throws Exception {
        // The end-to-end assertion for this unit's headline case, and the one thing the return value
        // cannot show: applyTo refuses the pasted mask either way, so without this the suite stays green
        // while the changelog, the consumer page and the source comments all claim a warning is emitted.
        //
        // Parallel-safe by the same discipline as ConfigurationValueRedactionTest: the filter matches a
        // property name that only this class provokes a warning for, and Surefire runs parallel=classes
        // so the methods of THIS class are serialized with each other.
        String credential = "dataPlaneBearerAuthenticationToken";
        assertThat("precondition: GET /mockserver/configuration must NOT return this property, so a bare "
                + "mask arriving for it cannot be a blessed round trip",
            serializer.serialize(configuration().dataPlaneBearerAuthenticationToken("t")),
            not(containsString(credential)));

        java.util.logging.Logger emittingLogger =
            java.util.logging.Logger.getLogger("org.mockserver.configuration.EmbeddedCredentialRedaction");
        List<String> captured = new ArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                if (record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()
                    && String.valueOf(record.getMessage()).contains(credential)) {
                    synchronized (captured) {
                        captured.add(record.getMessage());
                    }
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        java.util.logging.Level originalLevel = emittingLogger.getLevel();
        emittingLogger.addHandler(handler);
        emittingLogger.setLevel(java.util.logging.Level.ALL);
        try {
            Configuration live = configuration().dataPlaneBearerAuthenticationToken("eyJ-real-token");

            applyJsonTo("{\"" + credential + "\":\"" + MASK + "\"}", live);

            assertThat("the credential in force must survive", live.dataPlaneBearerAuthenticationToken(),
                is("eyJ-real-token"));
            assertThat("a PUT that answered 200 OK having written nothing must say so — exactly once",
                captured.size(), is(1));
            assertThat("the warning must name the property the operator has to fix",
                captured.get(0), containsString(credential));
            assertThat("the warning must never disclose the credential it is protecting",
                captured.get(0), not(containsString("eyJ-real-token")));
        } finally {
            emittingLogger.removeHandler(handler);
            emittingLogger.setLevel(originalLevel);
        }
    }

    @Test
    public void shouldNotAllowACredentialShapedSetterWithNoGetterToEscapeBothGuards() throws Exception {
        // Both guards in this class enumerate GETTERS. A write-only credential declared as a
        // @JsonProperty SETTER with no getter at all — the most natural shape for something that must
        // never be disclosed — would be deserialized, applied by applyTo/buildObject, masked on
        // --print-config, and enumerated by NEITHER guard. Every credential-shaped property has a
        // getter today; nothing asserted it until now.
        Set<String> guarded = new TreeSet<>(maskRefusingCredentialProperties());
        List<String> unreachable = new ArrayList<>();
        for (Method setter : ConfigurationDTO.class.getMethods()) {
            if (setter.getParameterCount() != 1
                || setter.isSynthetic()
                || !setter.getName().startsWith("set")
                || setter.getName().length() <= 3
                || !setter.getParameterTypes()[0].equals(String.class)) {
                continue;
            }
            String property = Introspector.decapitalize(setter.getName().substring(3));
            if (isSensitivePropertyName(property) && !guarded.contains(property)) {
                unreachable.add(property);
            }
        }

        assertThat("credential-shaped ConfigurationDTO setters with no correspondingly-guarded getter — "
                + "they are settable over PUT /mockserver/configuration and masked on --print-config, so "
                + "a pasted mask reaches them, but no guard in this class enumerates them. Give the "
                + "property a getter (@JsonIgnore-d if it must not be disclosed) so both guards see it: "
                + unreachable,
            unreachable, is(empty()));
    }

    @Test
    public void shouldNotDestroyACredentialWhenTheMaskIsAppliedBackOverIt() throws Exception {
        List<String> destroyed = new ArrayList<>();
        for (String credential : maskRefusingCredentialProperties()) {
            Configuration live = configuration();
            String held = setCredentialOn(live, credential);

            try {
                applyToViaDto(live, credential, MASK);
            } catch (Exception thrown) {
                // a setter that VALIDATES rejects the mask by throwing, which abandons applyTo
                // half-applied and fails the enclosing PUT rather than ignoring one bad value —
                // refusing must happen before the setter, and consistently
                destroyed.add(credential + " (applyTo threw " + rootCauseOf(thrown) + ")");
                continue;
            }

            Object value = instanceField(live, credential);
            if (!held.equals(value)) {
                destroyed.add(credential + " (became <" + value + ">)");
            }
        }

        assertThat("credential-shaped properties destroyed by a PUT carrying the mask an operator read "
                + "from GET /mockserver/config or --print-config — the credential in force must be left "
                + "untouched: " + destroyed,
            destroyed, is(empty()));
    }

    @Test
    public void shouldNotDestroyACredentialWithAValueThatMerelyContainsTheMask() throws Exception {
        // "***REDACTED***-my-new-key" reads as an operator typing a new credential over the mask. Neither
        // half is usable: welding them together persists a credential nobody holds, and it is not EQUAL
        // to the mask, so an equality check would let it straight through.
        List<String> destroyed = new ArrayList<>();
        for (String credential : maskRefusingCredentialProperties()) {
            Configuration live = configuration();
            String held = setCredentialOn(live, credential);

            try {
                applyToViaDto(live, credential, MASK + "-my-new-key");
            } catch (Exception thrown) {
                destroyed.add(credential + " (applyTo threw " + rootCauseOf(thrown) + ")");
                continue;
            }

            Object value = instanceField(live, credential);
            if (!held.equals(value)) {
                destroyed.add(credential + " (became <" + value + ">)");
            }
        }

        assertThat("credential-shaped properties overwritten by a value that merely CONTAINS the mask: "
                + destroyed, destroyed, is(empty()));
    }

    @Test
    public void shouldLeaveACredentialUnsetWhenBuildingAFreshConfigurationFromAMaskedValue() throws Exception {
        // buildObject() has no held configuration to refuse in favour of, so the field must be left
        // UNSET — writing the mask poisons the credential, and writing "" pins an empty value on the
        // instance whose getter then shadows the property file / environment variable behind it
        List<String> pinned = new ArrayList<>();
        for (String credential : maskRefusingCredentialProperties()) {
            for (String masked : Arrays.asList(MASK, MASK + "-my-new-key", "Bearer " + MASK)) {
                ConfigurationDTO dto = new ConfigurationDTO();
                dtoSetter(credential).invoke(dto, masked);

                Object value;
                try {
                    value = instanceField(dto.buildObject(), credential);
                } catch (Exception thrown) {
                    pinned.add(credential + " (<" + masked + "> made buildObject throw "
                        + rootCauseOf(thrown) + ")");
                    continue;
                }
                if (value != null) {
                    pinned.add(credential + " (<" + masked + "> became <" + value + ">)");
                }
            }
        }

        assertThat("credential-shaped properties left carrying — or pinned by — a masked value on a "
                + "freshly built Configuration, shadowing the static store that would have resolved the "
                + "real one: " + pinned,
            pinned, is(empty()));
    }

    @Test
    public void shouldStillApplyARealNewValueToEveryCredentialShapedProperty() throws Exception {
        // the failure direction of a refuse-the-mask guard is refusing everything — rotating a
        // credential over the control plane must still work
        List<String> notApplied = new ArrayList<>();
        for (String credential : maskRefusingCredentialProperties()) {
            if (STARTUP_ONLY_CREDENTIAL_PROPERTIES.contains(credential)) {
                continue;
            }
            Configuration live = configuration();
            setCredentialOn(live, credential);
            String rotated = valueTheSetterAccepts(credential, "rotated-credential-" + credential);

            applyToViaDto(live, credential, rotated);

            Object value = instanceField(live, credential);
            if (!rotated.equals(value)) {
                notApplied.add(credential + " (stayed <" + value + ">)");
            }
        }

        assertThat("credential-shaped properties a control-plane body carrying a REAL new value failed "
                + "to rotate — the mask guard has broken the write path: " + notApplied,
            notApplied, is(empty()));
    }

    /**
     * Credential-shaped properties fixed at startup: {@code applyTo} never writes them, so a control-plane
     * {@code PUT} can neither rotate nor clear them (see {@code HttpState.applyConfigurationUpdate}).
     */
    private static final Set<String> STARTUP_ONLY_CREDENTIAL_PROPERTIES = Set.of("controlPlaneBasicAuthenticationCredentials", "clusterFanInPeerAuthToken", "asyncKafkaSaslJaasConfig");

    @Test
    public void shouldNeverApplyAStartupOnlyCredentialOverTheControlPlane() throws Exception {
        List<String> applied = new ArrayList<>();
        for (String credential : STARTUP_ONLY_CREDENTIAL_PROPERTIES) {
            for (String supplied : java.util.Arrays.asList("replaced-" + credential, "")) {
                Configuration live = configuration();
                setCredentialOn(live, credential);
                Object held = instanceField(live, credential);
                applyToViaDto(live, credential, supplied);
                if (!held.equals(instanceField(live, credential))) {
                    applied.add(credential + " (took <" + supplied + ">)");
                }
            }
        }

        assertThat("startup-only credentials a control-plane PUT changed: " + applied, applied, is(empty()));
    }

    @Test
    public void shouldStillBuildAFreshConfigurationCarryingARealCredential() throws Exception {
        List<String> notBuilt = new ArrayList<>();
        for (String credential : maskRefusingCredentialProperties()) {
            String real = valueTheSetterAccepts(credential, "fresh-credential-" + credential);
            ConfigurationDTO dto = new ConfigurationDTO();
            dtoSetter(credential).invoke(dto, real);

            Object value = instanceField(dto.buildObject(), credential);
            if (!real.equals(value)) {
                notBuilt.add(credential + " (became <" + value + ">)");
            }
        }

        assertThat("credential-shaped properties a real value did NOT reach on a freshly built "
                + "Configuration: " + notBuilt,
            notBuilt, is(empty()));
    }

    @Test
    public void shouldStillClearACredentialWithAnEmptyString() throws Exception {
        // "" is a legitimate instruction — turn this credential off — and cannot be confused with the
        // mask, so refusing the mask must not refuse it. Asserted on the JSON wire path an operator
        // actually uses (HttpRequestHandler -> readValue -> applyTo) as well as the in-process setter:
        // over JSON "" binds as "", reaches applyTo, passes the mask guard and clears the credential.
        List<String> notCleared = new ArrayList<>();
        for (String credential : maskRefusingCredentialProperties()) {
            if (!STARTUP_ONLY_CREDENTIAL_PROPERTIES.contains(credential)) {
                Configuration overJson = configuration();
                setCredentialOn(overJson, credential);
                applyJsonTo("{\"" + credential + "\":\"\"}", overJson);
                Object cleared = instanceField(overJson, credential);
                if (!"".equals(cleared)) {
                    notCleared.add(credential + " (PUT of \"\" left <" + cleared + ">)");
                }

                Configuration live = configuration();
                setCredentialOn(live, credential);
                applyToViaDto(live, credential, "");
                Object applied = instanceField(live, credential);
                if (!"".equals(applied)) {
                    notCleared.add(credential + " (applyTo left <" + applied + ">)");
                }
            }

            ConfigurationDTO fresh = new ConfigurationDTO();
            dtoSetter(credential).invoke(fresh, "");
            Object built = instanceField(fresh.buildObject(), credential);
            if (!"".equals(built)) {
                notCleared.add(credential + " (buildObject left <" + built + ">)");
            }
        }

        assertThat("credential-shaped properties that can no longer be CLEARED with an empty string: "
                + notCleared, notCleared, is(empty()));
    }

    @Test
    public void shouldNotDestroyTheDataPlaneBearerTokenReadBackFromTheEffectiveConfiguration() throws Exception {
        // named regression, end to end over the exact operator sequence: read the effective
        // configuration, edit a neighbouring setting, PUT the result
        String token = "eyJ-data-plane-bearer-token";
        Configuration live = configuration()
            .dataPlaneBearerAuthenticationToken(token)
            .dataPlaneBasicAuthenticationRealm("original-realm");

        String asPrinted = redactSensitiveValue("dataPlaneBearerAuthenticationToken", token);
        assertThat("--print-config shows the operator the mask, which is what they paste back",
            asPrinted, is(MASK));

        applyJsonTo("{\"dataPlaneBearerAuthenticationToken\":\"" + asPrinted + "\","
            + "\"dataPlaneBasicAuthenticationRealm\":\"edited-realm\"}", live);

        assertThat("the working data-plane token must survive the round trip",
            live.dataPlaneBearerAuthenticationToken(), is(token));
        assertThat("and the edit the operator actually made must be applied",
            live.dataPlaneBasicAuthenticationRealm(), is("edited-realm"));
    }

    /**
     * Properties applied AFTER both validating setters in {@code applyTo}'s source order, which is the
     * only order that matters — {@code applyTo} walks its own fields and ignores the order of the JSON
     * body entirely.
     *
     * <p>This is the load-bearing detail of the test below and it is easy to get wrong: a property that
     * merely appears LATER IN THE BODY, or later in the DTO's field declarations, can still be applied
     * BEFORE the throw and would prove nothing. Both entries here sit after
     * {@code controlPlanePrivateKeyPath} and {@code forwardProxyPrivateKey} in {@code applyTo}, one
     * immediately after and one near the very end, so a future change that moves the mask guard part of
     * the way down the method is still caught by at least one of them.
     */
    private static final Map<String, String> APPLIED_AFTER_BOTH_VALIDATING_SETTERS = new LinkedHashMap<>();

    static {
        APPLIED_AFTER_BOTH_VALIDATING_SETTERS.put("blobStoreProjectId", "edited-project");
        APPLIED_AFTER_BOTH_VALIDATING_SETTERS.put("customJsonUnitMatchersClass", "com.example.EditedMatchers");
    }

    @Test
    public void shouldNotAbandonAPutHalfAppliedWhenAMaskedValueReachesAValidatingSetter() throws Exception {
        // controlPlanePrivateKeyPath and forwardProxyPrivateKey validate on write (fileExists), so the
        // mask used to be rejected by THROWING from the middle of applyTo — after neighbouring edits in
        // the same body had already been committed to the LIVE Configuration. HttpRequestHandler turns
        // that into a 400 over half-mutated state: the operator is told the PUT failed while part of it
        // stands. Refusing before the setter is what makes the whole body all-or-nothing again.
        //
        // Every outcome is COLLECTED rather than asserted inline, so each arm fails on its own merits.
        // Asserting inline would stop at the throw (or at the first arm), and the arm that actually
        // pins this behaviour — an edit applied after the validating setter — would never be evaluated.
        List<String> problems = new ArrayList<>();
        for (String validating : Arrays.asList("controlPlanePrivateKeyPath", "forwardProxyPrivateKey")) {
            Configuration live = configuration().proxyAuthenticationRealm("original-realm");
            for (String after : APPLIED_AFTER_BOTH_VALIDATING_SETTERS.keySet()) {
                Configuration.class.getMethod(after, String.class).invoke(live, "original-" + after);
            }
            String heldKeyPath = setCredentialOn(live, validating);

            StringBuilder body = new StringBuilder("{\"proxyAuthenticationRealm\":\"edited-realm\",\"")
                .append(validating).append("\":\"").append(MASK).append('"');
            for (Map.Entry<String, String> edit : APPLIED_AFTER_BOTH_VALIDATING_SETTERS.entrySet()) {
                body.append(",\"").append(edit.getKey()).append("\":\"").append(edit.getValue()).append('"');
            }
            body.append('}');

            try {
                applyJsonTo(body.toString(), live);
            } catch (Exception thrown) {
                problems.add(validating + " (applyTo threw " + rootCauseOf(thrown) + ")");
            }

            if (!heldKeyPath.equals(instanceField(live, validating))) {
                problems.add(validating + " (the key path in force became <"
                    + instanceField(live, validating) + ">)");
            }
            if (!"edited-realm".equals(live.proxyAuthenticationRealm())) {
                problems.add(validating + " (an edit applied BEFORE it was lost: proxyAuthenticationRealm "
                    + "is <" + live.proxyAuthenticationRealm() + ">)");
            }
            for (Map.Entry<String, String> edit : APPLIED_AFTER_BOTH_VALIDATING_SETTERS.entrySet()) {
                Object actual = Configuration.class.getMethod(edit.getKey()).invoke(live);
                if (!edit.getValue().equals(actual)) {
                    problems.add(validating + " (an edit applied AFTER it was lost: " + edit.getKey()
                        + " is <" + actual + "> not <" + edit.getValue() + ">)");
                }
            }
        }

        assertThat("a masked value reaching a validating setter must not abandon the PUT part-way — the "
                + "credential must be refused before the setter, and every other edit in the same body, "
                + "on BOTH sides of it, must still be applied: " + problems,
            problems, is(empty()));
    }

    /**
     * Every ConfigurationDTO property whose write path must refuse a value carrying the mask: its name is
     * credential-shaped under the shared {@link ConfigurationProperties#isSensitivePropertyName(String)}
     * rule, so {@code GET /mockserver/config}, {@code --print-config} and the dashboard's Server Info
     * tab all render it as the mask and an operator can paste that back.
     *
     * <p>Deliberately does <strong>not</strong> filter on what the property does on READ. Both previous
     * versions of this bug came from treating a read-side property as a write-side exemption: an
     * {@code @JsonIgnore}-d getter does not stop the mask arriving, and neither does a getter that
     * returns the real value — {@link #READABLE_DESPITE_CREDENTIAL_SHAPED_NAME} is a decision about
     * DISCLOSURE only, and every entry in it is still masked on the diagnostic surfaces. Discovered by
     * reflection, never from a list.
     *
     * <p>No exclusions at all. The credentials that are MASKED on read used to be excluded, because
     * their write paths refused a value equal to the mask but not one merely containing it; both now
     * refuse by containment, so the whole credential-shaped surface is held to one rule here.
     */
    private static List<String> maskRefusingCredentialProperties() {
        Set<String> properties = new TreeSet<>();
        for (Method getter : ConfigurationDTO.class.getMethods()) {
            String property = serializedPropertyNameOf(getter);
            if (property == null
                || !getter.getReturnType().equals(String.class)
                || !isSensitivePropertyName(property)) {
                continue;
            }
            properties.add(property);
        }
        // A RATCHET, not a smoke test. "greaterThan(some round number)" tolerates a rename silently
        // dropping several properties, which is the realistic regression — not "discovers zero". Raise
        // this when a credential-shaped property is added; NEVER lower it to make a failure go away,
        // because the failure is telling you coverage was lost.
        assertThat("reflection discovered fewer credential-shaped properties than it did when this "
                + "guard was written — a getter was renamed, retyped or removed, and its write path is "
                + "now unguarded",
            properties.size(), greaterThanOrEqualTo(19));
        return new ArrayList<>(properties);
    }

    /** The message a reflective invocation buried the real cause in, so a failure names it. */
    private static String rootCauseOf(Throwable thrown) {
        Throwable cause = thrown;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    /** Set one property on a fresh DTO and apply it, exactly as a single-property PUT body would. */
    private static void applyToViaDto(Configuration target, String property, String value) throws Exception {
        ConfigurationDTO dto = new ConfigurationDTO();
        dtoSetter(property).invoke(dto, value);
        dto.applyTo(target);
    }

    private static Method dtoSetter(String property) throws Exception {
        return ConfigurationDTO.class.getMethod("set"
            + Character.toUpperCase(property.charAt(0)) + property.substring(1), String.class);
    }

    // ---------------------------------------------------------------------------------------------

    private Configuration configurationWithEveryCredentialSet() throws Exception {
        Configuration configuration = configuration();
        for (String credential : WRITE_ONLY_CREDENTIALS) {
            Configuration.class
                .getMethod(credential, String.class)
                .invoke(configuration, realValueFor(credential));
        }
        return configuration;
    }

    /**
     * Apply a JSON configuration body onto a target exactly as the control plane does: bind it to a
     * {@link ConfigurationDTO} and call {@code applyTo}.
     */
    private void applyJsonTo(String json, Configuration target) throws Exception {
        ConfigurationDTO dto = ObjectMapperFactory
            .createObjectMapper()
            .readValue(json, ConfigurationDTO.class);
        dto.applyTo(target);
    }

    /** A single-property PUT body, built through Jackson so quoting/escaping matches the real wire. */
    private static String bodyWith(String property, String value) {
        ObjectNode body = ObjectMapperFactory.createObjectMapper().createObjectNode();
        body.put(property, value);
        return body.toString();
    }

    private static boolean isEmbeddedValueCredential(String credential) {
        return EMBEDDED_REAL_VALUES.containsKey(credential);
    }

    /** The secret itself — the string that must never appear on the wire. */
    private static String realSecretFor(String credential) {
        return REAL_SECRET_PREFIX + credential;
    }

    /** The value the property is set to: the bare secret, or a structured value that embeds it. */
    private static String realValueFor(String credential) {
        String embedded = EMBEDDED_REAL_VALUES.get(credential);
        return embedded != null ? embedded : realSecretFor(credential);
    }

    /** The exact value {@code GET /mockserver/configuration} must return for that value. */
    private static String expectedMaskFor(String credential) {
        String embedded = EMBEDDED_MASKED_VALUES.get(credential);
        return embedded != null ? embedded : MASK;
    }

    private static Method configurationGetter(String credential) throws Exception {
        return Configuration.class.getMethod(credential);
    }

    private static Method dtoGetter(String credential) throws Exception {
        return ConfigurationDTO.class.getMethod("get"
            + Character.toUpperCase(credential.charAt(0)) + credential.substring(1));
    }
}

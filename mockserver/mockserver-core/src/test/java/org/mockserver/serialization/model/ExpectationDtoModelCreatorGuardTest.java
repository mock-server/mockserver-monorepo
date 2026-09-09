package org.mockserver.serialization.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.BeanDeserializer;
import com.fasterxml.jackson.databind.deser.DefaultDeserializationContext;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import org.junit.Test;
import org.mockserver.model.Cookies;
import org.mockserver.model.Delay;
import org.mockserver.model.Provider;
import org.mockserver.model.StreamingPhysics;
import org.mockserver.serialization.ObjectMapperFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Build-time guard that catches a {@code org.mockserver.model} type reaching the wire without a way
 * for Jackson to construct it on the read path.
 *
 * <h2>The bug this prevents (issue #2668)</h2>
 * {@code HttpLlmResponseDTO.completion} held the raw model class {@code Completion} instead of a DTO,
 * and {@code StreamingPhysics} had no DTO, so {@code StreamingPhysics.timeToFirstToken} reached the
 * wire as a raw {@link org.mockserver.model.Delay}. {@code Delay} has only multi-argument
 * constructors, all-final fields, no no-arg constructor and no {@code @JsonCreator}, so Jackson threw
 * {@code InvalidDefinitionException: Cannot construct instance of org.mockserver.model.Delay (no
 * Creators, like default constructor, exist)} and every expectation using {@code timeToFirstToken}
 * was rejected with HTTP 400. It was invisible because serialisation of a raw {@code Delay} emits
 * exactly the bytes {@code DelayDTO} produces (so client-side JSON assertions stayed green); only
 * deserialisation failed.
 *
 * <h2>What this guard does</h2>
 * By reflection it starts from {@link ExpectationDTO}, walks the entire type graph reachable through
 * Jackson's own serialized-property view (DTOs and the raw model subtrees they expose), and for every
 * {@code org.mockserver.model} type it reaches asserts Jackson can actually construct one. When it
 * cannot it fails with the exact reachability path (e.g. {@code ExpectationDTO.httpLlmResponse ->
 * HttpLlmResponseDTO.completion -> Completion.streamingPhysics -> StreamingPhysics.timeToFirstToken :
 * ...Delay has no Jackson creator}) so the failure is fixable, not just a bare type name.
 *
 * <p>The walk enumerates each type's properties through {@code SerializationConfig.introspect(...)}
 * — Jackson's own view of what gets written to JSON — so it resolves generic type variables against
 * their concrete bindings (e.g. {@code HeadersModifierDTO.add} resolves to the concrete {@code Headers}
 * carrier, not the abstract {@code KeyToMultiValue} bound) and honours {@code @JsonIgnore}, getters,
 * and visibility exactly as serialisation does.
 *
 * <h2>Constructibility</h2>
 * A model type is treated as constructible if it is an enum, has a {@code @JsonValue} method, has a
 * {@code @JsonCreator} constructor/factory, has a no-arg constructor (any visibility), carries a
 * class-level {@code @JsonDeserialize(using=...)}, OR the real {@link ObjectMapperFactory} mapper can
 * actually read one back (which is what accounts for the custom deserializers registered as modules
 * in {@code ObjectMapperFactory}, e.g. for {@code NottableString}/{@code Headers}). A type fails only
 * when BOTH the structural checks find no creator AND the real mapper confirms "no Creators".
 *
 * <h2>KNOWN LIMITS — this guard is NOT exhaustive. Read a green run as "no creator-less model type
 * was reached through a statically-typed field", never as "every type that can cross the wire is
 * constructible".</h2>
 * <ul>
 *   <li><b>{@code Object} / unbounded wildcards / raw types.</b> A property typed {@code Object} (or
 *       whose element type erases to {@code Object}) hides its concrete runtime type, so a creator-less
 *       type reachable ONLY through such a property is invisible here. Bounded generics ARE resolved.</li>
 *   <li><b>Serialiser-driven shapes.</b> The walk uses Jackson's serialized-property view, but a
 *       custom {@code JsonSerializer}/{@code @JsonSerialize} can emit a JSON shape that differs from the
 *       bean-property graph (e.g. flattening a sub-object into a scalar). A type whose wire shape is
 *       produced that way may be walked over- or under-broadly.</li>
 *   <li><b>Custom-deserializer boundary.</b> A type read by a custom deserializer (rather than
 *       Jackson's default {@code BeanDeserializer}) is a leaf: the walk stops there because that
 *       deserializer constructs its whole subtree itself. This is correct when a custom deserializer
 *       fully owns construction (the MockServer pattern), but a type reachable ONLY behind a custom
 *       deserializer that internally re-delegates a sub-object to default bean deserialization is not
 *       seen here.</li>
 *   <li><b>Polymorphic subtypes.</b> A property typed as a {@code @JsonTypeInfo}/{@code @JsonSubTypes}
 *       base is a leaf, so a creator-less CONCRETE SUBTYPE reachable only through such a base is not
 *       seen. There are no polymorphic types in the reachable model/DTO graph today, so this is a
 *       latent rather than live gap — but adding one would create a blind spot here.</li>
 *   <li><b>Runtime-only registrations.</b> Constructibility via the real mapper is only as complete
 *       as {@link ObjectMapperFactory#createObjectMapper()}; deserializers registered elsewhere at
 *       runtime are not seen by the structural checks (the mapper probe does see the ones this
 *       factory registers).</li>
 *   <li><b>Message coupling.</b> The mapper probe distinguishes "no creator at all" from "has a
 *       creator, wrong input shape" by Jackson's {@code InvalidDefinitionException} wording. If a
 *       Jackson upgrade changes that wording the probe could misclassify — which is why
 *       {@link #guardMechanismItselfDetectsACreatorlessType()} pins {@code Delay} as a fail-closed
 *       tripwire: a wording change breaks that self-test loudly rather than letting this guard pass
 *       vacuously.</li>
 * </ul>
 * The {@link #MIN_MODEL_TYPES_WALKED} tripwire is the other fail-closed backstop: if a refactor
 * silently shrinks the reachable graph the guard fails rather than passing on an empty walk.
 */
public class ExpectationDtoModelCreatorGuardTest {

    /** The wire-facing root of the expectation graph. */
    private static final Class<?> ROOT = ExpectationDTO.class;

    private static final String MODEL_PACKAGE = "org.mockserver.model.";

    /**
     * Fail-closed tripwire: the real reachable graph is far larger than this. If the walk ever
     * discovers fewer model types than this, something has silently stopped the traversal (a moved
     * package, a reflection failure, an accidental early-out) and the guard must fail rather than pass
     * on a hollow walk. Set well below the observed count (37 on the tree that introduced this guard)
     * so ordinary graph growth/shrink does not flap it, but high enough that a collapsed walk (which
     * would reach only a handful) is caught.
     */
    private static final int MIN_MODEL_TYPES_WALKED = 25;

    /**
     * Model types deliberately excluded from the constructibility assertion. This must stay tiny and
     * every entry must carry a concrete justification (a registered deserializer, a documented
     * non-wire type) — never a convenience to make the guard pass. It is currently empty: no
     * reachable model type needs an exclusion, because the real-mapper probe already clears every
     * type that has a registered custom deserializer.
     */
    private static final Set<Class<?>> EXCLUDED_MODEL_TYPES = Collections.emptySet();

    /** Follow field references into these package subtrees only (the DTO graph + the model graph). */
    private static boolean isFollowable(Class<?> c) {
        if (c == null || c.isPrimitive()) {
            return false;
        }
        String n = c.getName();
        return n.startsWith("org.mockserver.model.")
            || n.startsWith("org.mockserver.serialization.model.");
    }

    private static boolean isModelType(Class<?> c) {
        return c.getName().startsWith(MODEL_PACKAGE);
    }

    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    @Test
    public void everyModelTypeReachableFromExpectationDtoMustHaveAJacksonCreator() {
        Walk walk = new Walk();
        walk.visit(objectMapper.constructType(ROOT), "");

        assertThat("reachable model-type graph is implausibly small — the walk was likely cut short "
                + "(moved package, reflection failure, or an accidental early-out); refusing to pass on a "
                + "hollow walk. Model types walked: " + walk.modelTypesChecked,
            walk.modelTypesChecked.size(), greaterThanOrEqualTo(MIN_MODEL_TYPES_WALKED));

        assertThat("Model type(s) reachable from ExpectationDTO that Jackson cannot construct on the "
                + "read path. Each will be rejected with an InvalidDefinitionException (HTTP 400) if it "
                + "ever carries data, exactly like issue #2668's Delay. Wrap the offending field in a DTO "
                + "(as DelayDTO/CompletionDTO do), give the model a @JsonCreator/no-arg constructor, or "
                + "register a deserializer in ObjectMapperFactory. Reachability path(s):\n  "
                + String.join("\n  ", walk.failures),
            walk.failures, is(empty()));
    }

    /**
     * Fail-closed self-test of the guard's own mechanism. If any of these regress, the constructibility
     * check is broken and the main test above could pass vacuously — so this pins the behaviour
     * independently of the live graph.
     */
    @Test
    public void guardMechanismItselfDetectsACreatorlessType() {
        // Delay is the canonical #2668 creator-less model type: only multi-arg constructors, no creator.
        assertFalse("guard must detect Delay as NON-constructible (its central mechanism); if this fails "
                + "the constructibility check or the Jackson error-message wording has drifted",
            isJacksonConstructible(Delay.class));
        // A no-declared-constructor bean gets an implicit no-arg constructor -> constructible.
        assertTrue("guard must accept a plain no-arg-constructor bean (StreamingPhysics)",
            isJacksonConstructible(StreamingPhysics.class));
        // An enum is always constructible by Jackson.
        assertTrue("guard must accept an enum (Provider)", isJacksonConstructible(Provider.class));
    }

    /**
     * Fail-closed self-test of the custom-deserializer boundary. If this regresses, the walk would
     * either descend past a custom deserializer (re-raising the Cookie/Header/Parameter false
     * positives) or stop at a plain bean (never reaching a real Delay) — so pin both directions.
     */
    @Test
    public void guardStopsAtCustomDeserializersButWalksPlainBeans() {
        // Cookies is read by the registered CookiesDeserializer (ObjectMapperFactory), which builds its
        // Cookie entries itself — a boundary leaf, so the walk must NOT descend into it.
        assertFalse("Cookies is read by a custom deserializer and must be treated as a boundary leaf",
            usesDefaultBeanDeserializer(Cookies.class));
        // StreamingPhysics has no custom deserializer — a plain bean the walk must descend through to
        // reach its timeToFirstToken:Delay (the #2668 path).
        assertTrue("StreamingPhysics is a plain bean the walk must descend through",
            usesDefaultBeanDeserializer(StreamingPhysics.class));
    }

    // ------------------------------------------------------------------------------------------------
    // Graph walk
    // ------------------------------------------------------------------------------------------------

    private final class Walk {
        /** Every JavaType visited — dedup + cycle guard across the whole (generic-resolved) graph. */
        private final Set<JavaType> visited = new HashSet<>();
        /** Every model type checked for constructibility (drives the size tripwire). */
        private final Set<Class<?>> modelTypesChecked = new LinkedHashSet<>();
        /** Model raw classes already reported, so one type is not flagged once per reachable path. */
        private final Set<Class<?>> reportedModelClasses = new HashSet<>();
        /** Reachability paths for model types that are not constructible. */
        private final List<String> failures = new ArrayList<>();

        /**
         * @param type       the (generic-resolved) type currently reached
         * @param pathToHere the chain of {@code Owner.property} segments that reached {@code type}
         *                   (empty for the root); the failure message appends {@code : <type> ...}
         */
        void visit(JavaType type, String pathToHere) {
            if (type == null || !visited.add(type)) {
                return; // already walked (handles cycles)
            }

            // Unwrap collections, arrays, maps and references to reach their element/value types,
            // carrying the same path (the container itself is not a model type to check).
            if (type.isContainerType() || type.isReferenceType()) {
                visit(type.getKeyType(), pathToHere);
                visit(type.getContentType(), pathToHere);
                return;
            }

            Class<?> raw = type.getRawClass();
            if (!isFollowable(raw)) {
                return;
            }

            if (isModelType(raw)) {
                modelTypesChecked.add(raw);
                if (!EXCLUDED_MODEL_TYPES.contains(raw) && reportedModelClasses.add(raw)
                    && !isJacksonConstructible(raw)) {
                    failures.add(pathToHere + " : " + raw.getName()
                        + " has no Jackson creator (no no-arg constructor, @JsonCreator, registered "
                        + "deserializer, enum, or @JsonValue)");
                }
            }

            if (raw.isEnum()) {
                return; // enum constants carry no wire-relevant property graph
            }

            // A type read by a CUSTOM deserializer (not Jackson's default BeanDeserializer) owns
            // construction of its whole subtree — e.g. CookiesDeserializer builds the Cookie entries
            // itself, so Jackson never independently bean-deserialises a Cookie and those children need
            // no Jackson creator. Such a type is a boundary leaf: we still check the type itself above,
            // but do not descend into its bean properties (doing so produced false positives on
            // Cookie/Header/Parameter). Default-bean DTOs and models are walked through.
            if (!usesDefaultBeanDeserializer(raw)) {
                return;
            }

            // Enumerate exactly the properties Jackson serialises (resolves generics, honours
            // @JsonIgnore / getters / visibility), so we walk what actually crosses the wire.
            BeanDescription description = objectMapper.getSerializationConfig().introspect(type);
            for (BeanPropertyDefinition property : description.findProperties()) {
                JavaType propertyType = property.getPrimaryType();
                String segment = raw.getSimpleName() + "." + property.getName();
                String childPath = pathToHere.isEmpty() ? segment : pathToHere + " -> " + segment;
                visit(propertyType, childPath);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Constructibility
    // ------------------------------------------------------------------------------------------------

    /**
     * True if Jackson can construct an instance of {@code type} on the read path. Structural checks
     * first (wording-independent positives); the real-mapper probe last, which is what clears types
     * whose only creator is a deserializer registered as a module in {@link ObjectMapperFactory}.
     */
    boolean isJacksonConstructible(Class<?> type) {
        if (type.isEnum()) {
            return true;
        }
        if (hasJsonValueMethod(type)) {
            return true;
        }
        if (hasJsonCreator(type)) {
            return true;
        }
        if (hasNoArgConstructor(type)) {
            return true;
        }
        if (hasClassLevelCustomDeserializer(type)) {
            return true;
        }
        return realMapperCanConstruct(type);
    }

    private static boolean hasNoArgConstructor(Class<?> type) {
        for (Constructor<?> c : type.getDeclaredConstructors()) {
            if (c.getParameterCount() == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasJsonCreator(Class<?> type) {
        for (Constructor<?> c : type.getDeclaredConstructors()) {
            if (c.isAnnotationPresent(JsonCreator.class)) {
                return true;
            }
        }
        for (Method m : type.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && m.isAnnotationPresent(JsonCreator.class)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasJsonValueMethod(Class<?> type) {
        for (Method m : type.getMethods()) {
            if (m.isAnnotationPresent(JsonValue.class)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasClassLevelCustomDeserializer(Class<?> type) {
        JsonDeserialize ann = type.getAnnotation(JsonDeserialize.class);
        if (ann == null) {
            return false;
        }
        return (ann.using() != null && ann.using() != JsonDeserializer.None.class)
            || (ann.builder() != null && ann.builder() != Void.class);
    }

    /** Lazily-created deserialization context off the configured mapper (see {@link #deserializationContext()}). */
    private DefaultDeserializationContext deserializationContext;

    /**
     * True if the configured mapper reads {@code raw} with Jackson's default {@link BeanDeserializer}
     * (so its properties are populated by recursing into their own deserializers, and this walk should
     * descend), rather than a custom deserializer that owns the whole subtree (a boundary leaf).
     *
     * <p>Resolving the deserializer needs a {@code DeserializationContext}, which Jackson only exposes
     * as an internal blueprint field on {@link ObjectMapper}; this reflects it and asks it for the
     * root deserializer. If that resolution fails for a type it is treated as a boundary leaf (we do
     * not descend) — a conservative choice whose gross form (the whole walk collapsing) is caught by
     * the {@link #MIN_MODEL_TYPES_WALKED} tripwire rather than passing silently.
     */
    private boolean usesDefaultBeanDeserializer(Class<?> raw) {
        try {
            JsonDeserializer<?> deserializer =
                deserializationContext().findRootValueDeserializer(objectMapper.constructType(raw));
            return deserializer instanceof BeanDeserializer;
        } catch (Exception e) {
            return false;
        }
    }

    private DefaultDeserializationContext deserializationContext() throws Exception {
        if (deserializationContext == null) {
            Field field = ObjectMapper.class.getDeclaredField("_deserializationContext");
            field.setAccessible(true);
            DefaultDeserializationContext blueprint = (DefaultDeserializationContext) field.get(objectMapper);
            deserializationContext =
                blueprint.createInstance(objectMapper.getDeserializationConfig(), null, null);
        }
        return deserializationContext;
    }

    /**
     * Asks the real {@link ObjectMapperFactory} mapper to read an empty object back into {@code type}.
     * This is the runtime authority that accounts for module-registered custom deserializers. A type
     * whose only failure is "no Creators, like default constructor, exist" is genuinely creator-less;
     * anything else (a successful read, an enum/shape mismatch, or "although at least one Creator
     * exists") means a creator or deserializer is present.
     */
    private boolean realMapperCanConstruct(Class<?> type) {
        try {
            objectMapper.readValue("{}", type);
            return true;
        } catch (InvalidDefinitionException e) {
            String message = String.valueOf(e.getMessage());
            // "no Creators, like default constructor, exist" == zero creators (the #2668 failure).
            // "although at least one Creator exists" == a creator is present, just wrong input shape.
            return !(message.contains("no Creators") && !message.contains("at least one Creator"));
        } catch (Exception e) {
            // MismatchedInput (e.g. enum/scalar from an object), value errors, etc. all imply that a
            // deserializer/creator exists and simply rejected the empty-object probe.
            return true;
        }
    }
}

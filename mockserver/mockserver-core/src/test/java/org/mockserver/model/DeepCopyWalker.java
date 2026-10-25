package org.mockserver.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Walks two object graphs side by side through every non-static field, and checks the second is a deep copy of the
 * first (or, in {@link Mode#EQUAL}, only that it holds the same values). JDK types are never reflected into: the
 * immutable ones are leaves compared by {@code equals}, and collections, maps and arrays are walked through their API,
 * so the walk works on Java 17 without opening {@code java.*} modules.
 */
final class DeepCopyWalker {

    enum Mode {
        /** every mutable object is a different instance holding the same values */
        COPY,
        /** the same values, whatever the identity */
        EQUAL
    }

    private static final Set<Class<?>> JDK_LEAVES = Set.of(
        String.class, Boolean.class, Character.class, Byte.class, Short.class, Integer.class, Long.class, Float.class, Double.class
    );

    // caches a copy rebuilds on use rather than carrying over; a copy may hold null
    private static final Set<String> REBUILT_CACHES = Set.of(
        "HttpRequest.convertedBodyCache",
        "HttpRequest.parsedBodyCache",
        "KeysToMultiValues.convertedMatcher",
        "KeysAndValues.convertedMatcher",
        "StringBody.value",
        "JsonBody.json",
        "JsonBody.jsonNode",
        "XmlBody.xml",
        "HttpRequest$RemoteAddress.bytes"
    );

    // never shown to a hook: a copy must hold null
    private static final Set<String> NOT_COPIED = Set.of(
        "HttpResponse.streamingBody"
    );

    // may be null in a complete fixture: one of two storage forms, a cache not yet built, or a field a body never sets
    private static final Set<String> NULL_IN_A_COMPLETE_FIXTURE = Set.of(
        "BinaryBody.bytes",
        "BinaryBody.segmentedBytes",
        "StringBody.rawBytes",
        "StringBody.segmentedBytes",
        "HttpRequest.convertedBodyCache",
        "HttpRequest.parsedBodyCache",
        "KeysToMultiValues.convertedMatcher",
        "KeysAndValues.convertedMatcher",
        "HttpRequest$RemoteAddress.address",
        "ResponseMatchingBody>BodyWithContentType.contentType",
        "ResponseMatchingBody>Body.optional",
        "LogEntryBody>BodyWithContentType.contentType"
    );

    // the recovery response of a fixture response is itself a complete response, but for its own recovery
    private static final Set<String> NULL_IN_A_COMPLETE_FIXTURE_AT = Set.of(
        ".recoverAfter.failResponse.recoverAfter"
    );

    // fields every instance of a class has as a newly built one does, so a fixture cannot give them another value
    private static final Map<String, String> SAME_AS_A_NEW_INSTANCE = Map.of(
        "Body.type", "set by each body's constructor, the same for every instance of that body",
        "BinaryBody.base64Converter", "a stateless converter, so every instance holds an equal one"
    );

    private static final Map<Class<?>, Optional<Object>> NEW_INSTANCES = new ConcurrentHashMap<>();

    private final Mode mode;
    private final IdentityHashMap<Object, Object> visited = new IdentityHashMap<>();
    private final Map<Class<?>, Set<Class<?>>> observedByDeclaredType = new HashMap<>();
    private final List<String> failures = new ArrayList<>();

    DeepCopyWalker(Mode mode) {
        this.mode = mode;
    }

    DeepCopyWalker walk(Object original, Object copy, String path) {
        compare(original, copy, path, null);
        return this;
    }

    List<String> failures() {
        return failures;
    }

    Map<Class<?>, Set<Class<?>>> observedByDeclaredType() {
        return observedByDeclaredType;
    }

    Set<Class<?>> observedClasses() {
        Set<Class<?>> observed = new LinkedHashSet<>();
        observedByDeclaredType.values().forEach(observed::addAll);
        return observed;
    }

    static boolean isLeaf(Object value) {
        return JDK_LEAVES.contains(value.getClass())
            || value instanceof Enum
            || value instanceof Charset
            || value instanceof Certificate
            || value instanceof InetAddress
            || value instanceof java.net.SocketAddress
            || value instanceof NottableString
            || value instanceof MediaType;
    }

    private void compare(Object original, Object copy, String path, String fieldKey) {
        if (original == null) {
            if (copy != null) {
                failures.add(path + ": null in the original but " + describe(copy) + " in the copy");
            }
            return;
        }
        if (copy == null) {
            failures.add(path + ": dropped, " + describe(original) + " in the original but null in the copy");
            return;
        }
        if (isLeaf(original)) {
            if (!original.equals(copy)) {
                failures.add(path + ": " + original + " in the original but " + copy + " in the copy");
            }
            return;
        }
        if (original.getClass() != copy.getClass()) {
            failures.add(path + ": " + original.getClass().getName() + " in the original but " + copy.getClass().getName() + " in the copy");
            return;
        }
        if (mode == Mode.COPY && original == copy && !isImmutableContainer(original)) {
            failures.add(path + ": the copy shares the original's mutable " + original.getClass().getName());
            return;
        }
        Object seen = visited.get(original);
        if (seen != null) {
            if (mode == Mode.COPY && seen != copy) {
                failures.add(path + ": an object the original holds twice is not the same object twice in the copy");
            }
            return;
        }
        visited.put(original, copy);
        if (original.getClass().isArray()) {
            compareArrays(original, copy, path);
        } else if (original instanceof Collection) {
            compareCollections((Collection<?>) original, (Collection<?>) copy, path);
        } else if (original instanceof Map) {
            compareMaps((Map<?, ?>) original, (Map<?, ?>) copy, path);
        } else if (original instanceof JsonNode) {
            if (!original.equals(copy)) {
                failures.add(path + ": a different JSON tree in the copy");
            }
        } else if (isJdkType(original.getClass())) {
            failures.add(path + ": " + original.getClass().getName() + " is a JDK type the walker does not know; make it a leaf or walk it");
        } else {
            compareFields(original, copy, path);
        }
    }

    private void compareArrays(Object original, Object copy, String path) {
        int length = Array.getLength(original);
        if (length != Array.getLength(copy)) {
            failures.add(path + ": an array of " + length + " in the original but of " + Array.getLength(copy) + " in the copy");
            return;
        }
        boolean primitive = original.getClass().getComponentType().isPrimitive();
        for (int i = 0; i < length; i++) {
            Object originalElement = Array.get(original, i);
            Object copyElement = Array.get(copy, i);
            if (primitive) {
                if (!originalElement.equals(copyElement)) {
                    failures.add(path + "[" + i + "]: " + originalElement + " in the original but " + copyElement + " in the copy");
                    return;
                }
            } else {
                compare(originalElement, copyElement, path + "[" + i + "]", null);
            }
        }
    }

    private void compareCollections(Collection<?> original, Collection<?> copy, String path) {
        if (original.size() != copy.size()) {
            failures.add(path + ": " + original.size() + " elements in the original but " + copy.size() + " in the copy");
            return;
        }
        Iterator<?> copyElements = copy.iterator();
        int index = 0;
        for (Object originalElement : original) {
            compare(originalElement, copyElements.next(), path + "[" + index++ + "]", null);
        }
    }

    private void compareMaps(Map<?, ?> original, Map<?, ?> copy, String path) {
        if (original.size() != copy.size()) {
            failures.add(path + ": " + original.size() + " entries in the original but " + copy.size() + " in the copy");
            return;
        }
        for (Map.Entry<?, ?> entry : original.entrySet()) {
            if (entry.getKey() != null && !isLeaf(entry.getKey())) {
                failures.add(path + ": a map keyed by a mutable " + entry.getKey().getClass().getName());
            } else if (!copy.containsKey(entry.getKey())) {
                failures.add(path + "[" + entry.getKey() + "]: missing from the copy");
            } else {
                compare(entry.getValue(), copy.get(entry.getKey()), path + "[" + entry.getKey() + "]", null);
            }
        }
    }

    private void compareFields(Object original, Object copy, String path) {
        Class<?> runtimeClass = original.getClass();
        for (Class<?> type = runtimeClass; type != null && !isJdkType(type); type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                String key = simpleName(type) + "." + field.getName();
                String fieldPath = path + "." + field.getName();
                Object originalValue;
                Object copyValue;
                try {
                    field.setAccessible(true);
                    originalValue = field.get(original);
                    copyValue = field.get(copy);
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
                if (field.getType().isPrimitive()) {
                    if (isCachedHash(field)) {
                        continue;
                    }
                    if (mode == Mode.COPY && isDefault(originalValue)) {
                        failures.add(fieldPath + ": " + originalValue + " in the fixture, its type's default, so a copy that drops it is not seen; give " + key + " another value");
                    } else {
                        checkDiffersFromANewInstance(runtimeClass, field, key, fieldPath, original, originalValue);
                    }
                    if (!originalValue.equals(copyValue)) {
                        failures.add(fieldPath + ": " + originalValue + " in the original but " + copyValue + " in the copy");
                    }
                    continue;
                }
                if (originalValue == null && mode == Mode.COPY
                    && !NULL_IN_A_COMPLETE_FIXTURE.contains(key) && !NULL_IN_A_COMPLETE_FIXTURE.contains(simpleName(runtimeClass) + ">" + key)
                    && NULL_IN_A_COMPLETE_FIXTURE_AT.stream().noneMatch(fieldPath::endsWith)) {
                    failures.add(fieldPath + ": null in the fixture, so a copy of it is not checked; set " + key + " in the fixture");
                }
                checkDiffersFromANewInstance(runtimeClass, field, key, fieldPath, original, originalValue);
                if (originalValue != null) {
                    observedByDeclaredType.computeIfAbsent(field.getType(), declared -> new LinkedHashSet<>()).add(originalValue.getClass());
                }
                if (NOT_COPIED.contains(key)) {
                    if (copyValue != null && mode == Mode.COPY) {
                        failures.add(fieldPath + ": must not be carried into a copy");
                    }
                    continue;
                }
                if (REBUILT_CACHES.contains(key) && (copyValue == null || mode == Mode.EQUAL)) {
                    continue;
                }
                compare(originalValue, copyValue, fieldPath, key);
            }
        }
    }

    /**
     * A field the fixture leaves as a newly built instance of its class has it (an initializer's value, such as an
     * enum, a string or an empty collection) cannot show that a copy dropped it, since the copy would be built with
     * that value too.
     */
    private void checkDiffersFromANewInstance(Class<?> runtimeClass, Field field, String key, String fieldPath, Object original, Object originalValue) {
        if (mode != Mode.COPY || originalValue == null || originalValue == original || isCachedHash(field)
            || SAME_AS_A_NEW_INSTANCE.containsKey(key) || NOT_COPIED.contains(key) || REBUILT_CACHES.contains(key)) {
            return;
        }
        Object newInstance = newInstance(runtimeClass);
        if (newInstance == null) {
            return;
        }
        try {
            if (Objects.deepEquals(originalValue, field.get(newInstance))) {
                failures.add(fieldPath + ": " + describe(originalValue) + " in the fixture, as in a newly built " + simpleName(runtimeClass)
                    + ", so a copy that leaves it at that value is not seen; give " + key + " another value");
            }
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * An instance of {@code type} built by its constructor with the fewest parameters that accepts nulls, zeros and
     * falses, or null when no constructor does.
     */
    static Object newInstance(Class<?> type) {
        return NEW_INSTANCES.computeIfAbsent(type, DeepCopyWalker::buildNewInstance).orElse(null);
    }

    private static Optional<Object> buildNewInstance(Class<?> type) {
        List<Constructor<?>> constructors = new ArrayList<>(Arrays.asList(type.getDeclaredConstructors()));
        constructors.sort(Comparator.<Constructor<?>>comparingInt(Constructor::getParameterCount).thenComparing(Constructor::toString));
        for (Constructor<?> constructor : constructors) {
            Object[] arguments = new Object[constructor.getParameterCount()];
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            for (int i = 0; i < arguments.length; i++) {
                arguments[i] = parameterTypes[i].isPrimitive() ? Array.get(Array.newInstance(parameterTypes[i], 1), 0) : null;
            }
            try {
                constructor.setAccessible(true);
                return Optional.of(constructor.newInstance(arguments));
            } catch (ReflectiveOperationException | RuntimeException refused) {
                // try the next constructor
            }
        }
        return Optional.empty();
    }

    // the only primitives a copy need not carry: cached hashes, which every copy recomputes
    private static boolean isCachedHash(Field field) {
        return field.getName().equals("hashCode");
    }

    private static boolean isDefault(Object primitive) {
        return primitive.equals(Boolean.FALSE) || primitive instanceof Number && ((Number) primitive).doubleValue() == 0 || primitive.equals('\0');
    }

    /**
     * Gives every primitive field reachable from {@code root} that holds its type's default another value, so the
     * walk can tell a copied primitive from a dropped one. Fields the walk does not compare are left alone.
     */
    static <T> T perturbDefaultPrimitives(T root) {
        perturb(root, new IdentityHashMap<>());
        return root;
    }

    private static void perturb(Object value, IdentityHashMap<Object, Boolean> visited) {
        if (value == null || isLeaf(value) || value instanceof JsonNode || visited.put(value, Boolean.TRUE) != null) {
            return;
        }
        if (value.getClass().isArray()) {
            if (!value.getClass().getComponentType().isPrimitive()) {
                for (int i = 0; i < Array.getLength(value); i++) {
                    perturb(Array.get(value, i), visited);
                }
            }
        } else if (value instanceof Collection) {
            ((Collection<?>) value).forEach(element -> perturb(element, visited));
        } else if (value instanceof Map) {
            ((Map<?, ?>) value).values().forEach(element -> perturb(element, visited));
        } else if (!isJdkType(value.getClass())) {
            for (Class<?> type = value.getClass(); type != null && !isJdkType(type); type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    String key = simpleName(type) + "." + field.getName();
                    if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic() || NOT_COPIED.contains(key) || REBUILT_CACHES.contains(key)) {
                        continue;
                    }
                    try {
                        field.setAccessible(true);
                        Object current = field.get(value);
                        if (field.getType().isPrimitive()) {
                            if (!isCachedHash(field) && isDefault(current)) {
                                field.set(value, nonDefault(field.getType()));
                            }
                        } else {
                            perturb(current, visited);
                        }
                    } catch (IllegalAccessException e) {
                        throw new AssertionError(e);
                    }
                }
            }
        }
    }

    private static Object nonDefault(Class<?> type) {
        if (type == boolean.class) {
            return true;
        } else if (type == char.class) {
            return 'x';
        } else if (type == byte.class) {
            return (byte) 1;
        } else if (type == short.class) {
            return (short) 1;
        } else if (type == int.class) {
            return 1;
        } else if (type == long.class) {
            return 1L;
        } else if (type == float.class) {
            return 1f;
        }
        return 1d;
    }

    private static boolean isImmutableContainer(Object value) {
        if (value.getClass().isArray()) {
            return Array.getLength(value) == 0;
        }
        String name = value.getClass().getName();
        return name.startsWith("java.util.ImmutableCollections$")
            || name.startsWith("java.util.Collections$Unmodifiable")
            || name.startsWith("java.util.Collections$Empty")
            || name.startsWith("java.util.Collections$Singleton");
    }

    private static boolean isJdkType(Class<?> type) {
        String name = type.getName();
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.") || name.startsWith("sun.");
    }

    static String simpleName(Class<?> type) {
        return type.getName().substring(type.getPackageName().length() + 1);
    }

    private static String describe(Object value) {
        return isLeaf(value) ? String.valueOf(value) : "a " + value.getClass().getName();
    }

    static boolean sameValues(Object original, Object other) {
        return new DeepCopyWalker(Mode.EQUAL).walk(original, other, "").failures().isEmpty();
    }

    static String describeFailures(List<String> failures) {
        return String.join("\n", failures);
    }
}

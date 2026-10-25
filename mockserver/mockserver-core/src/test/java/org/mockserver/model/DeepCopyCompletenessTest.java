package org.mockserver.model;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.jar.JarFile;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThrows;
import static org.mockserver.model.DeepCopyWalker.describeFailures;

/**
 * Proves {@link HttpRequest#deepCopy()}, {@link HttpResponse#deepCopy()} and {@link Body#copy()} complete: for every
 * concrete body found in {@code org.mockserver.model}, and every shape of the remote address, a fixture with every
 * field set is copied and walked through every reachable field. Each mutable object must be a different instance
 * holding the same values; only immutable values are shared. A field added to the model is null in the fixtures, and
 * fails here until a fixture sets it and the copy handles it; a body added fails until a fixture covers it.
 */
public class DeepCopyCompletenessTest {

    @Test
    public void shouldHaveAFixtureForEveryConcreteBody() {
        Set<String> fixtureBodies = new TreeSet<>();
        for (Supplier<Body<?>> body : DataPlaneCopyFixtures.bodies().values()) {
            fixtureBodies.add(body.get().getClass().getName());
        }

        assertThat(fixtureBodies, is(concreteSubclassesOf(Body.class)));
    }

    @Test
    public void shouldCopyARequestWithEveryBodyAndEveryRemoteAddressShape() {
        DeepCopyWalker walker = new DeepCopyWalker(DeepCopyWalker.Mode.COPY);
        for (Map.Entry<String, Supplier<Body<?>>> body : DataPlaneCopyFixtures.bodies().entrySet()) {
            for (Map.Entry<String, Supplier<Object>> remoteAddress : DataPlaneCopyFixtures.remoteAddresses().entrySet()) {
                HttpRequest original = DataPlaneCopyFixtures.request(body.getValue().get(), remoteAddress.getValue().get());

                HttpRequest copy = original.deepCopy();

                walker.walk(original, copy, "request with " + body.getKey() + " and a " + remoteAddress.getKey() + " remote address");
                assertThat(copy, is(original));
                assertThat(copy.isBodyAsReceived(), is(true));
            }
        }

        assertThat(describeFailures(walker.failures()), walker.failures(), is(empty()));
        assertEveryConcreteSubclassObserved(walker);
    }

    @Test
    public void shouldCopyAResponseWithEveryBodyAResponseCanHave() {
        DeepCopyWalker walker = new DeepCopyWalker(DeepCopyWalker.Mode.COPY);
        Set<String> responseBodies = new TreeSet<>();
        for (Map.Entry<String, Supplier<Body<?>>> body : DataPlaneCopyFixtures.bodies().entrySet()) {
            // a body with no content type is in a response only as the body a verification matches
            BodyWithContentType<?> withContentType = body.getValue().get() instanceof BodyWithContentType<?> bodyWithContentType
                ? bodyWithContentType
                : Not.not(new ResponseMatchingBody(body.getValue().get()));
            {
                responseBodies.add(withContentType.getClass().getName());
                HttpResponse original = DataPlaneCopyFixtures.response(withContentType);

                HttpResponse copy = original.deepCopy();

                walker.walk(original, copy, "response with " + body.getKey());
                assertThat(copy, is(original));
                assertThat(copy.getExpectationId(), is(original.getExpectationId()));
                assertThat(copy.getStreamingBody(), nullValue());
            }
        }

        assertThat(responseBodies, is(concreteSubclassesOf(BodyWithContentType.class)));
        assertThat(describeFailures(walker.failures()), walker.failures(), is(empty()));
        assertEveryConcreteSubclassObserved(walker);
    }

    @Test
    public void shouldCopyEveryBodyOnItsOwn() {
        DeepCopyWalker walker = new DeepCopyWalker(DeepCopyWalker.Mode.COPY);
        for (Map.Entry<String, Supplier<Body<?>>> body : DataPlaneCopyFixtures.bodies().entrySet()) {
            Body<?> original = body.getValue().get();

            Body<?> copy = original.copy();

            walker.walk(original, copy, body.getKey());
            assertThat(body.getKey(), copy, is(original));
            assertThat(body.getKey(), copy.hashCode(), is(original.hashCode()));
            assertThat(body.getKey(), copy.toString(), is(original.toString()));
        }

        assertThat(describeFailures(walker.failures()), walker.failures(), is(empty()));
    }

    @Test
    public void shouldFailTheWalkForASharedMutableMemberOrADroppedField() {
        HttpRequest original = DataPlaneCopyFixtures.request(DataPlaneCopyFixtures.bodies().get("ParameterBody").get(), "10.0.0.1:1");
        HttpRequest shared = original.deepCopy().withHeaders(original.getHeaders());
        HttpRequest dropped = original.deepCopy().withJwt(null);

        assertThat(new DeepCopyWalker(DeepCopyWalker.Mode.COPY).walk(original, shared, "shared").failures(), is(not(empty())));
        assertThat(new DeepCopyWalker(DeepCopyWalker.Mode.COPY).walk(original, dropped, "dropped").failures(), is(not(empty())));
        assertThat(new DeepCopyWalker(DeepCopyWalker.Mode.COPY).walk(original, original.clone(), "clone").failures(), is(not(empty())));
    }

    @Test
    public void shouldFailTheWalkForADroppedPrimitiveOrAPrimitiveTheFixtureLeftAtItsDefault() {
        HttpRequest original = DataPlaneCopyFixtures.request(DataPlaneCopyFixtures.bodies().get("RegexBody").get(), "10.0.0.1:1");
        HttpRequest dropped = original.deepCopy().setLoadGenerated(false);
        HttpRequest leftAtDefault = DataPlaneCopyFixtures.request(DataPlaneCopyFixtures.bodies().get("RegexBody").get(), "10.0.0.1:1").setLoadGenerated(false);

        assertThat(new DeepCopyWalker(DeepCopyWalker.Mode.COPY).walk(original, dropped, "dropped").failures(),
            contains("dropped.loadGenerated: true in the original but false in the copy"));
        assertThat(new DeepCopyWalker(DeepCopyWalker.Mode.COPY).walk(leftAtDefault, leftAtDefault.deepCopy(), "default").failures(),
            contains(startsWith("default.loadGenerated: false in the fixture, its type's default")));
        assertThat(DeepCopyWalker.perturbDefaultPrimitives(leftAtDefault).isLoadGenerated(), is(true));
    }

    @Test
    public void shouldFailTheWalkForAFieldTheFixtureLeftAsANewInstanceHasIt() {
        HttpRequest leftAsNew = DataPlaneCopyFixtures.request(DataPlaneCopyFixtures.bodies().get("RegexBody").get(), "10.0.0.1:1");
        leftAsNew.getJwt().withHeader(Jwt.jwt().getHeader());

        assertThat(new DeepCopyWalker(DeepCopyWalker.Mode.COPY).walk(leftAsNew, leftAsNew.deepCopy(), "new").failures(),
            contains(startsWith("new.jwt.header: " + Jwt.jwt().getHeader() + " in the fixture, as in a newly built Jwt")));
    }

    @Test
    public void shouldBuildANewInstanceOfEveryWalkedClassButAWrapperThatRefusesANullBody() {
        DeepCopyWalker walker = new DeepCopyWalker(DeepCopyWalker.Mode.COPY);
        for (Supplier<Body<?>> body : DataPlaneCopyFixtures.bodies().values()) {
            HttpRequest request = DataPlaneCopyFixtures.request(body.get(), DataPlaneCopyFixtures.remoteAddresses().get("received").get());
            walker.walk(request, request.deepCopy(), "request");
            if (body.get() instanceof BodyWithContentType<?> withContentType) {
                HttpResponse response = DataPlaneCopyFixtures.response(withContentType);
                walker.walk(response, response.deepCopy(), "response");
            }
        }
        Set<String> withoutANewInstance = new TreeSet<>();
        for (Class<?> type : walker.observedClasses()) {
            boolean leaf = type.isEnum() || NottableString.class.isAssignableFrom(type) || type == MediaType.class;
            if (!leaf && type.getName().startsWith("org.mockserver.") && DeepCopyWalker.newInstance(type) == null) {
                withoutANewInstance.add(type.getSimpleName());
            }
        }

        // its only own field, the wrapped body, has no initializer; its inherited fields are checked on the other bodies
        assertThat(withoutANewInstance, contains("ResponseMatchingBody"));
    }

    @Test
    public void shouldRefuseToCopyABodyThatDoesNotImplementCopy() {
        Body<String> custom = new Body<>(Body.Type.STRING) {
            @Override
            public String getValue() {
                return "custom";
            }
        };

        UnsupportedOperationException refused = assertThrows(UnsupportedOperationException.class, custom::copy);

        assertThat(refused.getMessage(), is(custom.getClass().getName() + " does not implement copy(), which a data-plane request filter or response transformer needs"));
    }

    @Test
    public void shouldResetTheDerivedBytesOfAReceivedRemoteAddressButKeepBytesWithNoAddressToDeriveThemFrom() {
        HttpRequest received = new HttpRequest().withReceivedRemoteAddress((HttpRequest.RemoteAddress) DataPlaneCopyFixtures.remoteAddresses().get("received").get());
        HttpRequest bytesOnly = new HttpRequest().withRemoteAddress("10.0.0.3:1").withRemoteAddressBytes(new byte[]{10, 0, 0, 3});

        Object receivedCopy = DataPlaneCopyFixtures.remoteAddressOf(received.deepCopy());
        Object bytesOnlyCopy = DataPlaneCopyFixtures.remoteAddressOf(bytesOnly.deepCopy());

        assertThat(DataPlaneCopyFixtures.getField(receivedCopy, "bytes"), nullValue());
        assertThat(received.deepCopy().getRemoteAddressBytes(), is(received.getRemoteAddressBytes()));
        assertThat(DataPlaneCopyFixtures.getField(bytesOnlyCopy, "bytes") == DataPlaneCopyFixtures.getField(DataPlaneCopyFixtures.remoteAddressOf(bytesOnly), "bytes"), is(false));
        assertThat(bytesOnly.deepCopy().getRemoteAddressBytes(), is(new byte[]{10, 0, 0, 3}));
    }

    private static void assertEveryConcreteSubclassObserved(DeepCopyWalker walker) {
        Set<Class<?>> observed = walker.observedClasses();
        List<String> unobserved = new ArrayList<>();
        for (Class<?> declaredType : walker.observedByDeclaredType().keySet()) {
            if (declaredType.getPackageName().equals(Body.class.getPackageName()) && !Modifier.isFinal(declaredType.getModifiers())) {
                for (String subclass : concreteSubclassesOf(declaredType)) {
                    if (observed.stream().noneMatch(type -> type.getName().equals(subclass))) {
                        unobserved.add(subclass + " (a " + declaredType.getSimpleName() + ")");
                    }
                }
            }
        }
        assertThat("concrete model types a walked field can hold but no fixture holds", unobserved, is(empty()));
    }

    // the concrete classes of the model package, scanned once
    private static List<Class<?>> concreteModelClasses;

    private static synchronized Set<String> concreteSubclassesOf(Class<?> type) {
        if (concreteModelClasses == null) {
            concreteModelClasses = new ArrayList<>();
            for (String name : modelClassNames()) {
                Class<?> candidate = loadClass(name);
                if (!Modifier.isAbstract(candidate.getModifiers()) && !candidate.isInterface() && !candidate.isAnonymousClass()
                    && !NottableString.class.isAssignableFrom(candidate)) {
                    concreteModelClasses.add(candidate);
                }
            }
        }
        Set<String> concrete = new TreeSet<>();
        for (Class<?> candidate : concreteModelClasses) {
            if (type.isAssignableFrom(candidate)) {
                concrete.add(candidate.getName());
            }
        }
        return concrete;
    }

    // every class in the model package, read from the directories and jars the class loader finds it in
    private static Set<String> modelClassNames() {
        String packagePath = Body.class.getPackageName().replace('.', '/');
        Set<String> names = new TreeSet<>();
        try {
            Enumeration<URL> locations = DeepCopyCompletenessTest.class.getClassLoader().getResources(packagePath);
            while (locations.hasMoreElements()) {
                URL location = locations.nextElement();
                if (location.getProtocol().equals("file")) {
                    String[] files = new File(location.toURI()).list();
                    for (String file : files != null ? files : new String[0]) {
                        addClassName(names, packagePath + "/" + file);
                    }
                } else if (location.getProtocol().equals("jar")) {
                    try (JarFile jar = ((JarURLConnection) location.openConnection()).getJarFile()) {
                        jar.stream().forEach(entry -> addClassName(names, entry.getName()));
                    }
                }
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertThat("classes found in " + packagePath, names.contains(Body.class.getName()), is(true));
        return names;
    }

    private static void addClassName(Set<String> names, String path) {
        String packagePath = Body.class.getPackageName().replace('.', '/') + "/";
        if (path.startsWith(packagePath) && path.endsWith(".class") && path.indexOf('/', packagePath.length()) < 0) {
            names.add(path.substring(0, path.length() - ".class".length()).replace('/', '.'));
        }
    }

    private static Class<?> loadClass(String name) {
        try {
            return Class.forName(name, false, DeepCopyCompletenessTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }
}

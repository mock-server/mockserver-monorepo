package org.mockserver.test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a probe's {@code main} in a fresh JVM that logs every class it loads, for tests that assert a server with no
 * extension configured loads no extension class and none of the classes that only a loaded data-plane hook may load.
 * The probe prints {@link #COMPLETED} once it has done everything it was asked to, and exits 0.
 */
public final class ClassLoadingProbe {

    public static final String COMPLETED = "CLASS-LOADING-PROBE-COMPLETED";
    private static final Pattern LOADED_CLASS = Pattern.compile("\\[class,load\\s*] (\\S+) source:");
    // classes a data-plane hook needs, which must load only when a hook is: a writer is the per-request class
    // that must not change, so the unloaded path builds today's writer and never names these
    private static final Set<String> DATA_PLANE_HOOK_CLASS_NAMES = Set.of(
        "DataPlaneHooks", "DataPlaneCall", "DataPlaneWriters", "CoreDataPlaneWriters", "TransformingWriter"
    );

    private ClassLoadingProbe() {
    }

    /**
     * Whether a loaded class is an extension class or a data-plane hook class, in any package and nested or not:
     * {@code org.mockserver.extension.*}, {@code *.DataPlaneHooks}, {@code *.DataPlaneCall},
     * {@code *.DataPlaneWriters}, {@code *.CoreDataPlaneWriters}, {@code *.TransformingWriter},
     * {@code *.Transforming*ResponseWriter} and {@code InProcessMockServer$Transforming*}.
     */
    public static boolean isExtensionOrDataPlaneHookClass(String className) {
        if (className.startsWith("org.mockserver.extension.")) {
            return true;
        }
        String[] names = className.substring(className.lastIndexOf('.') + 1).split("\\$");
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            if (DATA_PLANE_HOOK_CLASS_NAMES.contains(name)
                || name.startsWith("Transforming") && name.endsWith("ResponseWriter")
                || i > 0 && name.startsWith("Transforming") && names[0].equals("InProcessMockServer")) {
                return true;
            }
        }
        return false;
    }

    public static List<String> extensionOrDataPlaneHookClasses(List<String> loadedClasses) {
        List<String> matching = new ArrayList<>();
        for (String loadedClass : loadedClasses) {
            if (isExtensionOrDataPlaneHookClass(loadedClass)) {
                matching.add(loadedClass);
            }
        }
        return matching;
    }

    public static Run run(Class<?> probe, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(Arrays.asList(
            System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
            "-Xmx256m",
            "-Xlog:class+load=info:stdout",
            "-Dmockserver.logLevel=ERROR",
            "-Dmockserver.startupWarmup=false",
            "-cp", System.getProperty("java.class.path"),
            probe.getName()
        ));
        command.addAll(Arrays.asList(arguments));
        // output goes to a file, not a pipe, so waitFor's bound holds even if the probe hangs
        Path outputFile = Files.createTempFile("class-loading-probe", ".log");
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(outputFile.toFile());
            processBuilder.environment().remove("JAVA_TOOL_OPTIONS");
            Process process = processBuilder.start();
            boolean exited = process.waitFor(120, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
            return parse(exited && process.exitValue() == 0, new String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }

    private static Run parse(boolean exitedCleanly, String text) {
        List<String> loadedClasses = new ArrayList<>();
        Matcher matcher = LOADED_CLASS.matcher(text);
        while (matcher.find()) {
            loadedClasses.add(matcher.group(1));
        }
        boolean completed = exitedCleanly && text.contains(COMPLETED);
        return new Run(completed, loadedClasses, completed ? "" : text.substring(Math.max(0, text.length() - 4000)));
    }

    public static final class Run {
        public final boolean completed;
        public final List<String> loadedClasses;
        public final String output;

        private Run(boolean completed, List<String> loadedClasses, String output) {
            this.completed = completed;
            this.loadedClasses = loadedClasses;
            this.output = output;
        }
    }
}

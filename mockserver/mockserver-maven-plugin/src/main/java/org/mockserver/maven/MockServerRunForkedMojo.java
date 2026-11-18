package org.mockserver.maven;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.repository.RepositorySystem;
import org.mockserver.cli.Main;
import org.mockserver.client.MockServerClient;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.maven.InstanceHolder.runInitialization;

/**
 * Run a forked instance of the MockServer
 * <p>
 * To run from command line:
 * <p>
 * mvn -Dmockserver.serverPort="1080" -Dmockserver.logLevel="TRACE" org.mock-server:mockserver-maven-plugin:5.5.4:runForked
 *
 * @author jamesdbloom
 */
@Mojo(name = "runForked", requiresProject = false)
public class MockServerRunForkedMojo extends MockServerAbstractMojo {

    /**
     * Set JVM options for forked JVM
     */
    @Parameter(property = "mockserver.jvmOptions")
    protected String jvmOptions;

    /**
     * Used to look up Artifacts in the remote repository.
     */
    @Component
    protected RepositorySystem repositorySystem;
    private ProcessBuildFactory processBuildFactory = new ProcessBuildFactory();
    @VisibleForTesting
    int startAttempts = 150;

    private MockServerClient mockServerClient = getServerPorts() != null && getServerPorts().length > 0 ? new MockServerClient("localhost", getServerPorts()[0]) : null;

    private static String fileSeparators(String path) {
        StringBuilder ret = new StringBuilder();
        for (char c : path.toCharArray()) {
            if ((c == '/') || (c == '\\')) {
                ret.append(File.separatorChar);
            } else {
                ret.append(c);
            }
        }
        return ret.toString();
    }

    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Skipping plugin execution");
        } else {
            getLocalMockServerInstance().stop(getServerPorts(), true);
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                throw new RuntimeException("Exception while waiting for existing mock server JVM to stop", e);
            }
            if (getLog().isInfoEnabled()) {
                getLog().info("mockserver:runForked about to start MockServer on: "
                        + (getServerPorts() != null ? " serverPort " + Arrays.toString(getServerPorts()) : "")
                );
            }
            List<String> arguments = new ArrayList<>(Collections.singletonList(getJavaBin()));
//            arguments.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5010");
            arguments.add("-Dfile.encoding=UTF-8");
            // CVE-2026-114938 workaround; before jvmOptions so a user can still deliberately re-enable it
            arguments.add("-XX:-UseBASE64Intrinsics");
            if (isNotBlank(jvmOptions)) {
                arguments.add(jvmOptions);
            }
            arguments.add("-cp");
            StringBuilder classPath = new StringBuilder(resolvePathForJarWithDependencies());
            if (dependencies != null && !dependencies.isEmpty()) {
                for (Dependency dependency : dependencies) {
                    classPath.append(System.getProperty("path.separator"));
                    classPath.append(resolvePathForDependencyJar(dependency));
                }
            }
            arguments.add(classPath.toString());
            arguments.add(Main.class.getName());
            if (getServerPorts() != null) {
                arguments.add("-serverPort");
                arguments.add("" + Joiner.on(",").join(getServerPorts()));
                MockServerAbstractMojo.mockServerPort(getServerPorts());
            }
            if (proxyRemotePort != -1) {
                arguments.add("-proxyRemotePort");
                arguments.add("" + proxyRemotePort);
            }
            if (!Strings.isNullOrEmpty(proxyRemoteHost)) {
                arguments.add("-proxyRemoteHost");
                arguments.add("" + proxyRemoteHost);
            }
            if (!Strings.isNullOrEmpty(logLevel)) {
                arguments.add("-logLevel");
                arguments.add("" + logLevel);
            }
            getLog().info(" ");
            String message = Joiner.on(" ").join(arguments);
            getLog().info(StringUtils.rightPad("", message.length(), "-"));
            getLog().info(message);
            getLog().info(StringUtils.rightPad("", message.length(), "-"));
            getLog().info(" ");
            ProcessBuilder processBuilder = processBuildFactory.create(arguments);
            if (pipeLogToConsole) {
                processBuilder.inheritIO();
            }
            Process process;
            try {
                process = processBuilder.start();
            } catch (IOException e) {
                throw new MojoExecutionException("MockServer did not start: " + e.getMessage(), e);
            }
            if (getServerPorts() != null && getServerPorts().length > 0) {
                if (mockServerClient == null) {
                    mockServerClient = new MockServerClient("localhost", getServerPorts()[0]);
                }
                waitUntilStarted(process);
                getLog().info("mockserver:runForked MockServer is running on: "
                        + (getServerPorts() != null ? " serverPort " + Arrays.toString(getServerPorts()) : "")
                );
            }
            runInitialization(getServerPorts(), createInitializerClass(), createInitializerJson());
        }

    }

    /**
     * Fails the build, as the start goal does, when the forked JVM exits before MockServer answers (it refused to
     * start, for example because a port it was given is in use) or does not answer in time, when it is stopped.
     */
    private void waitUntilStarted(Process process) throws MojoExecutionException {
        for (int attempt = 0; attempt < startAttempts; attempt++) {
            if (mockServerClient.hasStarted(0, 0, MILLISECONDS)) {
                return;
            }
            if (!process.isAlive()) {
                throw new MojoExecutionException("MockServer did not start: its JVM exited with status " + process.exitValue()
                    + (pipeLogToConsole ? ", see its output above" : ", set pipeLogToConsole to see its output"));
            }
            try {
                MILLISECONDS.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        process.destroy();
        throw new MojoExecutionException("MockServer did not start: timed out waiting for it on serverPort " + Arrays.toString(getServerPorts()));
    }

    @VisibleForTesting
    String getJavaBin() {
        String javaBinary = "java";

        File javaHomeDirectory = new File(System.getProperty("java.home"));
        for (String javaExecutable : new String[]{"java", "java.exe"}) {
            File javaExeLocation = new File(javaHomeDirectory, fileSeparators("bin/" + javaExecutable));
            if (javaExeLocation.exists() && javaExeLocation.isFile()) {
                javaBinary = javaExeLocation.getAbsolutePath();
                break;
            }
        }

        return javaBinary;
    }

    private String resolvePathForDependencyJar(Dependency dependency) {
        String path = "";
        Artifact dependencyArtifact = repositorySystem.createArtifactWithClassifier(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion(), dependency.getType(), dependency.getClassifier());

        ArtifactResolutionRequest request = new ArtifactResolutionRequest();
        request.setArtifact(dependencyArtifact);

        request.setResolveRoot(true).setResolveTransitively(false);
        if (session != null && session.getRequest() != null) {
            request.setServers(session.getRequest().getServers());
            request.setMirrors(session.getRequest().getMirrors());
            request.setProxies(session.getRequest().getProxies());
            request.setLocalRepository(session.getLocalRepository());
            request.setRemoteRepositories(session.getRequest().getRemoteRepositories());
        }
        repositorySystem.resolve(request);
        if (dependencyArtifact != null && dependencyArtifact.getFile() != null) {
            path = dependencyArtifact.getFile().getAbsolutePath();
        }
        return path;
    }

    private String resolvePathForJarWithDependencies() {
        Dependency dependency = new Dependency();
        dependency.setGroupId("org.mock-server");
        dependency.setArtifactId("mockserver-netty-no-dependencies");
        dependency.setVersion(getVersion());
        dependency.setType("jar");
        return resolvePathForDependencyJar(dependency);
    }

    @VisibleForTesting
    String getVersion() {
        String version = "5.5.4";
        try {
            java.util.Properties p = new java.util.Properties();
            InputStream is = getClass().getResourceAsStream("/META-INF/maven/org.mock-server/mockserver-maven-plugin/pom.properties");
            if (is != null) {
                p.load(is);
                version = p.getProperty("version", "5.5.4");
            }
        } catch (Exception e) {
            // ignore
        }
        getLog().info("Using org.mock-server:mockserver-netty-no-dependencies:" + version);
        return version;
    }
}

package org.mockserver.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.repository.RepositorySystem;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockserver.client.MockServerClient;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * @author jamesdbloom
 */
public class MockServerRunForkedMojoTest {

    private final String jarWithDependenciesPath = "";
    private String javaBinaryPath;
    @Mock
    private RepositorySystem mockRepositorySystem;
    @Mock
    private ProcessBuildFactory mockProcessBuildFactory;
    @Mock
    private MockServerClient mockServerClient;
    @InjectMocks
    private MockServerRunForkedMojo mockServerRunForkedMojo;
    private ProcessBuilder processBuilder;
    @Mock
    private Artifact mockArtifact;

    @Before
    public void setupMocks() {
        processBuilder = new ProcessBuilder("echo", "");
        mockServerRunForkedMojo = new MockServerRunForkedMojo();
        javaBinaryPath = mockServerRunForkedMojo.getJavaBin();

        openMocks(this);

        when(mockRepositorySystem.createArtifactWithClassifier("org.mock-server", "mockserver-netty-no-dependencies", mockServerRunForkedMojo.getVersion(), "jar", "")).thenReturn(mockArtifact);
        when(mockServerClient.hasStarted(anyInt(), anyLong(), any(TimeUnit.class))).thenReturn(true);
    }

    @Test
    public void shouldRunMockServerForkedLocalPortSpecified() throws Exception {
        // given
        mockServerRunForkedMojo.serverPort = "1,2";
        mockServerRunForkedMojo.logLevel = "LEVEL";
        mockServerRunForkedMojo.pipeLogToConsole = true;
        mockServerRunForkedMojo.jvmOptions = "-Dfoo=bar";
        when(mockProcessBuildFactory.create(anyList())).thenReturn(processBuilder);


        // when
        mockServerRunForkedMojo.execute();

        // then
        verify(mockRepositorySystem).createArtifactWithClassifier("org.mock-server", "mockserver-netty-no-dependencies", mockServerRunForkedMojo.getVersion(), "jar", null);
        verify(mockProcessBuildFactory).create(Arrays.asList(
                javaBinaryPath,
                "-Dfile.encoding=UTF-8",
                "-XX:-UseBASE64Intrinsics",
                "-Dfoo=bar",
                "-cp", jarWithDependenciesPath, "org.mockserver.cli.Main",
                "-serverPort", "1,2",
                "-logLevel", "LEVEL"
        ));
        verify(mockServerClient).hasStarted(anyInt(), anyLong(), any(TimeUnit.class));
        assertEquals(ProcessBuilder.Redirect.INHERIT, processBuilder.redirectError());
        assertEquals(ProcessBuilder.Redirect.INHERIT, processBuilder.redirectOutput());
    }

    @Test
    public void shouldRunMockServerForkedPortForwarding() throws Exception {
        // given
        mockServerRunForkedMojo.serverPort = "1,2";
        mockServerRunForkedMojo.proxyRemotePort = 3;
        mockServerRunForkedMojo.proxyRemoteHost = "remoteHost";
        mockServerRunForkedMojo.logLevel = "LEVEL";
        mockServerRunForkedMojo.pipeLogToConsole = true;
        mockServerRunForkedMojo.jvmOptions = "-Dfoo=bar";
        when(mockProcessBuildFactory.create(anyList())).thenReturn(processBuilder);


        // when
        mockServerRunForkedMojo.execute();

        // then
        verify(mockRepositorySystem).createArtifactWithClassifier("org.mock-server", "mockserver-netty-no-dependencies", mockServerRunForkedMojo.getVersion(), "jar", null);
        verify(mockProcessBuildFactory).create(Arrays.asList(
                javaBinaryPath,
                "-Dfile.encoding=UTF-8",
                "-XX:-UseBASE64Intrinsics",
                "-Dfoo=bar",
                "-cp", jarWithDependenciesPath, "org.mockserver.cli.Main",
                "-serverPort", "1,2",
                "-proxyRemotePort", "3",
                "-proxyRemoteHost", "remoteHost",
                "-logLevel", "LEVEL"
        ));
        verify(mockServerClient).hasStarted(anyInt(), anyLong(), any(TimeUnit.class));
        assertEquals(ProcessBuilder.Redirect.INHERIT, processBuilder.redirectError());
        assertEquals(ProcessBuilder.Redirect.INHERIT, processBuilder.redirectOutput());
    }

    @Test
    public void shouldRunMockServerWithInitializer() throws Exception {
        // given
        ExampleInitializationClass.mockServerClient = null;
        mockServerRunForkedMojo.serverPort = "1,2";
        mockServerRunForkedMojo.pipeLogToConsole = true;
        mockServerRunForkedMojo.initializationClass = "org.mockserver.maven.ExampleInitializationClass";
        String classLocation = "org/mockserver/maven/ExampleInitializationClass.class";
        mockServerRunForkedMojo.compileClasspath = Collections.singletonList(ExampleInitializationClass.class.getClassLoader().getResource(classLocation).getFile().replaceAll(classLocation, ""));
        mockServerRunForkedMojo.testClasspath = Collections.emptyList();
        mockServerRunForkedMojo.jvmOptions = "-Dfoo=bar";
        when(mockProcessBuildFactory.create(anyList())).thenReturn(processBuilder);

        // when
        mockServerRunForkedMojo.execute();

        // then
        verify(mockProcessBuildFactory).create(Arrays.asList(
                javaBinaryPath,
                "-Dfile.encoding=UTF-8",
                "-XX:-UseBASE64Intrinsics",
                "-Dfoo=bar",
                "-cp", jarWithDependenciesPath, "org.mockserver.cli.Main",
                "-serverPort", "1,2",
                "-logLevel", "INFO"
        ));
        verify(mockServerClient).hasStarted(anyInt(), anyLong(), any(TimeUnit.class));
        assertEquals(ProcessBuilder.Redirect.INHERIT, processBuilder.redirectError());
        assertEquals(ProcessBuilder.Redirect.INHERIT, processBuilder.redirectOutput());
        assertNotNull(ExampleInitializationClass.mockServerClient);
    }

    @Test
    public void shouldFailTheBuildWhenTheForkedJvmCannotBeStarted() throws Exception {
        // given
        when(mockProcessBuildFactory.create(anyList())).thenReturn(new ProcessBuilder("TEST FAIL"));

        // when
        MojoExecutionException failed = assertThrows(MojoExecutionException.class, () -> mockServerRunForkedMojo.execute());

        // then
        assertThat(failed.getMessage(), startsWith("MockServer did not start: "));
        assertThat(failed.getCause(), instanceOf(IOException.class));
    }

    @Test
    public void shouldFailTheBuildWhenTheForkedJvmExitsBeforeMockServerStarts() throws Exception {
        // given: a forked JVM that refuses to start, as the command line does, exits with a non-zero status
        mockServerRunForkedMojo.serverPort = "1";
        mockServerRunForkedMojo.pipeLogToConsole = true;
        when(mockProcessBuildFactory.create(anyList())).thenReturn(new ProcessBuilder("sh", "-c", "exit 3"));
        when(mockServerClient.hasStarted(anyInt(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        // when
        MojoExecutionException failed = assertThrows(MojoExecutionException.class, () -> mockServerRunForkedMojo.execute());

        // then
        assertThat(failed.getMessage(), is("MockServer did not start: its JVM exited with status 3, see its output above"));
    }

    @Test
    public void shouldFailTheBuildAndStopTheForkedJvmWhenMockServerDoesNotStartInTime() throws Exception {
        // given
        mockServerRunForkedMojo.serverPort = "1";
        mockServerRunForkedMojo.startAttempts = 2;
        when(mockProcessBuildFactory.create(anyList())).thenReturn(new ProcessBuilder("sleep", "60"));
        when(mockServerClient.hasStarted(anyInt(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        // when
        MojoExecutionException failed = assertThrows(MojoExecutionException.class, () -> mockServerRunForkedMojo.execute());

        // then
        assertThat(failed.getMessage(), is("MockServer did not start: timed out waiting for it on serverPort [1]"));
        verify(mockServerClient, times(2)).hasStarted(anyInt(), anyLong(), any(TimeUnit.class));
        long deadline = System.currentTimeMillis() + 10_000;
        while (sleepingChildren() > 0 && System.currentTimeMillis() < deadline) {
            MILLISECONDS.sleep(50);
        }
        assertThat("the forked JVM is stopped", sleepingChildren(), is(0L));
    }

    private static long sleepingChildren() {
        return ProcessHandle.current().children()
            .filter(ProcessHandle::isAlive)
            .filter(child -> child.info().command().map(command -> command.endsWith("sleep")).orElse(false))
            .count();
    }

    @Test
    public void shouldRunMockServerForkedAndNotPipeToConsole() throws Exception {
        // given
        mockServerRunForkedMojo.serverPort = "1,2";
        mockServerRunForkedMojo.pipeLogToConsole = false;
        when(mockProcessBuildFactory.create(anyList())).thenReturn(processBuilder);

        // when
        mockServerRunForkedMojo.execute();

        // then
        verify(mockRepositorySystem).createArtifactWithClassifier("org.mock-server", "mockserver-netty-no-dependencies", mockServerRunForkedMojo.getVersion(), "jar", null);
        verify(mockServerClient).hasStarted(anyInt(), anyLong(), any(TimeUnit.class));
        assertFalse(processBuilder.redirectErrorStream());
    }

    @Test
    public void shouldHandleIncorrectInitializationClassName() throws Exception {
        // given
        ExampleInitializationClass.mockServerClient = null;
        mockServerRunForkedMojo.serverPort = "1,2";
        mockServerRunForkedMojo.pipeLogToConsole = true;
        mockServerRunForkedMojo.initializationClass = "org.mockserver.maven.InvalidClassName";
        when(mockProcessBuildFactory.create(anyList())).thenReturn(processBuilder);

        // when
        mockServerRunForkedMojo.execute();

        verify(mockServerClient).hasStarted(anyInt(), anyLong(), any(TimeUnit.class));
        assertNull(ExampleInitializationClass.mockServerClient);
    }

    @Test
    public void shouldSkipStoppingMockServer() throws Exception {
        // given
        mockServerRunForkedMojo.skip = true;

        // when
        mockServerRunForkedMojo.execute();

        // then
        verify(mockProcessBuildFactory, times(0)).create(anyList());
    }
}

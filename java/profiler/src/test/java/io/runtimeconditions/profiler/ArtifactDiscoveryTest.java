package io.runtimeconditions.profiler;

import io.runtimeconditions.profiler.project.ArtifactDiscovery;
import io.runtimeconditions.profiler.project.BuildTool;
import io.runtimeconditions.profiler.project.DiscoveryResult;
import io.runtimeconditions.profiler.project.ProjectDiscovery;
import io.runtimeconditions.profiler.project.RuntimeConditionsArtifact;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public final class ArtifactDiscoveryTest {
    private ArtifactDiscoveryTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: ArtifactDiscoveryTest <testdata>");
        }
        Path testdata = Path.of(args[0]).toAbsolutePath().normalize();
        verifiesMavenResources(testdata.resolve("maven-app"));
        verifiesGradleModules(testdata.resolve("gradle-app"));
        verifiesClasspathJar();
    }

    private static void verifiesMavenResources(Path root) throws Exception {
        DiscoveryResult result = new ProjectDiscovery().discover(root, List.of());
        assertEquals(BuildTool.MAVEN, result.buildTool(), "Maven project should be detected");
        assertEquals(1, result.artifacts().size(), "Maven fixture should expose one artifact");
        RuntimeConditionsArtifact artifact = result.artifacts().get(0);
        assertEquals(RuntimeConditionsArtifact.Kind.BINDING, artifact.kind(), "Maven artifact kind");
        assertContains(artifact.manifestUri(), "META-INF/runtimeconditions/runtimeconditions.bindings.yaml", "Maven manifest URI");
        assertContains(artifact.extensionUri(), "META-INF/runtimeconditions/runtimeconditions.extension.yaml", "Maven extension URI");
    }

    private static void verifiesGradleModules(Path root) throws Exception {
        DiscoveryResult result = new ProjectDiscovery().discover(root, List.of());
        assertEquals(BuildTool.GRADLE, result.buildTool(), "Gradle project should be detected");
        assertEquals(1, result.modules().size(), "Gradle fixture should expose one module");
        assertEquals(1, result.artifacts().size(), "Gradle fixture should expose one artifact");
        RuntimeConditionsArtifact artifact = result.artifacts().get(0);
        assertEquals(RuntimeConditionsArtifact.Kind.PACKAGE, artifact.kind(), "Gradle artifact kind");
        assertContains(artifact.manifestUri(), "META-INF/runtimeconditions/runtimeconditions.package.yaml", "Gradle manifest URI");
        assertContains(artifact.extensionUri(), "META-INF/runtimeconditions/runtimeconditions.extension.yaml", "Gradle extension URI");
    }

    private static void verifiesClasspathJar() throws Exception {
        Path jar = Files.createTempFile("runtimeconditions-artifact", ".jar");
        writeJar(jar);
        List<RuntimeConditionsArtifact> artifacts = new ArtifactDiscovery().discoverClasspathArtifact(jar);
        assertEquals(1, artifacts.size(), "Classpath JAR should expose one artifact");
        RuntimeConditionsArtifact artifact = artifacts.get(0);
        assertEquals(RuntimeConditionsArtifact.Kind.BINDING, artifact.kind(), "JAR artifact kind");
        assertContains(artifact.manifestUri(), "jar:file:", "JAR manifest URI scheme");
        assertContains(artifact.manifestUri(), "META-INF/runtimeconditions/runtimeconditions.bindings.yaml", "JAR manifest URI");
        assertContains(artifact.extensionUri(), "META-INF/runtimeconditions/runtimeconditions.extension.yaml", "JAR extension URI");
        Files.deleteIfExists(jar);
    }

    private static void writeJar(Path jar) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            writeJarEntry(out, "META-INF/runtimeconditions/runtimeconditions.bindings.yaml", """
                    apiVersion: runtimeconditions.io/v1alpha1
                    kind: RuntimeConditionsBinding
                    """);
            writeJarEntry(out, "META-INF/runtimeconditions/runtimeconditions.extension.yaml", """
                    apiVersion: runtimeconditions.io/v1alpha1
                    kind: RuntimeConditionsExtensionDefinition
                    """);
        }
    }

    private static void writeJarEntry(JarOutputStream out, String name, String content) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertContains(String value, String expected, String message) {
        if (value == null || !value.contains(expected)) {
            throw new AssertionError(message + ": expected " + value + " to contain " + expected);
        }
    }
}

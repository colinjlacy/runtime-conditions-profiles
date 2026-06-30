package io.runtimeconditions.profiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public final class ManifestValidationTest {
    private ManifestValidationTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("usage: ManifestValidationTest <testdata> [repo-root]");
        }
        Path testdata = Path.of(args[0]).toAbsolutePath().normalize();
        validatesAuthoringFixtures(testdata.resolve("authoring"));
        validatesMavenBinding(testdata.resolve("maven-app"));
        validatesGradlePackage(testdata.resolve("gradle-app"));
        validatesClasspathJar();
        if (args.length == 2) {
            validatesRepositoryJavaBindings(Path.of(args[1]).toAbsolutePath().normalize());
        }
        rejectsMissingExtensionDefinition();
        rejectsExtensionIdMismatch();
        rejectsUnresolvedDependency();
        rejectsTopLevelJavaClass();
        rejectsBindingVocabularyMismatch();
        rejectsMissingJavaBindingMethod();
        rejectsJavaBindingConstantMismatch();
    }

    private static void validatesAuthoringFixtures(Path fixturesRoot) throws Exception {
        try (var stream = Files.list(fixturesRoot)) {
            for (Path fixture : stream.filter(Files::isDirectory).sorted().toList()) {
                YamlDocument config = YamlDocument.parse(Files.readString(fixture.resolve("fixture.yaml")));
                boolean valid = Boolean.parseBoolean(config.scalar("valid"));
                String wantErrorContains = config.scalar("wantErrorContains");
                DiscoveryResult result = discoverRoot(fixture);
                if (valid) {
                    assertFalse(result.hasErrors(), fixture.getFileName() + " should be valid: " + diagnostics(result));
                } else {
                    assertTrue(result.hasErrors(), fixture.getFileName() + " should fail");
                    if (wantErrorContains != null && !wantErrorContains.isBlank()) {
                        assertDiagnosticContains(result, wantErrorContains);
                    }
                }
            }
        }
    }

    private static void validatesMavenBinding(Path root) throws Exception {
        DiscoveryResult result = new JavaProjectDiscovery().discover(root, List.of());
        assertFalse(result.hasErrors(), "Maven binding fixture should be valid: " + diagnostics(result));
        ValidatedRuntimeConditionsArtifact artifact = result.validatedArtifacts().get(0);
        assertEquals(
                "https://example.com/runtimeconditions/java-maven-fixture/v1alpha1/runtimeconditions.extension.yaml",
                artifact.manifestExtensionId(),
                "Maven manifest extension id");
        assertEquals(artifact.manifestExtensionId(), artifact.extensionId(), "Maven extension id match");
    }

    private static void validatesGradlePackage(Path root) throws Exception {
        DiscoveryResult result = new JavaProjectDiscovery().discover(root, List.of());
        assertFalse(result.hasErrors(), "Gradle package fixture should be valid: " + diagnostics(result));
        ValidatedRuntimeConditionsArtifact artifact = result.validatedArtifacts().get(0);
        assertEquals(
                "https://example.com/runtimeconditions/java-gradle-fixture/v1alpha1/runtimeconditions.extension.yaml",
                artifact.manifestExtensionId(),
                "Gradle manifest extension id");
        assertEquals(artifact.manifestExtensionId(), artifact.extensionId(), "Gradle extension id match");
    }

    private static void validatesClasspathJar() throws Exception {
        Path jar = Files.createTempFile("runtimeconditions-valid-artifact", ".jar");
        try {
            writeJar(jar, """
                    apiVersion: runtimeconditions.io/v1alpha1
                    kind: RuntimeConditionsBinding

                    metadata:
                      extension: https://example.com/runtimeconditions/java-jar-fixture/v1alpha1/runtimeconditions.extension.yaml
                      language: java

                    java:
                      package: io.runtimeconditions.fixtures.jar
                      declarations:
                        - class: Fixture
                          function: declare
                          nameArg: 0
                          kind: java.fixture
                    """, """
                    apiVersion: runtimeconditions.io/v1alpha1
                    kind: RuntimeConditionsExtensionDefinition

                    metadata:
                      id: https://example.com/runtimeconditions/java-jar-fixture/v1alpha1/runtimeconditions.extension.yaml

                    spec:
                      kinds:
                        - name: java.fixture
                    """);
            List<RuntimeConditionsArtifact> artifacts = new ArtifactDiscovery().discoverClasspathArtifact(jar);
            List<ValidatedRuntimeConditionsArtifact> validated = new ArtifactValidator().validate(artifacts);
            assertEquals(1, validated.size(), "JAR should validate one artifact");
            assertTrue(validated.get(0).diagnostics().isEmpty(), "JAR artifact should be valid: " + validated.get(0).diagnostics());
        } finally {
            Files.deleteIfExists(jar);
        }
    }

    private static void validatesRepositoryJavaBindings(Path repoRoot) throws Exception {
        ArtifactDiscovery discovery = new ArtifactDiscovery();
        List<RuntimeConditionsArtifact> artifacts = new ArrayList<>();
        artifacts.addAll(discovery.discoverProjectArtifacts(
                repoRoot.resolve("extensions/common-integrations/java"),
                BuildTool.SOURCE_ONLY));
        artifacts.addAll(discovery.discoverProjectArtifacts(
                repoRoot.resolve("extensions/env-configuration/java"),
                BuildTool.SOURCE_ONLY));

        List<ValidatedRuntimeConditionsArtifact> validated = new ArtifactValidator().validate(artifacts);
        assertEquals(2, validated.size(), "repository Java bindings should expose two artifacts");
        for (ValidatedRuntimeConditionsArtifact artifact : validated) {
            assertTrue(artifact.diagnostics().isEmpty(), "repository Java binding should be valid: " + artifact.diagnostics());
        }

        ValidatedRuntimeConditionsArtifact common = findArtifact(
                validated,
                "https://runtimeconditions.io/extensions/common-integrations/v1alpha1/runtimeconditions.extension.yaml");
        ValidatedRuntimeConditionsArtifact env = findArtifact(
                validated,
                "https://runtimeconditions.io/extensions/env-configuration/v1alpha1/runtimeconditions.extension.yaml");

        assertEquals("io.runtimeconditions.extensions.commonintegrations", common.javaManifest().packageName(), "common package");
        assertEquals(10, common.javaManifest().constants().size(), "common constants");
        assertEquals(3, common.javaManifest().declarations().size(), "common declarations");
        JavaSymbolMapping api = common.javaManifest().declarations().get(0);
        assertEquals(9, api.options().size(), "api declaration options");
        assertEquals(2, api.options().get(1).options().size(), "anchored schema options should resolve for GET");
        assertEquals(2, api.options().get(2).options().size(), "anchored schema options should resolve for HEAD");

        assertEquals("io.runtimeconditions.extensions.envconfiguration", env.javaManifest().packageName(), "env package");
        assertEquals(2, env.javaManifest().options().size(), "env options");
        assertEquals(3, env.javaManifest().options().get(0).appliesToKinds().size(), "env appliesToKinds");
        assertEquals(3, env.javaManifest().options().get(1).appliesToKinds().size(), "anchored appliesToKinds should resolve");
        assertEquals(2, env.javaManifest().options().get(1).options().get(0).stringArgs().size(), "anchored stringArgs should resolve");
    }

    private static void rejectsMissingExtensionDefinition() throws Exception {
        Path root = Files.createTempDirectory("runtimeconditions-missing-extension");
        Path resourceRoot = runtimeConditionsResourceRoot(root);
        Files.createDirectories(resourceRoot);
        Files.writeString(resourceRoot.resolve("runtimeconditions.bindings.yaml"), """
                apiVersion: runtimeconditions.io/v1alpha1
                kind: RuntimeConditionsBinding

                metadata:
                  extension: https://example.com/runtimeconditions/missing-extension/v1alpha1/runtimeconditions.extension.yaml
                  language: java

                java:
                  package: io.runtimeconditions.fixtures.missing
                """);

        DiscoveryResult result = new JavaProjectDiscovery().discover(root, List.of());
        assertTrue(result.hasErrors(), "missing extension definition should fail");
        assertDiagnosticContains(result, "runtimeconditions.extension.yaml is required next to the manifest");
    }

    private static void rejectsExtensionIdMismatch() throws Exception {
        Path root = Files.createTempDirectory("runtimeconditions-id-mismatch");
        Path resourceRoot = runtimeConditionsResourceRoot(root);
        Files.createDirectories(resourceRoot);
        Files.writeString(resourceRoot.resolve("runtimeconditions.bindings.yaml"), """
                apiVersion: runtimeconditions.io/v1alpha1
                kind: RuntimeConditionsBinding

                metadata:
                  extension: https://example.com/runtimeconditions/manifest/v1alpha1/runtimeconditions.extension.yaml
                  language: java

                java:
                  package: io.runtimeconditions.fixtures.mismatch
                """);
        Files.writeString(resourceRoot.resolve("runtimeconditions.extension.yaml"), """
                apiVersion: runtimeconditions.io/v1alpha1
                kind: RuntimeConditionsExtensionDefinition

                metadata:
                  id: https://example.com/runtimeconditions/definition/v1alpha1/runtimeconditions.extension.yaml
                """);

        DiscoveryResult result = new JavaProjectDiscovery().discover(root, List.of());
        assertTrue(result.hasErrors(), "extension id mismatch should fail");
        assertDiagnosticContains(result, "does not match extension definition");
    }

    private static void rejectsUnresolvedDependency() throws Exception {
        Path root = Files.createTempDirectory("runtimeconditions-missing-dependency");
        Path resourceRoot = runtimeConditionsResourceRoot(root);
        Files.createDirectories(resourceRoot);
        Files.writeString(resourceRoot.resolve("runtimeconditions.bindings.yaml"), """
                apiVersion: runtimeconditions.io/v1alpha1
                kind: RuntimeConditionsBinding

                metadata:
                  extension: https://example.com/runtimeconditions/with-dependency/v1alpha1/runtimeconditions.extension.yaml
                  language: java

                java:
                  package: io.runtimeconditions.fixtures.dependency
                """);
        Files.writeString(resourceRoot.resolve("runtimeconditions.extension.yaml"), """
                apiVersion: runtimeconditions.io/v1alpha1
                kind: RuntimeConditionsExtensionDefinition

                metadata:
                  id: https://example.com/runtimeconditions/with-dependency/v1alpha1/runtimeconditions.extension.yaml

                spec:
                  dependencies:
                    - https://example.com/runtimeconditions/missing-dependency/v1alpha1/runtimeconditions.extension.yaml
                """);

        DiscoveryResult result = new JavaProjectDiscovery().discover(root, List.of());
        assertTrue(result.hasErrors(), "unresolved dependency should fail");
        assertDiagnosticContains(result, "cannot be resolved from discovered Runtime Conditions artifacts");
    }

    private static void rejectsTopLevelJavaClass() throws Exception {
        Path root = Files.createTempDirectory("runtimeconditions-top-level-java-class");
        writeBindingFixture(root, """
                java:
                  package: io.runtimeconditions.fixtures.invalid
                  class: RuntimeConditions
                  declarations:
                    - class: Fixture
                      function: declare
                      nameArg: 0
                      kind: java.fixture
                """, """
                public final class Fixture {
                    public static Object declare(String name) {
                        return new Object();
                    }
                }
                """, """
                spec:
                  kinds:
                    - name: java.fixture
                """);

        DiscoveryResult result = discoverRoot(root);
        assertTrue(result.hasErrors(), "top-level java.class should fail");
        assertDiagnosticContains(result, "top-level java.class must not be used");
    }

    private static void rejectsBindingVocabularyMismatch() throws Exception {
        Path root = Files.createTempDirectory("runtimeconditions-java-vocabulary-mismatch");
        writeBindingFixture(root, """
                java:
                  package: io.runtimeconditions.fixtures.invalid
                  declarations:
                    - class: Fixture
                      function: declare
                      nameArg: 0
                      kind: java.unknown
                """, """
                public final class Fixture {
                    public static Object declare(String name) {
                        return new Object();
                    }
                }
                """, """
                spec:
                  kinds:
                    - name: java.fixture
                """);

        DiscoveryResult result = discoverRoot(root);
        assertTrue(result.hasErrors(), "unknown declaration kind should fail");
        assertDiagnosticContains(result, "declaration kind java.unknown");
    }

    private static void rejectsMissingJavaBindingMethod() throws Exception {
        Path root = Files.createTempDirectory("runtimeconditions-missing-java-method");
        writeBindingFixture(root, """
                java:
                  package: io.runtimeconditions.fixtures.invalid
                  declarations:
                    - class: Fixture
                      function: declare
                      nameArg: 0
                      kind: java.fixture
                """, """
                public final class Fixture {
                    public static Object other(String name) {
                        return new Object();
                    }
                }
                """, """
                spec:
                  kinds:
                    - name: java.fixture
                """);

        DiscoveryResult result = discoverRoot(root);
        assertTrue(result.hasErrors(), "missing Java binding method should fail");
        assertDiagnosticContains(result, "Fixture.declare is not declared in Java package");
    }

    private static void rejectsJavaBindingConstantMismatch() throws Exception {
        Path root = Files.createTempDirectory("runtimeconditions-java-constant-mismatch");
        writeBindingFixture(root, """
                java:
                  package: io.runtimeconditions.fixtures.invalid
                  constants:
                    Fixture.VALUE: expected
                  declarations:
                    - class: Fixture
                      function: declare
                      nameArg: 0
                      kind: java.fixture
                """, """
                public final class Fixture {
                    public static final String VALUE = "actual";

                    public static Object declare(String name) {
                        return new Object();
                    }
                }
                """, """
                spec:
                  kinds:
                    - name: java.fixture
                  interfaceTypes:
                    - name: java
                      targetKind: java.fixture
                  interfaceFields:
                    - name: mode
                      targetKind: java.fixture
                      targetType: java
                  fieldValues:
                    - field: interface.mode
                      targetKind: java.fixture
                      targetType: java
                      values:
                        - expected
                """);

        DiscoveryResult result = discoverRoot(root);
        assertTrue(result.hasErrors(), "constant mismatch should fail");
        assertDiagnosticContains(result, "does not match Java value");
    }

    private static DiscoveryResult discoverRoot(Path root) throws Exception {
        List<RuntimeConditionsArtifact> artifacts = new ArtifactDiscovery().discoverArtifactsUnder(root);
        return new DiscoveryResult(
                root,
                BuildTool.SOURCE_ONLY,
                List.of(),
                List.of(),
                artifacts,
                new ArtifactValidator().validate(artifacts));
    }

    private static void writeBindingFixture(
            Path root,
            String javaSection,
            String javaSource,
            String extensionSpec) throws IOException {
        Files.writeString(root.resolve("runtimeconditions.bindings.yaml"), """
                apiVersion: runtimeconditions.io/v1alpha1
                kind: RuntimeConditionsBinding

                metadata:
                  extension: https://example.com/runtimeconditions/java-fixture/v1alpha1/runtimeconditions.extension.yaml
                  language: java

                """ + javaSection);
        Files.writeString(root.resolve("runtimeconditions.extension.yaml"), """
                apiVersion: runtimeconditions.io/v1alpha1
                kind: RuntimeConditionsExtensionDefinition

                metadata:
                  id: https://example.com/runtimeconditions/java-fixture/v1alpha1/runtimeconditions.extension.yaml

                """ + extensionSpec);
        Path sourceRoot = root.resolve("src/main/java/io/runtimeconditions/fixtures/invalid");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("Fixture.java"), """
                package io.runtimeconditions.fixtures.invalid;

                """ + javaSource);
    }

    private static Path runtimeConditionsResourceRoot(Path root) {
        return root.resolve("src/main/resources").resolve(ArtifactDiscovery.RESOURCE_ROOT);
    }

    private static ValidatedRuntimeConditionsArtifact findArtifact(
            List<ValidatedRuntimeConditionsArtifact> artifacts,
            String extensionId) {
        for (ValidatedRuntimeConditionsArtifact artifact : artifacts) {
            if (extensionId.equals(artifact.manifestExtensionId())) {
                return artifact;
            }
        }
        throw new AssertionError("missing artifact for " + extensionId);
    }

    private static void writeJar(Path jar, String manifest, String extension) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            writeJarEntry(out, "META-INF/runtimeconditions/runtimeconditions.bindings.yaml", manifest);
            writeJarEntry(out, "META-INF/runtimeconditions/runtimeconditions.extension.yaml", extension);
        }
    }

    private static void writeJarEntry(JarOutputStream out, String name, String content) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    private static void assertDiagnosticContains(DiscoveryResult result, String expected) {
        if (result.diagnostics().stream().noneMatch(diagnostic -> diagnostic.message().contains(expected))) {
            throw new AssertionError("expected diagnostic containing " + expected + ", got " + diagnostics(result));
        }
    }

    private static String diagnostics(DiscoveryResult result) {
        StringBuilder out = new StringBuilder();
        for (RuntimeConditionsDiagnostic diagnostic : result.diagnostics()) {
            if (out.length() > 0) {
                out.append("; ");
            }
            out.append(diagnostic.message());
        }
        return out.toString();
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertFalse(boolean value, String message) {
        if (value) {
            throw new AssertionError(message);
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}

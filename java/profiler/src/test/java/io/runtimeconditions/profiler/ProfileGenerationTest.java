package io.runtimeconditions.profiler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class ProfileGenerationTest {
    private ProfileGenerationTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: ProfileGenerationTest <repo-root>");
        }
        Path repoRoot = Path.of(args[0]).toAbsolutePath().normalize();
        Path app = repoRoot.resolve("java/profiler/src/testdata/declarative-app");
        Path common = repoRoot.resolve("extensions/common-integrations/java");
        Path env = repoRoot.resolve("extensions/env-configuration/java");
        DiscoveryOptions discoveryOptions = new DiscoveryOptions(List.of(common, env), false);

        Map<String, Object> profile = new JavaProfileExtractor().extract(
                app,
                new JavaProfileOptions(
                        "java-declarative-app",
                        "example/java-declarative-app",
                        "test",
                        discoveryOptions));
        DiscoveryResult discovery = new JavaProjectDiscovery().discover(app, discoveryOptions);

        assertEquals("runtimeconditions.io/v1alpha1", profile.get("apiVersion"), "apiVersion");
        assertEquals("RuntimeConditionsProfile", profile.get("kind"), "kind");

        List<?> extensions = list(profile.get("extensions"), "extensions");
        assertEquals(2, extensions.size(), "extension count");
        assertTrue(
                extensions.contains("https://runtimeconditions.io/extensions/common-integrations/v1alpha1/runtimeconditions.extension.yaml"),
                "common extension should be present");
        assertTrue(
                extensions.contains("https://runtimeconditions.io/extensions/env-configuration/v1alpha1/runtimeconditions.extension.yaml"),
                "env extension should be present");

        List<?> conditions = list(profile.get("conditions"), "conditions");
        assertEquals(2, conditions.size(), "condition count");

        Map<?, ?> api = map(conditions.get(0), "api condition");
        assertEquals("users-api", api.get("name"), "api name");
        assertEquals("api", api.get("kind"), "api kind");
        Map<?, ?> apiInterface = map(api.get("interface"), "api interface");
        assertEquals("http", apiInterface.get("type"), "api interface type");
        assertEquals(2, list(apiInterface.get("operations"), "api operations").size(), "api operations");
        Map<?, ?> apiConfiguration = map(api.get("configuration"), "api configuration");
        Map<?, ?> envInput = map(list(apiConfiguration.get("env"), "api env").get(0), "api env input");
        assertEquals("token", envInput.get("property"), "api env property");
        assertEquals("AUTH_TOKEN", envInput.get("name"), "api env name");
        assertEquals(Boolean.TRUE, envInput.get("sensitive"), "api env sensitive");
        assertEquals(Boolean.FALSE, envInput.get("required"), "api env required");

        Map<?, ?> datastore = map(conditions.get(1), "datastore condition");
        assertEquals("users-db", datastore.get("name"), "datastore name");
        assertEquals("datastore", datastore.get("kind"), "datastore kind");
        Map<?, ?> datastoreInterface = map(datastore.get("interface"), "datastore interface");
        assertEquals("relational", datastoreInterface.get("type"), "datastore interface type");
        assertEquals("postgres", datastoreInterface.get("engine"), "datastore engine");
        Map<?, ?> datastoreConfiguration = map(datastore.get("configuration"), "datastore configuration");
        List<?> alternatives = list(datastoreConfiguration.get("alternatives"), "datastore alternatives");
        assertEquals(1, alternatives.size(), "datastore alternative count");
        assertEquals(2, list(map(alternatives.get(0), "datastore alternative").get("env"), "alternative env").size(), "alternative env count");

        String yaml = ProfileYamlWriter.write(profile);
        assertTrue(yaml.contains("kind: RuntimeConditionsProfile"), "YAML should contain profile kind");
        assertTrue(yaml.contains("name: users-api"), "YAML should contain API condition");

        assertTrue(new JavaProfileValidator().validate(profile, discovery).isEmpty(), "generated profile should validate");
        assertGoldenProfile(
                app,
                discoveryOptions,
                "java-declarative-app",
                "example/java-declarative-app",
                "test",
                repoRoot.resolve("java/profiler/src/testdata/golden/declarative-app.golden.yaml"));
        assertGoldenProfile(
                repoRoot.resolve("java/profiler/src/testdata/profile-generation/wildcard-cache"),
                new DiscoveryOptions(List.of(common), false),
                "wildcard-cache",
                "example/wildcard-cache",
                "test",
                repoRoot.resolve("java/profiler/src/testdata/golden/wildcard-cache.golden.yaml"));
        assertGoldenProfile(
                repoRoot.resolve("java/profiler/src/testdata/profile-generation/semantic-resolution"),
                discoveryOptions,
                "semantic-resolution",
                "example/semantic-resolution",
                "test",
                repoRoot.resolve("java/profiler/src/testdata/golden/semantic-resolution.golden.yaml"));
        assertGoldenProfile(
                repoRoot.resolve("java/profiler/src/testdata/profile-generation/unused-env"),
                discoveryOptions,
                "unused-env",
                "example/unused-env",
                "test",
                repoRoot.resolve("java/profiler/src/testdata/golden/unused-env.golden.yaml"));
        assertGoldenProfile(
                repoRoot.resolve("demos/apps/request-logger-http-java"),
                discoveryOptions,
                "request-logger-http",
                "github.com/colinjlacy/runtime-conditions-profiles/demos/apps/request-logger-http-java",
                "dev",
                repoRoot.resolve("java/profiler/src/testdata/golden/request-logger-http-java.golden.yaml"));

        Map<String, Object> unknownKind = mutableProfile(profile);
        condition(unknownKind, 0).put("kind", "worker");
        assertProfileInvalid(unknownKind, discovery, "conditions[0].kind worker");

        Map<String, Object> unknownInterfaceType = mutableProfile(profile);
        interfaceMap(condition(unknownInterfaceType, 0)).put("type", "grpc");
        assertProfileInvalid(unknownInterfaceType, discovery, "conditions[0].interface.type api/grpc");

        Map<String, Object> invalidMethod = mutableProfile(profile);
        operation(condition(invalidMethod, 0), 0).put("method", "FETCH");
        assertProfileInvalid(invalidMethod, discovery, "conditions[0].interface.operations[0].method FETCH");

        Map<String, Object> invalidEnvProperty = mutableProfile(profile);
        envInput(condition(invalidEnvProperty, 0), 0).put("property", "apiKey");
        assertProfileInvalid(invalidEnvProperty, discovery, "conditions[0].configuration.env[0].property apiKey");

        Map<String, Object> missingDependency = mutableProfile(profile);
        missingDependency.put("extensions", List.of("https://runtimeconditions.io/extensions/env-configuration/v1alpha1/runtimeconditions.extension.yaml"));
        assertProfileInvalid(
                missingDependency,
                discovery,
                "extensions missing dependency https://runtimeconditions.io/extensions/common-integrations/v1alpha1/runtimeconditions.extension.yaml");
    }

    private static void assertGoldenProfile(
            Path app,
            DiscoveryOptions options,
            String name,
            String workloadUri,
            String workloadVersion,
            Path golden) throws Exception {
        Map<String, Object> profile = new JavaProfileExtractor().extract(
                app,
                new JavaProfileOptions(name, workloadUri, workloadVersion, options));
        String actual = normalize(ProfileYamlWriter.write(profile));
        String expected = normalize(Files.readString(golden));
        assertEquals(expected, actual, golden.getFileName().toString());
    }

    private static Map<?, ?> map(Object value, String message) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new AssertionError(message + ": expected map, got " + value);
        }
        return map;
    }

    private static List<?> list(Object value, String message) {
        if (!(value instanceof List<?> list)) {
            throw new AssertionError(message + ": expected list, got " + value);
        }
        return list;
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertProfileInvalid(
            Map<String, Object> profile,
            DiscoveryResult discovery,
            String expectedMessage) {
        List<RuntimeConditionsDiagnostic> diagnostics = new JavaProfileValidator().validate(profile, discovery);
        for (RuntimeConditionsDiagnostic diagnostic : diagnostics) {
            if (diagnostic.message().contains(expectedMessage)) {
                return;
            }
        }
        throw new AssertionError("expected profile validation error containing " + expectedMessage + ", got " + messages(diagnostics));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> condition(Map<String, Object> profile, int index) {
        return (Map<String, Object>) list(profile.get("conditions"), "conditions").get(index);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> interfaceMap(Map<String, Object> condition) {
        return (Map<String, Object>) map(condition.get("interface"), "condition interface");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> operation(Map<String, Object> condition, int index) {
        return (Map<String, Object>) list(interfaceMap(condition).get("operations"), "condition operations").get(index);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> envInput(Map<String, Object> condition, int index) {
        Map<?, ?> configuration = map(condition.get("configuration"), "condition configuration");
        return (Map<String, Object>) list(configuration.get("env"), "configuration env").get(index);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mutableProfile(Map<String, Object> profile) {
        return (Map<String, Object>) deepCopy(profile);
    }

    private static String normalize(String value) {
        return value.replace("\r\n", "\n").trim() + "\n";
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> input) {
            java.util.LinkedHashMap<String, Object> copy = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : input.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), deepCopy(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> input) {
            java.util.ArrayList<Object> copy = new java.util.ArrayList<>();
            for (Object item : input) {
                copy.add(deepCopy(item));
            }
            return copy;
        }
        return value;
    }

    private static String messages(List<RuntimeConditionsDiagnostic> diagnostics) {
        StringBuilder out = new StringBuilder();
        for (RuntimeConditionsDiagnostic diagnostic : diagnostics) {
            if (!out.isEmpty()) {
                out.append("; ");
            }
            out.append(diagnostic.message());
        }
        return out.toString();
    }
}

package io.runtimeconditions.profiler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ProfilerCli {
    private ProfilerCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "discover".equals(args[0])) {
            discover(args.length == 0 ? new String[0] : dropFirst(args));
            return;
        }
        if ("generate".equals(args[0])) {
            generate(dropFirst(args));
            return;
        }
        if ("validate-extension".equals(args[0])) {
            validateArtifacts(dropFirst(args), false);
            return;
        }
        if ("validate-extensions".equals(args[0])) {
            validateArtifacts(dropFirst(args), true);
            return;
        }
        throw new IllegalArgumentException("unknown command: " + args[0]);
    }

    private static void generate(String[] args) throws Exception {
        Path project = Path.of(".");
        List<Path> classpath = new ArrayList<>();
        boolean resolveBuildClasspath = false;
        String name = "";
        String workloadUri = "";
        String workloadVersion = "dev";
        Path out = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--project" -> project = Path.of(requireValue(args, ++i, "--project"));
                case "--classpath" -> classpath.addAll(splitClasspath(requireValue(args, ++i, "--classpath")));
                case "--resolve-build-classpath" -> resolveBuildClasspath = true;
                case "--name" -> name = requireValue(args, ++i, "--name");
                case "--workload-uri" -> workloadUri = requireValue(args, ++i, "--workload-uri");
                case "--workload-version" -> workloadVersion = requireValue(args, ++i, "--workload-version");
                case "--out" -> out = Path.of(requireValue(args, ++i, "--out"));
                default -> throw new IllegalArgumentException("unknown flag: " + args[i]);
            }
        }

        Path root = project.toAbsolutePath().normalize();
        String profileName = name.isBlank() ? root.getFileName().toString() : name;
        String uri = workloadUri.isBlank() ? root.toString() : workloadUri;
        String yaml = ProfileYamlWriter.write(new JavaProfileExtractor().extract(
                root,
                new JavaProfileOptions(
                        profileName,
                        uri,
                        workloadVersion,
                        new DiscoveryOptions(classpath, resolveBuildClasspath))));
        if (out == null) {
            System.out.print(yaml);
        } else {
            Files.writeString(out, yaml);
        }
    }

    private static void discover(String[] args) throws Exception {
        Path project = Path.of(".");
        List<Path> classpath = new ArrayList<>();
        boolean resolveBuildClasspath = false;
        boolean json = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--project" -> project = Path.of(requireValue(args, ++i, "--project"));
                case "--classpath" -> classpath.addAll(splitClasspath(requireValue(args, ++i, "--classpath")));
                case "--resolve-build-classpath" -> resolveBuildClasspath = true;
                case "--json" -> json = true;
                default -> throw new IllegalArgumentException("unknown flag: " + args[i]);
            }
        }

        DiscoveryResult result = new JavaProjectDiscovery().discover(
                project,
                new DiscoveryOptions(classpath, resolveBuildClasspath));
        if (json) {
            printJson(result);
            return;
        }
        printText(result);
    }

    private static void validateArtifacts(String[] args, boolean plural) throws Exception {
        Path root = Path.of(".");
        List<Path> catalogRoots = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--root" -> root = Path.of(requireValue(args, ++i, "--root"));
                case "--catalog-root" -> catalogRoots.addAll(splitCommaPaths(requireValue(args, ++i, "--catalog-root")));
                default -> throw new IllegalArgumentException("unknown flag: " + args[i]);
            }
        }

        ArtifactDiscovery discovery = new ArtifactDiscovery();
        List<RuntimeConditionsArtifact> artifacts = new ArrayList<>();
        artifacts.addAll(javaArtifacts(discovery.discoverArtifactsUnder(root)));
        for (Path catalogRoot : catalogRoots) {
            artifacts.addAll(javaArtifacts(discovery.discoverArtifactsUnder(catalogRoot)));
        }
        if (artifacts.isEmpty()) {
            throw new IllegalArgumentException("no Runtime Conditions Java artifacts discovered under " + root.toAbsolutePath().normalize());
        }

        List<ValidatedRuntimeConditionsArtifact> validated = new ArtifactValidator().validate(artifacts);
        List<RuntimeConditionsDiagnostic> diagnostics = new ArrayList<>();
        for (ValidatedRuntimeConditionsArtifact artifact : validated) {
            diagnostics.addAll(artifact.diagnostics());
        }
        if (!diagnostics.isEmpty()) {
            throw new IllegalArgumentException("extension validation failed:" + diagnosticLines(diagnostics));
        }
        System.err.println("runtimeconditions: extension" + (plural ? "s" : "") + " validation passed");
    }

    private static String[] dropFirst(String[] args) {
        String[] result = new String[args.length - 1];
        System.arraycopy(args, 1, result, 0, result.length);
        return result;
    }

    private static String requireValue(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException(flag + " requires a value");
        }
        return args[index];
    }

    private static List<Path> splitClasspath(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String[] parts = value.split(java.io.File.pathSeparator);
        List<Path> result = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                result.add(Path.of(part));
            }
        }
        return result;
    }

    private static List<Path> splitCommaPaths(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String[] parts = value.split(",");
        List<Path> result = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                result.add(Path.of(part.trim()));
            }
        }
        return result;
    }

    private static List<RuntimeConditionsArtifact> javaArtifacts(List<RuntimeConditionsArtifact> artifacts) throws Exception {
        List<RuntimeConditionsArtifact> result = new ArrayList<>();
        for (RuntimeConditionsArtifact artifact : artifacts) {
            String language = manifestLanguage(artifact);
            if (language.isBlank() || "java".equals(language)) {
                result.add(artifact);
            }
        }
        return result;
    }

    private static String manifestLanguage(RuntimeConditionsArtifact artifact) throws Exception {
        Path sourcePath = artifact.sourcePath();
        if (sourcePath == null || !Files.isRegularFile(sourcePath)) {
            return "";
        }
        return nullToEmpty(YamlDocument.parse(Files.readString(sourcePath)).scalar("metadata", "language"));
    }

    private static void printText(DiscoveryResult result) {
        System.out.println("project: " + result.projectRoot());
        System.out.println("buildTool: " + result.buildTool().name().toLowerCase());
        for (Path module : result.modules()) {
            System.out.println("module: " + module);
        }
        for (Path classpathEntry : result.classpathEntries()) {
            System.out.println("classpath: " + classpathEntry);
        }
        for (RuntimeConditionsArtifact artifact : result.artifacts()) {
            System.out.println("artifact: kind=" + artifact.kind().name().toLowerCase()
                    + " manifest=" + artifact.manifestUri()
                    + " extension=" + nullToEmpty(artifact.extensionUri())
                    + " origin=" + artifact.origin());
        }
        for (ValidatedRuntimeConditionsArtifact artifact : result.validatedArtifacts()) {
            System.out.println("validatedArtifact: kind=" + artifact.artifact().kind().name().toLowerCase()
                    + " manifestExtensionId=" + nullToEmpty(artifact.manifestExtensionId())
                    + " extensionId=" + nullToEmpty(artifact.extensionId())
                    + " extensionDefinition=" + nullToEmpty(artifact.extensionDefinitionUri())
                    + " javaPackage=" + javaPackage(artifact)
                    + " declarations=" + declarationCount(artifact)
                    + " options=" + optionCount(artifact)
                    + " constants=" + constantCount(artifact));
        }
        for (RuntimeConditionsDiagnostic diagnostic : result.diagnostics()) {
            System.out.println("diagnostic: severity=" + diagnostic.severity().name().toLowerCase()
                    + " code=" + diagnostic.code()
                    + " source=" + diagnostic.source()
                    + " message=" + diagnostic.message());
        }
    }

    private static void printJson(DiscoveryResult result) {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        out.append("  \"project\": \"").append(json(result.projectRoot().toString())).append("\",\n");
        out.append("  \"buildTool\": \"").append(result.buildTool().name().toLowerCase()).append("\",\n");
        out.append("  \"modules\": [");
        for (int i = 0; i < result.modules().size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append("\"").append(json(result.modules().get(i).toString())).append("\"");
        }
        out.append("],\n");
        out.append("  \"classpath\": [");
        for (int i = 0; i < result.classpathEntries().size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append("\"").append(json(result.classpathEntries().get(i).toString())).append("\"");
        }
        out.append("],\n");
        out.append("  \"artifacts\": [\n");
        for (int i = 0; i < result.artifacts().size(); i++) {
            RuntimeConditionsArtifact artifact = result.artifacts().get(i);
            out.append("    {");
            out.append("\"kind\": \"").append(artifact.kind().name().toLowerCase()).append("\", ");
            out.append("\"manifest\": \"").append(json(artifact.manifestUri())).append("\", ");
            out.append("\"extension\": ");
            if (artifact.extensionUri() == null) {
                out.append("null");
            } else {
                out.append("\"").append(json(artifact.extensionUri())).append("\"");
            }
            out.append(", \"origin\": \"").append(json(artifact.origin())).append("\"");
            ValidatedRuntimeConditionsArtifact validated = result.validatedArtifacts().get(i);
            out.append(", \"manifestExtensionId\": ");
            appendJsonNullable(out, validated.manifestExtensionId());
            out.append(", \"extensionId\": ");
            appendJsonNullable(out, validated.extensionId());
            out.append(", \"extensionDefinition\": ");
            appendJsonNullable(out, validated.extensionDefinitionUri());
            out.append(", \"dependencies\": [");
            for (int d = 0; d < validated.dependencies().size(); d++) {
                if (d > 0) {
                    out.append(", ");
                }
                out.append("\"").append(json(validated.dependencies().get(d))).append("\"");
            }
            out.append("]");
            out.append(", \"javaPackage\": ");
            appendJsonNullable(out, javaPackageNullable(validated));
            out.append(", \"declarations\": ").append(declarationCount(validated));
            out.append(", \"options\": ").append(optionCount(validated));
            out.append(", \"constants\": ").append(constantCount(validated));
            out.append("}");
            if (i + 1 < result.artifacts().size()) {
                out.append(",");
            }
            out.append("\n");
        }
        out.append("  ],\n");
        out.append("  \"diagnostics\": [\n");
        List<RuntimeConditionsDiagnostic> diagnostics = result.diagnostics();
        for (int i = 0; i < diagnostics.size(); i++) {
            RuntimeConditionsDiagnostic diagnostic = diagnostics.get(i);
            out.append("    {");
            out.append("\"severity\": \"").append(diagnostic.severity().name().toLowerCase()).append("\", ");
            out.append("\"code\": \"").append(json(diagnostic.code())).append("\", ");
            out.append("\"source\": \"").append(json(diagnostic.source())).append("\", ");
            out.append("\"message\": \"").append(json(diagnostic.message())).append("\"");
            out.append("}");
            if (i + 1 < diagnostics.size()) {
                out.append(",");
            }
            out.append("\n");
        }
        out.append("  ]\n");
        out.append("}\n");
        System.out.print(out);
    }

    private static void appendJsonNullable(StringBuilder out, String value) {
        if (value == null) {
            out.append("null");
        } else {
            out.append("\"").append(json(value)).append("\"");
        }
    }

    private static String javaPackage(ValidatedRuntimeConditionsArtifact artifact) {
        String value = javaPackageNullable(artifact);
        return value == null ? "" : value;
    }

    private static String javaPackageNullable(ValidatedRuntimeConditionsArtifact artifact) {
        JavaManifestModel manifest = artifact.javaManifest();
        return manifest == null ? null : manifest.packageName();
    }

    private static int declarationCount(ValidatedRuntimeConditionsArtifact artifact) {
        JavaManifestModel manifest = artifact.javaManifest();
        return manifest == null ? 0 : manifest.declarations().size();
    }

    private static int optionCount(ValidatedRuntimeConditionsArtifact artifact) {
        JavaManifestModel manifest = artifact.javaManifest();
        return manifest == null ? 0 : manifest.options().size();
    }

    private static int constantCount(ValidatedRuntimeConditionsArtifact artifact) {
        JavaManifestModel manifest = artifact.javaManifest();
        return manifest == null ? 0 : manifest.constants().size();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String diagnosticLines(List<RuntimeConditionsDiagnostic> diagnostics) {
        StringBuilder out = new StringBuilder();
        for (RuntimeConditionsDiagnostic diagnostic : diagnostics) {
            out.append("\n- ");
            if (!diagnostic.source().isBlank()) {
                out.append(diagnostic.source()).append(": ");
            }
            out.append(diagnostic.message());
        }
        return out.toString();
    }
}

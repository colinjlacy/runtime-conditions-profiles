package io.runtimeconditions.profiler.profile;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import io.runtimeconditions.profiler.extension.RuntimeConditionsDiagnostic;
import io.runtimeconditions.profiler.extension.ValidatedRuntimeConditionsArtifact;
import io.runtimeconditions.profiler.project.DiscoveryResult;
import io.runtimeconditions.profiler.project.ProjectDiscovery;
import io.runtimeconditions.profiler.project.RuntimeConditionsArtifact;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Orchestrates profile generation: discovers validated binding artifacts,
 * parses the project's Java sources with the JDK compiler (running analyze()
 * so resolved types are available for schema inference), then runs the
 * two-pass extraction scanner and validates the emitted profile.
 */
public final class ProfileExtractor {
    public Map<String, Object> extract(Path projectRoot, ProfileOptions options) throws IOException {
        DiscoveryResult discovery = new ProjectDiscovery().discover(projectRoot, options.discoveryOptions());
        if (discovery.hasErrors()) {
            throw new IOException("Runtime Conditions artifact validation failed: " + diagnostics(discovery));
        }

        List<BindingArtifact> bindings = declarativeBindings(discovery);
        if (bindings.isEmpty()) {
            throw new IOException("no RuntimeConditionsBinding artifacts were discovered");
        }

        List<Path> sourceFiles = javaSourceFiles(discovery.projectRoot(), discovery.modules());
        if (sourceFiles.isEmpty()) {
            throw new IOException("no Java source files found under " + discovery.projectRoot());
        }

        ParsedJavaProgram program = parse(sourceFiles, discovery.classpathEntries());
        ExtractionScanner scanner = new ExtractionScanner(bindings, options, discovery, program.semantic());
        for (CompilationUnitTree unit : program.units()) {
            scanner.collect(unit);
        }
        for (CompilationUnitTree unit : program.units()) {
            scanner.extract(unit);
        }
        Map<String, Object> profile = scanner.profile();
        List<RuntimeConditionsDiagnostic> profileDiagnostics = new ProfileValidator().validate(profile, discovery);
        if (!profileDiagnostics.isEmpty()) {
            throw new IOException("generated Runtime Conditions Profile validation failed: " + diagnostics(profileDiagnostics));
        }
        return profile;
    }

    private List<BindingArtifact> declarativeBindings(DiscoveryResult discovery) {
        List<BindingArtifact> bindings = new ArrayList<>();
        for (ValidatedRuntimeConditionsArtifact artifact : discovery.validatedArtifacts()) {
            if (artifact.artifact().kind() != RuntimeConditionsArtifact.Kind.BINDING || artifact.javaManifest() == null) {
                continue;
            }
            bindings.add(new BindingArtifact(artifact));
        }
        return List.copyOf(bindings);
    }

    private ParsedJavaProgram parse(List<Path> sourceFiles, List<Path> classpathEntries) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("JDK compiler is required; run with a JDK rather than a JRE");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(sourceFiles);
            List<String> compilerOptions = new ArrayList<>();
            compilerOptions.add("-proc:none");
            if (!classpathEntries.isEmpty()) {
                compilerOptions.add("-classpath");
                compilerOptions.add(joinClasspath(classpathEntries));
                compilerOptions.add("-sourcepath");
                compilerOptions.add(joinClasspath(sourcePathEntries(classpathEntries)));
            }
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, diagnostics, compilerOptions, null, units);
            List<CompilationUnitTree> parsed = new ArrayList<>();
            for (CompilationUnitTree unit : task.parse()) {
                parsed.add(unit);
            }
            task.analyze();
            return new ParsedJavaProgram(List.copyOf(parsed), SemanticModel.index(task, parsed));
        }
    }

    private List<Path> sourcePathEntries(List<Path> classpathEntries) {
        List<Path> entries = new ArrayList<>();
        for (Path entry : classpathEntries) {
            if (!Files.isDirectory(entry)) {
                continue;
            }
            Path sourceRoot = entry.resolve("src/main/java");
            if (Files.isDirectory(sourceRoot)) {
                entries.add(sourceRoot);
            } else {
                entries.add(entry);
            }
        }
        return entries;
    }

    private List<Path> javaSourceFiles(Path root, List<Path> modules) throws IOException {
        List<Path> roots = new ArrayList<>();
        addSourceRoot(roots, root);
        for (Path module : modules) {
            addSourceRoot(roots, module);
        }
        List<Path> files = new ArrayList<>();
        for (Path sourceRoot : roots) {
            try (var stream = Files.walk(sourceRoot)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .filter(path -> !path.toString().contains("/target/"))
                        .filter(path -> !path.toString().contains("/build/"))
                        .sorted()
                        .forEach(files::add);
            }
        }
        return List.copyOf(files);
    }

    private void addSourceRoot(List<Path> roots, Path projectRoot) {
        Path sourceRoot = projectRoot.resolve("src/main/java");
        if (Files.isDirectory(sourceRoot)) {
            roots.add(sourceRoot);
        } else if (Files.isDirectory(projectRoot)) {
            roots.add(projectRoot);
        }
    }

    private String joinClasspath(List<Path> entries) {
        StringBuilder out = new StringBuilder();
        for (Path entry : entries) {
            if (!out.isEmpty()) {
                out.append(java.io.File.pathSeparator);
            }
            out.append(entry);
        }
        return out.toString();
    }

    private String diagnostics(DiscoveryResult discovery) {
        return diagnostics(discovery.diagnostics());
    }

    private String diagnostics(List<RuntimeConditionsDiagnostic> diagnostics) {
        StringBuilder out = new StringBuilder();
        for (RuntimeConditionsDiagnostic diagnostic : diagnostics) {
            if (!out.isEmpty()) {
                out.append("; ");
            }
            out.append(diagnostic.message());
        }
        return out.toString();
    }

    private record ParsedJavaProgram(List<CompilationUnitTree> units, SemanticModel semantic) {
    }
}

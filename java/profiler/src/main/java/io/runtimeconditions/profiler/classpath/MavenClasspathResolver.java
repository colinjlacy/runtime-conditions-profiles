package io.runtimeconditions.profiler.classpath;

import io.runtimeconditions.profiler.command.CommandResult;
import io.runtimeconditions.profiler.command.CommandRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class MavenClasspathResolver implements ClasspathResolver {
    private final CommandRunner commandRunner;

    public MavenClasspathResolver(CommandRunner commandRunner) {
        this.commandRunner = commandRunner;
    }

    @Override
    public List<Path> resolve(Path projectRoot, List<Path> modules) throws IOException {
        Path root = projectRoot.toAbsolutePath().normalize();
        Set<Path> entries = ClasspathEntries.set();
        Path executable = mavenExecutable(root);

        resolveProject(root, root, executable, entries);
        addMavenOutput(entries, root);
        for (Path module : modules) {
            Path normalized = module.toAbsolutePath().normalize();
            resolveProject(root, normalized, executable, entries);
            addMavenOutput(entries, normalized);
        }
        return new ArrayList<>(entries);
    }

    private void resolveProject(
            Path root,
            Path project,
            Path executable,
            Set<Path> entries) throws IOException {
        if (!Files.isRegularFile(project.resolve("pom.xml"))) {
            return;
        }
        Path outputFile = Files.createTempFile("runtimeconditions-maven-classpath", ".txt");
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.add("-q");
        command.add("-DincludeScope=runtime");
        command.add("-Dmdep.outputFile=" + outputFile);
        command.add("process-resources");
        command.add("dependency:build-classpath");

        try {
            CommandResult result = commandRunner.run(command, project);
            if (result.exitCode() != 0) {
                throw new IOException("Maven classpath resolution failed for "
                        + project
                        + " with exit code "
                        + result.exitCode()
                        + ": "
                        + ClasspathEntries.commandOutput(result));
            }
            if (Files.isRegularFile(outputFile)) {
                for (Path entry : ClasspathEntries.parse(Files.readString(outputFile), root)) {
                    entries.add(entry);
                }
            }
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }

    private Path mavenExecutable(Path root) {
        Path wrapper = root.resolve(ClasspathEntries.isWindows() ? "mvnw.cmd" : "mvnw");
        if (Files.isRegularFile(wrapper)) {
            return wrapper;
        }
        return Path.of("mvn");
    }

    private void addMavenOutput(Set<Path> entries, Path project) {
        ClasspathEntries.addIfExists(entries, project.resolve("target/classes"));
        ClasspathEntries.addIfExists(entries, project.resolve("target/test-classes"));
    }
}

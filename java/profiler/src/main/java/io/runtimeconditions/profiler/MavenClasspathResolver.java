package io.runtimeconditions.profiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class MavenClasspathResolver implements ClasspathResolver {
    private final CommandRunner commandRunner;

    MavenClasspathResolver(CommandRunner commandRunner) {
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
        return ClasspathEntries.sortedInsertionOrder(entries);
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
                        + commandOutput(result));
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
        Path wrapper = root.resolve(isWindows() ? "mvnw.cmd" : "mvnw");
        if (Files.isRegularFile(wrapper)) {
            return wrapper;
        }
        return Path.of("mvn");
    }

    private void addMavenOutput(Set<Path> entries, Path project) {
        ClasspathEntries.addIfExists(entries, project.resolve("target/classes"));
        ClasspathEntries.addIfExists(entries, project.resolve("target/test-classes"));
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private String commandOutput(CommandResult result) {
        String output = (result.stderr() + "\n" + result.stdout()).trim();
        return output.isEmpty() ? "<no output>" : output;
    }
}

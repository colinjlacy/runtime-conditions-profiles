package io.runtimeconditions.profiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class GradleClasspathResolver implements ClasspathResolver {
    private static final String CLASSPATH_PREFIX = "RCP_CLASSPATH=";

    private final CommandRunner commandRunner;

    GradleClasspathResolver(CommandRunner commandRunner) {
        this.commandRunner = commandRunner;
    }

    @Override
    public List<Path> resolve(Path projectRoot, List<Path> modules) throws IOException {
        Path root = projectRoot.toAbsolutePath().normalize();
        Set<Path> entries = ClasspathEntries.set();

        Path initScript = Files.createTempFile("runtimeconditions-gradle-classpath", ".gradle");
        Files.writeString(initScript, initScript());
        List<String> command = new ArrayList<>();
        command.add(gradleExecutable(root).toString());
        command.add("--no-daemon");
        command.add("-q");
        command.add("--init-script");
        command.add(initScript.toString());
        command.add("runtimeConditionsClasspath");

        try {
            CommandResult result = commandRunner.run(command, root);
            if (result.exitCode() != 0) {
                throw new IOException("Gradle classpath resolution failed with exit code "
                        + result.exitCode()
                        + ": "
                        + commandOutput(result));
            }
            for (String line : result.stdout().split("\\R")) {
                if (line.startsWith(CLASSPATH_PREFIX)) {
                    for (Path entry : ClasspathEntries.parse(line.substring(CLASSPATH_PREFIX.length()), root)) {
                        entries.add(entry);
                    }
                }
            }
        } finally {
            Files.deleteIfExists(initScript);
        }
        addGradleOutput(entries, root);
        for (Path module : modules) {
            addGradleOutput(entries, module);
        }
        return ClasspathEntries.sortedInsertionOrder(entries);
    }

    private Path gradleExecutable(Path root) {
        Path wrapper = root.resolve(isWindows() ? "gradlew.bat" : "gradlew");
        if (Files.isRegularFile(wrapper)) {
            return wrapper;
        }
        return Path.of("gradle");
    }

    private void addGradleOutput(Set<Path> entries, Path project) {
        ClasspathEntries.addIfExists(entries, project.resolve("build/classes/java/main"));
        ClasspathEntries.addIfExists(entries, project.resolve("build/resources/main"));
        ClasspathEntries.addIfExists(entries, project.resolve("build/classes/java/test"));
        ClasspathEntries.addIfExists(entries, project.resolve("build/resources/test"));
    }

    private String initScript() {
        return """
                allprojects {
                  tasks.register("runtimeConditionsClasspath") {
                    dependsOn(tasks.matching { it.name == "classes" })
                    doLast {
                      def sourceSets = project.extensions.findByName("sourceSets")
                      def paths = []
                      if (sourceSets != null) {
                        def main = sourceSets.findByName("main")
                        if (main != null) {
                          main.output.classesDirs.files.each { paths << it.absolutePath }
                          if (main.output.resourcesDir != null) {
                            paths << main.output.resourcesDir.absolutePath
                          }
                        }
                      }
                      def runtime = configurations.findByName("runtimeClasspath")
                      if (runtime == null) {
                        runtime = configurations.findByName("compileClasspath")
                      }
                      if (runtime != null) {
                        runtime.files.each { paths << it.absolutePath }
                      }
                      println("RCP_CLASSPATH=" + paths.findAll { it != null }.join(File.pathSeparator))
                    }
                  }
                }
                """;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private String commandOutput(CommandResult result) {
        String output = (result.stderr() + "\n" + result.stdout()).trim();
        return output.isEmpty() ? "<no output>" : output;
    }
}

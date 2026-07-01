package io.runtimeconditions.profiler;

import io.runtimeconditions.profiler.classpath.GradleClasspathResolver;
import io.runtimeconditions.profiler.classpath.MavenClasspathResolver;
import io.runtimeconditions.profiler.command.CommandResult;
import io.runtimeconditions.profiler.command.CommandRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ClasspathResolverTest {
    private ClasspathResolverTest() {
    }

    public static void main(String[] args) throws Exception {
        verifiesMavenWrapperAndClasspathParsing();
        verifiesGradleWrapperAndClasspathParsing();
    }

    private static void verifiesMavenWrapperAndClasspathParsing() throws Exception {
        Path root = Files.createTempDirectory("runtimeconditions-maven-root");
        Path module = root.resolve("service");
        Files.createDirectories(root.resolve("target/classes"));
        Files.createDirectories(module.resolve("target/classes"));
        Files.writeString(root.resolve("pom.xml"), "<project />");
        Files.writeString(module.resolve("pom.xml"), "<project />");
        Files.createFile(root.resolve("mvnw"));
        Path dependency = Files.createTempFile("runtimeconditions-maven-dependency", ".jar");

        FakeRunner runner = new FakeRunner((command, workingDirectory) -> {
            String outputPath = command.stream()
                    .filter(item -> item.startsWith("-Dmdep.outputFile="))
                    .findFirst()
                    .orElseThrow()
                    .substring("-Dmdep.outputFile=".length());
            Files.writeString(Path.of(outputPath), dependency.toString());
            return new CommandResult(0, "", "");
        });

        List<Path> entries = new MavenClasspathResolver(runner).resolve(root, List.of(module));
        assertTrue(runner.commands.get(0).get(0).endsWith("mvnw"), "Maven resolver should prefer mvnw");
        assertEquals(2, runner.commands.size(), "Maven resolver should inspect root and module POMs");
        assertContains(entries, root.resolve("target/classes"), "Maven root output");
        assertContains(entries, module.resolve("target/classes"), "Maven module output");
        assertContains(entries, dependency, "Maven dependency JAR");
    }

    private static void verifiesGradleWrapperAndClasspathParsing() throws Exception {
        Path root = Files.createTempDirectory("runtimeconditions-gradle-root");
        Path module = root.resolve("mapped-sdk");
        Files.createDirectories(module.resolve("build/resources/main"));
        Files.createFile(root.resolve("gradlew"));
        Path dependency = Files.createTempFile("runtimeconditions-gradle-dependency", ".jar");

        FakeRunner runner = new FakeRunner((command, workingDirectory) -> new CommandResult(
                0,
                "ignored\nRCP_CLASSPATH=" + dependency + java.io.File.pathSeparator + module.resolve("build/resources/main") + "\n",
                ""));

        List<Path> entries = new GradleClasspathResolver(runner).resolve(root, List.of(module));
        assertTrue(runner.commands.get(0).get(0).endsWith("gradlew"), "Gradle resolver should prefer gradlew");
        assertTrue(runner.commands.get(0).contains("--no-daemon"), "Gradle resolver should avoid persistent daemons");
        assertTrue(runner.commands.get(0).contains("runtimeConditionsClasspath"), "Gradle resolver should run injected classpath task");
        assertContains(entries, module.resolve("build/resources/main"), "Gradle module resources");
        assertContains(entries, dependency, "Gradle dependency JAR");
    }

    private static void assertContains(List<Path> entries, Path expected, String message) {
        Path normalized = expected.toAbsolutePath().normalize();
        if (!entries.contains(normalized)) {
            throw new AssertionError(message + ": missing " + normalized + " from " + entries);
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private interface FakeAction {
        CommandResult run(List<String> command, Path workingDirectory) throws IOException;
    }

    private static final class FakeRunner implements CommandRunner {
        private final FakeAction action;
        private final List<List<String>> commands = new ArrayList<>();

        private FakeRunner(FakeAction action) {
            this.action = action;
        }

        @Override
        public CommandResult run(List<String> command, Path workingDirectory) throws IOException {
            commands.add(List.copyOf(command));
            return action.run(command, workingDirectory);
        }
    }
}

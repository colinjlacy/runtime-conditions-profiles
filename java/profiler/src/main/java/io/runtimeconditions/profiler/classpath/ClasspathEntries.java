package io.runtimeconditions.profiler.classpath;

import io.runtimeconditions.profiler.command.CommandResult;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class ClasspathEntries {
    private ClasspathEntries() {
    }

    static List<Path> parse(String value, Path workingDirectory) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String[] parts = value.split(java.util.regex.Pattern.quote(File.pathSeparator));
        List<Path> entries = new ArrayList<>();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            Path path = Path.of(part.trim());
            if (!path.isAbsolute()) {
                path = workingDirectory.resolve(path);
            }
            entries.add(path.toAbsolutePath().normalize());
        }
        return entries;
    }

    static void addIfExists(Set<Path> entries, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (Files.exists(normalized)) {
            entries.add(normalized);
        }
    }

    static Set<Path> set() {
        return new LinkedHashSet<>();
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    static String commandOutput(CommandResult result) {
        String output = (result.stderr() + "\n" + result.stdout()).trim();
        return output.isEmpty() ? "<no output>" : output;
    }
}

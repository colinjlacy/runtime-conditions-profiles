package io.runtimeconditions.profiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class DefaultCommandRunner implements CommandRunner {
    private static final long TIMEOUT_SECONDS = 120;

    @Override
    public CommandResult run(List<String> command, Path workingDirectory) throws IOException {
        Path stdout = Files.createTempFile("runtimeconditions-command-stdout", ".log");
        Path stderr = Files.createTempFile("runtimeconditions-command-stderr", ".log");
        Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .start();
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("command timed out after " + TIMEOUT_SECONDS + "s: " + String.join(" ", command));
            }
            return new CommandResult(
                    process.exitValue(),
                    Files.readString(stdout, StandardCharsets.UTF_8),
                    Files.readString(stderr, StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while running command: " + String.join(" ", command), e);
        } finally {
            Files.deleteIfExists(stdout);
            Files.deleteIfExists(stderr);
        }
    }
}

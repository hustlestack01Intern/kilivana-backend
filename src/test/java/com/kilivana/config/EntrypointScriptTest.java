package com.kilivana.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.FileCopyUtils;

/**
 * Executes the real {@code docker/entrypoint.sh} rather than reading it.
 *
 * <p>The behaviours pinned here are the ones whose failure mode is silent and expensive: a
 * {@code chown -R} of the wrong directory, a skipped privilege drop, and an entrypoint that
 * dies on a stray space. Asserting that the file "contains {@code chown -R appuser:appgroup}"
 * proves the file has the right characters and nothing about what the shell does with them.
 *
 * <p>The script is driven with a stub {@code java}, {@code su-exec} and {@code id} on a
 * prepended {@code PATH}, so the run terminates at a marker instead of needing a JVM, a
 * container, or root. The script itself is the shipped file, unmodified.
 */
class EntrypointScriptTest {

    private static final Path SCRIPT = Path.of("").toAbsolutePath().resolve("docker/entrypoint.sh");

    @Test
    @DisplayName("the entrypoint is executable shell")
    void entrypointIsExecutableShell() throws Exception {
        assertThat(Files.isExecutable(SCRIPT)).isTrue();

        Process result = new ProcessBuilder("sh", "-n", SCRIPT.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(result.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(result.waitFor()).as(output).isZero();
    }

    @Test
    @DisplayName("a relative MEDIA_ROOT is refused before any chown runs")
    void refusesARelativeMediaRoot(@TempDir Path bin, @TempDir Path work) throws Exception {
        // Without the guard this is `chown -R appuser:appgroup uploads`, run as root against a
        // path relative to whatever the working directory happened to be.
        Result result = run(bin, work, Map.of("MEDIA_ROOT", "uploads"), 0);

        assertThat(result.exitCode).isEqualTo(1);
        assertThat(result.output).contains("MEDIA_ROOT='uploads' is not an absolute path");
        assertThat(result.output).doesNotContain("STUB-CHOWN");
    }

    @Test
    @DisplayName("MEDIA_ROOT=/ is refused, so the root chown can never be chown -R /")
    void refusesTheContainerRootAsMediaRoot(@TempDir Path bin, @TempDir Path work) throws Exception {
        Result result = run(bin, work, Map.of("MEDIA_ROOT", "/"), 0);

        assertThat(result.exitCode).isEqualTo(1);
        assertThat(result.output).contains("is the container root");
        assertThat(result.output).doesNotContain("STUB-CHOWN");
    }

    @Test
    @DisplayName("a root-looking MEDIA_ROOT with a trailing slash is refused too")
    void refusesTheContainerRootWithATrailingSlash(@TempDir Path bin, @TempDir Path work) throws Exception {
        // The guard strips exactly one trailing slash before comparing, so "//" and "/" agree.
        // A check written as `case "$MEDIA_ROOT" in /)` would have passed this one through to
        // `chown -R //`, which is the same directory.
        Result result = run(bin, work, Map.of("MEDIA_ROOT", "//"), 0);

        assertThat(result.exitCode).isEqualTo(1);
        assertThat(result.output).contains("is the container root");
    }

    @Test
    @DisplayName("as root a valid MEDIA_ROOT is chowned and privileges are dropped")
    void chownsThenDropsPrivilegesWhenRoot(@TempDir Path bin, @TempDir Path scratch) throws Exception {
        // A space in the path, deliberately: this is what catches an unquoted expansion.
        // `chown -R appuser:appgroup $MEDIA_ROOT` word-splits into two arguments and the
        // volume silently ends up half-chowned with no error, which is the failure this
        // assertion exists to prevent. @TempDir's own name has no space in it, so the
        // directory is created here rather than relying on the fixture.
        Path work = Files.createDirectory(scratch.resolve("work dir"));
        Path mediaRoot = Files.createDirectory(work.resolve("uploads"));

        Result result = run(bin, work, Map.of("MEDIA_ROOT", mediaRoot.toString()), 0);

        // The chown runs as one argument despite the space, and the script re-enters itself as
        // appuser rather than exec'ing the JVM at uid 0.
        assertThat(result.output).contains("STUB-CHOWN -R appuser:appgroup " + mediaRoot);
        assertThat(result.output).contains("STUB-SU-EXEC appuser /bin/sh");
        assertThat(result.output).doesNotContain("STUB-JAVA");
    }

    @Test
    @DisplayName("as a non-root uid the chown is skipped rather than failing")
    void skipsTheChownWhenNotRoot(@TempDir Path bin, @TempDir Path work) throws Exception {
        Path mediaRoot = Files.createDirectory(work.resolve("uploads"));

        Result result = run(bin, work, Map.of("MEDIA_ROOT", mediaRoot.toString()), 1000);

        // The directory is only ours to fix if we own it. `set -e` plus a failing chown here
        // would crash-loop a container that is otherwise fine.
        assertThat(result.exitCode).isZero();
        assertThat(result.output).doesNotContain("STUB-CHOWN");
        assertThat(result.output).contains("STUB-JAVA");
    }

    @Test
    @DisplayName("the guard runs before the JVM, so a bad MEDIA_ROOT never reaches java")
    void theGuardPrecedesTheJvm(@TempDir Path bin, @TempDir Path work) throws Exception {
        Result result = run(bin, work, Map.of("MEDIA_ROOT", "not/absolute"), 0);

        assertThat(result.output).doesNotContain("STUB-JAVA");
    }

    private static Result run(Path bin, Path work, Map<String, String> environment, int uid)
            throws IOException, InterruptedException {
        stub(bin, "id", "#!/bin/sh\necho " + uid + "\n");
        // su-exec never actually switches user, so the stub only has to be recognisable.
        stub(bin, "su-exec", "#!/bin/sh\necho \"STUB-SU-EXEC $@\"\n");
        stub(bin, "chown", "#!/bin/sh\necho \"STUB-CHOWN $@\"\n");
        stub(bin, "java", "#!/bin/sh\necho \"STUB-JAVA $@\"\n");

        List<String> command = new ArrayList<>(List.of("sh", SCRIPT.toString()));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(work.toFile())
                .redirectErrorStream(true);
        builder.environment().put("PATH", bin + ":" + builder.environment().getOrDefault("PATH", ""));
        // A stale MEDIA_ROOT in the developer's own shell must not decide the test outcome.
        builder.environment().remove("MEDIA_ROOT");
        builder.environment().putAll(environment);
        builder.environment().put("JAVA_OPTS", "-Xmx64m");

        Process process = builder.start();
        String output = FileCopyUtils.copyToString(new java.io.InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8));
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("the entrypoint never terminated").isTrue();
        return new Result(process.exitValue(), output);
    }

    private static void stub(Path bin, String name, String body) throws IOException {
        Path script = bin.resolve(name);
        Files.createFile(script);
        Files.writeString(script, body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private record Result(int exitCode, String output) {
    }
}

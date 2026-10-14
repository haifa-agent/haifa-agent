package io.haifa.agent.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.sdk.api.HaifaAgentException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R5/R9: the runtime probe has one shared bounded deadline - merged stdout and stderr consumption,
 * the process wait and the cleanup of a runtime that ignores graceful termination are all bounded
 * by the same deadline (no extra 5s/1s windows), a hanging runtime is destroyed and reaped (no
 * residual writes), and an interrupted assembly thread stops waiting immediately.
 */
class SkillScriptRuntimeProbeTest {
    private static final Duration TEST_DEADLINE = Duration.ofSeconds(2);

    @Test
    void acceptsAProbeThatPrintsItsVersionAndExits(@TempDir Path temporary) throws IOException {
        Path runtime = writeRuntime(temporary, "ok", "");
        var runtimeDeclaration = new SkillScriptPlatforms.SkillScriptRuntime("bash", runtime);
        runtimeDeclaration.preflight(TEST_DEADLINE);
    }

    @Test
    void boundsAProbeWhoseRuntimeHandsOutOutputButNeverExits(@TempDir Path temporary) throws IOException {
        Path residue = temporary.resolve("residue.txt");
        Path runtime = writeRuntime(temporary, "hang", residue.toString());
        var runtimeDeclaration = new SkillScriptPlatforms.SkillScriptRuntime("bash", runtime);

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> runtimeDeclaration.preflight(TEST_DEADLINE))
                .isInstanceOf(HaifaAgentException.class)
                .hasFieldOrPropertyWithValue("code", "SKILL_SCRIPT_RUNTIME_UNAVAILABLE")
                .hasMessageContaining("deadline");
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt).getSeconds())
                .as("the shared deadline - not a fixed cleanup window - bounded the preflight")
                .isLessThanOrEqualTo(3);

        // The probe destroyed the hanging child: it never got to write its residue marker.
        for (int attempt = 0; attempt < 40; attempt++) {
            if (Files.exists(residue)) break;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(Files.exists(residue))
                .as("the hanging runtime left no residual writes")
                .isFalse();
    }

    @Test
    void keepsDrainingEvenWhenStderrFloodsTheProbe(@TempDir Path temporary) throws IOException {
        Path runtime = writeRuntime(temporary, "flood", "");
        var runtimeDeclaration = new SkillScriptPlatforms.SkillScriptRuntime("bash", runtime);
        runtimeDeclaration.preflight(TEST_DEADLINE);
    }

    @Test
    void interruptingTheProbeStopsTheWaitAndDestroysTheProcess(@TempDir Path temporary) throws IOException {
        Path runtime = writeRuntime(temporary, "hang", "");
        var runtimeDeclaration = new SkillScriptPlatforms.SkillScriptRuntime("bash", runtime);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
        Thread probeThread = new Thread(() -> {
            try {
                runtimeDeclaration.preflight(Duration.ofSeconds(120));
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        probeThread.setDaemon(true);
        probeThread.start();
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        probeThread.interrupt();
        long startedJoin = System.nanoTime();
        try {
            probeThread.join(TimeUnit.SECONDS.toMillis(10));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while joining the probe thread", exception);
        }

        assertThat(Duration.ofNanos(System.nanoTime() - startedJoin).getSeconds())
                .as("the interrupted preflight stopped waiting promptly")
                .isLessThanOrEqualTo(5);
        assertThat(failure.get())
                .as(failure.get() == null ? "the probe threw" : failure.get().toString())
                .isInstanceOf(HaifaAgentException.class)
                .hasFieldOrPropertyWithValue("code", "SKILL_SCRIPT_RUNTIME_UNAVAILABLE")
                .hasMessageContaining("interrupted");
        assertThat(interrupted.get()).isTrue();
    }

    /**
     * Records one probe fixture runtime that understands {@code --version}. {@code kind} selects
     * the behavior: exit quickly after printing the version, hang past the deadline while writing
     * a residue marker only if it survives that long, or flood stderr before exiting.
     */
    private static Path writeRuntime(Path temporary, String kind, String residue) throws IOException {
        Path runtime = temporary.resolve("fixture-runtime" + (isWindows() ? ".cmd" : ".sh"));
        String script;
        if (isWindows()) {
            script = switch (kind) {
                case "hang" ->
                    "@echo off\r\n"
                            + "echo fake-runtime 1.0\r\n"
                            + "ping -n 4 127.0.0.1 >NUL\r\n"
                            + "echo residue >> \"" + residue + "\"\r\n"
                            + "ping -n 32 127.0.0.1 >NUL\r\n";
                case "flood" ->
                    "@echo off\r\n"
                            + "for /l %%i in (1,1,2000) do @echo %%i stderr flood noise 1>&2\r\n"
                            + "echo fake-runtime 1.0\r\n";
                default -> "@echo off\r\necho fake-runtime 1.0\r\n";
            };
            Files.writeString(runtime, script, StandardCharsets.ISO_8859_1);
        } else {
            script = switch (kind) {
                case "hang" ->
                    "#!/bin/sh\n"
                            + "echo fake-runtime 1.0\n"
                            + "sleep 3\n"
                            + "echo residue >> \"" + residue + "\"\n"
                            + "sleep 30\n";
                case "flood" ->
                    "#!/bin/sh\n"
                            + "i=0; while [ $i -lt 2000 ]; do echo \"$i stderr flood noise\" 1>&2; i=$((i+1)); done\n"
                            + "echo fake-runtime 1.0\n";
                default -> "#!/bin/sh\necho fake-runtime 1.0\n";
            };
            Files.writeString(runtime, script, StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(runtime, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        assertThat(runtime.toFile().length()).isGreaterThan(0);
        return runtime;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}

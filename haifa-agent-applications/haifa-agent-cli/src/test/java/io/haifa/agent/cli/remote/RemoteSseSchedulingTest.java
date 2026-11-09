package io.haifa.agent.cli.remote;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * A child JVM is required because the virtual-thread scheduler's size is process-wide and fixed at first use.
 * One carrier must support idle SSE observations and concurrent RPCs on the supported Java 21 runtime.
 */
class RemoteSseSchedulingTest {
    @Test
    void concurrentStreamsAndRpcProgressWithOneCarrier() throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String java =
                Path.of(System.getProperty("java.home"), "bin", executable).toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process child = new ProcessBuilder(
                        java,
                        "-Xmx128m",
                        "-Djdk.virtualThreadScheduler.parallelism=1",
                        "-Djdk.virtualThreadScheduler.maxPoolSize=1",
                        "-cp",
                        classpath,
                        RemoteSseSchedulingTest.class.getName())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            assertThat(child.waitFor(20, TimeUnit.SECONDS))
                    .as("single-carrier stream/RPC exchange completes")
                    .isTrue();
            assertThat(child.exitValue()).as("single-carrier stream/RPC result").isZero();
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        new RemoteCodingSessionClientTest().preservesWhitespaceOutputAndDeliversEventAndOutputFrames();
    }
}

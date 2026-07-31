package io.haifa.agent.testing.process;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ProcessTreeCleanupTest {
    @Test
    void convergesAJavaParentAndChildProcessTree() throws Exception {
        Process process = start("parent");
        ProcessTreeCleanup.Tracker tracker = ProcessTreeCleanup.track(process);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (process.descendants().findAny().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            assertTrue(process.isAlive());
            assertTrue(process.descendants().findAny().isPresent());

            ProcessTreeCleanup.Result result = tracker.converge(false, Duration.ofSeconds(3));

            assertTrue(result.passed());
            assertTrue(result.observedDescendants() >= 1);
            assertTrue(result.terminationRequests() >= 1);
            assertFalse(result.naturalExit());
            assertFalse(result.rootAlive());
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    @Test
    void remembersAndConvergesAChildAfterItsParentExits() throws Exception {
        Process process = start("parent-exits");
        ProcessTreeCleanup.Tracker tracker = ProcessTreeCleanup.track(process);
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));

            ProcessTreeCleanup.Result result = tracker.converge(true, Duration.ofSeconds(3));

            assertTrue(result.passed());
            assertTrue(result.observedDescendants() >= 1);
            assertTrue(result.terminationRequests() >= 1);
            assertFalse(result.naturalExit());
            assertFalse(result.rootAlive());
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    private static Process start(String mode) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", executable("java"))
                .toString();
        return new ProcessBuilder(
                        java,
                        "-cp",
                        System.getProperty("java.class.path"),
                        ProcessTreeCleanupTest.class.getName(),
                        mode)
                .start();
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 1 && (arguments[0].equals("parent") || arguments[0].equals("parent-exits"))) {
            String java = Path.of(System.getProperty("java.home"), "bin", executable("java"))
                    .toString();
            new ProcessBuilder(
                            java,
                            "-cp",
                            System.getProperty("java.class.path"),
                            ProcessTreeCleanupTest.class.getName(),
                            "child")
                    .start();
            if (arguments[0].equals("parent-exits")) {
                Thread.sleep(500);
                return;
            }
        }
        Thread.sleep(TimeUnit.MINUTES.toMillis(5));
    }

    private static String executable(String name) {
        return System.getProperty("os.name", "").toLowerCase().contains("windows") ? name + ".exe" : name;
    }
}

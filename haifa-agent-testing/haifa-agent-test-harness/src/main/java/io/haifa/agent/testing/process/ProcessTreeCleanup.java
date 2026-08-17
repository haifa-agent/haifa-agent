package io.haifa.agent.testing.process;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tracks and converges a child process tree on both POSIX and Windows hosts. */
public final class ProcessTreeCleanup {
    private ProcessTreeCleanup() {}

    public static Tracker track(Process process) {
        return new Tracker(process);
    }

    public static final class Tracker {
        private final Process process;
        private final ConcurrentHashMap<Long, ProcessHandle> observed = new ConcurrentHashMap<>();
        private final AtomicBoolean tracking = new AtomicBoolean(true);
        private final AtomicBoolean converged = new AtomicBoolean();
        private final Thread observer;

        private Tracker(Process process) {
            this.process = process;
            observe();
            observer = Thread.startVirtualThread(this::observeUntilStopped);
        }

        public Result converge(boolean completedWithinBudget, Duration grace) throws InterruptedException {
            if (!converged.compareAndSet(false, true)) {
                throw new IllegalStateException("process tree tracker has already converged");
            }
            int terminationRequests = 0;
            try {
                observe();
                if (!completedWithinBudget
                        || process.isAlive()
                        || observed.values().stream().anyMatch(ProcessHandle::isAlive)) {
                    terminationRequests += destroy(snapshot(), false);
                    if (process.isAlive()) {
                        process.destroy();
                        terminationRequests++;
                    }
                    waitForExit(grace);
                    observe();
                    terminationRequests += destroy(snapshot(), true);
                    if (process.isAlive()) {
                        process.destroyForcibly();
                        terminationRequests++;
                    }
                    waitForExit(grace);
                }
            } finally {
                tracking.set(false);
                observer.interrupt();
                observer.join();
            }
            observe();
            long descendantsAlive =
                    observed.values().stream().filter(ProcessHandle::isAlive).count();
            boolean rootAlive = process.isAlive();
            return new Result(
                    completedWithinBudget,
                    observed.size(),
                    terminationRequests,
                    descendantsAlive,
                    rootAlive,
                    descendantsAlive == 0 && !rootAlive);
        }

        private void observeUntilStopped() {
            try {
                while (tracking.get()) {
                    observe();
                    Thread.sleep(25);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                observe();
            }
        }

        private void observe() {
            process.descendants().forEach(handle -> observed.putIfAbsent(handle.pid(), handle));
        }

        private List<ProcessHandle> snapshot() {
            return List.copyOf(observed.values());
        }

        private void waitForExit(Duration grace) throws InterruptedException {
            long deadline = System.nanoTime() + grace.toNanos();
            while (System.nanoTime() < deadline) {
                observe();
                if (!process.isAlive() && observed.values().stream().noneMatch(ProcessHandle::isAlive)) return;
                Thread.sleep(25);
            }
        }
    }

    private static int destroy(List<ProcessHandle> handles, boolean forcibly) {
        int requests = 0;
        List<ProcessHandle> reverse = new ArrayList<>(handles);
        for (ProcessHandle handle : reverse.reversed()) {
            if (handle.isAlive() && (forcibly ? handle.destroyForcibly() : handle.destroy())) {
                requests++;
            }
        }
        return requests;
    }

    public record Result(
            boolean completedWithinBudget,
            int observedDescendants,
            int terminationRequests,
            long descendantsAlive,
            boolean rootAlive,
            boolean passed) {
        public boolean naturalExit() {
            return completedWithinBudget && terminationRequests == 0 && passed;
        }

        public Map<String, Object> artifact(int driverExitStatus) {
            LinkedHashMap<String, Object> artifact = new LinkedHashMap<>();
            artifact.put("schemaVersion", 1);
            artifact.put("driverExitStatus", driverExitStatus);
            artifact.put("timedOut", !completedWithinBudget);
            artifact.put("observedDescendants", observedDescendants);
            artifact.put("terminationRequests", terminationRequests);
            artifact.put("descendantsAlive", descendantsAlive);
            artifact.put("rootAlive", rootAlive);
            artifact.put("naturalExit", naturalExit());
            artifact.put("passed", passed);
            return Map.copyOf(artifact);
        }
    }
}

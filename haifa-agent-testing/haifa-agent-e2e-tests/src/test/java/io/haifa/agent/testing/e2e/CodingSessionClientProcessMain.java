package io.haifa.agent.testing.e2e;

import io.haifa.agent.cli.StandaloneCodingAgents;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Provider-neutral child-process entry used to verify the public Coding Agent assembly across JVMs. */
public final class CodingSessionClientProcessMain {
    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(12);

    private CodingSessionClientProcessMain() {}

    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = execute(args);
        } catch (RuntimeException exception) {
            System.err.println(
                    "CODING_CLIENT_PROCESS_FAILED:" + exception.getClass().getSimpleName());
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    private static int execute(String[] args) {
        if (args.length != 3) {
            System.err.println("Usage: CodingSessionClientProcessMain <workspace> <configuration> <task>");
            return 64;
        }
        Path workspace = Path.of(args[0]).toAbsolutePath().normalize();
        Path configuration = Path.of(args[1]).toAbsolutePath().normalize();
        try (var agent = StandaloneCodingAgents.open(workspace, configuration)) {
            var client = agent.client();
            var created = client.create(agent.projectId(), args[2], "coding-client-process-" + UUID.randomUUID());
            var sessionId = created.summary().sessionId();
            var snapshot = created.activeRun().orElseThrow();
            Instant deadline = Instant.now().plus(RUN_TIMEOUT);
            while (!snapshot.status().isTerminal() && Instant.now().isBefore(deadline)) {
                if (client.pendingInteraction(snapshot.runId()).isPresent()) {
                    return 3;
                }
                sleep();
                snapshot = client.open(sessionId).activeRun().orElseThrow();
            }
            if (!snapshot.status().isTerminal()) {
                client.cancel(sessionId, "coding-client-process-timeout-" + UUID.randomUUID());
                return 124;
            }
            return snapshot.status().name().equals("COMPLETED") ? 0 : 2;
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(25);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Coding Agent completion", exception);
        }
    }
}

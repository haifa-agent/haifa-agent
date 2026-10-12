package io.haifa.agent.runtime.core.execution;

import io.haifa.agent.runtime.core.attempt.AgentRunExecutionAttempt;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Worker/lease ownership boundary; distributed adapters can validate a durable lease here. */
@FunctionalInterface
public interface ExecutionOwnershipPort {
    boolean stillOwned(AgentRunExecutionAttempt attempt);

    /** Host-process marker appended to a worker id so a local runtime can verify the owner's liveness. */
    String HOST_PROCESS_MARKER = "-hostpid-";

    /** How the owner of an attempt relates to this host right now. */
    enum OwnerLiveness {
        /** Owned by this runtime instance. */
        OWNED,
        /** Owned by another process on this host that is still running. */
        ALIVE,
        /** Owned by a process on this host that is no longer running. */
        GONE,
        /** Ownership host state cannot be determined. */
        UNKNOWN
    }

    /**
     * Classifies the attempt's owner for abandoned-run settlement. The default implementation only knows about this
     * runtime instance: an owned attempt is {@link OwnerLiveness#OWNED}, everything else is
     * {@link OwnerLiveness#UNKNOWN}.
     */
    default OwnerLiveness ownerLiveness(AgentRunExecutionAttempt attempt) {
        return stillOwned(attempt) ? OwnerLiveness.OWNED : OwnerLiveness.UNKNOWN;
    }

    static ExecutionOwnershipPort local(String processInstanceId) {
        String currentInstance = Objects.requireNonNull(processInstanceId, "processInstanceId must not be null")
                .trim();
        if (currentInstance.isEmpty()) {
            throw new IllegalArgumentException("processInstanceId must not be blank");
        }
        return new ExecutionOwnershipPort() {
            @Override
            public boolean stillOwned(AgentRunExecutionAttempt attempt) {
                return attempt.workerId().filter(currentInstance::equals).isPresent();
            }

            @Override
            public OwnerLiveness ownerLiveness(AgentRunExecutionAttempt attempt) {
                if (stillOwned(attempt)) return OwnerLiveness.OWNED;
                Optional<String> workerId = attempt.workerId();
                if (workerId.isEmpty()) return OwnerLiveness.UNKNOWN;
                OptionalLong pid = hostProcessId(workerId.orElseThrow());
                if (pid.isEmpty()) return OwnerLiveness.UNKNOWN;
                boolean alive = ProcessHandle.of(pid.getAsLong())
                        .map(ProcessHandle::isAlive)
                        .orElse(false);
                return alive ? OwnerLiveness.ALIVE : OwnerLiveness.GONE;
            }
        };
    }

    /** Extracts the host process id encoded by {@link #HOST_PROCESS_MARKER}, if present and numeric. */
    static OptionalLong hostProcessId(String workerId) {
        if (workerId == null) return OptionalLong.empty();
        int marker = workerId.lastIndexOf(HOST_PROCESS_MARKER);
        if (marker < 0) return OptionalLong.empty();
        String digits = workerId.substring(marker + HOST_PROCESS_MARKER.length());
        if (digits.isEmpty()) return OptionalLong.empty();
        try {
            return OptionalLong.of(Long.parseLong(digits));
        } catch (NumberFormatException exception) {
            return OptionalLong.empty();
        }
    }
}

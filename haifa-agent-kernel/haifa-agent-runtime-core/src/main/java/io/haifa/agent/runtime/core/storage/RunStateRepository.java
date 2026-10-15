package io.haifa.agent.runtime.core.storage;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunId;
import java.util.List;
import java.util.Optional;

public interface RunStateRepository {
    void insert(AgentRun run);

    void save(AgentRun run, long expectedVersion);

    Optional<AgentRun> find(AgentRunId runId);

    /**
     * Reads only a Run owned by the supplied trusted caller. Adapters that materialize referenced
     * payloads should filter stored ownership before decoding those payloads.
     */
    default Optional<AgentRun> findVisible(AgentRunId runId, TenantRef tenant, PrincipalRef principal) {
        java.util.Objects.requireNonNull(tenant, "tenant must not be null");
        java.util.Objects.requireNonNull(principal, "principal must not be null");
        return find(java.util.Objects.requireNonNull(runId, "runId must not be null"))
                .filter(run -> tenant.equals(run.tenant()) && principal.equals(run.principal()));
    }

    /** Direct child runs of {@code parentRunId}, oldest first; the parent-child relation is the run row itself. */
    List<AgentRun> children(AgentRunId parentRunId);
}

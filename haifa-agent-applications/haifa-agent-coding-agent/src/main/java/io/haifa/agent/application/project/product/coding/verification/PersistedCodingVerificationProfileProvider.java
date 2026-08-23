package io.haifa.agent.application.project.product.coding.verification;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.core.storage.AgentSessionRepository;
import io.haifa.agent.runtime.core.storage.RunStateRepository;
import java.util.Objects;

/** Resolves the immutable profile from the Run's authoritative persisted Core Session. */
public final class PersistedCodingVerificationProfileProvider implements CodingVerificationProfileProvider {
    private final RunStateRepository runs;
    private final AgentSessionRepository sessions;

    public PersistedCodingVerificationProfileProvider(RunStateRepository runs, AgentSessionRepository sessions) {
        this.runs = Objects.requireNonNull(runs, "runs must not be null");
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
    }

    @Override
    public CodingSessionVerificationConfiguration configurationFor(AgentRunId runId) {
        return runs.find(Objects.requireNonNull(runId, "runId must not be null"))
                .flatMap(run -> sessions.find(run.sessionId()))
                .flatMap(session -> CodingSessionVerificationConfiguration.fromSessionMetadata(session.metadata()))
                .orElseGet(() -> CodingSessionVerificationConfiguration.freeze(CodingVerificationProfile.empty()));
    }
}

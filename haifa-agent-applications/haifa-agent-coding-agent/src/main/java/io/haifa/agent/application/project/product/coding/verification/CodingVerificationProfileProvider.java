package io.haifa.agent.application.project.product.coding.verification;

import io.haifa.agent.core.run.AgentRunId;

@FunctionalInterface
public interface CodingVerificationProfileProvider {
    CodingSessionVerificationConfiguration configurationFor(AgentRunId runId);

    static CodingVerificationProfileProvider empty() {
        CodingSessionVerificationConfiguration empty =
                CodingSessionVerificationConfiguration.freeze(CodingVerificationProfile.empty());
        return ignored -> empty;
    }
}

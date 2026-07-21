package io.haifa.agent.core.agent;

import static io.haifa.agent.core.support.DomainValues.requireText;

/** Schema-qualified output contract expected from an Agent definition. */
public record AgentOutputContractRef(String schemaId, String schemaVersion) {
    public AgentOutputContractRef {
        schemaId = requireText(schemaId, "schemaId");
        schemaVersion = requireText(schemaVersion, "schemaVersion");
    }
}

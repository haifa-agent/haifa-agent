package io.haifa.agent.core.agent;

import static io.haifa.agent.core.support.DomainValues.requireText;

/** Versioned capability needed to instantiate an Agent definition. */
public record AgentCapabilityRequirement(String capabilityId, String versionConstraint, boolean required) {
    public AgentCapabilityRequirement {
        capabilityId = requireText(capabilityId, "capabilityId");
        versionConstraint = requireText(versionConstraint, "versionConstraint");
    }
}

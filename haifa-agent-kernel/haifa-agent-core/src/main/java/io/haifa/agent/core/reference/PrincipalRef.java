package io.haifa.agent.core.reference;

import static io.haifa.agent.core.support.DomainValues.requireText;

/** Stable owner identity without an enterprise identity aggregate. */
public record PrincipalRef(String principalId, String principalType) {
    public PrincipalRef {
        principalId = requireText(principalId, "principalId");
        principalType = requireText(principalType, "principalType");
    }
}

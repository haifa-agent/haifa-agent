package io.haifa.agent.runtime.core.model.continuation;

import io.haifa.agent.model.api.SensitiveModelReasoning;

/** Production boundary for protecting or explicitly encoding controlled provider-continuation payloads. */
public interface ModelContinuationProtector {
    /** Whether the protected bytes provide confidentiality at rest. */
    default boolean providesConfidentiality() {
        return true;
    }

    /** Whether protected bytes can be reopened by a newly constructed repository instance. */
    default boolean supportsPersistentStorage() {
        return true;
    }

    ProtectedModelReasoning protect(SensitiveModelReasoning reasoning, String binding);

    SensitiveModelReasoning reveal(ProtectedModelReasoning payload, String binding);
}

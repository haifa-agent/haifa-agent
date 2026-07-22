package io.haifa.agent.runtime.core.model.continuation;

import io.haifa.agent.model.api.SensitiveModelReasoning;

/** Production boundary for encrypting controlled provider-continuation payloads. */
public interface ModelContinuationProtector {
    ProtectedModelReasoning protect(SensitiveModelReasoning reasoning, String binding);

    SensitiveModelReasoning reveal(ProtectedModelReasoning payload, String binding);
}

package io.haifa.agent.runtime.core.model.continuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.model.api.SensitiveModelReasoning;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PlaintextModelContinuationProtectorTest {
    private final PlaintextModelContinuationProtector protector = new PlaintextModelContinuationProtector();

    @Test
    void persistsReadablePayloadAndRevealsItWithTheSameBinding() {
        SensitiveModelReasoning reasoning = SensitiveModelReasoning.of("local plaintext payload");

        ProtectedModelReasoning protectedReasoning = protector.protect(reasoning, "binding-a");

        assertThat(protector.providesConfidentiality()).isFalse();
        assertThat(new String(protectedReasoning.persistenceEnvelope().ciphertext(), StandardCharsets.UTF_8))
                .contains("local plaintext payload");
        assertThat(protector.reveal(protectedReasoning, "binding-a")).isEqualTo(reasoning);
    }

    @Test
    void rejectsBindingMismatchAndContentCorruption() {
        ProtectedModelReasoning protectedReasoning =
                protector.protect(SensitiveModelReasoning.of("payload"), "binding-a");

        assertThatThrownBy(() -> protector.reveal(protectedReasoning, "binding-b"))
                .isInstanceOf(ModelContinuationException.class)
                .extracting(exception -> ((ModelContinuationException) exception).failure())
                .isEqualTo(ModelContinuationFailure.CORRUPT);

        ProtectedModelReasoningEnvelope envelope = protectedReasoning.persistenceEnvelope();
        byte[] corrupted = envelope.ciphertext();
        corrupted[corrupted.length - 1] ^= 1;
        ProtectedModelReasoning corruptedPayload = ProtectedModelReasoning.fromPersistenceEnvelope(
                new ProtectedModelReasoningEnvelope(envelope.nonce(), corrupted));
        assertThatThrownBy(() -> protector.reveal(corruptedPayload, "binding-a"))
                .isInstanceOf(ModelContinuationException.class);
    }
}

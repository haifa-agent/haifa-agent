package io.haifa.agent.runtime.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class RunEventPayloadsTest {
    @Test
    void assistantTextDeltaPreservesWhitespaceOnlyChunksVerbatim() {
        var payload = new RunEventPayloads.AssistantTextDelta("generation", " \n\t");

        assertThat(payload.textDelta()).isEqualTo(" \n\t");
    }

    @Test
    void assistantTextDeltaStillRejectsMissingAndEmptyChunks() {
        assertThatThrownBy(() -> new RunEventPayloads.AssistantTextDelta("generation", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunEventPayloads.AssistantTextDelta("generation", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void deliveryLifecycleAcceptsOnlyBoundedSafeCodes() {
        var payload = new RunEventPayloads.DeliveryLifecycle(
                "VERIFYING",
                "COMPLETION_DEFERRED",
                "DIFF_INSPECTION_MISSING",
                List.of("DIFF_INSPECTION", "VALIDATION_ATTEMPT"),
                25,
                1);
        assertThat(payload.missingEvidence()).containsExactly("DIFF_INSPECTION", "VALIDATION_ATTEMPT");

        assertThatThrownBy(() -> new RunEventPayloads.DeliveryLifecycle(
                        "VERIFYING", "COMPLETION_DEFERRED", "CODE", List.of("/host/path"), 25, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunEventPayloads.DeliveryLifecycle(
                        "VERIFYING", "COMPLETION_DEFERRED", "CODE", List.of(), 101, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

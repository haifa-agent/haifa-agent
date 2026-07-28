package io.haifa.agent.runtime.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}

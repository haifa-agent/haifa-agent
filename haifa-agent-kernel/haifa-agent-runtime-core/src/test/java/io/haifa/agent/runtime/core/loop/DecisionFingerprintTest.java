package io.haifa.agent.runtime.core.loop;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.runtime.core.decision.ToolCallDecision;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DecisionFingerprintTest {
    @Test
    void persistedFingerprintIsBoundedDeterministicAndContainsNoRawArgumentsOrIdentifiers() {
        ToolCallDecision first = decision("call-a", "C:/private/random-a", "CANARY_SECRET_BODY");
        ToolCallDecision sameSemanticAction = decision("call-b", "C:/private/random-a", "CANARY_SECRET_BODY");
        ToolCallDecision otherAction = decision("call-c", "C:/private/random-b", "OTHER_BODY");

        String fingerprint = DecisionFingerprint.of(first);

        assertThat(fingerprint)
                .isEqualTo(DecisionFingerprint.of(sameSemanticAction))
                .matches("action/1:TOOL_CALL:[0-9a-f]{64}")
                .doesNotContain("call-a", "provider-call-a", "C:/private", "CANARY_SECRET_BODY");
        assertThat(fingerprint).isNotEqualTo(DecisionFingerprint.of(otherAction));
    }

    private static ToolCallDecision decision(String id, String path, String body) {
        return new ToolCallDecision(List.of(new ToolRequest(
                new ToolCallId(id),
                new ProviderToolCallCorrelationId("provider-" + id),
                new RuntimeIdempotencyKey("key-" + id),
                "file.write",
                "1.0.0",
                new ToolArguments(
                        "file.write.input",
                        "1",
                        Map.of("path", path, "body", body, "options", Map.of("replace", true))))));
    }
}

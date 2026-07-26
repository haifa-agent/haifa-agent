package io.haifa.agent.runtime.core.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.RunInputId;
import io.haifa.agent.runtime.api.RunInputSubmission;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class CanonicalRequestDigestTest {
    @Test
    void isDeterministicSensitiveTextOpaqueAndContentSensitive() {
        RunInputSubmission first = input("sk-fake-secret");
        RunInputSubmission same = input("sk-fake-secret");
        RunInputSubmission changed = input("different");

        String digest = CanonicalRequestDigest.runInput(first);
        assertThat(digest)
                .isEqualTo(CanonicalRequestDigest.runInput(same))
                .isNotEqualTo(CanonicalRequestDigest.runInput(changed))
                .doesNotContain("sk-fake-secret")
                .matches("[0-9a-f]{64}");
    }

    private static RunInputSubmission input(String text) {
        return new RunInputSubmission(
                new RunInputId("input-1"),
                new AgentRunId("run-1"),
                OptionalLong.of(3),
                List.of(new TextPart(text, "text/plain")),
                "input-key",
                Instant.parse("2026-07-26T00:00:00Z"));
    }
}

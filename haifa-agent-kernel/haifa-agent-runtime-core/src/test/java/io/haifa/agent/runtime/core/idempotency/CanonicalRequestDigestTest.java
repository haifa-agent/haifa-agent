package io.haifa.agent.runtime.core.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.content.StoredImageContentPart;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.RunInputId;
import io.haifa.agent.runtime.api.RunInputSubmission;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class CanonicalRequestDigestTest {
    @Test
    void agentRunDigestExcludesIdempotencyKeyAndIncludesStableRequestContent() {
        AgentRunRequest first = runRequest("key-one", "objective", Map.of("maxIterations", 4));
        AgentRunRequest same = runRequest("key-two", "objective", Map.of("maxIterations", 4));
        AgentRunRequest changed = runRequest("key-one", "changed", Map.of("maxIterations", 4));

        assertThat(CanonicalRequestDigest.agentRun(first))
                .isEqualTo(CanonicalRequestDigest.agentRun(same))
                .isNotEqualTo(CanonicalRequestDigest.agentRun(changed))
                .matches("[0-9a-f]{64}");
    }

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

    @Test
    void ignoresSubMillisecondTimestampDifferences() {
        RunInputSubmission first = input("value", Instant.parse("2026-07-26T00:00:00.123456Z"));
        RunInputSubmission sameMillisecond = input("value", Instant.parse("2026-07-26T00:00:00.123999Z"));

        assertThat(CanonicalRequestDigest.runInput(first)).isEqualTo(CanonicalRequestDigest.runInput(sameMillisecond));
    }

    @Test
    void agentRunDigestSupportsOpaqueStoredImageInputs() {
        var image = new StoredImageContentPart(
                "personal-local", "image-1", "image/png", 9, "sha256:" + "a".repeat(64), "cat.png");
        AgentRunRequest request = new AgentRunRequest(
                "key",
                new AgentDefinitionId("agent"),
                Optional.empty(),
                "profile",
                new AgentSessionId("session"),
                Optional.empty(),
                "describe image",
                List.of(image),
                RuntimeOverrides.NONE);

        assertThat(CanonicalRequestDigest.agentRun(request))
                .matches("[0-9a-f]{64}")
                .doesNotContain(image.imageId());
    }

    private static RunInputSubmission input(String text) {
        return input(text, Instant.parse("2026-07-26T00:00:00Z"));
    }

    private static RunInputSubmission input(String text, Instant submittedAt) {
        return new RunInputSubmission(
                new RunInputId("input-1"),
                new AgentRunId("run-1"),
                OptionalLong.of(3),
                List.of(new TextPart(text, "text/plain")),
                "input-key",
                submittedAt);
    }

    private static AgentRunRequest runRequest(String key, String objective, Map<String, Object> overrides) {
        return new AgentRunRequest(
                key,
                new AgentDefinitionId("agent"),
                Optional.empty(),
                "profile",
                new AgentSessionId("session"),
                Optional.of(new ProjectRef("project")),
                objective,
                List.of(new TextPart("input", "text/plain")),
                new RuntimeOverrides("runtime.overrides", "1.0", overrides));
    }
}

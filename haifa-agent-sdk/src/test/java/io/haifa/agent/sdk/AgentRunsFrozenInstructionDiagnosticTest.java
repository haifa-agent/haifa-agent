package io.haifa.agent.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelAdapterCoordinate;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.runtime.api.FrozenInstructionDiagnostic;
import io.haifa.agent.sdk.api.HaifaAgents;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.product.ProductProfile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentRunsFrozenInstructionDiagnosticTest {
    @Test
    void rebuiltFacadeWithProfileBReturnsOriginalATerminalDigestWithoutInvokingModel() throws Exception {
        var persistence = SdkTestFixtures.persistenceContribution();
        var conversation = SdkTestFixtures.conversationContribution();
        var profileA = profile("  Synthetic original instruction A: 中文\nline two  ");
        FrozenInstructionDiagnostic frozen;
        AgentRunId runId;
        try (var first = HaifaAgents.builder(profileA)
                .model(SdkTestFixtures.modelContribution())
                .persistence(persistence)
                .conversation(conversation)
                .build()) {
            runId = first.conversations()
                    .start(new StartConversationCommand("frozen-a", "Synthetic fixture", "Return a fixture answer"))
                    .runId();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!first.runs().find(runId).orElseThrow().status().isTerminal() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(first.runs().find(runId).orElseThrow().status().isTerminal())
                    .isTrue();
            frozen = first.runs().frozenInstructionDiagnostic(runId).orElseThrow();
            assertThat(frozen.instructionContentHash()).isEqualTo(hash(profileA.instructions()));
            assertThat(first.runs().promptDiagnostics(runId).available()).isTrue();
            var messages =
                    first.runs().messages(runId, io.haifa.agent.runtime.api.RunMessageCursor.beforeFirst(runId), 10);
            assertThat(messages.items()).singleElement().satisfies(message -> {
                assertThat(message.role()).isEqualTo("ASSISTANT");
                assertThat(message.messageIndex()).isEqualTo(1);
                assertThat(message.text()).isNotBlank();
            });
        }
        var profileB = profile("Synthetic current instruction B");
        AtomicInteger calls = new AtomicInteger();
        var snapshot = SdkTestFixtures.snapshot();
        var model = new ModelContribution(
                Map.of(ModelAdapterCoordinate.from(snapshot), request -> {
                    calls.incrementAndGet();
                    return new AgentChatResponse(
                            "never-called",
                            "test-chat",
                            "answer",
                            List.of(),
                            ModelFinishReason.STOP,
                            ModelUsage.unpriced(1, 1),
                            "",
                            Map.of());
                }),
                snapshot,
                Map.of(snapshot.modelId().value(), snapshot));
        try (var rebuilt = HaifaAgents.builder(profileB)
                .model(model)
                .persistence(persistence)
                .conversation(conversation)
                .build()) {
            assertThat(rebuilt.profile().instructions()).isEqualTo(profileB.instructions());
            assertThat(rebuilt.runs().promptDiagnostics(runId).available()).isFalse();
            assertThat(rebuilt.runs().frozenInstructionDiagnostic(runId)).contains(frozen);
            assertThat(frozen.instructionContentHash()).isNotEqualTo(hash(profileB.instructions()));
            assertThat(calls).hasValue(0);
        }
    }

    private static ProductProfile profile(String instructions) {
        var p = SdkTestFixtures.profile("frozen-instruction");
        return new ProductProfile(
                p.productId(),
                p.productVersion(),
                p.definitionId(),
                p.definitionVersion(),
                instructions,
                p.defaultRunProfile(),
                p.budget(),
                p.limits(),
                p.allowedTools(),
                p.allowedSkills());
    }

    private static String hash(String text) throws Exception {
        return "sha256:"
                + HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}

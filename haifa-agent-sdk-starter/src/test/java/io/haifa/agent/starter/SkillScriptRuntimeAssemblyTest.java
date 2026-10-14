package io.haifa.agent.starter;

import static io.haifa.agent.starter.SkillScriptTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelAdapterCoordinate;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.policy.api.PolicyPresets;
import io.haifa.agent.policy.core.DefaultPolicyDecisionService;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.sdk.api.AgentMetadata;
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.api.HaifaAgents;
import io.haifa.agent.sdk.api.ModelImageResolver;
import io.haifa.agent.sdk.api.SdkCallerProvider;
import io.haifa.agent.sdk.contribution.InMemoryConversationContribution;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.sdk.contribution.SdkContributions;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.product.ProductId;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.sdk.product.ProductRunProfileRef;
import io.haifa.agent.sdk.product.ProductVersion;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R1: one assembly use case through the real SDK Runtime and its ToolPipeline. The model emits one
 * Tool Call, the Runtime asks for approval, the approval resumes the Run, and the trusted script
 * Tool executes a real local entry-file script. A run that completes proves the Tool output passed
 * the pipeline's output-schema validation, and the readable workspace output proves the product
 * file chain still owns the artifact.
 */
class SkillScriptRuntimeAssemblyTest {

    @Test
    void executesTheApprovedSkillScriptToolThroughTheRealRuntime(@TempDir Path temporary) throws Exception {
        Path runtime = SkillScriptTestSupport.runtimeExecutable();
        if (!java.nio.file.Files.isRegularFile(runtime)) {
            return;
        }
        Path workspace = SkillScriptTestSupport.createWorkspace(temporary, "workspace");
        Path dataRoot = SkillScriptTestSupport.createWorkspace(temporary, "data");
        var loader = new SkillScriptTestSupport.SkillPackageLoader(
                SkillScriptTestSupport.packageFiles(SkillScriptTestSupport.entryScript(false)));
        var declaration = declaration(
                loader.frozen, runtimeLanguage(), Duration.ofSeconds(60), "verify the runtime-approved skill script");
        var spec = spec(workspace, dataRoot, loader, List.of(declaration));

        AtomicInteger modelCalls = new AtomicInteger();
        AgentChatModel model = request -> {
            if (modelCalls.incrementAndGet() == 1) {
                assertThat(request.tools()).extracting("name").containsExactly("generate_note");
                return new AgentChatResponse(
                        "tool-response",
                        request.model().providerModelId(),
                        "",
                        List.of(new ModelToolCall(
                                new ProviderToolCallCorrelationId("skill-script-call-1"), "generate_note", Map.of())),
                        ModelFinishReason.TOOL_CALLS,
                        ModelUsage.unpriced(4, 2),
                        "",
                        Map.of());
            }
            return new AgentChatResponse(
                    "final-response",
                    request.model().providerModelId(),
                    "Generated the note.",
                    List.of(),
                    ModelFinishReason.STOP,
                    ModelUsage.unpriced(5, 3),
                    "",
                    Map.of());
        };

        try (var platform = SkillScriptPlatforms.trusted(spec, TENANT, PRINCIPAL);
                HaifaAgent agent = runtimeAgent(model, platform)) {
            var conversation =
                    agent.conversations().start(new StartConversationCommand("skill-1", "Notes", "Generate one."));
            approvePendingToolCall(agent, conversation.runId());
            var completed = agent.runs().await(conversation.runId());

            assertThat(completed.status())
                    .as(
                            "run failed with: %s",
                            completed.error().map(e -> e.message()).orElse("none"))
                    .isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(completed.output()).contains("Generated the note.");
            assertThat(modelCalls).hasValue(2);

            // The persisted Tool Call shows the result that the real pipeline validated and
            // persisted; the output schema test is the pipeline's own check above.
            var toolCalls = agent.runs().toolCalls(conversation.runId());
            assertThat(toolCalls).hasSize(1);
            assertThat(toolCalls.getFirst().status()).isEqualTo(ToolCallStatus.COMPLETED);
            var result = toolCalls.getFirst().result().orElseThrow();
            assertThat(result.structuredData().values()).containsEntry("processState", "EXITED");
            // The artifact the script wrote is still readable from the product file chain.
            assertThat(java.nio.file.Files.readString(
                            workspace.resolve("outputs").resolve("result.txt"), StandardCharsets.UTF_8))
                    .isEqualTo(SkillScriptTestSupport.MARKER + "\n");
        }
    }

    private static HaifaAgent runtimeAgent(AgentChatModel model, SkillScriptPlatforms.SkillScriptPlatform platform) {
        ProductProfile profile = ProductProfile.create(
                new ProductId("skill-script-entry-tests"),
                new ProductVersion("1.0.0"),
                new AgentDefinitionId("skill-script-entry-tests"),
                new AgentDefinitionVersion(1, 0, 0),
                "You run trusted skill scripts under approval.",
                new ProductRunProfileRef("starter-model", "1.0.0"),
                new AgentRunBudget(65_536, 8_192, 65_536, 16, 16, 0, "USD", 100),
                new AgentRunLimits(16, 0, 1, 120_000, 60_000, 16, 16, 0),
                Set.of(),
                Set.of());
        ResolvedModelSnapshot snapshot = ResolvedModelSnapshot.create(
                new ModelProviderId("test"),
                "1.0.0",
                new ModelDefinitionId("skill-script-entry-test"),
                "1.0.0",
                "skill-script-entry-test",
                "test-adapter",
                "1.0.0",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                ModelApiBindingDefinition.STANDARD_DIALECT,
                URI.create("https://model.invalid"),
                new CredentialRef("env://TEST_KEY"),
                false,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                8_192,
                1_024,
                Map.of(),
                Map.of());
        var builder = HaifaAgents.builder(profile)
                .metadata(new AgentMetadata("skill-script-entry-tests"))
                .callerProvider(SdkCallerProvider.defaultPublicUser())
                .model(new ModelContribution(
                        Map.of(ModelAdapterCoordinate.from(snapshot), model),
                        snapshot,
                        Map.of(snapshot.modelId().value(), snapshot)))
                .persistence(SdkContributions.inMemoryPersistence())
                .conversation(new InMemoryConversationContribution())
                .modelImageResolver(ModelImageResolver.unsupported())
                .tools(List.of())
                .policy(new PolicyPlatformContribution(
                        PolicyPresets.standardApproval(), new DefaultPolicyDecisionService()));
        platform.applyTo(builder);
        return builder.build();
    }

    private static void approvePendingToolCall(HaifaAgent agent, AgentRunId runId) throws InterruptedException {
        for (int attempt = 0; attempt < 400; attempt++) {
            var pending = agent.runs().pendingInteraction(runId);
            if (pending.isPresent()) {
                var interaction = pending.orElseThrow();
                agent.runs()
                        .respond(new InteractionResponseSubmission(
                                new InteractionResponseId("skill-approval-1"),
                                interaction.requestId(),
                                runId,
                                interaction.revision(),
                                InteractionAction.APPROVE,
                                List.of(),
                                "skill-approval-key-1",
                                Instant.now()));
                return;
            }
            if (agent.runs()
                    .find(runId)
                    .map(snapshot -> snapshot.status().isTerminal())
                    .orElse(false)) {
                return;
            }
            Thread.sleep(25);
        }
        throw new IllegalStateException("no approval interaction appeared for the trusted script Tool call");
    }
}

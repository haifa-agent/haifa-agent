package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.context.compression.CompactionQuality;
import io.haifa.agent.context.compression.ConversationSummary;
import io.haifa.agent.context.compression.SemanticConfidence;
import io.haifa.agent.context.compression.SemanticConversationSummaryV1;
import io.haifa.agent.context.compression.SemanticDecisionItem;
import io.haifa.agent.context.compression.SemanticDecisionStatus;
import io.haifa.agent.context.compression.SemanticProgress;
import io.haifa.agent.context.compression.SemanticSummaryItem;
import io.haifa.agent.context.compression.SummaryId;
import io.haifa.agent.context.compression.SummaryVersion;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageCursor;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.core.run.StructuredOutputRequirement;
import io.haifa.agent.core.step.AgentStep;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.step.AgentStepType;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.memory.api.MemoryId;
import io.haifa.agent.memory.api.MemoryScope;
import io.haifa.agent.memory.api.MemoryScopeType;
import io.haifa.agent.memory.api.MemoryVersion;
import io.haifa.agent.memory.api.MemoryVisibility;
import io.haifa.agent.model.api.ApiStyleId;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.model.api.SensitiveModelReasoning;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.bootstrap.RuntimeConfigurationSnapshot;
import io.haifa.agent.runtime.core.checkpoint.MemoryCheckpointRef;
import io.haifa.agent.runtime.core.model.continuation.AesGcmModelContinuationProtector;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationDraft;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationRef;
import io.haifa.agent.runtime.core.storage.RuntimeMemorySelection;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import io.haifa.agent.skill.api.FrozenSkillBinding;
import io.haifa.agent.skill.api.SkillActivation;
import io.haifa.agent.skill.api.SkillAlias;
import io.haifa.agent.skill.api.SkillContentDigest;
import io.haifa.agent.skill.api.SkillCoordinate;
import io.haifa.agent.skill.api.SkillMetadata;
import io.haifa.agent.skill.api.SkillName;
import io.haifa.agent.skill.api.SkillPackageIndex;
import io.haifa.agent.skill.api.SkillPackageReviewGrant;
import io.haifa.agent.skill.api.SkillResourceKind;
import io.haifa.agent.skill.api.SkillResourceRef;
import io.haifa.agent.skill.api.SkillScopeRef;
import io.haifa.agent.skill.api.SkillSourceRef;
import io.haifa.agent.skill.api.SkillTrustGrantState;
import io.haifa.agent.skill.api.SkillTrustScope;
import io.haifa.agent.skill.api.SkillTrustSnapshot;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteExtendedRuntimeStateTest {
    private static final Instant NOW = SqliteAggregateTestData.NOW;
    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Test
    void configurationSummaryMemoryAssetAndContinuationSurviveReopen(@TempDir java.nio.file.Path directory)
            throws Exception {
        SqliteStoreFoundation first = SqliteTestSupport.foundation(directory);
        var run = SqliteAggregateTestData.prepareRun(first);
        var protector = protector(KEY);
        var state = first.runtimeState(protector);

        RuntimeConfigurationSnapshot configuration = configuration();
        state.saveConfiguration(configuration);
        RuntimeMemorySelection memorySelection = new RuntimeMemorySelection(
                List.of(new MemoryCheckpointRef(
                        new MemoryId("memory"),
                        new MemoryVersion(2),
                        new MemoryScope(
                                run.tenant(),
                                run.principal(),
                                MemoryScopeType.USER,
                                run.principal().principalId(),
                                MemoryVisibility.OWNER_ONLY,
                                Set.of()))),
                "memory-policy-2",
                "sha256:query");
        state.saveMemorySelection(run.id(), memorySelection);
        SkillActivation activation = activation();
        assertThat(state.saveSkillActivation(run.id(), activation, 10_000, 10_000))
                .isEqualTo(activation);
        assertThat(state.addSkillResourceReadBytes(run.id(), 5, 10)).isEqualTo(5);

        AgentStep step =
                new AgentStep(new AgentStepId("step"), run.id(), null, null, new AgentStepType("tool"), 1, NOW);
        state.appendStep(step);
        String hostAbsolutePath =
                directory.resolve("readme").toAbsolutePath().normalize().toString();
        ToolCall call = new ToolCall(
                new ToolCallId("tool-call"),
                run.id(),
                step.id(),
                new ProviderToolCallCorrelationId("provider-call"),
                new RuntimeIdempotencyKey("asset-key"),
                "file.read",
                "1",
                new ToolArguments("tool.arguments", "1", Map.of("path", hostAbsolutePath)),
                NOW);
        state.appendToolCall(call);
        ToolResult result =
                new ToolResult(true, "read", Map.of("path", hostAbsolutePath, "bytes", 5), List.of(), List.of(), false);
        var asset = first.toolResultAssets().put(call.id(), result);
        AgentStep secondStep =
                new AgentStep(new AgentStepId("step-2"), run.id(), null, null, new AgentStepType("tool"), 2, NOW);
        state.appendStep(secondStep);
        ToolCall secondCall = new ToolCall(
                new ToolCallId("tool-call-2"),
                run.id(),
                secondStep.id(),
                new ProviderToolCallCorrelationId("provider-call-2"),
                new RuntimeIdempotencyKey("asset-key-2"),
                "file.read",
                "1",
                new ToolArguments("tool.arguments", "1", Map.of("path", "readme")),
                NOW);
        state.appendToolCall(secondCall);
        var secondAsset = first.toolResultAssets().put(secondCall.id(), result);
        assertThat(secondAsset).isNotEqualTo(asset);

        SensitiveModelReasoning reasoning = SensitiveModelReasoning.of("sensitive-reasoning-plaintext");
        SessionMessageDraft message = new SessionMessageDraft(
                new AgentMessageId("assistant-message"),
                run.sessionId(),
                Optional.of(run.id()),
                Optional.empty(),
                MessageRole.ASSISTANT,
                MessageStatus.COMPLETED,
                MessageVisibility.INTERNAL,
                List.of(new TextPart("answer", "plain")),
                Map.of(),
                NOW.plusSeconds(1));
        var reference = new ModelContinuationRef("continuation", "1.0", reasoning.digest(), reasoning.byteLength());
        state.appendSessionMessageWithContinuation(
                message,
                new ModelContinuationDraft(
                        reference,
                        run.id(),
                        run.sessionId(),
                        "model-call",
                        "deepseek",
                        "deepseek-chat",
                        configuration.model().configurationDigest(),
                        Set.of("provider-call"),
                        reasoning,
                        NOW.plusSeconds(1)));

        ConversationSummary summary = new ConversationSummary(
                new SummaryId("summary"),
                new SummaryVersion(1),
                run.sessionId(),
                new MessageCursor(1),
                new MessageCursor(1),
                List.of(message.id()),
                "sha256:source",
                List.of("fact"),
                List.of(),
                List.of(),
                List.of(call.id()),
                10,
                NOW.plusSeconds(2),
                "policy-1",
                "compressor-1",
                Set.of("internal"),
                true);
        first.summaries().compareAndSet(summary, 0);

        SqliteStoreFoundation reopened = SqliteTestSupport.foundation(directory);
        var reopenedState = reopened.runtimeState(protector(KEY));
        assertThat(reopenedState.configuration(configuration.reference())).contains(configuration);
        assertThat(reopenedState
                        .configuration(configuration.reference())
                        .orElseThrow()
                        .skillTrust())
                .isEqualTo(configuration.skillTrust());
        assertThat(reopenedState.memorySelection(run.id())).contains(memorySelection);
        assertThat(reopenedState.skillActivation(run.id(), new SkillAlias("test-skill")))
                .contains(activation);
        assertThat(reopenedState.addSkillResourceReadBytes(run.id(), 5, 10)).isEqualTo(10);
        assertThat(reopenedState.toolCalls(run.id()))
                .filteredOn(restored -> restored.id().equals(call.id()))
                .singleElement()
                .satisfies(
                        restored -> assertThat(restored.arguments().values()).containsEntry("path", hostAbsolutePath));
        assertThat(reopened.toolResultAssets().load(asset)).contains(result);
        assertThat(reopened.toolResultAssets().load(secondAsset)).contains(result);
        assertThat(reopened.summaries().latestValid(run.sessionId())).contains(summary);
        assertThat(reopenedState.resolveContinuation(message.id(), configuration.model(), Set.of("provider-call")))
                .isEqualTo(reasoning);

        assertThatThrownBy(() -> reopened.runtimeState(AesGcmModelContinuationProtector.ephemeral()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reopened.runtimeState(
                                protector("abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8)))
                        .resolveContinuation(message.id(), configuration.model(), Set.of("provider-call")))
                .isInstanceOf(RuntimeException.class);
        reopened.unitOfWork().execute(() -> {
            try (var statement = reopened.unitOfWork()
                    .currentConnection()
                    .prepareStatement("UPDATE model_continuation SET ciphertext_hash = ? WHERE continuation_id = ?")) {
                statement.setString(1, "sha256:" + "f".repeat(64));
                statement.setString(2, reference.id());
                statement.executeUpdate();
            } catch (java.sql.SQLException exception) {
                throw new IllegalStateException(exception);
            }
            return null;
        });
        assertThatThrownBy(() ->
                        reopenedState.resolveContinuation(message.id(), configuration.model(), Set.of("provider-call")))
                .isInstanceOf(RuntimeException.class);

        try (var paths = java.nio.file.Files.list(directory)) {
            for (var path : paths.toList()) {
                assertThat(new String(java.nio.file.Files.readAllBytes(path), StandardCharsets.ISO_8859_1))
                        .doesNotContain("sensitive-reasoning-plaintext")
                        .doesNotContain("0123456789abcdef0123456789abcdef");
            }
        }
    }

    @Test
    void semanticConversationSummaryV2SurvivesReopen(@TempDir java.nio.file.Path directory) throws Exception {
        SqliteStoreFoundation first = SqliteTestSupport.foundation(directory);
        var run = SqliteAggregateTestData.prepareRun(first);

        SemanticSummaryItem goal = new SemanticSummaryItem(
                "G-1", "Build semantic compaction", List.of("msg-1"), SemanticConfidence.OBSERVED);
        SemanticSummaryItem completed =
                new SemanticSummaryItem("P-1", "Phase 1 complete", List.of("msg-1"), SemanticConfidence.OBSERVED);
        SemanticDecisionItem decision = new SemanticDecisionItem(
                "D-1",
                "Store normalized V2 payload",
                "Avoid redundant markdown",
                SemanticDecisionStatus.ACCEPTED,
                List.of("msg-2"));
        SemanticSummaryItem nextStep =
                new SemanticSummaryItem("N-1", "Phase 3 coordinator", List.of(), SemanticConfidence.INFERRED);
        SemanticSummaryItem context =
                new SemanticSummaryItem("C-1", "Pure Java context", List.of("msg-1"), SemanticConfidence.OBSERVED);

        SemanticConversationSummaryV1 semantic = new SemanticConversationSummaryV1(
                "v1",
                "en",
                List.of(goal),
                List.of(),
                new SemanticProgress(List.of(completed), List.of(), List.of()),
                List.of(decision),
                List.of(nextStep),
                List.of(context),
                List.of());

        ConversationSummary v2Summary = new ConversationSummary(
                new SummaryId("summary-v2"),
                new SummaryVersion(1),
                run.sessionId(),
                new MessageCursor(1),
                new MessageCursor(2),
                List.of(new AgentMessageId("msg-1"), new AgentMessageId("msg-2")),
                "sha256:source-v2",
                List.of("Pure Java context"),
                List.of("Store normalized V2 payload"),
                List.of("Phase 3 coordinator"),
                List.of(new ToolCallId("tool-1")),
                25,
                NOW,
                "policy-v2",
                "compressor-semantic-v1",
                Set.of("internal"),
                true,
                Optional.of(semantic),
                CompactionQuality.SEMANTIC_VALIDATED);

        first.summaries().compareAndSet(v2Summary, 0);

        SqliteStoreFoundation reopened = SqliteTestSupport.foundation(directory);
        Optional<ConversationSummary> loaded = reopened.summaries().latestValid(run.sessionId());
        assertThat(loaded).isPresent();
        ConversationSummary actual = loaded.get();
        assertThat(actual.id()).isEqualTo(v2Summary.id());
        assertThat(actual.version()).isEqualTo(v2Summary.version());
        assertThat(actual.quality()).isEqualTo(CompactionQuality.SEMANTIC_VALIDATED);
        assertThat(actual.semanticSummary()).isPresent();
        assertThat(actual.semanticSummary().get()).isEqualTo(semantic);
        assertThat(actual.semanticSummary().get().goals()).containsExactly(goal);
        assertThat(actual.semanticSummary().get().decisions()).containsExactly(decision);
    }

    private static AesGcmModelContinuationProtector protector(byte[] key) {
        return new AesGcmModelContinuationProtector(new SecretKeySpec(key, "AES"), new SecureRandom());
    }

    private static RuntimeConfigurationSnapshot configuration() {
        FrozenSkillBinding original = activation().binding();
        FrozenSkillBinding skill = new FrozenSkillBinding(
                original.alias(),
                original.coordinate(),
                original.metadata(),
                original.packageIndex(),
                original.resourceIndexDigest(),
                original.registrationDigest(),
                original.resolutionPolicyRef(),
                Optional.of("package-review"));
        SkillPackageReviewGrant packageGrant = new SkillPackageReviewGrant(
                "package-review",
                1,
                1,
                new TenantRef("tenant"),
                new PrincipalRef("principal", "user"),
                "profile",
                SkillTrustScope.PRODUCT,
                Optional.empty(),
                skill.coordinate(),
                skill.registrationDigest(),
                skill.resourceIndexDigest(),
                NOW.minusSeconds(60),
                Optional.of(NOW.plusSeconds(600)),
                Optional.empty(),
                SkillTrustGrantState.ACTIVE,
                "reviewer",
                "sqlite-fixture",
                "SKILL_PACKAGE_REVIEWED");
        ResolvedModelSnapshot model = ResolvedModelSnapshot.create(
                new ModelProviderId("deepseek"),
                "1",
                new ModelDefinitionId("deepseek-chat"),
                "1",
                "deepseek-chat",
                "openai-compatible",
                "1",
                new ApiStyleId("openai-chat-completions"),
                "standard",
                URI.create("https://api.deepseek.com"),
                new CredentialRef("env://DEEPSEEK_API_KEY"),
                true,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.STRUCTURED_OUTPUT),
                8_192,
                1_024,
                Map.of(),
                Map.of());
        return new RuntimeConfigurationSnapshot(
                new RunConfigurationSnapshotRef("configuration-2", "sha256:configuration-2"),
                new AgentDefinitionId("agent"),
                new AgentDefinitionVersion(1, 0, 0),
                "profile",
                "1",
                AgentRunType.CHAT,
                new AgentRunBudget(100, 100, 100, 10, 10, 2, "USD", 100),
                new AgentRunLimits(10, 2, 1, 60_000, 10_000),
                List.of(),
                List.of(skill),
                new SkillContentDigest("sha256:" + "0".repeat(64)),
                "skill-policy-1",
                new SkillTrustSnapshot("sha256:" + "3".repeat(64), List.of(packageGrant), List.of()),
                Set.of(),
                "answer",
                RuntimeOverrides.NONE,
                List.of(),
                model,
                Map.of(),
                Optional.of(new StructuredOutputRequirement(
                        "java-record:TripPlan",
                        "sha256:trip-plan",
                        "TripPlan",
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of("city", Map.of("type", "string")),
                                "required",
                                List.of("city"),
                                "additionalProperties",
                                false))));
    }

    private static SkillActivation activation() {
        SkillContentDigest content = new SkillContentDigest("sha256:" + "1".repeat(64));
        SkillContentDigest registration = new SkillContentDigest("sha256:" + "2".repeat(64));
        SkillName name = new SkillName("test-skill");
        SkillMetadata metadata = new SkillMetadata(
                name, "test", Optional.empty(), Optional.empty(), Optional.empty(), Map.of(), Set.of());
        SkillPackageIndex index = new SkillPackageIndex(
                content,
                List.of(new SkillResourceRef(
                        "SKILL.md", SkillResourceKind.INSTRUCTION, "text/markdown", content, 10, true)));
        FrozenSkillBinding binding = new FrozenSkillBinding(
                new SkillAlias(name.value()),
                new SkillCoordinate(
                        SkillScopeRef.sdk(), new SkillSourceRef("test", "1"), name, Optional.empty(), content),
                metadata,
                index,
                content,
                registration,
                "skill-policy-1");
        return new SkillActivation(binding, "needed", "test", NOW, 10, 3);
    }
}

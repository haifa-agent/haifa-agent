package io.haifa.agent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.context.api.ContextBuildException;
import io.haifa.agent.context.api.ContextBuildFailure;
import io.haifa.agent.context.api.ContextBuildRequest;
import io.haifa.agent.context.budget.TokenEstimator;
import io.haifa.agent.context.core.DefaultAgentContextBuilder;
import io.haifa.agent.context.item.AssetDerivedTextContent;
import io.haifa.agent.context.item.ContextItem;
import io.haifa.agent.context.item.ContextItemId;
import io.haifa.agent.context.item.ContextItemType;
import io.haifa.agent.context.item.ContextPriority;
import io.haifa.agent.context.item.ContextProvenance;
import io.haifa.agent.context.item.ContextRetention;
import io.haifa.agent.context.item.ContextRole;
import io.haifa.agent.context.item.ContextSecurity;
import io.haifa.agent.context.item.DerivedTextKind;
import io.haifa.agent.context.item.TextContextContent;
import io.haifa.agent.context.prompt.PromptComponent;
import io.haifa.agent.context.prompt.PromptComponentId;
import io.haifa.agent.context.prompt.PromptLayer;
import io.haifa.agent.context.prompt.PromptRole;
import io.haifa.agent.context.selection.ContextSelectionPolicy;
import io.haifa.agent.context.trace.ContextSelectionDecision;
import io.haifa.agent.core.reference.AssetRef;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunUsage;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ContextCoreTest {
    private static final String SECRET_BODY = "secret-body-must-not-enter-trace";

    @Test
    void deterministicallySelectsDerivedTextAndTracesDropsWithoutBodies() {
        ContextItem required =
                item("required", "required-hash", 25, ContextPriority.CRITICAL, ContextRetention.MUST_KEEP, true);
        ContextItem duplicate =
                item("duplicate", "required-hash", 25, ContextPriority.HIGH, ContextRetention.KEEP_IF_RELEVANT, true);
        ContextItem blocked = new ContextItem(
                new ContextItemId("blocked"),
                ContextItemType.RUNTIME_STATE,
                new TextContextContent(ContextRole.SYSTEM, SECRET_BODY),
                5,
                ContextPriority.HIGH,
                ContextRetention.KEEP_IF_RELEVANT,
                new ContextSecurity(Set.of("secret"), false),
                new ContextProvenance("runtime", "blocked", "1", "blocked-hash"),
                Map.of());
        ContextItem asset = new ContextItem(
                new ContextItemId("asset-ocr"),
                ContextItemType.ASSET_DERIVED_TEXT,
                new AssetDerivedTextContent(
                        new AssetRef("asset-1", "image/png", "scan.png"), DerivedTextKind.OCR, "invoice total 42"),
                15,
                ContextPriority.NORMAL,
                ContextRetention.KEEP_IF_RELEVANT,
                ContextSecurity.INTERNAL,
                new ContextProvenance("asset", "asset-1", "ocr-v1", "asset-hash"),
                Map.of("derivation", "ocr-v1"));
        ContextItem overflow =
                item("overflow", "overflow-hash", 40, ContextPriority.LOW, ContextRetention.DROP_FIRST, true);

        var result = builder().build(request(List.of(required, duplicate, blocked, asset, overflow), 20, 10));

        assertThat(result.context().items())
                .extracting(value -> value.id().value())
                .containsExactly("required", "asset-ocr");
        assertThat(result.context().items().get(1).content()).isInstanceOf(AssetDerivedTextContent.class);
        assertThat(result.context().budget().modelContextWindow()).isEqualTo(100);
        assertThat(result.context().budget().outputReserve()).isEqualTo(20);
        assertThat(result.context().budget().availableInputTokens()).isEqualTo(70);
        assertThat(result.context().estimatedInputTokens()).isEqualTo(50);
        assertThat(result.trace().items())
                .extracting(value -> value.decision())
                .containsExactly(
                        ContextSelectionDecision.SELECTED,
                        ContextSelectionDecision.DROPPED_SECURITY,
                        ContextSelectionDecision.DROPPED_DUPLICATE,
                        ContextSelectionDecision.SELECTED,
                        ContextSelectionDecision.DROPPED_BUDGET);
        assertThat(result.trace().toString()).doesNotContain(SECRET_BODY).doesNotContain("invoice total 42");
    }

    @Test
    void requiredOverflowAndExhaustedRunBudgetsFailWithTypedReasons() {
        ContextItem tooLarge =
                item("too-large", "large-hash", 61, ContextPriority.CRITICAL, ContextRetention.MUST_KEEP, true);
        assertThatThrownBy(() -> builder().build(request(List.of(tooLarge), 20, 10)))
                .isInstanceOf(ContextBuildException.class)
                .extracting(error -> ((ContextBuildException) error).failure())
                .isEqualTo(ContextBuildFailure.REQUIRED_CONTEXT_TOO_LARGE);

        ContextBuildRequest exhausted = new ContextBuildRequest(
                new AgentRunId("run"),
                new AgentSessionId("session"),
                new TenantRef("tenant"),
                new PrincipalRef("principal", "user"),
                1,
                snapshot(),
                new AgentRunBudget(10, 100, 0, 10, 10, 1, "USD", 100),
                new AgentRunUsage(10, 0, 0, 0, 0, 0, 0, 0),
                List.of(prompt()),
                List.of(),
                List.of(),
                20,
                10,
                "none-v1",
                "none-v1",
                0);
        assertThatThrownBy(() -> builder().build(exhausted))
                .isInstanceOf(ContextBuildException.class)
                .extracting(error -> ((ContextBuildException) error).failure())
                .isEqualTo(ContextBuildFailure.RUN_INPUT_BUDGET_EXHAUSTED);
    }

    private static DefaultAgentContextBuilder builder() {
        return new DefaultAgentContextBuilder(new FixedEstimator(), new ContextSelectionPolicy(), List.of());
    }

    private static ContextBuildRequest request(List<ContextItem> items, int output, int safety) {
        return new ContextBuildRequest(
                new AgentRunId("run"),
                new AgentSessionId("session"),
                new TenantRef("tenant"),
                new PrincipalRef("principal", "user"),
                1,
                snapshot(),
                new AgentRunBudget(1_000, 1_000, 0, 10, 10, 1, "USD", 100),
                AgentRunUsage.ZERO,
                List.of(prompt()),
                items,
                List.of(),
                output,
                safety,
                "none-v1",
                "none-v1",
                0);
    }

    private static PromptComponent prompt() {
        return new PromptComponent(
                new PromptComponentId("safety"),
                "1",
                PromptLayer.SYSTEM_SAFETY,
                PromptRole.SYSTEM,
                "follow safety policy",
                false,
                Set.of("internal"));
    }

    private static ContextItem item(
            String id,
            String hash,
            int tokens,
            ContextPriority priority,
            ContextRetention retention,
            boolean providerAllowed) {
        return new ContextItem(
                new ContextItemId(id),
                ContextItemType.RUNTIME_STATE,
                new TextContextContent(ContextRole.SYSTEM, id + " content"),
                tokens,
                priority,
                retention,
                new ContextSecurity(Set.of("internal"), providerAllowed),
                new ContextProvenance("runtime", id, "1", hash),
                Map.of());
    }

    private static ResolvedModelSnapshot snapshot() {
        return ResolvedModelSnapshot.create(
                new ModelProviderId("provider"),
                "provider-v1",
                new ModelDefinitionId("model"),
                "model-v1",
                "model",
                "adapter",
                "adapter-v1",
                URI.create("https://provider.example.com"),
                new CredentialRef("env://MODEL_KEY"),
                Set.of(ModelCapability.TEXT_CHAT),
                100,
                40,
                Map.of("transport", "https"),
                Map.of("thinking", "disabled"));
    }

    private static final class FixedEstimator implements TokenEstimator {
        @Override
        public int estimate(PromptComponent prompt) {
            return 10;
        }

        @Override
        public int estimate(ContextItem item) {
            return item.estimatedTokens();
        }

        @Override
        public int estimate(ModelToolSpecification tool) {
            return 10;
        }

        @Override
        public String version() {
            return "fixed-v1";
        }
    }
}

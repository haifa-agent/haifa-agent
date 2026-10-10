package io.haifa.agent.runtime.core.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.api.TruncatedOutputPolicy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class TruncatedOutputPolicyBootstrapTest {
    private static final AgentRunBudget BUDGET = new AgentRunBudget(1_000, 1_000, 1_000, 5, 5, 0, "USD", 100);
    private static final AgentRunLimits LIMITS = new AgentRunLimits(5, 0, 1, 60_000, 10_000);
    private static final RuntimeCallerContext CALLER =
            new RuntimeCallerContext(new TenantRef("tenant"), new PrincipalRef("principal", "user"));

    @Test
    void resolvedProfileDefaultsToFailClosed() {
        ResolvedModelSnapshot model = DefaultResolvedModelSnapshots.deepSeekV4Pro();
        ResolvedProfile defaultProfile =
                new ResolvedProfile("profile", "1.0", AgentRunType.CHAT, BUDGET, LIMITS, model);
        assertThat(defaultProfile.truncatedOutputPolicy()).isEqualTo(TruncatedOutputPolicy.FAIL_CLOSED);

        ResolvedProfile fullDefaultProfile = new ResolvedProfile(
                "profile", "1.0", AgentRunType.CHAT, BUDGET, LIMITS, model, Map.of(), Map.of(), Optional.empty());
        assertThat(fullDefaultProfile.truncatedOutputPolicy()).isEqualTo(TruncatedOutputPolicy.FAIL_CLOSED);
    }

    @Test
    void resolvedProfileRejectsNullPolicy() {
        ResolvedModelSnapshot model = DefaultResolvedModelSnapshots.deepSeekV4Pro();
        assertThatThrownBy(() -> new ResolvedProfile(
                        "profile",
                        "1.0",
                        AgentRunType.CHAT,
                        BUDGET,
                        LIMITS,
                        model,
                        Map.of(),
                        Map.of(),
                        Optional.empty(),
                        null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("truncatedOutputPolicy");
    }

    @Test
    void contentAddressedSnapshotPreservesLegacyHashForDefaultAndDivergesForOptIn() {
        ResolvedModelSnapshot model = DefaultResolvedModelSnapshots.deepSeekV4Pro();
        ResolvedDefinition definition = new ResolvedDefinition(
                new AgentDefinitionId("agent"),
                new AgentDefinitionVersion(1, 0, 0),
                Set.of(),
                Set.of(),
                Set.of(),
                "Execute the task.",
                List.of());
        AgentRunRequest request = new AgentRunRequest(
                "req-1",
                new AgentDefinitionId("agent"),
                Optional.empty(),
                "profile",
                new AgentSessionId("session-1"),
                Optional.empty(),
                "Execute.",
                List.of(),
                RuntimeOverrides.NONE);

        ContentAddressedSnapshotFactory factory = new ContentAddressedSnapshotFactory();

        ResolvedProfile defaultProfile = new ResolvedProfile(
                "profile",
                "1.0",
                AgentRunType.CHAT,
                BUDGET,
                LIMITS,
                model,
                Map.of(),
                Map.of(),
                Optional.empty(),
                TruncatedOutputPolicy.FAIL_CLOSED);

        ResolvedProfile optInProfile = new ResolvedProfile(
                "profile",
                "1.0",
                AgentRunType.CHAT,
                BUDGET,
                LIMITS,
                model,
                Map.of(),
                Map.of(),
                Optional.empty(),
                TruncatedOutputPolicy.ACCEPT_NONEMPTY_PLAIN_TEXT);

        var defaultSnapshot = factory.create(request, definition, defaultProfile, CALLER);
        var optInSnapshot = factory.create(request, definition, optInProfile, CALLER);

        assertThat(defaultSnapshot.truncatedOutputPolicy()).isEqualTo(TruncatedOutputPolicy.FAIL_CLOSED);
        // Calculated by the identical fixture against the published dev2948c63b
        // baseline JARs, before the policy field existed.
        assertThat(defaultSnapshot.reference().contentHash())
                .isEqualTo("sha256:f780eb3f0700e5f830e9096c6d0441208b06e48678278ec17fcdf21f9d675e52");
        assertThat(optInSnapshot.truncatedOutputPolicy()).isEqualTo(TruncatedOutputPolicy.ACCEPT_NONEMPTY_PLAIN_TEXT);

        // Opt-in canonical hash must be strictly distinct from default hash
        assertThat(defaultSnapshot.reference().contentHash())
                .isNotEqualTo(optInSnapshot.reference().contentHash());

        // withModel preserves policy
        assertThat(optInSnapshot.withModel(model).truncatedOutputPolicy())
                .isEqualTo(TruncatedOutputPolicy.ACCEPT_NONEMPTY_PLAIN_TEXT);
    }
}

package io.haifa.agent.tool.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolEffectClassTest {
    @Test
    void derivesRecoveryEffectFromFrozenIdempotencyRatherThanAccessCapabilities() {
        assertEquals(
                ToolEffectClass.PURE_READ,
                definition(ToolIdempotency.PURE, Set.of(ToolSideEffect.NETWORK_ACCESS))
                        .effectClass());
        assertEquals(
                ToolEffectClass.IDEMPOTENT,
                definition(ToolIdempotency.IDEMPOTENT_WITH_KEY, Set.of(ToolSideEffect.FILE_WRITE))
                        .effectClass());
        assertEquals(
                ToolEffectClass.SIDE_EFFECTING,
                definition(ToolIdempotency.NON_IDEMPOTENT, Set.of()).effectClass());
    }

    private static ToolDefinition definition(ToolIdempotency idempotency, Set<ToolSideEffect> sideEffects) {
        Map<String, Object> schema =
                Map.of("$schema", ToolSchema.DRAFT_2020_12, "type", "object", "additionalProperties", false);
        return new ToolDefinition(
                new ToolName("fixture.tool"),
                new SemanticVersion("1.0.0"),
                new ToolProviderId("fixture"),
                "Fixture",
                "Fixture tool",
                new ToolSchema("fixture.input", "1.0.0", schema),
                new ToolSchema("fixture.output", "1.0.0", schema),
                ToolExecutionMode.IN_PROCESS,
                false,
                Duration.ofSeconds(1),
                "fixture",
                idempotency,
                ToolRisk.LOW,
                sideEffects,
                ToolResourceRequirements.none(),
                List.of(),
                ToolApprovalRequirement.NEVER,
                "test",
                false,
                Set.of());
    }
}

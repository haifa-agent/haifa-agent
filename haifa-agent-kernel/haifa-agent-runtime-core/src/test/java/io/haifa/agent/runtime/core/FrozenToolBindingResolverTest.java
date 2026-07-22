package io.haifa.agent.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.runtime.core.tool.FrozenToolBindingResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FrozenToolBindingResolverTest {
    private final FrozenToolBindingResolver resolver = new FrozenToolBindingResolver();
    private final io.haifa.agent.tool.api.FrozenToolBinding binding =
            TestToolPlatform.binding("echo", "1.0.0", "echo.input", false);

    @Test
    void resolvesOnlyExactAliasVersionAndSchemaFromRunBindings() {
        assertThat(resolver.resolve(List.of(binding), request("echo", "1.0.0", "echo.input", "1.0")))
                .isEqualTo(binding);
        assertThatThrownBy(() -> resolver.resolve(List.of(binding), request("missing", "1.0.0", "echo.input", "1.0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alias");
        assertThatThrownBy(() -> resolver.resolve(List.of(binding), request("echo", "1.0", "echo.input", "1.0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
        assertThatThrownBy(() -> resolver.resolve(List.of(binding), request("echo", "1.0.0", "other.input", "1.0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema");
    }

    private static ToolRequest request(String alias, String version, String schemaId, String schemaVersion) {
        return new ToolRequest(
                new ToolCallId("call"),
                new ProviderToolCallCorrelationId("provider-call"),
                new RuntimeIdempotencyKey("idempotency"),
                alias,
                version,
                new ToolArguments(schemaId, schemaVersion, Map.of()));
    }
}

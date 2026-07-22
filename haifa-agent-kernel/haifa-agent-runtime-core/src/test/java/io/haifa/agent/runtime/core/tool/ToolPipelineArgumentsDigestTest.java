package io.haifa.agent.runtime.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolPipelineArgumentsDigestTest {
    @Test
    void canonicalizesNestedMapOrderAndBindsSchemaAndValues() {
        Map<String, Object> leftNested = new LinkedHashMap<>();
        leftNested.put("b", 2);
        leftNested.put("a", List.of("x", true));
        Map<String, Object> rightNested = new LinkedHashMap<>();
        rightNested.put("a", List.of("x", true));
        rightNested.put("b", 2.0);

        String left = ToolPipeline.argumentsDigest(request("schema", "1.0", Map.of("nested", leftNested)));
        String reordered = ToolPipeline.argumentsDigest(request("schema", "1.0", Map.of("nested", rightNested)));
        String changed = ToolPipeline.argumentsDigest(request("schema", "1.0", Map.of("nested", Map.of("b", 3))));
        String changedSchema = ToolPipeline.argumentsDigest(request("other", "1.0", Map.of("nested", leftNested)));

        assertThat(reordered).isEqualTo(left);
        assertThat(changed).isNotEqualTo(left);
        assertThat(changedSchema).isNotEqualTo(left);
    }

    @Test
    void redactsProviderSummaryAndNestedStructuredDataBeforePersistence() {
        var result = new ToolResult(
                true,
                "summary secret",
                Map.of("value", "secret", "nested", List.of(Map.of("token", "secret"))),
                List.of(),
                List.of(),
                false);

        ToolResult redacted = ToolPipeline.redactResult(result, value -> value.replace("secret", "[REDACTED]"));

        assertThat(redacted.toString()).doesNotContain("secret").contains("[REDACTED]");
    }

    private static ToolRequest request(String schemaId, String schemaVersion, Map<String, Object> values) {
        return new ToolRequest(
                new ToolCallId("call"),
                new ProviderToolCallCorrelationId("provider-call"),
                new RuntimeIdempotencyKey("runtime-call"),
                "tool",
                "1.0.0",
                new ToolArguments(schemaId, schemaVersion, values));
    }
}

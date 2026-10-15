package io.haifa.agent.runtime.api;

import java.util.Map;

/** Bounded, redacted JSON-shaped data copied from one authoritative Tool Call. */
public record ToolDataView(Map<String, Object> values, boolean truncated) {
    public ToolDataView {
        values = RuntimeValues.immutableMap(values, "values");
    }

    public static ToolDataView empty() {
        return new ToolDataView(Map.of(), false);
    }
}

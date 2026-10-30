package io.haifa.agent.runtime.api;

import java.util.Map;
import java.util.Set;

/**
 * Bounded, redacted JSON-shaped data copied from one authoritative Tool Call.
 * {@code completeFields} certifies top-level fields copied without truncation or redaction.
 * Missing metadata is conservative and certifies nothing.
 */
public record ToolDataView(Map<String, Object> values, boolean truncated, Set<String> completeFields) {
    public ToolDataView {
        values = RuntimeValues.immutableMap(values, "values");
        completeFields = completeFields == null ? Set.of() : Set.copyOf(completeFields);
        if (!values.keySet().containsAll(completeFields)) {
            throw new IllegalArgumentException("completeFields must name projected fields");
        }
    }

    /** Legacy projections cannot certify any individual field when their truncation cause is unknown. */
    public ToolDataView(Map<String, Object> values, boolean truncated) {
        this(values, truncated, truncated ? Set.of() : values.keySet());
    }

    public static ToolDataView empty() {
        return new ToolDataView(Map.of(), false);
    }
}

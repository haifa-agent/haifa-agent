package io.haifa.agent.model.api;

import java.util.Map;

/** Provider-neutral function tool call. */
public record ModelToolCall(String id, String name, Map<String, Object> arguments) {
    public ModelToolCall {
        id = ModelValues.text(id, "id");
        name = ModelValues.text(name, "name");
        arguments = ModelValues.map(arguments, "arguments");
    }
}

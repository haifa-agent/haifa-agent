package io.haifa.agent.tool.api;

import java.util.Map;

public record ToolSchema(String id, String version, Map<String, Object> document) {
    public static final String DRAFT_2020_12 = "https://json-schema.org/draft/2020-12/schema";

    public ToolSchema {
        id = ToolValues.text(id, "id");
        version = ToolValues.text(version, "version");
        document = ToolValues.jsonObject(document, "document");
        if (document.isEmpty()) {
            throw new IllegalArgumentException("document must not be empty");
        }
    }
}

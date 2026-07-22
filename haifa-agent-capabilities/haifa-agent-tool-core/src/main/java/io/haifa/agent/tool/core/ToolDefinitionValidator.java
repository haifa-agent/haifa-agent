package io.haifa.agent.tool.core;

import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolSchema;
import java.util.Map;

public final class ToolDefinitionValidator {
    private static final int MAX_SCHEMA_DEPTH = 64;
    private static final int MAX_SCHEMA_NODES = 4096;
    private static final int MAX_SCHEMA_TEXT_CHARS = 1_048_576;

    public void validate(ToolDefinition definition) {
        validateSchema(definition.inputSchema(), "inputSchema");
        validateSchema(definition.outputSchema(), "outputSchema");
    }

    private static void validateSchema(ToolSchema schema, String name) {
        Object dialect = schema.document().get("$schema");
        if (!ToolSchema.DRAFT_2020_12.equals(dialect)) {
            throw new IllegalArgumentException(name + " must declare JSON Schema Draft 2020-12");
        }
        inspect(schema.document(), schema.document(), name, 0, new SchemaBudget());
    }

    private static void inspect(Object value, Map<String, Object> root, String name, int depth, SchemaBudget budget) {
        if (depth > MAX_SCHEMA_DEPTH) throw new IllegalArgumentException(name + " exceeds maximum schema depth");
        budget.node();
        if (value instanceof Map<?, ?> map) {
            Object reference = map.get("$ref");
            if (reference != null) {
                if (!(reference instanceof String text)) {
                    throw new IllegalArgumentException(name + " $ref must be a string");
                }
                if (!text.startsWith("#")) {
                    throw new IllegalArgumentException(name + " does not support remote or file references");
                }
                Object target = resolveLocalReference(root, text);
                if (!(target instanceof Map<?, ?>) && !(target instanceof Boolean)) {
                    throw new IllegalArgumentException(name + " contains an unresolved local reference: " + text);
                }
            }
            if (map.containsKey("pattern")) {
                throw new IllegalArgumentException(name + " pattern is outside the bounded schema subset");
            }
            validateKeywordTypes(map, name);
            map.forEach((key, element) -> {
                budget.text(String.valueOf(key));
                inspect(element, root, name, depth + 1, budget);
            });
        } else if (value instanceof Iterable<?> iterable) {
            iterable.forEach(element -> inspect(element, root, name, depth + 1, budget));
        } else if (value instanceof String text) {
            budget.text(text);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object resolveLocalReference(Map<String, Object> root, String reference) {
        if ("#".equals(reference)) return root;
        if (!reference.startsWith("#/")) return null;
        Object current = root;
        for (String token : reference.substring(2).split("/")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = ((Map<String, Object>) map).get(token.replace("~1", "/").replace("~0", "~"));
            if (current == null) return null;
        }
        return current;
    }

    private static void validateKeywordTypes(Map<?, ?> schema, String name) {
        Object type = schema.get("type");
        if (type != null && !(type instanceof String) && !(type instanceof Iterable<?>)) {
            throw new IllegalArgumentException(name + " type must be a string or array");
        }
        requireMap(schema, "properties", name);
        requireMap(schema, "$defs", name);
        requireList(schema, "required", name);
        requireList(schema, "enum", name);
        requireList(schema, "allOf", name);
        requireList(schema, "anyOf", name);
        requireList(schema, "oneOf", name);
        requireSchema(schema, "items", name);
        requireSchema(schema, "additionalProperties", name);
        for (String keyword : java.util.List.of(
                "minimum",
                "maximum",
                "exclusiveMinimum",
                "exclusiveMaximum",
                "minLength",
                "maxLength",
                "minItems",
                "maxItems",
                "minProperties",
                "maxProperties")) {
            Object value = schema.get(keyword);
            if (value != null && !(value instanceof Number)) {
                throw new IllegalArgumentException(name + " " + keyword + " must be numeric");
            }
        }
    }

    private static void requireMap(Map<?, ?> schema, String keyword, String name) {
        Object value = schema.get(keyword);
        if (value != null && !(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(name + " " + keyword + " must be an object");
        }
    }

    private static void requireList(Map<?, ?> schema, String keyword, String name) {
        Object value = schema.get(keyword);
        if (value != null && !(value instanceof Iterable<?>)) {
            throw new IllegalArgumentException(name + " " + keyword + " must be an array");
        }
    }

    private static void requireSchema(Map<?, ?> schema, String keyword, String name) {
        Object value = schema.get(keyword);
        if (value != null && !(value instanceof Map<?, ?>) && !(value instanceof Boolean)) {
            throw new IllegalArgumentException(name + " " + keyword + " must be a schema or boolean");
        }
    }

    private static final class SchemaBudget {
        private int nodes;
        private int textChars;

        private void node() {
            if (++nodes > MAX_SCHEMA_NODES) throw new IllegalArgumentException("schema exceeds maximum node count");
        }

        private void text(String value) {
            textChars = Math.addExact(textChars, value.length());
            if (textChars > MAX_SCHEMA_TEXT_CHARS) {
                throw new IllegalArgumentException("schema exceeds maximum text size");
            }
        }
    }
}

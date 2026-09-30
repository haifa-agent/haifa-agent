package io.haifa.agent.tool.core;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolDefinitionValidatorTest {
    private final ToolDefinitionValidator validator = new ToolDefinitionValidator();

    @Test
    void acceptsPropertyNamedPattern() {
        // Frozen DeerFlow glob/grep expose a required `pattern` argument.
        var input = schema(Map.of(
                "type", "object",
                "properties", Map.of(
                        "pattern", Map.of("type", "string", "minLength", 1),
                        "path", Map.of("type", "string")),
                "required", List.of("pattern", "path"),
                "additionalProperties", false));

        assertThatCode(() -> validator.validate(ToolFixtures.definition(ToolRisk.LOW, input))).doesNotThrowAnyException();
    }

    @Test
    void stillRejectsThePatternKeywordInsideAPropertySchema() {
        var input = schema(Map.of(
                "type", "object",
                "properties", Map.of("path", Map.of("type", "string", "pattern", "^/mnt/"))));

        assertThatThrownBy(() -> validator.validate(ToolFixtures.definition(ToolRisk.LOW, input)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pattern is outside the bounded schema subset");
    }

    @Test
    void stillRejectsThePatternKeywordAtTheRoot() {
        var input = schema(Map.of("type", "string", "pattern", "^x$"));

        assertThatThrownBy(() -> validator.validate(ToolFixtures.definition(ToolRisk.LOW, input)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pattern is outside the bounded schema subset");
    }

    @Test
    void acceptsDefinitionNamedPatternAndPropertyNamedRef() {
        var input = schema(Map.of(
                "type", "object",
                "$defs", Map.of("pattern", Map.of("type", "string")),
                "properties", Map.of(
                        "$ref", Map.of("type", "string"),
                        "glob", Map.of("$ref", "#/$defs/pattern"))));

        assertThatCode(() -> validator.validate(ToolFixtures.definition(ToolRisk.LOW, input))).doesNotThrowAnyException();
    }

    @Test
    void stillRejectsUnresolvedReferencesBelowNameMaps() {
        var input = schema(Map.of(
                "type", "object",
                "properties", Map.of("glob", Map.of("$ref", "#/$defs/missing"))));

        assertThatThrownBy(() -> validator.validate(ToolFixtures.definition(ToolRisk.LOW, input)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unresolved local reference");
    }

    private static ToolSchema schema(Map<String, Object> body) {
        var document = new java.util.LinkedHashMap<String, Object>(body);
        document.put("$schema", ToolSchema.DRAFT_2020_12);
        return ToolFixtures.schema("validator.input", document);
    }
}

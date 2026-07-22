package io.haifa.agent.tool.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.tool.api.ToolSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonSchema202012ValidatorTest {
    @Test
    void validatesInputAndOutputUsingBoundedStructuredErrors() {
        var validator = new JsonSchema202012Validator(2);

        var invalidInput =
                validator.validate(ToolFixtures.definition().inputSchema(), Map.of("path", "", "unexpected", true));
        var validOutput = validator.validate(ToolFixtures.definition().outputSchema(), Map.of("content", "ok"));

        assertThat(invalidInput.valid()).isFalse();
        assertThat(invalidInput.errors()).hasSize(2);
        assertThat(invalidInput.errors())
                .extracting(error -> error.keyword())
                .contains("minLength", "additionalProperties");
        assertThat(validOutput.valid()).isTrue();
    }

    @Test
    void rejectsRemoteAndFileReferencesAtCatalogFreeze() {
        ToolSchema remote = ToolFixtures.schema(
                "remote", Map.of("$schema", ToolSchema.DRAFT_2020_12, "$ref", "https://example.invalid/schema.json"));
        var definition = ToolFixtures.definition();
        var invalid = new io.haifa.agent.tool.api.ToolDefinition(
                definition.name(),
                definition.version(),
                definition.providerId(),
                definition.title(),
                definition.description(),
                remote,
                definition.outputSchema(),
                definition.executionMode(),
                definition.cancellationSupported(),
                definition.timeout(),
                definition.concurrencyPolicy(),
                definition.idempotency(),
                definition.risk(),
                definition.sideEffects(),
                definition.resources(),
                definition.credentialRequirements(),
                definition.approvalRequirement(),
                definition.provenance(),
                definition.deprecated(),
                definition.tags());

        assertThatThrownBy(() -> new ToolCatalogBuilder()
                        .register(
                                new io.haifa.agent.tool.api.ToolAlias("remote"),
                                invalid,
                                "binding",
                                ToolFixtures.provider()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remote or file");
    }

    @Test
    void resolvesLocalReferencesAtCatalogFreeze() {
        ToolSchema valid = ToolFixtures.schema(
                "local",
                Map.of(
                        "$schema",
                        ToolSchema.DRAFT_2020_12,
                        "$defs",
                        Map.of("path", Map.of("type", "string")),
                        "properties",
                        Map.of("path", Map.of("$ref", "#/$defs/path"))));
        ToolSchema unresolved = ToolFixtures.schema(
                "unresolved",
                Map.of(
                        "$schema",
                        ToolSchema.DRAFT_2020_12,
                        "properties",
                        Map.of("path", Map.of("$ref", "#/$defs/missing"))));

        assertThat(new ToolCatalogBuilder()
                        .register(
                                new io.haifa.agent.tool.api.ToolAlias("local"),
                                withInputSchema(valid),
                                "binding",
                                ToolFixtures.provider())
                        .freeze()
                        .snapshot()
                        .bindings())
                .hasSize(1);
        assertThatThrownBy(() -> new ToolCatalogBuilder()
                        .register(
                                new io.haifa.agent.tool.api.ToolAlias("unresolved"),
                                withInputSchema(unresolved),
                                "binding",
                                ToolFixtures.provider()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unresolved local reference");
    }

    @Test
    void enforcesValidationBudgetAndRejectsUnboundedPatternSchemas() {
        var bounded = new JsonSchema202012Validator(2, 2, Duration.ofSeconds(1));
        var result =
                bounded.validate(ToolFixtures.definition().inputSchema(), Map.of("path", "README.md", "extra", true));
        assertThat(result.errors()).singleElement().satisfies(error -> assertThat(error.keyword())
                .isEqualTo("limit"));

        ToolSchema pattern = ToolFixtures.schema(
                "pattern",
                Map.of(
                        "$schema",
                        ToolSchema.DRAFT_2020_12,
                        "type",
                        "object",
                        "properties",
                        Map.of("value", Map.of("type", "string", "pattern", "^(a+)+$")),
                        "required",
                        List.of("value")));
        var definition = ToolFixtures.definition();
        var invalid = new io.haifa.agent.tool.api.ToolDefinition(
                definition.name(),
                definition.version(),
                definition.providerId(),
                definition.title(),
                definition.description(),
                pattern,
                definition.outputSchema(),
                definition.executionMode(),
                definition.cancellationSupported(),
                definition.timeout(),
                definition.concurrencyPolicy(),
                definition.idempotency(),
                definition.risk(),
                definition.sideEffects(),
                definition.resources(),
                definition.credentialRequirements(),
                definition.approvalRequirement(),
                definition.provenance(),
                definition.deprecated(),
                definition.tags());

        assertThatThrownBy(() -> new ToolCatalogBuilder()
                        .register(
                                new io.haifa.agent.tool.api.ToolAlias("pattern"),
                                invalid,
                                "binding",
                                ToolFixtures.provider()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pattern");
    }

    private static io.haifa.agent.tool.api.ToolDefinition withInputSchema(ToolSchema inputSchema) {
        var definition = ToolFixtures.definition();
        return new io.haifa.agent.tool.api.ToolDefinition(
                definition.name(),
                definition.version(),
                definition.providerId(),
                definition.title(),
                definition.description(),
                inputSchema,
                definition.outputSchema(),
                definition.executionMode(),
                definition.cancellationSupported(),
                definition.timeout(),
                definition.concurrencyPolicy(),
                definition.idempotency(),
                definition.risk(),
                definition.sideEffects(),
                definition.resources(),
                definition.credentialRequirements(),
                definition.approvalRequirement(),
                definition.provenance(),
                definition.deprecated(),
                definition.tags());
    }
}

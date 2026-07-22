package io.haifa.agent.tool.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolCoordinate;
import io.haifa.agent.tool.api.ToolDefinitionHash;
import io.haifa.agent.tool.api.ToolInvocationException;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolCatalogBuilderTest {
    @Test
    void freezesDeterministicContentAddressedBindingsAndModelDisclosure() {
        var first = new ToolCatalogBuilder()
                .register(new ToolAlias("read"), ToolFixtures.definition(), "project-default", ToolFixtures.provider())
                .freeze();
        var second = new ToolCatalogBuilder()
                .register(new ToolAlias("read"), ToolFixtures.definition(), "project-default", ToolFixtures.provider())
                .freeze();

        assertThat(first.snapshot().digest()).isEqualTo(second.snapshot().digest());
        assertThat(first.snapshot().bindings().getFirst().coordinate().definitionHash())
                .isEqualTo(second.snapshot().bindings().getFirst().coordinate().definitionHash());
        ModelToolSpecification specification = new ModelToolSpecificationMapper()
                .map(first.snapshot().bindings().getFirst());
        assertThat(specification.name()).isEqualTo("read");
        assertThat(specification.inputJsonSchema())
                .isEqualTo(ToolFixtures.definition().inputSchema().document());
        assertThat(specification.strict()).isFalse();
        assertThat(specification.toString()).doesNotContain("project-default", "definitionHash");
    }

    @Test
    void rejectsDuplicateAliasesAndFreezesDeepJsonValues() {
        var builder = new ToolCatalogBuilder()
                .register(new ToolAlias("read"), ToolFixtures.definition(), "binding", ToolFixtures.provider());
        assertThatThrownBy(() -> builder.register(
                        new ToolAlias("read"), ToolFixtures.definition(), "binding", ToolFixtures.provider()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");

        var mutable = new LinkedHashMap<String, Object>();
        mutable.put("type", "object");
        mutable.put("$schema", io.haifa.agent.tool.api.ToolSchema.DRAFT_2020_12);
        var schema = ToolFixtures.schema("immutable", mutable);
        mutable.put("type", "array");
        assertThat(schema.document().get("type")).isEqualTo("object");
        assertThatThrownBy(() -> schema.document().put("x", true)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void canonicalHashHasGoldenValueAndIgnoresMapInsertionOrder() {
        var canonicalizer = new ToolDefinitionCanonicalizer();
        assertThat(canonicalizer.hash(ToolFixtures.definition()).value())
                .isEqualTo("8a929b725a834ddd01a63ffafae9c798447637a3978e6bbc66ec8d3dac3001ce");

        var ordered = new LinkedHashMap<String, Object>();
        ordered.put("$schema", ToolSchema.DRAFT_2020_12);
        ordered.put("type", "object");
        ordered.put("properties", Map.of("path", Map.of("minLength", 1, "type", "string")));
        ordered.put("required", List.of("path"));
        ordered.put("additionalProperties", false);
        var reversed = new LinkedHashMap<String, Object>();
        reversed.put("additionalProperties", false);
        reversed.put("required", List.of("path"));
        reversed.put("properties", Map.of("path", Map.of("type", "string", "minLength", 1)));
        reversed.put("type", "object");
        reversed.put("$schema", ToolSchema.DRAFT_2020_12);

        assertThat(canonicalizer.hash(
                        ToolFixtures.definition(ToolRisk.LOW, ToolFixtures.schema("file.read.input", ordered))))
                .isEqualTo(canonicalizer.hash(
                        ToolFixtures.definition(ToolRisk.LOW, ToolFixtures.schema("file.read.input", reversed))));
        assertThat(canonicalizer.hash(
                        ToolFixtures.definition(ToolRisk.CRITICAL, ToolFixtures.schema("file.read.input", ordered))))
                .isNotEqualTo(canonicalizer.hash(
                        ToolFixtures.definition(ToolRisk.LOW, ToolFixtures.schema("file.read.input", ordered))));
    }

    @Test
    void rejectsDuplicateCoordinatesEvenWhenAliasesDiffer() {
        var builder = new ToolCatalogBuilder()
                .register(new ToolAlias("read"), ToolFixtures.definition(), "binding", ToolFixtures.provider())
                .register(new ToolAlias("read_again"), ToolFixtures.definition(), "binding", ToolFixtures.provider());

        assertThatThrownBy(builder::freeze)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("coordinate");
    }

    @Test
    void frozenCatalogRejectsMutationAndInvokerFailsClosedOnDefinitionDrift() {
        var builder = new ToolCatalogBuilder()
                .register(new ToolAlias("read"), ToolFixtures.definition(), "binding", ToolFixtures.provider());
        var catalog = builder.freeze();
        assertThatThrownBy(() -> builder.register(
                        new ToolAlias("other"), ToolFixtures.definition(), "binding", ToolFixtures.provider()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("frozen");

        FrozenToolBinding original = catalog.snapshot().bindings().getFirst();
        var driftedCoordinate = new ToolCoordinate(
                original.coordinate().name(),
                original.coordinate().version(),
                original.coordinate().providerId(),
                new ToolDefinitionHash("f".repeat(64)));
        var drifted = new FrozenToolBinding(
                original.alias(),
                driftedCoordinate,
                original.definition(),
                original.providerBindingReference(),
                original.catalogDigest());

        assertThatThrownBy(() -> new DefaultToolInvoker(catalog).validateBinding(drifted))
                .isInstanceOf(ToolInvocationException.class)
                .hasMessageContaining("coordinate");
    }
}

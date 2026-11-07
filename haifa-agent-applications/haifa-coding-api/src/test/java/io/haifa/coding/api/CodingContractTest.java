package io.haifa.coding.api;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.networknt.schema.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CodingContractTest {
    private final ObjectMapper json = new ObjectMapper()
            .addMixIn(CodingProtocol.Entry.class, OptionalToolDetails.class)
            .addMixIn(CodingProtocol.Snapshot.class, OptionalActivity.class);

    private interface OptionalActivity {
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        CodingProtocol.ActivityHead activity();
    }

    private interface OptionalToolDetails {
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        CodingProtocol.ToolDetails toolDetails();
    }

    private final Path contract = Path.of("contract");

    private boolean valid(JsonSchema schema, JsonNode node) throws Exception {
        if (!schema.validate(node).isEmpty()) return false;
        if (json.writeValueAsBytes(node).length > (node.path("kind").asText().equals("snapshot") ? 262144 : 65536))
            return false;
        try {
            if (node.path("kind").asText().equals("snapshot"))
                CodingProtocolChecks.snapshot(json.treeToValue(node, CodingProtocol.Snapshot.class));
            if (node.path("kind").asText().equals("update"))
                CodingProtocolChecks.snapshot(
                        json.treeToValue(node.path("replacement"), CodingProtocol.Snapshot.class));
        } catch (IllegalArgumentException failure) {
            return false;
        }
        if (node.path("kind").asText().equals("update")) {
            return node.path("revision").asLong() == node.path("baseRevision").asLong() + 1
                    && node.path("revision").equals(node.path("replacement").path("revision"))
                    && node.path("viewId")
                            .equals(node.path("replacement").path("scope").path("viewId"));
        }
        return true;
    }

    @Test
    void trustedDeviceLoginInstructionRetainsLocalValuesAndRedactsDiagnostics() {
        var uri = java.net.URI.create("https://fixture.invalid/device");
        var instruction = new io.haifa.agent.application.project.product.coding.client.CodingDeviceLoginView(
                uri, "FIXTURE-CODE", 1);
        assertThat(instruction.verificationUri()).isEqualTo(uri);
        assertThat(instruction.userCode()).isEqualTo("FIXTURE-CODE");
        assertThat(instruction.toString()).doesNotContain("fixture.invalid", "FIXTURE-CODE");
    }

    @Test
    void sharesFixturesAndAtomicRevisionRulesWithTypeScript() throws Exception {
        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(json.readTree(
                        contract.resolve("coding-presentation.schema.json").toFile()));
        int executed = 0;
        for (var fixture : json.readTree(contract.resolve("fixtures/index.json").toFile())) {
            var node = json.readTree(contract.resolve("fixtures")
                    .resolve(fixture.path("file").asText())
                    .toFile());
            assertThat(valid(schema, node))
                    .as(fixture.path("file").asText())
                    .isEqualTo(fixture.path("valid").asBoolean());
            executed++;
            if (fixture.path("valid").asBoolean()) {
                Class<?> type =
                        switch (node.path("kind").asText()) {
                            case "snapshot" -> CodingProtocol.Snapshot.class;
                            case "update" -> CodingProtocol.Update.class;
                            default -> CodingProtocol.Resync.class;
                        };
                JsonNode roundTrip = json.readTree(json.writeValueAsBytes(json.treeToValue(node, type)));
                assertThat(roundTrip).isEqualTo(node);
            }
        }
        assertThat(executed).isEqualTo(12);
        var initial =
                json.readValue(contract.resolve("fixtures/snapshot.json").toFile(), CodingProtocol.Snapshot.class);
        var update = json.readValue(contract.resolve("fixtures/update.json").toFile(), CodingProtocol.Update.class);
        assertThat(CodingPresentationReducer.apply(initial, update)).isEqualTo(update.replacement());
        assertThat(CodingPresentationReducer.apply(update.replacement(), update))
                .isSameAs(update.replacement());
    }

    @Test
    void commandsRequireExactTargetsWithoutInventingOperationOutcomes() throws Exception {
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(
                contract.resolve("coding-presentation.schema.json").toFile());
        root.remove("oneOf");
        root.put("$ref", "#/$defs/Command");
        var schema =
                JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root);
        int executed = 0;
        for (var fixture :
                json.readTree(contract.resolve("fixtures/commands.json").toFile())) {
            var command = fixture.path("command");
            boolean accepted = schema.validate(command).isEmpty();
            if (accepted)
                try {
                    CodingProtocolChecks.command(json.treeToValue(command, CodingProtocol.Command.class));
                } catch (IllegalArgumentException invalid) {
                    accepted = false;
                }
            assertThat(accepted).isEqualTo(fixture.path("valid").asBoolean());
            executed++;
        }
        assertThat(executed).isEqualTo(3);
    }

    @Test
    void previewAndSubscribeWrappersShareTheSamePublicSchema() throws Exception {
        int executed = 0;
        for (var fixture :
                json.readTree(contract.resolve("fixtures/extras.json").toFile())) {
            var root = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(
                    contract.resolve("coding-presentation.schema.json").toFile());
            root.remove("oneOf");
            root.put("$ref", "#/$defs/" + fixture.path("schema").asText());
            var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(root);
            assertThat(schema.validate(fixture.path("value")).isEmpty())
                    .isEqualTo(fixture.path("valid").asBoolean());
            executed++;
        }
        assertThat(executed).isEqualTo(3);
    }

    @Test
    void productCommandsQueriesAndPairingShareTheCrossLanguageFixtures() throws Exception {
        int executed = 0;
        for (var fixture :
                json.readTree(contract.resolve("fixtures/product.json").toFile())) {
            var root = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(
                    contract.resolve("coding-presentation.schema.json").toFile());
            root.remove("oneOf");
            root.put("$ref", "#/$defs/" + fixture.path("schema").asText());
            var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(root);
            boolean valid = schema.validate(fixture.path("value")).isEmpty();
            if (valid && fixture.path("schema").asText().equals("CommandRequest"))
                try {
                    CodingProtocolChecks.command(
                            json.treeToValue(fixture.path("value").path("command"), CodingProtocol.Command.class));
                } catch (IllegalArgumentException rejected) {
                    valid = false;
                }
            assertThat(valid).isEqualTo(fixture.path("valid").asBoolean());
            executed++;
        }
        assertThat(executed).isEqualTo(10);
        var grant = new CodingProtocol.BrowserGrant("synthetic-proof-not-real", "client", "host", 1);
        assertThat(grant.toString()).isEqualTo("BrowserGrant[redacted]");
        assertThat(new CodingProtocol.PairingRequest("synthetic-proof-not-real", "host").toString())
                .isEqualTo("PairingRequest[redacted]");
    }

    @Test
    void publicContractHasNoEngineRuntimeOrDatabaseSignatures() {
        for (Class<?> type : CodingProtocol.class.getDeclaredClasses()) {
            if (!type.isRecord()) continue;
            for (var field : type.getRecordComponents()) {
                String signature = field.getGenericType().getTypeName();
                assertThat(signature).doesNotContain("io.haifa.agent", "sqlite", "jackson", "spring", "java.io.File");
            }
        }
    }
}

package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.model.api.ApiStyleId;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.api.TruncatedOutputPolicy;
import io.haifa.agent.runtime.core.bootstrap.RuntimeConfigurationSnapshot;
import io.haifa.agent.skill.api.SkillContentDigest;
import io.haifa.agent.skill.api.SkillTrustSnapshot;
import io.haifa.agent.store.sqlite.codec.EncodedPayload;
import io.haifa.agent.store.sqlite.codec.VersionedPayloadCodecRegistry;
import io.haifa.agent.store.sqlite.payload.SqliteRuntimePayloadTypes;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqliteConfigurationTruncatedPolicyTest {

    @Test
    void preservesOptInPolicyAfterClosingAndReopeningRealSqlite(@TempDir Path directory) throws Exception {
        RuntimeConfigurationSnapshot snapshot = createSnapshot(TruncatedOutputPolicy.ACCEPT_NONEMPTY_PLAIN_TEXT);
        var configuration = SqliteTestSupport.configuration(directory);
        var protector = new io.haifa.agent.runtime.core.model.continuation.AesGcmModelContinuationProtector(
                new javax.crypto.spec.SecretKeySpec(
                        "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), "AES"),
                new java.security.SecureRandom());
        try (SqliteStoreFoundation first = SqliteStoreFoundation.initialize(configuration, SqliteTestSupport.CLOCK)) {
            first.runtimeState(protector).saveConfiguration(snapshot);
        }
        try (SqliteStoreFoundation reopened =
                SqliteStoreFoundation.initialize(configuration, SqliteTestSupport.CLOCK)) {
            var restored = reopened.runtimeState(protector)
                    .configuration(snapshot.reference())
                    .orElseThrow();
            assertThat(restored).isEqualTo(snapshot);
            assertThat(restored.truncatedOutputPolicy()).isEqualTo(TruncatedOutputPolicy.ACCEPT_NONEMPTY_PLAIN_TEXT);
        }
    }

    @Test
    void roundTripsOptInTruncatedOutputPolicy() {
        VersionedPayloadCodecRegistry codecs = SqliteRuntimePayloadTypes.create(1024 * 1024);
        RuntimeConfigurationSnapshot optIn = createSnapshot(TruncatedOutputPolicy.ACCEPT_NONEMPTY_PLAIN_TEXT);

        EncodedPayload encoded = codecs.encode(SqliteRuntimePayloadTypes.CONFIGURATION, optIn);
        RuntimeConfigurationSnapshot decoded = codecs.decode(SqliteRuntimePayloadTypes.CONFIGURATION, encoded);

        assertThat(decoded.truncatedOutputPolicy()).isEqualTo(TruncatedOutputPolicy.ACCEPT_NONEMPTY_PLAIN_TEXT);
        assertThat(decoded.reference()).isEqualTo(optIn.reference());
    }

    @Test
    void legacySnapshotMissingTruncatedOutputPolicyFieldDefaultsToFailClosed() throws Exception {
        VersionedPayloadCodecRegistry codecs = SqliteRuntimePayloadTypes.create(1024 * 1024);
        RuntimeConfigurationSnapshot snapshot = createSnapshot(TruncatedOutputPolicy.FAIL_CLOSED);

        EncodedPayload encoded = codecs.encode(SqliteRuntimePayloadTypes.CONFIGURATION, snapshot);

        // Strip the "truncatedOutputPolicy" field from the JSON payload to simulate legacy DB record
        ObjectMapper mapper = new ObjectMapper();
        JsonNode tree = mapper.readTree(encoded.bytes());
        assertThat(tree.isObject()).isTrue();
        ((ObjectNode) tree).remove("truncatedOutputPolicy");

        byte[] legacyBytes = mapper.writeValueAsBytes(tree);
        String legacyHash = "sha256:"
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(legacyBytes));
        EncodedPayload legacyPayload = new EncodedPayload(
                SqliteRuntimePayloadTypes.CONFIGURATION.name(), encoded.schemaVersion(), legacyBytes, legacyHash);

        RuntimeConfigurationSnapshot decoded = codecs.decode(SqliteRuntimePayloadTypes.CONFIGURATION, legacyPayload);

        // Legacy configuration missing the JSON field safely defaults to FAIL_CLOSED
        assertThat(decoded.truncatedOutputPolicy()).isEqualTo(TruncatedOutputPolicy.FAIL_CLOSED);
    }

    private static RuntimeConfigurationSnapshot createSnapshot(TruncatedOutputPolicy policy) {
        ResolvedModelSnapshot model = ResolvedModelSnapshot.create(
                new ModelProviderId("deepseek"),
                "1",
                new ModelDefinitionId("deepseek-chat"),
                "1",
                "deepseek-chat",
                "openai-compatible",
                "1",
                new ApiStyleId("openai-chat-completions"),
                "standard",
                URI.create("https://api.deepseek.com"),
                new CredentialRef("env://DEEPSEEK_API_KEY"),
                true,
                Set.of(ModelCapability.TEXT_CHAT),
                8_192,
                2_048,
                Map.of(),
                Map.of());

        return new RuntimeConfigurationSnapshot(
                new RunConfigurationSnapshotRef("config-test", "sha256:0123456789abcdef"),
                new AgentDefinitionId("test-agent"),
                new AgentDefinitionVersion(1, 0, 0),
                "test-profile",
                "1.0.0",
                AgentRunType.CHAT,
                new AgentRunBudget(100, 100, 100, 10, 10, 2, "USD", 100),
                new AgentRunLimits(10, 2, 1, 60_000, 10_000),
                List.of(),
                List.of(),
                new SkillContentDigest("sha256:" + "0".repeat(64)),
                "policy-1",
                SkillTrustSnapshot.empty(),
                Set.of(),
                "Execute objective",
                RuntimeOverrides.NONE,
                List.of(),
                model,
                Map.of(),
                Optional.empty(),
                policy);
    }
}

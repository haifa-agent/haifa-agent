package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunSpec;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.core.session.AgentSession;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.SessionScope;
import io.haifa.agent.model.api.ApiStyleId;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.model.api.SensitiveModelReasoning;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.bootstrap.RuntimeConfigurationSnapshot;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationDraft;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationRef;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import io.haifa.agent.skill.api.SkillContentDigest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteSdkContributionsTest {
    private static final byte[] KEY_BYTES = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final SecretKeySpec CONTINUATION_KEY = new SecretKeySpec(KEY_BYTES, "AES");
    private static final Instant NOW = Instant.parse("2026-09-24T00:00:00Z");

    @Test
    void secretKeyFacadeRecoversProtectedContinuationAndRejectsWrongKey(@TempDir Path directory) {
        Path database = directory.resolve("agent.sqlite");
        PreparedContinuation prepared;

        SqliteSdkContributions first = SqliteSdkContributions.initializeWithKey(
                SqliteStoreConfiguration.defaults(database), Clock.systemUTC(), CONTINUATION_KEY);
        try {
            prepared = appendContinuation(first.persistence().runtimePersistence());
        } finally {
            first.persistence().close();
        }

        SqliteSdkContributions reopened = SqliteSdkContributions.initializeWithKey(
                SqliteStoreConfiguration.defaults(database), Clock.systemUTC(), CONTINUATION_KEY);
        try {
            assertThat(resolveContinuation(reopened, prepared)).isEqualTo(prepared.reasoning());
        } finally {
            reopened.persistence().close();
        }

        SqliteSdkContributions wrongKey = SqliteSdkContributions.initializeWithKey(
                SqliteStoreConfiguration.defaults(database), Clock.systemUTC(), new SecretKeySpec(new byte[32], "AES"));
        try {
            assertThatThrownBy(() -> resolveContinuation(wrongKey, prepared))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageNotContaining("sensitive-reasoning");
        } finally {
            wrongKey.persistence().close();
        }
    }

    @Test
    void copiesKeyMaterialBeforeClearingAnAdversarialEncoding(@TempDir Path directory) {
        Path database = directory.resolve("agent.sqlite");
        byte[] mutableEncoding = KEY_BYTES.clone();
        SqliteSdkContributions first = SqliteSdkContributions.initializeWithKey(
                SqliteStoreConfiguration.defaults(database), Clock.systemUTC(), new MutableSecretKey(mutableEncoding));
        PreparedContinuation prepared;
        try {
            prepared = appendContinuation(first.persistence().runtimePersistence());
        } finally {
            first.persistence().close();
        }
        assertThat(mutableEncoding).containsOnly((byte) 0);

        SqliteSdkContributions reopened = SqliteSdkContributions.initializeWithKey(
                SqliteStoreConfiguration.defaults(database), Clock.systemUTC(), CONTINUATION_KEY);
        try {
            assertThat(resolveContinuation(reopened, prepared)).isEqualTo(prepared.reasoning());
        } finally {
            reopened.persistence().close();
        }
    }

    @Test
    void rejectsInvalidAes256KeyBeforeOpeningDatabase(@TempDir Path directory) {
        Path database = directory.resolve("agent.sqlite");

        assertThatThrownBy(() -> SqliteSdkContributions.initializeWithKey(
                        SqliteStoreConfiguration.defaults(database),
                        Clock.systemUTC(),
                        new SecretKeySpec(new byte[16], "AES")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("256-bit AES key");
        assertThatThrownBy(() -> SqliteSdkContributions.initializeWithKey(
                        SqliteStoreConfiguration.defaults(database),
                        Clock.systemUTC(),
                        new SecretKeySpec(new byte[32], "HmacSHA256")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must use AES");
        assertThat(database).doesNotExist();
    }

    @Test
    void borrowedPersistenceDoesNotOwnTheFoundation(@TempDir Path directory) {
        SqliteSdkContributions sqlite = SqliteSdkContributions.initializeWithKey(
                SqliteStoreConfiguration.defaults(directory.resolve("agent.sqlite")),
                Clock.systemUTC(),
                CONTINUATION_KEY);
        var borrowed = sqlite.borrowedPersistence();

        borrowed.close();
        assertThat(borrowed.inTransaction(() -> "still-open")).isEqualTo("still-open");

        sqlite.persistence().close();
        assertThatThrownBy(() -> borrowed.inTransaction(() -> "closed"))
                .isInstanceOf(SqliteStoreException.class)
                .hasMessageContaining("closed");
    }

    private static SensitiveModelReasoning resolveContinuation(
            SqliteSdkContributions sqlite, PreparedContinuation prepared) {
        return sqlite.persistence()
                .runtimePersistence()
                .state()
                .resolveContinuation(prepared.messageId(), prepared.model(), Set.of("provider-call"));
    }

    private static PreparedContinuation appendContinuation(RuntimePersistencePorts persistence) {
        RuntimeConfigurationSnapshot configuration = configuration();
        persistence.state().saveConfiguration(configuration);
        AgentSessionId sessionId = new AgentSessionId("session");
        TenantRef tenant = new TenantRef("tenant");
        PrincipalRef principal = new PrincipalRef("principal", "user");
        persistence
                .sessions()
                .insert(AgentSession.open(sessionId, tenant, principal, null, SessionScope.USER, NOW, Map.of()));
        AgentRunId runId = new AgentRunId("run");
        persistence
                .runs()
                .insert(AgentRun.createRoot(
                        runId,
                        new AgentRunSpec(
                                sessionId,
                                null,
                                tenant,
                                principal,
                                configuration.definitionId(),
                                configuration.definitionVersion(),
                                configuration.profileId(),
                                configuration.profileVersion(),
                                configuration.runType(),
                                "objective",
                                configuration.budget(),
                                configuration.limits(),
                                configuration.reference()),
                        NOW));

        SensitiveModelReasoning reasoning = SensitiveModelReasoning.of("sensitive-reasoning");
        AgentMessageId messageId = new AgentMessageId("assistant-message");
        persistence
                .state()
                .appendSessionMessageWithContinuation(
                        new SessionMessageDraft(
                                messageId,
                                sessionId,
                                Optional.of(runId),
                                Optional.empty(),
                                MessageRole.ASSISTANT,
                                MessageStatus.COMPLETED,
                                MessageVisibility.INTERNAL,
                                List.of(new TextPart("answer", "plain")),
                                Map.of(),
                                NOW.plusSeconds(1)),
                        new ModelContinuationDraft(
                                new ModelContinuationRef(
                                        "continuation", "1.0", reasoning.digest(), reasoning.byteLength()),
                                runId,
                                sessionId,
                                "model-call",
                                configuration.model().providerId().value(),
                                configuration.model().modelId().value(),
                                configuration.model().configurationDigest(),
                                Set.of("provider-call"),
                                reasoning,
                                NOW.plusSeconds(1)));
        return new PreparedContinuation(messageId, configuration.model(), reasoning);
    }

    private static RuntimeConfigurationSnapshot configuration() {
        ResolvedModelSnapshot model = ResolvedModelSnapshot.create(
                new ModelProviderId("provider"),
                "1",
                new ModelDefinitionId("model"),
                "1",
                "model",
                "openai-compatible",
                "1",
                new ApiStyleId("openai-chat-completions"),
                "standard",
                URI.create("https://example.invalid"),
                new CredentialRef("env://TEST_API_KEY"),
                true,
                Set.of(ModelCapability.TEXT_CHAT),
                8_192,
                1_024,
                Map.of(),
                Map.of());
        return new RuntimeConfigurationSnapshot(
                new RunConfigurationSnapshotRef("configuration", "sha256:configuration"),
                new AgentDefinitionId("agent"),
                new AgentDefinitionVersion(1, 0, 0),
                "profile",
                "1",
                AgentRunType.CHAT,
                new AgentRunBudget(100, 100, 100, 10, 10, 2, "USD", 100),
                new AgentRunLimits(10, 2, 1, 60_000, 10_000),
                List.of(),
                List.of(),
                new SkillContentDigest("sha256:" + "0".repeat(64)),
                "skill-policy",
                Set.of(),
                "answer",
                RuntimeOverrides.NONE,
                List.of(),
                model);
    }

    private record PreparedContinuation(
            AgentMessageId messageId, ResolvedModelSnapshot model, SensitiveModelReasoning reasoning) {}

    private static final class MutableSecretKey implements SecretKey {
        private static final long serialVersionUID = 1L;
        private final byte[] encoding;

        private MutableSecretKey(byte[] encoding) {
            this.encoding = encoding;
        }

        @Override
        public String getAlgorithm() {
            return "AES";
        }

        @Override
        public String getFormat() {
            return "RAW";
        }

        @Override
        public byte[] getEncoded() {
            return encoding;
        }
    }
}

package io.haifa.agent.store.sqlite;

import static io.haifa.agent.store.sqlite.SqliteAggregateTestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.model.api.ApiStyleId;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.model.api.SensitiveModelReasoning;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationDraft;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationRef;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
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
    private static final SecretKeySpec KEY = new SecretKeySpec(KEY_BYTES, "AES");
    private static final AgentMessageId MESSAGE_ID = new AgentMessageId("assistant-message");
    private static final SensitiveModelReasoning REASONING = SensitiveModelReasoning.of("sensitive-reasoning");
    private static final ResolvedModelSnapshot MODEL = ResolvedModelSnapshot.create(
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

    @Test
    void secretKeyFacadeRecoversContinuationAfterRestartAndRejectsWrongKey(@TempDir Path directory) {
        SqliteStoreConfiguration configuration = SqliteTestSupport.configuration(directory);
        AgentRun run;
        try (SqliteStoreFoundation foundation = SqliteStoreFoundation.initialize(configuration, Clock.systemUTC())) {
            run = SqliteAggregateTestData.prepareRun(foundation);
        }
        try (var persistence = open(configuration, KEY)) {
            appendContinuation(persistence, run);
        }

        try (var persistence = open(configuration, KEY)) {
            assertThat(resolve(persistence)).isEqualTo(REASONING);
        }
        try (var persistence = open(configuration, new SecretKeySpec(new byte[32], "AES"))) {
            assertThatThrownBy(() -> resolve(persistence))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageNotContaining("sensitive-reasoning");
        }
    }

    @Test
    void rejectsNonAes256KeysBeforeOpeningDatabase(@TempDir Path directory) {
        SqliteStoreConfiguration configuration = SqliteTestSupport.configuration(directory);

        assertThatThrownBy(() -> open(configuration, new SecretKeySpec(new byte[16], "AES")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("256-bit AES key");
        assertThatThrownBy(() -> open(configuration, new SecretKeySpec(new byte[32], "HmacSHA256")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must use AES");
        assertThat(configuration.databasePath()).doesNotExist();
    }

    @Test
    void copiesKeyMaterialBeforeClearingAnAdversarialEncoding() {
        byte[] sharedEncoding = KEY_BYTES.clone();

        SecretKey copy = SqliteSdkContributions.requireAes256Key(new SharedEncodingKey(sharedEncoding));

        assertThat(copy.getEncoded()).isEqualTo(KEY_BYTES);
        assertThat(sharedEncoding).containsOnly((byte) 0);
    }

    @Test
    void borrowedPersistenceDoesNotOwnTheFoundation(@TempDir Path directory) {
        SqliteSdkContributions sqlite = SqliteSdkContributions.initializeWithKey(
                SqliteTestSupport.configuration(directory), Clock.systemUTC(), KEY);
        var borrowed = sqlite.borrowedPersistence();

        borrowed.close();
        assertThat(borrowed.inTransaction(() -> "still-open")).isEqualTo("still-open");

        sqlite.persistence().close();
        assertThatThrownBy(() -> borrowed.inTransaction(() -> "closed"))
                .isInstanceOf(SqliteStoreException.class)
                .hasMessageContaining("closed");
    }

    private static SqliteSdkPersistenceContribution open(SqliteStoreConfiguration configuration, SecretKey key) {
        return SqliteSdkContributions.initializeWithKey(configuration, Clock.systemUTC(), key)
                .persistence();
    }

    private static SensitiveModelReasoning resolve(SqliteSdkPersistenceContribution persistence) {
        return persistence.runtimePersistence().state().resolveContinuation(MESSAGE_ID, MODEL, Set.of("provider-call"));
    }

    private static void appendContinuation(SqliteSdkPersistenceContribution persistence, AgentRun run) {
        persistence
                .runtimePersistence()
                .state()
                .appendSessionMessageWithContinuation(
                        new SessionMessageDraft(
                                MESSAGE_ID,
                                run.sessionId(),
                                Optional.of(run.id()),
                                Optional.empty(),
                                MessageRole.ASSISTANT,
                                MessageStatus.COMPLETED,
                                MessageVisibility.INTERNAL,
                                List.of(new TextPart("answer", "plain")),
                                Map.of(),
                                NOW.plusSeconds(1)),
                        new ModelContinuationDraft(
                                new ModelContinuationRef(
                                        "continuation", "1.0", REASONING.digest(), REASONING.byteLength()),
                                run.id(),
                                run.sessionId(),
                                "model-call",
                                MODEL.providerId().value(),
                                MODEL.modelId().value(),
                                MODEL.configurationDigest(),
                                Set.of("provider-call"),
                                REASONING,
                                NOW.plusSeconds(1)));
    }

    /** Returns its backing array instead of a copy, so clearing it would destroy the caller's key. */
    private static final class SharedEncodingKey implements SecretKey {
        private static final long serialVersionUID = 1L;
        private final byte[] encoding;

        private SharedEncodingKey(byte[] encoding) {
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

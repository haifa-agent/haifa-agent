package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelAdapterCoordinate;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.policy.api.PolicyPresets;
import io.haifa.agent.policy.core.DefaultPolicyDecisionService;
import io.haifa.agent.runtime.api.RuntimeApiErrorCode;
import io.haifa.agent.runtime.api.RuntimeContractException;
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.api.HaifaAgents;
import io.haifa.agent.sdk.api.SdkCaller;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.product.ProductId;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.sdk.product.ProductRunProfileRef;
import io.haifa.agent.sdk.product.ProductVersion;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SqliteFrozenInstructionDiagnosticTest {
    private static final String INSTRUCTION_A = "Synthetic frozen instruction A: 中文\nsecond line";
    private static final SecretKey KEY = new SecretKeySpec(new byte[32], "AES");
    private static final SdkCaller DEFAULT_CALLER = SdkCaller.defaultPublicUser();

    @Test
    void waitingDigestSurvivesStoreReopenAndRemainsInvisibleToOtherCallers(@TempDir Path directory) throws Exception {
        Path db = directory.resolve("reopen.sqlite");
        AgentRunId id = setupWaitingRunWithInstruction(db, INSTRUCTION_A);

        var reopenedSqlite =
                SqliteSdkContributions.initializeWithKey(SqliteStoreConfiguration.defaults(db), Clock.systemUTC(), KEY);
        var callerRef = new AtomicReference<>(DEFAULT_CALLER);
        try (HaifaAgent agent = createAgent(reopenedSqlite, INSTRUCTION_A, callerRef)) {
            // 1. Same caller: frozen digest matches original instruction A
            var frozen = agent.runs().frozenInstructionDiagnostic(id).orElseThrow();
            assertThat(frozen.instructionContentHash()).isEqualTo(hash(INSTRUCTION_A));
            assertThat(agent.runs().find(id).orElseThrow().status()).isEqualTo(AgentRunStatus.WAITING_APPROVAL);

            // 2. Same tenant, different principal: invisible
            callerRef.set(new SdkCaller(DEFAULT_CALLER.tenant(), new PrincipalRef("another-user", "user")));
            assertThat(agent.runs().frozenInstructionDiagnostic(id)).isEmpty();

            // 3. Different tenant: invisible
            callerRef.set(new SdkCaller(new TenantRef("another-tenant"), DEFAULT_CALLER.principal()));
            assertThat(agent.runs().frozenInstructionDiagnostic(id)).isEmpty();

            // 4. Unknown run: invisible
            callerRef.set(DEFAULT_CALLER);
            assertThat(agent.runs().frozenInstructionDiagnostic(new AgentRunId("missing-run")))
                    .isEmpty();
        } finally {
            reopenedSqlite.persistence().close();
        }
    }

    @Test
    void actualSqlitePayloadHashRowHashAndMissingSnapshotFailWithFixedSafeError(@TempDir Path directory)
            throws Exception {
        for (String mutation : List.of(
                "UPDATE configuration_snapshot SET content_payload_hash = 'sha256:0000000000000000000000000000000000000000000000000000000000000000'",
                "UPDATE configuration_snapshot SET content_hash = 'sha256:0000000000000000000000000000000000000000000000000000000000000000'",
                "UPDATE configuration_snapshot SET content_payload = X'000102030405'",
                "DELETE FROM configuration_snapshot")) {
            Path db = directory.resolve("corrupt-" + UUID.randomUUID() + ".sqlite");
            AgentRunId id = setupWaitingRunWithInstruction(db, INSTRUCTION_A);
            long eventsBefore;
            try (var conn = DriverManager.getConnection("jdbc:sqlite:" + db);
                    var stmt = conn.createStatement()) {
                try (var rs = stmt.executeQuery("SELECT COUNT(*) FROM runtime_event")) {
                    assertThat(rs.next()).isTrue();
                    eventsBefore = rs.getLong(1);
                }
                assertThat(stmt.executeUpdate(mutation)).isEqualTo(1);
            }

            var sqlite = SqliteSdkContributions.initializeWithKey(
                    SqliteStoreConfiguration.defaults(db), Clock.systemUTC(), KEY);
            try (HaifaAgent agent = createAgent(sqlite, INSTRUCTION_A, new AtomicReference<>(DEFAULT_CALLER))) {
                assertThatThrownBy(() -> agent.runs().frozenInstructionDiagnostic(id))
                        .isInstanceOf(RuntimeContractException.class)
                        .satisfies(error -> {
                            var failure = (RuntimeContractException) error;
                            assertThat(failure.code()).isEqualTo(RuntimeApiErrorCode.INTERNAL_ERROR);
                            assertThat(failure.getMessage()).isEqualTo("Frozen instruction diagnostic is unavailable");
                            assertThat(failure.getCause()).isNull();
                            assertThat(failure.getMessage()).doesNotContain("Synthetic frozen instruction");
                        });

                try (var conn = DriverManager.getConnection("jdbc:sqlite:" + db);
                        var stmt = conn.createStatement()) {
                    try (var rs = stmt.executeQuery("SELECT COUNT(*) FROM runtime_event")) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getLong(1)).isEqualTo(eventsBefore);
                    }
                    try (var rs = stmt.executeQuery("SELECT COUNT(*) FROM run")) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getLong(1)).isEqualTo(1);
                    }
                }
            } finally {
                sqlite.persistence().close();
            }
        }
    }

    private static AgentRunId setupWaitingRunWithInstruction(Path db, String instruction) throws Exception {
        Files.createDirectories(db.getParent());
        var sqlite =
                SqliteSdkContributions.initializeWithKey(SqliteStoreConfiguration.defaults(db), Clock.systemUTC(), KEY);
        try (HaifaAgent agent = createAgent(sqlite, instruction, new AtomicReference<>(DEFAULT_CALLER))) {
            var started = agent.conversations()
                    .start(new StartConversationCommand("frozen-test", "Fixture", "Return fixture result"));
            AgentRunId id = started.runId();
            awaitWaitingApproval(agent, id);
            return id;
        } finally {
            sqlite.persistence().close();
        }
    }

    private static HaifaAgent createAgent(
            SqliteSdkContributions sqlite, String instruction, AtomicReference<SdkCaller> callerRef) {
        var profile = ProductProfile.create(
                new ProductId("frozen-fixture"),
                new ProductVersion("1.0.0"),
                new AgentDefinitionId("frozen-fixture-agent"),
                new AgentDefinitionVersion(1, 0, 0),
                instruction,
                new ProductRunProfileRef("frozen-chat", "1.0.0"),
                new AgentRunBudget(10_000, 10_000, 10_000, 8, 8, 0, "USD", 1_000),
                new AgentRunLimits(8, 0, 1, 30_000, 30_000),
                Set.of("pending_write"),
                Set.of());
        var snapshot = ResolvedModelSnapshot.create(
                new ModelProviderId("frozen-fixture"),
                "1.0.0",
                new ModelDefinitionId("frozen-fixture"),
                "1.0.0",
                "frozen-fixture",
                "frozen-fixture",
                "1.0.0",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                ModelApiBindingDefinition.STANDARD_DIALECT,
                URI.create("https://model.invalid"),
                new CredentialRef("env://UNUSED_FROZEN_FIXTURE"),
                false,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                8_192,
                1_024,
                Map.of(),
                Map.of());
        var model = new ModelContribution(
                Map.of(
                        ModelAdapterCoordinate.from(snapshot),
                        request -> new AgentChatResponse(
                                "frozen-fixture",
                                request.model().providerModelId(),
                                "",
                                List.of(new ModelToolCall(
                                        new ProviderToolCallCorrelationId("pending-write"),
                                        "pending_write",
                                        Map.of("text", "synthetic pending write"))),
                                ModelFinishReason.TOOL_CALLS,
                                ModelUsage.unpriced(4, 4),
                                "",
                                Map.of())),
                snapshot,
                Map.of(snapshot.modelId().value(), snapshot));
        return HaifaAgents.builder(profile)
                .model(model)
                .callerProvider(callerRef::get)
                .persistence(sqlite.borrowedPersistence())
                .conversation(sqlite.conversation())
                .tool(new PendingWriteTool())
                .policy(new PolicyPlatformContribution(
                        PolicyPresets.standardApproval(), new DefaultPolicyDecisionService()))
                .build();
    }

    private static void awaitWaitingApproval(HaifaAgent agent, AgentRunId runId) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            var snapshot = agent.runs().find(runId);
            if (snapshot.isPresent() && snapshot.get().status() == AgentRunStatus.WAITING_APPROVAL) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Run did not reach WAITING_APPROVAL within deadline");
    }

    private static String hash(String text) throws Exception {
        return "sha256:"
                + HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    public record WriteInput(String text) {}

    public record WriteOutput(boolean written) {}

    private static final class PendingWriteTool implements JavaTool<WriteInput, WriteOutput> {
        @Override
        public JavaToolSpec<WriteInput, WriteOutput> spec() {
            return JavaToolSpec.builder("pending_write", WriteInput.class, WriteOutput.class)
                    .description("Synthetic durable approval fixture")
                    .sideEffects(ToolSideEffect.FILE_WRITE)
                    .build();
        }

        @Override
        public WriteOutput invoke(WriteInput input, JavaToolContext context) {
            return new WriteOutput(true);
        }
    }
}

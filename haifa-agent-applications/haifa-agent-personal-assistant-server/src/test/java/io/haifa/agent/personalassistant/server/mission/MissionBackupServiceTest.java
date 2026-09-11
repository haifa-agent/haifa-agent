package io.haifa.agent.personalassistant.server.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.session.AgentSession;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.SessionScope;
import io.haifa.agent.personalassistant.application.mission.DeterministicMissionPlanner;
import io.haifa.agent.personalassistant.application.mission.MissionApplicationService;
import io.haifa.agent.personalassistant.application.mission.MissionConstraints;
import io.haifa.agent.personalassistant.application.mission.MissionException;
import io.haifa.agent.personalassistant.application.mission.MissionExecutionCoordinator;
import io.haifa.agent.personalassistant.application.mission.MissionPlanValidator;
import io.haifa.agent.personalassistant.application.mission.MissionRuntimeAccess;
import io.haifa.agent.runtime.core.model.continuation.PlaintextModelContinuationProtector;
import io.haifa.agent.sdk.api.SdkConfigurationDigest;
import io.haifa.agent.sdk.contribution.SdkContributionMetadata;
import io.haifa.agent.sdk.conversation.ConversationRecord;
import io.haifa.agent.sdk.conversation.ConversationStatus;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.product.ProductContributionCoordinate;
import io.haifa.agent.sdk.product.ProductProviderSuitability;
import io.haifa.agent.store.sqlite.SqliteConnectionFactory;
import io.haifa.agent.store.sqlite.SqliteSdkProductContributions;
import io.haifa.agent.store.sqlite.SqliteStoreConfiguration;
import io.haifa.agent.store.sqlite.migration.HaifaAgentStoreMigrations;
import io.haifa.agent.store.sqlite.migration.SqlScriptParser;
import io.haifa.agent.store.sqlite.migration.SqliteMigrationRunner;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MissionBackupServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC);
    private static final String FULL_INITIALIZATION_RESOURCE =
            "/io/haifa/agent/store/sqlite/migration/haifa-agent-v1.0-init.sql";

    @TempDir
    Path directory;

    @Test
    void personalSqliteAssemblyReopensSharedV10ArtifactAndRoundTripsConversation() throws Exception {
        Path database = directory.resolve("personal-v1.sqlite");
        initializeArtifact(database);
        AgentSessionId sessionId = new AgentSessionId("personal-v1-conversation");
        ConversationRecord conversation = new ConversationRecord(
                sessionId,
                new TenantRef("local"),
                new PrincipalRef("public-user", "user"),
                "Shared V1",
                ConversationStatus.ACTIVE,
                Optional.empty(),
                OptionalLong.empty(),
                Optional.empty(),
                CLOCK.instant(),
                CLOCK.instant(),
                0);

        SqliteSdkProductContributions first = personalSqlite(database);
        first.persistence()
                .runtimePersistence()
                .sessions()
                .insert(AgentSession.open(
                        sessionId,
                        conversation.tenant(),
                        conversation.principal(),
                        null,
                        SessionScope.USER,
                        CLOCK.instant(),
                        Map.of()));
        first.conversation().conversationStore().create(conversation);
        first.persistence().close();

        SqliteSdkProductContributions reopened = personalSqlite(database);
        try {
            assertThat(reopened.conversation().conversationStore().find(sessionId))
                    .contains(conversation);
        } finally {
            reopened.persistence().close();
        }
    }

    @Test
    void createsVerifiedBackupAndRestoresOnlyIntoFreshDirectory() throws Exception {
        Fixture fixture = fixture("source");
        Path backup = directory.resolve("backup");
        Path restored = directory.resolve("restored");

        var result = fixture.service().create(backup);
        var restore = fixture.service().restore(backup, restored);

        assertThat(result.manifest().missionSchemaVersion()).isEqualTo(8);
        assertThat(result.manifest().runtimeSchemaVersion())
                .isEqualTo(HaifaAgentStoreMigrations.CURRENT_SCHEMA_VERSION);
        assertThat(restore.directory()).isEqualTo(restored.toAbsolutePath());
        assertThat(new SqliteMissionStore(restored.resolve("personal-assistant.sqlite"), new ObjectMapper())
                        .schemaVersion())
                .isEqualTo(8);
        Files.writeString(restored.resolve("occupied"), "occupied");
        assertThatThrownBy(() -> fixture.service().restore(backup, restored))
                .isInstanceOf(MissionException.class)
                .extracting(value -> ((MissionException) value).code())
                .isEqualTo("MISSION_MAINTENANCE_PATH_NOT_FRESH");
        fixture.dispatcher().close();
    }

    @Test
    void rejectsNonQuiescentBackupAndCorruptedRestore() throws Exception {
        Fixture fixture = fixture("active");
        MissionApplicationService missions = new MissionApplicationService(
                fixture.store(),
                fixture.store(),
                new DeterministicMissionPlanner(),
                MissionPlanValidator.phaseOne(),
                () -> "mission-active",
                CLOCK);
        missions.create(new MissionApplicationService.CreateMission(
                "create-active",
                "local/public-user",
                "conversation-active",
                "Active mission blocks backup",
                List.of("evidence"),
                MissionConstraints.DEFAULT));
        assertThatThrownBy(() -> fixture.service().create(directory.resolve("blocked-backup")))
                .isInstanceOf(MissionException.class)
                .extracting(value -> ((MissionException) value).code())
                .isEqualTo("MISSION_NOT_QUIESCENT");

        Fixture clean = fixture("clean");
        Path backup = directory.resolve("corrupt-backup");
        clean.service().create(backup);
        Files.writeString(backup.resolve("personal-assistant.sqlite"), "corrupt", StandardOpenOption.TRUNCATE_EXISTING);
        assertThatThrownBy(() -> clean.service().restore(backup, directory.resolve("corrupt-restore")))
                .isInstanceOf(MissionException.class)
                .extracting(value -> ((MissionException) value).code())
                .isEqualTo("MISSION_BACKUP_INTEGRITY_FAILED");
        fixture.dispatcher().close();
        clean.dispatcher().close();
    }

    @Test
    void executableMaintenanceFlowBacksUpVerifiesRestoresAndRejectsOnlineBackup() throws Exception {
        Fixture fixture = fixture("cli-source");
        String digest = "sha256:product";
        String binding = "deep-research@1#sha256:skill";
        Path backup = directory.resolve("cli-backup");
        Path restored = directory.resolve("cli-restored");

        MissionMaintenanceMain.run(new String[] {
            "backup", fixture.store().database().getParent().toString(), backup.toString(), digest, binding
        });
        MissionMaintenanceMain.run(new String[] {"verify", backup.toString(), "-", digest, binding});
        MissionMaintenanceMain.run(new String[] {"restore", backup.toString(), restored.toString(), digest, binding});
        assertThat(Files.isRegularFile(restored.resolve("personal-assistant.sqlite")))
                .isTrue();

        fixture.dispatcher().start();
        assertThatThrownBy(() -> MissionMaintenanceMain.run(new String[] {
                    "backup",
                    fixture.store().database().getParent().toString(),
                    directory.resolve("online-backup").toString(),
                    digest,
                    binding
                }))
                .isInstanceOf(MissionException.class)
                .extracting(value -> ((MissionException) value).code())
                .isEqualTo("MISSION_SERVER_MUST_BE_STOPPED");
        fixture.dispatcher().close();
    }

    private Fixture fixture(String name) throws Exception {
        Path data = directory.resolve(name);
        Files.createDirectories(data);
        Path database = data.resolve("personal-assistant.sqlite").toAbsolutePath();
        var connections = new SqliteConnectionFactory(SqliteStoreConfiguration.defaults(database));
        connections.initialize();
        new SqliteMigrationRunner(connections, CLOCK).migrate(HaifaAgentStoreMigrations.all());
        var store = new SqliteMissionStore(database, new ObjectMapper());
        MissionRuntimeAccess runtime = request -> {
            throw new AssertionError("planner is not used");
        };
        var coordinator = new MissionExecutionCoordinator(store, runtime, CLOCK, "dispatcher");
        var dispatcher = new MissionDispatcher(store, coordinator, CLOCK, data);
        var service = new MissionBackupService(
                store,
                dispatcher,
                new ObjectMapper().findAndRegisterModules(),
                CLOCK,
                "sha256:product",
                "deep-research@1#sha256:skill");
        return new Fixture(store, dispatcher, service);
    }

    private static SqliteSdkProductContributions personalSqlite(Path database) {
        return SqliteSdkProductContributions.initialize(
                SqliteStoreConfiguration.defaults(database),
                CLOCK,
                new PlaintextModelContinuationProtector(),
                metadata("pa-persistence", ProductCapabilities.PERSISTENCE),
                metadata("pa-conversation", ProductCapabilities.CONVERSATION),
                metadata("pa-memory", ProductCapabilities.MEMORY));
    }

    private static SdkContributionMetadata metadata(
            String id, io.haifa.agent.sdk.product.ProductCapabilityId capability) {
        return new SdkContributionMetadata(
                new ProductContributionCoordinate(id, "1.0.0"),
                capability,
                SdkConfigurationDigest.sha256(id, "shared-v1"),
                ProductProviderSuitability.PRODUCTION,
                id);
    }

    private static void initializeArtifact(Path database) throws Exception {
        String script;
        try (InputStream input = MissionBackupServiceTest.class.getResourceAsStream(FULL_INITIALIZATION_RESOURCE)) {
            assertThat(input).as("shared V1.0 initialization SQL resource").isNotNull();
            script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.createStatement()) {
            for (String sql : SqlScriptParser.parse(script)) {
                statement.execute(sql);
            }
        }
    }

    private record Fixture(SqliteMissionStore store, MissionDispatcher dispatcher, MissionBackupService service) {}
}

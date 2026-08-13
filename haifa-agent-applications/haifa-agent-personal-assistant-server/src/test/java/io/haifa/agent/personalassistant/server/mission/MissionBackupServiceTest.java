package io.haifa.agent.personalassistant.server.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.personalassistant.application.mission.DeterministicMissionPlanner;
import io.haifa.agent.personalassistant.application.mission.MissionApplicationService;
import io.haifa.agent.personalassistant.application.mission.MissionConstraints;
import io.haifa.agent.personalassistant.application.mission.MissionException;
import io.haifa.agent.personalassistant.application.mission.MissionExecutionCoordinator;
import io.haifa.agent.personalassistant.application.mission.MissionPlanValidator;
import io.haifa.agent.personalassistant.application.mission.MissionRuntimeAccess;
import io.haifa.agent.store.sqlite.SqliteConnectionFactory;
import io.haifa.agent.store.sqlite.SqliteStoreConfiguration;
import io.haifa.agent.store.sqlite.migration.RuntimeStoreMigrations;
import io.haifa.agent.store.sqlite.migration.SqliteMigrationRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MissionBackupServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path directory;

    @Test
    void createsVerifiedBackupAndRestoresOnlyIntoFreshDirectory() throws Exception {
        Fixture fixture = fixture("source");
        Path backup = directory.resolve("backup");
        Path restored = directory.resolve("restored");

        var result = fixture.service().create(backup);
        var restore = fixture.service().restore(backup, restored);

        assertThat(result.manifest().missionSchemaVersion()).isEqualTo(7);
        assertThat(result.manifest().runtimeSchemaVersion()).isEqualTo(7);
        assertThat(restore.directory()).isEqualTo(restored.toAbsolutePath());
        assertThat(new SqliteMissionStore(restored.resolve("personal-assistant.sqlite"), new ObjectMapper())
                        .schemaVersion())
                .isEqualTo(7);
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
        new SqliteMigrationRunner(connections, CLOCK).migrate(RuntimeStoreMigrations.all());
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

    private record Fixture(SqliteMissionStore store, MissionDispatcher dispatcher, MissionBackupService service) {}
}

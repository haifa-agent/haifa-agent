package io.haifa.agent.personalassistant.server.mission;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.personalassistant.server.configuration.product.PersonalAssistantProperties;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MissionCapacityMonitorTest {
    @TempDir
    Path data;

    @Test
    void stopsNewAdmissionAtTheFixedDatabaseThreshold() throws Exception {
        Path database = data.resolve("personal-assistant.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE artifact(payload_id TEXT, payload_sha256 TEXT, payload_byte_count INTEGER)");
        }

        var healthy = new MissionCapacityMonitor(data, limits(1024 * 1024, 1024 * 1024));
        assertThat(healthy.refreshIntegrity()).isEqualTo("NONE");
        assertThat(healthy.snapshot().acceptingNewWork()).isTrue();

        var stopped = new MissionCapacityMonitor(data, limits(1, 1));
        assertThat(stopped.refreshIntegrity()).isEqualTo("NONE");
        var snapshot = stopped.snapshot();
        assertThat(snapshot.acceptingNewWork()).isFalse();
        assertThat(snapshot.databaseWarning()).isTrue();
        assertThat(snapshot.blockerCode()).isEqualTo("MISSION_DATABASE_CAPACITY_EXHAUSTED");
    }

    private static PersonalAssistantProperties.Mission limits(long databaseWarning, long databaseStop) {
        return new PersonalAssistantProperties.Mission(
                "deterministic-stub",
                8,
                4,
                20,
                1,
                2,
                30 * 60_000,
                200_000,
                100,
                8,
                4 * 1024 * 1024,
                500,
                20_000,
                100,
                databaseWarning,
                databaseStop,
                1024 * 1024,
                1024 * 1024);
    }
}

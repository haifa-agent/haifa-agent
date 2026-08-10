package io.haifa.agent.personalassistant.server.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void boundsLongRunningMissionWallClockAtTwoHours() {
        assertThat(limits(1, 1, PersonalAssistantProperties.Mission.MAX_WALL_CLOCK_MILLIS)
                        .maxWallClockMillis())
                .isEqualTo(2 * 60 * 60_000L);
        assertThatThrownBy(() -> limits(1, 1, PersonalAssistantProperties.Mission.MAX_WALL_CLOCK_MILLIS + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not exceed 2 hours");
    }

    private static PersonalAssistantProperties.Mission limits(long databaseWarning, long databaseStop) {
        return limits(databaseWarning, databaseStop, 30 * 60_000L);
    }

    private static PersonalAssistantProperties.Mission limits(
            long databaseWarning, long databaseStop, long maxWallClockMillis) {
        return new PersonalAssistantProperties.Mission(
                "deterministic-stub",
                8,
                4,
                20,
                1,
                2,
                maxWallClockMillis,
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

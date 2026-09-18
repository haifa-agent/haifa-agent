package io.haifa.agent.personalassistant.server.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.personalassistant.application.mission.MissionException;
import io.haifa.agent.personalassistant.application.mission.MissionExecutionCoordinator;
import io.haifa.agent.personalassistant.application.mission.MissionRuntimeAccess;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MissionDispatcherTest {
    @TempDir
    Path directory;

    @Test
    void secondDispatcherForSameDataDirectoryFailsClosed() {
        SqliteMissionStore store = new SqliteMissionStore(directory.resolve("personal.sqlite"), new ObjectMapper());
        MissionRuntimeAccess runtime = request -> {
            throw new AssertionError("planner is not used");
        };
        var coordinator = new MissionExecutionCoordinator(store, runtime, Clock.systemUTC(), "dispatcher");
        try (var first = new MissionDispatcher(store, coordinator, Clock.systemUTC(), directory);
                var second = new MissionDispatcher(store, coordinator, Clock.systemUTC(), directory)) {
            first.start();
            assertThat(first.ready()).isTrue();
            assertThat(first.snapshot().status()).isEqualTo("READY");
            assertThat(first.snapshot().lastReconcileAtMillis()).isPositive();
            assertThatThrownBy(second::start)
                    .isInstanceOf(MissionException.class)
                    .extracting(value -> ((MissionException) value).code())
                    .isEqualTo("MISSION_DISPATCHER_ALREADY_RUNNING");
        }
    }
}

package io.haifa.agent.personalassistant.server.mission;

import io.haifa.agent.personalassistant.application.mission.MissionException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Safe, low-cardinality Mission operations and admission facts. */
@Component
public final class MissionOperationsService {
    private final SqliteMissionStore store;
    private final MissionDispatcher dispatcher;
    private final MissionCapacityMonitor capacity;
    private final Clock clock;

    public MissionOperationsService(
            SqliteMissionStore store, MissionDispatcher dispatcher, MissionCapacityMonitor capacity, Clock clock) {
        this.store = java.util.Objects.requireNonNull(store);
        this.dispatcher = java.util.Objects.requireNonNull(dispatcher);
        this.capacity = java.util.Objects.requireNonNull(capacity);
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    public void requireAdmission() {
        MissionCapacityMonitor.CapacitySnapshot capacitySnapshot = capacity.snapshot();
        if (!capacitySnapshot.acceptingNewWork()) {
            throw new MissionException(capacitySnapshot.blockerCode(), "Mission capacity prevents new work");
        }
        if (!dispatcher.ready()) {
            throw new MissionException("MISSION_NOT_READY", "Mission execution is not ready to accept new work");
        }
    }

    public OperationsSnapshot snapshot() {
        Instant now = Instant.ofEpochMilli(clock.instant().toEpochMilli());
        return new OperationsSnapshot(
                dispatcher.snapshot(), store.operationalSnapshot(now), capacity.snapshot(), store.schemaVersion());
    }

    public UpgradeReadiness upgradeReadiness() {
        OperationsSnapshot current = snapshot();
        List<String> blockers = new ArrayList<>();
        if (current.store().activeMissions() != 0) blockers.add("ACTIVE_MISSIONS");
        if (current.store().pendingOutbox() != 0) blockers.add("PENDING_OUTBOX");
        if (current.store().unsettledAttempts() != 0) blockers.add("UNSETTLED_ATTEMPTS");
        if (current.dispatcher().maintenancePaused()) blockers.add("MAINTENANCE_BUSY");
        if (!current.capacity().acceptingNewWork())
            blockers.add(current.capacity().blockerCode());
        return new UpgradeReadiness(blockers.isEmpty(), List.copyOf(blockers), current.schemaVersion());
    }

    public record OperationsSnapshot(
            MissionDispatcher.DispatcherSnapshot dispatcher,
            SqliteMissionStore.OperationalSnapshot store,
            MissionCapacityMonitor.CapacitySnapshot capacity,
            int schemaVersion) {}

    public record UpgradeReadiness(boolean ready, List<String> blockerCodes, int schemaVersion) {}
}

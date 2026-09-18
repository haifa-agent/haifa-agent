package io.haifa.agent.personalassistant.application.mission;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** Deterministic Store/UoW adapter used by contract and application tests. */
public final class InMemoryMissionStore implements MissionStore, MissionUnitOfWork {
    private final Map<String, PersonalMission.Persistence> missions = new HashMap<>();
    private final Map<String, MissionCommandBinding> commands = new HashMap<>();

    @Override
    public synchronized <T> T execute(Supplier<T> work) {
        Map<String, PersonalMission.Persistence> missionBackup = new HashMap<>(missions);
        Map<String, MissionCommandBinding> commandBackup = new HashMap<>(commands);
        try {
            return work.get();
        } catch (RuntimeException | Error failure) {
            missions.clear();
            missions.putAll(missionBackup);
            commands.clear();
            commands.putAll(commandBackup);
            throw failure;
        }
    }

    @Override
    public MissionCommandReservation reserveCommand(MissionCommandBinding proposal) {
        String key = commandKey(proposal.ownerScope(), proposal.operation(), proposal.idempotencyKey());
        MissionCommandBinding existing = commands.get(key);
        if (existing != null) {
            if (!existing.requestDigest().equals(proposal.requestDigest())) {
                throw new MissionException(
                        "MISSION_IDEMPOTENCY_CONFLICT", "Idempotency-Key was reused with another payload");
            }
            return new MissionCommandReservation(existing, false);
        }
        commands.put(key, proposal);
        return new MissionCommandReservation(proposal, true);
    }

    @Override
    public void insert(PersonalMission mission) {
        PersonalMission.Persistence value = mission.persistence();
        if (missions.containsKey(value.missionId())) {
            throw new MissionException("MISSION_ALREADY_EXISTS", "Mission already exists");
        }
        findActive(value.conversationId(), value.ownerScope()).ifPresent(existing -> {
            throw new MissionException("MISSION_ACTIVE_EXISTS", "Conversation already has an active Mission");
        });
        missions.put(value.missionId(), value);
    }

    @Override
    public void save(PersonalMission mission, long expectedVersion) {
        PersonalMission.Persistence value = mission.persistence();
        PersonalMission.Persistence current = missions.get(value.missionId());
        if (current == null || !current.ownerScope().equals(value.ownerScope())) {
            throw new MissionException("MISSION_NOT_FOUND", "Mission is unavailable");
        }
        if (current.version() != expectedVersion) {
            throw new MissionException("MISSION_REVISION_STALE", "Mission revision is stale");
        }
        missions.put(value.missionId(), value);
    }

    @Override
    public Optional<PersonalMission> find(String missionId, String ownerScope) {
        PersonalMission.Persistence value = missions.get(missionId);
        if (value == null || !value.ownerScope().equals(ownerScope)) return Optional.empty();
        return Optional.of(PersonalMission.reconstitute(value));
    }

    @Override
    public Optional<PersonalMission> findActive(String conversationId, String ownerScope) {
        return missions.values().stream()
                .filter(value -> value.conversationId().equals(conversationId)
                        && value.ownerScope().equals(ownerScope)
                        && !value.state().terminal())
                .findFirst()
                .map(PersonalMission::reconstitute);
    }

    @Override
    public List<PersonalMission> list(
            String ownerScope, Optional<String> conversationId, Optional<MissionListCursor> cursor, int limit) {
        return missions.values().stream()
                .filter(value -> value.ownerScope().equals(ownerScope))
                .filter(value ->
                        conversationId.map(value.conversationId()::equals).orElse(true))
                .filter(value -> cursor.map(item -> value.updatedAt().isBefore(item.updatedAt())
                                || (value.updatedAt().equals(item.updatedAt())
                                        && value.missionId().compareTo(item.missionId()) < 0))
                        .orElse(true))
                .sorted(Comparator.comparing(PersonalMission.Persistence::updatedAt)
                        .thenComparing(PersonalMission.Persistence::missionId)
                        .reversed())
                .limit(limit)
                .map(PersonalMission::reconstitute)
                .toList();
    }

    private static String commandKey(String ownerScope, String operation, String idempotencyKey) {
        return ownerScope + '\u0000' + operation + '\u0000' + idempotencyKey;
    }
}

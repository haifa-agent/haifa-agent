package io.haifa.agent.personalassistant.application.mission;

import java.util.List;
import java.util.Optional;

/** Product persistence port. Calls participate in the injected MissionUnitOfWork. */
public interface MissionStore {
    MissionCommandReservation reserveCommand(MissionCommandBinding proposal);

    void insert(PersonalMission mission);

    void save(PersonalMission mission, long expectedVersion);

    Optional<PersonalMission> find(String missionId, String ownerScope);

    Optional<PersonalMission> findActive(String conversationId, String ownerScope);

    List<PersonalMission> list(
            String ownerScope, Optional<String> conversationId, Optional<MissionListCursor> cursor, int limit);
}

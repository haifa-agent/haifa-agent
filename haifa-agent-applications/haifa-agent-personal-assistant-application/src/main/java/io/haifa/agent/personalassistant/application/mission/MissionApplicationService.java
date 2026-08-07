package io.haifa.agent.personalassistant.application.mission;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Explicit Mission commands and queries. Phase 1 confirms plans but does not dispatch Task Runs. */
public final class MissionApplicationService {
    private final MissionStore store;
    private final MissionUnitOfWork unitOfWork;
    private final MissionPlanner planner;
    private final MissionPlanValidator validator;
    private final MissionIdGenerator ids;
    private final Clock clock;

    public MissionApplicationService(
            MissionStore store,
            MissionUnitOfWork unitOfWork,
            MissionPlanner planner,
            MissionPlanValidator validator,
            MissionIdGenerator ids,
            Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.unitOfWork = Objects.requireNonNull(unitOfWork);
        this.planner = Objects.requireNonNull(planner);
        this.validator = Objects.requireNonNull(validator);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
    }

    public MissionSnapshot create(CreateMission command) {
        Objects.requireNonNull(command);
        Instant now = now();
        String requestDigest = MissionValues.digest(
                command.conversationId(),
                command.objective(),
                String.join("\u0000", command.acceptanceCriteria()),
                command.constraints().toString());
        String proposedMissionId = ids.nextId();
        MissionCommandBinding binding = unitOfWork.execute(() -> {
            MissionCommandBinding reserved = store.reserveCommand(new MissionCommandBinding(
                            command.ownerScope(),
                            "create",
                            command.idempotencyKey(),
                            requestDigest,
                            proposedMissionId,
                            now))
                    .binding();
            if (store.find(reserved.missionId(), command.ownerScope()).isEmpty()) {
                store.insert(PersonalMission.create(
                        reserved.missionId(),
                        command.conversationId(),
                        command.ownerScope(),
                        command.objective(),
                        command.acceptanceCriteria(),
                        command.constraints(),
                        Optional.empty(),
                        now));
            }
            return reserved;
        });
        PersonalMission mission = unitOfWork.execute(() -> require(binding.missionId(), command.ownerScope()));
        if (mission.state() == MissionState.PLANNING) {
            MissionPlanner.PlanningResult planned = planner.plan(new MissionPlanner.PlanningRequest(
                    mission.missionId(),
                    mission.objective(),
                    mission.acceptanceCriteria(),
                    mission.constraints(),
                    mission.persistence().revisions().size() + 1));
            validatePlannerSchema(planned);
            mission.proposePlan(planned.tasks(), planned.plannerSessionId(), planned.plannerRunId(), validator, now());
            long expected = mission.version() - 1;
            unitOfWork.execute(() -> {
                PersonalMission current = require(binding.missionId(), command.ownerScope());
                if (current.state() == MissionState.PLANNING) store.save(mission, expected);
                return null;
            });
        }
        return unitOfWork.execute(
                () -> require(binding.missionId(), command.ownerScope()).snapshot());
    }

    public MissionSnapshot replacePlan(ReplaceMissionPlan command) {
        Objects.requireNonNull(command);
        String digest = MissionValues.digest(
                command.missionId(),
                Long.toString(command.expectedVersion()),
                command.tasks().toString());
        return unitOfWork.execute(() -> {
            PersonalMission mission = require(command.missionId(), command.ownerScope());
            MissionCommandReservation reservation = store.reserveCommand(new MissionCommandBinding(
                    command.ownerScope(),
                    "replace-plan",
                    command.idempotencyKey(),
                    digest,
                    command.missionId(),
                    now()));
            MissionCommandBinding binding = reservation.binding();
            if (!binding.missionId().equals(command.missionId())) {
                throw new MissionException("MISSION_IDEMPOTENCY_CONFLICT", "command belongs to another Mission");
            }
            if (!reservation.created()) return mission.snapshot();
            if (mission.version() != command.expectedVersion()) {
                throw new MissionException("MISSION_REVISION_STALE", "Mission revision is stale");
            }
            long expected = mission.version();
            mission.proposePlan(command.tasks(), Optional.empty(), Optional.empty(), validator, now());
            store.save(mission, expected);
            return mission.snapshot();
        });
    }

    public MissionSnapshot regeneratePlan(RegenerateMissionPlan command) {
        Objects.requireNonNull(command);
        String digest =
                MissionValues.digest(command.missionId(), Long.toString(command.expectedVersion()), "regenerate");
        Regeneration reservation = unitOfWork.execute(() -> {
            PersonalMission mission = require(command.missionId(), command.ownerScope());
            MissionCommandReservation reserved = store.reserveCommand(new MissionCommandBinding(
                    command.ownerScope(),
                    "regenerate-plan",
                    command.idempotencyKey(),
                    digest,
                    command.missionId(),
                    now()));
            if (!reserved.created() && mission.version() != command.expectedVersion()) {
                return new Regeneration(mission, false);
            }
            if (mission.version() != command.expectedVersion()) {
                throw new MissionException("MISSION_REVISION_STALE", "Mission revision is stale");
            }
            if (mission.state() != MissionState.WAITING_CONFIRMATION) {
                throw new MissionException("MISSION_PLAN_FROZEN", "Mission plan can no longer be regenerated");
            }
            return new Regeneration(mission, true);
        });
        if (!reservation.execute()) return reservation.mission().snapshot();

        PersonalMission mission = reservation.mission();
        MissionPlanner.PlanningResult planned = planner.plan(new MissionPlanner.PlanningRequest(
                mission.missionId(),
                mission.objective(),
                mission.acceptanceCriteria(),
                mission.constraints(),
                mission.persistence().revisions().size() + 1));
        validatePlannerSchema(planned);
        mission.proposePlan(planned.tasks(), planned.plannerSessionId(), planned.plannerRunId(), validator, now());
        return unitOfWork.execute(() -> {
            PersonalMission current = require(command.missionId(), command.ownerScope());
            if (current.version() != command.expectedVersion()) {
                return current.snapshot();
            }
            store.save(mission, command.expectedVersion());
            return mission.snapshot();
        });
    }

    public MissionSnapshot confirm(ChangeMission command) {
        return change(command, "confirm", mission -> mission.confirm(now()));
    }

    public MissionSnapshot cancel(ChangeMission command) {
        return change(command, "cancel", mission -> mission.cancel(now()));
    }

    public Optional<MissionSnapshot> find(String missionId, String ownerScope) {
        return unitOfWork.execute(() -> store.find(missionId, ownerScope).map(PersonalMission::snapshot));
    }

    public List<MissionSnapshot> list(String ownerScope, Optional<String> conversationId, int limit) {
        return list(ownerScope, conversationId, Optional.empty(), limit);
    }

    public List<MissionSnapshot> list(
            String ownerScope, Optional<String> conversationId, Optional<MissionListCursor> cursor, int limit) {
        if (limit < 1 || limit > 51) {
            throw new MissionException("MISSION_LIMIT_EXCEEDED", "Mission query size must be 1 to 51");
        }
        return unitOfWork.execute(() -> store.list(ownerScope, conversationId, cursor, limit).stream()
                .map(PersonalMission::snapshot)
                .toList());
    }

    private MissionSnapshot change(
            ChangeMission command, String operation, java.util.function.Consumer<PersonalMission> behavior) {
        Objects.requireNonNull(command);
        String digest = MissionValues.digest(command.missionId(), Long.toString(command.expectedVersion()));
        return unitOfWork.execute(() -> {
            PersonalMission mission = require(command.missionId(), command.ownerScope());
            MissionCommandReservation reservation = store.reserveCommand(new MissionCommandBinding(
                    command.ownerScope(), operation, command.idempotencyKey(), digest, command.missionId(), now()));
            if (!reservation.created()) return mission.snapshot();
            if (mission.version() != command.expectedVersion()) {
                throw new MissionException("MISSION_REVISION_STALE", "Mission revision is stale");
            }
            long expected = mission.version();
            behavior.accept(mission);
            store.save(mission, expected);
            return mission.snapshot();
        });
    }

    private PersonalMission require(String missionId, String ownerScope) {
        return store.find(missionId, ownerScope)
                .orElseThrow(() -> new MissionException("MISSION_NOT_FOUND", "Mission is unavailable"));
    }

    private Instant now() {
        return Instant.ofEpochMilli(clock.instant().toEpochMilli());
    }

    private static void validatePlannerSchema(MissionPlanner.PlanningResult planned) {
        if (!MissionPlanRevision.SCHEMA_ID.equals(planned.schemaId())
                || !MissionPlanRevision.SCHEMA_VERSION.equals(planned.schemaVersion())) {
            throw new MissionException("MISSION_PLAN_SCHEMA_UNSUPPORTED", "Planner returned an unsupported schema");
        }
    }

    private record Regeneration(PersonalMission mission, boolean execute) {}

    public record CreateMission(
            String idempotencyKey,
            String ownerScope,
            String conversationId,
            String objective,
            List<String> acceptanceCriteria,
            MissionConstraints constraints) {
        public CreateMission {
            idempotencyKey = MissionValues.text(idempotencyKey, "idempotencyKey", 128);
            ownerScope = MissionValues.text(ownerScope, "ownerScope", 256);
            conversationId = MissionValues.text(conversationId, "conversationId", 256);
            objective = MissionValues.text(objective, "objective", 8_000);
            acceptanceCriteria = MissionValues.texts(acceptanceCriteria, "acceptanceCriteria", 20, 1_000);
            constraints = Objects.requireNonNull(constraints);
        }
    }

    public record ReplaceMissionPlan(
            String idempotencyKey, String ownerScope, String missionId, long expectedVersion, List<MissionTask> tasks) {
        public ReplaceMissionPlan {
            idempotencyKey = MissionValues.text(idempotencyKey, "idempotencyKey", 128);
            ownerScope = MissionValues.text(ownerScope, "ownerScope", 256);
            missionId = MissionValues.text(missionId, "missionId", 256);
            if (expectedVersion < 0)
                throw new MissionException("MISSION_REVISION_STALE", "expectedVersion must not be negative");
            tasks = List.copyOf(Objects.requireNonNull(tasks));
        }
    }

    public record RegenerateMissionPlan(
            String idempotencyKey, String ownerScope, String missionId, long expectedVersion) {
        public RegenerateMissionPlan {
            idempotencyKey = MissionValues.text(idempotencyKey, "idempotencyKey", 128);
            ownerScope = MissionValues.text(ownerScope, "ownerScope", 256);
            missionId = MissionValues.text(missionId, "missionId", 256);
            if (expectedVersion < 0) {
                throw new MissionException("MISSION_REVISION_STALE", "expectedVersion must not be negative");
            }
        }
    }

    public record ChangeMission(String idempotencyKey, String ownerScope, String missionId, long expectedVersion) {
        public ChangeMission {
            idempotencyKey = MissionValues.text(idempotencyKey, "idempotencyKey", 128);
            ownerScope = MissionValues.text(ownerScope, "ownerScope", 256);
            missionId = MissionValues.text(missionId, "missionId", 256);
            if (expectedVersion < 0)
                throw new MissionException("MISSION_REVISION_STALE", "expectedVersion must not be negative");
        }
    }
}

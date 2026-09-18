package io.haifa.agent.personalassistant.application.mission;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    private final MissionExecutionStore execution;
    private final Map<String, String> skillBindingReferences;
    private final MissionAdmission admission;

    public MissionApplicationService(
            MissionStore store,
            MissionUnitOfWork unitOfWork,
            MissionPlanner planner,
            MissionPlanValidator validator,
            MissionIdGenerator ids,
            Clock clock) {
        this(
                store,
                unitOfWork,
                planner,
                validator,
                ids,
                clock,
                MissionExecutionStore.unavailable(),
                Map.of(),
                MissionAdmission.allowAll());
    }

    public MissionApplicationService(
            MissionStore store,
            MissionUnitOfWork unitOfWork,
            MissionPlanner planner,
            MissionPlanValidator validator,
            MissionIdGenerator ids,
            Clock clock,
            MissionExecutionStore execution) {
        this(store, unitOfWork, planner, validator, ids, clock, execution, Map.of(), MissionAdmission.allowAll());
    }

    public MissionApplicationService(
            MissionStore store,
            MissionUnitOfWork unitOfWork,
            MissionPlanner planner,
            MissionPlanValidator validator,
            MissionIdGenerator ids,
            Clock clock,
            MissionExecutionStore execution,
            Map<String, String> skillBindingReferences) {
        this(
                store,
                unitOfWork,
                planner,
                validator,
                ids,
                clock,
                execution,
                skillBindingReferences,
                MissionAdmission.allowAll());
    }

    public MissionApplicationService(
            MissionStore store,
            MissionUnitOfWork unitOfWork,
            MissionPlanner planner,
            MissionPlanValidator validator,
            MissionIdGenerator ids,
            Clock clock,
            MissionExecutionStore execution,
            Map<String, String> skillBindingReferences,
            MissionAdmission admission) {
        this.store = Objects.requireNonNull(store);
        this.unitOfWork = Objects.requireNonNull(unitOfWork);
        this.planner = Objects.requireNonNull(planner);
        this.validator = Objects.requireNonNull(validator);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
        this.execution = Objects.requireNonNull(execution);
        this.skillBindingReferences = Map.copyOf(skillBindingReferences);
        this.admission = Objects.requireNonNull(admission);
    }

    public MissionSnapshot create(CreateMission command) {
        Objects.requireNonNull(command);
        Instant now = now();
        Optional<ResearchBrief> frozenResearchBrief = ResearchTimeRangeFreezer.freeze(command.researchBrief(), now);
        String requestDigest = MissionValues.digest(
                command.conversationId(),
                command.modelBinding().modelId(),
                command.modelBinding().configurationDigest(),
                command.objective(),
                String.join("\u0000", command.acceptanceCriteria()),
                command.defaultDeadlineApplied()
                        ? new MissionConstraints(
                                        command.constraints().maxTasks(),
                                        command.constraints().maxDependencyDepth(),
                                        Optional.empty())
                                .toString()
                        : command.constraints().toString(),
                command.mode().name(),
                command.researchBrief().map(Object::toString).orElse(""));
        String proposedMissionId = ids.nextId();
        MissionCommandBinding binding = unitOfWork.execute(() -> {
            MissionCommandReservation reservation = store.reserveCommand(new MissionCommandBinding(
                    command.ownerScope(), "create", command.idempotencyKey(), requestDigest, proposedMissionId, now));
            MissionCommandBinding reserved = reservation.binding();
            if (reservation.created()) admission.requireAdmission();
            if (store.find(reserved.missionId(), command.ownerScope()).isEmpty()) {
                store.insert(PersonalMission.create(
                        reserved.missionId(),
                        command.conversationId(),
                        command.ownerScope(),
                        command.objective(),
                        command.acceptanceCriteria(),
                        command.constraints(),
                        command.mode() == MissionMode.DEEP_RESEARCH ? Optional.of("deep-research") : Optional.empty(),
                        command.mode() == MissionMode.DEEP_RESEARCH
                                ? Optional.of(requireSkillBinding("deep-research"))
                                : Optional.empty(),
                        command.mode(),
                        frozenResearchBrief,
                        command.modelBinding(),
                        now));
            }
            return reserved;
        });
        PersonalMission mission = unitOfWork.execute(() -> require(binding.missionId(), command.ownerScope()));
        if (mission.state() == MissionState.PLANNING) {
            try {
                MissionPlanner.PlanningResult planned = planner.plan(new MissionPlanner.PlanningRequest(
                        mission.missionId(),
                        mission.modelBinding(),
                        mission.objective(),
                        mission.acceptanceCriteria(),
                        mission.constraints(),
                        mission.persistence().revisions().size() + 1,
                        mission.mode(),
                        mission.researchBrief()));
                validatePlannerSchema(planned);
                mission.proposePlan(
                        planned.tasks(), planned.plannerSessionId(), planned.plannerRunId(), validator, now());
                long expected = mission.version() - 1;
                unitOfWork.execute(() -> {
                    PersonalMission current = require(binding.missionId(), command.ownerScope());
                    if (current.state() == MissionState.PLANNING) store.save(mission, expected);
                    return null;
                });
            } catch (RuntimeException failure) {
                failPlanning(binding.missionId(), command.ownerScope(), planningFailureCode(failure));
                throw failure;
            }
        }
        return unitOfWork.execute(() -> snapshot(require(binding.missionId(), command.ownerScope())));
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
            if (!reservation.created()) return snapshot(mission);
            if (mission.version() != command.expectedVersion()) {
                throw new MissionException("MISSION_REVISION_STALE", "Mission revision is stale");
            }
            long expected = mission.version();
            mission.proposePlan(command.tasks(), Optional.empty(), Optional.empty(), validator, now());
            store.save(mission, expected);
            return snapshot(mission);
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
        if (!reservation.execute()) return snapshot(reservation.mission());

        PersonalMission mission = reservation.mission();
        MissionPlanner.PlanningResult planned = planner.plan(new MissionPlanner.PlanningRequest(
                mission.missionId(),
                mission.modelBinding(),
                mission.objective(),
                mission.acceptanceCriteria(),
                mission.constraints(),
                mission.persistence().revisions().size() + 1,
                mission.mode(),
                mission.researchBrief()));
        validatePlannerSchema(planned);
        mission.proposePlan(planned.tasks(), planned.plannerSessionId(), planned.plannerRunId(), validator, now());
        return unitOfWork.execute(() -> {
            PersonalMission current = require(command.missionId(), command.ownerScope());
            if (current.version() != command.expectedVersion()) {
                return snapshot(current);
            }
            store.save(mission, command.expectedVersion());
            return snapshot(mission);
        });
    }

    public MissionSnapshot confirm(ChangeMission command) {
        return change(command, "confirm", mission -> mission.confirm(now()));
    }

    public MissionSnapshot cancel(ChangeMission command) {
        return change(command, "cancel", mission -> {
            mission.cancel(now());
            execution.cancelMission(mission.missionId(), now());
        });
    }

    public Optional<MissionSnapshot> find(String missionId, String ownerScope) {
        return unitOfWork.execute(() -> store.find(missionId, ownerScope).map(this::snapshot));
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
                .map(this::snapshot)
                .toList());
    }

    public MissionSnapshot retry(RetryMissionTask command) {
        Objects.requireNonNull(command);
        String digest =
                MissionValues.digest(command.missionId(), command.taskId(), Long.toString(command.expectedVersion()));
        return unitOfWork.execute(() -> {
            PersonalMission mission = require(command.missionId(), command.ownerScope());
            MissionCommandReservation reservation = store.reserveCommand(new MissionCommandBinding(
                    command.ownerScope(), "retry-task", command.idempotencyKey(), digest, command.missionId(), now()));
            if (!reservation.created()) return snapshot(mission);
            if (mission.version() != command.expectedVersion()) {
                throw new MissionException("MISSION_REVISION_STALE", "Mission revision is stale");
            }
            execution.retryBlocked(command.missionId(), command.ownerScope(), command.taskId(), now());
            return snapshot(require(command.missionId(), command.ownerScope()));
        });
    }

    private MissionSnapshot change(
            ChangeMission command, String operation, java.util.function.Consumer<PersonalMission> behavior) {
        Objects.requireNonNull(command);
        String digest = MissionValues.digest(command.missionId(), Long.toString(command.expectedVersion()));
        return unitOfWork.execute(() -> {
            PersonalMission mission = require(command.missionId(), command.ownerScope());
            MissionCommandReservation reservation = store.reserveCommand(new MissionCommandBinding(
                    command.ownerScope(), operation, command.idempotencyKey(), digest, command.missionId(), now()));
            if (!reservation.created()) return snapshot(mission);
            if (mission.version() != command.expectedVersion()) {
                throw new MissionException("MISSION_REVISION_STALE", "Mission revision is stale");
            }
            long expected = mission.version();
            behavior.accept(mission);
            store.save(mission, expected);
            return snapshot(mission);
        });
    }

    private MissionSnapshot snapshot(PersonalMission mission) {
        return mission.snapshot().withExecution(execution.snapshot(mission.missionId()));
    }

    private PersonalMission require(String missionId, String ownerScope) {
        return store.find(missionId, ownerScope)
                .orElseThrow(() -> new MissionException("MISSION_NOT_FOUND", "Mission is unavailable"));
    }

    private void failPlanning(String missionId, String ownerScope, String failureCode) {
        unitOfWork.execute(() -> {
            PersonalMission current = require(missionId, ownerScope);
            if (current.state() == MissionState.PLANNING) {
                long expected = current.version();
                current.failPlanning(failureCode, now());
                store.save(current, expected);
            }
            return null;
        });
    }

    private static String planningFailureCode(RuntimeException failure) {
        return failure instanceof MissionException missionFailure ? missionFailure.code() : "MISSION_PLANNER_FAILED";
    }

    private Instant now() {
        return Instant.ofEpochMilli(clock.instant().toEpochMilli());
    }

    private String requireSkillBinding(String alias) {
        return Optional.ofNullable(skillBindingReferences.get(alias))
                .orElseThrow(() -> new MissionException(
                        "MISSION_SKILL_BINDING_UNAVAILABLE", "Mission Skill binding is unavailable"));
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
            MissionModelBinding modelBinding,
            String objective,
            List<String> acceptanceCriteria,
            MissionConstraints constraints,
            MissionMode mode,
            Optional<ResearchBrief> researchBrief,
            boolean defaultDeadlineApplied) {
        public CreateMission {
            idempotencyKey = MissionValues.text(idempotencyKey, "idempotencyKey", 128);
            ownerScope = MissionValues.text(ownerScope, "ownerScope", 256);
            conversationId = MissionValues.text(conversationId, "conversationId", 256);
            modelBinding = Objects.requireNonNull(modelBinding);
            objective = MissionValues.text(objective, "objective", 8_000);
            acceptanceCriteria = MissionValues.texts(acceptanceCriteria, "acceptanceCriteria", 20, 1_000);
            constraints = Objects.requireNonNull(constraints);
            mode = Objects.requireNonNull(mode);
            researchBrief = Objects.requireNonNull(researchBrief);
            if (mode == MissionMode.DEEP_RESEARCH && researchBrief.isEmpty()) {
                throw new MissionException("MISSION_RESEARCH_BRIEF_REQUIRED", "Deep Research requires a brief");
            }
            if (mode == MissionMode.STANDARD && researchBrief.isPresent()) {
                throw new MissionException("MISSION_RESEARCH_BRIEF_FORBIDDEN", "Standard Mission cannot carry a brief");
            }
            if (defaultDeadlineApplied && constraints.deadlineAt().isEmpty()) {
                throw new MissionException(
                        "MISSION_DEADLINE_INVALID", "A defaulted Mission deadline must be materialized");
            }
        }

        public CreateMission(
                String idempotencyKey,
                String ownerScope,
                String conversationId,
                MissionModelBinding modelBinding,
                String objective,
                List<String> acceptanceCriteria,
                MissionConstraints constraints) {
            this(
                    idempotencyKey,
                    ownerScope,
                    conversationId,
                    modelBinding,
                    objective,
                    acceptanceCriteria,
                    constraints,
                    MissionMode.STANDARD,
                    Optional.empty(),
                    false);
        }

        public CreateMission(
                String idempotencyKey,
                String ownerScope,
                String conversationId,
                String objective,
                List<String> acceptanceCriteria,
                MissionConstraints constraints,
                MissionMode mode,
                Optional<ResearchBrief> researchBrief) {
            this(
                    idempotencyKey,
                    ownerScope,
                    conversationId,
                    MissionModelBinding.legacyDefault(),
                    objective,
                    acceptanceCriteria,
                    constraints,
                    mode,
                    researchBrief,
                    false);
        }

        public CreateMission(
                String idempotencyKey,
                String ownerScope,
                String conversationId,
                String objective,
                List<String> acceptanceCriteria,
                MissionConstraints constraints) {
            this(
                    idempotencyKey,
                    ownerScope,
                    conversationId,
                    MissionModelBinding.legacyDefault(),
                    objective,
                    acceptanceCriteria,
                    constraints,
                    MissionMode.STANDARD,
                    Optional.empty(),
                    false);
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

    public record RetryMissionTask(
            String idempotencyKey, String ownerScope, String missionId, String taskId, long expectedVersion) {
        public RetryMissionTask {
            idempotencyKey = MissionValues.text(idempotencyKey, "idempotencyKey", 128);
            ownerScope = MissionValues.text(ownerScope, "ownerScope", 256);
            missionId = MissionValues.text(missionId, "missionId", 256);
            taskId = MissionValues.text(taskId, "taskId", 64);
            if (expectedVersion < 0) {
                throw new MissionException("MISSION_REVISION_STALE", "expectedVersion must not be negative");
            }
        }
    }
}

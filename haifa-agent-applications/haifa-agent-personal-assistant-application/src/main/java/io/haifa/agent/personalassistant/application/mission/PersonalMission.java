package io.haifa.agent.personalassistant.application.mission;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Product aggregate for a durable user-confirmed Mission. */
public final class PersonalMission {
    private final String missionId;
    private final String conversationId;
    private final String ownerScope;
    private final String objective;
    private final List<String> acceptanceCriteria;
    private final MissionConstraints constraints;
    private final Optional<String> selectedSkillId;
    private final Optional<String> selectedSkillBinding;
    private final MissionMode mode;
    private final Optional<ResearchBrief> researchBrief;
    private final Instant createdAt;
    private final Instant deadlineAt;
    private final List<MissionPlanRevision> revisions;
    private MissionState state;
    private Integer activePlanRevisionNo;
    private Integer confirmedPlanRevisionNo;
    private String failureCode;
    private Instant updatedAt;
    private Instant confirmedAt;
    private Instant finishedAt;
    private long version;

    private PersonalMission(Persistence value) {
        missionId = MissionValues.text(value.missionId(), "missionId", 256);
        conversationId = MissionValues.text(value.conversationId(), "conversationId", 256);
        ownerScope = MissionValues.text(value.ownerScope(), "ownerScope", 256);
        objective = MissionValues.text(value.objective(), "objective", 8_000);
        acceptanceCriteria = MissionValues.texts(value.acceptanceCriteria(), "acceptanceCriteria", 20, 1_000);
        constraints = Objects.requireNonNull(value.constraints());
        selectedSkillId = Objects.requireNonNull(value.selectedSkillId())
                .map(item -> MissionValues.text(item, "selectedSkillId", 128));
        selectedSkillBinding = Objects.requireNonNull(value.selectedSkillBinding())
                .map(item -> MissionValues.text(item, "selectedSkillBinding", 1_024));
        mode = Objects.requireNonNull(value.mode());
        researchBrief = Objects.requireNonNull(value.researchBrief());
        if (mode == MissionMode.DEEP_RESEARCH
                && (researchBrief.isEmpty()
                        || !selectedSkillId.orElse("").equals("deep-research")
                        || selectedSkillBinding.isEmpty())) {
            throw new MissionException("MISSION_PERSISTENCE_INVALID", "Deep Research binding is incomplete");
        }
        if (mode == MissionMode.STANDARD && researchBrief.isPresent()) {
            throw new MissionException("MISSION_PERSISTENCE_INVALID", "Standard Mission has a research brief");
        }
        state = Objects.requireNonNull(value.state());
        activePlanRevisionNo = value.activePlanRevisionNo().orElse(null);
        confirmedPlanRevisionNo = value.confirmedPlanRevisionNo().orElse(null);
        failureCode = value.failureCode().orElse(null);
        version = value.version();
        if (version < 0) throw new MissionException("MISSION_PERSISTENCE_INVALID", "version must not be negative");
        createdAt = MissionValues.millisecond(value.createdAt(), "createdAt");
        updatedAt = MissionValues.millisecond(value.updatedAt(), "updatedAt");
        confirmedAt = value.confirmedAt()
                .map(item -> MissionValues.millisecond(item, "confirmedAt"))
                .orElse(null);
        finishedAt = value.finishedAt()
                .map(item -> MissionValues.millisecond(item, "finishedAt"))
                .orElse(null);
        deadlineAt = constraints.deadlineAt().orElse(createdAt.plusSeconds(30 * 60L));
        revisions = new ArrayList<>(Objects.requireNonNull(value.revisions()));
        validatePersistedState();
    }

    public static PersonalMission create(
            String missionId,
            String conversationId,
            String ownerScope,
            String objective,
            List<String> acceptanceCriteria,
            MissionConstraints constraints,
            Optional<String> selectedSkillId,
            Instant at) {
        return create(
                missionId,
                conversationId,
                ownerScope,
                objective,
                acceptanceCriteria,
                constraints,
                selectedSkillId,
                Optional.empty(),
                MissionMode.STANDARD,
                Optional.empty(),
                at);
    }

    public static PersonalMission create(
            String missionId,
            String conversationId,
            String ownerScope,
            String objective,
            List<String> acceptanceCriteria,
            MissionConstraints constraints,
            Optional<String> selectedSkillId,
            Optional<String> selectedSkillBinding,
            MissionMode mode,
            Optional<ResearchBrief> researchBrief,
            Instant at) {
        Instant now = MissionValues.millisecond(at, "at");
        return new PersonalMission(new Persistence(
                missionId,
                conversationId,
                ownerScope,
                objective,
                acceptanceCriteria,
                constraints,
                selectedSkillId,
                selectedSkillBinding,
                mode,
                researchBrief,
                MissionState.PLANNING,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                now,
                now,
                Optional.empty(),
                Optional.empty(),
                List.of()));
    }

    public static PersonalMission reconstitute(Persistence value) {
        return new PersonalMission(Objects.requireNonNull(value));
    }

    public void proposePlan(
            List<MissionTask> tasks,
            Optional<String> plannerSessionId,
            Optional<String> plannerRunId,
            MissionPlanValidator validator,
            Instant at) {
        requirePlanMutable();
        List<MissionTask> ordered = validator.validate(tasks, constraints).stream()
                .map(task -> task.withState(MissionTaskState.PLANNED))
                .toList();
        int revisionNo = revisions.size() + 1;
        String digest = MissionValues.digest(
                MissionPlanRevision.SCHEMA_ID, MissionPlanRevision.SCHEMA_VERSION, ordered.toString());
        revisions.add(new MissionPlanRevision(
                revisionNo,
                MissionPlanRevision.SCHEMA_ID,
                MissionPlanRevision.SCHEMA_VERSION,
                ordered,
                digest,
                plannerSessionId,
                plannerRunId,
                at,
                Optional.empty()));
        activePlanRevisionNo = revisionNo;
        state = MissionState.WAITING_CONFIRMATION;
        changed(at);
    }

    public void confirm(Instant at) {
        if (state == MissionState.CANCELLED)
            throw new MissionException("MISSION_CANCELLED", "cancelled Mission cannot be confirmed");
        if (state != MissionState.WAITING_CONFIRMATION || activePlanRevisionNo == null) {
            throw new MissionException("MISSION_STATE_CONFLICT", "Mission is not waiting for confirmation");
        }
        int index = activePlanRevisionNo - 1;
        revisions.set(index, revisions.get(index).confirm(at));
        confirmedPlanRevisionNo = activePlanRevisionNo;
        confirmedAt = MissionValues.millisecond(at, "confirmedAt");
        state = MissionState.RUNNING;
        changed(at);
    }

    public void cancel(Instant at) {
        if (state.terminal()) {
            if (state == MissionState.CANCELLED) return;
            throw new MissionException("MISSION_STATE_CONFLICT", "terminal Mission cannot be cancelled");
        }
        state = MissionState.CANCELLED;
        finishedAt = MissionValues.millisecond(at, "finishedAt");
        changed(at);
    }

    public Persistence persistence() {
        return new Persistence(
                missionId,
                conversationId,
                ownerScope,
                objective,
                acceptanceCriteria,
                constraints,
                selectedSkillId,
                selectedSkillBinding,
                mode,
                researchBrief,
                state,
                Optional.ofNullable(activePlanRevisionNo),
                Optional.ofNullable(confirmedPlanRevisionNo),
                Optional.ofNullable(failureCode),
                version,
                createdAt,
                updatedAt,
                Optional.ofNullable(confirmedAt),
                Optional.ofNullable(finishedAt),
                revisions);
    }

    public MissionSnapshot snapshot() {
        return MissionSnapshot.from(this);
    }

    private void requirePlanMutable() {
        if (confirmedPlanRevisionNo != null || state == MissionState.RUNNING || state.terminal()) {
            throw new MissionException("MISSION_PLAN_FROZEN", "confirmed or terminal Mission plan cannot be replaced");
        }
        if (state != MissionState.PLANNING && state != MissionState.WAITING_CONFIRMATION) {
            throw new MissionException(
                    "MISSION_STATE_CONFLICT", "Mission plan cannot be replaced in its current state");
        }
    }

    private void changed(Instant at) {
        Instant normalized = MissionValues.millisecond(at, "at");
        if (normalized.isBefore(updatedAt))
            throw new MissionException("MISSION_TIME_INVALID", "Mission time moved backwards");
        updatedAt = normalized;
        version++;
    }

    private void validatePersistedState() {
        if (updatedAt.isBefore(createdAt))
            throw new MissionException("MISSION_PERSISTENCE_INVALID", "updatedAt precedes createdAt");
        if (activePlanRevisionNo != null
                && revisions.stream().noneMatch(value -> value.revisionNo() == activePlanRevisionNo)) {
            throw new MissionException("MISSION_PERSISTENCE_INVALID", "active plan revision is unavailable");
        }
        if (confirmedPlanRevisionNo != null) {
            MissionPlanRevision confirmed = revisions.stream()
                    .filter(value -> value.revisionNo() == confirmedPlanRevisionNo)
                    .findFirst()
                    .orElseThrow(
                            () -> new MissionException("MISSION_PERSISTENCE_INVALID", "confirmed plan is unavailable"));
            if (confirmed.confirmedAt().isEmpty()) {
                throw new MissionException("MISSION_PERSISTENCE_INVALID", "confirmed plan timestamp is unavailable");
            }
        }
        if (state.terminal() != (finishedAt != null)) {
            throw new MissionException("MISSION_PERSISTENCE_INVALID", "terminal state and finishedAt disagree");
        }
    }

    String missionId() {
        return missionId;
    }

    String conversationId() {
        return conversationId;
    }

    String ownerScope() {
        return ownerScope;
    }

    String objective() {
        return objective;
    }

    List<String> acceptanceCriteria() {
        return acceptanceCriteria;
    }

    MissionConstraints constraints() {
        return constraints;
    }

    Optional<String> selectedSkillId() {
        return selectedSkillId;
    }

    Optional<String> selectedSkillBinding() {
        return selectedSkillBinding;
    }

    MissionMode mode() {
        return mode;
    }

    Optional<ResearchBrief> researchBrief() {
        return researchBrief;
    }

    MissionState state() {
        return state;
    }

    Optional<MissionPlanRevision> activePlan() {
        if (activePlanRevisionNo == null) return Optional.empty();
        return revisions.stream()
                .filter(value -> value.revisionNo() == activePlanRevisionNo)
                .findFirst();
    }

    long version() {
        return version;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant updatedAt() {
        return updatedAt;
    }

    Optional<Instant> confirmedAt() {
        return Optional.ofNullable(confirmedAt);
    }

    Optional<Instant> finishedAt() {
        return Optional.ofNullable(finishedAt);
    }

    public record Persistence(
            String missionId,
            String conversationId,
            String ownerScope,
            String objective,
            List<String> acceptanceCriteria,
            MissionConstraints constraints,
            Optional<String> selectedSkillId,
            Optional<String> selectedSkillBinding,
            MissionMode mode,
            Optional<ResearchBrief> researchBrief,
            MissionState state,
            Optional<Integer> activePlanRevisionNo,
            Optional<Integer> confirmedPlanRevisionNo,
            Optional<String> failureCode,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Optional<Instant> confirmedAt,
            Optional<Instant> finishedAt,
            List<MissionPlanRevision> revisions) {
        public Persistence {
            acceptanceCriteria = List.copyOf(acceptanceCriteria);
            revisions = List.copyOf(revisions);
        }

        public Persistence(
                String missionId,
                String conversationId,
                String ownerScope,
                String objective,
                List<String> acceptanceCriteria,
                MissionConstraints constraints,
                Optional<String> selectedSkillId,
                MissionState state,
                Optional<Integer> activePlanRevisionNo,
                Optional<Integer> confirmedPlanRevisionNo,
                Optional<String> failureCode,
                long version,
                Instant createdAt,
                Instant updatedAt,
                Optional<Instant> confirmedAt,
                Optional<Instant> finishedAt,
                List<MissionPlanRevision> revisions) {
            this(
                    missionId,
                    conversationId,
                    ownerScope,
                    objective,
                    acceptanceCriteria,
                    constraints,
                    selectedSkillId,
                    Optional.empty(),
                    MissionMode.STANDARD,
                    Optional.empty(),
                    state,
                    activePlanRevisionNo,
                    confirmedPlanRevisionNo,
                    failureCode,
                    version,
                    createdAt,
                    updatedAt,
                    confirmedAt,
                    finishedAt,
                    revisions);
        }
    }
}

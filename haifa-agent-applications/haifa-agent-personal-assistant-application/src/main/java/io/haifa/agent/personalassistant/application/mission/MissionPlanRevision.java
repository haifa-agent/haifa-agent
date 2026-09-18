package io.haifa.agent.personalassistant.application.mission;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Versioned whole-plan replacement. A confirmed revision is immutable. */
public record MissionPlanRevision(
        int revisionNo,
        String schemaId,
        String schemaVersion,
        List<MissionTask> tasks,
        String planDigest,
        Optional<String> plannerSessionId,
        Optional<String> plannerRunId,
        Instant createdAt,
        Optional<Instant> confirmedAt) {
    public static final String SCHEMA_ID = "pa.mission-plan";
    public static final String SCHEMA_VERSION = "v1";

    public MissionPlanRevision {
        if (revisionNo < 1) throw new MissionException("MISSION_PLAN_INVALID", "revisionNo must be positive");
        schemaId = MissionValues.text(schemaId, "schemaId", 128);
        schemaVersion = MissionValues.text(schemaVersion, "schemaVersion", 32);
        if (!SCHEMA_ID.equals(schemaId) || !SCHEMA_VERSION.equals(schemaVersion)) {
            throw new MissionException("MISSION_PLAN_SCHEMA_UNSUPPORTED", "unsupported Mission plan schema");
        }
        tasks = List.copyOf(Objects.requireNonNull(tasks, "tasks must not be null"));
        planDigest = MissionValues.text(planDigest, "planDigest", 80);
        plannerSessionId = optionalText(plannerSessionId, "plannerSessionId");
        plannerRunId = optionalText(plannerRunId, "plannerRunId");
        createdAt = MissionValues.millisecond(createdAt, "createdAt");
        confirmedAt = Objects.requireNonNull(confirmedAt, "confirmedAt must not be null")
                .map(value -> MissionValues.millisecond(value, "confirmedAt"));
    }

    public MissionPlanRevision confirm(Instant at) {
        if (confirmedAt.isPresent()) throw new MissionException("MISSION_PLAN_FROZEN", "plan is already confirmed");
        return new MissionPlanRevision(
                revisionNo,
                schemaId,
                schemaVersion,
                tasks,
                planDigest,
                plannerSessionId,
                plannerRunId,
                createdAt,
                Optional.of(MissionValues.millisecond(at, "confirmedAt")));
    }

    private static Optional<String> optionalText(Optional<String> value, String field) {
        return Objects.requireNonNull(value, field + " must not be null")
                .map(item -> MissionValues.text(item, field, 256));
    }
}

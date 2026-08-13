package io.haifa.agent.personalassistant.application.mission;

/** Minimal standard-Run boundary used by the Server's strict structured plan decoder. */
public interface MissionRuntimeAccess {
    PlannerRunResult runPlanner(MissionPlanner.PlanningRequest request);

    default PlannerRunResult repairPlanner(
            MissionPlanner.PlanningRequest request,
            PlannerRunResult invalidRun,
            String violationCode,
            String violationMessage,
            int repairAttemptNo) {
        throw new MissionException("MISSION_PLAN_REPAIR_UNAVAILABLE", "Mission Plan repair is unavailable");
    }

    default TaskRunBinding startTask(MissionDispatchIntent intent) {
        throw new MissionException("MISSION_EXECUTION_UNAVAILABLE", "Mission Task execution is unavailable");
    }

    default TaskRunObservation observeTask(String runId) {
        return new TaskRunObservation(
                runId,
                TaskRunState.OUTCOME_UNKNOWN,
                java.util.Optional.empty(),
                java.util.Optional.of("MISSION_EXECUTION_UNAVAILABLE"));
    }

    default void cancelTask(String runId) {}

    default SynthesisRunResult runSynthesis(MissionSynthesisIntent intent) {
        throw new MissionException("MISSION_SYNTHESIS_UNAVAILABLE", "Mission Synthesis is unavailable");
    }

    default SynthesisRunResult reviseSynthesis(
            MissionSynthesisIntent intent,
            SynthesisRunResult previous,
            ReportQualityGate.Result quality,
            int revisionAttempt) {
        throw new MissionException(
                "MISSION_SYNTHESIS_REVISION_UNAVAILABLE", "Mission Synthesis revision is unavailable");
    }

    default SynthesisRunResult repairSynthesis(
            MissionSynthesisIntent intent,
            SynthesisRunResult invalid,
            String violationCode,
            String violationMessage,
            int repairAttemptNo) {
        throw new MissionException("MISSION_SYNTHESIS_REPAIR_UNAVAILABLE", "Mission Synthesis repair is unavailable");
    }

    default void appendFinalMessage(
            String conversationId, String missionId, String synthesisRunId, String finalMessage) {}

    record PlannerRunResult(String sessionId, String runId, String structuredOutput, MissionUsage usage) {
        public PlannerRunResult(String sessionId, String runId, String structuredOutput) {
            this(sessionId, runId, structuredOutput, MissionUsage.NONE);
        }

        public PlannerRunResult {
            usage = java.util.Objects.requireNonNull(usage);
        }
    }

    record TaskRunBinding(String sessionId, String runId) {}

    record SynthesisRunResult(
            String sessionId,
            String runId,
            String structuredOutput,
            MissionUsage usage,
            java.util.List<String> degradationReasons,
            int qualityGateRevisionCount) {
        public SynthesisRunResult(String sessionId, String runId, String structuredOutput) {
            this(sessionId, runId, structuredOutput, MissionUsage.NONE, java.util.List.of(), 0);
        }

        public SynthesisRunResult(String sessionId, String runId, String structuredOutput, MissionUsage usage) {
            this(sessionId, runId, structuredOutput, usage, java.util.List.of(), 0);
        }

        public SynthesisRunResult(
                String sessionId,
                String runId,
                String structuredOutput,
                MissionUsage usage,
                java.util.List<String> degradationReasons) {
            this(sessionId, runId, structuredOutput, usage, degradationReasons, 0);
        }

        public SynthesisRunResult {
            usage = java.util.Objects.requireNonNull(usage);
            degradationReasons = java.util.List.copyOf(degradationReasons);
            if (qualityGateRevisionCount < 0 || qualityGateRevisionCount > 2) {
                throw new IllegalArgumentException("Quality Gate revision count is invalid");
            }
        }
    }

    record TaskRunObservation(
            String runId,
            TaskRunState state,
            java.util.Optional<String> result,
            java.util.Optional<String> failureCode,
            MissionUsage usage) {
        public TaskRunObservation(
                String runId,
                TaskRunState state,
                java.util.Optional<String> result,
                java.util.Optional<String> failureCode) {
            this(runId, state, result, failureCode, MissionUsage.NONE);
        }

        public TaskRunObservation {
            usage = java.util.Objects.requireNonNull(usage);
        }
    }

    enum TaskRunState {
        ACTIVE,
        WAITING_USER,
        COMPLETED,
        FAILED,
        CANCELLED,
        OUTCOME_UNKNOWN
    }
}

package io.haifa.agent.personalassistant.application.mission;

/** Product delivery boundary: deterministic validation plus immutable Artifact publication. */
@FunctionalInterface
public interface MissionResultPublisher {
    MissionPublishedResult publish(MissionSynthesisIntent intent, MissionRuntimeAccess.SynthesisRunResult synthesis);

    default ReportQualityGate.Result evaluate(
            MissionSynthesisIntent intent, MissionRuntimeAccess.SynthesisRunResult synthesis) {
        return ReportQualityGate.Result.passedResult();
    }

    default MissionPublishedResult publishDegraded(
            MissionSynthesisIntent intent,
            MissionRuntimeAccess.SynthesisRunResult synthesis,
            ReportQualityGate.Result quality) {
        throw new MissionException("MISSION_REPORT_QUALITY_FAILED", "No quality-approved report is available");
    }

    static MissionResultPublisher unavailable() {
        return (intent, synthesis) -> new MissionPublishedResult(
                "unavailable",
                java.util.List.of(),
                java.util.List.of(),
                synthesis.structuredOutput(),
                synthesis.structuredOutput(),
                "COMPLETE");
    }
}

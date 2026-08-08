package io.haifa.agent.personalassistant.application.mission;

/** Product delivery boundary: deterministic validation plus immutable Artifact publication. */
@FunctionalInterface
public interface MissionResultPublisher {
    MissionPublishedResult publish(MissionSynthesisIntent intent, MissionRuntimeAccess.SynthesisRunResult synthesis);

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

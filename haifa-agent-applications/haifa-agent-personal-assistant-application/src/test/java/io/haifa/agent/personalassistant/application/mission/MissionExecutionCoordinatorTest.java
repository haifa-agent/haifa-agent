package io.haifa.agent.personalassistant.application.mission;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.personalassistant.application.runtime.SdkMissionRuntimeAccess;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MissionExecutionCoordinatorTest {
    @Test
    void doesNotRepeatCompletedResearchWhenOnlyNormalizationFails() {
        assertThat(MissionExecutionCoordinator.retryableTaskFailure("MISSION_TASK_NORMALIZATION_FAILED"))
                .isFalse();
        assertThat(MissionExecutionCoordinator.retryableTaskFailure("MODEL_RESPONSE_INVALID"))
                .isTrue();
    }

    @Test
    void fallsBackOnceWhenCompletedResearchSynthesisViolatesTheDeliverySchema() {
        var intent = new MissionSynthesisIntent(
                "mission-1",
                "conversation-1",
                "owner-1",
                MissionMode.DEEP_RESEARCH,
                "Research Jingning hydropower",
                List.of(SdkMissionRuntimeAccess.conservativeResearchTaskResult(
                        "Research history", "Historical notes", "MODEL_RESPONSE_INVALID")),
                List.of());
        var modelSynthesis = new MissionRuntimeAccess.SynthesisRunResult(
                "session-1",
                "run-1",
                "{\"directAnswer\":\"Integrated research answer\",\"invalid\":true}",
                new MissionUsage(100, 1, 0));
        var calls = new AtomicInteger();
        MissionResultPublisher publisher = (request, synthesis) -> {
            if (calls.getAndIncrement() == 0) {
                throw new MissionException("MISSION_RESULT_SCHEMA_INVALID", "invalid model synthesis");
            }
            return new MissionPublishedResult(
                    "artifact-1",
                    List.of("artifact-1"),
                    List.of(),
                    synthesis.structuredOutput(),
                    "fallback report",
                    "COMPLETE");
        };

        var delivery = MissionExecutionCoordinator.publishWithResearchFallback(publisher, intent, modelSynthesis);

        assertThat(calls).hasValue(2);
        assertThat(delivery.synthesis().structuredOutput())
                .contains("pa.research-final-result/v1", "MISSION_RESULT_SCHEMA_INVALID", "Integrated research answer");
        assertThat(delivery.synthesis().usage()).isEqualTo(modelSynthesis.usage());
        assertThat(delivery.published().finalMessage()).isEqualTo("fallback report");
    }
}

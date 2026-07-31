package io.haifa.agent.personalassistant.application.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PersonalQuestionRecommenderTest {
    @Test
    void returnsTwoOrThreeBoundedQuestionsAndCarriesTheExclusionPolicy() {
        AtomicReference<AgentChatRequest> observed = new AtomicReference<>();
        var recommender = recommender(
                """
                {"questions":["下一步如何拆分实施阶段？","有哪些主要风险需要提前验证？","能否给出一个验收清单？","ignored"]}
                """,
                observed);

        List<String> questions = recommender.recommend(
                new AgentRunId("run-1"),
                List.of(
                        new PersonalQuestionRecommender.RecommendationTurn("USER", "请设计一个迁移方案"),
                        new PersonalQuestionRecommender.RecommendationTurn("ASSISTANT", "可以分三阶段迁移。")));

        assertThat(questions).containsExactly("下一步如何拆分实施阶段？", "有哪些主要风险需要提前验证？", "能否给出一个验收清单？");
        AgentChatRequest request = observed.get();
        assertThat(request.maxOutputTokens()).isEqualTo(256);
        assertThat(request.tools()).isEmpty();
        assertThat(request.messages().getFirst().content())
                .contains("quick factual Q&A", "arithmetic", "{\"questions\":[]}");
        assertThat(request.options()).containsEntry("response_format", Map.of("type", "json_object"));
    }

    @Test
    void acceptsAnEmptyListForQuickAnswersAndCalculations() {
        var recommender = recommender("{\"questions\":[]}", new AtomicReference<>());

        assertThat(recommender.recommend(
                        new AgentRunId("run-calculation"),
                        List.of(
                                new PersonalQuestionRecommender.RecommendationTurn("USER", "2 + 2 等于多少？"),
                                new PersonalQuestionRecommender.RecommendationTurn("ASSISTANT", "4"))))
                .isEmpty();
    }

    @Test
    void failsClosedForMalformedOrInsufficientModelOutput() {
        var recommender = recommender("{\"questions\":[\"只有一个问题？\"]}", new AtomicReference<>());

        assertThat(recommender.recommend(
                        new AgentRunId("run-malformed"),
                        List.of(
                                new PersonalQuestionRecommender.RecommendationTurn("USER", "解释迁移方案"),
                                new PersonalQuestionRecommender.RecommendationTurn("ASSISTANT", "说明如下"))))
                .isEmpty();
    }

    private static PersonalQuestionRecommender recommender(
            String responseContent, AtomicReference<AgentChatRequest> observed) {
        return new PersonalQuestionRecommender(
                request -> {
                    observed.set(request);
                    return new AgentChatResponse(
                            "recommendation-response",
                            "test-model",
                            responseContent,
                            List.of(),
                            ModelFinishReason.STOP,
                            ModelUsage.unpriced(100, 30),
                            "",
                            Map.of());
                },
                snapshot(),
                new ObjectMapper());
    }

    private static ResolvedModelSnapshot snapshot() {
        return ResolvedModelSnapshot.create(
                new ModelProviderId("test-provider"),
                "1.0.0",
                new ModelDefinitionId("test-model"),
                "1.0.0",
                "test-model",
                "test-adapter",
                "1.0.0",
                URI.create("https://example.test/v1"),
                new CredentialRef("env://TEST_KEY"),
                Set.of(ModelCapability.TEXT_CHAT),
                8_192,
                1_024,
                Map.of(),
                Map.of());
    }
}

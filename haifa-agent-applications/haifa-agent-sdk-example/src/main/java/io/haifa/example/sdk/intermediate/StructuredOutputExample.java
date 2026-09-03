package io.haifa.example.sdk.intermediate;

import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.starter.HaifaAgentStarter;
import io.haifa.example.sdk.support.DeterministicExampleSupport;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Returns a Java record only after Runtime validates and persists the frozen final-output schema. */
public final class StructuredOutputExample {
    private StructuredOutputExample() {}

    public static void main(String[] arguments) throws Exception {
        Map<String, Object> output = Map.of(
                "translations",
                Map.of(
                        "English", List.of("Hello", "Thank you", "Goodbye"),
                        "Japanese", List.of("こんにちは", "ありがとう", "さようなら"),
                        "French", List.of("Bonjour", "Merci", "Au revoir")));
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> new AgentChatResponse(
                "translation-response",
                request.model().providerModelId(),
                "{\"translations\":{\"English\":[\"Hello\",\"Thank you\",\"Goodbye\"],"
                        + "\"Japanese\":[\"こんにちは\",\"ありがとう\",\"さようなら\"],"
                        + "\"French\":[\"Bonjour\",\"Merci\",\"Au revoir\"]}}",
                List.of(),
                ModelFinishReason.STOP,
                ModelUsage.unpriced(4, 4),
                "",
                Map.of(),
                Optional.empty(),
                Optional.of(output));

        var request =
                new TranslationRequest("Chinese", List.of("你好", "谢谢", "再见"), List.of("English", "Japanese", "French"));
        var prompt = "Translate the following %s phrases %s into these languages: %s. "
                + "Return a JSON object where each key is a target language "
                + "and the value is the list of translated phrases in the same order."
                        .formatted(request.sourceLanguage(), request.phrases(), request.destLangs());

        try (var agent = HaifaAgentStarter.builder()
                .model(model, DeterministicExampleSupport.snapshot())
                .build()) {
            var response = agent.chat(prompt, TranslationResult.class).await();
            TranslationResult result = response.value();
            result.translations().forEach((language, phrases) -> System.out.println(language + ": " + phrases));
        }
    }

    public record TranslationRequest(String sourceLanguage, List<String> phrases, List<String> destLangs) {}

    public record TranslationResult(Map<String, List<String>> translations) {}
}

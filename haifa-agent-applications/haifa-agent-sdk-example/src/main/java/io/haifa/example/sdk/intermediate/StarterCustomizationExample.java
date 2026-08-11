package io.haifa.example.sdk.intermediate;

import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.starter.HaifaAgentStarter;
import io.haifa.example.sdk.support.DeterministicExampleSupport;
import java.time.Duration;

/** Applies trusted instructions, connection bounds, a model contribution, and an application Tool. */
public final class StarterCustomizationExample {
    private StarterCustomizationExample() {}

    public static void main(String[] arguments) throws Exception {
        try (var agent = HaifaAgentStarter.builder()
                .instructions("Answer concise support questions using application-owned capabilities.")
                .connectTimeout(Duration.ofSeconds(5))
                .model(
                        DeterministicExampleSupport.model("Support is available for this example."),
                        DeterministicExampleSupport.snapshot())
                .tool(new SupportLookupTool())
                .build()) {
            var conversation = agent.conversations()
                    .start(new StartConversationCommand(
                            "custom-starter-start", "Customized Starter", "What support is available?"));
            var completed = agent.runs().await(conversation.activeRunId().orElseThrow());
            System.out.println(completed.output().orElseThrow());
        }
    }

    public record SupportRequest(String topic) {}

    public record SupportResponse(String answer) {}

    private static final class SupportLookupTool implements JavaTool<SupportRequest, SupportResponse> {
        private static final JavaToolSpec<SupportRequest, SupportResponse> SPEC = JavaToolSpec.builder(
                        "support.lookup", SupportRequest.class, SupportResponse.class)
                .alias("support_lookup")
                .description("Return deterministic support information")
                .pure()
                .build();

        @Override
        public JavaToolSpec<SupportRequest, SupportResponse> spec() {
            return SPEC;
        }

        @Override
        public SupportResponse invoke(SupportRequest input, JavaToolContext context) {
            return new SupportResponse("Example support for " + input.topic());
        }
    }
}

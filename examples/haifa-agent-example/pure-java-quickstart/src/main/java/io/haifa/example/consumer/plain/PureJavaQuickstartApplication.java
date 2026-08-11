package io.haifa.example.consumer.plain;

import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.starter.HaifaAgentStarter;

/** Complete pure Java consumer application with one typed Tool. */
public final class PureJavaQuickstartApplication {
    private PureJavaQuickstartApplication() {}

    public static void main(String[] arguments) throws Exception {
        try (var agent = HaifaAgentStarter.builder()
                .instructions("Use weather_get for weather questions, then answer in one sentence.")
                .tool(new WeatherTool())
                .build()) {
            var conversation = agent.conversations()
                    .start(new StartConversationCommand(
                            "standalone-java-start", "Standalone Java", "What is the weather in Shanghai?"));
            var completed = agent.runs().await(conversation.activeRunId().orElseThrow());
            System.out.println(completed.output().orElseThrow());
        }
    }
}

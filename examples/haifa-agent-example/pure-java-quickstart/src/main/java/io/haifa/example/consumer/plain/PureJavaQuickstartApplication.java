package io.haifa.example.consumer.plain;

import io.haifa.agent.starter.HaifaAgentStarter;

/** Complete pure Java consumer application with one typed Tool. */
public final class PureJavaQuickstartApplication {
    private PureJavaQuickstartApplication() {}

    public static void main(String[] arguments) throws Exception {
        try (var agent = HaifaAgentStarter.builder()
                .name("standalone-weather-agent")
                .description("Standalone pure Java weather consumer")
                .instructions("Use weather_get for weather questions, then answer in one sentence.")
                .tool(new WeatherTool())
                .build()) {
            var response = agent.chat("What is the weather in Shanghai?").await();
            System.out.println(response.text());
        }
    }
}

package io.haifa.example.consumer.plain;

import io.haifa.agent.starter.HaifaAgentStarter;
import java.util.List;

/**
 * Complete pure Java consumer application demonstrating schema-validated structured Record output.
 */
public final class PureJavaStructuredOutputApplication {
    private PureJavaStructuredOutputApplication() {}

    public record WeatherForecastReport(
            String city,
            String condition,
            int temperatureCelsius,
            List<String> recommendations) {}

    public static void main(String[] arguments) throws Exception {
        try (var agent = HaifaAgentStarter.builder()
                .name("standalone-structured-agent")
                .instructions("Provide realistic weather analysis and practical recommendations.")
                .build()) {
            var prompt = "Generate a structured JSON weather report for Tokyo in Autumn.";
            var response = agent.chat(prompt, WeatherForecastReport.class).await();
            WeatherForecastReport report = response.value();

            System.out.println("City: " + report.city());
            System.out.println("Condition: " + report.condition());
            System.out.println("Temperature: " + report.temperatureCelsius() + "°C");
            System.out.println("Recommendations: " + report.recommendations());
        }
    }
}

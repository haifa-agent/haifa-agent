package io.haifa.example.consumer.plain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.haifa.agent.starter.HaifaAgentStarter;
import org.junit.jupiter.api.Test;

class PureJavaQuickstartApplicationTest {
    @Test
    void assemblesTheExternalConsumerWithoutCallingAProvider() {
        var tool = new WeatherTool();

        assertEquals("weather_get", tool.spec().alias().value());
        try (var agent = HaifaAgentStarter.builder()
                .credentialEnvironmentVariable("PATH")
                .tool(tool)
                .build()) {
            assertNotNull(agent.assembly());
        }
    }

    @Test
    void assemblesStreamingConsumerWithoutCallingAProvider() {
        try (var agent = HaifaAgentStarter.builder()
                .credentialEnvironmentVariable("PATH")
                .name("streaming-test-agent")
                .build()) {
            assertNotNull(agent.runs());
            assertNotNull(agent.conversations());
        }
    }

    @Test
    void validatesStructuredOutputRecordSchemaWithoutCallingAProvider() {
        var requirement = io.haifa.agent.sdk.internal.StructuredOutputRecords.requirement(
                PureJavaStructuredOutputApplication.WeatherForecastReport.class);
        assertNotNull(requirement);
        assertEquals("WeatherForecastReport", requirement.responseName());
        assertNotNull(requirement.jsonSchema());
    }
}

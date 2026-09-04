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

    @Test
    void assemblesVisionConsumerWithoutCallingAProvider() throws Exception {
        byte[] bytes = PureJavaVisionApplication.loadImageBytes();
        assertNotNull(bytes);
        assertEquals(24748, bytes.length);

        var imageStore = new io.haifa.agent.sdk.api.InMemoryImageStore();
        var imagePart = imageStore.store(bytes, "image/webp", "indoor-door-people.webp");
        assertNotNull(imagePart);
        assertEquals("image/webp", imagePart.mediaType());
        assertEquals("indoor-door-people.webp", imagePart.originalFilename());
        assertEquals(
                "sha256:b02eb0f560b43ffd898a094db0aa36d54959513f807fed35d032cafe946ffbf5",
                imagePart.sha256());

        var resolved = imageStore.resolve(imagePart);
        assertNotNull(resolved);
        assertEquals("image/webp", resolved.mediaType());
        assertEquals(bytes.length, resolved.bytes().length);

        try (var agent = HaifaAgentStarter.builder()
                .credentialEnvironmentVariable("PATH")
                .name("vision-test-agent")
                .defaultModel("deepseek-v4-flash-vision-exp")
                .modelImageResolver(imageStore)
                .build()) {
            assertNotNull(agent.assembly());
            assertNotNull(agent.runs());
        }
    }
}

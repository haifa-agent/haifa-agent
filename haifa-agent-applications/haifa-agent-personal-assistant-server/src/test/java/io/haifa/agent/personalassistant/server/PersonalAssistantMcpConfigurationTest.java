package io.haifa.agent.personalassistant.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.personalassistant.server.configuration.product.PersonalAssistantProperties;
import java.net.URI;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PersonalAssistantMcpConfigurationTest {
    @Test
    void acceptsExplicitLoopbackExternalUtilityAllowlist() {
        var mcp = new PersonalAssistantProperties.Mcp(
                "external",
                "127.0.0.1",
                20002,
                URI.create("http://127.0.0.1:20002/mcp"),
                Set.of("calculate", "time_now"),
                "personal_mcp",
                "haifa-utility",
                "Haifa Utility MCP");

        assertThat(mcp.mode()).isEqualTo("external");
        assertThat(mcp.allowedTools()).containsExactlyInAnyOrder("calculate", "time_now");
    }

    @Test
    void acceptsCompleteUtilityMcpV1Catalog() {
        Set<String> tools = Set.of(
                "location_search",
                "weather_current",
                "weather_forecast",
                "air_quality",
                "time_now",
                "time_convert",
                "currency_rate",
                "currency_convert",
                "holiday_list",
                "holiday_next",
                "workday_is_workday",
                "workday_add",
                "calculate",
                "unit_convert",
                "wikipedia_search",
                "wikipedia_summary",
                "microsoft_docs_search",
                "microsoft_docs_fetch",
                "microsoft_code_sample_search");

        var mcp = new PersonalAssistantProperties.Mcp(
                "external",
                "127.0.0.1",
                20002,
                URI.create("http://127.0.0.1:20002/mcp"),
                tools,
                "personal_mcp",
                "haifa-utility",
                "Haifa Utility MCP");

        assertThat(mcp.allowedTools()).containsExactlyInAnyOrderElementsOf(tools);
    }

    @Test
    void rejectsNonLoopbackExternalEndpoint() {
        assertThatThrownBy(() -> new PersonalAssistantProperties.Mcp(
                        "external",
                        "127.0.0.1",
                        20002,
                        URI.create("https://example.com/mcp"),
                        Set.of("calculate"),
                        "personal_mcp",
                        "haifa-utility",
                        "Haifa Utility MCP"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback HTTP");
    }

    @Test
    void embeddedModeCannotExpandBeyondEcho() {
        assertThatThrownBy(() -> new PersonalAssistantProperties.Mcp(
                        "embedded-echo",
                        "127.0.0.1",
                        20002,
                        URI.create("http://127.0.0.1:20002/mcp"),
                        Set.of("calculate"),
                        "personal_mcp",
                        "personal-local",
                        "Personal local utility"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only allows the echo Tool");
    }
}

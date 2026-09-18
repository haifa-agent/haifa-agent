package io.haifa.agent.personalassistant.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.personalassistant.server.configuration.product.PersonalAssistantProperties;
import java.net.URI;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PersonalAssistantMcpConfigurationTest {
    @Test
    void acceptsExplicitLoopbackExternalAllowlist() {
        var mcp = external(Set.of("calculate", "time_now"), false);

        assertThat(mcp.mode()).isEqualTo("external");
        assertThat(mcp.allowedTools()).containsExactlyInAnyOrder("calculate", "time_now");
        assertThat(mcp.required()).isFalse();
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

        var mcp = external(tools, true);

        assertThat(mcp.allowedTools()).containsExactlyInAnyOrderElementsOf(tools);
        assertThat(mcp.required()).isTrue();
    }

    @Test
    void rejectsNonLoopbackExternalEndpoint() {
        assertThatThrownBy(() -> new PersonalAssistantProperties.Mcp(
                        "external",
                        URI.create("https://example.com/mcp"),
                        Set.of("calculate"),
                        "personal_mcp",
                        "haifa-utility",
                        "Haifa Utility MCP",
                        false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback HTTP");
    }

    @Test
    void rejectsExternalModeWithoutEndpoint() {
        assertThatThrownBy(() -> new PersonalAssistantProperties.Mcp(
                        "external",
                        null,
                        Set.of("calculate"),
                        "personal_mcp",
                        "haifa-utility",
                        "Haifa Utility MCP",
                        false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mcp.endpoint must be absolute");
    }

    @Test
    void rejectsExternalModeWithoutReviewedTools() {
        assertThatThrownBy(() -> new PersonalAssistantProperties.Mcp(
                        "external",
                        URI.create("http://127.0.0.1:20002/mcp"),
                        Set.of(),
                        "personal_mcp",
                        "haifa-utility",
                        "Haifa Utility MCP",
                        false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mcp.allowedTools must contain 1 to 32 entries");
    }

    @Test
    void disabledModeCarriesNoEndpointToolsOrRequirement() {
        var mcp = new PersonalAssistantProperties.Mcp(
                "disabled", null, null, "personal_mcp", "personal-local", "Personal MCP", true);

        assertThat(mcp.mode()).isEqualTo("disabled");
        assertThat(mcp.endpoint()).isNull();
        assertThat(mcp.allowedTools()).isEmpty();
        assertThat(mcp.required()).isFalse();
    }

    @Test
    void rejectsUnknownMode() {
        assertThatThrownBy(() -> new PersonalAssistantProperties.Mcp(
                        "embedded-echo", null, null, "personal_mcp", "personal-local", "Personal MCP", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mcp.mode must be disabled or external");
    }

    private static PersonalAssistantProperties.Mcp external(Set<String> tools, boolean required) {
        return new PersonalAssistantProperties.Mcp(
                "external",
                URI.create("http://127.0.0.1:20002/mcp"),
                tools,
                "personal_mcp",
                "haifa-utility",
                "Haifa Utility MCP",
                required);
    }
}

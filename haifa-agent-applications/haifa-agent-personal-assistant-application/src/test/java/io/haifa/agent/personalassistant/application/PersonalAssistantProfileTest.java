package io.haifa.agent.personalassistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.personalassistant.application.product.PersonalAssistantProfile;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.product.ProductCapabilityMode;
import io.haifa.agent.sdk.product.ProductContributionCoordinate;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PersonalAssistantProfileTest {
    @Test
    void profileRequiresToolSkillAndMcpButDisablesCodingCapabilities() {
        ProductContributionCoordinate coordinate = new ProductContributionCoordinate("test", "1");
        String mcpAlias = "personal_mcp_calculate";
        var profile = PersonalAssistantProfile.create(
                new PersonalAssistantProfile.ContributionCoordinates(
                        coordinate, coordinate, coordinate, coordinate, coordinate, coordinate, coordinate, coordinate),
                Set.of(),
                Set.of(mcpAlias));
        assertThat(profile.requirement(ProductCapabilities.TOOL).mode()).isEqualTo(ProductCapabilityMode.REQUIRED);
        assertThat(profile.requirement(ProductCapabilities.SKILL).mode()).isEqualTo(ProductCapabilityMode.REQUIRED);
        assertThat(profile.requirement(ProductCapabilities.MCP).mode()).isEqualTo(ProductCapabilityMode.REQUIRED);
        assertThat(profile.requirement(ProductCapabilities.SHELL).mode()).isEqualTo(ProductCapabilityMode.NONE);
        assertThat(profile.allowedTools())
                .contains(
                        PersonalAssistantProfile.PRODUCT_TOOL_ALIAS,
                        PersonalAssistantProfile.SKILL_LOAD_ALIAS,
                        mcpAlias);
    }
}

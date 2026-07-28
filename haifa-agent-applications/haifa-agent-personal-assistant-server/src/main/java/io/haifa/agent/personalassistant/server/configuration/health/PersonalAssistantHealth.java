package io.haifa.agent.personalassistant.server.configuration.health;

import io.haifa.agent.personalassistant.application.PersonalAssistantApplication;
import io.haifa.agent.personalassistant.server.configuration.mcp.PersonalMcpRuntime;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("personalAssistant")
public final class PersonalAssistantHealth implements HealthIndicator {
    private final PersonalAssistantApplication application;
    private final PersonalMcpRuntime mcp;

    public PersonalAssistantHealth(PersonalAssistantApplication application, PersonalMcpRuntime mcp) {
        this.application = application;
        this.mcp = mcp;
    }

    @Override
    public Health health() {
        return Health.up()
                .withDetail("product", "haifa-personal-assistant")
                .withDetail("assembly", application.productDigest())
                .withDetail("mcpMode", mcp.mode())
                .withDetail("mcp", mcp.endpoint().getHost())
                .build();
    }
}

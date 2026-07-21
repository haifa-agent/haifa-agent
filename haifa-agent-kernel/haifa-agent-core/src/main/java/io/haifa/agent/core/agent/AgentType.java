package io.haifa.agent.core.agent;

import static io.haifa.agent.core.support.DomainValues.requireText;

/** Open Agent type value that product modules may extend without changing Core. */
public record AgentType(String value) {
    public static final AgentType CODING = new AgentType("coding");
    public static final AgentType DOCUMENT = new AgentType("document");
    public static final AgentType RESEARCH = new AgentType("research");

    public AgentType {
        value = requireText(value, "agentType").toLowerCase(java.util.Locale.ROOT);
    }
}

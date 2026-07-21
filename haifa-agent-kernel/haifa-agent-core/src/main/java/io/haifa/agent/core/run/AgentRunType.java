package io.haifa.agent.core.run;

import static io.haifa.agent.core.support.DomainValues.requireText;

import java.util.Locale;

/** Open product/execution classification for a run. */
public record AgentRunType(String value) {
    public static final AgentRunType CHAT = new AgentRunType("chat");
    public static final AgentRunType CODING = new AgentRunType("coding");
    public static final AgentRunType DOCUMENT = new AgentRunType("document");
    public static final AgentRunType RESEARCH = new AgentRunType("research");
    public static final AgentRunType ENTERPRISE = new AgentRunType("enterprise");

    public AgentRunType {
        value = requireText(value, "runType").toLowerCase(Locale.ROOT);
    }
}

package io.haifa.agent.core.error;

import static io.haifa.agent.core.support.DomainValues.requireText;

/** Stable open error code independent of provider exception classes. */
public record AgentErrorCode(String value) {
    public AgentErrorCode {
        value = requireText(value, "errorCode");
    }
}

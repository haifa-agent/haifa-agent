package io.haifa.agent.runtime.core.storage;

import io.haifa.agent.core.message.AgentMessage;

@FunctionalInterface
public interface MessageRedactionListener {
    void onRedacted(AgentMessage source);
}

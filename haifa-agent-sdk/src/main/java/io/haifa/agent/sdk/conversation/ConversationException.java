package io.haifa.agent.sdk.conversation;

import io.haifa.agent.sdk.api.HaifaAgentException;

/** Stable product-neutral Conversation error. */
public final class ConversationException extends HaifaAgentException {
    public ConversationException(String code, String operation, String correlation) {
        super(code, operation, correlation, code);
    }
}

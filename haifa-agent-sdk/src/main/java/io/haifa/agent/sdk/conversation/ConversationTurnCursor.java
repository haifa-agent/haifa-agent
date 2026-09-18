package io.haifa.agent.sdk.conversation;

public record ConversationTurnCursor(long sequence) {
    public ConversationTurnCursor {
        if (sequence < 0) throw new IllegalArgumentException("sequence must not be negative");
    }
}

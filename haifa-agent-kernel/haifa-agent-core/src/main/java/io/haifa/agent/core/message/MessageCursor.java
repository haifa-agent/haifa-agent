package io.haifa.agent.core.message;

/** Stable, monotonically increasing position in one session message stream. */
public record MessageCursor(long value) implements Comparable<MessageCursor> {
    public static final MessageCursor BEFORE_FIRST = new MessageCursor(0);

    public MessageCursor {
        if (value < 0) throw new IllegalArgumentException("message cursor must not be negative");
    }

    public static MessageCursor parse(String value) {
        if (value == null || !value.startsWith("m:")) {
            throw new IllegalArgumentException("invalid message cursor");
        }
        return new MessageCursor(Long.parseLong(value.substring(2)));
    }

    public String serialize() {
        return "m:" + value;
    }

    @Override
    public int compareTo(MessageCursor other) {
        return Long.compare(value, other.value);
    }
}

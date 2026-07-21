package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.core.tool.ToolResult;

public record LargeToolResultPolicy(long maxInlineCharacters) {
    public LargeToolResultPolicy {
        if (maxInlineCharacters < 1) throw new IllegalArgumentException("maxInlineCharacters must be positive");
    }

    public boolean requiresExternalization(ToolResult result) {
        long size = (long) result.summary().length()
                + result.structuredData().toString().length();
        return result.truncated() || size > maxInlineCharacters;
    }

    public static LargeToolResultPolicy defaults() {
        return new LargeToolResultPolicy(16_384);
    }
}

package io.haifa.agent.runtime.core.storage;

import io.haifa.agent.core.content.*;
import io.haifa.agent.core.message.MessageRole;
import java.util.List;
import java.util.Locale;

/** Source facts that bounded selection retains until withdrawal or an authorized reader exists. */
public final class HistoryRetention {
    private HistoryRetention() {}

    public static boolean readable(List<ContentPart> contents) {
        return contents.stream()
                .allMatch(part ->
                        part instanceof TextPart || part instanceof ToolCallPart || part instanceof ToolResultPart);
    }

    public static boolean required(
            java.util.Optional<io.haifa.agent.core.run.AgentRunId> owner,
            MessageRole role,
            List<ContentPart> contents) {
        return owner.isEmpty()
                || !readable(contents)
                || role == MessageRole.USER
                        && contents.stream()
                                .filter(TextPart.class::isInstance)
                                .map(TextPart.class::cast)
                                .anyMatch(part ->
                                        part.text().toLowerCase(Locale.ROOT).startsWith("project constraint:")
                                                || part.text().startsWith("项目约束"));
    }
}

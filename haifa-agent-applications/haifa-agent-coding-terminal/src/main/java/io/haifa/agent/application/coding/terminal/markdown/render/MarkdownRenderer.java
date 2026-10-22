package io.haifa.agent.application.coding.terminal.markdown.render;

import java.util.Set;

/** In-memory assistant Markdown rendering contract; no session or persistence state is changed. */
public interface MarkdownRenderer {
    String render(String documentId, String markdown, int width);

    void retain(Set<String> documentIds);

    void resetAll();

    ParseMetrics metrics(String documentId);

    record ParseMetrics(int fullParseCount, int lastDeltaCharacters, long totalParsedCharacters) {}
}

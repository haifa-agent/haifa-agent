package io.haifa.agent.cli;

import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.credential.core.DefaultSecretRedactor;
import io.haifa.agent.execution.api.ExecutionOutputChannel;
import io.haifa.agent.execution.api.ToolOutputPreview;
import io.haifa.agent.runtime.api.display.BoundedText;
import java.util.HashMap;
import java.util.Map;

/** Subscription-local display normalization and bounded redaction continuity, never durable Run state. */
final class CodingHostPreviewStream {
    static final int MAXIMUM_PENDING_STREAMS = 64;
    private static final int MAXIMUM_PENDING_CHARACTERS = 4096;

    private record Key(ToolCallId tool, ExecutionOutputChannel channel) {}

    private final Map<Key, State> streams = new HashMap<>();
    private final DefaultSecretRedactor redactor = new DefaultSecretRedactor();

    synchronized ToolOutputPreview accept(ToolOutputPreview preview) {
        if (preview.previewDropped()) throw new IllegalStateException("Preview continuity was lost");
        Key key = new Key(preview.toolCallId(), preview.channel());
        State state = streams.get(key);
        if (state == null) {
            if (streams.size() >= MAXIMUM_PENDING_STREAMS)
                throw new IllegalStateException("Preview stream limit exceeded");
            state = new State(redactor.openStream(MAXIMUM_PENDING_CHARACTERS));
            streams.put(key, state);
        }
        String safe = state.redaction.accept(state.normalize(preview.text()));
        if (!state.redaction.awaitingMoreText() && !state.display.pending()) streams.remove(key);
        var bounded = BoundedText.of(safe, 4096, 256);
        if (bounded.text().isEmpty() && !preview.outputTruncated()) return null;
        return new ToolOutputPreview(
                preview.runId(),
                preview.toolCallId(),
                preview.channel(),
                bounded.text(),
                preview.outputTruncated(),
                bounded.truncated());
    }

    private static final class State {
        final DefaultSecretRedactor.Stream redaction;
        final io.haifa.agent.application.project.product.coding.presentation.CodingPresentationText.Stream display =
                new io.haifa.agent.application.project.product.coding.presentation.CodingPresentationText.Stream();

        State(DefaultSecretRedactor.Stream redaction) {
            this.redaction = redaction;
        }

        String normalize(String text) {
            return display.accept(text);
        }
    }
}

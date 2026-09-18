package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.core.reference.AssetRef;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolResult;
import java.util.Optional;

/** External authoritative storage for tool results too large to keep inline. */
public interface ToolResultAssetStore {
    AssetRef put(ToolCallId toolCallId, ToolResult result);

    /**
     * Attempts best-effort externalization without making the authoritative inline result unavailable.
     * Stores with transactional nesting should override this method so the failure is contained before
     * it can mark the caller's transaction rollback-only.
     */
    default Optional<AssetRef> tryPut(ToolCallId toolCallId, ToolResult result) {
        try {
            return Optional.of(put(toolCallId, result));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    Optional<ToolResult> load(AssetRef reference);
}

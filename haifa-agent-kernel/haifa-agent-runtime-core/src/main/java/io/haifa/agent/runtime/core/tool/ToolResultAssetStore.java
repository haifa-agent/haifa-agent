package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.core.reference.AssetRef;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolResult;
import java.util.Optional;

/** External authoritative storage for tool results too large to keep inline. */
public interface ToolResultAssetStore {
    AssetRef put(ToolCallId toolCallId, ToolResult result);

    Optional<ToolResult> load(AssetRef reference);
}

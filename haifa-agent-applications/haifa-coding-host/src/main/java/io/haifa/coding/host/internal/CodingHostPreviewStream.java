package io.haifa.coding.host.internal;

import io.haifa.agent.application.project.product.coding.presentation.CodingPreviewProjection;
import io.haifa.agent.execution.api.ToolOutputPreview;

/** Legacy native wrapper around the engine-owned safe preview projection. */
public final class CodingHostPreviewStream {
    public static final int MAXIMUM_PENDING_STREAMS = CodingPreviewProjection.MAXIMUM_PENDING_STREAMS;
    private final CodingPreviewProjection projection = new CodingPreviewProjection();

    public ToolOutputPreview accept(ToolOutputPreview preview) {
        return projection.accept(preview);
    }
}

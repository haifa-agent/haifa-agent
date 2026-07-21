package io.haifa.agent.context.source;

import io.haifa.agent.context.api.ContextBuildRequest;
import io.haifa.agent.context.item.ContextItem;
import java.util.List;

public interface ContextSource {
    String id();

    String version();

    /** Cheap capability/profile gate evaluated before a source performs any lookup. */
    default boolean supports(ContextBuildRequest request) {
        return true;
    }

    List<ContextItem> load(ContextBuildRequest request);
}

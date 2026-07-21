package io.haifa.agent.context.selection;

import io.haifa.agent.context.item.ContextItem;
import java.util.Comparator;

/** Stable priority/retention ordering with item id as the final tie-breaker. */
public final class ContextSelectionPolicy {
    public Comparator<ContextItem> comparator() {
        return Comparator.comparing(ContextItem::priority)
                .thenComparing(ContextItem::retention)
                .thenComparing(item -> item.id().value());
    }

    public String version() {
        return "priority-retention-v1";
    }
}

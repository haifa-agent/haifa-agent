package io.haifa.agent.context.source;

import io.haifa.agent.context.api.ContextBuildRequest;
import io.haifa.agent.context.item.ContextItem;
import java.util.List;

public interface ContextSource {
    String id();

    String version();

    List<ContextItem> load(ContextBuildRequest request);
}

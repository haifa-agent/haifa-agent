package io.haifa.agent.sdk.contribution;

import io.haifa.agent.context.source.ContextSource;
import io.haifa.agent.sdk.product.ProductCapabilities;
import java.util.List;
import java.util.Objects;

/** Ordered, explicitly registered Context sources for one product assembly. */
public final class ContextSourceContribution extends AbstractSdkContribution {
    private final List<ContextSource> sources;

    public ContextSourceContribution(SdkContributionMetadata metadata, List<? extends ContextSource> sources) {
        super(metadata);
        if (!ProductCapabilities.CONTEXT.equals(metadata.capabilityId())) {
            throw new IllegalArgumentException("context contribution must provide the context capability");
        }
        this.sources = List.copyOf(Objects.requireNonNull(sources, "sources must not be null"));
        if (this.sources.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("sources must not contain null");
        }
        if (this.sources.isEmpty()) {
            throw new IllegalArgumentException("context contribution must contain at least one source");
        }
    }

    public List<ContextSource> sources() {
        return sources;
    }
}

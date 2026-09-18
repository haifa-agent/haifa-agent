package io.haifa.agent.runtime.api;

import java.util.Objects;
import java.util.Optional;

/** Safe target projection; raw arguments and host paths are intentionally absent. */
public record InteractionTargetView(
        String type, String reference, Optional<String> version, Optional<String> digest, String safeSummary) {
    public InteractionTargetView {
        type = InteractionKind.requireToken(type, "type");
        reference = InteractionOption.requireText(reference, "reference", 256);
        version = normalize(version, "version");
        digest = normalize(digest, "digest");
        safeSummary = InteractionOption.requireText(safeSummary, "safeSummary", 512);
    }

    private static Optional<String> normalize(Optional<String> value, String field) {
        return Objects.requireNonNull(value, field + " must not be null")
                .map(item -> InteractionOption.requireText(item, field, 256));
    }
}

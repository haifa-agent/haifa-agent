package io.haifa.agent.context.compression;

import java.util.Objects;

public record SummaryId(String value) {
    public SummaryId {
        value = Objects.requireNonNull(value, "value must not be null").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("summary id must not be blank");
    }
}

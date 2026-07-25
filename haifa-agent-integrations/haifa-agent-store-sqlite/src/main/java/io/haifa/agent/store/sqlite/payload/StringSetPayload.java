package io.haifa.agent.store.sqlite.payload;

import java.util.Set;

public record StringSetPayload(Set<String> values) {
    public StringSetPayload {
        values = Set.copyOf(values);
    }
}

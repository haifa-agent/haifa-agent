package io.haifa.agent.runtime.core.input;

import java.util.Objects;

public record RunInputAcceptance(RunInputRecord record, boolean newlyAccepted) {
    public RunInputAcceptance {
        record = Objects.requireNonNull(record, "record must not be null");
    }
}

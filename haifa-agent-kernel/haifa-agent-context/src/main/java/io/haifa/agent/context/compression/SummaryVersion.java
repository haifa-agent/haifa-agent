package io.haifa.agent.context.compression;

public record SummaryVersion(long value) {
    public SummaryVersion {
        if (value < 1) throw new IllegalArgumentException("summary version must be positive");
    }
}

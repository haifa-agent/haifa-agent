package io.haifa.agent.context.compaction;

@FunctionalInterface
public interface ContextCompressor {
    CompressionResult compress(CompressionRequest request);

    default String version() {
        return "custom-v1";
    }
}

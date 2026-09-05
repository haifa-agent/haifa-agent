package io.haifa.agent.context.compression;

@FunctionalInterface
public interface ContextCompressor {
    CompressionResult compress(CompressionRequest request);

    default String version() {
        return "custom-v1";
    }
}

package io.haifa.agent.store.sqlite.payload;

public record BinaryPayload(byte[] bytes) {
    public BinaryPayload {
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}

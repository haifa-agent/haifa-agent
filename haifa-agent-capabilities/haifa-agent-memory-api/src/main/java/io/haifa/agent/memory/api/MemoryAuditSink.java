package io.haifa.agent.memory.api;

public interface MemoryAuditSink {
    void record(MemoryAuditEvent event);
}

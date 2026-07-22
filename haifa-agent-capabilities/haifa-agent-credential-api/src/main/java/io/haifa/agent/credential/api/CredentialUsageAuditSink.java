package io.haifa.agent.credential.api;

@FunctionalInterface
public interface CredentialUsageAuditSink {
    void record(CredentialUsageAudit event);

    static CredentialUsageAuditSink noop() {
        return event -> {};
    }
}

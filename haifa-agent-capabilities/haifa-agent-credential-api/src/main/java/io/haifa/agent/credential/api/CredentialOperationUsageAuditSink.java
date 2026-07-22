package io.haifa.agent.credential.api;

@FunctionalInterface
public interface CredentialOperationUsageAuditSink {
    void record(CredentialOperationUsageAudit event);

    static CredentialOperationUsageAuditSink noop() {
        return event -> {};
    }
}

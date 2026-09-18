package io.haifa.agent.policy.api;

public enum PolicySideEffect {
    FILE_WRITE,
    PROCESS_EXECUTION,
    NETWORK_ACCESS,
    CREDENTIAL_USE,
    EXTERNAL_SYSTEM_MUTATION,
    PERMISSION_ELEVATION
}

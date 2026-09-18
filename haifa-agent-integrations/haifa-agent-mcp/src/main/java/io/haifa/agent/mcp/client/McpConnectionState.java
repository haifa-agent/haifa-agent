package io.haifa.agent.mcp.client;

public enum McpConnectionState {
    DISCONNECTED,
    CONNECTING,
    INITIALIZING,
    READY,
    CLOSING,
    FAILED
}

package io.haifa.agent.mcp.config;

import java.time.Duration;
import java.util.Objects;

public record McpConnectionPolicy(
        Duration connectTimeout,
        Duration requestTimeout,
        Duration idleTimeout,
        Duration shutdownTimeout,
        int maxReconnectAttempts) {
    public McpConnectionPolicy {
        connectTimeout = positive(connectTimeout, "connectTimeout");
        requestTimeout = positive(requestTimeout, "requestTimeout");
        idleTimeout = positive(idleTimeout, "idleTimeout");
        shutdownTimeout = positive(shutdownTimeout, "shutdownTimeout");
        if (maxReconnectAttempts < 0 || maxReconnectAttempts > 8) {
            throw new IllegalArgumentException("maxReconnectAttempts is out of range");
        }
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}

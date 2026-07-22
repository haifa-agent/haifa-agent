package io.haifa.agent.mcp.transport.stdio;

import io.haifa.agent.execution.api.ManagedProcessRequest;
import java.util.Objects;

public record McpManagedProcessLaunch(ManagedProcessRequest request, AutoCloseable environmentBinding)
        implements AutoCloseable {
    public McpManagedProcessLaunch {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(environmentBinding, "environmentBinding");
    }

    @Override
    public void close() {
        try {
            environmentBinding.close();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("failed to close MCP stdio environment binding", exception);
        }
    }
}

package io.haifa.agent.transport.http;

import java.util.Optional;

@FunctionalInterface
public interface RunOperationAuthorizer {
    void authorize(
            TrustedCallerContext caller,
            RunOperation operation,
            Optional<String> runId,
            Optional<String> interactionRequestId);
}

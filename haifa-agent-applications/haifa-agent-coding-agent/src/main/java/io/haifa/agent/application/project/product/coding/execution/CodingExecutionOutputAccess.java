package io.haifa.agent.application.project.product.coding.execution;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import java.util.List;
import java.util.Objects;

/** Trusted, bounded access to retained output produced by an execution in the same Run. */
public interface CodingExecutionOutputAccess {
    ReadResult read(ReadRequest request);

    enum Mode {
        WINDOW,
        SEARCH
    }

    enum FailureCode {
        INVALID_TOOL_ARGUMENT,
        EXECUTION_OUTPUT_NOT_FOUND,
        EXECUTION_OUTPUT_UNAVAILABLE,
        EXECUTION_OUTPUT_BINARY_UNSUPPORTED
    }

    record ReadRequest(
            TenantRef tenant,
            PrincipalRef principal,
            AgentRunId runId,
            String outputRef,
            Mode mode,
            long offsetBytes,
            int maximumBytes,
            String query,
            int maximumMatches) {
        public ReadRequest {
            tenant = Objects.requireNonNull(tenant, "tenant must not be null");
            principal = Objects.requireNonNull(principal, "principal must not be null");
            runId = Objects.requireNonNull(runId, "runId must not be null");
            outputRef = Objects.requireNonNull(outputRef, "outputRef must not be null");
            mode = Objects.requireNonNull(mode, "mode must not be null");
            query = query == null ? "" : query;
        }
    }

    record Match(long byteOffset, String snippet) {
        public Match {
            if (byteOffset < 0) throw new IllegalArgumentException("byteOffset must not be negative");
            snippet = Objects.requireNonNull(snippet, "snippet must not be null");
        }
    }

    record ReadResult(
            String outputRef,
            Mode mode,
            String text,
            long startOffsetBytes,
            long endOffsetBytes,
            Long nextOffsetBytes,
            boolean hasMore,
            long retainedBytes,
            boolean captureTruncated,
            List<Match> matches) {
        public ReadResult {
            outputRef = Objects.requireNonNull(outputRef, "outputRef must not be null");
            mode = Objects.requireNonNull(mode, "mode must not be null");
            text = Objects.requireNonNull(text, "text must not be null");
            matches = List.copyOf(Objects.requireNonNull(matches, "matches must not be null"));
        }
    }

    final class AccessException extends RuntimeException {
        private final FailureCode code;

        public AccessException(FailureCode code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code must not be null");
        }

        public FailureCode code() {
            return code;
        }
    }
}

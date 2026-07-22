package io.haifa.agent.runtime.core.checkpoint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class RuntimeCheckpointStateHasher {
    private RuntimeCheckpointStateHasher() {}

    static String digest(RuntimeCheckpointState state) {
        String canonical = state.runId().value()
                + "|"
                + state.nextIteration()
                + "|"
                + state.sessionMessageCursor().serialize()
                + "|"
                + state.modelConfigurationDigest()
                + "|"
                + state.activeSummary()
                + "|"
                + state.toolCalls()
                + "|"
                + state.forcedContextRebuildAttempts()
                + "|"
                + state.selectedMemories()
                + "|"
                + state.memoryRetrievalPolicyVersion()
                + "|"
                + state.memoryQueryDigest()
                + "|"
                + state.modelContinuations()
                + "|"
                + state.capabilityCheckpoints();
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}

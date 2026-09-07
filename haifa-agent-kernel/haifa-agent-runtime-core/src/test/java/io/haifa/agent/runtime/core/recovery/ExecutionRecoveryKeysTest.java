package io.haifa.agent.runtime.core.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolCallId;
import org.junit.jupiter.api.Test;

class ExecutionRecoveryKeysTest {
    private static final AgentRunId RUN_ID = new AgentRunId("run-1");
    private static final ToolCallId TOOL_CALL_ID = new ToolCallId("tool-call-1");

    @Test
    void sameSourceProducesTheSameRequestAndSuccessorKeys() {
        assertThat(ExecutionRecoveryKeys.requestId(RUN_ID, TOOL_CALL_ID))
                .isEqualTo(ExecutionRecoveryKeys.requestId(RUN_ID, TOOL_CALL_ID));
        assertThat(ExecutionRecoveryKeys.successor(RUN_ID, TOOL_CALL_ID, "sha256:intent"))
                .isEqualTo(ExecutionRecoveryKeys.successor(RUN_ID, TOOL_CALL_ID, "sha256:intent"));
    }

    @Test
    void distinctSourceOrIntentProducesDistinctKeys() {
        assertThat(ExecutionRecoveryKeys.requestId(RUN_ID, TOOL_CALL_ID))
                .isNotEqualTo(ExecutionRecoveryKeys.requestId(RUN_ID, new ToolCallId("tool-call-2")));
        assertThat(ExecutionRecoveryKeys.successor(RUN_ID, TOOL_CALL_ID, "sha256:intent"))
                .isNotEqualTo(ExecutionRecoveryKeys.successor(RUN_ID, TOOL_CALL_ID, "sha256:other-intent"));
    }

    @Test
    void everyFrozenRequirementFieldAffectsTheDigest() {
        String baseline = requirementDigest(
                "execution.run@1.0.0",
                "sha256:definition",
                "sha256:arguments",
                "sha256:configuration",
                "NETWORK_PERMISSION_REQUIRED",
                "tenant-1/principal-1");

        assertThat(requirementDigest(
                        "execution.run@2.0.0",
                        "sha256:definition",
                        "sha256:arguments",
                        "sha256:configuration",
                        "NETWORK_PERMISSION_REQUIRED",
                        "tenant-1/principal-1"))
                .isNotEqualTo(baseline);
        assertThat(requirementDigest(
                        "execution.run@1.0.0",
                        "sha256:other-definition",
                        "sha256:arguments",
                        "sha256:configuration",
                        "NETWORK_PERMISSION_REQUIRED",
                        "tenant-1/principal-1"))
                .isNotEqualTo(baseline);
        assertThat(requirementDigest(
                        "execution.run@1.0.0",
                        "sha256:definition",
                        "sha256:other-arguments",
                        "sha256:configuration",
                        "NETWORK_PERMISSION_REQUIRED",
                        "tenant-1/principal-1"))
                .isNotEqualTo(baseline);
        assertThat(requirementDigest(
                        "execution.run@1.0.0",
                        "sha256:definition",
                        "sha256:arguments",
                        "sha256:other-configuration",
                        "NETWORK_PERMISSION_REQUIRED",
                        "tenant-1/principal-1"))
                .isNotEqualTo(baseline);
        assertThat(requirementDigest(
                        "execution.run@1.0.0",
                        "sha256:definition",
                        "sha256:arguments",
                        "sha256:configuration",
                        "PORT_BIND_FAILED",
                        "tenant-1/principal-1"))
                .isNotEqualTo(baseline);
        assertThat(requirementDigest(
                        "execution.run@1.0.0",
                        "sha256:definition",
                        "sha256:arguments",
                        "sha256:configuration",
                        "NETWORK_PERMISSION_REQUIRED",
                        "tenant-1/principal-2"))
                .isNotEqualTo(baseline);
    }

    private static String requirementDigest(
            String coordinate,
            String definitionHash,
            String argumentsDigest,
            String configurationDigest,
            String failureCode,
            String principalScope) {
        return ExecutionRecoveryKeys.requirementDigest(
                RUN_ID,
                TOOL_CALL_ID,
                coordinate,
                definitionHash,
                argumentsDigest,
                configurationDigest,
                failureCode,
                principalScope);
    }
}

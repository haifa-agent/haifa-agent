package io.haifa.agent.runtime.core.execution;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExecutionOwnershipPortTest {
    @Test
    void extractsEmbeddedHostProcessId() {
        assertThat(ExecutionOwnershipPort.hostProcessId(
                        "project-runtime-abc" + ExecutionOwnershipPort.HOST_PROCESS_MARKER + "4321"))
                .hasValue(4321L);
        assertThat(ExecutionOwnershipPort.hostProcessId("worker-a")).isEmpty();
        assertThat(ExecutionOwnershipPort.hostProcessId("worker" + ExecutionOwnershipPort.HOST_PROCESS_MARKER))
                .isEmpty();
        assertThat(ExecutionOwnershipPort.hostProcessId(
                        "worker" + ExecutionOwnershipPort.HOST_PROCESS_MARKER + "notanumber"))
                .isEmpty();
        assertThat(ExecutionOwnershipPort.hostProcessId(null)).isEmpty();
    }
}

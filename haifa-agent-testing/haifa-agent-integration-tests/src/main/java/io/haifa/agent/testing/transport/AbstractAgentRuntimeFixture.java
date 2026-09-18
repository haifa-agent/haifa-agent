package io.haifa.agent.testing.transport;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.AgentRunHandle;
import io.haifa.agent.runtime.api.AgentRunListener;
import io.haifa.agent.runtime.api.AgentRunOutputEvent;
import io.haifa.agent.runtime.api.AgentRunOutputListener;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.AgentRuntime;
import io.haifa.agent.runtime.api.InteractionResponse;
import io.haifa.agent.runtime.api.ResumeAgentRunRequest;
import io.haifa.agent.runtime.api.RunOutputCursor;
import io.haifa.agent.runtime.api.RuntimeCommand;
import io.haifa.agent.runtime.api.RuntimeCommandResult;
import java.util.List;
import java.util.Optional;

/** Small test-only base that keeps product Fixtures focused on the operations they exercise. */
public abstract class AbstractAgentRuntimeFixture implements AgentRuntime {
    @Override
    public AgentRunSnapshot start(AgentRunRequest request) {
        throw new UnsupportedOperationException();
    }

    @Override
    public AgentRunSnapshot resume(ResumeAgentRunRequest request) {
        throw new UnsupportedOperationException();
    }

    @Override
    public AgentRunSnapshot respond(InteractionResponse response) {
        throw new UnsupportedOperationException();
    }

    @Override
    public RuntimeCommandResult command(RuntimeCommand command) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<AgentRunSnapshot> find(AgentRunId runId) {
        return Optional.empty();
    }

    @Override
    public AgentRunHandle handle(AgentRunId runId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addListener(AgentRunListener listener) {}

    @Override
    public List<AgentRunOutputEvent> outputEvents(AgentRunId runId, RunOutputCursor after, int limit) {
        return List.of();
    }

    @Override
    public void addOutputListener(AgentRunOutputListener listener) {}
}

package io.haifa.agent.runtime.core.completion;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunResult;
import io.haifa.agent.runtime.core.decision.FinalAnswerDecision;

public final class DefaultRunFinalizer implements RunFinalizer {
    @Override
    public AgentRunResult finalizeResult(AgentRun run, FinalAnswerDecision decision) {
        return new AgentRunResult(
                decision.outcome(),
                decision.summary(),
                decision.outputSchemaId(),
                decision.outputSchemaVersion(),
                decision.structuredOutput(),
                decision.artifacts(),
                decision.warnings());
    }
}

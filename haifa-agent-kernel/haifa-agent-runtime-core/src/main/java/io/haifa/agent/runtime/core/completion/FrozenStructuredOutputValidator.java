package io.haifa.agent.runtime.core.completion;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.decision.FinalAnswerDecision;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.tool.api.ToolSchema;
import io.haifa.agent.tool.api.ToolSchemaValidator;
import java.util.Objects;

/** Validates terminal output against the exact schema frozen in the Run configuration snapshot. */
public final class FrozenStructuredOutputValidator implements OutputContractValidator {
    private final RuntimeStateRepository state;
    private final ToolSchemaValidator schemas;

    public FrozenStructuredOutputValidator(RuntimeStateRepository state, ToolSchemaValidator schemas) {
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.schemas = Objects.requireNonNull(schemas, "schemas must not be null");
    }

    @Override
    public boolean isValid(AgentRun run, FinalAnswerDecision decision) {
        var requirement = state.configuration(run.configurationSnapshot())
                .flatMap(configuration -> configuration.structuredOutput());
        if (requirement.isEmpty()) return true;
        var expected = requirement.orElseThrow();
        if (!expected.schemaId().equals(decision.outputSchemaId())
                || !expected.schemaVersion().equals(decision.outputSchemaVersion())) return false;
        return schemas.validate(
                        new ToolSchema(expected.schemaId(), expected.schemaVersion(), expected.jsonSchema()),
                        decision.structuredOutput())
                .valid();
    }
}

package io.haifa.agent.orchestration.api;

@FunctionalInterface
public interface WorkflowDefinitionCompiler {
    CompiledWorkflowDefinition compile(WorkflowDefinition definition);
}

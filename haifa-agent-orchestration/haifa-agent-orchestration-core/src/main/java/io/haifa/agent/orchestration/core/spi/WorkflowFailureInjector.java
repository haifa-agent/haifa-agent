package io.haifa.agent.orchestration.core.spi;

@FunctionalInterface
public interface WorkflowFailureInjector {
    WorkflowFailureInjector NONE = ignored -> {};

    void afterCommit(WorkflowFailurePoint point);
}

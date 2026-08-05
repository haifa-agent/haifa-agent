package io.haifa.agent.execution.api;

/** Defines whether a process may continue after its retained output budget is exhausted. */
public enum ExecutionOutputOverflowPolicy {
    RETAIN_HEAD_TAIL,
    TERMINATE
}

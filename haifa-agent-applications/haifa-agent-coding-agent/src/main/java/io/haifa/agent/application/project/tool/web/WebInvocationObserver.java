package io.haifa.agent.application.project.tool.web;

public interface WebInvocationObserver {
    void dispatched();

    void acknowledged();
}

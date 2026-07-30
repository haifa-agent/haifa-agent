package io.haifa.agent.web;

public interface WebInvocationObserver {
    void dispatched();

    void acknowledged();
}

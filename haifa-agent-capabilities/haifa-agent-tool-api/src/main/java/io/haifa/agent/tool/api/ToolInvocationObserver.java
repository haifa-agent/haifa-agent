package io.haifa.agent.tool.api;

public interface ToolInvocationObserver {
    void dispatched();

    default void dispatched(ToolDispatchEvidence evidence) {
        dispatched();
    }

    void acknowledged();

    static ToolInvocationObserver noop() {
        return new ToolInvocationObserver() {
            @Override
            public void dispatched() {}

            @Override
            public void acknowledged() {}
        };
    }
}

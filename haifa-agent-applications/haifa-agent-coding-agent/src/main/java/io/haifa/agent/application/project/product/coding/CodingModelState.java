package io.haifa.agent.application.project.product.coding;

import java.util.Objects;

/** Product-safe, orthogonal model state for Coding surfaces. */
public record CodingModelState(
        Connection connection,
        BindingAvailability bindingAvailability,
        RuntimeStatus runtime,
        RunScope runScope) {
    public CodingModelState {
        connection = Objects.requireNonNull(connection, "connection must not be null");
        bindingAvailability = Objects.requireNonNull(bindingAvailability, "bindingAvailability must not be null");
        runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        runScope = Objects.requireNonNull(runScope, "runScope must not be null");
    }

    public static CodingModelState unavailable() {
        return new CodingModelState(Connection.CONNECTED, BindingAvailability.UNAVAILABLE, RuntimeStatus.NORMAL, RunScope.IDLE);
    }

    public enum Connection { CONNECTED, LOGIN_REQUIRED, REAUTH_REQUIRED }

    public enum BindingAvailability { AVAILABLE, UNAVAILABLE }

    public enum RuntimeStatus { NORMAL, RATE_LIMITED, UNREACHABLE }

    public enum RunScope { IDLE, ACTIVE_RUN, HISTORICAL }
}

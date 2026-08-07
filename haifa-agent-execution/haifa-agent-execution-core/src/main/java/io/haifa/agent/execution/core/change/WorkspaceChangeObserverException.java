package io.haifa.agent.execution.core.change;

import java.util.Objects;

/** Safe, stable failure raised while establishing or converging a workspace observation window. */
public final class WorkspaceChangeObserverException extends RuntimeException {
    public static final String UNAVAILABLE = "WORKSPACE_CHANGE_OBSERVER_UNAVAILABLE";
    public static final String RESYNC_FAILED = "WORKSPACE_CHANGE_OBSERVER_RESYNC_FAILED";

    private final String code;

    private WorkspaceChangeObserverException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code must not be null");
    }

    public static WorkspaceChangeObserverException unavailable(Throwable cause) {
        return new WorkspaceChangeObserverException(UNAVAILABLE, "workspace change observation is unavailable", cause);
    }

    public static WorkspaceChangeObserverException resyncFailed(Throwable cause) {
        return new WorkspaceChangeObserverException(
                RESYNC_FAILED, "workspace change observation could not be converged", cause);
    }

    public String code() {
        return code;
    }
}

package io.haifa.agent.project.hostworkspace;

/** Git validation could not run; callers must not misclassify this as a plain directory. */
public final class HostGitInspectionUnavailableException extends IllegalStateException {
    public HostGitInspectionUnavailableException() {
        super("host Git inspection is unavailable");
    }

    public HostGitInspectionUnavailableException(Throwable cause) {
        super("host Git inspection is unavailable", cause);
    }
}

package io.haifa.agent.project.store;

public final class ProjectStoreConflictException extends RuntimeException {
    public ProjectStoreConflictException(String message) {
        super(message);
    }
}

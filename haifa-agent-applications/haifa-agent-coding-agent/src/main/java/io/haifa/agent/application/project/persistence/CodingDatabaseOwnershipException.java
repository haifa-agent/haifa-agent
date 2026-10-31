package io.haifa.agent.application.project.persistence;

/** Signals that another process already owns the authoritative Coding Agent SQLite database. */
public final class CodingDatabaseOwnershipException extends IllegalStateException {
    public static final String CODE = "DATABASE_OWNED_BY_ANOTHER_PROCESS";

    public CodingDatabaseOwnershipException() {
        super(CODE + ": the Coding Agent database is already owned by another process");
    }
}

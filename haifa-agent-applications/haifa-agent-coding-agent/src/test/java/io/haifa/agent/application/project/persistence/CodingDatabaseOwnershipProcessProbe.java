package io.haifa.agent.application.project.persistence;

import java.nio.file.Path;

/** Child-JVM fixture: OS FileLock exclusion must be observed from a separate process. */
final class CodingDatabaseOwnershipProcessProbe {
    static final int OWNERSHIP_CONFLICT_EXIT_CODE = 42;

    private CodingDatabaseOwnershipProcessProbe() {}

    public static void main(String[] arguments) {
        try (CodingDatabaseOwnership ownership = CodingDatabaseOwnership.acquire(Path.of(arguments[0]))) {
            // Acquired and released successfully.
        } catch (CodingDatabaseOwnershipException ownershipConflict) {
            System.exit(OWNERSHIP_CONFLICT_EXIT_CODE);
        }
    }
}

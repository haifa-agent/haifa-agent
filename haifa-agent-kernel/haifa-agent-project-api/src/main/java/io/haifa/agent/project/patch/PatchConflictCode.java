package io.haifa.agent.project.patch;

public enum PatchConflictCode {
    REVISION_CONFLICT,
    EXPECTED_HASH_REQUIRED,
    CONTENT_HASH_CONFLICT,
    TARGET_EXISTS,
    TARGET_NOT_FOUND,
    HUNK_MISMATCH,
    ENCODING_CONFLICT,
    MUTATION_REJECTED
}

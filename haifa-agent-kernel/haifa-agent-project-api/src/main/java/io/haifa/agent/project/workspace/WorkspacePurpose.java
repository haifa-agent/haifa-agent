package io.haifa.agent.project.workspace;

public enum WorkspacePurpose {
    PRIMARY,
    /** A peer authorized local directory of one session; carries no main or attached role. */
    DIRECTORY,
    CHILD,
    RECOVERY,
    TEMPORARY
}

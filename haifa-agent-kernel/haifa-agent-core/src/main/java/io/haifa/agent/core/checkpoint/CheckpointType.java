package io.haifa.agent.core.checkpoint;

/** Reason and scope for capturing a checkpoint. */
public enum CheckpointType {
    AUTOMATIC,
    MANUAL,
    INTERACTION,
    APPROVAL,
    FAILURE_RECOVERY,
    GRAPH_NODE,
    WORKSPACE_SNAPSHOT
}

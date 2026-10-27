package io.haifa.agent.sandbox.docker;

/**
 * Lifecycle execution mode for {@code docker-aio} sandbox containers.
 */
public enum DockerAioLifecycleMode {
    /**
     * Ephemeral mode (ADR-038): each single command execution provisions a brand new container
     * and immediately destroys it upon command completion.
     */
    EPHEMERAL,

    /**
     * Warm session mode (DeerFlow parity): containers are held open across tool commands within the same
     * workspace/session, eliminating repeated cold-start and teardown overhead (~2ms per command).
     */
    WARM_SESSION
}

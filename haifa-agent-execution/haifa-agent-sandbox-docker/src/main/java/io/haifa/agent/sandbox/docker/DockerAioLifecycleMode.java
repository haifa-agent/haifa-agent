package io.haifa.agent.sandbox.docker;

/**
 * Lifecycle execution mode for {@code docker-aio} sandbox containers.
 *
 * <p>Default mode is {@link #WARM_SESSION} per revised ADR-038 and DeerFlow architecture parity.
 */
public enum DockerAioLifecycleMode {
    /**
     * Ephemeral mode: each single command execution provisions a brand new container
     * and immediately destroys it upon command completion.
     */
    EPHEMERAL,

    /**
     * Warm session mode (revised ADR-038 default): containers are retained and reused per
     * workspace, eliminating repeated cold-start and teardown overhead across multi-turn interactions.
     * Stale containers are evicted after an idle timeout.
     */
    WARM_SESSION
}

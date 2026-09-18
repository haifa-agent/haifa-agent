package io.haifa.agent.application.project.product.coding.client;

import java.util.Objects;

/** Secret-free progress emitted while an interactive model login is running. */
public record CodingAuthenticationProgressView(Phase phase) {
    public CodingAuthenticationProgressView {
        phase = Objects.requireNonNull(phase, "phase must not be null");
    }

    public enum Phase {
        STARTING,
        WAITING_USER,
        EXCHANGING,
        STORING
    }
}

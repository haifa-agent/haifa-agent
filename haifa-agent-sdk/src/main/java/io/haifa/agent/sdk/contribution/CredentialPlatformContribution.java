package io.haifa.agent.sdk.contribution;

import io.haifa.agent.credential.api.CredentialBroker;
import java.util.Objects;

/** Product-selected credential lease boundary; credentials themselves never enter the profile. */
public record CredentialPlatformContribution(CredentialBroker broker) {
    public CredentialPlatformContribution {
        broker = Objects.requireNonNull(broker, "broker must not be null");
    }
}

package io.haifa.agent.sdk.contribution;

import io.haifa.agent.credential.api.CredentialBroker;
import io.haifa.agent.sdk.product.ProductCapabilities;
import java.util.Objects;

/** Product-selected credential lease boundary; credentials themselves never enter the profile. */
public final class CredentialPlatformContribution extends AbstractSdkContribution {
    private final CredentialBroker broker;

    public CredentialPlatformContribution(SdkContributionMetadata metadata, CredentialBroker broker) {
        super(metadata);
        if (!ProductCapabilities.CREDENTIAL.equals(metadata.capabilityId())) {
            throw new IllegalArgumentException("credential contribution must provide the credential capability");
        }
        this.broker = Objects.requireNonNull(broker, "broker must not be null");
    }

    public CredentialBroker broker() {
        return broker;
    }
}

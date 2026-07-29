package io.haifa.agent.sdk.contribution;

import io.haifa.agent.sdk.product.ProductCapabilities;
import java.util.Objects;
import java.util.Set;

/** Logical identity of the product-selected command/script runtimes. */
public final class ShellPlatformContribution extends AbstractSdkContribution {
    private final String operatingSystem;
    private final Set<String> scriptLanguages;

    public ShellPlatformContribution(
            SdkContributionMetadata metadata, String operatingSystem, Set<String> scriptLanguages) {
        super(metadata);
        if (!ProductCapabilities.SHELL.equals(metadata.capabilityId())) {
            throw new IllegalArgumentException("shell contribution must provide the shell capability");
        }
        this.operatingSystem = Objects.requireNonNull(operatingSystem, "operatingSystem must not be null")
                .trim();
        if (this.operatingSystem.isEmpty()) throw new IllegalArgumentException("operatingSystem must not be blank");
        this.scriptLanguages = Set.copyOf(Objects.requireNonNull(scriptLanguages, "scriptLanguages must not be null"));
        if (this.scriptLanguages.isEmpty()) throw new IllegalArgumentException("scriptLanguages must not be empty");
    }

    public String operatingSystem() {
        return operatingSystem;
    }

    public Set<String> scriptLanguages() {
        return scriptLanguages;
    }
}

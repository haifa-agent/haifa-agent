package io.haifa.experiments.langgraph4j;

final class UnsupportedFixtureCapabilityException extends IllegalArgumentException {
    private final FixtureCapability capability;

    UnsupportedFixtureCapabilityException(FixtureCapability capability) {
        super("WORKFLOW_CAPABILITY_UNSUPPORTED: " + capability.name());
        this.capability = capability;
    }

    FixtureCapability capability() {
        return capability;
    }
}

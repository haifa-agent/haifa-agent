package io.haifa.agent.runtime.core.bootstrap;

import java.util.Objects;

public final class CapabilityResolutionException extends RuntimeException {
    private final CapabilityResolutionErrorCode code;
    private final String capabilityId;

    public CapabilityResolutionException(CapabilityResolutionErrorCode code, String capabilityId, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.capabilityId = Objects.requireNonNull(capabilityId, "capabilityId must not be null");
    }

    public CapabilityResolutionErrorCode code() {
        return code;
    }

    public String capabilityId() {
        return capabilityId;
    }
}

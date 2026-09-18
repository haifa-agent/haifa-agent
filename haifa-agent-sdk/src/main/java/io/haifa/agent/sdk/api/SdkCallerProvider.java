package io.haifa.agent.sdk.api;

/** Host-authenticated identity lookup used by SDK services. */
@FunctionalInterface
public interface SdkCallerProvider {
    SdkCaller current();

    static SdkCallerProvider defaultPublicUser() {
        return SdkCaller::defaultPublicUser;
    }
}

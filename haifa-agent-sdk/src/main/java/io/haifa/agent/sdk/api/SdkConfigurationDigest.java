package io.haifa.agent.sdk.api;

/** Public helper for computing non-secret frozen configuration identities. */
public final class SdkConfigurationDigest {
    private SdkConfigurationDigest() {}

    public static String sha256(String... canonicalFields) {
        return io.haifa.agent.sdk.internal.CanonicalSdkDigest.sha256(canonicalFields);
    }
}

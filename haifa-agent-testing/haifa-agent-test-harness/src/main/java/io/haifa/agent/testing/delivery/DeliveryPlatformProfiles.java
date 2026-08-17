package io.haifa.agent.testing.delivery;

import io.haifa.agent.testing.harness.PlatformManifest;

/** Autonomous Delivery validation for suite-specific platform extensions. */
final class DeliveryPlatformProfiles {
    private DeliveryPlatformProfiles() {}

    static DeliveryHostProfile requireCurrentHost(PlatformManifest.PlatformProfile profile) {
        return requireHost(profile, System.getProperty("os.name", ""));
    }

    static DeliveryHostProfile requireHost(PlatformManifest.PlatformProfile profile, String osName) {
        String currentPlatform = PlatformManifest.currentPlatform(osName);
        if (!profile.platform().equals(currentPlatform)) {
            throw new IllegalArgumentException("platform combination "
                    + profile.id()
                    + " targets "
                    + profile.platform()
                    + " but current host is "
                    + currentPlatform);
        }
        String terminalBackend = profile.requireString("terminalBackend");
        String sandboxProfile = profile.requireString("sandboxProfile");
        String networkPolicy = profile.requireString("networkPolicy");
        String shell = profile.requireString("shell");
        String isolationAssurance = profile.requireString("isolationAssurance");
        String hostProfile = profile.requireString("hostProfile");
        if (profile.requireInt("maxParallelExternalCalls") != 1) {
            throw new IllegalArgumentException("Autonomous Delivery platform profiles must serialize external calls");
        }
        DeliveryHostProfile resolved = DeliveryHostProfile.require(hostProfile, osName);
        if (!resolved.platform().equals(profile.platform())
                || !resolved.terminalBackend().equals(terminalBackend)
                || !resolved.executionProvider().equals(sandboxProfile)
                || !resolved.networkPolicy().equals(networkPolicy)
                || !resolved.shell().equals(shell)
                || !resolved.isolationAssurance().equals(isolationAssurance)) {
            throw new IllegalArgumentException(
                    "platform combination does not match its DeliveryHostProfile: " + profile.id());
        }
        return resolved;
    }
}

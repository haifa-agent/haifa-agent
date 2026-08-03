package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeliveryCliConfigurationFactoryTest {
    @Test
    void rendersTheExplicitDeepSeekDialectRequiredByTheCurrentCliSchema() {
        String configuration = DeliveryCliConfigurationFactory.render(
                suite(),
                toolchains(),
                DeliveryHostProfile.require("trusted-host-default-v1", "Mac OS X"),
                combination("deepseek"));

        assertAll(
                () -> assertTrue(configuration.contains("dialectId: deepseek-openai-chat")),
                () -> assertTrue(configuration.contains("dialectVersion: \"1.0\"")),
                () -> assertTrue(configuration.contains("nativeStreaming: true")),
                () -> assertTrue(configuration.contains("providerModelId: deepseek-v4-flash")));
    }

    @Test
    void rejectsAnUnconfiguredProviderInsteadOfRenderingDeepSeekCredentialsForIt() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DeliveryCliConfigurationFactory.render(
                        suite(),
                        toolchains(),
                        DeliveryHostProfile.require("trusted-host-default-v1", "Mac OS X"),
                        combination("openai")));
    }

    private static AutonomousDeliverySuiteManifest suite() {
        return new AutonomousDeliverySuiteManifest(
                1,
                "fixture-suite",
                AutonomousDeliveryCaseCatalog.EXPECTED_CATALOG_ID,
                "PHASE_1",
                "fixture-matrix",
                null,
                null,
                new AutonomousDeliverySuiteManifest.Budget(60_000, 4, 8, 4, 1),
                List.of(new AutonomousDeliverySuiteManifest.CaseSelection("04", 1, true)));
    }

    private static DeliveryToolchainSet toolchains() {
        return new DeliveryToolchainSet(
                Path.of("toolchains/java/bin/java"),
                Path.of("toolchains/java/bin/javac"),
                Path.of("toolchains/python/python"),
                Path.of("toolchains/node/node"),
                Path.of("toolchains/go/go"),
                Path.of("toolchains/git/git"),
                Path.of("toolchains/shell/bash"));
    }

    private static AutonomousDeliveryMatrixManifest.Combination combination(String provider) {
        return new AutonomousDeliveryMatrixManifest.Combination(
                "macos-" + provider + "-host-default",
                "macos",
                provider,
                "deepseek-v4-flash",
                "unix-pty",
                "host-guarded",
                "allow",
                "auto",
                "TRUSTED_HOST_ONLY",
                "trusted-host-default-v1",
                1);
    }
}

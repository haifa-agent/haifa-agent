package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AutonomousDeliveryApplicationTest {
    @Test
    void requiresInjectedStandardClientFactory() {
        assertThrows(NullPointerException.class, () -> new AutonomousDeliveryApplication(null));
    }

    @Test
    void freezesResolvedToolchainPaths() {
        Map<String, Path> tools = new LinkedHashMap<>();
        tools.put("java", Path.of("java"));
        AutonomousDeliveryApplication.Options options = new AutonomousDeliveryApplication.Options(
                Path.of("project"),
                Path.of("configuration"),
                Path.of("runs"),
                "0".repeat(40),
                "suite",
                "platform",
                "profile",
                "1".repeat(64),
                1,
                tools);
        tools.put("git", Path.of("git"));

        assertEquals(Map.of("java", Path.of("java")), options.toolchains());
    }
}

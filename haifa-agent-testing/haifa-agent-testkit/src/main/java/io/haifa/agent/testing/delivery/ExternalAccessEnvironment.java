package io.haifa.agent.testing.delivery;

import java.util.Locale;
import java.util.Map;

/** Removes credentials and live external-service opt-ins from deterministic child processes. */
final class ExternalAccessEnvironment {
    private ExternalAccessEnvironment() {}

    static void isolate(Map<String, String> environment) {
        environment.keySet().removeIf(ExternalAccessEnvironment::isExternalAccessVariable);
    }

    static boolean isExternalAccessVariable(String name) {
        String normalized = name.toUpperCase(Locale.ROOT);
        return normalized.contains("LIVE_TEST")
                || (normalized.contains("LIVE_") && normalized.endsWith("_TEST"))
                || normalized.endsWith("_API_KEY")
                || normalized.endsWith("_ACCESS_KEY")
                || normalized.endsWith("_KEY")
                || normalized.endsWith("_TOKEN")
                || normalized.endsWith("_SECRET")
                || normalized.endsWith("_PASSWORD")
                || normalized.endsWith("_CREDENTIAL")
                || normalized.endsWith("_ENDPOINT")
                || normalized.endsWith("_WORKSPACE_ID");
    }
}

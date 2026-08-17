package io.haifa.agent.testing.harness;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Parser for the two public harness actions. */
record HarnessCliOptions(
        String action,
        Path projectRoot,
        Path configRoot,
        Path runRoot,
        String suite,
        String profile,
        String platform,
        RunMode mode,
        Path output,
        Path plan,
        String budgetApproval) {
    static HarnessCliOptions parse(String[] arguments, Map<String, String> environment) {
        if (arguments.length == 0 || (!arguments[0].equals("plan") && !arguments[0].equals("run"))) {
            throw new IllegalArgumentException("first argument must be plan or run");
        }
        String action = arguments[0];
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (int index = 1; index < arguments.length; index += 2) {
            if (!arguments[index].startsWith("--") || index + 1 >= arguments.length) {
                throw new IllegalArgumentException("every option requires a --kebab-case name and value");
            }
            if (values.put(arguments[index], arguments[index + 1]) != null) {
                throw new IllegalArgumentException("duplicate option: " + arguments[index]);
            }
        }
        if (action.equals("run")) {
            rejectUnknown(values, "--plan", "--approve-budget");
            return new HarnessCliOptions(
                    action,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    requiredPath(values, "--plan"),
                    values.get("--approve-budget"));
        }
        rejectUnknown(
                values,
                "--project-root",
                "--config-root",
                "--run-root",
                "--suite",
                "--profile",
                "--platform",
                "--mode",
                "--output");
        Path projectRoot = path(values.get("--project-root"), environment.get("HAIFA_AGENT_ROOT"), Path.of("."));
        Path configRoot = path(
                values.get("--config-root"),
                environment.get("HAIFA_TEST_CONFIG_ROOT"),
                projectRoot.resolve("test-config"));
        Path runRoot = path(values.get("--run-root"), environment.get("HAIFA_TEST_RUN_ROOT"), null);
        if (runRoot == null) throw new IllegalArgumentException("--run-root is required");
        return new HarnessCliOptions(
                action,
                projectRoot,
                configRoot,
                runRoot,
                required(values, "--suite"),
                required(values, "--profile"),
                required(values, "--platform"),
                RunMode.parse(required(values, "--mode")),
                path(values.get("--output"), null, Path.of("execution-plan.json")),
                null,
                null);
    }

    private static void rejectUnknown(Map<String, String> values, String... allowed) {
        java.util.Set<String> accepted = java.util.Set.of(allowed);
        values.keySet().stream()
                .filter(value -> !accepted.contains(value))
                .findFirst()
                .ifPresent(value -> {
                    throw new IllegalArgumentException("unknown option: " + value);
                });
    }

    private static String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static Path requiredPath(Map<String, String> values, String name) {
        return Path.of(required(values, name));
    }

    private static Path path(String explicit, String environment, Path fallback) {
        String value = explicit == null || explicit.isBlank() ? environment : explicit;
        return value == null || value.isBlank() ? fallback : Path.of(value);
    }
}

package io.haifa.agent.testing.harness;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Safe plan file consumed by the public run action. */
public record ExecutionPlanDocument(int schemaVersion, ResolvedTestPlan plan) {
    public ExecutionPlanDocument {
        if (schemaVersion != 2) throw new IllegalArgumentException("execution plan document schemaVersion must be 2");
        Objects.requireNonNull(plan, "plan must not be null").verifyIntegrity();
        request(plan);
        suiteType(plan);
        RunnerArtifact.fromReviewedInput(plan.content().get("runnerArtifact"));
    }

    public static ExecutionPlanDocument freeze(
            TestRunRequest request, ResolvedTestPlan nativePlan, RunnerArtifact runnerArtifact) {
        ResolvedTestPlan plan = Objects.requireNonNull(nativePlan, "nativePlan must not be null")
                .withReviewedInput("request", RequestCoordinates.from(request).reviewedInput())
                .withReviewedInput(
                        "runnerArtifact",
                        Objects.requireNonNull(runnerArtifact, "runnerArtifact must not be null")
                                .reviewedInput());
        return new ExecutionPlanDocument(2, plan);
    }

    public RequestCoordinates request() {
        return request(plan);
    }

    private static RequestCoordinates request(ResolvedTestPlan plan) {
        return RequestCoordinates.fromReviewedInput(plan.content().get("request"));
    }

    public String suiteType() {
        return suiteType(plan);
    }

    private static String suiteType(ResolvedTestPlan plan) {
        Object value = plan.content().get("suiteType");
        if (!(value instanceof String text)) throw new IllegalArgumentException("suiteType must be a string");
        String suiteType = require(text, "suiteType");
        if (!suiteType.equals("critical-path") && !suiteType.equals("autonomous-delivery")) {
            throw new IllegalArgumentException("unsupported suiteType: " + suiteType);
        }
        return suiteType;
    }

    public RunnerArtifact runnerArtifact() {
        return RunnerArtifact.fromReviewedInput(plan.content().get("runnerArtifact"));
    }

    public ResolvedTestPlan nativePlan() {
        return plan.withoutReviewedInput("runnerArtifact").withoutReviewedInput("request");
    }

    public TestRunRequest toRunRequest() {
        return request().toRunRequest();
    }

    public record RequestCoordinates(
            String projectRoot,
            String configRoot,
            String runRoot,
            String suiteRef,
            String agentProfileRef,
            String platformRef,
            RunMode mode) {
        public RequestCoordinates {
            projectRoot = absolute(projectRoot, "projectRoot");
            configRoot = absolute(configRoot, "configRoot");
            runRoot = absolute(runRoot, "runRoot");
            suiteRef = require(suiteRef, "suiteRef");
            agentProfileRef = require(agentProfileRef, "agentProfileRef");
            platformRef = require(platformRef, "platformRef");
            Objects.requireNonNull(mode, "mode must not be null");
        }

        public static RequestCoordinates from(TestRunRequest request) {
            Objects.requireNonNull(request, "request must not be null");
            return new RequestCoordinates(
                    request.projectRoot().toString(),
                    request.configRoot().toString(),
                    request.runRoot().toString(),
                    request.suiteRef(),
                    request.agentProfileRef(),
                    request.platformRef(),
                    request.mode());
        }

        static RequestCoordinates fromReviewedInput(Object value) {
            if (!(value instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("resolved plan must contain request coordinates");
            }
            return new RequestCoordinates(
                    string(map, "projectRoot"),
                    string(map, "configRoot"),
                    string(map, "runRoot"),
                    string(map, "suiteRef"),
                    string(map, "agentProfileRef"),
                    string(map, "platformRef"),
                    RunMode.parse(string(map, "mode")));
        }

        Map<String, Object> reviewedInput() {
            LinkedHashMap<String, Object> input = new LinkedHashMap<>();
            input.put("projectRoot", projectRoot);
            input.put("configRoot", configRoot);
            input.put("runRoot", runRoot);
            input.put("suiteRef", suiteRef);
            input.put("agentProfileRef", agentProfileRef);
            input.put("platformRef", platformRef);
            input.put("mode", mode.name().toLowerCase(java.util.Locale.ROOT));
            return Map.copyOf(input);
        }

        TestRunRequest toRunRequest() {
            return new TestRunRequest(
                    Path.of(projectRoot),
                    Path.of(configRoot),
                    Path.of(runRoot),
                    suiteRef,
                    agentProfileRef,
                    platformRef,
                    mode);
        }

        private static String absolute(String value, String field) {
            Path path = Path.of(require(value, field)).normalize();
            if (!path.isAbsolute()) throw new IllegalArgumentException(field + " must be absolute");
            return path.toString();
        }

        private static String string(Map<?, ?> map, String field) {
            Object value = map.get(field);
            if (!(value instanceof String text)) {
                throw new IllegalArgumentException("request coordinate " + field + " is invalid");
            }
            return text;
        }
    }

    private static String require(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}

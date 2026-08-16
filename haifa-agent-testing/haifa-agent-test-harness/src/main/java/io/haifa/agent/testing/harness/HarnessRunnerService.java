package io.haifa.agent.testing.harness;

import io.haifa.agent.testing.suite.CriticalPathSuiteApplication;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/** Executes an approved plan without exposing suite-internal phases or gates. */
final class HarnessRunnerService {
    private final HarnessPlanService plans = new HarnessPlanService();

    int run(ExecutionPlanDocument document, String budgetApproval) throws Exception {
        return new HarnessLifecycle<>(
                        new HarnessLifecycle.Stages<
                                ExecutionPlanDocument, ExecutionPlanDocument, Integer, Integer, Integer>() {
                            @Override
                            public ExecutionPlanDocument resolve(TestRunRequest request) {
                                document.plan().verifyIntegrity();
                                return document;
                            }

                            @Override
                            public void preflight(TestRunRequest request, ExecutionPlanDocument resolved)
                                    throws Exception {
                                plans.requireCurrent(resolved);
                                if (resolved.mode().requiresBudgetApproval()
                                        && (budgetApproval == null || budgetApproval.isBlank())) {
                                    throw new IllegalArgumentException(
                                            "--approve-budget is required for live and release runs");
                                }
                            }

                            @Override
                            public ExecutionPlanDocument provision(
                                    TestRunRequest request, ExecutionPlanDocument resolved) {
                                return resolved;
                            }

                            @Override
                            public Integer execute(
                                    TestRunRequest request,
                                    ExecutionPlanDocument resolved,
                                    ExecutionPlanDocument prepared)
                                    throws Exception {
                                return executeNative(prepared, budgetApproval);
                            }

                            @Override
                            public Integer grade(
                                    TestRunRequest request, ExecutionPlanDocument resolved, Integer exitCode) {
                                return exitCode;
                            }

                            @Override
                            public Integer finalizeRun(
                                    TestRunRequest request,
                                    ExecutionPlanDocument resolved,
                                    Integer exitCode,
                                    Integer gradedExitCode) {
                                return gradedExitCode;
                            }
                        })
                .run(document.request());
    }

    private int executeNative(ExecutionPlanDocument document, String budgetApproval) throws Exception {
        if (document.suiteType().equals("autonomous-delivery")) {
            return new AutonomousDeliveryRunService().run(document, positiveDecimal(budgetApproval));
        }
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("HAIFA_TEST_APPROVED_PLAN_SHA256", document.plan().sha256());
        environment.put(
                "HAIFA_TEST_APPROVED_MAX_ESTIMATED_COST_USD",
                positiveDecimal(budgetApproval).stripTrailingZeros().toPlainString());
        TestRunRequest request = document.request();
        return new CriticalPathSuiteApplication()
                .run(
                        new CriticalPathSuiteApplication.Options(
                                request.projectRoot(),
                                request.configRoot(),
                                request.runRoot(),
                                request.suiteRef(),
                                request.platformRef(),
                                request.agentProfileRef()),
                        Map.copyOf(environment));
    }

    private static BigDecimal positiveDecimal(String value) {
        try {
            BigDecimal parsed = new BigDecimal(value);
            if (parsed.signum() > 0) return parsed;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("budget approval must be a positive number", exception);
        }
        throw new IllegalArgumentException("budget approval must be a positive number");
    }
}

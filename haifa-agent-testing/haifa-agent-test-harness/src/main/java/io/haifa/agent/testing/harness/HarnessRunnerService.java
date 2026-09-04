package io.haifa.agent.testing.harness;

import io.haifa.agent.cli.StandaloneCodingAgents;
import io.haifa.agent.testing.delivery.AutonomousDeliveryApplication;
import io.haifa.agent.testing.personal.PersonalAssistantSmokeSuiteApplication;
import io.haifa.agent.testing.repository.RepositoryRevision;
import io.haifa.agent.testing.suite.CriticalPathSuiteApplication;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Executes an approved plan without exposing suite-internal phases or gates. */
final class HarnessRunnerService {
    private final HarnessPlanService plans = new HarnessPlanService();
    private final RunnerArtifact currentRunner;

    HarnessRunnerService(RunnerArtifact currentRunner) {
        this.currentRunner = currentRunner;
    }

    int run(ExecutionPlanDocument document, String budgetApproval, Consumer<String> progressOutput) throws Exception {
        document.plan().verifyIntegrity();
        ResolvedRunContext context = plans.resolveAndVerify(document, currentRunner);
        if (document.request().mode().requiresBudgetApproval()
                && (budgetApproval == null || budgetApproval.isBlank())) {
            throw new IllegalArgumentException("--approve-budget is required for live and release runs");
        }
        BigDecimal approvedBudget =
                document.request().mode().requiresBudgetApproval() ? positiveDecimal(budgetApproval) : BigDecimal.ONE;
        RunEvidenceWriter.NativeResult nativeResult = executeNative(context, approvedBudget, progressOutput);
        RunEvidenceWriter.PublishedRun published = new RunEvidenceWriter()
                .write(
                        context,
                        nativeResult,
                        approvedBudget,
                        RepositoryRevision.inspect(context.request().projectRoot()),
                        RepositoryRevision.inspect(context.request().configRoot()));
        if (context instanceof ResolvedRunContext.AutonomousDelivery delivery && !published.successful()) {
            throw new IllegalStateException("Phase "
                    + delivery.suite().phase()
                    + " gate failed; immutable evidence: "
                    + published.evidenceRoot());
        }
        return published.successful() ? 0 : 1;
    }

    private RunEvidenceWriter.NativeResult executeNative(
            ResolvedRunContext context, BigDecimal approvedBudget, Consumer<String> progressOutput) throws Exception {
        if (context instanceof ResolvedRunContext.AutonomousDelivery delivery) {
            return executeAutonomousDelivery(delivery, approvedBudget, progressOutput);
        }
        if (context instanceof ResolvedRunContext.PersonalAssistantSmoke personalAssistantSmoke) {
            return new PersonalAssistantSmokeSuiteApplication().run(personalAssistantSmoke);
        }
        return new CriticalPathSuiteApplication()
                .run((ResolvedRunContext.CriticalPath) context, approvedBudget, Map.copyOf(System.getenv()));
    }

    private RunEvidenceWriter.NativeResult executeAutonomousDelivery(
            ResolvedRunContext.AutonomousDelivery context, BigDecimal approvedBudget, Consumer<String> progressOutput)
            throws Exception {
        ExecutableResolver tools = new ExecutableResolver(System.getenv());
        Map<String, Path> toolchains = new LinkedHashMap<>();
        toolchains.put("java", tools.require("java", "HAIFA_JAVA_EXECUTABLE", "java"));
        toolchains.put("javac", tools.require("javac", "HAIFA_JAVAC_EXECUTABLE", "javac"));
        toolchains.put("python", tools.require("python", "HAIFA_PYTHON_EXECUTABLE", "python", "python3"));
        toolchains.put("node", tools.require("node", "HAIFA_NODE_EXECUTABLE", "node"));
        toolchains.put("go", tools.require("go", "HAIFA_GO_EXECUTABLE", "go"));
        toolchains.put("git", tools.require("git", "HAIFA_GIT_EXECUTABLE", "git"));
        toolchains.put("shell", tools.require("shell", "HAIFA_SHELL_EXECUTABLE", "pwsh", "powershell", "bash", "sh"));
        return new AutonomousDeliveryApplication(StandaloneCodingAgents.factory())
                .run(context, approvedBudget.longValueExact(), toolchains, progressOutput);
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

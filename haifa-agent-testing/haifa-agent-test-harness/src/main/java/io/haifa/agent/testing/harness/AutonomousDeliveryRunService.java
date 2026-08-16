package io.haifa.agent.testing.harness;

import io.haifa.agent.cli.StandaloneCodingAgents;
import io.haifa.agent.testing.delivery.AutonomousDeliveryApplication;
import io.haifa.agent.testing.repository.RepositoryRevision;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Hides campaign and phase mechanics behind the public run action. */
final class AutonomousDeliveryRunService {
    int run(ExecutionPlanDocument document, BigDecimal approvedBudget) throws Exception {
        TestRunRequest request = document.request();
        RepositoryRevision revision = RepositoryRevision.inspect(request.projectRoot());
        ExecutableResolver tools = new ExecutableResolver(System.getenv());
        Map<String, Path> toolchains = new LinkedHashMap<>();
        toolchains.put("java", tools.require("java", "HAIFA_JAVA_EXECUTABLE", "java"));
        toolchains.put("javac", tools.require("javac", "HAIFA_JAVAC_EXECUTABLE", "javac"));
        toolchains.put("python", tools.require("python", "HAIFA_PYTHON_EXECUTABLE", "python", "python3"));
        toolchains.put("node", tools.require("node", "HAIFA_NODE_EXECUTABLE", "node"));
        toolchains.put("go", tools.require("go", "HAIFA_GO_EXECUTABLE", "go"));
        toolchains.put("git", tools.require("git", "HAIFA_GIT_EXECUTABLE", "git"));
        toolchains.put("shell", tools.require("shell", "HAIFA_SHELL_EXECUTABLE", "pwsh", "powershell", "bash", "sh"));
        new AutonomousDeliveryApplication(StandaloneCodingAgents.factory())
                .run(new AutonomousDeliveryApplication.Options(
                        request.projectRoot(),
                        request.configRoot(),
                        request.runRoot(),
                        revision.commit(),
                        request.suiteRef(),
                        request.platformRef(),
                        request.agentProfileRef(),
                        document.plan().sha256(),
                        approvedBudget.longValueExact(),
                        toolchains));
        return 0;
    }
}

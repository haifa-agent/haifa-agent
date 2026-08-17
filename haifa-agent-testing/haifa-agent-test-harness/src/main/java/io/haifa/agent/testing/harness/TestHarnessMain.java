package io.haifa.agent.testing.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;

/** Public shaded-JAR entry point. Only plan and run are user-facing actions. */
public final class TestHarnessMain {
    private static final ObjectMapper JSON = new ObjectMapper();

    private TestHarnessMain() {}

    public static void main(String[] arguments) {
        try {
            int exitCode = execute(HarnessCliOptions.parse(arguments, System.getenv()));
            if (exitCode != 0) System.exit(exitCode);
        } catch (IllegalArgumentException exception) {
            System.err.println("Invalid test harness request: " + exception.getMessage());
            System.exit(2);
        } catch (Exception exception) {
            System.err.println("Test harness failed: " + exception.getClass().getSimpleName());
            System.exit(1);
        }
    }

    static int execute(HarnessCliOptions options) throws Exception {
        RunnerArtifact runnerArtifact = RunnerArtifact.current();
        if (options.action().equals("plan")) {
            ExecutionPlanDocument document = new HarnessPlanService()
                    .resolve(
                            new TestRunRequest(
                                    options.projectRoot(),
                                    options.configRoot(),
                                    options.runRoot(),
                                    options.suite(),
                                    options.profile(),
                                    options.platform(),
                                    options.mode()),
                            runnerArtifact);
            Path output = options.output().toAbsolutePath().normalize();
            if (output.getParent() != null) Files.createDirectories(output.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), document);
            System.out.printf(
                    "Plan sha256=%s runnerSha256=%s file=%s%n",
                    document.plan().sha256(), document.runnerArtifact().sha256(), output);
            return 0;
        }
        ExecutionPlanDocument document = JSON.readValue(options.plan().toFile(), ExecutionPlanDocument.class);
        return new HarnessRunnerService(runnerArtifact).run(document, options.budgetApproval());
    }
}

package io.haifa.example.sdk.advanced;

import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ModelInvocationException;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.starter.HaifaAgentStarter;
import io.haifa.example.sdk.support.DeterministicExampleSupport;
import java.time.Duration;

/** Handles synchronous assembly rejection, bounded waiting, and safe asynchronous Run errors. */
public final class SafeErrorHandlingExample {
    private static final String MISSING_KEY = "HAIFA_EXAMPLE_INTENTIONALLY_MISSING_KEY_9F3C";

    private SafeErrorHandlingExample() {}

    public static void main(String[] arguments) throws Exception {
        demonstrateSynchronousCredentialFailure();

        var failingModel = DeterministicExampleSupport.scripted(request -> {
            throw new ModelInvocationException(
                    ModelErrorCategory.AUTHENTICATION_FAILED,
                    false,
                    401,
                    "example_authentication_failed",
                    new ModelCallId(request.callId().value()),
                    "example model authentication failed",
                    null);
        });
        try (var agent = HaifaAgentStarter.builder()
                .model(failingModel, DeterministicExampleSupport.snapshot())
                .build()) {
            var conversation = agent.conversations()
                    .start(new StartConversationCommand("safe-error-start", "Safe error", "Reply with ready."));
            var runId = conversation.activeRunId().orElseThrow();
            var completed = agent.runs().await(runId, Duration.ofSeconds(5));
            if (completed.isEmpty()) {
                agent.runs().handle(runId).cancel();
                System.out.println("Run exceeded the client wait budget; cancellation requested.");
                return;
            }
            printTerminalOutcome(completed.orElseThrow());
        }
    }

    private static void demonstrateSynchronousCredentialFailure() {
        try {
            HaifaAgentStarter.builder()
                    .credentialEnvironmentVariable(MISSING_KEY)
                    .build()
                    .close();
        } catch (IllegalStateException exception) {
            System.out.println("Synchronous setup failure: credential is not configured.");
        }
    }

    private static void printTerminalOutcome(AgentRunSnapshot snapshot) {
        if (snapshot.error().isPresent()) {
            var error = snapshot.error().orElseThrow();
            System.out.printf(
                    "Run failed safely: code=%s category=%s retryability=%s diagnosticId=%s%n",
                    error.code(),
                    error.category(),
                    error.retryability(),
                    error.optionalDiagnosticId().orElse("none"));
            return;
        }
        System.out.println("Run succeeded: " + snapshot.output().orElseThrow());
    }
}

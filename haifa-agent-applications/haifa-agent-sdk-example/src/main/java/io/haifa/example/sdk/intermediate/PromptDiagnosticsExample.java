package io.haifa.example.sdk.intermediate;

import io.haifa.example.sdk.support.DeterministicExampleSupport;

/** Reads process-local redacted Prompt composition facts after one completed Run. */
public final class PromptDiagnosticsExample {
    private PromptDiagnosticsExample() {}

    public static void main(String[] arguments) throws Exception {
        try (var agent = DeterministicExampleSupport.inMemory()) {
            var response = agent.chat("Give a short answer.").await();
            var diagnostics = agent.runs().promptDiagnostics(response.runId());
            diagnostics
                    .components()
                    .forEach(component -> System.out.printf(
                            "%d %s %s/%s %s %s tokens=%d%n",
                            component.order(),
                            component.componentId(),
                            component.layer(),
                            component.role(),
                            component.source(),
                            component.contentDigest(),
                            component.estimatedTokens()));
        }
    }
}

package io.haifa.agent.testing.sdk;

import io.haifa.agent.sdk.diagnostics.PromptDiagnosticSource;
import io.haifa.agent.sdk.diagnostics.PromptDiagnostics;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Dependency-free assertions for redacted SDK diagnostics and trajectories. */
public final class SdkDiagnosticAssertions {
    private SdkDiagnosticAssertions() {}

    public static void assertSources(PromptDiagnostics diagnostics, PromptDiagnosticSource... expected) {
        Objects.requireNonNull(diagnostics, "diagnostics must not be null");
        var actual = diagnostics.components().stream()
                .map(component -> component.source())
                .toList();
        for (PromptDiagnosticSource source : expected) {
            if (!actual.contains(source)) throw new AssertionError("missing Prompt diagnostic source: " + source);
        }
    }

    public static void assertContainsDigest(PromptDiagnostics diagnostics, String digest) {
        Objects.requireNonNull(diagnostics, "diagnostics must not be null");
        if (diagnostics.components().stream()
                .noneMatch(component -> component.contentDigest().equals(digest))) {
            throw new AssertionError("missing Prompt component digest");
        }
    }

    public static void assertModelCallCount(ScriptedAgentChatModel model, int expected) {
        int actual =
                Objects.requireNonNull(model, "model must not be null").calls().size();
        if (actual != expected) throw new AssertionError("expected " + expected + " model call(s), got " + actual);
    }

    public static void assertToolNames(ModelCallTrace call, String... expected) {
        var actual = Objects.requireNonNull(call, "call must not be null").toolNames();
        if (!actual.equals(List.of(expected))) {
            throw new AssertionError("unexpected disclosed Tool trajectory");
        }
    }

    public static void assertToolInvocationCount(FakeJavaTool<?, ?> tool, int expected) {
        int actual = Objects.requireNonNull(tool, "tool must not be null")
                .inputDigests()
                .size();
        if (actual != expected) throw new AssertionError("expected " + expected + " Tool call(s), got " + actual);
    }

    public static void assertNoSensitiveText(Object diagnostic, String... forbiddenValues) {
        String rendered = Objects.toString(diagnostic, "");
        Arrays.stream(forbiddenValues)
                .filter(Objects::nonNull)
                .filter(value -> !value.isEmpty())
                .filter(rendered::contains)
                .findFirst()
                .ifPresent(value -> {
                    throw new AssertionError("diagnostic contains forbidden sensitive text");
                });
    }
}

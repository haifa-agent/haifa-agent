package io.haifa.agent.testing.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.sdk.diagnostics.PromptDiagnosticComponent;
import io.haifa.agent.sdk.diagnostics.PromptDiagnosticSource;
import io.haifa.agent.sdk.diagnostics.PromptDiagnostics;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SdkTestkitTest {
    @Test
    void fixedBoundariesAreDeterministic() {
        var ids = FixedSdkBoundaries.identifiers("sdk-test");
        assertEquals("sdk-test-1", ids.nextValue());
        assertEquals("sdk-test-2", ids.nextValue());
        assertEquals(Instant.EPOCH, FixedSdkBoundaries.time(Instant.EPOCH).now());
        assertEquals(
                "alice",
                FixedSdkBoundaries.caller("tenant", "alice")
                        .current()
                        .principal()
                        .principalId());
    }

    @Test
    void scriptedModelRejectsUnexpectedCallsAndDoesNotRetainMessageText() {
        var model = ScriptedAgentChatModel.builder()
                .thenRespond(new AgentChatResponse(
                        "response",
                        "model",
                        "answer",
                        List.of(),
                        ModelFinishReason.STOP,
                        ModelUsage.unpriced(1, 1),
                        "",
                        Map.of()))
                .build();
        String secretMessage = "private-prompt-value";
        model.invoke(new AgentChatRequest(
                new ModelCallId("call-1"),
                new io.haifa.agent.core.run.AgentRunId("run-1"),
                1,
                1,
                snapshot(),
                List.of(ModelMessage.text(ModelMessageRole.USER, secretMessage)),
                List.of(),
                128,
                Duration.ofSeconds(5),
                Map.of()));

        model.assertExhausted();
        assertEquals(1, model.calls().size());
        SdkDiagnosticAssertions.assertModelCallCount(model, 1);
        SdkDiagnosticAssertions.assertToolNames(model.calls().getFirst());
        SdkDiagnosticAssertions.assertNoSensitiveText(model.calls(), secretMessage);
        assertThrows(
                AssertionError.class,
                () -> model.invoke(new AgentChatRequest(
                        new ModelCallId("call-2"),
                        new io.haifa.agent.core.run.AgentRunId("run-2"),
                        1,
                        1,
                        snapshot(),
                        List.of(ModelMessage.text(ModelMessageRole.USER, "another")),
                        List.of(),
                        128,
                        Duration.ofSeconds(5),
                        Map.of())));
    }

    @Test
    void fakeToolRecordsOnlyInputDigestsAndCanInjectSafeFailure() {
        var spec =
                JavaToolSpec.builder("echo", Input.class, Output.class).pure().build();
        var responding = FakeJavaTool.responding(spec, new Output("ok"));
        assertEquals(List.of(), responding.inputDigests());
        var failing = FakeJavaTool.failing(spec, new IllegalStateException("SAFE_FAILURE"));
        assertEquals("echo", failing.spec().name().value());
        var context = new io.haifa.agent.sdk.tool.JavaToolContext(
                new io.haifa.agent.core.run.AgentRunId("run-1"),
                new io.haifa.agent.core.reference.TenantRef("tenant"),
                new io.haifa.agent.core.reference.PrincipalRef("alice", "user"),
                Instant.parse("2026-08-12T00:00:00Z"),
                Optional.empty(),
                Optional.empty(),
                () -> false,
                List.of());
        responding.invoke(new Input("private-tool-value"), context);
        assertEquals(1, responding.inputDigests().size());
        SdkDiagnosticAssertions.assertToolInvocationCount(responding, 1);
        SdkDiagnosticAssertions.assertNoSensitiveText(responding.inputDigests(), "private-tool-value");
        assertEquals(
                "SAFE_FAILURE",
                assertThrows(IllegalStateException.class, () -> failing.invoke(new Input("value"), context))
                        .getMessage());
    }

    @Test
    void diagnosticAssertionsRejectSensitiveValues() {
        var runId = new io.haifa.agent.core.run.AgentRunId("run-1");
        var diagnostics = new PromptDiagnostics(
                runId,
                true,
                PromptDiagnostics.AVAILABLE,
                OptionalInt.of(1),
                List.of(new PromptDiagnosticComponent(
                        0,
                        "runtime-safety",
                        "SYSTEM_SAFETY",
                        "SYSTEM",
                        "1.0",
                        "sha256:" + "a".repeat(64),
                        8,
                        PromptDiagnosticSource.RUNTIME_SAFETY)));
        SdkDiagnosticAssertions.assertSources(diagnostics, PromptDiagnosticSource.RUNTIME_SAFETY);
        SdkDiagnosticAssertions.assertContainsDigest(diagnostics, "sha256:" + "a".repeat(64));
        SdkDiagnosticAssertions.assertNoSensitiveText(diagnostics, "secret-value", "/Users/private/workspace");
        assertThrows(
                AssertionError.class,
                () -> SdkDiagnosticAssertions.assertNoSensitiveText("prefix secret-value suffix", "secret-value"));
    }

    record Input(String value) {}

    record Output(String value) {}

    private static ResolvedModelSnapshot snapshot() {
        return ResolvedModelSnapshot.create(
                new ModelProviderId("test"),
                "1.0.0",
                new ModelDefinitionId("test-model"),
                "1.0.0",
                "test-model",
                "test-adapter",
                "1.0.0",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                ModelApiBindingDefinition.STANDARD_DIALECT,
                URI.create("https://model.invalid"),
                new CredentialRef("env://TEST_KEY"),
                false,
                Set.of(ModelCapability.TEXT_CHAT),
                8_192,
                1_024,
                Map.of(),
                Map.of());
    }
}

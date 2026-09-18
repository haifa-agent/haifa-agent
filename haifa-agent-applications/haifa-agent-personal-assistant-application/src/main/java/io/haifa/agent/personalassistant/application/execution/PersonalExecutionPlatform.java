package io.haifa.agent.personalassistant.application.execution;

import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.execution.core.tool.ExecutionToolDefinitionFactory;
import io.haifa.agent.execution.core.tool.ExecutionToolProvider;
import io.haifa.agent.execution.core.tool.ScriptRuntimeResolver;
import io.haifa.agent.policy.api.ApprovalVerificationService;
import io.haifa.agent.policy.api.PolicyDigest;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.sdk.contribution.ApprovalPlatformContribution;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolDefinition;
import java.util.Objects;

/** Product assembly component for the shared execution Tool and its approval capability. */
public record PersonalExecutionPlatform(
        ToolDefinition definition,
        ExecutionToolProvider provider,
        PersonalShellRuntime shellRuntime,
        ApprovalPlatformContribution approval) {
    private static final int MAX_APPROVAL_PROMPT_LENGTH = 2_048;
    private static final int MAX_ARGS_SUMMARY_LENGTH = 256;

    public static PersonalExecutionPlatform create(
            ExecutionToolProvider provider,
            SandboxProfile profile,
            ScriptRuntimeResolver runtimes,
            ApprovalVerificationService approvalVerification) {
        Objects.requireNonNull(provider);
        Objects.requireNonNull(profile);
        Objects.requireNonNull(runtimes);
        String profileIdentity = profile.ref().value() + "@" + profile.ref().version();
        ToolDefinition definition = ExecutionToolDefinitionFactory.create(
                profileIdentity,
                provider.configurationIdentity(),
                provider.scratchSpecDigest(),
                true,
                false,
                runtimes.languages());
        return new PersonalExecutionPlatform(
                definition,
                provider,
                new PersonalShellRuntime(runtimes.operatingSystem().name(), runtimes.languages()),
                new ApprovalPlatformContribution(approvalVerification));
    }

    public String approvalPrompt(FrozenToolBinding binding, ToolCall call, boolean reauthentication) {
        if (!"execution_run".equals(binding.definition().name().value())) {
            return io.haifa.agent.sdk.contribution.ProductApprovalPromptFormatter.defaultFormatter()
                    .format(binding, call, reauthentication);
        }
        var arguments = call.arguments().values();
        String mode = safe(arguments.get("mode"));
        String language = safe(arguments.getOrDefault("language", "default-shell"));
        String purpose = safe(arguments.get("purpose"));
        String content = safe(arguments.get("content"));
        String args = safe(arguments.getOrDefault("args", java.util.List.of()));
        String timeout = safe(arguments.getOrDefault("timeoutMillis", 15_000));
        String digest = PolicyDigest.sha256Fields(java.util.List.of(mode, language, content, args, purpose, timeout));
        String summary = (reauthentication ? "Reauthenticate and approve execution" : "Approve execution")
                + "\nMode: " + mode
                + "\nLanguage: " + language
                + "\nPurpose: " + purpose
                + "\nArgs: " + boundedValue(args, MAX_ARGS_SUMMARY_LENGTH)
                + "\nTimeout: " + timeout + " ms"
                + "\nOutput: bounded and redacted"
                + "\nWorkspace: application-owned; the model cannot select cwd"
                + "\nProvider: trusted host process; no strong isolation; network disconnection is not guaranteed"
                + "\nInvocation digest: " + digest
                + "\nRisks: HIGH, PROCESS_EXECUTION, NON_IDEMPOTENT, host access; approve once or reject";
        return boundedContent(summary, content);
    }

    static String boundedContent(String summary, String content) {
        String label = "\nFull content:\n";
        String complete = summary + label + content;
        if (complete.length() <= MAX_APPROVAL_PROMPT_LENGTH) return complete;
        String marker =
                "\n[Content truncated; original length=" + content.length() + "; exact invocation digest shown above]";
        int available = MAX_APPROVAL_PROMPT_LENGTH - summary.length() - label.length() - marker.length();
        if (available < 0) {
            throw new IllegalArgumentException("execution approval summary exceeds safe display limit");
        }
        if (available > 0 && available < content.length() && Character.isHighSurrogate(content.charAt(available - 1))) {
            available--;
        }
        return summary + label + content.substring(0, available).stripTrailing() + marker;
    }

    private static String boundedValue(String value, int maximumLength) {
        if (value.length() <= maximumLength) return value;
        String marker = "...[truncated]";
        int end = maximumLength - marker.length();
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end) + marker;
    }

    private static String safe(Object value) {
        String text = String.valueOf(value);
        String withoutAnsi = text.replaceAll("\\u001B\\[[;?0-9]*[ -/]*[@-~]", "");
        StringBuilder safe = new StringBuilder(withoutAnsi.length());
        withoutAnsi.codePoints().forEach(codePoint -> {
            if (codePoint == '\n' || codePoint == '\r' || codePoint == '\t' || !Character.isISOControl(codePoint)) {
                safe.appendCodePoint(codePoint);
            }
        });
        return safe.toString();
    }
}

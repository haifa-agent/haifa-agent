package io.haifa.agent.personalassistant.application.execution;

import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.execution.api.ToolOutputPreviewPublisher;
import io.haifa.agent.execution.core.tool.ExecutionToolDefinitionFactory;
import io.haifa.agent.execution.core.tool.ExecutionToolProvider;
import io.haifa.agent.execution.core.tool.ScriptRuntimeResolver;
import io.haifa.agent.policy.api.ApprovalVerificationService;
import io.haifa.agent.policy.api.PolicyDigest;
import io.haifa.agent.runtime.api.ApprovalPresentation;
import io.haifa.agent.runtime.api.ApprovalPrompt;
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
    private static final int MAX_PRESENTATION_CONTENT_LENGTH = 16_000;
    private static final int MAX_APPROVAL_TITLE_LENGTH = 256;
    private static final int MAX_PURPOSE_LENGTH = 512;
    private static final int MAX_CONTENT_TYPE_LENGTH = 64;
    private static final int MAX_FACT_VALUE_LENGTH = 512;

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

    public ApprovalPrompt approvalPrompt(FrozenToolBinding binding, ToolCall call, boolean reauthentication) {
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
        String displayLanguage = boundedValue(displayLanguage(language), MAX_CONTENT_TYPE_LENGTH);
        String title = boundedValue(
                "SCRIPT".equals(mode) ? "执行 " + displayLanguage + " 脚本" : "执行 " + displayLanguage + " 命令",
                MAX_APPROVAL_TITLE_LENGTH);
        String presentationPurpose =
                purpose.isBlank() ? "Agent 请求在本机执行命令以继续任务。" : boundedValue(purpose, MAX_PURPOSE_LENGTH);
        String presentationContent = boundedValue(content, MAX_PRESENTATION_CONTENT_LENGTH);
        return new ApprovalPrompt(
                boundedContent(summary, content),
                presentationContent.isBlank()
                        ? java.util.Optional.empty()
                        : java.util.Optional.of(new ApprovalPresentation(
                                title,
                                presentationPurpose,
                                displayLanguage,
                                presentationContent,
                                java.util.List.of(
                                        new ApprovalPresentation.Fact("执行位置", "本机环境"),
                                        new ApprovalPresentation.Fact("工作目录", "当前项目"),
                                        new ApprovalPresentation.Fact("网络访问", "未请求"),
                                        new ApprovalPresentation.Fact("说明", "本次批准仅适用于这一次执行")),
                                java.util.List.of(
                                        new ApprovalPresentation.Fact("模式", nonBlank(mode)),
                                        new ApprovalPresentation.Fact("语言", nonBlank(language)),
                                        new ApprovalPresentation.Fact("参数", nonBlank(args)),
                                        new ApprovalPresentation.Fact("超时", nonBlank(timeout + " ms")),
                                        new ApprovalPresentation.Fact("提供方", "受信宿主进程，无强隔离"),
                                        new ApprovalPresentation.Fact("调用摘要", digest)),
                                java.util.Optional.of("HIGH"))));
    }

    private static String nonBlank(String value) {
        String bounded = boundedValue(value, MAX_FACT_VALUE_LENGTH);
        return bounded.isBlank() ? "—" : bounded;
    }

    private static String displayLanguage(String language) {
        return switch (language) {
            case "powershell", "pwsh" -> "PowerShell";
            case "default-shell" -> "系统 Shell";
            case "bash" -> "Bash";
            case "sh" -> "Shell";
            case "cmd" -> "CMD";
            default -> language.isBlank() ? "系统 Shell" : language;
        };
    }

    public ToolOutputPreviewPublisher previewPublisher() {
        return provider.previewPublisher();
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

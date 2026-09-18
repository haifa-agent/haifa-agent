package io.haifa.agent.runtime.core.recovery;

import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.step.AgentStep;
import io.haifa.agent.core.step.AgentStepStatus;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallStatus;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Builds a bounded user-visible partial-completion summary from safe persisted facts. */
public final class TerminalFailureSummary {
    private static final int MAXIMUM_COMPLETED_ITEMS = 5;
    private static final int MAXIMUM_ITEM_CHARACTERS = 160;

    private TerminalFailureSummary() {}

    public static String create(AgentError error, List<ToolCall> toolCalls, List<AgentStep> steps) {
        Objects.requireNonNull(error, "error must not be null");
        Objects.requireNonNull(toolCalls, "toolCalls must not be null");
        Objects.requireNonNull(steps, "steps must not be null");

        List<String> completed = completedPurposes(toolCalls);
        String unfinished = unfinishedPurpose(toolCalls).orElse("");
        Optional<AgentError> latestStepError = steps.stream()
                .filter(step -> step.status() == AgentStepStatus.FAILED)
                .sorted(java.util.Comparator.comparingInt(AgentStep::sequence).reversed())
                .map(AgentStep::error)
                .flatMap(Optional::stream)
                .map(value -> value.error())
                .findFirst();
        boolean chinese = containsHan(String.join(" ", completed) + " " + unfinished);
        boolean safetyBlocked = latestStepError
                .map(value -> value.details().values().stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .map(text -> text.toLowerCase(Locale.ROOT))
                        .anyMatch(text -> text.contains("blocked by the execution safety policy")))
                .orElse(false);
        return chinese
                ? chinese(error, completed, unfinished, latestStepError, safetyBlocked)
                : english(error, completed, unfinished, latestStepError, safetyBlocked);
    }

    private static List<String> completedPurposes(List<ToolCall> toolCalls) {
        LinkedHashSet<String> purposes = new LinkedHashSet<>();
        toolCalls.stream()
                .filter(call -> call.status() == ToolCallStatus.COMPLETED)
                .sorted(java.util.Comparator.comparing(ToolCall::requestedAt))
                .map(TerminalFailureSummary::purpose)
                .flatMap(Optional::stream)
                .forEach(purposes::add);
        return purposes.stream().limit(MAXIMUM_COMPLETED_ITEMS).toList();
    }

    private static Optional<String> unfinishedPurpose(List<ToolCall> toolCalls) {
        return toolCalls.reversed().stream()
                .filter(call -> call.status() != ToolCallStatus.COMPLETED)
                .map(call -> purpose(call).orElseGet(() -> bounded(call.toolName())))
                .findFirst();
    }

    private static Optional<String> purpose(ToolCall call) {
        Object value = call.arguments().values().get("purpose");
        if (!(value instanceof String text) || text.isBlank()) return Optional.empty();
        return Optional.of(bounded(text));
    }

    private static String chinese(
            AgentError error,
            List<String> completed,
            String unfinished,
            Optional<AgentError> latestStepError,
            boolean safetyBlocked) {
        StringBuilder text = new StringBuilder("任务未完全完成，但已保留本次运行中成功完成的结果。\n\n已完成：\n");
        appendItems(text, completed, "没有可确认的成功工具步骤。");
        text.append("\n未完成：\n- ")
                .append(unfinished.isBlank() ? "后续执行步骤" : unfinished)
                .append("\n- 原因：")
                .append(
                        safetyBlocked
                                ? "工具请求被执行安全策略拒绝；重复尝试后运行已停止。"
                                : latestStepError
                                        .map(value -> value.message() + "（"
                                                + value.code().wireCode() + "）")
                                        .orElse(error.message()));
        text.append("\n\n需要你处理：\n- 请在确认安全后手动完成“")
                .append(unfinished.isBlank() ? "未完成步骤" : unfinished)
                .append("”，再检查运行结果。")
                .append("\n- 也可以调整要求后重新提交，让智能体采用其他可执行的方式。")
                .append("\n\n错误：")
                .append(error.code().wireCode());
        error.optionalDiagnosticId().ifPresent(value -> text.append("\n诊断编号：").append(value));
        return text.toString();
    }

    private static String english(
            AgentError error,
            List<String> completed,
            String unfinished,
            Optional<AgentError> latestStepError,
            boolean safetyBlocked) {
        StringBuilder text = new StringBuilder(
                "The task did not fully complete, but successful results from this run were kept.\n\nCompleted:\n");
        appendItems(text, completed, "No successful tool step could be confirmed.");
        text.append("\nNot completed:\n- ")
                .append(unfinished.isBlank() ? "The remaining execution step" : unfinished)
                .append("\n- Reason: ")
                .append(
                        safetyBlocked
                                ? "The tool request was blocked by the execution safety policy, and the run stopped after repeated attempts."
                                : latestStepError
                                        .map(value -> value.message() + " ("
                                                + value.code().wireCode() + ")")
                                        .orElse(error.message()));
        text.append("\n\nAction needed:\n- After confirming it is safe, complete “")
                .append(unfinished.isBlank() ? "the unfinished step" : unfinished)
                .append("” manually and verify the result.")
                .append("\n- You can also revise the request and retry with another executable approach.")
                .append("\n\nError: ")
                .append(error.code().wireCode());
        error.optionalDiagnosticId()
                .ifPresent(value -> text.append("\nDiagnostic ID: ").append(value));
        return text.toString();
    }

    private static void appendItems(StringBuilder text, List<String> items, String empty) {
        if (items.isEmpty()) {
            text.append("- ").append(empty).append('\n');
            return;
        }
        items.forEach(item -> text.append("- ").append(item).append('\n'));
    }

    private static String bounded(String value) {
        String normalized =
                value.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAXIMUM_ITEM_CHARACTERS) return normalized;
        return normalized.substring(0, MAXIMUM_ITEM_CHARACTERS - 1) + "…";
    }

    private static boolean containsHan(String value) {
        return value.codePoints()
                .mapToObj(Character.UnicodeScript::of)
                .anyMatch(script -> script == Character.UnicodeScript.HAN);
    }
}

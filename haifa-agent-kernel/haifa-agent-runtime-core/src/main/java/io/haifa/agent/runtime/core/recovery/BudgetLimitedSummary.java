package io.haifa.agent.runtime.core.recovery;

import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallStatus;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Builds a bounded partial-completion summary when a configured Run budget stops further work. */
public final class BudgetLimitedSummary {
    private static final int MAXIMUM_COMPLETED_ITEMS = 5;
    private static final int MAXIMUM_ITEM_CHARACTERS = 160;

    private BudgetLimitedSummary() {}

    public static String create(String resource, long used, long limit, List<ToolCall> toolCalls) {
        String safeResource = Objects.requireNonNull(resource, "resource must not be null")
                .replaceAll("[^A-Za-z0-9_]", "_")
                .toUpperCase(Locale.ROOT);
        List<String> completed = completedSteps(Objects.requireNonNull(toolCalls, "toolCalls must not be null"));
        boolean chinese = completed.stream().anyMatch(BudgetLimitedSummary::containsHan);
        return chinese ? chinese(safeResource, used, limit, completed) : english(safeResource, used, limit, completed);
    }

    private static String chinese(String resource, long used, long limit, List<String> completed) {
        StringBuilder text = new StringBuilder("任务已在配置的资源上限处受控停止，成功完成的结果均已保留。\n\n已完成：\n");
        appendItems(text, completed, "没有可确认的成功工具步骤。");
        return text.append("\n停止原因：\n- 限制资源：")
                .append(resource)
                .append("\n- 使用量：")
                .append(used)
                .append(" / ")
                .append(limit)
                .append("\n\n下一步：\n- 检查已有结果；如需继续，请明确提高预算后新建后续 Run。")
                .toString();
    }

    private static String english(String resource, long used, long limit, List<String> completed) {
        StringBuilder text = new StringBuilder(
                "The task stopped at its configured resource limit, and successful results from this run were kept.\n\nCompleted:\n");
        appendItems(text, completed, "No successful tool step could be confirmed.");
        return text.append("\nStop reason:\n- Limiting resource: ")
                .append(resource)
                .append("\n- Usage: ")
                .append(used)
                .append(" / ")
                .append(limit)
                .append(
                        "\n\nNext step:\n- Review the retained results. To continue, start a follow-up Run with an explicitly larger budget.")
                .toString();
    }

    private static List<String> completedSteps(List<ToolCall> toolCalls) {
        LinkedHashSet<String> steps = new LinkedHashSet<>();
        toolCalls.stream()
                .filter(call -> call.status() == ToolCallStatus.COMPLETED)
                .sorted(java.util.Comparator.comparing(ToolCall::requestedAt))
                .map(BudgetLimitedSummary::completedStep)
                .map(BudgetLimitedSummary::bounded)
                .forEach(steps::add);
        return steps.stream().limit(MAXIMUM_COMPLETED_ITEMS).toList();
    }

    private static String completedStep(ToolCall call) {
        Object purpose = call.arguments().values().get("purpose");
        if (purpose instanceof String value && !value.isBlank()) return value;
        return call.toolName();
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

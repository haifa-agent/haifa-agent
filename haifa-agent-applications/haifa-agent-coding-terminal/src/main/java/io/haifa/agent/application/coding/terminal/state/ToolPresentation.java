package io.haifa.agent.application.coding.terminal.state;

import io.haifa.agent.runtime.api.RunEventPayloads;

/** Display-only naming and status wording for tool lifecycle facts. */
final class ToolPresentation {
    private ToolPresentation() {}

    static String displayName(RunEventPayloads.ToolLifecycle lifecycle) {
        String displayName = lifecycle.displayName();
        return lifecycle.toolName().isBlank() ? legacyDisplayName(displayName) : displayName;
    }

    static String machineName(RunEventPayloads.ToolLifecycle lifecycle) {
        return lifecycle.toolName().isBlank() ? lifecycle.displayName() : lifecycle.toolName();
    }

    static String statusSuffix(String status, String reasonCode) {
        String normalized = status == null ? "" : status.strip().toUpperCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "FAILED", "ERROR" -> " · 未完成：" + shortReason(reasonCode);
            case "TIMEOUT" -> " · 未完成：执行超时";
            case "DENIED", "REJECTED", "SHELL COMMAND DENIED" -> " · " + deniedLabel(reasonCode);
            case "CANCELLED" -> " · " + cancelledLabel(reasonCode);
            default -> "";
        };
    }

    private static String deniedLabel(String reasonCode) {
        String reason = reasonCode == null ? "" : reasonCode.strip().toUpperCase(java.util.Locale.ROOT);
        if (reason.contains("APPROVAL_REJECTED") || reason.equals("REJECTED")) return "已拒绝";
        if (reason.startsWith("CODING_") || reason.contains("POLICY") || reason.contains("DENIED")) {
            return "规则阻止";
        }
        return "已拒绝";
    }

    private static String cancelledLabel(String reasonCode) {
        String reason = reasonCode == null ? "" : reasonCode.strip().toUpperCase(java.util.Locale.ROOT);
        if (reason.startsWith("CODING_")
                || reason.contains("POLICY")
                || reason.equals("CAPABILITY_DENIED")
                || reason.contains("DENIED")) {
            return "规则阻止";
        }
        return "已取消";
    }

    private static String shortReason(String reasonCode) {
        String reason = reasonCode == null ? "" : reasonCode.strip().toUpperCase(java.util.Locale.ROOT);
        return switch (reason) {
            case "PATCH_AMBIGUOUS_MATCH" -> "匹配到多处";
            case "PATCH_EXPECTED_LINES_NOT_FOUND" -> "未找到预期内容";
            case "PATCH_CONTEXT_MISMATCH" -> "上下文不匹配";
            case "FILE_CURSOR_STALE" -> "文件已变化";
            case "WALL_TIME_EXCEEDED", "TIMEOUT" -> "执行超时";
            case "RUN_BUDGET_EXCEEDED" -> "预算用尽";
            case "ABSOLUTE_WORKDIR_FORBIDDEN", "WORKDIR_INVALID", "CWD_DENIED" -> "工作目录不可用";
            case "NETWORK_UNAVAILABLE" -> "网络不可用";
            default -> "工具调用未完成";
        };
    }

    private static String legacyDisplayName(String value) {
        if (value == null || value.isBlank()) return "Tool";
        return switch (value) {
            case "file_read" -> "Read File";
            case "file_write" -> "Write File";
            case "file_create" -> "Create File";
            case "file_patch" -> "Apply Patch";
            case "file_delete" -> "Delete File";
            case "file_move" -> "Move File";
            case "file_list" -> "List Files";
            case "file_stat" -> "Inspect Path";
            case "file_search" -> "Search Files";
            case "file_diff" -> "Preview Diff";
            case "execution_run" -> "Run Command";
            case "workspace_attach" -> "Add Directory";
            case "web_search" -> "Search Web";
            case "web_fetch" -> "Fetch Web";
            case "task" -> "Delegate Task";
            default -> value;
        };
    }
}

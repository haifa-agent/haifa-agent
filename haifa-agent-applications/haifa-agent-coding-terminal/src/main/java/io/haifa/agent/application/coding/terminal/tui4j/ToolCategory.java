package io.haifa.agent.application.coding.terminal.tui4j;

import java.util.Locale;

/**
 * Functional semantic classification of tool executions for distinct capsule badges.
 */
enum ToolCategory {
    READ("READ"),
    EDIT("EDIT"),
    EXEC("EXEC"),
    SEARCH("SEARCH"),
    TOOL("TOOL");

    private final String label;

    ToolCategory(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }

    static ToolCategory fromToolName(String name) {
        if (name == null || name.isBlank()) {
            return TOOL;
        }
        String lower = name.strip().toLowerCase(Locale.ROOT);
        int dot = lower.indexOf(" · ");
        if (dot > 0) {
            lower = lower.substring(0, dot);
        }
        int colon = lower.indexOf(':');
        if (colon > 0) {
            lower = lower.substring(0, colon);
        }
        lower = lower.strip();

        if (lower.startsWith("file_read")
                || lower.startsWith("read_file")
                || lower.startsWith("view_file")
                || lower.startsWith("file_stat")
                || lower.startsWith("list_directory")
                || lower.startsWith("glob_find")
                || lower.equals("workspace.read")) {
            return READ;
        }
        if (lower.startsWith("file_patch")
                || lower.startsWith("patch_file")
                || lower.startsWith("replace_file_content")
                || lower.startsWith("write_to_file")
                || lower.startsWith("file_write")
                || lower.startsWith("file_edit")) {
            return EDIT;
        }
        if (lower.startsWith("execution_run")
                || lower.startsWith("run_command")
                || lower.startsWith("shell_exec")
                || lower.startsWith("terminal_exec")
                || lower.startsWith("exec_")) {
            return EXEC;
        }
        if (lower.startsWith("grep_search")
                || lower.startsWith("search_code")
                || lower.startsWith("code_search")
                || lower.startsWith("web_search")
                || lower.contains("search")) {
            return SEARCH;
        }
        return TOOL;
    }
}

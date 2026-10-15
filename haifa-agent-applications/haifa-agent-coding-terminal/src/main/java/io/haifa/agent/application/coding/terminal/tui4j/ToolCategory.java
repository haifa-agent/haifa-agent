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
        int slash = lower.indexOf('/');
        if (slash > 0) {
            lower = lower.substring(slash + 1);
        }
        lower = lower.strip();

        return switch (lower) {
            case "file_read",
                    "file_stat",
                    "file_list",
                    "file_diff",
                    "read_file",
                    "view_file",
                    "list_directory",
                    "glob_find",
                    "web_fetch" -> READ;

            case "file_patch",
                    "file_write",
                    "file_create",
                    "file_delete",
                    "file_move",
                    "patch_file",
                    "replace_file_content",
                    "write_to_file",
                    "edit_file" -> EDIT;

            case "execution_run", "run_command", "shell_exec", "terminal_exec" -> EXEC;

            case "file_search", "web_search", "grep_search", "search_code", "code_search" -> SEARCH;

            default -> TOOL;
        };
    }
}

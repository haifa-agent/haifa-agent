package io.haifa.agent.cli;

import io.haifa.agent.application.project.workspace.WorkspaceAccessMode;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Renders the minimal workspace path mapping into the frozen instructions of a new Run. */
final class CodingWorkspaceRegistryPrompt {
    private CodingWorkspaceRegistryPrompt() {}

    record Entry(String workspaceRef, String rootPath, WorkspaceAccessMode mode, boolean current) {
        public Entry {
            Objects.requireNonNull(workspaceRef, "workspaceRef must not be null");
            Objects.requireNonNull(rootPath, "rootPath must not be null");
            Objects.requireNonNull(mode, "mode must not be null");
            if (workspaceRef.isBlank()) {
                throw new IllegalArgumentException("workspaceRef must not be blank");
            }
            if (rootPath.isBlank()) {
                throw new IllegalArgumentException("rootPath must not be blank");
            }
        }
    }

    static String render(List<Entry> entries) {
        Objects.requireNonNull(entries, "entries must not be null");
        List<Entry> ordered = entries.stream()
                .sorted(Comparator.comparing((Entry entry) -> !entry.current()).thenComparing(Entry::workspaceRef))
                .toList();
        StringBuilder prompt =
                new StringBuilder("\n\nUse rootPath and its descendants as host absolute paths for file tools.\n"
                        + "Use the matching workspaceRef with a normalized relativeWorkdir for execution_run;\n"
                        + "use \".\" as relativeWorkdir for that workspace root.\n"
                        + "Paths not listed here are not available unless workspace_attach succeeds.\n\n"
                        + "<workspace_paths>\n");
        for (Entry entry : ordered) {
            prompt.append("  <workspace workspaceRef=\"")
                    .append(xml(entry.workspaceRef()))
                    .append("\" rootPath=\"")
                    .append(xml(entry.rootPath()))
                    .append("\" mode=\"")
                    .append(entry.mode().name())
                    .append("\"");
            if (entry.current()) {
                prompt.append(" current=\"true\"");
            }
            prompt.append(" />\n");
        }
        return prompt.append("</workspace_paths>").toString();
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}

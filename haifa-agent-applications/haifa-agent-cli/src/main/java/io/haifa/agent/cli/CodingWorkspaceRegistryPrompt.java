package io.haifa.agent.cli;

import io.haifa.agent.project.hostworkspace.registry.HostWorkspaceRegistryView;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Renders the path-redacted CA workspace registry into the frozen instructions of a new Run. */
final class CodingWorkspaceRegistryPrompt {
    private CodingWorkspaceRegistryPrompt() {}

    static String render(List<HostWorkspaceRegistryView> entries) {
        List<HostWorkspaceRegistryView> ordered =
                List.copyOf(Objects.requireNonNull(entries, "entries must not be null")).stream()
                        .sorted(Comparator.comparing(HostWorkspaceRegistryView::workspaceRef))
                        .toList();
        StringBuilder prompt =
                new StringBuilder("\n\n<workspace_registry path_contract=\"host-absolute-file-paths\">\n");
        for (HostWorkspaceRegistryView entry : ordered) {
            prompt.append("  <workspace ref=\"")
                    .append(xml(entry.workspaceRef()))
                    .append("\" name=\"")
                    .append(xml(entry.safeDisplayName()))
                    .append("\" permission=\"")
                    .append(entry.permission())
                    .append("\" source=\"")
                    .append(entry.source())
                    .append("\" status=\"")
                    .append(entry.status())
                    .append("\" />\n");
        }
        return prompt.append("</workspace_registry>").toString();
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}

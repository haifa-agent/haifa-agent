package io.haifa.agent.cli;

import io.haifa.agent.application.project.product.coding.CodingWorkspaceView;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Renders the path-redacted CA workspace registry into the frozen instructions of a new Run. */
final class CodingWorkspaceRegistryPrompt {
    private CodingWorkspaceRegistryPrompt() {}

    static String render(List<CodingWorkspaceView> entries) {
        List<CodingWorkspaceView> ordered =
                List.copyOf(Objects.requireNonNull(entries, "entries must not be null")).stream()
                        .sorted(Comparator.comparing(CodingWorkspaceView::workspaceRef))
                        .toList();
        StringBuilder prompt = new StringBuilder("\n\n<workspace_registry path_contract=\"host-absolute-file-paths\""
                + " execution_target_contract=\"workspace-ref-plus-relative-workdir\">\n");
        for (CodingWorkspaceView entry : ordered) {
            prompt.append("  <workspace ref=\"")
                    .append(xml(entry.workspaceRef()))
                    .append("\" name=\"")
                    .append(xml(entry.safeDisplayName()))
                    .append("\" mode=\"")
                    .append(xml(entry.mode()))
                    .append("\" source=\"")
                    .append(xml(entry.source()))
                    .append("\" status=\"")
                    .append(xml(entry.status()))
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

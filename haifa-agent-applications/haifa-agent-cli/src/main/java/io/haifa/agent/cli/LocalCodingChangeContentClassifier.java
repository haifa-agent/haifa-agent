package io.haifa.agent.cli;

import io.haifa.agent.application.project.product.coding.delivery.CodingChangeContentClassifier;
import io.haifa.agent.application.project.product.coding.delivery.CodingChangeContentKind;
import io.haifa.agent.project.changeset.FileChange;
import io.haifa.agent.project.filesystem.FileType;
import io.haifa.agent.project.filesystem.ReadOptions;
import io.haifa.agent.project.filesystem.WorkspaceFileErrorCode;
import io.haifa.agent.project.filesystem.WorkspaceFileException;
import io.haifa.agent.project.hostworkspace.HostWorkspaceFileService;
import io.haifa.agent.project.path.WorkspacePath;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Reads only a bounded post-change sample to distinguish text from binary content. */
final class LocalCodingChangeContentClassifier implements CodingChangeContentClassifier {
    private static final int SAMPLE_BYTES = 8 * 1024;
    private final HostWorkspaceFileService files;

    LocalCodingChangeContentClassifier(HostWorkspaceFileService files) {
        this.files = Objects.requireNonNull(files, "files must not be null");
    }

    @Override
    public CodingChangeContentKind classify(FileChange change) {
        if (change.after() == null || change.after().type() != FileType.FILE) {
            return CodingChangeContentKind.OPAQUE;
        }
        var logical = change.optionalDestination().orElse(change.path());
        try {
            files.read(
                    new WorkspacePath(new io.haifa.agent.project.workspace.WorkspaceId("default"), logical),
                    new ReadOptions(SAMPLE_BYTES, SAMPLE_BYTES, StandardCharsets.UTF_8, true));
            return CodingChangeContentKind.TEXT;
        } catch (WorkspaceFileException exception) {
            if (exception.code() == WorkspaceFileErrorCode.BINARY_CONTENT
                    || exception.code() == WorkspaceFileErrorCode.UNSUPPORTED_ENCODING) {
                return CodingChangeContentKind.BINARY;
            }
            return CodingChangeContentKind.OPAQUE;
        }
    }
}

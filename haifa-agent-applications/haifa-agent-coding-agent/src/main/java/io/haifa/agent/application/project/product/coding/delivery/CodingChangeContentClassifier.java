package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.project.changeset.FileChange;
import io.haifa.agent.project.changeset.FileChangeSet;

@FunctionalInterface
public interface CodingChangeContentClassifier {
    CodingChangeContentKind classify(FileChangeSet changeSet, FileChange change);

    static CodingChangeContentClassifier opaque() {
        return (changeSet, change) -> CodingChangeContentKind.OPAQUE;
    }
}

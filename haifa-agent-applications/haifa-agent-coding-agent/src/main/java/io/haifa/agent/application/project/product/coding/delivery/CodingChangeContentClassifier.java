package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.project.changeset.FileChange;

@FunctionalInterface
public interface CodingChangeContentClassifier {
    CodingChangeContentKind classify(FileChange change);

    static CodingChangeContentClassifier opaque() {
        return change -> CodingChangeContentKind.OPAQUE;
    }
}

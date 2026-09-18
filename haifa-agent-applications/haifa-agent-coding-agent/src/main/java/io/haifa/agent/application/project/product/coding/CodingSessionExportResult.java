package io.haifa.agent.application.project.product.coding;

import java.util.Objects;

public record CodingSessionExportResult(String logicalPath, int messageCount, String schemaVersion) {
    public CodingSessionExportResult {
        logicalPath = CodingProductValues.requireText(logicalPath, "logicalPath", 4_096);
        if (messageCount < 0) throw new IllegalArgumentException("messageCount must not be negative");
        schemaVersion = Objects.requireNonNull(schemaVersion, "schemaVersion must not be null");
    }
}

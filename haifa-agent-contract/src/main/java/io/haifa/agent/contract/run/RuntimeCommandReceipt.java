package io.haifa.agent.contract.run;

import io.haifa.agent.contract.common.ApiVersion;
import io.haifa.agent.contract.common.CorrelationId;
import java.util.Objects;

public record RuntimeCommandReceipt(
        ApiVersion apiVersion, String commandId, String runId, String commandType, String status, long runVersion) {
    public RuntimeCommandReceipt {
        apiVersion = Objects.requireNonNull(apiVersion, "apiVersion must not be null");
        commandId = CorrelationId.requireText(commandId, "commandId", 256);
        runId = CorrelationId.requireText(runId, "runId", 256);
        commandType = CorrelationId.requireText(commandType, "commandType", 64);
        status = CorrelationId.requireText(status, "status", 64);
        if (runVersion < 0) throw new IllegalArgumentException("runVersion must not be negative");
    }
}

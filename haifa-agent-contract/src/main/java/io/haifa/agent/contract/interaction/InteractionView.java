package io.haifa.agent.contract.interaction;

import io.haifa.agent.contract.common.ApiVersion;
import io.haifa.agent.contract.common.CorrelationId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record InteractionView(
        ApiVersion apiVersion,
        String requestId,
        String runId,
        String sessionId,
        long revision,
        String kind,
        String state,
        String title,
        String safePrompt,
        List<String> allowedActions,
        InputContract inputContract,
        TargetView target,
        RequesterView requester,
        Instant createdAt,
        Instant expiresAt,
        ConsequenceView consequences) {
    public InteractionView {
        apiVersion = Objects.requireNonNull(apiVersion, "apiVersion must not be null");
        requestId = text(requestId, "requestId", 256);
        runId = text(runId, "runId", 256);
        sessionId = text(sessionId, "sessionId", 256);
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
        kind = text(kind, "kind", 64);
        state = text(state, "state", 64);
        title = text(title, "title", 256);
        safePrompt = text(safePrompt, "safePrompt", 2_048);
        allowedActions = List.copyOf(Objects.requireNonNull(allowedActions, "allowedActions must not be null"));
        if (allowedActions.size() > 8 || allowedActions.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("allowedActions must contain at most 8 non-null values");
        }
        inputContract = Objects.requireNonNull(inputContract, "inputContract must not be null");
        target = Objects.requireNonNull(target, "target must not be null");
        requester = Objects.requireNonNull(requester, "requester must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        consequences = Objects.requireNonNull(consequences, "consequences must not be null");
    }

    private static String text(String value, String field, int maximumLength) {
        return CorrelationId.requireText(value, field, maximumLength);
    }

    public record InputContract(
            String type,
            int maximumCharacters,
            int minimumSelections,
            int maximumSelections,
            int maximumParts,
            int maximumBytes,
            List<Option> options,
            Optional<String> schemaRef) {
        public InputContract {
            type = text(type, "type", 64);
            if (maximumCharacters < 0
                    || minimumSelections < 0
                    || maximumSelections < minimumSelections
                    || maximumParts < 0
                    || maximumBytes < 0) {
                throw new IllegalArgumentException("input limits are invalid");
            }
            options = List.copyOf(Objects.requireNonNull(options, "options must not be null"));
            if (options.size() > 100) throw new IllegalArgumentException("options exceed the public limit");
            schemaRef = Objects.requireNonNull(schemaRef, "schemaRef must not be null");
        }
    }

    public record Option(String id, String label) {
        public Option {
            id = text(id, "id", 64);
            label = text(label, "label", 256);
        }
    }

    public record TargetView(
            String type, String reference, Optional<String> version, Optional<String> digest, String safeSummary) {
        public TargetView {
            type = text(type, "type", 64);
            reference = text(reference, "reference", 256);
            version = Objects.requireNonNull(version, "version must not be null");
            digest = Objects.requireNonNull(digest, "digest must not be null");
            safeSummary = text(safeSummary, "safeSummary", 512);
        }
    }

    public record RequesterView(String principalType, String displayLabel) {
        public RequesterView {
            principalType = text(principalType, "principalType", 64);
            displayLabel = text(displayLabel, "displayLabel", 256);
        }
    }

    public record ConsequenceView(String accepted, String rejected, String expired) {
        public ConsequenceView {
            accepted = text(accepted, "accepted", 512);
            rejected = text(rejected, "rejected", 512);
            expired = text(expired, "expired", 512);
        }
    }
}

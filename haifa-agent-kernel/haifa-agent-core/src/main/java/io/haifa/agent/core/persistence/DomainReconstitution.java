package io.haifa.agent.core.persistence;

import java.time.Instant;
import java.util.Objects;

/** Shared validation helpers for controlled domain reconstitution. */
public final class DomainReconstitution {

    public static final String SCHEMA_VERSION = "1";

    private DomainReconstitution() {}

    public static void requireSupportedVersion(String schemaVersion, String snapshotType) {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new DomainReconstitutionException(
                    DomainReconstitutionFailure.UNSUPPORTED_SCHEMA_VERSION,
                    snapshotType + " schema version is unsupported: " + schemaVersion);
        }
    }

    public static <E extends Enum<E>> E enumValue(Class<E> type, String value, String field) {
        Objects.requireNonNull(type, "type must not be null");
        if (value == null) {
            invalid(field + " must not be null");
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new DomainReconstitutionException(
                    DomainReconstitutionFailure.UNKNOWN_ENUM, "unknown " + field + ": " + value, exception);
        }
    }

    public static void requireVersion(long version, String aggregate) {
        if (version < 0) {
            invalid(aggregate + " version must not be negative");
        }
    }

    public static void requireChronological(Instant createdAt, Instant updatedAt, String aggregate) {
        if (createdAt == null || updatedAt == null) {
            invalid(aggregate + " createdAt and updatedAt must not be null");
        }
        if (updatedAt.isBefore(createdAt)) {
            invalid(aggregate + " updatedAt must not precede createdAt");
        }
    }

    public static void requireWithinHistory(
            Instant value, Instant createdAt, Instant updatedAt, String field, String aggregate) {
        if (value != null && (value.isBefore(createdAt) || value.isAfter(updatedAt))) {
            invalid(aggregate + " " + field + " must be within the persisted history");
        }
    }

    public static void invalid(String message) {
        throw new DomainReconstitutionException(DomainReconstitutionFailure.INVALID_HISTORY, message);
    }

    public static DomainReconstitutionException invalid(String aggregate, RuntimeException cause) {
        return new DomainReconstitutionException(
                DomainReconstitutionFailure.INVALID_HISTORY,
                aggregate + " persisted history is invalid: " + cause.getMessage(),
                cause);
    }
}

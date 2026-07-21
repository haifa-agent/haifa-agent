package io.haifa.agent.contract.common;

import io.haifa.agent.common.version.SchemaVersion;
import java.util.Objects;

/** Version carried by externally visible Haifa Agent protocols. */
public record ApiVersion(SchemaVersion schemaVersion) {

    public static final ApiVersion CURRENT = new ApiVersion(SchemaVersion.V1);

    public ApiVersion {
        schemaVersion = Objects.requireNonNull(schemaVersion, "schemaVersion must not be null");
    }
}

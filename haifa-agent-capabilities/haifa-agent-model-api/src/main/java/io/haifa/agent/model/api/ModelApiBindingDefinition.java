package io.haifa.agent.model.api;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/** Lightweight binding from an API Style to an optional dialect and endpoint override. */
public record ModelApiBindingDefinition(ApiStyleId style, String dialect, URI endpointOverride) {
    public static final String STANDARD_DIALECT = "standard";

    public ModelApiBindingDefinition(ApiStyleId style) {
        this(style, STANDARD_DIALECT, null);
    }

    public ModelApiBindingDefinition(ApiStyleId style, String dialect) {
        this(style, dialect, null);
    }

    public ModelApiBindingDefinition {
        style = Objects.requireNonNull(style, "style must not be null");
        dialect = dialect == null || dialect.isBlank() ? STANDARD_DIALECT : dialect.trim();
        if (!dialect.matches("[a-z][a-z0-9-]{0,127}")) {
            throw new IllegalArgumentException("dialect must be a lower-case kebab-case identifier");
        }
        if (endpointOverride != null) endpointOverride = normalizeEndpoint(endpointOverride, "endpointOverride");
    }

    public Optional<URI> endpoint() {
        return Optional.ofNullable(endpointOverride);
    }

    public URI resolveEndpoint(URI providerEndpoint) {
        return endpointOverride == null ? normalizeEndpoint(providerEndpoint, "provider endpoint") : endpointOverride;
    }

    static URI normalizeEndpoint(URI endpoint, String field) {
        URI normalized =
                Objects.requireNonNull(endpoint, field + " must not be null").normalize();
        if (!normalized.isAbsolute()
                || normalized.getHost() == null
                || normalized.getUserInfo() != null
                || normalized.getQuery() != null
                || normalized.getFragment() != null) {
            throw new IllegalArgumentException(
                    field + " must be an absolute network base URI without userinfo, query, or fragment");
        }
        String value = normalized.toString();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return URI.create(value);
    }
}

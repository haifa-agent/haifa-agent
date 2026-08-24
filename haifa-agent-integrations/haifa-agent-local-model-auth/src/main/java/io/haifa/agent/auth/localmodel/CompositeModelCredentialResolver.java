package io.haifa.agent.auth.localmodel;

import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.CredentialResolver;
import io.haifa.agent.model.api.ResolvedCredential;
import java.util.Map;
import java.util.Objects;

/** Explicit scheme router for hosts that genuinely compose separate credential resolvers. */
public final class CompositeModelCredentialResolver implements CredentialResolver {
    private final Map<String, CredentialResolver> resolvers;

    public CompositeModelCredentialResolver(Map<String, ? extends CredentialResolver> resolvers) {
        Objects.requireNonNull(resolvers, "resolvers must not be null");
        this.resolvers = resolvers.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        entry -> {
                            String scheme = entry.getKey().trim().toLowerCase(java.util.Locale.ROOT);
                            if (!scheme.matches("[a-z][a-z0-9+.-]*"))
                                throw new IllegalArgumentException("scheme is invalid");
                            return scheme;
                        },
                        entry -> Objects.requireNonNull(entry.getValue(), "resolver must not be null")));
    }

    @Override
    public ResolvedCredential resolve(CredentialRef reference) {
        String value =
                Objects.requireNonNull(reference, "reference must not be null").value();
        int separator = value.indexOf("://");
        if (separator < 1) throw new IllegalArgumentException("credential reference scheme is invalid");
        CredentialResolver resolver =
                resolvers.get(value.substring(0, separator).toLowerCase(java.util.Locale.ROOT));
        if (resolver == null) throw new IllegalArgumentException("credential reference scheme is unsupported");
        return resolver.resolve(reference);
    }
}

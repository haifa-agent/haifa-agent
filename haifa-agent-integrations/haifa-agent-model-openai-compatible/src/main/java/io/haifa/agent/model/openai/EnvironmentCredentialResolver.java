package io.haifa.agent.model.openai;

import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.CredentialResolver;
import io.haifa.agent.model.api.ResolvedCredential;
import java.util.Objects;
import java.util.function.Function;

/** Resolves {@code env://NAME} references without retaining a secret registry. */
public final class EnvironmentCredentialResolver implements CredentialResolver {
    private static final String PREFIX = "env://";
    private final Function<String, String> environment;

    public EnvironmentCredentialResolver() {
        this(System::getenv);
    }

    public EnvironmentCredentialResolver(Function<String, String> environment) {
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
    }

    @Override
    public ResolvedCredential resolve(CredentialRef reference) {
        String value =
                Objects.requireNonNull(reference, "reference must not be null").value();
        if (!value.startsWith(PREFIX) || value.length() == PREFIX.length()) {
            throw new IllegalArgumentException("unsupported credential reference scheme");
        }
        String variable = value.substring(PREFIX.length());
        if (!variable.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("invalid environment credential reference");
        }
        String secret = environment.apply(variable);
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("credential environment variable is not configured: " + variable);
        }
        return new ResolvedCredential(secret);
    }
}

package io.haifa.agent.credential.api;

import java.util.Objects;

public record CredentialBindingScope(CredentialScopeKind kind, String value) {
    public CredentialBindingScope {
        Objects.requireNonNull(kind, "kind");
        value = CredentialValues.text(value, "value");
    }
}

package io.haifa.agent.auth.localmodel;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Locally stored provider API key with a permanently redacted string form. */
public final class StoredApiKeyCredential implements StoredModelCredential {
    private static final int MAX_SECRET_LENGTH = 64 * 1024;

    private final LocalModelAuthReference reference;
    private final String apiKey;
    private final java.util.Map<String, String> attributes;

    public StoredApiKeyCredential(LocalModelAuthReference reference, String apiKey) {
        this(reference, apiKey, java.util.Map.of());
    }

    public StoredApiKeyCredential(
            LocalModelAuthReference reference, String apiKey, java.util.Map<String, String> attributes) {
        this.reference = Objects.requireNonNull(reference, "reference must not be null");
        this.apiKey = secret(apiKey, "apiKey");
        this.attributes = java.util.Map.copyOf(Objects.requireNonNull(attributes, "attributes must not be null"));
    }

    @Override
    public LocalModelAuthReference reference() {
        return reference;
    }

    public String apiKey() {
        return apiKey;
    }

    public java.util.Map<String, String> attributes() {
        return attributes;
    }

    public Optional<String> attribute(String name) {
        Objects.requireNonNull(name, "name must not be null");
        return Optional.ofNullable(attributes.get(name));
    }

    public Optional<String> workspaceId() {
        return attribute("workspace_id");
    }

    public Optional<String> region() {
        return attribute("region");
    }

    @Override
    public LocalModelConnectionView safeView(boolean unofficialLocalCompatibility) {
        return new LocalModelConnectionView(
                reference,
                reference.providerId(),
                LocalModelConnectionView.Method.API_KEY,
                LocalModelConnectionView.Status.AUTHENTICATED,
                "Saved API key",
                OptionalLong.empty(),
                Optional.empty(),
                unofficialLocalCompatibility);
    }

    @Override
    public String toString() {
        return "StoredApiKeyCredential[reference=" + reference + ", apiKey=<redacted>]";
    }

    static String secret(String value, String field) {
        String checked = Objects.requireNonNull(value, field + " must not be null");
        if (checked.isBlank() || checked.length() > MAX_SECRET_LENGTH || checked.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return checked;
    }
}

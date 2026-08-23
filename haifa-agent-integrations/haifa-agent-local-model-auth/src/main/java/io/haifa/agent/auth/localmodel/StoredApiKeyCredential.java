package io.haifa.agent.auth.localmodel;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Locally stored provider API key with a permanently redacted string form. */
public final class StoredApiKeyCredential implements StoredModelCredential {
    private static final int MAX_SECRET_LENGTH = 64 * 1024;

    private final LocalModelAuthReference reference;
    private final String apiKey;

    public StoredApiKeyCredential(LocalModelAuthReference reference, String apiKey) {
        this.reference = Objects.requireNonNull(reference, "reference must not be null");
        this.apiKey = secret(apiKey, "apiKey");
    }

    @Override
    public LocalModelAuthReference reference() {
        return reference;
    }

    public String apiKey() {
        return apiKey;
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

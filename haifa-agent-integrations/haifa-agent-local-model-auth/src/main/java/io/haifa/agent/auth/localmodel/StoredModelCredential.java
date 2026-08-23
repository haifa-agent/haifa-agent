package io.haifa.agent.auth.localmodel;

/** Secret-bearing local credential. Never log, serialize outside the auth file codec, or expose to a product DTO. */
public sealed interface StoredModelCredential permits StoredApiKeyCredential, StoredExternalCredential {
    LocalModelAuthReference reference();

    LocalModelConnectionView safeView(boolean unofficialLocalCompatibility);
}

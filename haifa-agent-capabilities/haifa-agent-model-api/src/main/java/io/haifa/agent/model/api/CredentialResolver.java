package io.haifa.agent.model.api;

/** Resolves a non-secret reference at the provider boundary. */
@FunctionalInterface
public interface CredentialResolver {
    ResolvedCredential resolve(CredentialRef reference);
}

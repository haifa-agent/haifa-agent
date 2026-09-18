package io.haifa.agent.model.gemini;

import io.haifa.agent.model.api.CredentialRef;
import java.util.Optional;

/** Resolves trusted Antigravity CloudCode project identities for direct requests. */
@FunctionalInterface
public interface AntigravityCloudCodeProjectResolver {
    Optional<String> resolveProject(CredentialRef credentialRef);
}

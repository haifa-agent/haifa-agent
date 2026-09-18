package io.haifa.agent.model.openai.responses;

import io.haifa.agent.model.api.CredentialRef;
import java.util.Optional;

/** Resolves trusted Codex account identities for authenticated requests. */
@FunctionalInterface
public interface CodexAccountIdentityResolver {
    Optional<CodexAccountIdentity> resolve(CredentialRef credentialRef);
}

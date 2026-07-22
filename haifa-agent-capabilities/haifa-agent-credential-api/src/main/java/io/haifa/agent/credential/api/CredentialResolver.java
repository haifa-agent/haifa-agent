package io.haifa.agent.credential.api;

import java.util.Collection;

public interface CredentialResolver {
    CredentialBinding resolve(CredentialRequest request, Collection<CredentialBinding> bindings);

    CredentialBinding resolve(CredentialOperationRequest request, Collection<CredentialBinding> bindings);
}

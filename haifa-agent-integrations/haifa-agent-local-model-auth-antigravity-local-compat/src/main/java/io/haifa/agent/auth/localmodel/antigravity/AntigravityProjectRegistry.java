package io.haifa.agent.auth.localmodel.antigravity;

import io.haifa.agent.model.api.CredentialRef;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local trusted projection of CloudCode projects discovered by an authenticated login. */
public final class AntigravityProjectRegistry {
    private final ConcurrentHashMap<String, String> projects = new ConcurrentHashMap<>();

    public void record(CredentialRef reference, AntigravityProjectAndQuota projection) {
        projects.put(reference.value(), projection.projectId());
    }

    public Optional<String> resolve(CredentialRef reference) {
        return Optional.ofNullable(projects.get(reference.value()));
    }
}

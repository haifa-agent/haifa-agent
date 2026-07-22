package io.haifa.agent.credential.core;

import io.haifa.agent.credential.api.CredentialEnvironmentSink;
import io.haifa.agent.credential.api.CredentialHeaderSink;
import io.haifa.agent.credential.api.CredentialLease;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class CredentialLeaseInjector {
    public void intoHeader(CredentialLease lease, String name, String prefix, CredentialHeaderSink sink) {
        Objects.requireNonNull(sink, "sink");
        lease.use(secret -> {
            sink.put(name, prefix + new String(secret, StandardCharsets.UTF_8));
            return null;
        });
    }

    public void intoEnvironment(CredentialLease lease, String name, CredentialEnvironmentSink sink) {
        Objects.requireNonNull(sink, "sink");
        lease.use(secret -> {
            sink.put(name, new String(secret, StandardCharsets.UTF_8));
            return null;
        });
    }
}

package io.haifa.agent.auth.localmodel;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable registry assembled from a trusted, explicit allowlist. */
public final class ExternalLoginRegistry {
    private final Map<ExternalLoginMethodId, ExternalLoginMethod> methods;

    public ExternalLoginRegistry(Collection<? extends ExternalLoginMethod> methods) {
        Objects.requireNonNull(methods, "methods must not be null");
        Map<ExternalLoginMethodId, ExternalLoginMethod> copy = new LinkedHashMap<>();
        for (ExternalLoginMethod method : methods) {
            ExternalLoginMethod checked = Objects.requireNonNull(method, "method must not be null");
            ExternalLoginMethodId id = checked.descriptor().methodId();
            if (copy.putIfAbsent(id, checked) != null) {
                throw new IllegalArgumentException("duplicate external login method: " + id);
            }
        }
        this.methods = Map.copyOf(copy);
    }

    public ExternalLoginMethod require(ExternalLoginMethodId methodId) {
        ExternalLoginMethod method = methods.get(Objects.requireNonNull(methodId, "methodId must not be null"));
        if (method == null) throw new ExternalLoginMethodUnavailableException("AUTH_LOGIN_METHOD_UNAVAILABLE");
        return method;
    }

    public List<ExternalLoginMethodDescriptor> descriptors() {
        return methods.values().stream().map(ExternalLoginMethod::descriptor).toList();
    }
}

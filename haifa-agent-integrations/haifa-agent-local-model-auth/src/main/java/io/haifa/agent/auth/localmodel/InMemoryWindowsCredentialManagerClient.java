package io.haifa.agent.auth.localmodel;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory Windows Credential Manager fake for isolated automated testing. */
public final class InMemoryWindowsCredentialManagerClient implements WindowsCredentialManagerClient {
    private final Map<String, String> storage = new ConcurrentHashMap<>();

    @Override
    public Optional<String> read(String targetName) {
        Objects.requireNonNull(targetName, "targetName must not be null");
        return Optional.ofNullable(storage.get(targetName));
    }

    @Override
    public void write(String targetName, String secret) {
        Objects.requireNonNull(targetName, "targetName must not be null");
        Objects.requireNonNull(secret, "secret must not be null");
        byte[] bytes = secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > MAX_CREDENTIAL_BLOB_SIZE) {
            throw new WindowsCredentialManagerException(
                    WindowsCredentialManagerException.Reason.ITEM_TOO_LARGE,
                    "Credential blob size (" + bytes.length + " bytes) exceeds Windows Credential Manager limit ("
                            + MAX_CREDENTIAL_BLOB_SIZE + " bytes)");
        }
        storage.put(targetName, secret);
    }

    @Override
    public boolean delete(String targetName) {
        Objects.requireNonNull(targetName, "targetName must not be null");
        return storage.remove(targetName) != null;
    }

    @Override
    public List<String> listTargets(String prefixFilter) {
        Objects.requireNonNull(prefixFilter, "prefixFilter must not be null");
        String prefix =
                prefixFilter.endsWith("*") ? prefixFilter.substring(0, prefixFilter.length() - 1) : prefixFilter;
        return storage.keySet().stream()
                .filter(name -> name.startsWith(prefix))
                .sorted()
                .toList();
    }

    public void clear() {
        storage.clear();
    }

    public Map<String, String> snapshot() {
        return Map.copyOf(storage);
    }
}

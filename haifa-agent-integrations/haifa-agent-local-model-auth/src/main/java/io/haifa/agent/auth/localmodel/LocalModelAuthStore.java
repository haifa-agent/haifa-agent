package io.haifa.agent.auth.localmodel;

import java.util.List;
import java.util.Optional;

/** Narrow persistence boundary for current-user local model credentials. */
public interface LocalModelAuthStore {
    Optional<StoredModelCredential> find(LocalModelAuthReference reference);

    List<LocalModelConnectionView> listSafe();

    void save(StoredModelCredential credential);

    boolean delete(LocalModelAuthReference reference);
}

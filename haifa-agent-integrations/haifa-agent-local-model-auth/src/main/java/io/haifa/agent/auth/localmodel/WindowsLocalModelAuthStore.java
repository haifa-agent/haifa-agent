package io.haifa.agent.auth.localmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** OS-backed current-user auth store using Windows Credential Manager. */
public final class WindowsLocalModelAuthStore implements LocalModelAuthStore {
    public static final String TARGET_PREFIX = "haifa:model-auth:";

    private final WindowsCredentialManagerClient client;
    private final StoredModelCredentialPayloadCodec codec;

    public WindowsLocalModelAuthStore(WindowsCredentialManagerClient client, ObjectMapper json) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.codec = new StoredModelCredentialPayloadCodec(Objects.requireNonNull(json, "json must not be null"));
    }

    public static WindowsLocalModelAuthStore defaultStore(ObjectMapper json) {
        return new WindowsLocalModelAuthStore(WindowsCredentialManagerClient.defaultClient(), json);
    }

    public WindowsCredentialManagerClient client() {
        return client;
    }

    @Override
    public Optional<StoredModelCredential> find(LocalModelAuthReference reference) {
        LocalModelAuthReference checked = Objects.requireNonNull(reference, "reference must not be null");
        String targetName = toTargetName(checked);
        return client.read(targetName).map(payload -> codec.decode(checked, payload));
    }

    @Override
    public List<LocalModelConnectionView> listSafe() {
        List<String> targets;
        try {
            targets = client.listTargets(TARGET_PREFIX);
        } catch (WindowsCredentialManagerException e) {
            if (e.reason() == WindowsCredentialManagerException.Reason.UNAVAILABLE) {
                return List.of();
            }
            throw e;
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("OS_CREDENTIAL_STORE_UNAVAILABLE")) {
                return List.of();
            }
            throw e;
        }
        List<LocalModelConnectionView> views = new ArrayList<>();
        for (String target : targets) {
            LocalModelAuthReference reference = fromTargetName(target);
            if (reference != null) {
                client.read(target).ifPresent(payload -> {
                    try {
                        StoredModelCredential credential = codec.decode(reference, payload);
                        views.add(credential.safeView(false));
                    } catch (RuntimeException ignored) {
                        // Skip corrupted or unparseable target
                    }
                });
            }
        }
        return List.copyOf(views);
    }

    @Override
    public void save(StoredModelCredential credential) {
        StoredModelCredential checked = Objects.requireNonNull(credential, "credential must not be null");
        String targetName = toTargetName(checked.reference());
        String payload = codec.encode(checked);
        client.write(targetName, payload);
    }

    @Override
    public boolean delete(LocalModelAuthReference reference) {
        LocalModelAuthReference checked = Objects.requireNonNull(reference, "reference must not be null");
        String targetName = toTargetName(checked);
        return client.delete(targetName);
    }

    public static String toTargetName(LocalModelAuthReference reference) {
        String ref = reference.value();
        if (ref.startsWith("model-auth://")) {
            return TARGET_PREFIX + ref.substring("model-auth://".length());
        }
        throw new IllegalArgumentException("Invalid model auth reference: " + ref);
    }

    public static LocalModelAuthReference fromTargetName(String targetName) {
        if (targetName != null && targetName.startsWith(TARGET_PREFIX)) {
            String path = targetName.substring(TARGET_PREFIX.length());
            try {
                return LocalModelAuthReference.parse("model-auth://" + path);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }
}

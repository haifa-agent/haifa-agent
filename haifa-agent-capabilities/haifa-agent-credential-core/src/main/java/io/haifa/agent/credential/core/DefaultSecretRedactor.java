package io.haifa.agent.credential.core;

import io.haifa.agent.credential.api.CredentialLease;
import io.haifa.agent.credential.api.SecretRedactor;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class DefaultSecretRedactor implements SecretRedactor {
    private static final String REDACTED = "[REDACTED]";
    private static final Pattern AUTHORIZATION =
            Pattern.compile("(?i)(authorization\\s*[:=]\\s*(?:bearer|basic)\\s+)[^\\s,;]+", Pattern.MULTILINE);
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)((?:api[-_]?key|access[-_]?token|client[-_]?secret|password)\\s*[:=]\\s*)[^\\s,;]+",
            Pattern.MULTILINE);

    private final Map<CredentialLease, String> activeSecrets = new ConcurrentHashMap<>();

    @Override
    public void track(CredentialLease lease) {
        lease.use(secret -> activeSecrets.put(lease, new String(secret, StandardCharsets.UTF_8)));
    }

    @Override
    public void forget(CredentialLease lease) {
        activeSecrets.remove(lease);
    }

    @Override
    public String redact(String value) {
        if (value == null) {
            return null;
        }
        String redacted = value;
        for (String secret : activeSecrets.values()) {
            if (!secret.isEmpty()) {
                redacted = redacted.replace(secret, REDACTED);
            }
        }
        redacted = AUTHORIZATION.matcher(redacted).replaceAll("$1" + REDACTED);
        return KEY_VALUE.matcher(redacted).replaceAll("$1" + REDACTED);
    }
}

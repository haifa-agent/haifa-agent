package io.haifa.agent.auth.localmodel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strict versioned codec for the local plaintext auth file. */
final class LocalModelAuthFileCodec {
    static final int SCHEMA_VERSION = 1;
    static final int MAX_FILE_BYTES = 1024 * 1024;

    private final ObjectMapper json;

    LocalModelAuthFileCodec(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json must not be null");
    }

    Map<LocalModelAuthReference, StoredModelCredential> decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes must not be null");
        if (bytes.length < 1 || bytes.length > MAX_FILE_BYTES) {
            throw new IllegalStateException("Local model auth file size is invalid");
        }
        try {
            JsonNode root = json.readTree(bytes);
            if (!root.isObject()) throw new IllegalStateException("Local model auth file schema is invalid");
            requireExactFields(root, Set.of("version", "credentials"));
            if (root.path("version").asInt(-1) != SCHEMA_VERSION) {
                throw new IllegalStateException("Local model auth file schema is invalid");
            }
            JsonNode credentials = root.get("credentials");
            if (credentials == null || !credentials.isObject()) {
                throw new IllegalStateException("Local model auth credentials object is invalid");
            }
            Map<LocalModelAuthReference, StoredModelCredential> result = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = credentials.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                LocalModelAuthReference reference = LocalModelAuthReference.parse(field.getKey());
                StoredModelCredential value = parseCredential(reference, field.getValue());
                if (result.put(reference, value) != null) {
                    throw new IllegalStateException("Local model auth file contains duplicate credentials");
                }
            }
            return result;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException | IOException exception) {
            throw new IllegalStateException("Local model auth file is corrupted", exception);
        }
    }

    byte[] encode(Map<LocalModelAuthReference, StoredModelCredential> credentials) {
        Objects.requireNonNull(credentials, "credentials must not be null");
        ObjectNode root = json.createObjectNode();
        root.put("version", SCHEMA_VERSION);
        ObjectNode values = root.putObject("credentials");
        credentials.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(java.util.Comparator.comparing(LocalModelAuthReference::value)))
                .forEach(
                        entry -> writeCredential(values.putObject(entry.getKey().value()), entry.getValue()));
        try {
            byte[] bytes = json.writeValueAsBytes(root);
            if (bytes.length > MAX_FILE_BYTES) {
                throw new IllegalStateException("Local model auth file exceeds the size limit");
            }
            return bytes;
        } catch (IOException exception) {
            throw new IllegalStateException("Local model auth file could not be encoded", exception);
        }
    }

    private StoredModelCredential parseCredential(LocalModelAuthReference reference, JsonNode node) {
        if (!node.isObject()) throw new IllegalStateException("Local model auth credential entry is invalid");
        String kind = requiredText(node, "kind");
        if ("API_KEY".equals(kind)) {
            requireExactFields(node, Set.of("kind", "api_key"));
            return new StoredApiKeyCredential(reference, requiredText(node, "api_key"));
        }
        if ("EXTERNAL".equals(kind)) {
            requireFields(
                    node,
                    Set.of(
                            "kind",
                            "method_id",
                            "client_registration_ref",
                            "access_token",
                            "refresh_token",
                            "expires_at_epoch_millis",
                            "issued_at_epoch_millis",
                            "account_id"),
                    Set.of("reason_code"));
            return new StoredExternalCredential(
                    reference,
                    new ExternalLoginMethodId(requiredText(node, "method_id")),
                    requiredText(node, "client_registration_ref"),
                    requiredText(node, "access_token"),
                    requiredText(node, "refresh_token"),
                    requiredLong(node, "expires_at_epoch_millis"),
                    requiredLong(node, "issued_at_epoch_millis"),
                    requiredText(node, "account_id"),
                    optionalText(node, "reason_code"));
        }
        throw new IllegalStateException("Local model auth credential kind is unsupported");
    }

    private static void writeCredential(ObjectNode node, StoredModelCredential credential) {
        if (credential instanceof StoredApiKeyCredential apiKey) {
            node.put("kind", "API_KEY");
            node.put("api_key", apiKey.apiKey());
            return;
        }
        StoredExternalCredential external = (StoredExternalCredential) credential;
        node.put("kind", "EXTERNAL");
        node.put("method_id", external.methodId().value());
        node.put("client_registration_ref", external.clientRegistrationRef());
        node.put("access_token", external.accessToken());
        node.put("refresh_token", external.refreshToken());
        node.put("expires_at_epoch_millis", external.expiresAtEpochMillis());
        node.put("issued_at_epoch_millis", external.issuedAtEpochMillis());
        node.put("account_id", external.accountId());
        external.reasonCode().ifPresent(code -> node.put("reason_code", code));
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException("Local model auth credential field is invalid");
        }
        return value.textValue();
    }

    private static long requiredLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong() || value.longValue() < 1) {
            throw new IllegalStateException("Local model auth credential timestamp is invalid");
        }
        return value.longValue();
    }

    private static java.util.Optional<String> optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return java.util.Optional.empty();
        }
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException("Local model auth credential field is invalid");
        }
        return java.util.Optional.of(value.textValue().trim());
    }

    private static void requireExactFields(JsonNode node, Set<String> allowed) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        if (!allowed.containsAll(names) || !names.containsAll(allowed)) {
            throw new IllegalStateException("Local model auth schema contains unexpected fields");
        }
    }

    private static void requireFields(JsonNode node, Set<String> required, Set<String> optional) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        Set<String> allowed = new java.util.HashSet<>(required);
        allowed.addAll(optional);
        if (!allowed.containsAll(names) || !names.containsAll(required)) {
            throw new IllegalStateException("Local model auth schema contains unexpected fields");
        }
    }
}

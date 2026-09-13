package io.haifa.agent.auth.localmodel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/** Serializes and deserializes individual stored model credentials into typed JSON payloads for the OS store. */
final class StoredModelCredentialPayloadCodec {
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    private final ObjectMapper json;

    StoredModelCredentialPayloadCodec(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json must not be null");
    }

    String encode(StoredModelCredential credential) {
        Objects.requireNonNull(credential, "credential must not be null");
        ObjectNode node = json.createObjectNode();
        if (credential instanceof StoredApiKeyCredential apiKey) {
            node.put("kind", "API_KEY");
            node.put("api_key", apiKey.apiKey());
        } else if (credential instanceof StoredExternalCredential external) {
            node.put("kind", "EXTERNAL");
            node.put("method_id", external.methodId().value());
            node.put("client_registration_ref", external.clientRegistrationRef());
            node.put("access_token", external.accessToken());
            node.put("refresh_token", external.refreshToken());
            node.put("expires_at_epoch_millis", external.expiresAtEpochMillis());
            node.put("issued_at_epoch_millis", external.issuedAtEpochMillis());
            node.put("account_id", external.accountId());
            external.reasonCode().ifPresent(code -> node.put("reason_code", code));
        } else {
            throw new IllegalArgumentException(
                    "Unsupported credential type: " + credential.getClass().getName());
        }
        try {
            return json.writeValueAsString(node);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to encode credential payload", exception);
        }
    }

    StoredModelCredential decode(LocalModelAuthReference reference, String payload) {
        Objects.requireNonNull(reference, "reference must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        if (payload.isBlank() || payload.length() > MAX_PAYLOAD_BYTES) {
            throw new IllegalStateException("Stored credential payload is invalid or exceeds size limit");
        }
        try {
            JsonNode node = json.readTree(payload);
            if (!node.isObject()) {
                throw new IllegalStateException("Stored credential payload schema is invalid");
            }
            String kind = requiredText(node, "kind");
            if ("API_KEY".equals(kind)) {
                return new StoredApiKeyCredential(reference, requiredText(node, "api_key"));
            }
            if ("EXTERNAL".equals(kind)) {
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
            throw new IllegalStateException("Unsupported credential kind in payload: " + kind);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException | IOException exception) {
            throw new IllegalStateException("Stored credential payload is corrupted", exception);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException("Stored credential payload field is missing or invalid: " + field);
        }
        return value.textValue();
    }

    private static long requiredLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong() || value.longValue() < 1) {
            throw new IllegalStateException("Stored credential payload timestamp is invalid: " + field);
        }
        return value.longValue();
    }

    private static Optional<String> optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException("Stored credential optional field is invalid: " + field);
        }
        return Optional.of(value.textValue().trim());
    }
}

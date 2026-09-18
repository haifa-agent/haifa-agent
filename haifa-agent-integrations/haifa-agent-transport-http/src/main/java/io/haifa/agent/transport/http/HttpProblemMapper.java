package io.haifa.agent.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.runtime.api.RuntimeApiErrorCode;
import io.haifa.agent.runtime.api.RuntimeContractException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;

final class HttpProblemMapper {
    private final ObjectMapper json;
    private final Clock clock;

    HttpProblemMapper(ObjectMapper json, Clock clock) {
        this.json = Objects.requireNonNull(json);
        this.clock = Objects.requireNonNull(clock);
    }

    HttpTransportResponse map(Throwable failure, String correlationId) {
        RuntimeApiErrorCode code;
        int status;
        String detail;
        if (failure instanceof TransportFailure transport) {
            code = transport.code();
            status = transport.status();
            detail = transport.getMessage();
        } else if (failure instanceof RuntimeContractException contract) {
            code = contract.code();
            status = status(contract.code());
            detail = contract.getMessage();
        } else if (failure instanceof HttpAuthenticationException) {
            code = RuntimeApiErrorCode.AUTHENTICATION_REQUIRED;
            status = 401;
            detail = "Authentication is required";
        } else if (failure instanceof HttpAuthorizationException || failure instanceof SecurityException) {
            code = RuntimeApiErrorCode.RUN_NOT_FOUND;
            status = 404;
            detail = "The resource does not exist or is not visible";
        } else if (failure instanceof IllegalArgumentException) {
            code = RuntimeApiErrorCode.RUN_STATE_CONFLICT;
            status = 400;
            detail = "The request is invalid";
        } else {
            code = RuntimeApiErrorCode.INTERNAL_ERROR;
            status = 500;
            detail = "The request could not be completed";
        }
        ObjectNode problem = json.createObjectNode();
        problem.put("type", "urn:haifa:problem:" + code.name().toLowerCase(java.util.Locale.ROOT));
        problem.put("title", title(status));
        problem.put("status", status);
        problem.put("errorCode", code.name());
        problem.put("correlationId", correlationId);
        problem.put("detail", safe(detail));
        problem.put("timestamp", java.time.Instant.ofEpochMilli(clock.millis()).toString());
        return new HttpTransportResponse(
                status,
                Map.of(
                        "Content-Type", "application/problem+json",
                        "X-Haifa-Api-Version", "1.0",
                        "X-Correlation-Id", correlationId),
                problem.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static int status(RuntimeApiErrorCode code) {
        return switch (code) {
            case RUN_NOT_FOUND, INTERACTION_NOT_FOUND -> 404;
            case CURSOR_EXPIRED -> 410;
            case PAYLOAD_TOO_LARGE -> 413;
            case RATE_LIMITED -> 429;
            case CONTRACT_VERSION_UNSUPPORTED -> 400;
            case RUN_VERSION_CONFLICT, INTERACTION_REVISION_CONFLICT -> 412;
            case RUN_STATE_CONFLICT,
                    IDEMPOTENCY_CONFLICT,
                    INTERACTION_ALREADY_RESOLVED,
                    INTERACTION_EXPIRED,
                    INTERACTION_ACTION_NOT_ALLOWED,
                    APPROVAL_AUTHORITY_DENIED,
                    APPROVAL_TARGET_STALE,
                    CURSOR_INVALID -> 409;
            case AUTHENTICATION_REQUIRED -> 401;
            case INTERNAL_ERROR -> 500;
        };
    }

    private static String title(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 404 -> "Not Found";
            case 409 -> "Conflict";
            case 410 -> "Gone";
            case 412 -> "Precondition Failed";
            case 413 -> "Payload Too Large";
            case 415 -> "Unsupported Media Type";
            case 406 -> "Not Acceptable";
            case 429 -> "Too Many Requests";
            default -> "Internal Server Error";
        };
    }

    private static String safe(String detail) {
        if (detail == null || detail.isBlank() || detail.length() > 512) return "The request could not be completed";
        return detail.replaceAll("[\\r\\n\\p{Cntrl}]", " ");
    }
}

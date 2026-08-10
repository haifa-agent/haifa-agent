package io.haifa.agent.personalassistant.server.web.v1.error;

import io.haifa.agent.personalassistant.application.mission.MissionException;
import io.haifa.agent.personalassistant.server.web.v1.dto.PersonalApiDtos;
import io.haifa.agent.sdk.api.HaifaAgentException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

/** Stable safe error envelope. Exceptions and request bodies are intentionally not reflected. */
@RestControllerAdvice
public final class PersonalApiExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(PersonalApiExceptionHandler.class);

    @ExceptionHandler(MissionException.class)
    ResponseEntity<PersonalApiDtos.Error> mission(MissionException exception, ServerWebExchange exchange) {
        String code = exception.code();
        HttpStatus status;
        if (code.contains("NOT_FOUND")) {
            status = HttpStatus.NOT_FOUND;
        } else if (code.contains("PRECONDITION") || code.contains("REVISION_STALE")) {
            status = HttpStatus.PRECONDITION_FAILED;
        } else if (code.contains("CAPACITY")) {
            status = HttpStatus.TOO_MANY_REQUESTS;
        } else if (code.contains("UNAVAILABLE") || code.contains("NOT_READY")) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
        } else if (code.contains("PLANNER") || code.contains("RESULT_INVALID")) {
            status = HttpStatus.UNPROCESSABLE_ENTITY;
        } else if (code.contains("CONFLICT")
                || code.contains("ACTIVE")
                || code.contains("FROZEN")
                || code.contains("RETRYABLE")
                || code.contains("STATE")) {
            status = HttpStatus.CONFLICT;
        } else {
            status = HttpStatus.BAD_REQUEST;
        }
        String diagnosticId = correlation(exchange);
        return ResponseEntity.status(status)
                .body(new PersonalApiDtos.Error(
                        code, exception.getMessage(), diagnosticId, diagnosticId, actions(status)));
    }

    @ExceptionHandler(HaifaAgentException.class)
    ResponseEntity<PersonalApiDtos.Error> sdk(HaifaAgentException exception) {
        String code = exception.code();
        HttpStatus status = code.contains("NOT_FOUND")
                ? HttpStatus.NOT_FOUND
                : code.contains("CONFLICT") || code.contains("REVISION") ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                .body(new PersonalApiDtos.Error(code, exception.getMessage(), exception.correlation()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<PersonalApiDtos.Error> illegalArgument(
            IllegalArgumentException exception, ServerWebExchange exchange) {
        String correlation = correlation(exchange);
        LOGGER.warn(
                "personal_api_invalid_argument correlationId={} method={} path={} exceptionType={} origin={}",
                correlation,
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath().value(),
                exception.getClass().getName(),
                origin(exception));
        return ResponseEntity.badRequest()
                .body(new PersonalApiDtos.Error("INVALID_REQUEST", "The request is invalid.", correlation));
    }

    @ExceptionHandler(ServerWebInputException.class)
    ResponseEntity<PersonalApiDtos.Error> badRequest(ServerWebInputException exception, ServerWebExchange exchange) {
        return ResponseEntity.badRequest()
                .body(new PersonalApiDtos.Error("INVALID_REQUEST", "The request is invalid.", correlation(exchange)));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<PersonalApiDtos.Error> responseStatus(
            ResponseStatusException exception, ServerWebExchange exchange) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        String code =
                switch (status) {
                    case UNSUPPORTED_MEDIA_TYPE -> "UNSUPPORTED_MEDIA_TYPE";
                    case METHOD_NOT_ALLOWED -> "METHOD_NOT_ALLOWED";
                    case NOT_ACCEPTABLE -> "NOT_ACCEPTABLE";
                    case NOT_FOUND -> "NOT_FOUND";
                    default -> status.is4xxClientError() ? "INVALID_REQUEST" : "UPSTREAM_REQUEST_FAILED";
                };
        String message = status == HttpStatus.UNSUPPORTED_MEDIA_TYPE
                ? "The request content type is not supported."
                : "The request could not be accepted.";
        return ResponseEntity.status(status).body(new PersonalApiDtos.Error(code, message, correlation(exchange)));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<PersonalApiDtos.Error> conflict(IllegalStateException exception, ServerWebExchange exchange) {
        String candidate = exception.getMessage();
        String code = candidate != null && candidate.matches("[A-Z][A-Z0-9_]{2,63}") ? candidate : "OPERATION_CONFLICT";
        HttpStatus status = code.contains("UNAVAILABLE") ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        return ResponseEntity.status(status)
                .body(new PersonalApiDtos.Error(code, "The operation cannot be completed.", correlation(exchange)));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<PersonalApiDtos.Error> notFound(NoResourceFoundException exception, ServerWebExchange exchange) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new PersonalApiDtos.Error(
                        "NOT_FOUND", "The requested resource was not found.", correlation(exchange)));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<PersonalApiDtos.Error> unexpected(Exception exception, ServerWebExchange exchange) {
        String correlation = correlation(exchange);
        LOGGER.warn(
                "personal_api_unexpected_error correlationId={} exceptionType={} origin={}",
                correlation,
                exception.getClass().getName(),
                origin(exception));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new PersonalApiDtos.Error("INTERNAL_ERROR", "The request could not be completed.", correlation));
    }

    private static String correlation(ServerWebExchange exchange) {
        String existing = exchange.getRequest().getHeaders().getFirst("X-Request-Id");
        return existing == null || existing.isBlank()
                ? UUID.randomUUID().toString()
                : existing.substring(0, Math.min(128, existing.length()));
    }

    private static java.util.List<String> actions(HttpStatus status) {
        return switch (status) {
            case TOO_MANY_REQUESTS -> java.util.List.of("WAIT_OR_CONTACT_ADMIN");
            case SERVICE_UNAVAILABLE -> java.util.List.of("RETRY_AFTER_READINESS");
            case PRECONDITION_FAILED, CONFLICT -> java.util.List.of("REFRESH_AND_RETRY");
            default -> java.util.List.of("REVIEW_REQUEST");
        };
    }

    private static String origin(Exception exception) {
        StackTraceElement[] trace = exception.getStackTrace();
        if (trace.length == 0) return "unknown";
        StringBuilder value = new StringBuilder(trace[0].toString());
        for (int index = 1; index < Math.min(4, trace.length); index++) {
            value.append(" <- ").append(trace[index]);
        }
        return value.toString();
    }
}

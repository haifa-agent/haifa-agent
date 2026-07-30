package io.haifa.agent.personalassistant.server.web.v1.error;

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
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

/** Stable safe error envelope. Exceptions and request bodies are intentionally not reflected. */
@RestControllerAdvice
public final class PersonalApiExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(PersonalApiExceptionHandler.class);

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

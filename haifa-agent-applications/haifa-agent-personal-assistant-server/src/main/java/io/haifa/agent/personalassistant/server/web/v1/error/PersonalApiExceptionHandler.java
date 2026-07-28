package io.haifa.agent.personalassistant.server.web.v1.error;

import io.haifa.agent.personalassistant.server.web.v1.dto.PersonalApiDtos;
import io.haifa.agent.sdk.api.HaifaAgentException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

/** Stable safe error envelope. Exceptions and request bodies are intentionally not reflected. */
@RestControllerAdvice
public final class PersonalApiExceptionHandler {
    @ExceptionHandler(HaifaAgentException.class)
    ResponseEntity<PersonalApiDtos.Error> sdk(HaifaAgentException exception) {
        String code = exception.code();
        HttpStatus status = code.contains("NOT_FOUND")
                ? HttpStatus.NOT_FOUND
                : code.contains("CONFLICT") || code.contains("REVISION") ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                .body(new PersonalApiDtos.Error(code, exception.getMessage(), exception.correlation()));
    }

    @ExceptionHandler({IllegalArgumentException.class, ServerWebInputException.class})
    ResponseEntity<PersonalApiDtos.Error> badRequest(Exception exception, ServerWebExchange exchange) {
        return ResponseEntity.badRequest()
                .body(new PersonalApiDtos.Error("INVALID_REQUEST", "The request is invalid.", correlation(exchange)));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<PersonalApiDtos.Error> unexpected(Exception exception, ServerWebExchange exchange) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new PersonalApiDtos.Error(
                        "INTERNAL_ERROR", "The request could not be completed.", correlation(exchange)));
    }

    private static String correlation(ServerWebExchange exchange) {
        String existing = exchange.getRequest().getHeaders().getFirst("X-Request-Id");
        return existing == null || existing.isBlank()
                ? UUID.randomUUID().toString()
                : existing.substring(0, Math.min(128, existing.length()));
    }
}

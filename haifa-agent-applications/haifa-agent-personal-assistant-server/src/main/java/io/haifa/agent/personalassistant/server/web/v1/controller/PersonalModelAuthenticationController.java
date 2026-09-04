package io.haifa.agent.personalassistant.server.web.v1.controller;

import io.haifa.agent.auth.localmodel.ExternalLoginAttemptId;
import io.haifa.agent.auth.localmodel.ExternalLoginMethodId;
import io.haifa.agent.auth.localmodel.ExternalLoginMode;
import io.haifa.agent.auth.localmodel.LocalModelAuthenticationService;
import io.haifa.agent.auth.localmodel.antigravity.AntigravityExternalLoginMethod;
import io.haifa.agent.auth.localmodel.codex.CodexExternalLoginMethod;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.personalassistant.server.configuration.product.PersonalAssistantProperties;
import io.haifa.agent.personalassistant.server.web.v1.dto.PersonalApiDtos;
import io.haifa.agent.personalassistant.server.web.v1.mapper.PersonalApiMapper;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Loopback-protected HTTP adapter for safe local model connection use cases. */
@RestController
@RequestMapping("/api/v1/model-connections")
public final class PersonalModelAuthenticationController {
    private final LocalModelAuthenticationService authentication;
    private final PersonalApiMapper mapper;
    private final Supplier<List<PersonalAssistantProperties.ModelProvider>> providers;

    @Autowired
    public PersonalModelAuthenticationController(
            LocalModelAuthenticationService authentication,
            PersonalApiMapper mapper,
            PersonalAssistantProperties properties) {
        this(authentication, mapper, properties::modelProviders);
    }

    PersonalModelAuthenticationController(LocalModelAuthenticationService authentication, PersonalApiMapper mapper) {
        this(authentication, mapper, List::of);
    }

    PersonalModelAuthenticationController(
            LocalModelAuthenticationService authentication,
            PersonalApiMapper mapper,
            Supplier<List<PersonalAssistantProperties.ModelProvider>> providers) {
        this.authentication = Objects.requireNonNull(authentication, "authentication must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.providers = Objects.requireNonNull(providers, "providers must not be null");
    }

    @GetMapping
    Mono<List<PersonalApiDtos.ModelConnection>> connections() {
        return Mono.fromCallable(this::connectionViews).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/api-key")
    Mono<ResponseEntity<PersonalApiDtos.ModelConnection>> saveApiKey(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PersonalApiDtos.SaveModelApiKey request) {
        return Mono.fromCallable(() -> {
                    PersonalApiDtos.SaveModelApiKey value = Objects.requireNonNull(request, "request must not be null");
                    char[] secret = Objects.requireNonNull(value.apiKey(), "apiKey must not be null");
                    if (!apiKeySupported(value.providerId())) {
                        java.util.Arrays.fill(secret, '\0');
                        throw new IllegalStateException("AUTH_API_KEY_UNAVAILABLE");
                    }
                    return mapper.modelConnection(authentication.saveApiKey(value.providerId(), secret));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(connection -> ResponseEntity.created(URI.create("/api/v1/model-connections"))
                        .body(connection));
    }

    @PostMapping("/{methodId}/browser-attempts")
    Mono<ResponseEntity<PersonalApiDtos.ExternalLoginAttempt>> startBrowserAttempt(
            @PathVariable String methodId, @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return Mono.fromCallable(() -> {
                    ExternalLoginMethodId method = externalLoginMethod(methodId);
                    return mapper.externalLoginAttempt(
                            authentication.startExternalLogin(method, ExternalLoginMode.BROWSER));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(attempt -> ResponseEntity.accepted().body(attempt));
    }

    @GetMapping("/{methodId}/browser-attempts/{attemptId}")
    PersonalApiDtos.ExternalLoginAttempt attempt(@PathVariable String methodId, @PathVariable String attemptId) {
        externalLoginMethod(methodId);
        return mapper.externalLoginAttempt(authentication.attempt(new ExternalLoginAttemptId(attemptId)));
    }

    @DeleteMapping("/{methodId}/browser-attempts/{attemptId}")
    ResponseEntity<Void> cancel(
            @PathVariable String methodId,
            @PathVariable String attemptId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        externalLoginMethod(methodId);
        return authentication.cancel(new ExternalLoginAttemptId(attemptId))
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{*connectionId}")
    Mono<ResponseEntity<Void>> logout(
            @PathVariable String connectionId, @RequestHeader("Idempotency-Key") String idempotencyKey) {
        String normalized = connectionId.startsWith("/") ? connectionId.substring(1) : connectionId;
        return Mono.fromCallable(() -> authentication.logout(normalized))
                .subscribeOn(Schedulers.boundedElastic())
                .map(deleted -> deleted
                        ? ResponseEntity.noContent().build()
                        : ResponseEntity.notFound().build());
    }

    private List<PersonalApiDtos.ModelConnection> connectionViews() {
        List<PersonalApiDtos.ModelConnection> result = new java.util.ArrayList<>();
        List<io.haifa.agent.auth.localmodel.LocalModelConnectionView> stored = authentication.connections();
        java.util.Set<String> projected = new java.util.LinkedHashSet<>();
        for (PersonalAssistantProperties.ModelProvider provider : providers.get()) {
            String reference = provider.credentialReference();
            var managed = stored.stream()
                    .filter(connection -> connection.connectionId().value().equals(reference))
                    .findFirst();
            if (managed.isPresent()) {
                result.add(mapper.modelConnection(managed.orElseThrow()));
                projected.add(reference);
                continue;
            }
            boolean ready = !authentication.connectionRequired(new CredentialRef(reference));
            boolean environment = reference.startsWith("env://");
            ExternalLoginMethodId externalMethod = externalLoginMethod(provider).orElse(null);
            boolean externalLogin = externalMethod != null;
            result.add(new PersonalApiDtos.ModelConnection(
                    "configured://" + provider.id() + "/default",
                    provider.id(),
                    externalLogin ? "EXTERNAL_LOGIN" : "API_KEY",
                    ready ? "AUTHENTICATED" : "REAUTH_REQUIRED",
                    environment
                            ? ready ? "Environment credential" : "Environment credential unavailable"
                            : "Not connected",
                    null,
                    ready ? null : "AUTH_CREDENTIAL_REQUIRED",
                    !environment && !externalLogin,
                    externalLogin && externalLoginSupported(externalMethod),
                    false,
                    externalLogin && externalLoginUnofficial(externalMethod)));
        }
        authentication.externalLoginMethods().forEach(method -> {
            String reference = externalCredentialReference(method.methodId());
            if (!projected.add(reference)) return;
            stored.stream()
                    .filter(connection -> connection.connectionId().value().equals(reference))
                    .findFirst()
                    .map(mapper::modelConnection)
                    .ifPresentOrElse(
                            result::add,
                            () -> result.add(new PersonalApiDtos.ModelConnection(
                                    "configured://" + method.methodId().value() + "/default",
                                    method.methodId().value(),
                                    "EXTERNAL_LOGIN",
                                    "REAUTH_REQUIRED",
                                    "Not connected",
                                    null,
                                    "AUTH_CREDENTIAL_REQUIRED",
                                    false,
                                    true,
                                    false,
                                    method.unofficial())));
        });
        stored.stream()
                .filter(connection -> projected.add(connection.connectionId().value()))
                .map(mapper::modelConnection)
                .forEach(result::add);
        return List.copyOf(result);
    }

    private boolean apiKeySupported(String providerId) {
        List<PersonalAssistantProperties.ModelProvider> configured = providers.get();
        if (configured.isEmpty()) {
            return !"openai-codex".equalsIgnoreCase(providerId) && !"google-antigravity".equalsIgnoreCase(providerId);
        }
        return configured.stream()
                .anyMatch(provider -> provider.id().equalsIgnoreCase(providerId)
                        && provider.credentialReference().startsWith("model-auth://")
                        && externalLoginMethod(provider).isEmpty());
    }

    private boolean externalLoginSupported(ExternalLoginMethodId methodId) {
        return authentication.externalLoginMethods().stream()
                .anyMatch(value -> value.methodId().equals(methodId));
    }

    private boolean externalLoginUnofficial(ExternalLoginMethodId methodId) {
        return authentication.externalLoginMethods().stream()
                .filter(value -> value.methodId().equals(methodId))
                .findFirst()
                .map(io.haifa.agent.auth.localmodel.ExternalLoginMethodDescriptor::unofficial)
                .orElse(false);
    }

    private static ExternalLoginMethodId externalLoginMethod(String methodId) {
        return switch (Objects.requireNonNull(methodId, "methodId must not be null")) {
            case "codex" -> CodexExternalLoginMethod.METHOD_ID;
            case "antigravity" -> AntigravityExternalLoginMethod.METHOD_ID;
            default -> throw new IllegalArgumentException("AUTH_LOGIN_METHOD_UNAVAILABLE");
        };
    }

    private static java.util.Optional<ExternalLoginMethodId> externalLoginMethod(
            PersonalAssistantProperties.ModelProvider provider) {
        if ("openai-codex".equals(provider.id())) return java.util.Optional.of(CodexExternalLoginMethod.METHOD_ID);
        if ("model-auth://google-antigravity/default".equals(provider.credentialReference())) {
            return java.util.Optional.of(AntigravityExternalLoginMethod.METHOD_ID);
        }
        return java.util.Optional.empty();
    }

    private static String externalCredentialReference(ExternalLoginMethodId methodId) {
        if (CodexExternalLoginMethod.METHOD_ID.equals(methodId)) return "model-auth://openai-codex/default";
        if (AntigravityExternalLoginMethod.METHOD_ID.equals(methodId)) {
            return "model-auth://google-antigravity/default";
        }
        throw new IllegalArgumentException("AUTH_LOGIN_METHOD_UNAVAILABLE");
    }
}

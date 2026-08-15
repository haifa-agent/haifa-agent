package io.haifa.agent.personalassistant.server.web.v1.controller;

import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.content.ImageUrlContentPart;
import io.haifa.agent.personalassistant.application.PersonalAssistantApplication;
import io.haifa.agent.personalassistant.server.configuration.product.PersonalAssistantProperties;
import io.haifa.agent.personalassistant.server.image.PersonalImageStore;
import io.haifa.agent.personalassistant.server.observability.PersonalRunLoggingService;
import io.haifa.agent.personalassistant.server.web.v1.dto.PersonalApiDtos;
import io.haifa.agent.personalassistant.server.web.v1.mapper.PersonalApiMapper;
import io.haifa.agent.sdk.conversation.ConversationStatus;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1")
public final class PersonalAssistantController {
    private final PersonalAssistantApplication application;
    private final PersonalApiMapper mapper;
    private final PersonalAssistantProperties properties;
    private final PersonalRunLoggingService runLogging;
    private final PersonalImageStore imageStore;

    public PersonalAssistantController(
            PersonalAssistantApplication application,
            PersonalApiMapper mapper,
            PersonalAssistantProperties properties,
            PersonalRunLoggingService runLogging,
            PersonalImageStore imageStore) {
        this.application = application;
        this.mapper = mapper;
        this.properties = properties;
        this.runLogging = runLogging;
        this.imageStore = imageStore;
    }

    @GetMapping("/bootstrap")
    PersonalApiDtos.Bootstrap bootstrap() {
        List<String> capabilities = new ArrayList<>(List.of(
                "conversation",
                "usage",
                "tool",
                "skill",
                "mcp",
                "memory",
                "interaction",
                "approval",
                "shell",
                "execution",
                "recommended-questions",
                "mission",
                "sse"));
        Set<String> registeredTools = application.capabilities().registrations().stream()
                .filter(registration -> "TOOL".equals(registration.kind()))
                .map(
                        io.haifa.agent.personalassistant.application.PersonalCapabilityRegistry.CapabilityRegistration
                                ::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (registeredTools.containsAll(Set.of("web_search", "web_fetch"))) {
            capabilities.add("web-research");
        }
        return new PersonalApiDtos.Bootstrap(
                "Haifa Personal Assistant",
                "v1",
                "connected",
                properties.caller().principal(),
                List.copyOf(capabilities),
                application.productDigest(),
                properties.defaultModelId(),
                application.models().stream().map(mapper::model).toList());
    }

    @GetMapping("/models")
    List<PersonalApiDtos.Model> models() {
        return application.models().stream().map(mapper::model).toList();
    }

    @GetMapping("/conversations")
    List<PersonalApiDtos.Conversation> conversations(
            @RequestParam Optional<String> q,
            @RequestParam(defaultValue = "ACTIVE") Set<String> status,
            @RequestParam(defaultValue = "50") int limit) {
        return application.conversations(q, status, bounded(limit)).stream()
                .map(mapper::conversation)
                .toList();
    }

    @PostMapping("/conversations")
    ResponseEntity<PersonalApiDtos.Conversation> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PersonalApiDtos.CreateConversation request) {
        String modelId = request.modelId() == null || request.modelId().isBlank()
                ? properties.defaultModelId()
                : text(request.modelId(), "modelId");
        var value = mapper.conversation(
                request.modelSelection() == null
                        ? application.start(
                                key(idempotencyKey),
                                text(request.displayName(), "displayName"),
                                text(request.message(), "message"),
                                modelId,
                                imageInputs(request.images()))
                        : application.start(
                                key(idempotencyKey),
                                text(request.displayName(), "displayName"),
                                text(request.message(), "message"),
                                modelSelection(request.modelSelection()),
                                imageInputs(request.images())));
        value.activeRunId().ifPresent(runId -> runLogging.observe(value.id(), runId, "conversation-created"));
        return ResponseEntity.created(URI.create("/api/v1/conversations/" + value.id()))
                .eTag(Long.toString(value.revision()))
                .body(value);
    }

    @PatchMapping("/conversations/{conversationId}/model")
    PersonalApiDtos.ModelSelection selectModel(
            @PathVariable String conversationId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PersonalApiDtos.SelectModel request) {
        return mapper.modelSelection(application.selectModel(
                conversationId, revision(ifMatch), key(idempotencyKey), modelSelection(request)));
    }

    private static io.haifa.agent.personalassistant.application.PersonalModelSelectionRequest modelSelection(
            PersonalApiDtos.SelectModel request) {
        var preferences = java.util.Objects.requireNonNull(request.preferences(), "preferences must not be null");
        return new io.haifa.agent.personalassistant.application.PersonalModelSelectionRequest(
                text(request.modelBindingId(), "modelBindingId"),
                text(request.preferenceSchemaVersion(), "preferenceSchemaVersion"),
                text(request.profileVersion(), "profileVersion"),
                text(request.profileDigest(), "profileDigest"),
                new io.haifa.agent.personalassistant.application.PersonalModelPreferences(
                        io.haifa.agent.personalassistant.application.PersonalResponseMode.valueOf(
                                text(preferences.responseMode(), "responseMode")),
                        java.util.Optional.ofNullable(preferences.effort())
                                .map(String::trim)
                                .filter(value -> !value.isEmpty())
                                .map(io.haifa.agent.model.api.ModelReasoningEffort::valueOf),
                        io.haifa.agent.personalassistant.application.PersonalResponseLength.valueOf(
                                text(preferences.responseLength(), "responseLength"))));
    }

    @GetMapping("/conversations/{conversationId}")
    ResponseEntity<PersonalApiDtos.Conversation> conversation(@PathVariable String conversationId) {
        return application
                .conversation(conversationId)
                .map(mapper::conversation)
                .map(value -> ResponseEntity.ok()
                        .eTag(Long.toString(value.revision()))
                        .body(value))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PatchMapping("/conversations/{conversationId}")
    ResponseEntity<PersonalApiDtos.Conversation> update(
            @PathVariable String conversationId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PersonalApiDtos.UpdateConversation request) {
        long revision = revision(ifMatch);
        PersonalAssistantApplication.ConversationView value;
        if (request.displayName() != null && !request.displayName().isBlank()) {
            value = application.rename(conversationId, revision, key(idempotencyKey), request.displayName());
        } else {
            ConversationStatus status =
                    ConversationStatus.valueOf(text(request.status(), "status").toUpperCase(Locale.ROOT));
            value = application.status(conversationId, revision, key(idempotencyKey), status);
        }
        var body = mapper.conversation(value);
        return ResponseEntity.ok().eTag(Long.toString(body.revision())).body(body);
    }

    @GetMapping("/conversations/{conversationId}/turns")
    List<PersonalApiDtos.Turn> turns(
            @PathVariable String conversationId, @RequestParam(defaultValue = "100") int limit) {
        return application.turns(conversationId, bounded(limit)).stream()
                .map(mapper::turn)
                .toList();
    }

    @PostMapping("/conversations/{conversationId}/messages")
    ResponseEntity<PersonalApiDtos.Conversation> submit(
            @PathVariable String conversationId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PersonalApiDtos.SubmitMessage request) {
        var body = mapper.conversation(application.submit(
                conversationId,
                revision(ifMatch),
                key(idempotencyKey),
                text(request.message(), "message"),
                imageInputs(request.images())));
        body.activeRunId().ifPresent(runId -> runLogging.observe(body.id(), runId, "message-submitted"));
        return ResponseEntity.accepted().eTag(Long.toString(body.revision())).body(body);
    }

    @PostMapping(
            value = "/images",
            consumes = {"image/png", "image/jpeg", "image/webp", "image/gif"})
    ResponseEntity<PersonalApiDtos.UploadedImage> uploadImage(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(HttpHeaders.CONTENT_TYPE) String mediaType,
            @RequestHeader(value = "X-Image-Filename", required = false) String filename,
            @RequestBody byte[] bytes) {
        key(idempotencyKey);
        var image = imageStore.save(bytes, mediaType, filename);
        return ResponseEntity.created(URI.create("/api/v1/images/" + image.imageId()))
                .body(new PersonalApiDtos.UploadedImage(
                        image.imageId(),
                        image.mediaType(),
                        image.sizeBytes(),
                        image.originalFilename(),
                        image.sha256()));
    }

    private List<ContentPart> imageInputs(List<PersonalApiDtos.ImageInput> values) {
        List<PersonalApiDtos.ImageInput> inputs = values == null ? List.of() : List.copyOf(values);
        if (inputs.size() > 4) throw new IllegalArgumentException("a message may contain at most 4 images");
        List<ContentPart> result = new ArrayList<>(inputs.size());
        for (PersonalApiDtos.ImageInput input : inputs) {
            String kind = text(input.kind(), "image.kind").toLowerCase(Locale.ROOT);
            switch (kind) {
                case "url" -> result.add(new ImageUrlContentPart(URI.create(text(input.url(), "image.url"))));
                case "upload" -> result.add(imageStore.reference(text(input.imageId(), "image.imageId")));
                default -> throw new IllegalArgumentException("image.kind must be url or upload");
            }
        }
        return List.copyOf(result);
    }

    @PostMapping("/conversations/{conversationId}/runs/{runId}/recommend-questions")
    Mono<PersonalApiDtos.RecommendedQuestions> recommendQuestions(
            @PathVariable String conversationId,
            @PathVariable String runId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        key(idempotencyKey);
        return Mono.fromCallable(() ->
                        new PersonalApiDtos.RecommendedQuestions(application.recommendQuestions(conversationId, runId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/runs/{runId}")
    ResponseEntity<PersonalApiDtos.Run> run(@PathVariable String runId) {
        return application.run(runId).map(mapper::run).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound()
                .build());
    }

    @PostMapping("/runs/{runId}/cancel")
    PersonalApiDtos.Run cancel(@PathVariable String runId, @RequestHeader("Idempotency-Key") String idempotencyKey) {
        key(idempotencyKey);
        return mapper.run(application.cancel(runId));
    }

    @GetMapping("/runs/{runId}/activities")
    List<PersonalApiDtos.Activity> activities(
            @PathVariable String runId, @RequestParam(defaultValue = "200") int limit) {
        return application.activities(runId, bounded(limit)).stream()
                .map(mapper::activity)
                .toList();
    }

    @GetMapping("/runs/{runId}/interaction")
    ResponseEntity<PersonalApiDtos.Interaction> interaction(@PathVariable String runId) {
        return application
                .pendingInteraction(runId)
                .map(mapper::interaction)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/runs/{runId}/interactions/{interactionId}/response")
    PersonalApiDtos.InteractionReceipt respond(
            @PathVariable String runId,
            @PathVariable String interactionId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PersonalApiDtos.InteractionResponse request) {
        return mapper.receipt(application.respond(
                runId,
                interactionId,
                revision(ifMatch),
                text(request.action(), "action"),
                Optional.ofNullable(request.text()),
                key(idempotencyKey)));
    }

    @GetMapping("/memory/candidates")
    List<PersonalApiDtos.MemoryCandidate> candidates(@RequestParam(defaultValue = "50") int limit) {
        return application.memoryCandidates(bounded(limit)).stream()
                .map(mapper::candidate)
                .toList();
    }

    @PostMapping("/memory/candidates/{candidateId}/approve")
    PersonalApiDtos.Memory approve(
            @PathVariable String candidateId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return mapper.memory(application.approveMemoryCandidate(candidateId, revision(ifMatch), key(idempotencyKey)));
    }

    @PostMapping("/memory/candidates/{candidateId}/reject")
    PersonalApiDtos.MemoryCandidate reject(
            @PathVariable String candidateId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PersonalApiDtos.RejectMemory request) {
        return mapper.candidate(application.rejectMemoryCandidate(
                candidateId, revision(ifMatch), key(idempotencyKey), text(request.reason(), "reason")));
    }

    @GetMapping("/memory")
    List<PersonalApiDtos.Memory> memories(@RequestParam(defaultValue = "100") int limit) {
        return application.memories(bounded(limit)).stream().map(mapper::memory).toList();
    }

    @PostMapping("/memory/{memoryId}/versions/{version}/invalidate")
    PersonalApiDtos.Memory invalidate(
            @PathVariable String memoryId,
            @PathVariable long version,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PersonalApiDtos.InvalidateMemory request) {
        return mapper.memory(
                application.invalidateMemory(memoryId, version, key(idempotencyKey), text(request.reason(), "reason")));
    }

    private static int bounded(int value) {
        if (value < 1 || value > 500) throw new IllegalArgumentException("limit must be between 1 and 500");
        return value;
    }

    private static long revision(String value) {
        String normalized = text(value, "If-Match").replace("W/", "").replace("\"", "");
        try {
            long revision = Long.parseLong(normalized);
            if (revision < 0) throw new NumberFormatException();
            return revision;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("If-Match must contain the numeric revision", exception);
        }
    }

    private static String key(String value) {
        String normalized = text(value, "Idempotency-Key");
        if (normalized.length() > 128) throw new IllegalArgumentException("Idempotency-Key is too long");
        return normalized;
    }

    private static String text(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 16_384) {
            throw new IllegalArgumentException(field + " must contain 1 to 16384 characters");
        }
        return normalized;
    }
}

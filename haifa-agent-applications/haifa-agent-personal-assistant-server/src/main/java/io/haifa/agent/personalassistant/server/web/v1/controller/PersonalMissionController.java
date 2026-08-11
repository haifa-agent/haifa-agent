package io.haifa.agent.personalassistant.server.web.v1.controller;

import io.haifa.agent.personalassistant.application.PersonalAssistantApplication;
import io.haifa.agent.personalassistant.application.mission.MissionApplicationService;
import io.haifa.agent.personalassistant.application.mission.MissionConstraints;
import io.haifa.agent.personalassistant.application.mission.MissionException;
import io.haifa.agent.personalassistant.application.mission.MissionListCursor;
import io.haifa.agent.personalassistant.application.mission.MissionMode;
import io.haifa.agent.personalassistant.application.mission.MissionTask;
import io.haifa.agent.personalassistant.application.mission.MissionTaskState;
import io.haifa.agent.personalassistant.application.mission.ResearchBrief;
import io.haifa.agent.personalassistant.server.configuration.product.PersonalAssistantProperties;
import io.haifa.agent.personalassistant.server.web.v1.dto.PersonalApiDtos;
import io.haifa.agent.personalassistant.server.web.v1.mapper.PersonalApiMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Explicit Mission HTTP surface. Trusted ownership and product limits never come from the browser. */
@RestController
@RequestMapping("/api/v1/missions")
public final class PersonalMissionController {
    private final MissionApplicationService missions;
    private final PersonalAssistantApplication application;
    private final PersonalAssistantProperties properties;
    private final PersonalApiMapper mapper;
    private final Clock clock;

    public PersonalMissionController(
            MissionApplicationService missions,
            PersonalAssistantApplication application,
            PersonalAssistantProperties properties,
            PersonalApiMapper mapper,
            Clock clock) {
        this.missions = missions;
        this.application = application;
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
    }

    @PostMapping
    ResponseEntity<PersonalApiDtos.MissionSnapshot> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PersonalApiDtos.CreateMission request) {
        if (request == null) throw new MissionException("MISSION_REQUEST_INVALID", "Mission request is required");
        String conversationId = text(request.conversationId(), "conversationId", 256);
        if (application.conversation(conversationId).isEmpty()) {
            throw new MissionException("MISSION_NOT_FOUND", "Mission is unavailable");
        }
        MissionMode mode =
                request.mode() == null || request.mode().isBlank() ? MissionMode.STANDARD : parseMode(request.mode());
        if (mode == MissionMode.STANDARD
                && request.selectedSkillId() != null
                && !request.selectedSkillId().isBlank()) {
            throw new MissionException(
                    "MISSION_SKILL_SELECTION_FORBIDDEN", "Browser-selected Mission Skills are not accepted");
        }
        if (mode == MissionMode.DEEP_RESEARCH && !"deep-research".equals(request.selectedSkillId())) {
            throw new MissionException(
                    "MISSION_SKILL_SELECTION_REQUIRED", "Deep Research requires the deep-research Skill");
        }
        Optional<ResearchBrief> brief = researchBrief(mode, request.researchBrief());
        List<String> criteria = request.acceptanceCriteria() == null ? List.of() : request.acceptanceCriteria();
        if (criteria.size() > properties.mission().maxAcceptanceCriteria()) {
            throw new MissionException("MISSION_LIMIT_EXCEEDED", "acceptanceCriteria exceeds the product limit");
        }
        var command = new MissionApplicationService.CreateMission(
                key(idempotencyKey),
                ownerScope(),
                conversationId,
                text(request.objective(), "objective", 8_000),
                criteria,
                constraints(request.constraints()),
                mode,
                brief,
                request.constraints() == null || request.constraints().deadlineAt() == null);
        var body = mapper.mission(missions.create(command));
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/missions/" + body.missionId()))
                .eTag(Long.toString(body.version()))
                .body(body);
    }

    @GetMapping
    PersonalApiDtos.MissionPage list(
            @RequestParam Optional<String> conversationId,
            @RequestParam Optional<String> cursor,
            @RequestParam(defaultValue = "20") int size) {
        if (size < 1 || size > 50) {
            throw new MissionException("MISSION_LIMIT_EXCEEDED", "Mission page size must be 1 to 50");
        }
        Optional<String> normalizedConversation =
                conversationId.filter(value -> !value.isBlank()).map(value -> text(value, "conversationId", 256));
        List<io.haifa.agent.personalassistant.application.mission.MissionSnapshot> values =
                missions.list(ownerScope(), normalizedConversation, decodeCursor(cursor), size + 1);
        boolean hasMore = values.size() > size;
        List<io.haifa.agent.personalassistant.application.mission.MissionSnapshot> page =
                hasMore ? values.subList(0, size) : values;
        Optional<String> next = hasMore ? Optional.of(encodeCursor(page.get(page.size() - 1))) : Optional.empty();
        return new PersonalApiDtos.MissionPage(
                page.stream().map(mapper::mission).toList(), next);
    }

    @GetMapping("/{missionId}")
    ResponseEntity<PersonalApiDtos.MissionSnapshot> get(@PathVariable String missionId) {
        return response(missionId);
    }

    @GetMapping("/{missionId}/snapshot")
    ResponseEntity<PersonalApiDtos.MissionSnapshot> snapshot(@PathVariable String missionId) {
        return response(missionId);
    }

    @GetMapping("/{missionId}/artifacts/{artifactId}")
    ResponseEntity<byte[]> artifact(@PathVariable String missionId, @PathVariable String artifactId) {
        String safeMissionId = text(missionId, "missionId", 256);
        if (missions.find(safeMissionId, ownerScope()).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var artifact = application.artifacts().findByProject("mission-" + safeMissionId).stream()
                .filter(value -> value.id().value().equals(text(artifactId, "artifactId", 256)))
                .findFirst();
        if (artifact.isEmpty()) return ResponseEntity.notFound().build();
        var value = artifact.orElseThrow();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(value.payload().mediaType()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(value.title(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(application.artifacts().load(value));
    }

    @PutMapping("/{missionId}/plan")
    ResponseEntity<PersonalApiDtos.MissionSnapshot> replacePlan(
            @PathVariable String missionId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PersonalApiDtos.ReplaceMissionPlan request) {
        if (request == null) throw new MissionException("MISSION_PLAN_INVALID", "Mission plan request is required");
        if (Boolean.TRUE.equals(request.regenerate())) {
            var body = mapper.mission(missions.regeneratePlan(new MissionApplicationService.RegenerateMissionPlan(
                    key(idempotencyKey), ownerScope(), missionId, revision(ifMatch))));
            return ResponseEntity.ok().eTag(Long.toString(body.version())).body(body);
        }
        if (request.plan() == null || request.plan().tasks() == null) {
            throw new MissionException("MISSION_PLAN_INVALID", "A complete Mission plan is required");
        }
        List<MissionTask> tasks =
                request.plan().tasks().stream().map(this::task).toList();
        var body = mapper.mission(missions.replacePlan(new MissionApplicationService.ReplaceMissionPlan(
                key(idempotencyKey), ownerScope(), missionId, revision(ifMatch), tasks)));
        return ResponseEntity.ok().eTag(Long.toString(body.version())).body(body);
    }

    @PostMapping("/{missionId}/confirm")
    ResponseEntity<PersonalApiDtos.MissionSnapshot> confirm(
            @PathVariable String missionId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return changed(missions.confirm(change(missionId, ifMatch, idempotencyKey)));
    }

    @PostMapping("/{missionId}/cancel")
    ResponseEntity<PersonalApiDtos.MissionSnapshot> cancel(
            @PathVariable String missionId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody(required = false) PersonalApiDtos.CancelMission ignored) {
        return changed(missions.cancel(change(missionId, ifMatch, idempotencyKey)));
    }

    @PostMapping("/{missionId}/tasks/{taskId}/retry")
    ResponseEntity<PersonalApiDtos.MissionSnapshot> retry(
            @PathVariable String missionId,
            @PathVariable String taskId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return changed(missions.retry(new MissionApplicationService.RetryMissionTask(
                key(idempotencyKey),
                ownerScope(),
                text(missionId, "missionId", 256),
                text(taskId, "taskId", 64),
                revision(ifMatch))));
    }

    private ResponseEntity<PersonalApiDtos.MissionSnapshot> response(String missionId) {
        return missions.find(text(missionId, "missionId", 256), ownerScope())
                .map(mapper::mission)
                .map(value ->
                        ResponseEntity.ok().eTag(Long.toString(value.version())).body(value))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private ResponseEntity<PersonalApiDtos.MissionSnapshot> changed(
            io.haifa.agent.personalassistant.application.mission.MissionSnapshot snapshot) {
        var body = mapper.mission(snapshot);
        return ResponseEntity.ok().eTag(Long.toString(body.version())).body(body);
    }

    private MissionApplicationService.ChangeMission change(String missionId, String ifMatch, String idempotencyKey) {
        return new MissionApplicationService.ChangeMission(
                key(idempotencyKey), ownerScope(), text(missionId, "missionId", 256), revision(ifMatch));
    }

    private MissionConstraints constraints(PersonalApiDtos.MissionConstraints requested) {
        int productTasks = properties.mission().maxTasks();
        int productDepth = properties.mission().maxDependencyDepth();
        Instant maximumDeadline =
                clock.instant().plusMillis(properties.mission().maxWallClockMillis());
        if (requested == null) {
            return new MissionConstraints(productTasks, productDepth, Optional.of(maximumDeadline));
        }
        int maxTasks = requested.maxTasks() == null ? productTasks : requested.maxTasks();
        int maxDepth = requested.maxDependencyDepth() == null ? productDepth : requested.maxDependencyDepth();
        if (maxTasks > productTasks || maxDepth > productDepth) {
            throw new MissionException("MISSION_LIMIT_EXCEEDED", "Mission constraints may only narrow product limits");
        }
        Instant deadline = requested.deadlineAt();
        if (deadline != null && !deadline.isAfter(clock.instant())) {
            throw new MissionException("MISSION_DEADLINE_INVALID", "Mission deadline must be in the future");
        }
        if (deadline != null && deadline.isAfter(maximumDeadline)) {
            throw new MissionException(
                    "MISSION_LIMIT_EXCEEDED", "Mission deadline may only narrow the product wall-clock limit");
        }
        return new MissionConstraints(maxTasks, maxDepth, Optional.of(deadline == null ? maximumDeadline : deadline));
    }

    private static MissionMode parseMode(String value) {
        try {
            return MissionMode.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new MissionException("MISSION_MODE_INVALID", "Mission mode is unsupported", exception);
        }
    }

    private static Optional<ResearchBrief> researchBrief(MissionMode mode, PersonalApiDtos.ResearchBrief value) {
        if (mode == MissionMode.STANDARD) {
            if (value != null) {
                throw new MissionException("MISSION_RESEARCH_BRIEF_FORBIDDEN", "Standard Mission cannot carry a brief");
            }
            return Optional.empty();
        }
        if (value == null) {
            throw new MissionException("MISSION_RESEARCH_BRIEF_REQUIRED", "Deep Research requires a brief");
        }
        return Optional.of(new ResearchBrief(
                value.question(),
                value.scope(),
                value.timeRange(),
                value.region(),
                value.audience(),
                value.sourcePreferences() == null ? List.of() : value.sourcePreferences(),
                value.exclusions() == null ? List.of() : value.exclusions(),
                value.deliveryFormat()));
    }

    private MissionTask task(PersonalApiDtos.MissionTask value) {
        if (value == null) throw new MissionException("MISSION_PLAN_INVALID", "Mission task is required");
        if (value.state() != null && !value.state().isBlank() && !"PLANNED".equalsIgnoreCase(value.state())) {
            throw new MissionException("MISSION_PLAN_INVALID", "Replacement tasks must be planned");
        }
        return new MissionTask(
                value.taskId(),
                value.ordinal() == null ? 0 : value.ordinal(),
                value.title(),
                value.objective(),
                value.acceptanceCriteria() == null ? List.of() : value.acceptanceCriteria(),
                value.dependsOn() == null ? List.of() : value.dependsOn(),
                value.taskType(),
                Set.copyOf(value.requiredSkillIds() == null ? List.of() : value.requiredSkillIds()),
                value.resultSchemaId(),
                value.resultSchemaVersion(),
                MissionTaskState.PLANNED);
    }

    private String ownerScope() {
        return properties.caller().tenant() + "/" + properties.caller().principal();
    }

    private static Optional<MissionListCursor> decodeCursor(Optional<String> cursor) {
        if (cursor.isEmpty() || cursor.orElseThrow().isBlank()) return Optional.empty();
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor.orElseThrow()), StandardCharsets.UTF_8);
            String[] fields = decoded.split("\\n", 3);
            if (fields.length != 3 || !"v1".equals(fields[0])) throw new IllegalArgumentException();
            return Optional.of(new MissionListCursor(Instant.ofEpochMilli(Long.parseLong(fields[1])), fields[2]));
        } catch (RuntimeException exception) {
            throw new MissionException("MISSION_CURSOR_INVALID", "Mission cursor is invalid", exception);
        }
    }

    private static String encodeCursor(io.haifa.agent.personalassistant.application.mission.MissionSnapshot snapshot) {
        String value = "v1\n" + snapshot.updatedAt().toEpochMilli() + "\n" + snapshot.missionId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static long revision(String value) {
        if (value == null || value.isBlank()) {
            throw new MissionException("MISSION_PRECONDITION_REQUIRED", "If-Match is required");
        }
        String normalized = value.trim().replace("W/", "").replace("\"", "");
        try {
            long result = Long.parseLong(normalized);
            if (result < 0) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException exception) {
            throw new MissionException("MISSION_PRECONDITION_INVALID", "If-Match must contain a revision", exception);
        }
    }

    private static String key(String value) {
        String normalized = text(value, "Idempotency-Key", 128);
        if (!normalized.chars().allMatch(character -> character >= 0x21 && character <= 0x7e)) {
            throw new MissionException("MISSION_IDEMPOTENCY_INVALID", "Idempotency-Key must be printable ASCII");
        }
        return normalized;
    }

    private static String text(String value, String field, int maximum) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new MissionException("MISSION_REQUEST_INVALID", field + " is invalid");
        }
        return normalized;
    }
}

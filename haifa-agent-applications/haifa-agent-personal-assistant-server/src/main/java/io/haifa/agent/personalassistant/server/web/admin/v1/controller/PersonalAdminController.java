package io.haifa.agent.personalassistant.server.web.admin.v1.controller;

import io.haifa.agent.personalassistant.application.PersonalAssistantApplication;
import io.haifa.agent.personalassistant.application.PersonalCapabilityRegistry;
import io.haifa.agent.personalassistant.server.admin.PersonalAdminQueryService;
import io.haifa.agent.personalassistant.server.mission.MissionOperationsService;
import io.haifa.agent.personalassistant.server.web.admin.v1.dto.PersonalAdminDtos;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Separate loopback-only Admin surface. It is deliberately absent from the Personal Assistant API. */
@RestController
@RequestMapping("/v1/admin")
public final class PersonalAdminController {
    private final PersonalAdminQueryService queries;
    private final PersonalAssistantApplication application;
    private final MissionOperationsService missionOperations;

    public PersonalAdminController(
            PersonalAdminQueryService queries,
            PersonalAssistantApplication application,
            MissionOperationsService missionOperations) {
        this.queries = queries;
        this.application = application;
        this.missionOperations = missionOperations;
    }

    @GetMapping({"", "/"})
    PersonalAdminDtos.Index index() {
        return new PersonalAdminDtos.Index(
                "Haifa Personal Assistant Admin",
                "v1",
                true,
                "This diagnostic API exposes safe execution metadata and hides sensitive content.");
    }

    @GetMapping("/sessions")
    List<PersonalAdminDtos.Session> sessions(@RequestParam(defaultValue = "100") int limit) {
        return queries.sessions(bounded(limit)).stream()
                .map(PersonalAdminController::session)
                .toList();
    }

    @GetMapping("/sessions/{sessionId}/runs")
    List<PersonalAdminDtos.Run> runs(@PathVariable String sessionId, @RequestParam(defaultValue = "100") int limit) {
        return queries.runs(sessionId, bounded(limit)).stream()
                .map(PersonalAdminController::run)
                .toList();
    }

    @GetMapping("/sessions/{sessionId}/runs/{runId}/tree")
    ResponseEntity<PersonalAdminDtos.Trace> tree(@PathVariable String sessionId, @PathVariable String runId) {
        return queries.trace(sessionId, runId)
                .map(PersonalAdminController::trace)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/capabilities")
    PersonalAdminDtos.Capabilities capabilities() {
        PersonalCapabilityRegistry value = application.capabilities();
        return new PersonalAdminDtos.Capabilities(
                value.toolCatalogDigest(),
                value.skillCatalogDigest(),
                value.skillResolutionPolicy(),
                value.registrations().stream()
                        .map(PersonalAdminController::capability)
                        .toList());
    }

    @GetMapping("/missions/operations")
    PersonalAdminDtos.MissionOperations missionOperations() {
        var value = missionOperations.snapshot();
        var dispatcher = value.dispatcher();
        var store = value.store();
        var capacity = value.capacity();
        return new PersonalAdminDtos.MissionOperations(
                dispatcher.status(),
                dispatcher.ready(),
                dispatcher.maintenancePaused(),
                dispatcher.recoveryCount(),
                dispatcher.lastReconcileLatencyMillis(),
                dispatcher.lastReconcileAtMillis(),
                value.schemaVersion(),
                store.missionStates(),
                store.activeMissions(),
                store.activeAttempts(),
                store.unsettledAttempts(),
                store.pendingOutbox(),
                store.oldestOutboxAgeMillis(),
                store.blockedTasks(),
                store.outcomeUnknownAttempts(),
                store.budgetExhaustedTasks(),
                store.modelTokens(),
                store.modelCalls(),
                store.toolCalls(),
                store.duplicatePrevented(),
                capacity.databaseBytes(),
                capacity.artifactBytes(),
                capacity.artifactFiles(),
                capacity.databaseWarning(),
                capacity.artifactWarning(),
                capacity.blockerCode(),
                "No automatic Event, Session, or Artifact GC; use documented quiescent maintenance.");
    }

    @GetMapping("/missions/upgrade-readiness")
    PersonalAdminDtos.UpgradeReadiness upgradeReadiness() {
        var value = missionOperations.upgradeReadiness();
        return new PersonalAdminDtos.UpgradeReadiness(
                value.ready(),
                value.blockerCodes(),
                value.schemaVersion(),
                value.ready() ? "CREATE_BACKUP_THEN_UPGRADE" : "WAIT_OR_CANCEL_ACTIVE_MISSIONS");
    }

    private static PersonalAdminDtos.Capability capability(PersonalCapabilityRegistry.CapabilityRegistration value) {
        return new PersonalAdminDtos.Capability(
                value.id(),
                value.kind(),
                value.name(),
                value.displayName(),
                value.description(),
                value.status(),
                value.source(),
                value.tags(),
                value.attributes().stream()
                        .map(attribute -> new PersonalAdminDtos.CapabilityAttribute(
                                attribute.label(), attribute.value(), attribute.tone()))
                        .toList(),
                value.details());
    }

    private static PersonalAdminDtos.Session session(PersonalAdminQueryService.SessionSummary value) {
        return new PersonalAdminDtos.Session(
                value.id(),
                value.status(),
                value.createdAt(),
                value.updatedAt(),
                value.runCount(),
                value.latestRunStatus());
    }

    private static PersonalAdminDtos.Run run(PersonalAdminQueryService.RunSummary value) {
        return new PersonalAdminDtos.Run(
                value.id(),
                value.sessionId(),
                value.status(),
                value.objective(),
                value.createdAt(),
                value.updatedAt(),
                value.completedAt(),
                value.errorCode());
    }

    private static PersonalAdminDtos.Trace trace(PersonalAdminQueryService.Trace value) {
        return new PersonalAdminDtos.Trace(
                value.sessionId(),
                value.runId(),
                node(value.root()),
                value.nodes().stream().map(PersonalAdminController::node).toList(),
                value.failureNodeId());
    }

    private static PersonalAdminDtos.Node node(PersonalAdminQueryService.Node value) {
        return new PersonalAdminDtos.Node(
                value.id(),
                value.parentId(),
                value.kind(),
                value.label(),
                value.status(),
                value.startedAt(),
                value.completedAt(),
                value.durationMillis(),
                value.sequence(),
                value.summary(),
                value.details());
    }

    private static int bounded(int value) {
        if (value < 1 || value > 200) throw new IllegalArgumentException("limit must be between 1 and 200");
        return value;
    }
}

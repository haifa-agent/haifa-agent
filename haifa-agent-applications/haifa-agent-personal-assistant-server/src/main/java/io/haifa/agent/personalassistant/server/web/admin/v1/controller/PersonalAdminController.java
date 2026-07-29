package io.haifa.agent.personalassistant.server.web.admin.v1.controller;

import io.haifa.agent.personalassistant.server.admin.PersonalAdminQueryService;
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

    public PersonalAdminController(PersonalAdminQueryService queries) {
        this.queries = queries;
    }

    @GetMapping({"", "/"})
    PersonalAdminDtos.Index index() {
        return new PersonalAdminDtos.Index(
                "Haifa Personal Assistant Admin",
                "v1",
                true,
                "This local diagnostic API contains complete prompts, tool arguments, results, and errors.");
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

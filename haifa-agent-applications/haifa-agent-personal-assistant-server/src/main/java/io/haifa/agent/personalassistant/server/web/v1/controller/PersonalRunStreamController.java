package io.haifa.agent.personalassistant.server.web.v1.controller;

import io.haifa.agent.personalassistant.server.web.v1.dto.PersonalApiDtos;
import io.haifa.agent.personalassistant.server.web.v1.sse.PersonalRunStreamService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/v1/runs")
public final class PersonalRunStreamController {
    private final PersonalRunStreamService streams;

    public PersonalRunStreamController(PersonalRunStreamService streams) {
        this.streams = streams;
    }

    @GetMapping(path = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<PersonalApiDtos.StreamEvent>> stream(
            @PathVariable String runId, @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        return streams.open(runId, java.util.Optional.ofNullable(lastEventId));
    }
}

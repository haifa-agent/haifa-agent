package io.haifa.agent.personalassistant.server.observability;

import io.haifa.agent.personalassistant.application.PersonalAssistantApplication;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Logs the safe, projected lifecycle of Personal Assistant runs.
 *
 * <p>The projection deliberately omits answer deltas, prompts, Tool arguments, command/script
 * content, result bodies, and interaction text.
 */
@Service
public final class PersonalRunLoggingService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PersonalRunLoggingService.class);
    private static final Set<String> TERMINAL_RUN_STATUSES = Set.of("COMPLETED", "FAILED", "CANCELLED", "TIMEOUT");

    private final PersonalAssistantApplication application;
    private final Map<String, PersonalAssistantApplication.StreamSubscription> subscriptions =
            new ConcurrentHashMap<>();

    public PersonalRunLoggingService(PersonalAssistantApplication application) {
        this.application = application;
    }

    public void observe(String conversationId, String runId, String trigger) {
        LOGGER.info(
                "event=run.observation.started conversationId={} runId={} trigger={}", conversationId, runId, trigger);
        try {
            PersonalAssistantApplication.StreamSubscription subscription =
                    application.subscribe(runId, event -> log(conversationId, event));
            PersonalAssistantApplication.StreamSubscription previous = subscriptions.put(runId, subscription);
            if (previous != null) previous.close();
            application.run(runId).ifPresent(run -> {
                LOGGER.info(
                        "event=run.snapshot conversationId={} runId={} status={} version={} modelCalls={} toolCalls={}",
                        conversationId,
                        runId,
                        run.status(),
                        run.version(),
                        run.usage().modelCalls(),
                        run.usage().toolCalls());
                if (TERMINAL_RUN_STATUSES.contains(run.status())) close(runId);
            });
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "event=run.observation.failed conversationId={} runId={} failureType={}",
                    conversationId,
                    runId,
                    failure.getClass().getName());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    void observeRecoveredRuns() {
        try {
            application.conversations(Optional.empty(), Set.of("ACTIVE"), 100).stream()
                    .filter(conversation -> conversation.activeRunId().isPresent())
                    .forEach(conversation -> observe(
                            conversation.id(), conversation.activeRunId().orElseThrow(), "application-recovered"));
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "event=run.recovery-observation.failed failureType={}",
                    failure.getClass().getName());
        }
    }

    private void log(String conversationId, PersonalAssistantApplication.StreamEvent event) {
        switch (event.type()) {
            case "run.status" -> {
                LOGGER.info(
                        "event=run.status conversationId={} runId={} status={} sequence={}",
                        conversationId,
                        event.runId(),
                        event.value(),
                        event.sequence());
                if (TERMINAL_RUN_STATUSES.contains(event.value())) close(event.runId());
            }
            case "interaction.status" ->
                LOGGER.info(
                        "event=interaction.status conversationId={} runId={} status={} sequence={}",
                        conversationId,
                        event.runId(),
                        event.value(),
                        event.sequence());
            case "activity.committed" ->
                event.activity()
                        .ifPresent(activity -> LOGGER.info(
                                "event=activity.committed conversationId={} runId={} activityId={} kind={} capability={} status={} sequence={}",
                                conversationId,
                                event.runId(),
                                activity.activityId(),
                                activity.kind(),
                                activity.displayName(),
                                activity.status(),
                                event.sequence()));
            case "answer.delta" ->
                LOGGER.debug(
                        "event=answer.delta conversationId={} runId={} characterCount={} sequence={}",
                        conversationId,
                        event.runId(),
                        event.value().length(),
                        event.sequence());
            default ->
                LOGGER.debug(
                        "event=run.event conversationId={} runId={} type={} sequence={}",
                        conversationId,
                        event.runId(),
                        event.type(),
                        event.sequence());
        }
    }

    private void close(String runId) {
        PersonalAssistantApplication.StreamSubscription subscription = subscriptions.remove(runId);
        if (subscription != null) subscription.close();
    }

    @PreDestroy
    void close() {
        subscriptions.values().forEach(PersonalAssistantApplication.StreamSubscription::close);
        subscriptions.clear();
    }
}

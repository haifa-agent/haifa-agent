package io.haifa.coding.host;

import static org.assertj.core.api.Assertions.*;

import io.haifa.coding.api.*;
import io.haifa.coding.api.CodingProtocol.*;
import io.haifa.coding.client.CodingClients;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodingHostProductTest {
    @TempDir
    Path directory;

    @Test
    void externalStyleNativeAndEmbeddedBindingsShareFactsButClosingEitherDoesNotOwnTheHost() throws Exception {
        try (var fixture = new CodingHostFixture(directory);
                var host = fixture.start(Optional.empty());
                var embedded = host.embeddedClient();
                var remote = CodingClients.connect(host.endpoint())) {
            assertThat(remote.connection().displayName()).isEqualTo("Third-party SDK product");
            assertThat(remote.connection().capabilities()).contains("native-tool-details-v1");
            assertThat(embedded.connection().capabilities()).contains("native-tool-details-v1");
            assertThat(remote.connection().projectId())
                    .isEqualTo(embedded.connection().projectId());
            var receipt = remote.execute(create("create-once", "create-key", "first turn"));
            assertThat(receipt.receipt().disposition()).isEqualTo("accepted");
            String session = receipt.session().sessionId();
            try (var observation = remote.observe(session)) {
                Snapshot current = observation.initial();
                boolean completed = false;
                for (int i = 0; i < 32; i++) {
                    if (current.entries().stream().anyMatch(e -> e.text().equals("sdk-safe-answer"))) {
                        completed = true;
                        break;
                    }
                    var next = observation.poll(Duration.ofSeconds(5));
                    assertThat(next).isInstanceOf(Update.class);
                    current = CodingPresentationReducer.apply(current, (Update) next);
                }
                assertThat(completed).isTrue();
                assertThat(current.scope().source()).isEqualTo("native");
            }
            assertThat(embedded.history(session, 32, null).entries())
                    .anyMatch(e ->
                            e.text().equals("sdk-safe-answer") && e.status().equals("FINAL"));
            var models = remote.query(query("models", null, null));
            assertThat(models.models()).hasSize(1);
            assertThat(models.models().getFirst().responseMode().kind()).isNotBlank();
            var sessions = remote.query(query("sessions", null, null)).sessions();
            assertThat(sessions).anyMatch(value -> value.sessionId().equals(session));
            assertThat(remote.query(new Query(
                                    "sessions", new CommandTarget(null, null, null, null, null), "", 10, null))
                            .sessions())
                    .containsExactlyElementsOf(sessions);
            assertThatThrownBy(() -> CodingHosts.start(fixture.options(Optional.empty()), fixture.environment))
                    .isInstanceOfSatisfying(CodingClientException.class, e -> assertThat(e.code())
                            .isEqualTo("DATABASE_OWNED_BY_ANOTHER_PROCESS"));
            remote.close();
            assertThat(embedded.query(query("sessions", null, null)).sessions()).isNotEmpty();
            assertThat(host.browserUri()).isEmpty();
        }
    }

    @Test
    void approvedInteractionHasExactTargetsAndClientConsentIsNotShared() throws Exception {
        try (var fixture = new CodingHostFixture(directory)) {
            fixture.answer.set(request -> fixture.modelCalls.get() == 1
                    ? CodingHostFixture.tool(
                            "file-call",
                            "file_create",
                            Map.of(
                                    "path",
                                    fixture.workspace.resolve("approved.txt").toString(),
                                    "content",
                                    "approved body"))
                    : CodingHostFixture.response("after approval"));
            try (var host = fixture.start(Optional.empty());
                    var first = CodingClients.connect(host.endpoint());
                    var second = CodingClients.connect(host.endpoint())) {
                var created = first.execute(create("approval-create", "approval-key", "write a file"));
                String session = created.session().sessionId();
                try (var observation = first.observe(session)) {
                    Snapshot current = observation.initial();
                    Entry pending = null;
                    for (int i = 0; i < 32; i++) {
                        pending = current.entries().stream()
                                .filter(e -> e.interaction() != null)
                                .findFirst()
                                .orElse(null);
                        if (pending != null) break;
                        var next = observation.poll(Duration.ofSeconds(5));
                        assertThat(next).isInstanceOf(Update.class);
                        current = CodingPresentationReducer.apply(current, (Update) next);
                    }
                    assertThat(pending).isNotNull();
                    var interaction = pending.interaction();
                    var target = new CommandTarget(
                            null, session, pending.runId(), interaction.id(), interaction.revision() + 1);
                    var stale = new Command(
                            "command",
                            "allowForSession",
                            "stale",
                            "stale-key",
                            target,
                            null,
                            "approve",
                            null,
                            List.of());
                    assertThat(first.execute(stale).receipt().disposition()).isEqualTo("rejected");
                    var exact = new Command(
                            "command",
                            "allowForSession",
                            "exact",
                            "exact-key",
                            new CommandTarget(null, session, pending.runId(), interaction.id(), interaction.revision()),
                            null,
                            "approve",
                            null,
                            List.of());
                    assertThat(first.execute(exact).receipt().disposition()).isEqualTo("accepted");
                    assertThat(first.open(session).consent().state()).isEqualTo("ACTIVE");
                    assertThat(second.open(session).consent().state()).isEqualTo("ASK");
                }
            }
        }
    }

    @Test
    void explicitDurablePersistenceAndFailedStartDoNotLeaveAnOwner() throws Exception {
        try (var fixture = new CodingHostFixture(directory)) {
            String trusted = Files.readString(fixture.config);
            Files.writeString(fixture.config, "persistence:\n  mode: MEMORY\n");
            assertThatThrownBy(() -> fixture.start(Optional.empty()))
                    .hasMessageContaining("HOST_DURABLE_PERSISTENCE_REQUIRED");
            Files.writeString(fixture.config, trusted);
            var writableAssets = Files.createDirectories(fixture.workspace.resolve("mutable-assets"));
            Files.writeString(writableAssets.resolve("index.html"), "workspace-controlled");
            assertThatThrownBy(() -> fixture.start(Optional.of(new BrowserOptions(writableAssets, "index.html"))))
                    .hasMessage("ASSET_ROOT_EXPOSES_OWNER_DATA");
            assertThatThrownBy(() -> fixture.start(Optional.of(new BrowserOptions(fixture.assets, "missing.html"))))
                    .isInstanceOf(RuntimeException.class);
            try (var host = fixture.start(Optional.empty());
                    var client = host.embeddedClient()) {
                assertThat(client.query(query("sessions", null, null)).sessions())
                        .isEmpty();
            }
        }
    }

    static Command create(String request, String key, String text) {
        return new Command(
                "command",
                "create",
                request,
                key,
                new CommandTarget(null, null, null, null, null),
                text,
                null,
                null,
                List.of());
    }

    static Query query(String operation, String session, String run) {
        return new Query(operation, new CommandTarget(null, session, run, null, null), null, 32, null);
    }
}

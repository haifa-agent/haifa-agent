package io.haifa.agent.application.coding.terminal.application;

import static io.haifa.agent.application.coding.terminal.application.CodingTerminalTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.coding.terminal.event.TerminalEventPump;
import io.haifa.agent.application.coding.terminal.event.TerminalInput;
import io.haifa.agent.application.coding.terminal.state.TerminalUiReducer;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import io.haifa.agent.application.project.product.coding.CodingQueuedMessage;
import io.haifa.agent.application.project.product.coding.CodingSessionHistoryItem;
import io.haifa.agent.application.project.product.coding.CodingSessionHistoryPage;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationClient;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationProgressView;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationView;
import io.haifa.agent.application.project.product.coding.client.CodingBrowserLoginView;
import io.haifa.agent.application.project.product.coding.client.CodingDeviceLoginView;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class CodingTerminalLifecycleTest {
    @Test
    void firstStartupOffersOnlyConfiguredAuthenticationMethodsWhenTheSelectedCredentialIsMissing() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        CodingAuthenticationClient authentication = new CodingAuthenticationClient() {
            @Override
            public boolean connectionRequired() {
                return true;
            }

            @Override
            public List<CodingAuthenticationView> connections() {
                return List.of();
            }

            @Override
            public CodingAuthenticationView loginCodexBrowser() {
                throw new UnsupportedOperationException();
            }

            @Override
            public CodingAuthenticationView saveApiKey(String providerId, char[] apiKey) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean logout(String connectionId) {
                return false;
            }
        };
        var controller = new CodingTerminalController(
                PROJECT_ID,
                client,
                authentication,
                new TerminalEventPump(32),
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 40),
                Runnable::run);

        controller.start(CodingTerminalStartup.empty());

        assertThat(controller.state().selector()).get().satisfies(selector -> {
            assertThat(selector.kind()).isEqualTo("auth-login");
            assertThat(selector.title()).isEqualTo("Connect a model to get started");
            assertThat(selector.options()).containsExactly("Provider API key (secure input)");
        });
    }

    @Test
    void enabledAntigravityLoginAppearsInOnboardingAndStartsItsBrowserFlow() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
        CodingAuthenticationClient authentication = new CodingAuthenticationClient() {
            @Override
            public boolean connectionRequired() {
                return true;
            }

            @Override
            public boolean antigravityConnectionSupported() {
                return true;
            }

            @Override
            public List<CodingAuthenticationView> connections() {
                return List.of();
            }

            @Override
            public CodingAuthenticationView loginCodexBrowser() {
                throw new UnsupportedOperationException();
            }

            @Override
            public CodingAuthenticationView loginAntigravityBrowser(
                    java.util.function.Consumer<CodingBrowserLoginView> instructions,
                    java.util.function.Consumer<CodingAuthenticationProgressView> progress) {
                started.set(true);
                progress.accept(
                        new CodingAuthenticationProgressView(CodingAuthenticationProgressView.Phase.WAITING_USER));
                instructions.accept(new CodingBrowserLoginView(
                        URI.create("https://accounts.google.com/o/oauth2/v2/auth?state=test"), 1_000));
                return new CodingAuthenticationView(
                        "model-auth://google-antigravity/default",
                        "google-antigravity",
                        CodingAuthenticationView.Method.ANTIGRAVITY_SUBSCRIPTION,
                        CodingAuthenticationView.Status.AUTHENTICATED,
                        "Google account",
                        Optional.empty(),
                        OptionalLong.empty(),
                        true);
            }

            @Override
            public CodingAuthenticationView saveApiKey(String providerId, char[] apiKey) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean logout(String connectionId) {
                return false;
            }
        };
        var controller = new CodingTerminalController(
                PROJECT_ID,
                client,
                authentication,
                new TerminalEventPump(32),
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 40),
                Runnable::run);

        controller.start(CodingTerminalStartup.empty());

        assertThat(controller.state().selector().orElseThrow().options())
                .containsExactly("Antigravity subscription", "Provider API key (secure input)");
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));
        controller.drainEvents();

        assertThat(started).isTrue();
        assertThat(controller.state().transcript()).anySatisfy(item -> {
            assertThat(item.title()).isEqualTo("Antigravity connection");
            assertThat(item.status()).isEqualTo("CONNECTED");
        });
        assertThat(controller.state().status()).isEqualTo("Connected to Antigravity (UNOFFICIAL_LOCAL_COMPAT)");
    }

    @Test
    void deviceLoginPrintsTheBrowserUrlAndUserCodeAsPersistentTranscriptContent() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        CodingAuthenticationClient authentication = new CodingAuthenticationClient() {
            @Override
            public List<CodingAuthenticationView> connections() {
                return List.of();
            }

            @Override
            public CodingAuthenticationView loginCodexBrowser() {
                throw new UnsupportedOperationException();
            }

            @Override
            public CodingAuthenticationView loginCodexDevice(
                    java.util.function.Consumer<CodingDeviceLoginView> instructions,
                    java.util.function.Consumer<CodingAuthenticationProgressView> progress) {
                progress.accept(
                        new CodingAuthenticationProgressView(CodingAuthenticationProgressView.Phase.WAITING_USER));
                instructions.accept(new CodingDeviceLoginView(
                        URI.create("https://auth.openai.com/codex/device"), "ABCD-1234", 1_000));
                progress.accept(
                        new CodingAuthenticationProgressView(CodingAuthenticationProgressView.Phase.EXCHANGING));
                progress.accept(new CodingAuthenticationProgressView(CodingAuthenticationProgressView.Phase.STORING));
                return authenticatedCodexConnection();
            }

            @Override
            public CodingAuthenticationView saveApiKey(String providerId, char[] apiKey) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean logout(String connectionId) {
                return false;
            }
        };
        var pump = new TerminalEventPump(32);
        var controller = new CodingTerminalController(
                PROJECT_ID,
                client,
                authentication,
                pump,
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 40),
                Runnable::run);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/login codex device"));
        controller.drainEvents();

        assertThat(controller.state().transcript()).anySatisfy(item -> {
            assertThat(item.title()).isEqualTo("ChatGPT device login");
            assertThat(item.body())
                    .contains("Browser URL: https://auth.openai.com/codex/device", "Device code: ABCD-1234");
            assertThat(item.status()).isEqualTo("WAITING");
        });
        assertThat(controller.state().transcript()).anySatisfy(item -> {
            assertThat(item.title()).isEqualTo("ChatGPT Codex connection");
            assertThat(item.body()).contains("Credentials were saved to ~/.haifa-agent/auth.json");
            assertThat(item.status()).isEqualTo("CONNECTED");
        });
        assertThat(controller.state().status()).isEqualTo("Connected to ChatGPT Codex (UNOFFICIAL_LOCAL_COMPAT)");
    }

    @Test
    void browserLoginPrintsTheAuthorizationUrlWhenAutomaticLaunchFallsBack() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        CodingAuthenticationClient authentication = new CodingAuthenticationClient() {
            @Override
            public List<CodingAuthenticationView> connections() {
                return List.of();
            }

            @Override
            public CodingAuthenticationView loginCodexBrowser() {
                throw new UnsupportedOperationException();
            }

            @Override
            public CodingAuthenticationView loginCodexBrowser(
                    java.util.function.Consumer<CodingBrowserLoginView> instructions,
                    java.util.function.Consumer<CodingAuthenticationProgressView> progress) {
                progress.accept(
                        new CodingAuthenticationProgressView(CodingAuthenticationProgressView.Phase.WAITING_USER));
                instructions.accept(new CodingBrowserLoginView(
                        URI.create("https://auth.openai.com/oauth/authorize?client_id=test&state=state"), 1_000));
                progress.accept(
                        new CodingAuthenticationProgressView(CodingAuthenticationProgressView.Phase.EXCHANGING));
                progress.accept(new CodingAuthenticationProgressView(CodingAuthenticationProgressView.Phase.STORING));
                return authenticatedCodexConnection();
            }

            @Override
            public CodingAuthenticationView saveApiKey(String providerId, char[] apiKey) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean logout(String connectionId) {
                return false;
            }
        };
        var pump = new TerminalEventPump(32);
        var controller = new CodingTerminalController(
                PROJECT_ID,
                client,
                authentication,
                pump,
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 40),
                Runnable::run);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/login codex browser"));
        controller.drainEvents();

        assertThat(controller.state().transcript()).anySatisfy(item -> {
            assertThat(item.title()).isEqualTo("ChatGPT browser login");
            assertThat(item.body())
                    .contains(
                            "A browser sign-in was requested.",
                            "If it did not open, use this URL: "
                                    + "https://auth.openai.com/oauth/authorize?client_id=test&state=state");
        });
        assertThat(controller.state().transcript()).anySatisfy(item -> {
            assertThat(item.title()).isEqualTo("ChatGPT Codex connection");
            assertThat(item.body())
                    .contains("Credentials were saved to ~/.haifa-agent/auth.json", "UNOFFICIAL_LOCAL_COMPAT");
            assertThat(item.status()).isEqualTo("CONNECTED");
        });
        assertThat(controller.state().toString()).doesNotContain("client_id=test", "state=state");
        assertThat(controller.state().status()).isEqualTo("Connected to ChatGPT Codex (UNOFFICIAL_LOCAL_COMPAT)");
    }

    @Test
    void browserLoginFailureRemainsVisibleWithTheFailedStageAndAction() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        CodingAuthenticationClient authentication = new CodingAuthenticationClient() {
            @Override
            public List<CodingAuthenticationView> connections() {
                return List.of();
            }

            @Override
            public CodingAuthenticationView loginCodexBrowser() {
                throw new UnsupportedOperationException();
            }

            @Override
            public CodingAuthenticationView loginCodexBrowser(
                    java.util.function.Consumer<CodingBrowserLoginView> instructions,
                    java.util.function.Consumer<CodingAuthenticationProgressView> progress) {
                progress.accept(
                        new CodingAuthenticationProgressView(CodingAuthenticationProgressView.Phase.EXCHANGING));
                throw new IllegalStateException("AUTH_REAUTH_REQUIRED");
            }

            @Override
            public CodingAuthenticationView saveApiKey(String providerId, char[] apiKey) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean logout(String connectionId) {
                return false;
            }
        };
        var controller = new CodingTerminalController(
                PROJECT_ID,
                client,
                authentication,
                new TerminalEventPump(32),
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 40),
                Runnable::run);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/login codex browser"));
        controller.drainEvents();

        assertThat(controller.state().transcript()).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("ChatGPT Codex connection failed");
            assertThat(item.body())
                    .contains(
                            "Last stage: Authorization received. Exchanging it for Codex credentials.",
                            "Reason: AUTH_REAUTH_REQUIRED",
                            "verify the OAuth Client ID and redirect registration");
            assertThat(item.status()).isEqualTo("FAILED");
        });
        assertThat(controller.state().recoverableError()).contains("AUTH_REAUTH_REQUIRED");
    }

    @Test
    void resumeSelectorOpensTheSelectedRealSession() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.summaries = List.of(client.view.summary());
        var controller = controller(client);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/resume"));
        assertThat(controller.state().selector()).isPresent();
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));

        assertThat(client.opened).containsExactly(SESSION_ID);
        assertThat(controller.state().session()).contains(client.view);
        assertThat(controller.state().selector()).isEmpty();
    }

    @Test
    void startupResumeSelectorUsesTheExistingBoundedSessionPicker() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.summaries = List.of(client.view.summary());
        var controller = controller(client);

        controller.start(CodingTerminalStartup.selectSession());

        assertThat(client.listLimit).isEqualTo(50);
        assertThat(controller.state().selector()).isPresent();
        assertThat(controller.state().selector().orElseThrow().options().getFirst())
                .contains("session", "ACTIVE", "session-1");
    }

    @Test
    void startupLastOpensTheMostRecentSessionBeforeSubmittingThePrompt() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.summaries = List.of(client.view.summary());
        var controller = controller(client);

        controller.start(CodingTerminalStartup.lastSession(Optional.of("continue the work")));

        assertThat(client.listLimit).isEqualTo(1);
        assertThat(client.opened).containsExactly(SESSION_ID);
        assertThat(client.submittedMessages).containsExactly("continue the work");
        assertThat(controller.state().session()).isPresent();
    }

    @Test
    void automaticStartupLoadsTheLastSessionOnTheInteractiveEffectQueue() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.summaries = List.of(client.view.summary());
        ArrayDeque<Runnable> effects = new ArrayDeque<>();
        var controller = new CodingTerminalController(
                PROJECT_ID,
                client,
                new TerminalEventPump(32),
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 40),
                effects::addLast);

        controller.start(CodingTerminalStartup.autoLast());
        controller.accept(input(TerminalInput.Kind.EDITOR_CHANGED, "draft while loading"));

        assertThat(client.listCalls).isZero();
        assertThat(client.opened).isEmpty();
        assertThat(controller.state().session()).isEmpty();
        assertThat(controller.state().editorBuffer()).isEqualTo("draft while loading");
        assertThat(effects).hasSize(1);

        effects.removeFirst().run();
        assertThat(controller.state().session()).isEmpty();

        controller.drainEvents();

        assertThat(client.listCalls).isEqualTo(1);
        assertThat(client.listLimit).isEqualTo(1);
        assertThat(client.opened).containsExactly(SESSION_ID);
        assertThat(controller.state().session()).contains(client.view);
        assertThat(controller.state().editorBuffer()).isEqualTo("draft while loading");
    }

    @Test
    void automaticStartupKeepsTheExistingEmptyStateWhenTheProjectHasNoSessions() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        ArrayDeque<Runnable> effects = new ArrayDeque<>();
        var controller = new CodingTerminalController(
                PROJECT_ID,
                client,
                new TerminalEventPump(32),
                new TerminalUiReducer(),
                TerminalUiState.initial(120, 40),
                effects::addLast);

        controller.start(CodingTerminalStartup.autoLast());
        effects.removeFirst().run();
        controller.drainEvents();

        assertThat(client.listCalls).isEqualTo(1);
        assertThat(controller.state().session()).isEmpty();
        assertThat(controller.state().recoverableError()).isEmpty();
        assertThat(controller.state().status()).isEqualTo("Idle");
    }

    @Test
    void startupPromptDoesNotTakeOverAnActiveRun() {
        FakeClient client = new FakeClient(activeView());
        var controller = controller(client);

        controller.start(CodingTerminalStartup.session(SESSION_ID, Optional.of("do not steer this")));

        assertThat(client.submittedMessages).isEmpty();
        assertThat(client.steeredMessages).isEmpty();
        assertThat(controller.state().editorBuffer()).isEqualTo("do not steer this");
        assertThat(controller.state().recoverableError()).contains("RUN_TAKEOVER_NOT_SUPPORTED");
    }

    @Test
    void openingASessionRestoresTheSafeHistoryProjection() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.history = new CodingSessionHistoryPage(
                SESSION_ID,
                List.of(
                        new CodingSessionHistoryItem(
                                "history-1",
                                CodingSessionHistoryItem.Kind.USER,
                                "You",
                                "previous question",
                                "COMPLETED",
                                1,
                                Instant.EPOCH),
                        new CodingSessionHistoryItem(
                                "history-2",
                                CodingSessionHistoryItem.Kind.ASSISTANT,
                                "Assistant",
                                "previous answer",
                                "COMPLETED",
                                2,
                                Instant.EPOCH)),
                true);
        var controller = controller(client);

        controller.open(SESSION_ID);

        assertThat(controller.state().transcript())
                .extracting(value -> value.body())
                .containsExactly(
                        "Earlier history is not loaded or has been compacted.", "previous question", "previous answer");
    }

    @Test
    void altUpRestoresTheSelectedDurableFollowUpIntoTheEditor() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        CodingQueuedMessage queued = new CodingQueuedMessage("follow-1", SESSION_ID, "queued task", 1, 2);
        client.restorable = List.of(queued);
        var controller = controller(client);
        controller.open(SESSION_ID);

        controller.accept(input(TerminalInput.Kind.RESTORE, ""));
        assertThat(controller.state().selector()).isPresent();
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));

        assertThat(client.restored).containsExactly("follow-1");
        assertThat(controller.state().editorBuffer()).isEqualTo("queued task");
        assertThat(controller.state().editorCursor()).isEqualTo("queued task".length());
    }
}

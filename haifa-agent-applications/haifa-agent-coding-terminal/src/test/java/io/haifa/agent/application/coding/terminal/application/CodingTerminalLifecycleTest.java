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
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationProviderView;
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
    void firstStartupOffersTheConnectionCenterWhenTheSelectedCredentialIsMissing() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        CodingAuthenticationClient authentication = new CodingAuthenticationClient() {
            @Override
            public boolean connectionRequired() {
                return true;
            }

            @Override
            public String apiKeyProviderId() {
                return "deepseek";
            }

            @Override
            public List<CodingAuthenticationProviderView> providers() {
                return List.of(provider(
                        "deepseek",
                        "DeepSeek",
                        CodingAuthenticationProviderView.Category.API_KEY,
                        false,
                        true,
                        apiKeyMethod()));
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
            assertThat(selector.kind()).isEqualTo("auth-provider");
            assertThat(selector.title()).isEqualTo("Connect a model");
            assertThat(selector.options()).containsExactly("DeepSeek");
            assertThat(selector.details()).singleElement().satisfies(detail -> {
                assertThat(detail.category()).isEqualTo("API Key");
                assertThat(detail.badge()).isEqualTo("Current");
            });
        });
    }

    @Test
    void connectionCenterFiltersProvidersByTypedText() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        CodingAuthenticationClient authentication = new CodingAuthenticationClient() {
            @Override
            public List<CodingAuthenticationProviderView> providers() {
                return List.of(
                        provider(
                                "deepseek",
                                "DeepSeek",
                                CodingAuthenticationProviderView.Category.API_KEY,
                                false,
                                false,
                                apiKeyMethod()),
                        provider(
                                "google-antigravity",
                                "Google Antigravity Direct",
                                CodingAuthenticationProviderView.Category.SUBSCRIPTION,
                                false,
                                false,
                                browserMethod()));
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
        var controller = controller(client, authentication);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/connect"));
        controller.accept(input(TerminalInput.Kind.SELECTOR_FILTER, "anti"));

        assertThat(controller.state().selector()).get().satisfies(selector -> {
            assertThat(selector.kind()).isEqualTo("auth-provider");
            assertThat(selector.options()).containsExactly("Antigravity");
            assertThat(selector.title()).contains("Search: anti");
        });

        controller.accept(input(TerminalInput.Kind.SELECTOR_BACKSPACE, ""));
        controller.accept(input(TerminalInput.Kind.SELECTOR_BACKSPACE, ""));
        controller.accept(input(TerminalInput.Kind.SELECTOR_BACKSPACE, ""));
        controller.accept(input(TerminalInput.Kind.SELECTOR_BACKSPACE, ""));

        assertThat(controller.state().selector().orElseThrow().options()).containsExactly("Antigravity", "DeepSeek");
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
            public List<CodingAuthenticationProviderView> providers() {
                return List.of(
                        provider(
                                "deepseek",
                                "DeepSeek",
                                CodingAuthenticationProviderView.Category.API_KEY,
                                false,
                                false,
                                apiKeyMethod()),
                        provider(
                                "google-antigravity",
                                "Google Antigravity Direct",
                                CodingAuthenticationProviderView.Category.SUBSCRIPTION,
                                false,
                                false,
                                browserMethod()));
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

        var selector = controller.state().selector().orElseThrow();
        var options = selector.options();
        assertThat(options).contains("DeepSeek", "Antigravity");
        assertThat(options.get(0)).isEqualTo("Antigravity");
        assertThat(options.get(options.size() - 1)).isEqualTo("DeepSeek");
        assertThat(selector.details().getFirst().category()).isEqualTo("Subscription");
        assertThat(selector.details().getLast().category()).isEqualTo("API Key");

        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));
        controller.drainEvents();

        assertThat(started).isTrue();
        assertThat(controller.state().transcript()).anySatisfy(item -> {
            assertThat(item.title()).isEqualTo("Antigravity connection");
            assertThat(item.status()).isEqualTo("CONNECTED");
        });
        assertThat(controller.state().status()).contains("Connected to Antigravity");
    }

    @Test
    void deviceLoginUsesAShortLivedWaitingPanelInsteadOfPersistingTheCode() {
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

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/connect codex device"));
        controller.drainEvents();

        assertThat(controller.state().transcript())
                .noneMatch(item -> item.title().equals("ChatGPT device sign-in"));
        assertThat(controller.state().transcript()).anySatisfy(item -> {
            assertThat(item.title()).isEqualTo("ChatGPT connection");
            assertThat(item.body()).contains("Connected. Credentials were saved to the system credential store.");
            assertThat(item.status()).isEqualTo("CONNECTED");
        });
        assertThat(controller.state().status()).contains("Connected to ChatGPT");
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

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/connect codex browser"));
        controller.drainEvents();

        assertThat(controller.state().transcript())
                .noneMatch(item -> item.title().equals("ChatGPT browser sign-in"));
        assertThat(controller.state().transcript()).anySatisfy(item -> {
            assertThat(item.title()).isEqualTo("ChatGPT connection");
            assertThat(item.body())
                    .contains(
                            "Connected. Credentials were saved to the system credential store.",
                            "UNOFFICIAL_LOCAL_COMPAT");
            assertThat(item.status()).isEqualTo("CONNECTED");
        });
        assertThat(controller.state().toString()).doesNotContain("client_id=test", "state=state");
        assertThat(controller.state().status()).contains("Connected to ChatGPT");
    }

    @Test
    void escapeRequestsCancellationWithoutWaitingForTheBlockingLogin() throws Exception {
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var cancelSeen = new java.util.concurrent.CountDownLatch(1);
        var cancelRequested = new java.util.concurrent.atomic.AtomicBoolean();
        var loginCalls = new java.util.concurrent.atomic.AtomicInteger();
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
                loginCalls.incrementAndGet();
                instructions.accept(new CodingBrowserLoginView(
                        URI.create("https://auth.openai.com/oauth/authorize?state=test"),
                        System.currentTimeMillis() + 60_000));
                started.countDown();
                try {
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("AUTH_CANCELLED");
            }

            @Override
            public boolean cancelLogin() {
                cancelRequested.set(true);
                cancelSeen.countDown();
                return true;
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
        var executor = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            var controller = new CodingTerminalController(
                    PROJECT_ID,
                    new FakeClient(view(Optional.empty())),
                    authentication,
                    new TerminalEventPump(32),
                    new TerminalUiReducer(),
                    TerminalUiState.initial(120, 40),
                    executor);

            controller.accept(input(TerminalInput.Kind.SUBMIT, "/connect codex browser"));
            assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            controller.accept(input(TerminalInput.Kind.TICK, ""));
            assertThat(controller.state().selector().orElseThrow().kind()).isEqualTo("auth-wait");
            assertThat(controller.state().status()).contains("Waiting for browser authorization", "remaining");

            controller.accept(input(TerminalInput.Kind.CANCEL_OR_CLOSE, ""));

            // cancelLogin runs on the control-effect executor; Escape must not block on it.
            assertThat(cancelSeen.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    .isTrue();
            assertThat(cancelRequested).isTrue();
            assertThat(controller.state().selector()).isEmpty();
            assertThat(controller.state().status()).isEqualTo("Cancelling sign-in");

            controller.accept(input(TerminalInput.Kind.SUBMIT, "/connect codex browser"));
            assertThat(loginCalls).hasValue(1);
            assertThat(controller.state().status()).contains("A sign-in is already in progress");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
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

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/connect codex browser"));
        controller.drainEvents();

        assertThat(controller.state().transcript()).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("ChatGPT connection failed");
            assertThat(item.body())
                    .contains(
                            "Stage: Authorization received. Exchanging it for credentials.",
                            "Reason code: AUTH_REAUTH_REQUIRED",
                            "Sign-in has expired. Run /connect to sign in again.");
            assertThat(item.status()).isEqualTo("FAILED");
        });
        assertThat(controller.state().recoverableError()).contains("AUTH_REAUTH_REQUIRED");
    }

    @Test
    void accountManagementMasksLabelsAndConfirmsBeforeDisconnect() {
        var disconnected = new java.util.concurrent.atomic.AtomicBoolean();
        CodingAuthenticationView connection = new CodingAuthenticationView(
                "model-auth://deepseek/default",
                "deepseek",
                CodingAuthenticationView.Method.API_KEY,
                CodingAuthenticationView.Status.AUTHENTICATED,
                "alice@example.com",
                Optional.empty(),
                OptionalLong.empty(),
                true);
        CodingAuthenticationClient authentication = new CodingAuthenticationClient() {
            @Override
            public List<CodingAuthenticationView> connections() {
                return List.of(connection);
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
                disconnected.set(true);
                return true;
            }
        };
        var controller = controller(new FakeClient(view(Optional.empty())), authentication);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/account"));

        assertThat(controller.state().selector()).get().satisfies(selector -> {
            assertThat(selector.kind()).isEqualTo("auth-account");
            assertThat(selector.options()).containsExactly("DeepSeek · API Key · a***@example.com · Connected");
        });

        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));
        assertThat(controller.state().selector()).get().satisfies(selector -> {
            assertThat(selector.kind()).isEqualTo("auth-account-actions");
            assertThat(selector.options()).containsExactly("Update API key", "Disconnect", "Back");
        });

        controller.accept(input(TerminalInput.Kind.SELECT_NEXT, ""));
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));
        assertThat(controller.state().selector()).get().satisfies(selector -> {
            assertThat(selector.kind()).isEqualTo("auth-logout-confirm");
            assertThat(selector.options().getFirst()).isEqualTo("Cancel");
        });
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));
        assertThat(disconnected).isFalse();
        assertThat(controller.state().selector()).isEmpty();

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/account"));
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));
        controller.accept(input(TerminalInput.Kind.SELECT_NEXT, ""));
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));
        controller.accept(input(TerminalInput.Kind.SELECT_NEXT, ""));
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));

        assertThat(disconnected).isTrue();
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

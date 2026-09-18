package io.haifa.agent.testing.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionConsequenceView;
import io.haifa.agent.runtime.api.InteractionInputContract;
import io.haifa.agent.runtime.api.InteractionKind;
import io.haifa.agent.runtime.api.InteractionRequestId;
import io.haifa.agent.runtime.api.InteractionRequesterView;
import io.haifa.agent.runtime.api.InteractionResponseReceipt;
import io.haifa.agent.runtime.api.InteractionResponseReceiptStatus;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.runtime.api.InteractionState;
import io.haifa.agent.runtime.api.InteractionTargetView;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPage;
import io.haifa.agent.transport.http.HaifaHttpTransportAdapter;
import io.haifa.agent.transport.http.HttpAuthorizationException;
import io.haifa.agent.transport.http.HttpTransportConfiguration;
import io.haifa.agent.transport.http.HttpTransportRequest;
import io.haifa.agent.transport.http.RunEventCursorTokenCodec;
import io.haifa.agent.transport.http.RuntimeCallerScope;
import io.haifa.agent.transport.http.TrustedCallerContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class CrossProductInteractionFixtureTest {
    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");
    private static final AgentRunId RUN_ID = new AgentRunId("run-product-fixture");
    private static final AgentSessionId SESSION_ID = new AgentSessionId("session-product-fixture");

    @Test
    void codingApprovalIsSafeAndDuplicateHttpResponseDoesNotDuplicateApplication() {
        FixtureRuntime runtime = new FixtureRuntime(interaction(
                InteractionKind.APPROVAL,
                "Approve one high-risk operation?",
                new InteractionTargetView(
                        "tool-call", "tool-call-7", Optional.of("1"), Optional.of("sha256:safe"), "restricted write"),
                InteractionInputContract.NONE,
                List.of(InteractionAction.APPROVE, InteractionAction.REJECT)));
        var adapter = adapter(runtime, new AtomicBoolean());

        var pending = adapter.handle(getPending());
        var accepted = adapter.handle(response("approve", "response-coding", "approval-key"));
        var duplicate = adapter.handle(response("approve", "response-coding", "approval-key"));

        assertThat(pending.status()).isEqualTo(200);
        assertThat(pending.bodyUtf8())
                .contains("restricted write")
                .doesNotContain("C:\\\\secret", "toolArguments", "Bearer");
        assertThat(accepted.status()).isEqualTo(202);
        assertThat(duplicate.status()).isEqualTo(202);
        assertThat(runtime.businessApplications).isEqualTo(1);
    }

    @Test
    void documentReviewUsesStableArtifactReferenceAndNeverEmbedsDocumentBodyOrHostPath() {
        FixtureRuntime runtime = new FixtureRuntime(interaction(
                InteractionKind.ARTIFACT_REVIEW,
                "Review the proposed artifact revision",
                new InteractionTargetView(
                        "artifact",
                        "artifact-contract-42",
                        Optional.of("v7"),
                        Optional.of("sha256:document"),
                        "contract revision summary"),
                InteractionInputContract.contentParts(4, 4_096),
                List.of(InteractionAction.SUBMIT, InteractionAction.REJECT)));
        var adapter = adapter(runtime, new AtomicBoolean());

        var pending = adapter.handle(getPending());

        assertThat(pending.status()).isEqualTo(200);
        assertThat(pending.bodyUtf8())
                .contains("artifact-contract-42", "sha256:document")
                .doesNotContain("confidential document body", "C:\\\\Users\\\\");
    }

    @Test
    void enterpriseFixtureFailsClosedWhenAuthorizationIsRevokedAndDoesNotExposeOrganizationDetails() {
        FixtureRuntime runtime = new FixtureRuntime(interaction(
                InteractionKind.APPROVAL,
                "Authorize the referenced business operation?",
                new InteractionTargetView(
                        "business-target",
                        "business-ref-9",
                        Optional.of("version-3"),
                        Optional.of("sha256:business"),
                        "single bounded operation"),
                InteractionInputContract.NONE,
                List.of(InteractionAction.APPROVE, InteractionAction.REJECT)));
        AtomicBoolean revoked = new AtomicBoolean(true);
        var adapter = adapter(runtime, revoked);

        var denied = adapter.handle(response("approve", "response-enterprise", "enterprise-key"));

        assertThat(denied.status()).isEqualTo(404);
        assertThat(denied.bodyUtf8())
                .contains("RUN_NOT_FOUND")
                .doesNotContain("manager", "department", "authority lookup", "business-ref-9");
        assertThat(runtime.businessApplications).isZero();
    }

    private static HaifaHttpTransportAdapter adapter(FixtureRuntime runtime, AtomicBoolean revoked) {
        TrustedCallerContext caller = new TrustedCallerContext("tenant", "user", "reviewer", "fixture");
        return new HaifaHttpTransportAdapter(
                runtime,
                request -> caller,
                (trusted, operation, runId, requestId) -> {
                    if (revoked.get() && operation == io.haifa.agent.transport.http.RunOperation.RESPOND_INTERACTION) {
                        throw new HttpAuthorizationException();
                    }
                },
                new RuntimeCallerScope() {
                    @Override
                    public <T> T call(TrustedCallerContext trusted, java.util.function.Supplier<T> operation) {
                        return operation.get();
                    }
                },
                new RunEventCursorTokenCodec() {
                    @Override
                    public String encode(RunEventCursor cursor) {
                        return "fixture-cursor";
                    }

                    @Override
                    public RunEventCursor decode(AgentRunId expectedRunId, String token) {
                        return RunEventCursor.beforeFirst(expectedRunId);
                    }
                },
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                HttpTransportConfiguration.DEFAULT,
                () -> "product-fixture");
    }

    private static HttpTransportRequest getPending() {
        return new HttpTransportRequest(
                "GET", "/v1/runs/" + RUN_ID.value() + "/interactions/pending", Map.of(), Map.of(), new byte[0]);
    }

    private static HttpTransportRequest response(String action, String responseId, String key) {
        String body =
                """
                {"responseId":"%s","requestId":"request-product","runId":"%s",
                 "expectedRevision":0,"action":"%s","inputs":[],
                 "idempotencyKey":"%s","respondedAt":"2026-07-26T00:00:00Z"}
                """
                        .formatted(responseId, RUN_ID.value(), action, key);
        return new HttpTransportRequest(
                "POST",
                "/v1/runs/" + RUN_ID.value() + "/interactions/request-product/responses",
                Map.of("Content-Type", List.of("application/json")),
                Map.of(),
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static InteractionView interaction(
            InteractionKind kind,
            String prompt,
            InteractionTargetView target,
            InteractionInputContract input,
            List<InteractionAction> actions) {
        return new InteractionView(
                new InteractionRequestId("request-product"),
                RUN_ID,
                SESSION_ID,
                0,
                kind,
                InteractionState.PENDING,
                "Review required",
                prompt,
                actions,
                input,
                target,
                new InteractionRequesterView("agent", "requester"),
                NOW,
                NOW.plusSeconds(300),
                new InteractionConsequenceView("continue", "stop", "expire"));
    }

    private static final class FixtureRuntime extends AbstractAgentRuntimeFixture {
        private final InteractionView pending;
        private final Map<String, InteractionResponseReceipt> receipts = new java.util.HashMap<>();
        private int businessApplications;

        private FixtureRuntime(InteractionView pending) {
            this.pending = pending;
        }

        @Override
        public Optional<InteractionView> pendingInteraction(AgentRunId runId) {
            return Optional.of(pending);
        }

        @Override
        public InteractionResponseReceipt respond(InteractionResponseSubmission response) {
            return receipts.computeIfAbsent(response.idempotencyKey(), ignored -> {
                businessApplications++;
                return new InteractionResponseReceipt(
                        response.responseId(),
                        response.requestId(),
                        response.runId(),
                        InteractionResponseReceiptStatus.NEWLY_ACCEPTED,
                        InteractionState.RESPONDED,
                        1,
                        2);
            });
        }

        @Override
        public RunEventPage events(AgentRunId runId, RunEventCursor after, int limit) {
            return new RunEventPage(List.of(), after, after, false);
        }
    }
}

package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.application.project.product.coding.client.CodingSessionApprovalStatus.State;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.runtime.api.*;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CodingHostApprovalsTest {
    @Test
    void presentationReadsCannotRenewOrReplaceExpiredConsent() {
        var f = new Fixture();
        assertThat(f.host.presentationStatus("legacy", f.session)).isEmpty();
        f.host.initialize("a");
        f.host.grant("a", f.pending, "grant");
        f.now.set(Instant.EPOCH.plusSeconds(44));
        assertThat(f.host.presentationStatus("a", f.session)).hasValueSatisfying(status -> {
            assertThat(status.state()).isEqualTo(State.ACTIVE);
            assertThat(status.expiresAt()).contains(Instant.EPOCH.plusSeconds(45));
        });
        f.now.set(Instant.EPOCH.plusSeconds(45));
        assertThat(f.host.presentationStatus("a", f.session)).isEmpty();
        f.now.set(Instant.EPOCH.plusSeconds(46));
        assertThat(f.host.presentationStatus("a", f.session)).isEmpty();
        f.host.renew("a");
        assertThat(f.host.presentationStatus("a", f.session))
                .hasValueSatisfying(status -> assertThat(status.state()).isEqualTo(State.ASK));
        assertThat(f.responses).hasValue(1);
    }

    @Test
    void renewalExtendsOnlyALiveLeaseAndAMissedDeadlineCannotResurrectConsent() {
        var f = new Fixture();
        f.host.initialize("a");
        f.host.grant("a", f.pending, "grant");
        assertThat(f.host.status("a", f.session).state()).isEqualTo(State.ACTIVE);
        f.now.set(Instant.EPOCH.plusSeconds(20));
        f.host.renew("a");
        assertThat(f.host.status("a", f.session).expiresAt()).contains(Instant.EPOCH.plusSeconds(65));
        f.now.set(Instant.EPOCH.plusSeconds(65));
        f.host.renew("a");
        assertThat(f.host.status("a", f.session).state()).isEqualTo(State.ASK);
        assertThat(f.host.prepare("a", f.pending)).isPresent();
        assertThat(f.responses).hasValue(1);
    }

    @Test
    void anotherClientAndReconnectedOrRestartedHostNeverInheritConsent() {
        var f = new Fixture();
        f.host.initialize("a");
        f.host.initialize("b");
        f.host.grant("a", f.pending, "grant");
        assertThat(f.host.status("b", f.session).state()).isEqualTo(State.ASK);
        assertThat(f.host.prepare("b", f.pending)).isPresent();
        f.host.closeClient("a");
        f.host.initialize("c");
        assertThat(f.host.status("c", f.session).state()).isEqualTo(State.ASK);
        assertThatThrownBy(() -> f.host.grant("a", f.pending, "stale")).isInstanceOf(CodingHostException.class);
        f.host.close();
        var restarted = new CodingHostApprovals(f.runtime, () -> "response-restarted", f.now::get);
        restarted.initialize("d");
        assertThat(restarted.status("d", f.session).state()).isEqualTo(State.ASK);
        assertThat(f.responses).hasValue(1);
    }

    @Test
    void expiryIsCheckedWithoutMaintenanceAndResetRevokesAllClientsWithoutRuntimeCancellation() {
        var f = new Fixture();
        f.host.initialize("a");
        f.host.initialize("b");
        f.host.grant("a", f.pending, "a-grant");
        f.host.grant("b", f.pending, "b-grant");
        f.host.reset(f.session);
        assertThat(f.host.status("a", f.session).state()).isEqualTo(State.ASK);
        assertThat(f.host.status("b", f.session).state()).isEqualTo(State.ASK);
        f.host.grant("a", f.pending, "again");
        f.now.set(Instant.EPOCH.plusSeconds(45));
        assertThat(f.host.prepare("a", f.pending)).isPresent();
        assertThat(f.host.status("a", f.session).expiresAt()).isEmpty();
        assertThat(f.responses).hasValue(3);
        assertThat(f.host.prepare("legacy", f.pending))
                .hasValueSatisfying(
                        prompt -> assertThat(prompt.allowForSession()).isFalse());
    }

    private static final class Fixture {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);
        final AtomicInteger responses = new AtomicInteger();
        final AgentSessionId session = new AgentSessionId("session");
        final AgentRunId run = new AgentRunId("run");
        final ToolCallId call = new ToolCallId("call");
        final InteractionView pending = new InteractionView(
                new InteractionRequestId("request"),
                run,
                session,
                1,
                InteractionKind.APPROVAL,
                InteractionState.PENDING,
                "Approval",
                "Synthetic write",
                List.of(InteractionAction.REJECT, InteractionAction.APPROVE),
                InteractionInputContract.NONE,
                new InteractionTargetView("tool", call.value(), Optional.empty(), Optional.empty(), "Synthetic tool"),
                new InteractionRequesterView("operator", "Operator"),
                Instant.EPOCH,
                Optional.empty(),
                new InteractionConsequenceView("Allow", "Reject", "Expire"));
        final AgentRuntime runtime = (AgentRuntime) Proxy.newProxyInstance(
                AgentRuntime.class.getClassLoader(),
                new Class<?>[] {AgentRuntime.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "pendingInteraction" -> Optional.of(pending);
                    case "toolCalls" ->
                        List.of(new ToolCallView(
                                call,
                                run,
                                "file_write",
                                "1",
                                new ToolDataView(Map.of("path", "synthetic.txt"), false, Set.of("path")),
                                ToolCallStatus.WAITING_APPROVAL,
                                Optional.empty(),
                                Optional.empty(),
                                Instant.EPOCH,
                                Optional.empty(),
                                Optional.empty()));
                    case "respond" -> {
                        responses.incrementAndGet();
                        var value = (InteractionResponseSubmission) args[0];
                        yield new InteractionResponseReceipt(
                                value.responseId(),
                                value.requestId(),
                                value.runId(),
                                InteractionResponseReceiptStatus.NEWLY_ACCEPTED,
                                InteractionState.APPLIED,
                                2,
                                3);
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        final CodingHostApprovals host =
                new CodingHostApprovals(runtime, () -> "response-" + responses.get(), now::get, Duration.ofSeconds(45));
    }
}

package io.haifa.agent.store.sqlite;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelAdapterCoordinate;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.policy.api.PolicyPresets;
import io.haifa.agent.policy.core.DefaultPolicyDecisionService;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RuntimeApiErrorCode;
import io.haifa.agent.runtime.api.RuntimeContractException;
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.api.HaifaAgents;
import io.haifa.agent.sdk.api.SdkCaller;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.product.ProductId;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.sdk.product.ProductRunProfileRef;
import io.haifa.agent.sdk.product.ProductVersion;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;

/** Consumer-owned, network-free fixture launched in two distinct JVMs by the integration test. */
public final class FrozenInstructionDiagnosticProcess {
    private static final String A = "Synthetic frozen instruction A: 中文\nsecond line";
    private static final String B = "Synthetic current instruction B";

    private FrozenInstructionDiagnosticProcess() {}

    public static void main(String[] args) throws Exception {
        execute(Path.of(args[1]), args[0]);
    }

    static void execute(Path root, String mode) throws Exception {
        boolean writer = mode.equals("write");
        boolean corrupt = mode.equals("corrupt-read");
        boolean lifecycle = mode.equals("lifecycle");
        Files.createDirectories(root);
        var sqlite = SqliteSdkContributions.initializeWithKey(
                SqliteStoreConfiguration.defaults(root.resolve("runtime.sqlite")),
                Clock.systemUTC(),
                new SecretKeySpec(new byte[32], "AES"));
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        var modelStarted = new AtomicReference<>(new CountDownLatch(1));
        String instructionHashA = hash(A);
        String instructionHashB = hash(B);
        var caller = new AtomicReference<>(SdkCaller.defaultPublicUser());
        List<String> actualInstructions = new CopyOnWriteArrayList<>();
        var snapshot = ResolvedModelSnapshot.create(
                new ModelProviderId("frozen-fixture"),
                "1.0.0",
                new ModelDefinitionId("frozen-fixture"),
                "1.0.0",
                "frozen-fixture",
                "frozen-fixture",
                "1.0.0",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                ModelApiBindingDefinition.STANDARD_DIALECT,
                URI.create("https://model.invalid"),
                new CredentialRef("env://UNUSED_FROZEN_FIXTURE"),
                false,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                8192,
                1024,
                Map.of(),
                Map.of());
        var model = new ModelContribution(
                Map.of(ModelAdapterCoordinate.from(snapshot), request -> {
                    modelCalls.incrementAndGet();
                    boolean usesA = request.messages().stream()
                            .anyMatch(message -> message.content().contains(A));
                    boolean usesB = request.messages().stream()
                            .anyMatch(message -> message.content().contains(B));
                    require(usesA != usesB, "Actual model request must contain exactly one frozen instruction");
                    actualInstructions.add(usesA ? "A" : "B");
                    System.out.println(
                            "MODEL_CAPTURE pid=" + ProcessHandle.current().pid() + " call=" + modelCalls.get()
                                    + " instruction_hash=" + (usesA ? instructionHashA : instructionHashB));
                    modelStarted.get().countDown();
                    if (request.messages().stream().anyMatch(message -> message.role() == ModelMessageRole.TOOL)) {
                        return new AgentChatResponse(
                                "frozen-complete",
                                request.model().providerModelId(),
                                "fixture done",
                                List.of(),
                                ModelFinishReason.STOP,
                                ModelUsage.unpriced(4, 4),
                                "",
                                Map.of());
                    }
                    return new AgentChatResponse(
                            "frozen-fixture",
                            request.model().providerModelId(),
                            "",
                            List.of(new ModelToolCall(
                                    new ProviderToolCallCorrelationId("pending-write"),
                                    "pending_write",
                                    Map.of("text", "synthetic pending write"))),
                            ModelFinishReason.TOOL_CALLS,
                            ModelUsage.unpriced(4, 4),
                            "",
                            Map.of());
                }),
                snapshot,
                Map.of(snapshot.modelId().value(), snapshot));
        var p = ProductProfile.create(
                new ProductId("frozen-fixture"),
                new ProductVersion("1.0.0"),
                new AgentDefinitionId("frozen-fixture-agent"),
                new AgentDefinitionVersion(1, 0, 0),
                writer ? A : B,
                new ProductRunProfileRef("frozen-chat", "1.0.0"),
                new AgentRunBudget(10000, 10000, 10000, 8, 8, 0, "USD", 1000),
                new AgentRunLimits(8, 0, 1, 30000, 30000),
                Set.of("pending_write"),
                Set.of());
        try {
            try (var agent = HaifaAgents.builder(p)
                    .model(model)
                    .callerProvider(caller::get)
                    .persistence(sqlite.borrowedPersistence())
                    .conversation(sqlite.conversation())
                    .tool(new PendingWrite(root.resolve("must-not-exist.txt"), writes))
                    .policy(new PolicyPlatformContribution(
                            PolicyPresets.standardApproval(), new DefaultPolicyDecisionService()))
                    .build()) {
                Properties metadata = new Properties();
                Path metadataFile = root.resolve("identity.properties");
                AgentRunId id;
                if (writer) {
                    id = agent.conversations()
                            .start(new StartConversationCommand("frozen-write", "Fixture", "Return fixture result"))
                            .runId();
                    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                    var initialWait =
                            new WaitProbe("initial-approval", "model-notification", id, modelCalls, writes, deadline);
                    awaitApproval(agent, initialWait, modelStarted.get(), deadline);
                    var frozen = agent.runs().frozenInstructionDiagnostic(id).orElseThrow();
                    require(frozen.instructionContentHash().equals(hash(A)), "Writer digest must match frozen A");
                    metadata.setProperty("run_id", id.value());
                    metadata.setProperty("hash", frozen.instructionContentHash());
                    metadata.setProperty(
                            "config_ref", frozen.configurationSnapshot().snapshotId());
                    metadata.setProperty(
                            "request_id",
                            agent.runs()
                                    .pendingInteraction(id)
                                    .orElseThrow()
                                    .requestId()
                                    .value());
                    try (Writer out = Files.newBufferedWriter(metadataFile, StandardCharsets.UTF_8)) {
                        metadata.store(out, "Only identity and digest, no instruction body");
                    }
                    require(modelCalls.get() == 1, "Waiting fixture must have one real model call");
                } else {
                    try (Reader in = Files.newBufferedReader(metadataFile, StandardCharsets.UTF_8)) {
                        metadata.load(in);
                    }
                    id = new AgentRunId(metadata.getProperty("run_id"));
                    if (!corrupt) {
                        require(
                                agent.runs().find(id).orElseThrow().status() == AgentRunStatus.WAITING_APPROVAL,
                                "Existing waiting Run must survive restart");
                        require(
                                agent.runs()
                                        .pendingInteraction(id)
                                        .orElseThrow()
                                        .requestId()
                                        .value()
                                        .equals(metadata.getProperty("request_id")),
                                "Original pending request must survive restart");
                        require(
                                !agent.runs().promptDiagnostics(id).available(),
                                "Rebuilt facade must lack process-local Context evidence");
                    }
                    var owner = caller.get();
                    caller.set(new SdkCaller(owner.tenant(), new PrincipalRef("another-user", "user")));
                    require(
                            agent.runs().frozenInstructionDiagnostic(id).isEmpty(),
                            "Existing same-tenant cross-principal Run must be invisible");
                    caller.set(new SdkCaller(new TenantRef("another-tenant"), owner.principal()));
                    require(
                            agent.runs().frozenInstructionDiagnostic(id).isEmpty(),
                            "Existing cross-tenant Run must be invisible");
                    caller.set(owner);
                    require(
                            agent.runs()
                                    .frozenInstructionDiagnostic(new AgentRunId("missing-frozen-run"))
                                    .isEmpty(),
                            "Unknown Run must stay invisible");
                    System.out.println("AUTH existing_run=" + id.value()
                            + " same_tenant_cross_principal=INVISIBLE cross_tenant=INVISIBLE");
                    int events = corrupt
                            ? -1
                            : agent.runs()
                                    .events(id, RunEventCursor.beforeFirst(id), 100)
                                    .items()
                                    .size();
                    if (corrupt) {
                        try {
                            agent.runs().frozenInstructionDiagnostic(id);
                            throw new AssertionError("Corrupt visible snapshot must fail closed");
                        } catch (RuntimeContractException failure) {
                            require(failure.code() == RuntimeApiErrorCode.INTERNAL_ERROR, "Safe failure code");
                            require(
                                    failure.getMessage().equals("Frozen instruction diagnostic is unavailable"),
                                    "Fixed safe message");
                            require(failure.getCause() == null, "No payload-bearing cause may escape");
                            System.out.println("FIXED_SAFE_FAILURE code=" + failure.code());
                        }
                    } else {
                        var frozen =
                                agent.runs().frozenInstructionDiagnostic(id).orElseThrow();
                        require(
                                frozen.instructionContentHash().equals(metadata.getProperty("hash")),
                                "Restart digest must identify original A");
                        require(
                                !frozen.instructionContentHash()
                                        .equals(hash(agent.profile().instructions())),
                                "Current B must not replace frozen A");
                        require(
                                frozen.configurationSnapshot().snapshotId().equals(metadata.getProperty("config_ref")),
                                "Same frozen ref");
                        require(
                                frozen.definitionId().equals(p.definitionId())
                                        && frozen.definitionVersion().equals(p.definitionVersion()),
                                "Frozen definition binding must be preserved");
                    }
                    if (!corrupt)
                        require(
                                agent.runs()
                                                .events(id, RunEventCursor.beforeFirst(id), 100)
                                                .items()
                                                .size()
                                        == events,
                                "Read cannot append events");
                    require(modelCalls.get() == 0, "Query cannot schedule model execution");
                }
                require(!Files.exists(root.resolve("must-not-exist.txt")), "Query cannot execute pending write");
                require(writes.get() == 0, "Query cannot invoke a Tool");
                if (lifecycle) {
                    var pending = agent.runs().pendingInteraction(id).orElseThrow();
                    agent.runs()
                            .respond(new InteractionResponseSubmission(
                                    new InteractionResponseId("approve-frozen-a"),
                                    pending.requestId(),
                                    id,
                                    pending.revision(),
                                    InteractionAction.APPROVE,
                                    List.of(),
                                    "approve-frozen-a",
                                    Instant.now()));
                    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                    var resumedWait =
                            new WaitProbe("resumed-terminal", "public-await", id, modelCalls, writes, deadline);
                    try {
                        long remaining = deadline - System.nanoTime();
                        require(remaining > 0, "Terminal fixture deadline exhausted");
                        var terminal = agent.runs()
                                .await(id, Duration.ofNanos(remaining))
                                .orElseThrow(() -> new AssertionError("Approved A did not complete within deadline"));
                        resumedWait.observe(terminal);
                        require(System.nanoTime() <= deadline, "Terminal fixture deadline exceeded");
                        require(terminal.status() == AgentRunStatus.COMPLETED, "Approved A must complete");
                    } finally {
                        resumedWait.finish();
                    }
                    require(Files.exists(root.resolve("must-not-exist.txt")), "Actual approved Tool must execute");
                    require(writes.get() == 1, "Approved original Tool must execute exactly once");
                    require(
                            agent.runs()
                                    .frozenInstructionDiagnostic(id)
                                    .orElseThrow()
                                    .instructionContentHash()
                                    .equals(hash(A)),
                            "Terminal A must retain original digest");
                    require(actualInstructions.equals(List.of("A")), "Resumed actual model request must use frozen A");
                    var nextModelStarted = new CountDownLatch(1);
                    modelStarted.set(nextModelStarted);
                    var next = agent.conversations()
                            .start(new StartConversationCommand(
                                    "frozen-next-b", "Fixture B", "Return next fixture result"))
                            .runId();
                    deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                    var nextWait =
                            new WaitProbe("next-b-approval", "model-notification", next, modelCalls, writes, deadline);
                    awaitApproval(agent, nextWait, nextModelStarted, deadline);
                    require(
                            agent.runs()
                                    .frozenInstructionDiagnostic(next)
                                    .orElseThrow()
                                    .instructionContentHash()
                                    .equals(hash(B)),
                            "New Run must freeze current B");
                    require(actualInstructions.equals(List.of("A", "B")), "Actual next model request must use B");
                    require(writes.get() == 1, "Next pending Tool must not execute before approval");
                    System.out.println("LIFECYCLE original=" + id.value() + " terminal=COMPLETED hash=" + hash(A)
                            + " next=" + next.value() + " next_hash=" + hash(B) + " actual_request_hashes=" + hash(A)
                            + "," + hash(B));
                }
                System.out.println("FROZEN mode=" + mode + " pid="
                        + ProcessHandle.current().pid() + " run=" + id.value()
                        + " status="
                        + (corrupt
                                ? "UNREADABLE_FROZEN_CONFIGURATION"
                                : agent.runs().find(id).orElseThrow().status()) + " hash="
                        + metadata.getProperty("hash")
                        + " calls=" + modelCalls.get() + " writes=" + writes.get());
            }
        } finally {
            sqlite.persistence().close();
        }
    }

    private static String hash(String text) throws Exception {
        return "sha256:"
                + HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static void require(boolean condition, String safeMessage) {
        if (!condition) throw new AssertionError(safeMessage);
    }

    private static void awaitApproval(HaifaAgent agent, WaitProbe probe, CountDownLatch modelStarted, long deadline)
            throws InterruptedException {
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !modelStarted.await(remaining, TimeUnit.NANOSECONDS)) {
                probe.snapshot(agent);
                throw new AssertionError("Actual model request did not arrive within approval deadline");
            }
            while (System.nanoTime() < deadline) {
                var snapshot = probe.snapshot(agent);
                require(System.nanoTime() <= deadline, "Approval fixture deadline exceeded");
                if (snapshot.status() == AgentRunStatus.WAITING_APPROVAL) {
                    require(agent.runs().pendingInteraction(probe.id).isPresent(), "Real pending approval must exist");
                    require(System.nanoTime() <= deadline, "Pending approval fixture deadline exceeded");
                    return;
                }
                require(!snapshot.status().isTerminal(), "Fixture terminated before real approval");
                long sleepNanos = Math.min(Duration.ofMillis(10).toNanos(), deadline - System.nanoTime());
                if (sleepNanos > 0) TimeUnit.NANOSECONDS.sleep(sleepNanos);
            }
            throw new AssertionError("Fixture did not reach real approval within deadline");
        } finally {
            probe.finish();
        }
    }

    private static final class WaitProbe {
        private final String stage;
        private final String mode;
        private final AgentRunId id;
        private final AtomicInteger modelCalls;
        private final AtomicInteger writes;
        private final long started;
        private long reads;
        private long readNanos;
        private long maxReadNanos;
        private long lastLogNanos;
        private AgentRunSnapshot last;

        private WaitProbe(
                String stage,
                String mode,
                AgentRunId id,
                AtomicInteger modelCalls,
                AtomicInteger writes,
                long deadline) {
            this.stage = stage;
            this.mode = mode;
            this.id = id;
            this.modelCalls = modelCalls;
            this.writes = writes;
            this.started = deadline - Duration.ofSeconds(10).toNanos();
            System.out.println("WAIT_START stage=" + stage + " mode=" + mode + " run=" + id.value() + " pid="
                    + ProcessHandle.current().pid() + " timeout_ms=10000");
        }

        private AgentRunSnapshot snapshot(HaifaAgent agent) {
            long readStarted = System.nanoTime();
            var snapshot = agent.runs().find(id).orElseThrow();
            long elapsed = System.nanoTime() - readStarted;
            reads++;
            readNanos += elapsed;
            maxReadNanos = Math.max(maxReadNanos, elapsed);
            observe(snapshot);
            return snapshot;
        }

        private void observe(AgentRunSnapshot snapshot) {
            long now = System.nanoTime();
            boolean changed = last == null || last.status() != snapshot.status();
            last = snapshot;
            if (changed || now - lastLogNanos >= Duration.ofSeconds(1).toNanos()) {
                log("WAIT_OBSERVATION");
                lastLogNanos = now;
            }
        }

        private void finish() {
            log("WAIT_FINISH");
        }

        private void log(String kind) {
            System.out.println(kind + " stage=" + stage + " mode=" + mode + " run=" + id.value() + " pid="
                    + ProcessHandle.current().pid()
                    + " elapsed_ms=" + (System.nanoTime() - started) / 1_000_000
                    + " status=" + (last == null ? "UNOBSERVED" : last.status())
                    + " error_code="
                    + (last == null
                            ? "NONE"
                            : last.error().map(error -> error.code().wireCode()).orElse("NONE"))
                    + " reads=" + reads + " read_ms=" + readNanos / 1_000_000 + " max_read_ms="
                    + maxReadNanos / 1_000_000
                    + " model_calls=" + modelCalls.get() + " writes=" + writes.get());
        }
    }

    public record WriteInput(String text) {}

    public record WriteOutput(boolean written) {}

    private record PendingWrite(Path target, AtomicInteger writes) implements JavaTool<WriteInput, WriteOutput> {
        @Override
        public JavaToolSpec<WriteInput, WriteOutput> spec() {
            return JavaToolSpec.builder("pending_write", WriteInput.class, WriteOutput.class)
                    .description("Synthetic durable approval fixture")
                    .sideEffects(ToolSideEffect.FILE_WRITE)
                    .build();
        }

        @Override
        public WriteOutput invoke(WriteInput input, JavaToolContext context) {
            try {
                Files.writeString(target, input.text());
                writes.incrementAndGet();
                return new WriteOutput(true);
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("fixture write failed");
            }
        }
    }
}

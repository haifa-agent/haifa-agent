package io.haifa.agent.store.sqlite.payload;

import io.haifa.agent.runtime.core.bootstrap.RuntimeConfigurationSnapshot;
import io.haifa.agent.runtime.core.checkpoint.RuntimeCheckpointState;
import io.haifa.agent.store.sqlite.codec.PayloadType;
import io.haifa.agent.store.sqlite.codec.VersionedPayloadCodecRegistry;

/** Closed list of payload contracts owned by the SQLite runtime adapter. */
public final class SqliteRuntimePayloadTypes {

    public static final PayloadType<MetadataPayload> METADATA =
            new PayloadType<>("metadata", "1", MetadataPayload.class);
    public static final PayloadType<AgentErrorPayload> AGENT_ERROR =
            new PayloadType<>("agent-error", "1", AgentErrorPayload.class);
    public static final PayloadType<RunResultPayload> RUN_RESULT =
            new PayloadType<>("run-result", "1", RunResultPayload.class);
    public static final PayloadType<ContentPartsPayload> CONTENT_PARTS =
            new PayloadType<>("content-parts", "1", ContentPartsPayload.class);
    public static final PayloadType<StepResultPayload> STEP_RESULT =
            new PayloadType<>("step-result", "1", StepResultPayload.class);
    public static final PayloadType<ToolArgumentsPayload> TOOL_ARGUMENTS =
            new PayloadType<>("tool-arguments", "1", ToolArgumentsPayload.class);
    public static final PayloadType<ToolResultPayload> TOOL_RESULT =
            new PayloadType<>("tool-result", "1", ToolResultPayload.class);
    public static final PayloadType<PlanItemsPayload> PLAN_ITEMS =
            new PayloadType<>("plan-items", "1", PlanItemsPayload.class);
    public static final PayloadType<StringPayload> STRING = new PayloadType<>("string", "1", StringPayload.class);
    public static final PayloadType<RuntimeConfigurationSnapshot> CONFIGURATION =
            new PayloadType<>("runtime-configuration", "1", RuntimeConfigurationSnapshot.class);
    public static final PayloadType<RuntimeCheckpointState> CHECKPOINT_STATE =
            new PayloadType<>("runtime-checkpoint-state", "1", RuntimeCheckpointState.class);
    public static final PayloadType<EventDataPayload> EVENT_DATA =
            new PayloadType<>("runtime-event-data", "1", EventDataPayload.class);
    public static final PayloadType<OutboxPayload> OUTBOX =
            new PayloadType<>("runtime-outbox-payload", "1", OutboxPayload.class);
    public static final PayloadType<CommandResultPayload> COMMAND_RESULT =
            new PayloadType<>("runtime-command-result", "1", CommandResultPayload.class);
    public static final PayloadType<InteractionTargetPayload> INTERACTION_TARGET =
            new PayloadType<>("interaction-target", "1", InteractionTargetPayload.class);
    public static final PayloadType<ConversationSummaryPayload> CONVERSATION_SUMMARY =
            new PayloadType<>("conversation-summary-content", "1", ConversationSummaryPayload.class);
    public static final PayloadType<MemorySelectionPayload> MEMORY_SELECTION =
            new PayloadType<>("memory-selection", "1", MemorySelectionPayload.class);
    public static final PayloadType<StringSetPayload> STRING_SET =
            new PayloadType<>("string-set", "1", StringSetPayload.class);
    public static final PayloadType<BinaryPayload> BINARY =
            new PayloadType<>("protected-binary", "1", BinaryPayload.class);
    public static final PayloadType<SkillActivationPayload> SKILL_ACTIVATION =
            new PayloadType<>("skill-activation", "1", SkillActivationPayload.class);
    public static final PayloadType<io.haifa.agent.policy.api.PolicySnapshot> POLICY_SNAPSHOT =
            new PayloadType<>("policy-snapshot", "1", io.haifa.agent.policy.api.PolicySnapshot.class);
    public static final PayloadType<io.haifa.agent.policy.api.PolicyRequest> POLICY_REQUEST =
            new PayloadType<>("policy-request", "1", io.haifa.agent.policy.api.PolicyRequest.class);
    public static final PayloadType<io.haifa.agent.policy.api.ApprovalRequestContext> APPROVAL_REQUEST_CONTEXT =
            new PayloadType<>("approval-request-context", "1", io.haifa.agent.policy.api.ApprovalRequestContext.class);

    private SqliteRuntimePayloadTypes() {}

    public static VersionedPayloadCodecRegistry create(int maximumPayloadBytes) {
        return VersionedPayloadCodecRegistry.builder(maximumPayloadBytes)
                .register(METADATA)
                .register(AGENT_ERROR)
                .register(RUN_RESULT)
                .register(CONTENT_PARTS)
                .register(STEP_RESULT)
                .register(TOOL_ARGUMENTS)
                .register(TOOL_RESULT)
                .register(PLAN_ITEMS)
                .register(STRING)
                .register(CONFIGURATION)
                .register(CHECKPOINT_STATE)
                .register(EVENT_DATA)
                .register(OUTBOX)
                .register(COMMAND_RESULT)
                .register(INTERACTION_TARGET)
                .register(CONVERSATION_SUMMARY)
                .register(MEMORY_SELECTION)
                .register(STRING_SET)
                .register(BINARY)
                .register(SKILL_ACTIVATION)
                .register(POLICY_SNAPSHOT)
                .register(POLICY_REQUEST)
                .register(APPROVAL_REQUEST_CONTEXT)
                .build();
    }
}

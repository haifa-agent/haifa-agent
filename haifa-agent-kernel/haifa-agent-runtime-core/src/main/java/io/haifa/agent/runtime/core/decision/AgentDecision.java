package io.haifa.agent.runtime.core.decision;

public sealed interface AgentDecision
        permits FinalAnswerDecision, ToolCallDecision, DelegationDecision, InteractionDecision, ContinueDecision {}

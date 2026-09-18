import { describe, expect, it } from "vitest";
import type { Activity, Run, StreamEvent } from "../api/generated";
import { appReducer, initialState } from "./appReducer";

const usage = {
  inputTokens: 1,
  outputTokens: 2,
  totalTokens: 3,
  cachedInputTokens: 0,
  modelCalls: 1,
  toolCalls: 1,
};

describe("appReducer", () => {
  it("rejects a stale run snapshot", () => {
    const current: Run = {
      id: "run-1",
      conversationId: "conversation-1",
      status: "COMPLETED",
      version: 4,
      updatedAt: "2026-07-28T00:00:00Z",
      usage,
    };
    const stale = { ...current, status: "RUNNING", version: 3 };
    const state = { ...initialState, run: current };
    expect(appReducer(state, { type: "runLoaded", run: stale }).run).toEqual(current);
  });

  it("deduplicates activity and stream sequence", () => {
    const activity: Activity = {
      activityId: "activity-1",
      eventId: "event-activity-1",
      runId: "run-1",
      kind: "MCP",
      displayName: "mcp.echo",
      safeTargetSummary: "Local stub",
      status: "SUCCEEDED",
      startedAt: "2026-07-28T00:00:00Z",
      occurredAt: "2026-07-28T00:00:01Z",
      safeResultSummary: "Completed",
      version: 2,
    };
    const event: StreamEvent = {
      eventId: "event-1",
      type: "activity.committed",
      runId: "run-1",
      occurredAt: "2026-07-28T00:00:00Z",
      value: "SUCCEEDED",
      activity,
      source: "durable",
      sequence: 5,
    };
    const once = appReducer(initialState, { type: "streamEvent", event });
    const twice = appReducer(once, { type: "streamEvent", event });
    expect(twice.activities).toHaveLength(1);
    expect(twice.streamSequences.durable).toBe(5);
  });

  it("merges one operation lifecycle without losing its true start time", () => {
    const requested: Activity = {
      activityId: "tool:call-1",
      eventId: "event-requested",
      runId: "run-1",
      kind: "TOOL",
      displayName: "workspace.inspect",
      safeTargetSummary: "Repository",
      status: "REQUESTED",
      requestedAt: "2026-07-28T00:00:00Z",
      occurredAt: "2026-07-28T00:00:00Z",
      safeResultSummary: "",
      version: 2,
    };
    const succeeded: Activity = {
      ...requested,
      eventId: "event-succeeded",
      status: "SUCCEEDED",
      requestedAt: null,
      startedAt: null,
      completedAt: "2026-07-28T00:00:03Z",
      occurredAt: "2026-07-28T00:00:03Z",
      safeResultSummary: "Completed",
      version: 4,
    };

    const first = appReducer(initialState, { type: "activitiesLoaded", activities: [requested] });
    const merged = appReducer(first, { type: "activitiesLoaded", activities: [succeeded] });

    expect(merged.activities).toHaveLength(1);
    expect(merged.activities[0]).toMatchObject({
      activityId: "tool:call-1",
      eventId: "event-succeeded",
      requestedAt: "2026-07-28T00:00:00Z",
      completedAt: "2026-07-28T00:00:03Z",
      status: "SUCCEEDED",
    });
  });

  it("deduplicates each stream source independently and resets a failed generation draft", () => {
    const transient = (type: string, value: string, sequence: number): StreamEvent => ({
      eventId: `transient-${sequence}`,
      type,
      runId: "run-1",
      occurredAt: "2026-07-28T00:00:00Z",
      value,
      source: "transient",
      sequence,
    });
    const durable: StreamEvent = {
      eventId: "durable-1",
      type: "run.status",
      runId: "run-1",
      occurredAt: "2026-07-28T00:00:00Z",
      value: "RUNNING",
      source: "durable",
      sequence: 1,
    };

    const first = appReducer(initialState, {
      type: "streamEvent",
      event: transient("answer.delta", "failed draft", 1),
    });
    const failed = appReducer(first, {
      type: "streamEvent",
      event: transient("answer.failed", "generation-1", 2),
    });
    const durableAfterTransient = appReducer(failed, { type: "streamEvent", event: durable });
    const retried = appReducer(durableAfterTransient, {
      type: "streamEvent",
      event: transient("answer.delta", "clean retry", 3),
    });

    expect(failed.streamDraft).toBe("");
    expect(failed.outputPhase).toBe("idle");
    expect(retried.streamDraft).toBe("clean retry");
    expect(retried.outputPhase).toBe("streaming");
    expect(retried.streamSequences).toEqual({ durable: 1, transient: 3 });
  });

  it("clears current-run projections when a new run is selected", () => {
    const oldRun: Run = {
      id: "run-old",
      conversationId: "conversation-1",
      status: "COMPLETED",
      version: 4,
      updatedAt: "2026-07-28T00:00:00Z",
      usage,
    };
    const nextRun: Run = {
      ...oldRun,
      id: "run-next",
      status: "RUNNING",
      version: 1,
    };
    const state = {
      ...initialState,
      run: oldRun,
      activities: [
        {
          activityId: "old",
          eventId: "event-old",
          runId: oldRun.id,
          kind: "TOOL" as const,
          displayName: "old.tool",
          safeTargetSummary: "Old target",
          status: "SUCCEEDED",
          startedAt: "2026-07-28T00:00:00Z",
          occurredAt: "2026-07-28T00:00:01Z",
          safeResultSummary: "Completed",
          version: 3,
        },
      ],
      streamSequences: { durable: 4, transient: 9 },
      streamDraft: "old",
      outputPhase: "streaming" as const,
    };
    const result = appReducer(state, { type: "runLoaded", run: nextRun });
    expect(result.activities).toEqual([]);
    expect(result.streamSequences).toEqual({ durable: 0, transient: 0 });
    expect(result.streamDraft).toBe("");
    expect(result.outputPhase).toBe("idle");
  });

  it("tracks answer output phases from transient stream events", () => {
    const event = (type: string, sequence: number): StreamEvent => ({
      eventId: `event-${sequence}`,
      type,
      runId: "run-1",
      occurredAt: "2026-07-28T00:00:00Z",
      value: "generation-1",
      source: "transient",
      sequence,
    });

    const started = appReducer(initialState, { type: "streamEvent", event: event("answer.started", 1) });
    const streaming = appReducer(started, {
      type: "streamEvent",
      event: { ...event("answer.delta", 2), value: "Hello" },
    });
    const committed = appReducer(streaming, {
      type: "streamEvent",
      event: event("answer.committed", 3),
    });

    expect(started.outputPhase).toBe("starting");
    expect(streaming.outputPhase).toBe("streaming");
    expect(committed.outputPhase).toBe("idle");
    expect(committed.streamDraft).toBe("Hello");
  });

  it("keeps the streamed answer visible when the run becomes terminal", () => {
    const running: Run = {
      id: "run-live",
      conversationId: "conversation-1",
      status: "RUNNING",
      version: 1,
      updatedAt: "2026-07-28T00:00:00Z",
      usage,
    };
    const completed = { ...running, status: "COMPLETED", version: 2 };
    const state = {
      ...initialState,
      run: running,
      streamDraft: "Complete streamed answer",
    };

    const result = appReducer(state, { type: "runLoaded", run: completed });

    expect(result.streamDraft).toBe("Complete streamed answer");
  });

  it("atomically replaces the streamed answer with its committed assistant turn", () => {
    const completed: Run = {
      id: "run-live",
      conversationId: "conversation-1",
      status: "COMPLETED",
      version: 2,
      updatedAt: "2026-07-28T00:00:00Z",
      usage,
    };
    const state = {
      ...initialState,
      run: completed,
      streamDraft: "Complete streamed answer",
    };

    const result = appReducer(state, {
      type: "turnsLoaded",
      turns: [
        {
          id: "turn-user",
          role: "USER",
          runId: completed.id,
          sequence: 1,
          text: "Question",
          images: [],
          audios: [],
          createdAt: "2026-07-28T00:00:00Z",
        },
        {
          id: "turn-assistant",
          role: "ASSISTANT",
          runId: completed.id,
          sequence: 2,
          text: "Complete committed answer",
          images: [],
          audios: [],
          createdAt: "2026-07-28T00:00:01Z",
        },
      ],
    });

    expect(result.streamDraft).toBe("");
    expect(result.turns.at(-1)?.text).toBe("Complete committed answer");
  });
});

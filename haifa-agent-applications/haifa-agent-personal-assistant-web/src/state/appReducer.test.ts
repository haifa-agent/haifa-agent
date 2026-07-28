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
      runId: "run-1",
      kind: "MCP",
      displayName: "mcp.echo",
      safeTargetSummary: "Local stub",
      status: "SUCCEEDED",
      startedAt: "2026-07-28T00:00:00Z",
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
      sequence: 5,
    };
    const once = appReducer(initialState, { type: "streamEvent", event });
    const twice = appReducer(once, { type: "streamEvent", event });
    expect(twice.activities).toHaveLength(1);
    expect(twice.streamSequence).toBe(5);
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
          runId: oldRun.id,
          kind: "TOOL" as const,
          displayName: "old.tool",
          safeTargetSummary: "Old target",
          status: "SUCCEEDED",
          startedAt: "2026-07-28T00:00:00Z",
          safeResultSummary: "Completed",
          version: 3,
        },
      ],
      streamSequence: 9,
      streamDraft: "old",
    };
    const result = appReducer(state, { type: "runLoaded", run: nextRun });
    expect(result.activities).toEqual([]);
    expect(result.streamSequence).toBe(0);
    expect(result.streamDraft).toBe("");
  });
});

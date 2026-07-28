import { describe, expect, it } from "vitest";
import { bootstrapFixture } from "../data/fixtures";
import { appReducer, createUiState } from "./appReducer";

describe("appReducer", () => {
  it("keeps a follow-up queued while a run is active", () => {
    const state = createUiState(bootstrapFixture);
    const next = appReducer(state, {
      type: "submitMessage",
      text: "完成后再给我一个三行摘要",
      now: "10:40",
    });

    const detail = next.details["session-data-cleanup"];
    expect(detail.run?.id).toBe("run-data-01");
    expect(detail.messages.at(-1)?.state).toBe("queued");
    expect(next.toast).toContain("排队");
  });

  it("applies steer input without starting a second run", () => {
    const state = {
      ...createUiState(bootstrapFixture),
      deliveryMode: "steer" as const,
    };
    const next = appReducer(state, {
      type: "submitMessage",
      text: "只处理前 100 行",
      now: "10:40",
    });

    const detail = next.details["session-data-cleanup"];
    expect(detail.run?.id).toBe("run-data-01");
    expect(detail.messages.at(-1)?.state).toBe("applied");
    expect(detail.run?.activity.at(-1)?.title).toContain("补充");
  });

  it("requires an explicit action to promote a memory candidate", () => {
    const state = createUiState(bootstrapFixture);
    const candidate = state.memoryCandidates[0];
    const next = appReducer(state, {
      type: "approveMemoryCandidate",
      id: candidate.id,
    });

    expect(next.memoryCandidates.some((item) => item.id === candidate.id)).toBe(false);
    expect(next.memories[0].content).toBe(candidate.content);
    expect(next.memories[0].active).toBe(true);
  });

  it("completes an approved run and publishes a logical artifact", () => {
    const state = createUiState(bootstrapFixture);
    const approved = appReducer(state, {
      type: "approveInteraction",
      approved: true,
    });
    const completed = appReducer(approved, { type: "completeApprovedRun" });
    const detail = completed.details["session-data-cleanup"];

    expect(detail.summary.status).toBe("completed");
    expect(detail.artifacts[0].id).toBe("artifact-data-summary");
    expect(detail.artifacts[0].name).not.toMatch(/[\\/]/);
    expect(detail.run?.steps.every((step) => step.state === "completed")).toBe(true);
    expect(detail.tokenUsage).toEqual({
      inputTokens: 6_782,
      outputTokens: 628,
      totalTokens: 7_410,
      cacheReadInputTokens: 5_376,
      modelCalls: 3,
      providerReportedModelCalls: 3,
      updatedAt: "刚刚",
    });
  });
});

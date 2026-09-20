import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { Activity } from "../../api/generated";
import { ActivityFeed } from "./ActivityRightPanel";

const baseActivity: Activity = {
  activityId: "tool:call-1",
  eventId: "event-1",
  runId: "run-1",
  kind: "TOOL",
  displayName: "execution_run",
  safeTargetSummary: "git status",
  status: "SUCCEEDED",
  occurredAt: "2026-07-28T01:00:01Z",
  safeResultSummary: "Completed",
  version: 5,
};

function activity(overrides: Partial<Activity>): Activity {
  return { ...baseActivity, ...overrides };
}

describe("ActivityFeed tool details", () => {
  it("defaults to collapsed and reveals bounded preview, stats and reference on demand", async () => {
    const user = userEvent.setup();
    const item = activity({
      toolDetail: {
        outputPreview: "Command exited (exit 0)",
        truncated: true,
        byteCount: 4_800,
        lineCount: 42,
        truncationReason: "OUTPUT_LINES",
        processState: "EXITED",
        exitCode: 0,
        resultRef: "asset-1",
        outcomeUnknown: false,
      },
    });

    render(<ActivityFeed activities={[item]} emptyText="empty" />);

    expect(screen.queryByText("Command exited (exit 0)")).toBeNull();
    await user.click(screen.getByRole("button", { name: "展开工具详情" }));

    expect(screen.getByText("Command exited (exit 0)")).toBeTruthy();
    expect(screen.getByText(/EXITED/)).toBeTruthy();
    expect(screen.getByText(/退出码：0/)).toBeTruthy();
    expect(screen.getByText(/asset-1/)).toBeTruthy();
    expect(screen.getByText(/已截断/)).toBeTruthy();
  });

  it("keeps the user expand state across streamed activity updates", async () => {
    const user = userEvent.setup();
    const item = activity({
      toolDetail: { outputPreview: "Command exited (exit 0)", truncated: false, byteCount: 24, lineCount: 1 },
    });
    const { rerender } = render(<ActivityFeed activities={[item]} emptyText="empty" />);

    await user.click(screen.getByRole("button", { name: "展开工具详情" }));
    expect(screen.getByText("Command exited (exit 0)")).toBeTruthy();

    rerender(
      <ActivityFeed
        activities={[activity({ ...item, version: 6, status: "SUCCEEDED", safeResultSummary: "Completed" })]}
        emptyText="empty"
      />,
    );

    expect(screen.getByRole("button", { name: "收起工具详情" })).toBeTruthy();
    expect(screen.getByText("Command exited (exit 0)")).toBeTruthy();
  });

  it("marks unknown outcomes explicitly and never shows success", () => {
    const item = activity({
      status: "OUTCOME_UNKNOWN",
      safeResultSummary: "AUTOMATIC_REPLAY_FORBIDDEN",
      toolDetail: { outcomeUnknown: true },
    });

    render(<ActivityFeed activities={[item]} emptyText="empty" />);

    expect(screen.getByText("结果未知，未自动重放。")).toBeTruthy();
    expect(screen.queryByText("Completed")).toBeNull();
  });

  it("shows failure reason without a detail toggle when no bounded detail exists", () => {
    const item = activity({ status: "FAILED", safeResultSummary: "IO_FAILED" });

    render(<ActivityFeed activities={[item]} emptyText="empty" />);

    expect(screen.getByText("IO_FAILED")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "展开工具详情" })).toBeNull();
  });

  it("labels cancelled, timed-out and unknown tools without success wording", () => {
    const cancelled = activity({ activityId: "tool:call-2", status: "CANCELLED", safeResultSummary: "已取消" });
    const timedOut = activity({ activityId: "tool:call-3", status: "TIMEOUT", safeResultSummary: "WALL_TIME_EXCEEDED" });
    const unknown = activity({
      activityId: "tool:call-4",
      status: "OUTCOME_UNKNOWN",
      safeResultSummary: "TOOL_OUTCOME_UNKNOWN",
      toolDetail: { outcomeUnknown: true },
    });

    render(<ActivityFeed activities={[cancelled, timedOut, unknown]} emptyText="empty" />);

    expect(screen.getByText("已停止")).toBeTruthy();
    expect(screen.getByText("已超时")).toBeTruthy();
    expect(screen.getByText("结果未知")).toBeTruthy();
    expect(screen.queryByText("已完成")).toBeNull();
  });
});

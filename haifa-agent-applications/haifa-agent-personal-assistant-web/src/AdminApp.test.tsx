import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import AdminApp from "./AdminApp";
import type { PersonalAdminClient } from "./admin/client";
import type { AdminRun, AdminSession, AdminTrace } from "./admin/types";

const session: AdminSession = {
  id: "session-sensitive",
  status: "ACTIVE",
  createdAt: "2026-07-29T01:00:00Z",
  updatedAt: "2026-07-29T01:01:00Z",
  runCount: 1,
  latestRunStatus: "FAILED",
};

const run: AdminRun = {
  id: "run-failed",
  sessionId: session.id,
  status: "FAILED",
  objective: "diagnose the private prompt",
  createdAt: "2026-07-29T01:00:00Z",
  updatedAt: "2026-07-29T01:01:00Z",
  completedAt: "2026-07-29T01:01:00Z",
  errorCode: "TOOL_FAILED",
};

const trace: AdminTrace = {
  sessionId: session.id,
  runId: run.id,
  root: {
    id: `run:${run.id}`,
    parentId: null,
    kind: "run",
    label: "Run run-failed",
    status: "FAILED",
    startedAt: run.createdAt,
    completedAt: run.completedAt,
    durationMillis: 60_000,
    sequence: null,
    summary: "TOOL_FAILED",
    details: { objective: run.objective },
  },
  nodes: [
    {
      id: "tool:tool-call-1",
      parentId: `run:${run.id}`,
      kind: "tool",
      label: "personal_checklist",
      status: "FAILED",
      startedAt: run.createdAt,
      completedAt: run.completedAt,
      durationMillis: 1_200,
      sequence: 3,
      summary: "TOOL_FAILED",
      details: {
        arguments: {
          value: { values: { secretPrompt: "完整敏感 Prompt", token: "tool-secret" } },
        },
        error: { value: { code: "TOOL_FAILED", message: "原始工具错误" } },
      },
    },
  ],
  failureNodeId: "tool:tool-call-1",
};

function client(): PersonalAdminClient {
  return {
    sessions: vi.fn(async () => [session]),
    runs: vi.fn(async () => [run]),
    trace: vi.fn(async () => trace),
  };
}

describe("Personal Assistant Admin application", () => {
  beforeEach(() => {
    window.history.replaceState(null, "", "/admin/");
  });

  it("loads one session run tree and focuses the failed node with complete raw content", async () => {
    const api = client();
    render(<AdminApp client={api} />);

    expect(await screen.findByText("已定位到失败节点")).toBeTruthy();
    expect(screen.getAllByText("personal_checklist").length).toBeGreaterThan(0);
    expect(screen.getByText(/完整敏感 Prompt/)).toBeTruthy();
    expect(screen.getByText(/tool-secret/)).toBeTruthy();
    expect(screen.getByText(/原始工具错误/)).toBeTruthy();
    expect(api.sessions).toHaveBeenCalled();
    expect(api.runs).toHaveBeenCalledWith(session.id, expect.any(AbortSignal));
    expect(api.trace).toHaveBeenCalledWith(session.id, run.id, expect.any(AbortSignal));
    expect(new URL(window.location.href).searchParams.get("runId")).toBe(run.id);
  });

  it("refreshes only through the Admin client", async () => {
    const api = client();
    render(<AdminApp client={api} />);
    await screen.findByText("已定位到失败节点");

    fireEvent.click(screen.getByRole("button", { name: "刷新" }));

    await waitFor(() => expect(api.sessions).toHaveBeenCalledTimes(2));
  });
});

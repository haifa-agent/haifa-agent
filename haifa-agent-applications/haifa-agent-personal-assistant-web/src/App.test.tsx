import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type {
  Activity,
  Bootstrap,
  Conversation,
  Interaction,
  Memory,
  MemoryCandidate,
  Run,
  Turn,
} from "./api/generated";
import {
  PersonalAssistantApiError,
  type PersonalAssistantClient,
} from "./api/client";
import App from "./App";

const conversation: Conversation = {
  id: "conversation-1",
  displayName: "每日计划",
  status: "ACTIVE",
  activeRunId: null,
  createdAt: "2026-07-28T01:00:00Z",
  lastActivityAt: "2026-07-28T02:00:00Z",
  revision: 3,
};
const turns: Turn[] = [
  {
    id: "turn-1",
    role: "USER",
    runId: "run-1",
    sequence: 1,
    text: "请整理今天的待办",
    createdAt: "2026-07-28T01:00:00Z",
  },
  {
    id: "turn-2",
    role: "ASSISTANT",
    runId: "run-1",
    sequence: 2,
    text: "已经按优先级整理完成。",
    createdAt: "2026-07-28T01:00:01Z",
  },
];
const run: Run = {
  id: "run-1",
  conversationId: conversation.id,
  status: "COMPLETED",
  version: 8,
  updatedAt: "2026-07-28T01:00:01Z",
  output: "已经按优先级整理完成。",
  resultSummary: "Completed",
  errorCode: null,
  usage: {
    inputTokens: 39934,
    outputTokens: 1409,
    totalTokens: 41343,
    cachedInputTokens: 27776,
    modelCalls: 2,
    toolCalls: 1,
  },
};
const activity: Activity = {
  activityId: "activity-1",
  runId: run.id,
  kind: "TOOL",
  displayName: "checklist.verify",
  safeTargetSummary: "Current checklist",
  status: "SUCCEEDED",
  startedAt: "2026-07-28T01:00:00Z",
  completedAt: "2026-07-28T01:00:01Z",
  safeResultSummary: "Completed",
  interactionRef: null,
  version: 5,
};
const candidate: MemoryCandidate = {
  id: "candidate-1",
  kind: "PREFERENCE",
  subjectKey: "travel",
  content: "国内出行优先选择高铁。",
  status: "PENDING",
  updatedAt: "2026-07-28T01:00:00Z",
  revision: 1,
};
const memory: Memory = {
  id: "memory-1",
  version: 1,
  kind: "PREFERENCE",
  subjectKey: "writing",
  content: "回答保持简洁。",
  status: "ACTIVE",
  createdAt: "2026-07-28T01:00:00Z",
  updatedAt: "2026-07-28T01:00:00Z",
};
const bootstrap: Bootstrap = {
  product: "Haifa Personal Assistant",
  apiVersion: "v1",
  connection: "connected",
  caller: "public-user",
  capabilities: ["conversation", "tool", "skill", "mcp", "memory", "usage", "sse"],
  assemblyDigest: "safe-digest",
};

function client(): PersonalAssistantClient {
  return {
    bootstrap: vi.fn(async () => bootstrap),
    conversations: vi.fn(async () => [conversation]),
    createConversation: vi.fn(async () => conversation),
    conversation: vi.fn(async () => conversation),
    updateConversation: vi.fn(async () => conversation),
    turns: vi.fn(async () => turns),
    submitMessage: vi.fn(async () => conversation),
    run: vi.fn(async () => run),
    cancelRun: vi.fn(async () => ({ ...run, status: "CANCELLED" })),
    activities: vi.fn(async () => [activity]),
    interaction: vi.fn(async () => null),
    respondToInteraction: vi.fn(async () => ({
      responseId: "response-1",
      interactionId: "interaction-1",
      runId: run.id,
      status: "ACCEPTED",
      interactionState: "RESOLVED",
      revision: 2,
      runVersion: 9,
    })),
    memoryCandidates: vi.fn(async () => [candidate]),
    memories: vi.fn(async () => [memory]),
    approveMemory: vi.fn(async () => memory),
    rejectMemory: vi.fn(async () => ({ ...candidate, status: "REJECTED" })),
    invalidateMemory: vi.fn(async () => ({ ...memory, status: "INVALIDATED" })),
    streamRun: vi.fn(async () => undefined),
  };
}

describe("Personal Assistant application", () => {
  beforeEach(() => {
    window.history.replaceState(null, "", "/");
  });

  it("renders authoritative run usage and safe activity", async () => {
    render(<App client={client()} />);
    expect(await screen.findByText("每日计划")).toBeTruthy();
    expect(await screen.findByText("checklist.verify")).toBeTruthy();
    const usage = screen.getByLabelText("本次运行 Token 消耗");
    expect(usage.textContent).toContain("39,934");
    expect(usage.textContent).toContain("1,409");
    expect(usage.textContent).toContain("41,343");
    expect(screen.queryByText("Follow-up")).toBeNull();
    expect(screen.queryByText("Steer")).toBeNull();
    expect(screen.queryByText("Run Diagnostics")).toBeNull();
    expect(screen.queryByText("运行诊断")).toBeNull();
  });

  it("restores the conversation selected by the URL on refresh", async () => {
    const requested = {
      ...conversation,
      id: "conversation-2",
      displayName: "URL selected conversation",
    };
    window.history.replaceState(null, "", `/?conversationId=${requested.id}`);
    const api = client();
    vi.mocked(api.conversations).mockResolvedValue([conversation, requested]);
    vi.mocked(api.conversation).mockImplementation(async (id) =>
      id === requested.id ? requested : conversation,
    );
    vi.mocked(api.turns).mockResolvedValue([]);

    render(<App client={api} />);

    expect(
      await screen.findByRole("heading", { level: 1, name: requested.displayName }),
    ).toBeTruthy();
    expect(api.conversation).toHaveBeenCalledWith(requested.id, expect.any(AbortSignal));
    expect(new URL(window.location.href).searchParams.get("conversationId")).toBe(
      requested.id,
    );
  });

  it("writes an explicitly selected conversation to browser history", async () => {
    const second = {
      ...conversation,
      id: "conversation-2",
      displayName: "Second conversation",
    };
    const api = client();
    vi.mocked(api.conversations).mockResolvedValue([conversation, second]);
    vi.mocked(api.conversation).mockImplementation(async (id) =>
      id === second.id ? second : conversation,
    );

    render(<App client={api} />);
    await screen.findByRole("heading", { level: 1, name: conversation.displayName });

    fireEvent.click(screen.getByText(second.displayName).closest("button")!);

    expect(
      await screen.findByRole("heading", { level: 1, name: second.displayName }),
    ).toBeTruthy();
    expect(new URL(window.location.href).searchParams.get("conversationId")).toBe(
      second.id,
    );
  });

  it("keeps a running conversation usable when an optional activity snapshot is invalid", async () => {
    const active = { ...conversation, activeRunId: "run-active" };
    const activeRun = { ...run, id: "run-active", status: "RUNNING" };
    const api = client();
    vi.mocked(api.conversations).mockResolvedValue([active]);
    vi.mocked(api.conversation).mockResolvedValue(active);
    vi.mocked(api.run).mockResolvedValue(activeRun);
    vi.mocked(api.activities).mockRejectedValue(
      new PersonalAssistantApiError(
        400,
        "INVALID_REQUEST",
        "The request is invalid.",
        "refresh-correlation",
      ),
    );
    vi.mocked(api.streamRun).mockImplementation(async (_runId, handlers, signal) => {
      handlers.onOpen?.();
      await new Promise<void>((resolve) =>
        signal.addEventListener("abort", () => resolve(), { once: true }),
      );
    });

    render(<App client={api} />);

    expect(
      await screen.findByRole("heading", { level: 1, name: active.displayName }),
    ).toBeTruthy();
    await waitFor(() => expect(api.activities).toHaveBeenCalledWith(activeRun.id, expect.any(AbortSignal)));
    expect(screen.queryByText(/INVALID_REQUEST/)).toBeNull();
    expect(screen.getByPlaceholderText("当前任务运行中")).toBeTruthy();
  });

  it("renders Markdown and LaTeX in conversation turns", async () => {
    const api = client();
    vi.mocked(api.turns).mockResolvedValue([
      turns[0],
      {
        ...turns[1],
        text: "## Summary\n\nEnergy is $E = mc^2$.",
      },
    ]);
    const { container } = render(<App client={api} />);

    expect(await screen.findByRole("heading", { level: 2, name: "Summary" })).toBeTruthy();
    expect(container.querySelector(".message-content .katex")).toBeTruthy();
  });

  it("opens memory management without writing on page load", async () => {
    const api = client();
    render(<App client={api} />);
    await screen.findByText("每日计划");
    expect(api.approveMemory).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: /记忆/ }));
    expect(await screen.findByRole("dialog", { name: "记忆管理" })).toBeTruthy();
    expect(screen.getByText("国内出行优先选择高铁。")).toBeTruthy();
    expect(api.approveMemory).not.toHaveBeenCalled();
  });

  it("disables the composer while a run is active", async () => {
    const active = {
      ...conversation,
      activeRunId: "run-active",
    };
    const activeRun = { ...run, id: "run-active", status: "RUNNING" };
    const api = client();
    vi.mocked(api.conversations).mockResolvedValue([active]);
    vi.mocked(api.conversation).mockResolvedValue(active);
    vi.mocked(api.run).mockResolvedValue(activeRun);
    render(<App client={api} />);

    const composer = await screen.findByPlaceholderText("当前任务运行中");
    await waitFor(() => expect(composer.hasAttribute("disabled")).toBe(true));
    expect(screen.getByText(/当前任务运行中，完成或停止后可继续输入/)).toBeTruthy();
  });

  it("shows the exact high-risk execution approval content", async () => {
    const active = { ...conversation, activeRunId: "run-approval" };
    const waiting = { ...run, id: "run-approval", status: "WAITING_APPROVAL" };
    const interaction: Interaction = {
      id: "interaction-execution",
      runId: waiting.id,
      conversationId: conversation.id,
      revision: 1,
      kind: "approval",
      state: "PENDING",
      title: "Approve execution",
      safePrompt:
        "Mode: SCRIPT\nLanguage: powershell\nPurpose: inspect CPU\nRisks: HIGH\nFull content:\nGet-CimInstance Win32_Processor",
      allowedActions: ["approve", "reject"],
      inputType: "NONE",
      maximumCharacters: 0,
      createdAt: "2026-07-28T01:00:00Z",
      expiresAt: "2026-07-28T02:00:00Z",
    };
    const api = client();
    vi.mocked(api.conversations).mockResolvedValue([active]);
    vi.mocked(api.conversation).mockResolvedValue(active);
    vi.mocked(api.run).mockResolvedValue(waiting);
    vi.mocked(api.interaction).mockResolvedValue(interaction);

    const { container } = render(<App client={api} />);

    expect((await screen.findAllByText("Approve execution")).length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText(/Get-CimInstance Win32_Processor/)).not.toHaveLength(0);
    expect(container.querySelector(".execution-risk-badge")).toBeTruthy();
  });

  it("refreshes the approval snapshot when SSE reports an interaction change", async () => {
    const active = { ...conversation, activeRunId: "run-stream-approval" };
    const waiting = { ...run, id: "run-stream-approval", status: "WAITING_APPROVAL" };
    const interaction: Interaction = {
      id: "interaction-stream-execution",
      runId: waiting.id,
      conversationId: conversation.id,
      revision: 1,
      kind: "approval",
      state: "PENDING",
      title: "Approve execution",
      safePrompt: "Risks: HIGH\nFull content:\nGet-Date",
      allowedActions: ["approve", "reject"],
      inputType: "NONE",
      maximumCharacters: 0,
      createdAt: "2026-07-28T01:00:00Z",
      expiresAt: "2026-07-28T02:00:00Z",
    };
    const api = client();
    vi.mocked(api.conversations).mockResolvedValue([active]);
    vi.mocked(api.conversation).mockResolvedValue(active);
    vi.mocked(api.run).mockResolvedValue(waiting);
    vi.mocked(api.interaction).mockResolvedValueOnce(null).mockResolvedValue(interaction);
    vi.mocked(api.streamRun).mockImplementation(async (_runId, handlers, signal) => {
      handlers.onOpen?.();
      handlers.onEvent({
        eventId: "event-interaction",
        type: "interaction.status",
        runId: waiting.id,
        occurredAt: "2026-07-28T01:00:01Z",
        value: "WAITING_APPROVAL",
        sequence: 1,
      });
      await new Promise<void>((resolve) =>
        signal.addEventListener("abort", () => resolve(), { once: true }),
      );
    });

    render(<App client={api} />);

    expect((await screen.findAllByText(/Get-Date/)).length).toBeGreaterThanOrEqual(1);
    await waitFor(() => expect(api.interaction).toHaveBeenCalledTimes(2));
  });

  it("reconciles the committed assistant turn after a terminal stream event", async () => {
    const activeConversation = { ...conversation, activeRunId: "run-live", revision: 4 };
    const settledConversation = { ...conversation, activeRunId: null, revision: 5 };
    const runningRun = { ...run, id: "run-live", status: "RUNNING", version: 1 };
    const completedRun = { ...runningRun, status: "COMPLETED", version: 2 };
    const userTurn = { ...turns[0], runId: "run-live" };
    const assistantTurn = {
      ...turns[1],
      runId: "run-live",
      text: "Final committed assistant answer",
    };
    let resolveCommittedTurns!: (value: Turn[]) => void;
    const committedTurns = new Promise<Turn[]>((resolve) => {
      resolveCommittedTurns = resolve;
    });
    const api = client();
    vi.mocked(api.conversations)
      .mockResolvedValueOnce([activeConversation])
      .mockResolvedValue([settledConversation]);
    vi.mocked(api.conversation)
      .mockResolvedValueOnce(activeConversation)
      .mockResolvedValue(settledConversation);
    vi.mocked(api.turns)
      .mockResolvedValueOnce([userTurn])
      .mockReturnValue(committedTurns);
    vi.mocked(api.run)
      .mockResolvedValueOnce(runningRun)
      .mockResolvedValue(completedRun);
    vi.mocked(api.streamRun).mockImplementation(async (_runId, handlers) => {
      handlers.onOpen?.();
      handlers.onEvent({
        eventId: "event-answer",
        type: "answer.delta",
        runId: "run-live",
        occurredAt: "2026-07-28T01:00:00Z",
        value: "Complete streamed assistant answer",
        sequence: 9,
      });
      handlers.onEvent({
        eventId: "event-final",
        type: "run.status",
        runId: "run-live",
        occurredAt: "2026-07-28T01:00:01Z",
        value: "COMPLETED",
        sequence: 10,
      });
    });

    render(<App client={api} />);

    expect(await screen.findByText("Complete streamed assistant answer")).toBeTruthy();
    await waitFor(() => expect(api.turns).toHaveBeenCalledTimes(2));
    expect(screen.getByText("Complete streamed assistant answer")).toBeTruthy();

    await act(async () => resolveCommittedTurns([userTurn, assistantTurn]));

    expect(await screen.findByText("Final committed assistant answer")).toBeTruthy();
    expect(screen.queryByText("Complete streamed assistant answer")).toBeNull();
    expect(api.conversation).toHaveBeenCalledTimes(2);
  });
});

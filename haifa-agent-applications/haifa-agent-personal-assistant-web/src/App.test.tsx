import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
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

const model = {
  id: "personal-chat",
  displayName: "Personal Chat",
  providerId: "deepseek",
  providerDisplayName: "DeepSeek",
  capabilities: ["TEXT_CHAT", "TOOL_CALLING"],
  contextWindow: 64000,
};
const proModel = {
  ...model,
  id: "deepseek-v4-pro",
  displayName: "DeepSeek V4 Pro",
};
const flashModel = {
  ...model,
  id: "deepseek-v4-flash",
  displayName: "DeepSeek V4 Flash",
};
const bailianModel = {
  ...model,
  id: "qwen-plus",
  displayName: "Qwen Plus",
  providerId: "bailian",
  providerDisplayName: "阿里云百炼",
};
const conversation: Conversation = {
  id: "conversation-1",
  displayName: "每日计划",
  status: "ACTIVE",
  activeRunId: null,
  createdAt: "2026-07-28T01:00:00Z",
  lastActivityAt: "2026-07-28T02:00:00Z",
  revision: 3,
  model: { model, revision: 0, available: true },
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
const modelActivity: Activity = {
  activityId: "model-activity-1",
  runId: run.id,
  kind: "MODEL",
  displayName: "deepseek-chat",
  safeTargetSummary: "deepseek · iteration 1 · attempt 1",
  status: "SUCCEEDED",
  startedAt: "2026-07-28T00:59:59Z",
  completedAt: "2026-07-28T01:00:00Z",
  safeResultSummary: "Input 39,934 · Output 1,409",
  interactionRef: null,
  version: 3,
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
  defaultModelId: model.id,
  models: [model],
};

function client(): PersonalAssistantClient {
  return {
    bootstrap: vi.fn(async () => bootstrap),
    conversations: vi.fn(async () => [conversation]),
    createConversation: vi.fn(async () => conversation),
    selectModel: vi.fn(async (_conversation, modelId) => ({
      model: [model, proModel, flashModel, bailianModel]
        .find((candidate) => candidate.id === modelId) ?? model,
      revision: 1,
      available: true,
    })),
    conversation: vi.fn(async () => conversation),
    updateConversation: vi.fn(async () => conversation),
    turns: vi.fn(async () => turns),
    submitMessage: vi.fn(async () => conversation),
    recommendedQuestions: vi.fn(async () => ({ questions: [] })),
    run: vi.fn(async () => run),
    cancelRun: vi.fn(async () => ({ ...run, status: "CANCELLED" })),
    activities: vi.fn(async () => [modelActivity, activity]),
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
    expect(await screen.findByText("deepseek-chat")).toBeTruthy();
    expect(screen.getByText("Input 39,934 · Output 1,409")).toBeTruthy();
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

  it("renders a typed safe execution error and diagnostic id", async () => {
    const api = client();
    vi.mocked(api.run).mockResolvedValue({
      ...run,
      status: "FAILED",
      errorCode: "RUN_BUDGET_EXCEEDED",
      error: {
        code: "RUN_BUDGET_EXCEEDED",
        message: "Run budget exceeded",
        category: "RESOURCE_LIMIT",
        retryability: "NOT_RETRYABLE",
        details: { resource: "modelCalls", limit: 2, used: 2 },
        diagnosticId: "diag-budget",
        occurredAt: "2026-07-28T01:00:01Z",
      },
    });

    render(<App client={api} />);

    expect(await screen.findByText(/\[RUN_BUDGET_EXCEEDED\] Run budget exceeded/)).toBeTruthy();
    expect(screen.getByText(/诊断编号：diag-budget/)).toBeTruthy();
    expect(screen.getByText(/缩小任务范围后重新发起/)).toBeTruthy();
    expect(screen.queryByText(/java\.|Exception|stack/i)).toBeNull();
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

  it("renders contextual questions below the completed answer and submits a clicked question", async () => {
    const api = client();
    vi.mocked(api.recommendedQuestions).mockResolvedValue({
      questions: [
        "哪些待办最适合安排在上午？",
        "如何为这些待办设置提醒？",
        "能否按预计耗时重新排序？",
      ],
    });
    render(<App client={api} />);

    const question = await screen.findByRole("button", { name: "哪些待办最适合安排在上午？" });
    expect(question.closest(".message.assistant")).toBeTruthy();
    expect(screen.getByRole("region", { name: "推荐问题" })).toBeTruthy();

    fireEvent.click(question);

    await waitFor(() =>
      expect(api.submitMessage).toHaveBeenCalledWith(
        conversation,
        "哪些待办最适合安排在上午？",
        expect.objectContaining({ idempotencyKey: expect.any(String) }),
      ),
    );
  });

  it("copies a complete assistant answer and an individual code block", async () => {
    const answer = [
      "## PowerShell",
      "",
      "运行以下命令：",
      "",
      "```powershell",
      "Get-Process | Select-Object -First 1",
      "```",
    ].join("\n");
    const writeText = vi.fn(async () => undefined);
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText },
    });
    const api = client();
    vi.mocked(api.turns).mockResolvedValue([
      turns[0],
      { ...turns[1], text: answer },
    ]);
    render(<App client={api} />);

    await screen.findByRole("heading", { level: 2, name: "PowerShell" });
    fireEvent.click(screen.getByRole("button", { name: "复制完整回答" }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(answer));
    expect(screen.getByRole("button", { name: "完整回答已复制" })).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "复制代码" }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(
      "Get-Process | Select-Object -First 1",
    ));
    expect(screen.getByRole("button", { name: "代码已复制" })).toBeTruthy();
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

  it("opens slash commands and selects a model by provider then model", async () => {
    const api = client();
    vi.mocked(api.bootstrap).mockResolvedValue({
      ...bootstrap,
      defaultModelId: flashModel.id,
      models: [proModel, flashModel, bailianModel],
    });

    render(<App client={api} />);

    const composer = await screen.findByPlaceholderText("输入消息或 / 命令，Enter 发送");
    expect(screen.queryByRole("combobox", { name: "选择模型" })).toBeNull();
    fireEvent.change(composer, { target: { value: "/" } });
    expect(await screen.findByRole("dialog", { name: "命令功能" })).toBeTruthy();

    fireEvent.click(screen.getByRole("option", { name: /选择模型/ }));
    expect(await screen.findByRole("dialog", { name: "选择模型厂商" })).toBeTruthy();

    fireEvent.click(screen.getByRole("option", { name: /DeepSeek.*2 个可用模型/ }));
    const modelDialog = await screen.findByRole("dialog", { name: "选择 DeepSeek 模型" });
    fireEvent.click(within(modelDialog).getByRole("option", { name: /DeepSeek V4 Pro/ }));

    await waitFor(() => expect(api.selectModel).toHaveBeenCalledWith(
      conversation,
      proModel.id,
      { idempotencyKey: expect.any(String) },
    ));
    expect((composer as HTMLTextAreaElement).value).toBe("");
  });

  it("uses the slash-selected model when creating a new conversation", async () => {
    const api = client();
    vi.mocked(api.bootstrap).mockResolvedValue({
      ...bootstrap,
      defaultModelId: flashModel.id,
      models: [proModel, flashModel],
    });

    render(<App client={api} />);

    await screen.findByText("每日计划");
    fireEvent.click(screen.getByRole("button", { name: "新建会话" }));
    const composer = await screen.findByPlaceholderText("输入消息或 / 命令，Enter 发送");
    fireEvent.change(composer, { target: { value: "/" } });
    fireEvent.click(await screen.findByRole("option", { name: /选择模型/ }));
    fireEvent.click(await screen.findByRole("option", { name: /DeepSeek.*2 个可用模型/ }));
    const modelDialog = await screen.findByRole("dialog", { name: "选择 DeepSeek 模型" });
    fireEvent.click(within(modelDialog).getByRole("option", { name: /DeepSeek V4 Pro/ }));

    fireEvent.change(composer, { target: { value: "使用所选模型开始对话" } });
    fireEvent.keyDown(composer, { key: "Enter" });

    await waitFor(() => expect(api.createConversation).toHaveBeenCalledWith(
      "使用所选模型开始对话",
      "使用所选模型开始对话",
      { idempotencyKey: expect.any(String) },
      proModel.id,
    ));
  });

  it("defaults slash model selection to DeepSeek Flash", async () => {
    const api = client();
    vi.mocked(api.bootstrap).mockResolvedValue({
      ...bootstrap,
      defaultModelId: flashModel.id,
      models: [proModel, flashModel, bailianModel],
    });

    render(<App client={api} />);

    await screen.findByText("每日计划");
    fireEvent.click(screen.getByRole("button", { name: "新建会话" }));
    const composer = await screen.findByPlaceholderText("输入消息或 / 命令，Enter 发送");
    fireEvent.change(composer, { target: { value: "/" } });
    fireEvent.keyDown(composer, { key: "Enter" });

    const providerDialog = await screen.findByRole("dialog", { name: "选择模型厂商" });
    expect(within(providerDialog)
      .getByRole("option", { name: /DeepSeek.*2 个可用模型/ })
      .getAttribute("aria-selected")).toBe("true");
    fireEvent.keyDown(composer, { key: "Enter" });

    const modelDialog = await screen.findByRole("dialog", { name: "选择 DeepSeek 模型" });
    expect(within(modelDialog)
      .getByRole("option", { name: /DeepSeek V4 Flash/ })
      .getAttribute("aria-selected")).toBe("true");
    fireEvent.keyDown(composer, { key: "Enter" });

    fireEvent.change(composer, { target: { value: "使用默认模型开始对话" } });
    fireEvent.keyDown(composer, { key: "Enter" });

    await waitFor(() => expect(api.createConversation).toHaveBeenCalledWith(
      "使用默认模型开始对话",
      "使用默认模型开始对话",
      { idempotencyKey: expect.any(String) },
      flashModel.id,
    ));
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
    const { container } = render(<App client={api} />);

    const composer = await screen.findByPlaceholderText("当前任务运行中");
    await waitFor(() => expect(composer.hasAttribute("disabled")).toBe(true));
    expect(screen.getByText(/当前任务运行中，完成或停止后可继续输入/)).toBeTruthy();
    const cancelButton = screen.getByRole("button", { name: "停止当前任务" });
    expect(cancelButton.closest(".run-heading-row")).toBeTruthy();
    expect(cancelButton.querySelector("span")?.textContent).toBe("停止当前任务");
    expect(container.querySelector(".active-run-note button")).toBeNull();
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

    expect(await screen.findByRole("heading", { name: "Approve execution" })).toBeTruthy();
    expect(screen.getByText(/Get-CimInstance Win32_Processor/)).toBeTruthy();
    expect(screen.getByRole("heading", { name: "审批内容" })).toBeTruthy();
    expect(screen.getByRole("heading", { name: "审批选项" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "批准" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "拒绝" })).toBeTruthy();
    expect(container.querySelector(".execution-risk-badge")).toBeTruthy();
    expect(container.querySelector(".messages > .interaction-card")).toBeTruthy();
    expect(container.querySelector(".activity-panel .interaction-card")).toBeNull();
  });

  it("renders an approval without waiting for the activities snapshot", async () => {
    const active = { ...conversation, activeRunId: "run-fast-approval" };
    const waiting = { ...run, id: "run-fast-approval", status: "WAITING_APPROVAL" };
    const interaction: Interaction = {
      id: "interaction-fast-approval",
      runId: waiting.id,
      conversationId: conversation.id,
      revision: 1,
      kind: "approval",
      state: "PENDING",
      title: "Approve immediately",
      safePrompt: "Risks: HIGH\nFull content:\nGet-Date",
      allowedActions: ["approve", "reject"],
      inputType: "NONE",
      maximumCharacters: 0,
      createdAt: "2026-07-28T01:00:00Z",
      expiresAt: "2026-07-28T02:00:00Z",
    };
    let resolveActivities!: (activities: Activity[]) => void;
    const activitiesPending = new Promise<Activity[]>((resolve) => {
      resolveActivities = resolve;
    });
    const api = client();
    vi.mocked(api.conversations).mockResolvedValue([active]);
    vi.mocked(api.conversation).mockResolvedValue(active);
    vi.mocked(api.run).mockResolvedValue(waiting);
    vi.mocked(api.activities).mockReturnValue(activitiesPending);
    vi.mocked(api.interaction).mockResolvedValue(interaction);
    vi.mocked(api.streamRun).mockImplementation(async (_runId, _handlers, signal) => {
      await new Promise<void>((resolve) =>
        signal.addEventListener("abort", () => resolve(), { once: true }),
      );
    });

    render(<App client={api} />);

    expect(await screen.findByRole("heading", { name: "Approve immediately" })).toBeTruthy();
    expect(api.activities).toHaveBeenCalled();
    await act(async () => resolveActivities([activity]));
  });

  it("previews long approval content and expands it on demand", async () => {
    const active = { ...conversation, activeRunId: "run-long-approval" };
    const waiting = { ...run, id: "run-long-approval", status: "WAITING_APPROVAL" };
    const longContent = [
      "Mode: SCRIPT",
      "Language: powershell",
      "Purpose: inspect services",
      "Risks: HIGH",
      "Full content:",
      ...Array.from({ length: 24 }, (_, index) => `Write-Output "line-${index + 1}"`),
      "END-OF-APPROVAL-CONTENT",
    ].join("\n");
    const interaction: Interaction = {
      id: "interaction-long-execution",
      runId: waiting.id,
      conversationId: conversation.id,
      revision: 1,
      kind: "approval",
      state: "PENDING",
      title: "Approve long script",
      safePrompt: longContent,
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

    render(<App client={api} />);

    const expand = await screen.findByRole("button", {
      name: "展开查看全部内容",
    });
    expect(expand.getAttribute("aria-expanded")).toBe("false");
    expect(screen.queryByText(/END-OF-APPROVAL-CONTENT/)).toBeNull();

    fireEvent.click(expand);

    expect(await screen.findByText(/END-OF-APPROVAL-CONTENT/)).toBeTruthy();
    const collapse = screen.getByRole("button", { name: "收起内容" });
    expect(collapse.getAttribute("aria-expanded")).toBe("true");
    fireEvent.click(collapse);
    await waitFor(() => expect(screen.queryByText(/END-OF-APPROVAL-CONTENT/)).toBeNull());
  });

  it("shows a visible error when a waiting approval cannot be loaded", async () => {
    const active = { ...conversation, activeRunId: "run-approval-error" };
    const waiting = { ...run, id: "run-approval-error", status: "WAITING_APPROVAL" };
    const api = client();
    vi.mocked(api.conversations).mockResolvedValue([active]);
    vi.mocked(api.conversation).mockResolvedValue(active);
    vi.mocked(api.run).mockResolvedValue(waiting);
    vi.mocked(api.interaction).mockRejectedValue(
      new PersonalAssistantApiError(400, "INVALID_REQUEST", "The request is invalid.", "correlation-1"),
    );

    render(<App client={api} />);

    expect((await screen.findByRole("alert")).textContent).toContain(
      "审批或交互详情加载失败，请重试；任务尚未继续执行。",
    );
    expect(screen.queryByRole("button", { name: "Approve" })).toBeNull();
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
    let resolveInitialInteraction!: (interaction: Interaction | null) => void;
    const initialInteractionPending = new Promise<Interaction | null>((resolve) => {
      resolveInitialInteraction = resolve;
    });
    const api = client();
    vi.mocked(api.conversations).mockResolvedValue([active]);
    vi.mocked(api.conversation).mockResolvedValue(active);
    vi.mocked(api.run).mockResolvedValue(waiting);
    vi.mocked(api.interaction)
      .mockReturnValueOnce(initialInteractionPending)
      .mockResolvedValue(interaction);
    vi.mocked(api.streamRun).mockImplementation(async (_runId, handlers, signal) => {
      handlers.onOpen?.();
      handlers.onEvent({
        eventId: "event-interaction",
        type: "interaction.status",
        runId: waiting.id,
        occurredAt: "2026-07-28T01:00:01Z",
        value: "WAITING_APPROVAL",
        source: "durable",
        sequence: 1,
      });
      await new Promise<void>((resolve) =>
        signal.addEventListener("abort", () => resolve(), { once: true }),
      );
    });

    render(<App client={api} />);

    expect((await screen.findAllByText(/Get-Date/)).length).toBeGreaterThanOrEqual(1);
    await waitFor(() => expect(api.interaction).toHaveBeenCalledTimes(2));
    await act(async () => resolveInitialInteraction(null));
    expect(screen.getByRole("heading", { name: "Approve execution" })).toBeTruthy();
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
        source: "transient",
        sequence: 9,
      });
      handlers.onEvent({
        eventId: "event-final",
        type: "run.status",
        runId: "run-live",
        occurredAt: "2026-07-28T01:00:01Z",
        value: "COMPLETED",
        source: "durable",
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

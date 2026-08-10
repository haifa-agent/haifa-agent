import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type {
  Activity,
  Bootstrap,
  Conversation,
  Interaction,
  Memory,
  MemoryCandidate,
  MissionSnapshot,
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
    images: [],
    createdAt: "2026-07-28T01:00:00Z",
  },
  {
    id: "turn-2",
    role: "ASSISTANT",
    runId: "run-1",
    sequence: 2,
    text: "已经按优先级整理完成。",
    images: [],
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
  eventId: "event-activity-1",
  runId: run.id,
  kind: "TOOL",
  displayName: "checklist.verify",
  safeTargetSummary: "Current checklist",
  status: "SUCCEEDED",
  startedAt: "2026-07-28T01:00:00Z",
  completedAt: "2026-07-28T01:00:01Z",
  occurredAt: "2026-07-28T01:00:01Z",
  safeResultSummary: "Completed",
  interactionRef: null,
  version: 5,
};
const modelActivity: Activity = {
  activityId: "model-activity-1",
  eventId: "event-model-activity-1",
  runId: run.id,
  kind: "MODEL",
  displayName: "deepseek-chat",
  safeTargetSummary: "deepseek · iteration 1 · attempt 1",
  status: "SUCCEEDED",
  startedAt: "2026-07-28T00:59:59Z",
  completedAt: "2026-07-28T01:00:00Z",
  occurredAt: "2026-07-28T01:00:00Z",
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
const missionTask: MissionSnapshot["tasks"][number] = {
  taskId: "task-1",
  ordinal: 1,
  title: "准备交付",
  objective: "整理明确的交付内容",
  acceptanceCriteria: ["可验收"],
  dependsOn: [],
  taskType: "GENERAL",
  requiredSkillIds: [],
  resultSchemaId: "pa.task-result",
  resultSchemaVersion: "v1",
  state: "PLANNED",
};
const mission: MissionSnapshot = {
  schemaVersion: "pa.mission-snapshot/v1",
  missionId: "mission-1",
  conversationId: conversation.id,
  objective: "交付一份可验收的计划",
  acceptanceCriteria: ["计划明确"],
  constraints: { maxTasks: 8, maxDependencyDepth: 4 },
  mode: "STANDARD",
  researchBrief: null,
  selectedSkillId: null,
  selectedSkillBinding: null,
  state: "WAITING_CONFIRMATION",
  plan: {
    revision: 1,
    schemaId: "pa.mission-plan",
    schemaVersion: "v1",
    tasks: [missionTask],
    plannerSessionId: null,
    plannerRunId: null,
    createdAt: "2026-08-08T00:00:00Z",
  },
  tasks: [missionTask],
  blocker: null,
  artifacts: [],
  sources: [],
  finalResult: null,
  version: 1,
  createdAt: "2026-08-08T00:00:00Z",
  updatedAt: "2026-08-08T00:00:00Z",
  confirmedAt: null,
  finishedAt: null,
  pollAfterMs: 5000,
  execution: {
    dispatcherStatus: "READY",
    recovering: false,
    allTasksSettled: false,
    completedTasks: 0,
    blockedTasks: 0,
    currentTaskId: null,
    latestAttempt: null,
  },
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
    Object.defineProperty(navigator, "onLine", { configurable: true, value: true });
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

  it("opens the explicit Mission workspace and confirms a fixed plan", async () => {
    const api = {
      ...client(),
      bootstrap: vi.fn(async () => ({ ...bootstrap, capabilities: [...bootstrap.capabilities, "mission"] })),
      missions: vi.fn(async () => ({ items: [mission], nextCursor: null })),
      missionSnapshot: vi.fn(async () => mission),
      confirmMission: vi.fn(async () => ({ ...mission, state: "RUNNING" as const, version: 2 })),
      cancelMission: vi.fn(async () => ({ ...mission, state: "CANCELLED" as const, version: 2 })),
      replaceMissionPlan: vi.fn(async () => ({ ...mission, version: 2 })),
      createMission: vi.fn(async () => mission),
    } satisfies PersonalAssistantClient;

    render(<App client={api} />);

    expect(await screen.findByText("交付一份可验收的计划")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Mission" }));
    const dialog = await screen.findByRole("dialog", { name: "Mission" });
    fireEvent.click(await within(dialog).findByRole("button", { name: /交付一份可验收的计划/ }));
    const taskButton = await within(dialog).findByRole("button", { name: /准备交付/ });
    fireEvent.click(taskButton);
    const taskDetail = within(dialog).getByRole("complementary", { name: "计划任务详情" });
    expect(within(taskDetail).getByRole("heading", { name: "准备交付" })).toBeTruthy();
    expect(within(taskDetail).getByText("整理明确的交付内容")).toBeTruthy();
    expect(within(taskDetail).getByText("可验收")).toBeTruthy();
    expect(within(taskDetail).queryByText(/Skill|Schema|pa\.task-result/)).toBeNull();
    fireEvent.click(within(dialog).getByRole("button", { name: "编辑计划" }));
    const planEditor = within(dialog).getByLabelText("完整计划 JSON") as HTMLTextAreaElement;
    expect(planEditor.value).not.toMatch(/requiredSkillIds|taskType|resultSchemaId|deep-research/);
    fireEvent.click(within(dialog).getByRole("button", { name: "取消编辑" }));
    fireEvent.click(screen.getByRole("button", { name: /确认计划/ }));
    await waitFor(() => expect(api.confirmMission).toHaveBeenCalledWith(
      expect.objectContaining({ missionId: "mission-1", version: 1 }),
      expect.objectContaining({ idempotencyKey: expect.any(String) }),
    ));
    expect((await screen.findAllByText("计划已确认")).length).toBeGreaterThan(0);
  });

  it("requires acceptance criteria before creating a Mission", async () => {
    const api = {
      ...client(),
      bootstrap: vi.fn(async () => ({ ...bootstrap, capabilities: [...bootstrap.capabilities, "mission"] })),
      missions: vi.fn(async () => ({ items: [], nextCursor: null })),
      createMission: vi.fn(async () => mission),
    } satisfies PersonalAssistantClient;

    render(<App client={api} />);
    fireEvent.click(await screen.findByRole("button", { name: "Mission" }));
    const dialog = await screen.findByRole("dialog", { name: "Mission" });
    const objectiveInput = within(dialog).getByLabelText("目标");
    const criteriaInput = within(dialog).getByLabelText("验收标准（必填，1～20 条）");
    const createButton = within(dialog).getByRole("button", { name: "创建并生成计划" });

    fireEvent.change(objectiveInput, { target: { value: "以太坊过去3年重要的技术迭代" } });
    expect((criteriaInput as HTMLTextAreaElement).required).toBe(true);
    expect((createButton as HTMLButtonElement).disabled).toBe(true);
    expect(api.createMission).not.toHaveBeenCalled();

    fireEvent.change(criteriaInput, { target: { value: "列出重要升级、时间及其技术影响" } });
    await waitFor(() => expect((createButton as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(createButton);

    await waitFor(() => expect(api.createMission).toHaveBeenCalledWith(
      expect.objectContaining({
        objective: "以太坊过去3年重要的技术迭代",
        acceptanceCriteria: ["列出重要升级、时间及其技术影响"],
      }),
      expect.objectContaining({ idempotencyKey: expect.any(String) }),
    ));
  });

  it("shows execution progress and retries a blocked Mission task", async () => {
    const blocked = {
      ...mission,
      state: "WAITING_USER" as const,
      version: 4,
      tasks: [{ ...missionTask, state: "BLOCKED" as const }],
      execution: {
        ...mission.execution,
        completedTasks: 0,
        blockedTasks: 1,
        currentTaskId: "task-1",
        latestAttempt: {
          taskId: "task-1",
          attemptNo: 2,
          state: "FAILED" as const,
          sessionId: "session-1",
          runId: "run-1",
          failureCode: "TASK_RUN_FAILED",
          updatedAt: "2026-08-08T00:00:01Z",
        },
      },
    };
    const api = {
      ...client(),
      bootstrap: vi.fn(async () => ({ ...bootstrap, capabilities: [...bootstrap.capabilities, "mission"] })),
      missions: vi.fn(async () => ({ items: [blocked], nextCursor: null })),
      missionSnapshot: vi.fn(async () => blocked),
      interaction: vi.fn(async () => null),
      retryMissionTask: vi.fn(async () => ({ ...blocked, state: "RUNNING" as const, version: 5 })),
    } satisfies PersonalAssistantClient;

    render(<App client={api} />);
    fireEvent.click(await screen.findByRole("button", { name: "Mission" }));
    const dialog = await screen.findByRole("dialog", { name: "Mission" });
    fireEvent.click(await within(dialog).findByRole("button", { name: /交付一份可验收的计划/ }));
    expect(await within(dialog).findByText("已完成 0/1")).toBeTruthy();
    fireEvent.click(within(dialog).getByRole("button", { name: "重试任务" }));
    await waitFor(() => expect(api.retryMissionTask).toHaveBeenCalledWith(
      expect.objectContaining({ missionId: "mission-1", version: 4 }),
      "task-1",
      expect.objectContaining({ idempotencyKey: expect.any(String) }),
    ));
  });

  it("explains a planning dependency-depth failure without exposing Skill details", async () => {
    const failed: MissionSnapshot = {
      ...mission,
      state: "FAILED",
      blocker: "MISSION_PLAN_DEPENDENCY_DEPTH_EXCEEDED",
      tasks: [],
      selectedSkillId: "deep-research",
      selectedSkillBinding: "product/personal-assistant-bundled@1/deep-research@2.0.0#sha256:test",
    };
    const api = {
      ...client(),
      bootstrap: vi.fn(async () => ({ ...bootstrap, capabilities: [...bootstrap.capabilities, "mission"] })),
      missions: vi.fn(async () => ({ items: [failed], nextCursor: null })),
      missionSnapshot: vi.fn(async () => failed),
    } satisfies PersonalAssistantClient;

    render(<App client={api} />);
    fireEvent.click(await screen.findByRole("button", { name: "Mission" }));
    const dialog = await screen.findByRole("dialog", { name: "Mission" });

    expect(await within(dialog).findByText("Mission 规划失败：任务依赖层级超过限制。")).toBeTruthy();
    expect(within(dialog).queryByText(/deep-research@|执行 Skill/)).toBeNull();
  });

  it("renders a partial Deep Research delivery with evidence and artifacts", async () => {
    const delivered: MissionSnapshot = {
      ...mission,
      mode: "DEEP_RESEARCH",
      selectedSkillId: "deep-research",
      selectedSkillBinding: "product/personal-assistant-bundled@1/deep-research@1.0.0#sha256:test",
      state: "PARTIALLY_COMPLETED",
      sources: ["https://research.stub/source-1"],
      artifacts: ["artifact-report"],
      finalResult: JSON.stringify({
        directAnswer: "Bounded evidence summary",
        completionKind: "PARTIAL",
        completedItems: ["Primary evidence checked"],
        failedItems: ["Secondary evidence unavailable"],
        unverifiedClaims: ["claim-unverified"],
        residualRisks: ["Evidence may change"],
        unresolvedQuestions: ["When will the dataset refresh?"],
      }),
    };
    const api = {
      ...client(),
      bootstrap: vi.fn(async () => ({ ...bootstrap, capabilities: [...bootstrap.capabilities, "mission"] })),
      missions: vi.fn(async () => ({ items: [delivered], nextCursor: null })),
      missionSnapshot: vi.fn(async () => delivered),
    } satisfies PersonalAssistantClient;

    render(<App client={api} />);
    fireEvent.click(await screen.findByRole("button", { name: "Mission" }));
    const dialog = await screen.findByRole("dialog", { name: "Mission" });

    expect(await within(dialog).findByText("Bounded evidence summary")).toBeTruthy();
    expect(within(dialog).getByText("Secondary evidence unavailable")).toBeTruthy();
    expect(within(dialog).getByText("claim-unverified")).toBeTruthy();
    expect(within(dialog).getByText("https://research.stub/source-1")).toBeTruthy();
    expect(within(dialog).getByText("artifact-report")).toBeTruthy();
    expect(within(dialog).queryByText(/deep-research@|执行 Skill/)).toBeNull();
  });

  it("renders a degraded v2 delivery with report view download and copy actions", async () => {
    const writeText = vi.fn(async () => undefined);
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText } });
    const delivered: MissionSnapshot = {
      ...mission,
      mode: "DEEP_RESEARCH",
      selectedSkillId: "deep-research",
      selectedSkillBinding: "product/personal-assistant-bundled@1/deep-research@2.0.0#sha256:test",
      state: "PARTIALLY_COMPLETED",
      artifacts: ["artifact-report", "artifact-delivery"],
      finalResult: JSON.stringify({
        schemaVersion: "pa.research-delivery/v2",
        completionKind: "PARTIAL",
        degraded: true,
        degradationReasons: ["REPORT_REQUIRED_SECTION_MISSING"],
        affectedTaskIds: ["task-1"],
        reportArtifactRef: { artifactId: "artifact-report", title: "research-report.md" },
        qualityGate: { passed: false, failedChecks: ["REPORT_REQUIRED_SECTION_MISSING"] },
      }),
    };
    const api = {
      ...client(),
      bootstrap: vi.fn(async () => ({ ...bootstrap, capabilities: [...bootstrap.capabilities, "mission"] })),
      missions: vi.fn(async () => ({ items: [delivered], nextCursor: null })),
      missionSnapshot: vi.fn(async () => delivered),
      missionArtifact: vi.fn(async () => "# 完整研究报告\n\n正文"),
    } satisfies PersonalAssistantClient;

    render(<App client={api} />);
    fireEvent.click(await screen.findByRole("button", { name: "Mission" }));
    const dialog = await screen.findByRole("dialog", { name: "Mission" });

    expect(await within(dialog).findByText(/调研降级完成/)).toBeTruthy();
    expect(within(dialog).getByText("报告缺少必要章节")).toBeTruthy();
    expect(within(dialog).getByText((_content, element) => element?.tagName === "P"
      && element.textContent?.includes("受影响任务：") === true
      && element.textContent.includes("task-1"))).toBeTruthy();
    expect(within(dialog).getByRole("link", { name: "查看完整报告" }).getAttribute("href"))
      .toContain("/missions/mission-1/artifacts/artifact-report");
    expect(within(dialog).getByRole("link", { name: "下载 Markdown" }).getAttribute("download"))
      .toBe("research-report.md");
    fireEvent.click(within(dialog).getByRole("button", { name: /复制完整报告/ }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith("# 完整研究报告\n\n正文"));
    expect(await within(dialog).findByRole("button", { name: /已复制/ })).toBeTruthy();
  });

  it("fails closed for an unknown research delivery version", async () => {
    const delivered: MissionSnapshot = {
      ...mission,
      state: "COMPLETED",
      finalResult: JSON.stringify({
        schemaVersion: "pa.research-delivery/v999",
        completionKind: "COMPLETE",
        directAnswer: "must not be rendered",
      }),
    };
    const api = {
      ...client(),
      bootstrap: vi.fn(async () => ({ ...bootstrap, capabilities: [...bootstrap.capabilities, "mission"] })),
      missions: vi.fn(async () => ({ items: [delivered], nextCursor: null })),
      missionSnapshot: vi.fn(async () => delivered),
    } satisfies PersonalAssistantClient;

    render(<App client={api} />);
    fireEvent.click(await screen.findByRole("button", { name: "Mission" }));
    const dialog = await screen.findByRole("dialog", { name: "Mission" });
    expect(await within(dialog).findByText("最终报告版本不受支持")).toBeTruthy();
    expect(within(dialog).queryByText("must not be rendered")).toBeNull();
  });

  it("announces offline Mission state and restores focus when Escape closes the dialog", async () => {
    Object.defineProperty(navigator, "onLine", { configurable: true, value: false });
    const running = { ...mission, state: "RUNNING" as const };
    const api = {
      ...client(),
      bootstrap: vi.fn(async () => ({ ...bootstrap, capabilities: [...bootstrap.capabilities, "mission"] })),
      missions: vi.fn(async () => ({ items: [running], nextCursor: null })),
      missionSnapshot: vi.fn(async () => running),
    } satisfies PersonalAssistantClient;

    render(<App client={api} />);
    const open = await screen.findByRole("button", { name: "Mission" });
    fireEvent.click(open);
    const dialog = await screen.findByRole("dialog", { name: "Mission" });
    expect(within(dialog).getByRole("status").textContent).toContain("当前离线");
    fireEvent.keyDown(dialog, { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Mission" })).toBeNull());
    await waitFor(() => expect(document.activeElement).toBe(open));
  });

  it("retries the Mission list after a reachable browser loses and regains the Server", async () => {
    vi.useFakeTimers();
    try {
      let dialogAttempts = 0;
      const missions = vi.fn(async (conversationId?: string) => {
        if (conversationId) return { items: [], nextCursor: null };
        if (dialogAttempts++ === 0) throw new TypeError("Failed to fetch");
        return { items: [], nextCursor: null };
      });
      const api = {
        ...client(),
        bootstrap: vi.fn(async () => ({ ...bootstrap, capabilities: [...bootstrap.capabilities, "mission"] })),
        missions,
      } satisfies PersonalAssistantClient;

      render(<App client={api} />);
      await act(async () => {
        await Promise.resolve();
        await Promise.resolve();
      });
      fireEvent.click(screen.getByRole("button", { name: "Mission" }));
      await act(async () => {
        await Promise.resolve();
        await Promise.resolve();
      });
      const dialog = screen.getByRole("dialog", { name: "Mission" });
      expect(within(dialog).getByRole("status").textContent).toContain("暂时无法同步");

      await act(async () => {
        await vi.advanceTimersByTimeAsync(2_000);
      });
      expect(missions).toHaveBeenCalledTimes(3);
      expect(within(dialog).getByRole("status").textContent).toContain("Mission 状态已同步");
    } finally {
      vi.useRealTimers();
    }
  });

  it("scrolls the activity panel to the latest live event", async () => {
    const activeConversation = { ...conversation, activeRunId: "run-live-activity" };
    const activeRun = { ...run, id: "run-live-activity", status: "RUNNING", version: 1 };
    const liveActivity = {
      ...activity,
      activityId: "activity-live",
      runId: activeRun.id,
      displayName: "workspace.inspect",
      version: 1,
    };
    let emitActivity!: () => void;
    const api = client();
    vi.mocked(api.conversations).mockResolvedValue([activeConversation]);
    vi.mocked(api.conversation).mockResolvedValue(activeConversation);
    vi.mocked(api.run).mockResolvedValue(activeRun);
    vi.mocked(api.activities).mockResolvedValue([]);
    vi.mocked(api.streamRun).mockImplementation(async (_runId, handlers, signal) => {
      handlers.onOpen?.();
      emitActivity = () => handlers.onEvent({
        eventId: "event-live-activity",
        type: "activity.committed",
        runId: activeRun.id,
        occurredAt: "2026-07-28T01:00:01Z",
        value: "SUCCEEDED",
        activity: liveActivity,
        source: "durable",
        sequence: 1,
      });
      await new Promise<void>((resolve) =>
        signal.addEventListener("abort", () => resolve(), { once: true }),
      );
    });

    const { container } = render(<App client={api} />);
    await waitFor(() => expect(api.streamRun).toHaveBeenCalled());
    const panel = container.querySelector<HTMLElement>(".activity-panel")!;
    Object.defineProperty(panel, "scrollHeight", { configurable: true, value: 640 });
    panel.scrollTop = 0;

    await act(async () => emitActivity());

    expect(await screen.findByText("workspace.inspect")).toBeTruthy();
    expect(panel.scrollTop).toBe(640);
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

    expect((await screen.findAllByText(/\[RUN_BUDGET_EXCEEDED\] Run budget exceeded/)).length).toBe(2);
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

  it("adds an HTTPS image URL and an uploaded image to one message", async () => {
    const imageModel = { ...model, id: "openai-image", capabilities: ["TEXT_CHAT", "IMAGE_INPUT"] };
    const imageConversation = {
      ...conversation,
      model: { model: imageModel, revision: 0, available: true },
    };
    const api = client();
    vi.mocked(api.bootstrap).mockResolvedValue({ ...bootstrap, defaultModelId: imageModel.id, models: [imageModel] });
    vi.mocked(api.conversations).mockResolvedValue([imageConversation]);
    vi.mocked(api.conversation).mockResolvedValue(imageConversation);
    vi.mocked(api.submitMessage).mockResolvedValue(imageConversation);
    api.uploadImage = vi.fn(async () => ({
      imageId: "11111111-1111-4111-8111-111111111111",
      mediaType: "image/png",
      sizeBytes: 9,
      originalFilename: "cat.png",
      sha256: `sha256:${"a".repeat(64)}`,
    }));
    const { container } = render(<App client={api} />);

    expect(screen.queryByRole("textbox", { name: "图片 URL" })).toBeNull();
    fireEvent.click(await screen.findByRole("button", { name: "添加图片" }));
    const imageDialog = screen.getByRole("dialog", { name: "添加图片" });
    fireEvent.click(within(imageDialog).getByRole("button", { name: /^添加图片 URL/ }));
    const url = within(imageDialog).getByRole("textbox", { name: "图片 URL" });
    fireEvent.change(url, { target: { value: "https://images.example.test/cat.png" } });
    fireEvent.click(within(imageDialog).getByRole("button", { name: "确认添加图片 URL" }));
    const file = new File([new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10, 1])], "cat.png", {
      type: "image/png",
    });
    fireEvent.change(container.querySelector('input[type="file"]')!, { target: { files: [file] } });
    expect(await screen.findByText("cat.png")).toBeTruthy();
    expect(within(screen.getByRole("region", { name: "待发送图片" })).getByText("2/4")).toBeTruthy();

    fireEvent.change(screen.getByRole("textbox", { name: "给个人助理发送消息" }), {
      target: { value: "描述这些图片" },
    });
    fireEvent.click(screen.getByRole("button", { name: "发送消息" }));

    await waitFor(() => expect(api.submitMessage).toHaveBeenCalledWith(
      imageConversation,
      "描述这些图片",
      expect.objectContaining({ idempotencyKey: expect.any(String) }),
      [
        { kind: "url", url: "https://images.example.test/cat.png", imageId: undefined },
        { kind: "upload", url: undefined, imageId: "11111111-1111-4111-8111-111111111111" },
      ],
    ));

    await waitFor(() => expect(screen.queryByRole("region", { name: "待发送图片" })).toBeNull());
    fireEvent.click(screen.getByRole("button", { name: "添加图片" }));
    const secondDialog = screen.getByRole("dialog", { name: "添加图片" });
    fireEvent.click(within(secondDialog).getByRole("button", { name: /^添加图片 URL/ }));
    fireEvent.change(within(secondDialog).getByRole("textbox", { name: "图片 URL" }), {
      target: { value: "https://images.example.test/architecture.png" },
    });
    fireEvent.click(within(secondDialog).getByRole("button", { name: "确认添加图片 URL" }));
    fireEvent.click(screen.getByRole("button", { name: /^解释图片/ }));
    expect((screen.getByRole("textbox", { name: "给个人助理发送消息" }) as HTMLTextAreaElement).value)
      .toBe("请解释这张图片");
    fireEvent.click(screen.getByRole("button", { name: "发送消息" }));

    await waitFor(() => expect(api.submitMessage).toHaveBeenNthCalledWith(
      2,
      imageConversation,
      "请解释这张图片",
      expect.objectContaining({ idempotencyKey: expect.any(String) }),
      [{ kind: "url", url: "https://images.example.test/architecture.png", imageId: undefined }],
    ));
  });

  it("closes the image URL input with its close control and closes the menu with Escape", async () => {
    const imageModel = { ...model, id: "openai-image", capabilities: ["TEXT_CHAT", "IMAGE_INPUT"] };
    const imageConversation = {
      ...conversation,
      model: { model: imageModel, revision: 0, available: true },
    };
    const api = client();
    vi.mocked(api.bootstrap).mockResolvedValue({ ...bootstrap, defaultModelId: imageModel.id, models: [imageModel] });
    vi.mocked(api.conversations).mockResolvedValue([imageConversation]);
    vi.mocked(api.conversation).mockResolvedValue(imageConversation);
    render(<App client={api} />);

    fireEvent.click(await screen.findByRole("button", { name: "添加图片" }));
    const imageDialog = screen.getByRole("dialog", { name: "添加图片" });
    fireEvent.click(within(imageDialog).getByRole("button", { name: /^添加图片 URL/ }));
    expect(within(imageDialog).getByRole("textbox", { name: "图片 URL" })).toBeTruthy();
    fireEvent.click(within(imageDialog).getByRole("button", { name: "关闭图片 URL" }));
    expect(screen.queryByRole("textbox", { name: "图片 URL" })).toBeNull();

    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("dialog", { name: "添加图片" })).toBeNull();
  });

  it("renders sent images inside the user message without exposing opaque ids", async () => {
    const opaqueId = "11111111-1111-4111-8111-111111111111";
    const api = client();
    vi.mocked(api.turns).mockResolvedValue([
      {
        ...turns[0],
        images: [
          {
            kind: "url",
            url: "https://images.example.test/blue-vase.png",
            imageId: null,
            mediaType: null,
            sizeBytes: 0,
            originalFilename: "",
          },
          {
            kind: "upload",
            url: null,
            imageId: opaqueId,
            mediaType: "image/png",
            sizeBytes: 9,
            originalFilename: `${opaqueId}.png`,
          },
        ],
      },
      turns[1],
    ]);

    const { container } = render(<App client={api} />);

    await screen.findByRole("img", { name: "第 1 张图片" });
    const message = container.querySelector(".messages > .message.user")!;
    expect(message.querySelector('.turn-images[aria-label="消息包含 2 张图片"]')).toBeTruthy();
    expect(within(message as HTMLElement).getByRole("img", { name: "第 1 张图片" })).toBeTruthy();
    expect(within(message as HTMLElement).getByText("已上传图片 2")).toBeTruthy();
    expect(screen.queryByText(`${opaqueId}.png`)).toBeNull();
    expect(container.querySelector(".activity-panel .turn-images")).toBeNull();
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
    expect(await screen.findByRole("button", { name: "代码已复制" })).toBeTruthy();
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
    vi.mocked(api.activities).mockResolvedValue([]);
    const { container } = render(<App client={api} />);

    const composer = await screen.findByPlaceholderText("当前任务运行中");
    await waitFor(() => expect(composer.hasAttribute("disabled")).toBe(true));
    expect(screen.getByText("任务运行中")).toBeTruthy();
    const liveRunCard = container.querySelector(".live-run-card");
    expect(liveRunCard?.querySelector(".live-run-copy")?.textContent).toBe("任务运行中");
    expect(liveRunCard?.querySelector(".live-run-secondary .live-run-label")?.textContent).toBe("当前任务");
    expect(liveRunCard?.querySelector(".live-run-secondary .live-run-progress")).toBeTruthy();
    const detailsButton = screen.getByRole("button", { name: /查看运行详情/ });
    expect(detailsButton.classList.contains("live-run-action-details")).toBe(true);
    fireEvent.click(detailsButton);
    const activityPanel = screen.getByLabelText("当前运行详情");
    await waitFor(() => expect(document.activeElement).toBe(activityPanel));
    expect(activityPanel.classList.contains("activity-panel-attention")).toBe(true);
    const cancelButton = screen.getByRole("button", { name: "停止当前任务" });
    expect(cancelButton.closest(".run-heading-row")).toBeTruthy();
    expect(cancelButton.querySelector("span")?.textContent).toBe("停止当前任务");
    expect(container.querySelector(".active-run-note button")).toBeNull();
    expect(container.querySelector(".active-run-note")).toBeNull();
  });

  it("offers one-click resend when a failed run produced no assistant output", async () => {
    const failedRun: Run = {
      ...run,
      id: "run-failed-without-output",
      status: "FAILED",
      output: "",
      resultSummary: null,
      errorCode: "MODEL_CALL_FAILED",
    };
    const failedTurn: Turn = {
      id: "turn-failed-user",
      role: "USER",
      runId: failedRun.id,
      sequence: 1,
      text: "请重新处理这条消息",
      images: [],
      createdAt: "2026-07-28T01:00:00Z",
    };
    const api = client();
    vi.mocked(api.turns).mockResolvedValue([failedTurn]);
    vi.mocked(api.run).mockResolvedValue(failedRun);
    vi.mocked(api.activities).mockResolvedValue([]);

    render(<App client={api} />);

    const resend = await screen.findByRole("button", { name: "重新发送上一条失败消息" });
    fireEvent.click(resend);

    await waitFor(() => expect(api.submitMessage).toHaveBeenCalledWith(
      conversation,
      failedTurn.text,
      { idempotencyKey: expect.any(String) },
    ));
  });

  it("shows the latest safe activity in the live run card", async () => {
    const active = { ...conversation, activeRunId: "run-tool" };
    const activeRun = { ...run, id: "run-tool", status: "RUNNING", version: 1 };
    const startedActivity: Activity = {
      ...activity,
      activityId: "activity-tool-started",
      runId: activeRun.id,
      displayName: "workspace.inspect",
      safeTargetSummary: "Repository source files",
      safeResultSummary: "",
      status: "STARTED",
      startedAt: new Date(Date.now()).toISOString(),
      completedAt: null,
      version: 9,
    };
    const api = client();
    vi.mocked(api.conversations).mockResolvedValue([active]);
    vi.mocked(api.conversation).mockResolvedValue(active);
    vi.mocked(api.run).mockResolvedValue(activeRun);
    vi.mocked(api.activities).mockResolvedValue([startedActivity]);

    const { container } = render(<App client={api} />);

    await waitFor(() => expect(container.querySelector(".live-run-card")).toBeTruthy());
    const liveCard = container.querySelector<HTMLElement>(".live-run-card")!;
    expect(within(liveCard).getByText("正在运行 workspace.inspect")).toBeTruthy();
    expect(within(liveCard).getByText("Repository source files")).toBeTruthy();
    expect(within(liveCard).getByText("耗时 1秒")).toBeTruthy();
  });

  it("shows authoritative plan progress when the run has real todo data", async () => {
    const active = { ...conversation, activeRunId: "run-plan" };
    const plannedRun: Run = {
      ...run,
      id: "run-plan",
      status: "RUNNING",
      version: 3,
      plan: {
        id: "plan-1",
        objective: "Inspect repository",
        revision: 2,
        updatedAt: "2026-07-28T01:00:00Z",
        items: [
          { id: "todo-1", title: "读取结构", priority: "HIGH", status: "COMPLETED" },
          { id: "todo-2", title: "分析实现", priority: "HIGH", status: "IN_PROGRESS" },
        ],
      },
    };
    const api = client();
    vi.mocked(api.conversations).mockResolvedValue([active]);
    vi.mocked(api.conversation).mockResolvedValue(active);
    vi.mocked(api.run).mockResolvedValue(plannedRun);
    vi.mocked(api.activities).mockResolvedValue([]);

    const { container } = render(<App client={api} />);

    await waitFor(() => expect(container.querySelector(".live-run-progress")).toBeTruthy());
    const progress = container.querySelector<HTMLElement>(".live-run-progress")!;
    expect(within(progress).getByText("计划步骤 1/2 · 当前：分析实现")).toBeTruthy();
    expect(progress.getAttribute("aria-label")).toContain("计划步骤 1/2");
  });

  it("shows answer generation as a higher-priority live state", async () => {
    const active = { ...conversation, activeRunId: "run-answer" };
    const activeRun = { ...run, id: "run-answer", status: "RUNNING", version: 1 };
    const api = client();
    vi.mocked(api.conversations).mockResolvedValue([active]);
    vi.mocked(api.conversation).mockResolvedValue(active);
    vi.mocked(api.run).mockResolvedValue(activeRun);
    vi.mocked(api.activities).mockResolvedValue([]);
    vi.mocked(api.streamRun).mockImplementation(async (_runId, handlers, signal) => {
      handlers.onOpen?.();
      handlers.onEvent({
        eventId: "event-answer-started",
        type: "answer.started",
        runId: activeRun.id,
        occurredAt: "2026-07-28T01:00:00Z",
        value: "generation-1",
        source: "transient",
        sequence: 1,
      });
      await new Promise<void>((resolve) =>
        signal.addEventListener("abort", () => resolve(), { once: true }),
      );
    });

    render(<App client={api} />);

    expect(await screen.findByText("正在生成回答")).toBeTruthy();
    expect(screen.getByText("模型正在准备首段内容")).toBeTruthy();
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
    const interactionCard = container.querySelector<HTMLElement>(".messages > .interaction-card");
    expect(interactionCard).toBeTruthy();
    expect(container.querySelector(".activity-panel .interaction-card")).toBeNull();
    const liveCard = container.querySelector<HTMLElement>(".live-run-card");
    expect(liveCard).toBeTruthy();
    expect(within(liveCard!).getByText("需要你的审批")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /查看并处理/ }));
    expect(document.activeElement).toBe(interactionCard);
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

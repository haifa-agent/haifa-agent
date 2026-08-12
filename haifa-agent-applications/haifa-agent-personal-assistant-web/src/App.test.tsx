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
  ReplaceMissionPlan,
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
  capabilities: ["conversation", "tool", "skill", "mcp", "memory", "usage", "web-research", "sse"],
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

const researchPlanTasks: MissionSnapshot["tasks"] = [
  { ...missionTask, taskId: "history-evolution", ordinal: 1, title: "技术发展沿革", objective: "梳理版本演进与关键节点", acceptanceCriteria: ["形成可核验时间线"], dependsOn: [], taskType: "RESEARCH", requiredSkillIds: ["deep-research"], resultSchemaId: "pa.research-task-result" },
  { ...missionTask, taskId: "current-status-operation", ordinal: 2, title: "当前能力与生态", objective: "整理当前能力与生态现状", acceptanceCriteria: ["核心指标标注年份"], dependsOn: ["history-evolution"], taskType: "RESEARCH", requiredSkillIds: ["deep-research"], resultSchemaId: "pa.research-task-result" },
  { ...missionTask, taskId: "governance-security-compatibility", ordinal: 3, title: "治理、安全与兼容性", objective: "核验治理、安全与兼容性边界", acceptanceCriteria: ["重大判断引用权威来源"], dependsOn: ["history-evolution"], taskType: "RESEARCH", requiredSkillIds: ["deep-research"], resultSchemaId: "pa.research-task-result" },
  { ...missionTask, taskId: "performance-cost-evidence", ordinal: 4, title: "性能、成本与实证数据", objective: "整理选型比较关键指标", acceptanceCriteria: ["形成指标清单"], dependsOn: ["current-status-operation", "governance-security-compatibility"], taskType: "RESEARCH", requiredSkillIds: ["deep-research"], resultSchemaId: "pa.research-task-result" },
  { ...missionTask, taskId: "selection-framework-recommendation", ordinal: 5, title: "选型框架与综合建议", objective: "形成选型建议与验证清单", acceptanceCriteria: ["给出三种场景建议"], dependsOn: ["performance-cost-evidence"], taskType: "RESEARCH", requiredSkillIds: ["deep-research"], resultSchemaId: "pa.research-task-result" },
];

const researchMission: MissionSnapshot = {
  ...mission,
  missionId: "mission-research",
  objective: "研究主流开源数据库过去三年的技术演进与适用场景",
  acceptanceCriteria: ["形成有来源支持的选型分析"],
  mode: "DEEP_RESEARCH",
  researchBrief: {
    question: "主流开源数据库分别适合哪些技术场景？",
    scope: "主流开源数据库及其生态",
    timeRange: "过去三年",
    region: "全球",
    audience: "技术决策者",
    sourcePreferences: ["官方文档与独立基准测试"],
    exclusions: ["无来源营销材料"],
    deliveryFormat: "中文 Markdown 报告",
  },
  selectedSkillId: "deep-research",
  selectedSkillBinding: "product/bundled/deep-research@2.2.0#sha256:test",
  plan: {
    ...mission.plan!,
    tasks: researchPlanTasks,
  },
  tasks: researchPlanTasks,
  version: 3,
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
    await waitFor(() => expect(window.location.pathname).toBe("/missions/mission-1"));
    expect(new URLSearchParams(window.location.search).get("conversationId")).toBe("conversation-1");
    fireEvent.click(await within(dialog).findByRole("button", { name: /交付一份可验收的计划/ }));
    const taskButton = await within(dialog).findByRole("button", { name: /准备交付/ });
    fireEvent.click(taskButton);
    const taskDetail = within(dialog).getByRole("complementary", { name: "Mission 详情面板" });
    expect(within(taskDetail).getByRole("heading", { name: "准备交付" })).toBeTruthy();
    expect(within(taskDetail).getByText("整理明确的交付内容")).toBeTruthy();
    expect(within(taskDetail).getByText("可验收")).toBeTruthy();
    expect(within(taskDetail).queryByText(/Skill|Schema|pa\.task-result/)).toBeNull();
    fireEvent.click(within(taskDetail).getByRole("button", { name: "关闭任务详情" }));
    expect(within(dialog).queryByRole("complementary", { name: "Mission 详情面板" })).toBeNull();
    fireEvent.click(taskButton);
    fireEvent.click(within(dialog).getByRole("button", { name: "适度调整计划" }));
    const planEditor = within(dialog).getByRole("region", { name: "适度调整计划" });
    expect((within(planEditor).getByLabelText("任务标题") as HTMLInputElement).value).toBe("准备交付");
    expect(within(planEditor).queryByText(/requiredSkillIds|taskType|resultSchemaId|deep-research/)).toBeNull();
    fireEvent.click(within(planEditor).getByRole("button", { name: "退出调整" }));
    fireEvent.click(screen.getByRole("button", { name: /确认计划/ }));
    await waitFor(() => expect(api.confirmMission).toHaveBeenCalledWith(
      expect.objectContaining({ missionId: "mission-1", version: 1 }),
      expect.objectContaining({ idempotencyKey: expect.any(String) }),
    ));
    expect((await screen.findAllByText("执行中")).length).toBeGreaterThan(0);
    fireEvent.click(within(dialog).getByRole("button", { name: "回到对话" }));
    expect(window.location.pathname).toBe("/");
  });

  it("routes the deep-research slash command to a prefilled Mission draft without starting a normal run", async () => {
    const api = {
      ...client(),
      bootstrap: vi.fn(async () => ({ ...bootstrap, capabilities: [...bootstrap.capabilities, "mission"] })),
      missions: vi.fn(async () => ({ items: [], nextCursor: null })),
      createMission: vi.fn(async () => researchMission),
    } satisfies PersonalAssistantClient;

    render(<App client={api} />);
    await screen.findByText("每日计划");
    const composer = await screen.findByRole("textbox", { name: "给个人助理发送消息" });
    fireEvent.change(composer, { target: { value: "/deep-research 研究主流开源数据库过去三年的变化" } });
    fireEvent.submit(composer.closest("form")!);

    const dialog = await screen.findByRole("dialog", { name: "Mission" });
    expect((within(dialog).getByLabelText("目标") as HTMLTextAreaElement).value)
      .toBe("研究主流开源数据库过去三年的变化");
    expect((within(dialog).getByRole("radio", { name: /Deep Research/ }) as HTMLInputElement).checked).toBe(true);
    expect(api.submitMessage).not.toHaveBeenCalled();
    expect(api.createMission).not.toHaveBeenCalled();

    fireEvent.click(within(dialog).getByRole("button", { name: "生成计划" }));
    await waitFor(() => expect(api.createMission).toHaveBeenCalledWith(
      expect.objectContaining({
        objective: "研究主流开源数据库过去三年的变化",
        mode: "DEEP_RESEARCH",
        selectedSkillId: "deep-research",
      }),
      expect.objectContaining({ idempotencyKey: expect.any(String) }),
    ));
  });

  it("only routes an explicit deep-research request and leaves broad research wording in normal chat", async () => {
    const api = {
      ...client(),
      bootstrap: vi.fn(async () => ({ ...bootstrap, capabilities: [...bootstrap.capabilities, "mission"] })),
      missions: vi.fn(async () => ({ items: [], nextCursor: null })),
      createMission: vi.fn(async () => researchMission),
    } satisfies PersonalAssistantClient;

    render(<App client={api} />);
    await screen.findByText("每日计划");
    const composer = await screen.findByRole("textbox", { name: "给个人助理发送消息" });
    fireEvent.change(composer, { target: { value: "帮我调研一下主流数据库" } });
    fireEvent.submit(composer.closest("form")!);
    await waitFor(() => expect(api.submitMessage).toHaveBeenCalledWith(
      expect.objectContaining({ id: conversation.id }),
      "帮我调研一下主流数据库",
      expect.any(Object),
    ));
    await waitFor(() => expect((composer as HTMLTextAreaElement).disabled).toBe(false));

    fireEvent.change(composer, { target: { value: "调用 deep-research skill 研究数据库安全演进" } });
    fireEvent.submit(composer.closest("form")!);
    const dialog = await screen.findByRole("dialog", { name: "Mission" });
    expect((within(dialog).getByLabelText("目标") as HTMLTextAreaElement).value).toBe("研究数据库安全演进");
    expect(api.createMission).not.toHaveBeenCalled();
  });

  it("keeps an unavailable Deep Research draft cost-free and explains the missing Web capability", async () => {
    const api = {
      ...client(),
      bootstrap: vi.fn(async () => ({ ...bootstrap, capabilities: [...bootstrap.capabilities, "mission"]
        .filter((capability) => capability !== "web-research") })),
      missions: vi.fn(async () => ({ items: [], nextCursor: null })),
      createMission: vi.fn(async () => researchMission),
    } satisfies PersonalAssistantClient;

    render(<App client={api} />);
    await screen.findByText("每日计划");
    fireEvent.click(await screen.findByRole("button", { name: "Deep Research" }));
    const composer = screen.getByRole("textbox", { name: "给个人助理发送消息" });
    fireEvent.change(composer, { target: { value: "研究数据库兼容性" } });
    fireEvent.submit(composer.closest("form")!);

    const dialog = await screen.findByRole("dialog", { name: "Mission" });
    expect(within(dialog).getByRole("alert").textContent).toContain("Web Search/Fetch Provider");
    expect((within(dialog).getByRole("button", { name: "生成计划" }) as HTMLButtonElement).disabled).toBe(true);
    expect(api.createMission).not.toHaveBeenCalled();
    expect(api.submitMessage).not.toHaveBeenCalled();
  });

  it("opens the current Mission instead of creating a second active Mission", async () => {
    const api = {
      ...client(),
      bootstrap: vi.fn(async () => ({ ...bootstrap, capabilities: [...bootstrap.capabilities, "mission"] })),
      missions: vi.fn(async () => ({ items: [researchMission], nextCursor: null })),
      missionSnapshot: vi.fn(async () => researchMission),
      createMission: vi.fn(async () => researchMission),
    } satisfies PersonalAssistantClient;

    render(<App client={api} />);
    await screen.findByText("每日计划");
    const composer = screen.getByRole("textbox", { name: "给个人助理发送消息" });
    fireEvent.change(composer, { target: { value: "/deep-research 研究新的数据库主题" } });
    fireEvent.submit(composer.closest("form")!);

    const dialog = await screen.findByRole("dialog", { name: "Mission" });
    expect(within(dialog).getByRole("alert").textContent).toContain("已有进行中的 Mission");
    expect(within(dialog).getAllByText(researchMission.objective)).toHaveLength(2);
    expect(api.createMission).not.toHaveBeenCalled();
    expect(api.submitMessage).not.toHaveBeenCalled();
  });

  it("does not silently discard attachments when Deep Research routing is requested", async () => {
    const imageModel = { ...model, capabilities: [...model.capabilities, "IMAGE_INPUT"] };
    const imageConversation = { ...conversation, model: { model: imageModel, revision: 0, available: true } };
    const api = {
      ...client(),
      bootstrap: vi.fn(async () => ({
        ...bootstrap,
        capabilities: [...bootstrap.capabilities, "mission"],
        models: [imageModel],
      })),
      conversations: vi.fn(async () => [imageConversation]),
      conversation: vi.fn(async () => imageConversation),
      missions: vi.fn(async () => ({ items: [], nextCursor: null })),
      createMission: vi.fn(async () => researchMission),
    } satisfies PersonalAssistantClient;

    render(<App client={api} />);
    await screen.findByText("每日计划");
    fireEvent.click(screen.getByRole("button", { name: "添加图片" }));
    const imageDialog = screen.getByRole("dialog", { name: "添加图片" });
    fireEvent.click(within(imageDialog).getByRole("button", { name: /^添加图片 URL/ }));
    fireEvent.change(within(imageDialog).getByRole("textbox", { name: "图片 URL" }), {
      target: { value: "https://example.com/evidence.png" },
    });
    fireEvent.click(within(imageDialog).getByRole("button", { name: "确认添加图片 URL" }));
    fireEvent.click(screen.getByRole("button", { name: "Deep Research" }));
    const composer = screen.getByRole("textbox", { name: "给个人助理发送消息" });
    fireEvent.change(composer, { target: { value: "研究图片中的证据" } });
    fireEvent.submit(composer.closest("form")!);

    expect(await screen.findByText(/附件不会被静默丢弃/)).toBeTruthy();
    expect(screen.getByText("example.com")).toBeTruthy();
    expect(screen.queryByRole("dialog", { name: "Mission" })).toBeNull();
    expect(api.createMission).not.toHaveBeenCalled();
    expect(api.submitMessage).not.toHaveBeenCalled();
  });

  it("opens a mission directly from its stable URL", async () => {
    window.history.replaceState(null, "", "/missions/mission-1?conversationId=conversation-1");
    const api = {
      ...client(),
      bootstrap: vi.fn(async () => ({ ...bootstrap, capabilities: [...bootstrap.capabilities, "mission"] })),
      missions: vi.fn(async () => ({ items: [mission], nextCursor: null })),
      missionSnapshot: vi.fn(async () => mission),
    } satisfies PersonalAssistantClient;

    render(<App client={api} />);

    const dialog = await screen.findByRole("dialog", { name: "Mission" });
    expect(await within(dialog).findByRole("button", { name: /交付一份可验收的计划/ })).toBeTruthy();
    expect(window.location.pathname).toBe("/missions/mission-1");
  });

  it("moderately adjusts a deterministic research plan before confirmation", async () => {
    const replaceMissionPlan = vi.fn(async (_mission: MissionSnapshot, request: ReplaceMissionPlan) => {
      const tasks = request.plan?.tasks ?? [];
      return {
        ...researchMission,
        version: 4,
        plan: { ...researchMission.plan!, revision: 2, tasks },
        tasks,
      };
    });
    const api = {
      ...client(),
      bootstrap: vi.fn(async () => ({ ...bootstrap, capabilities: [...bootstrap.capabilities, "mission"] })),
      missions: vi.fn(async () => ({ items: [researchMission], nextCursor: null })),
      missionSnapshot: vi.fn(async () => researchMission),
      replaceMissionPlan,
      confirmMission: vi.fn(async () => ({ ...researchMission, state: "RUNNING" as const, version: 5 })),
      cancelMission: vi.fn(async () => ({ ...researchMission, state: "CANCELLED" as const, version: 5 })),
      createMission: vi.fn(async () => researchMission),
    } satisfies PersonalAssistantClient;

    render(<App client={api} />);
    fireEvent.click(await screen.findByRole("button", { name: "Mission" }));
    const dialog = await screen.findByRole("dialog", { name: "Mission" });
    fireEvent.click(await within(dialog).findByRole("button", { name: /研究主流开源数据库过去三年/ }));
    fireEvent.click(within(dialog).getByRole("button", { name: "适度调整计划" }));
    const adjuster = within(dialog).getByRole("region", { name: "适度调整计划" });

    fireEvent.click(within(adjuster).getByRole("button", { name: /编辑任务 02 当前能力与生态/ }));
    fireEvent.click(within(adjuster).getByRole("checkbox", { name: /01 · 技术发展沿革/ }));
    fireEvent.click(within(adjuster).getByRole("button", { name: /编辑任务 03 治理、安全与兼容性/ }));
    fireEvent.click(within(adjuster).getByRole("checkbox", { name: /01 · 技术发展沿革/ }));

    fireEvent.click(within(adjuster).getByRole("button", { name: /编辑任务 04 性能、成本与实证数据/ }));
    fireEvent.change(within(adjuster).getByLabelText("任务标题"), {
      target: { value: "性能、成本、生态与公开基准比较" },
    });
    fireEvent.change(within(adjuster).getByLabelText("任务验收标准"), {
      target: { value: "形成指标清单\n至少选取 3 组可核验的公开基准或案例" },
    });

    fireEvent.click(within(adjuster).getByRole("button", { name: "增加任务" }));
    fireEvent.change(within(adjuster).getByLabelText("任务标题"), {
      target: { value: "迁移案例与生态验证" },
    });
    fireEvent.change(within(adjuster).getByLabelText("任务目标"), {
      target: { value: "验证典型迁移案例并识别生态与退出条件" },
    });
    fireEvent.click(within(adjuster).getByRole("button", { name: "上移任务" }));
    fireEvent.click(within(adjuster).getByRole("checkbox", { name: /02 · 当前能力与生态/ }));
    fireEvent.click(within(adjuster).getByRole("checkbox", { name: /03 · 治理、安全与兼容性/ }));

    fireEvent.click(within(adjuster).getByRole("button", { name: /编辑任务 06 选型框架与综合建议/ }));
    fireEvent.click(within(adjuster).getByRole("checkbox", { name: /05 · 迁移案例与生态验证/ }));
    expect(within(adjuster).queryByText(/deep-research|requiredSkillIds|resultSchemaId/)).toBeNull();
    fireEvent.click(within(adjuster).getByRole("button", { name: "保存调整" }));

    await waitFor(() => expect(replaceMissionPlan).toHaveBeenCalledTimes(1));
    const request = replaceMissionPlan.mock.calls[0]![1];
    const tasks = request.plan!.tasks;
    expect(tasks).toHaveLength(6);
    expect(tasks.map((task) => task.ordinal)).toEqual([1, 2, 3, 4, 5, 6]);
    expect(tasks[1]?.dependsOn).toEqual([]);
    expect(tasks[2]?.dependsOn).toEqual([]);
    expect(tasks[3]?.title).toBe("性能、成本、生态与公开基准比较");
    expect(tasks[3]?.acceptanceCriteria).toContain("至少选取 3 组可核验的公开基准或案例");
    expect(tasks[4]?.dependsOn).toEqual(["current-status-operation", "governance-security-compatibility"]);
    expect(tasks[5]?.dependsOn).toEqual(["performance-cost-evidence", "manual-research-6"]);
    expect(tasks.every((task) => task.taskType === "RESEARCH")).toBe(true);
    expect(tasks.every((task) => task.requiredSkillIds[0] === "deep-research")).toBe(true);
    expect(await within(dialog).findByText("执行计划 · 第 2 版")).toBeTruthy();
    expect(within(dialog).getByRole("button", { name: /确认计划/ })).toBeTruthy();
  });

  it("creates a Standard Mission from a goal with generated acceptance criteria", async () => {
    const api = {
      ...client(),
      bootstrap: vi.fn(async () => ({ ...bootstrap, capabilities: [...bootstrap.capabilities, "mission"] })),
      missions: vi.fn(async () => ({ items: [], nextCursor: null })),
      createMission: vi.fn(async () => mission),
    } satisfies PersonalAssistantClient;

    render(<App client={api} />);
    fireEvent.click(await screen.findByRole("button", { name: "Mission" }));
    const dialog = await screen.findByRole("dialog", { name: "Mission" });
    expect(within(dialog).getByRole("heading", { name: "你希望 Mission 最终交付什么？" })).toBeTruthy();
    expect(within(dialog).getByText("所属会话")).toBeTruthy();
    expect(within(dialog).getByText("每日计划")).toBeTruthy();
    expect(within(dialog).queryByRole("heading", { name: "为“每日计划”创建 Mission" })).toBeNull();
    const objectiveInput = within(dialog).getByLabelText("目标");
    const createButton = within(dialog).getByRole("button", { name: "生成计划" });

    fireEvent.change(objectiveInput, { target: { value: "以太坊过去3年重要的技术迭代" } });
    expect(within(dialog).getByRole("heading", { name: "执行默认值已准备" })).toBeTruthy();
    expect(within(dialog).queryByLabelText("验收标准")).toBeNull();
    await waitFor(() => expect((createButton as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(createButton);

    await waitFor(() => expect(api.createMission).toHaveBeenCalledWith(
      expect.objectContaining({
        objective: "以太坊过去3年重要的技术迭代",
        acceptanceCriteria: [
          "交付结果覆盖目标要求的核心范围",
          "关键结论说明依据、限制与未完成项",
          "形成清晰、可继续使用的最终交付",
        ],
        mode: "STANDARD",
      }),
      expect.objectContaining({ idempotencyKey: expect.any(String) }),
    ));
  });

  it("progressively reveals Deep Research settings and submits deterministic defaults", async () => {
    const api = {
      ...client(),
      bootstrap: vi.fn(async () => ({ ...bootstrap, capabilities: [...bootstrap.capabilities, "mission"] })),
      missions: vi.fn(async () => ({ items: [], nextCursor: null })),
      createMission: vi.fn(async () => mission),
    } satisfies PersonalAssistantClient;

    render(<App client={api} />);
    fireEvent.click(await screen.findByRole("button", { name: "Mission" }));
    const dialog = await screen.findByRole("dialog", { name: "Mission" });
    fireEvent.click(within(dialog).getByRole("radio", { name: /Deep Research/ }));
    fireEvent.change(within(dialog).getByLabelText("目标"), {
      target: { value: "以太坊过去3年重要的技术迭代" },
    });

    expect(within(dialog).getByRole("heading", { name: "研究默认值已准备" })).toBeTruthy();
    expect(within(dialog).getByText("过去3年至今")).toBeTruthy();
    expect(within(dialog).getByText("未指定（以目标明确地区为准）")).toBeTruthy();
    expect(within(dialog).queryByLabelText("验收标准")).toBeNull();

    fireEvent.click(within(dialog).getByRole("button", { name: "调整研究设置" }));
    const criteriaInput = within(dialog).getByLabelText("验收标准") as HTMLTextAreaElement;
    expect(criteriaInput.value).toContain("关键结论提供可追溯来源");
    expect((within(dialog).getByLabelText("时间范围") as HTMLInputElement).value).toBe("过去3年至今");
    expect(within(dialog).getByText("来源与边界")).toBeTruthy();
    fireEvent.change(within(dialog).getByLabelText("地区"), { target: { value: "全球" } });

    const deepResearchCreate = within(dialog).getByRole("button", { name: "生成计划" });
    fireEvent.submit(deepResearchCreate.closest("form")!);
    await waitFor(() => expect(api.createMission).toHaveBeenCalledWith(
      expect.objectContaining({
        objective: "以太坊过去3年重要的技术迭代",
        mode: "DEEP_RESEARCH",
        selectedSkillId: "deep-research",
        acceptanceCriteria: expect.arrayContaining([
          "覆盖目标涉及的关键事实、发展过程与当前状态",
          "关键结论提供可追溯来源，并说明不确定性与证据限制",
        ]),
        researchBrief: expect.objectContaining({
          question: "以太坊过去3年重要的技术迭代",
          timeRange: "过去3年至今",
          region: "全球",
          sourcePreferences: ["一手与官方来源", "权威数据库与专业资料", "独立可靠来源"],
          exclusions: ["无法追溯原始出处的转载", "缺少事实依据的纯营销材料"],
        }),
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
    expect(await within(dialog).findByText("0/1")).toBeTruthy();
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
      selectedSkillBinding: "product/personal-assistant-bundled@1/deep-research@2.2.0#sha256:test",
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
    expect(within(dialog).getByRole("link", { name: "网页来源 · research.stub" })).toBeTruthy();
    expect(within(dialog).getByRole("button", { name: /交付文件 1/ })).toBeTruthy();
    expect(within(dialog).queryByText(/deep-research@|执行 Skill/)).toBeNull();
  });

  it("renders a degraded v2 delivery with report view download and copy actions", async () => {
    const writeText = vi.fn(async () => undefined);
    const createObjectURL = vi.fn(() => "blob:research-report");
    const revokeObjectURL = vi.fn();
    const anchorClick = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText } });
    Object.defineProperty(URL, "createObjectURL", { configurable: true, value: createObjectURL });
    Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: revokeObjectURL });
    const delivered: MissionSnapshot = {
      ...mission,
      mode: "DEEP_RESEARCH",
      selectedSkillId: "deep-research",
      selectedSkillBinding: "product/personal-assistant-bundled@1/deep-research@2.2.0#sha256:test",
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
    expect(within(dialog).getByRole("button", { name: "阅读全文" })).toBeTruthy();
    expect(within(dialog).queryByRole("link", { name: "查看完整报告" })).toBeNull();
    const embeddedReport = await within(dialog).findByRole("region", { name: "完整研究报告" });
    expect(await within(embeddedReport).findByText("正文")).toBeTruthy();
    const downloadLink = within(dialog).getByRole("link", { name: "下载 Markdown" });
    expect(downloadLink.getAttribute("download"))
      .toBe("research-report.md");
    fireEvent.click(downloadLink);
    await waitFor(() => expect(createObjectURL).toHaveBeenCalledTimes(1));
    expect(anchorClick).toHaveBeenCalledTimes(1);
    expect(revokeObjectURL).toHaveBeenCalledWith("blob:research-report");
    expect(await within(dialog).findByRole("link", { name: "已下载" })).toBeTruthy();
    fireEvent.click(within(dialog).getByRole("button", { name: /复制完整报告/ }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith("# 完整研究报告\n\n正文"));
    expect(await within(dialog).findByRole("button", { name: /已复制/ })).toBeTruthy();
    fireEvent.click(within(dialog).getByRole("button", { name: /完整研究报告.*research-report\.md/ }));
    const artifactPanel = within(dialog).getByRole("complementary", { name: "Mission 详情面板" });
    expect(within(artifactPanel).getByRole("heading", { name: "完整研究报告" })).toBeTruthy();
    expect(await within(artifactPanel).findByText("正文")).toBeTruthy();
    anchorClick.mockRestore();
  });

  it("renders a concise Mission delivery card inside the ordinary conversation", async () => {
    const deliveryTurn: Turn = {
      id: "turn-delivery",
      role: "ASSISTANT",
      runId: "run-delivery",
      sequence: 3,
      text: "<!-- haifa-mission-delivery:mission-1 -->\nDeep Research Mission 已完成。完整报告与证据已保存在 Mission 中。",
      images: [],
      createdAt: "2026-08-12T02:15:00Z",
    };
    const delivered: MissionSnapshot = {
      ...researchMission,
      missionId: "mission-1",
      objective: "评估数据库路线",
      state: "COMPLETED",
      artifacts: ["artifact-report", "artifact-sources", "artifact-delivery"],
      finalResult: JSON.stringify({
        schemaVersion: "pa.research-delivery/v2",
        completionKind: "COMPLETE",
        degraded: false,
        reportArtifactRef: { artifactId: "artifact-report", title: "research-report.md", mediaType: "text/markdown" },
        sourcesArtifactRef: { artifactId: "artifact-sources", title: "sources.json", mediaType: "application/json" },
        sourceCount: 7,
        unverifiedClaimCount: 2,
        unresolvedQuestionCount: 1,
        evidenceSummary: {
          totalClaimCount: 5,
          unverifiedClaimCount: 2,
          singleSourceClaimCount: 1,
          counterevidenceClaimCount: 1,
          unresolvedQuestionCount: 1,
        },
        efficiencyMetrics: {
          tokensPerValidSource: 120,
          duplicateSearchFetchRatio: 0,
          evidencePerMaterialClaim: 1.8,
          singleSourceClaimRatio: 0.2,
          synthesisTokenRatio: 0.3,
          qualityGateRevisionCount: 1,
        },
        qualityGate: { passed: true, failedChecks: [] },
      }),
    };
    const newerMission: MissionSnapshot = {
      ...mission,
      missionId: "mission-2",
      objective: "后续已完成任务",
      state: "COMPLETED",
    };
    const api = {
      ...client(),
      bootstrap: vi.fn(async () => ({ ...bootstrap, capabilities: [...bootstrap.capabilities, "mission"] })),
      turns: vi.fn(async () => [...turns, deliveryTurn]),
      missions: vi.fn(async () => ({ items: [newerMission, delivered], nextCursor: null })),
      missionSnapshot: vi.fn(async () => delivered),
      missionArtifact: vi.fn(async (_missionId: string, artifactId: string) => artifactId === "artifact-sources"
        ? JSON.stringify({ schemaVersion: "pa.research-sources/v1", sources: [] })
        : "# 完整报告"),
    } satisfies PersonalAssistantClient;

    render(<App client={api} />);

    const card = await screen.findByRole("region", { name: "Deep Research Mission 交付" });
    expect(within(card).getByRole("heading", { name: "评估数据库路线" })).toBeTruthy();
    expect(within(card).getByText("5").nextSibling?.textContent).toBe("结论");
    expect(within(card).getByText(/本报告包含尚未充分核实的判断/)).toBeTruthy();
    expect(within(card).getByRole("button", { name: "查看完整报告" })).toBeTruthy();
    expect(within(card).getByRole("button", { name: "证据与来源" })).toBeTruthy();
    fireEvent.click(within(card).getByRole("button", { name: "继续追问" }));
    expect((screen.getByPlaceholderText("输入消息或 / 命令，Enter 发送") as HTMLTextAreaElement).value)
      .toBe("关于“评估数据库路线”，我想继续了解：");
    fireEvent.click(within(card).getByRole("button", { name: "查看完整报告" }));
    const dialog = await screen.findByRole("dialog", { name: "Mission" });
    const detailPanel = within(dialog).getByRole("complementary", { name: "Mission 详情面板" });
    expect(await within(detailPanel).findByRole("heading", { name: "完整研究报告" })).toBeTruthy();
    fireEvent.click(within(dialog).getByRole("button", { name: "回到对话" }));
    fireEvent.click(within(card).getByRole("button", { name: "证据与来源" }));
    const evidenceDialog = await screen.findByRole("dialog", { name: "Mission" });
    const evidencePanel = within(evidenceDialog).getByRole("complementary", { name: "Mission 详情面板" });
    expect(await within(evidencePanel).findByRole("heading", { name: "来源清单" })).toBeTruthy();
  });

  it("renders a navigable research document with task links and stable source citations", async () => {
    const report = [
      "<!-- haifa-section: synthesis -->",
      "## 综合分析",
      "",
      "<!-- haifa-task: performance-cost-evidence -->",
      "关键判断由官方文档与独立报道共同支持 [[source-official]][[source-news]]。",
      "缺失引用 [[source-missing]]。",
      "",
      "<!-- haifa-section: conclusions -->",
      "## 结论与建议",
    ].join("\n");
    const reportTurn: Turn = {
      id: "turn-report",
      role: "ASSISTANT",
      runId: "run-report",
      sequence: 3,
      text: report,
      images: [],
      createdAt: "2026-08-11T06:00:00Z",
    };
    const delivered: MissionSnapshot = {
      ...researchMission,
      state: "COMPLETED",
      finalResult: JSON.stringify({
        schemaVersion: "pa.research-delivery/v2",
        completionKind: "COMPLETE",
        degraded: false,
        reportArtifactRef: { artifactId: "artifact-report", title: "research-report.md" },
        sourcesArtifactRef: { artifactId: "artifact-sources" },
        qualityGate: { passed: true, failedChecks: [] },
      }),
    };
    const sources = JSON.stringify({
      schemaVersion: "pa.research-sources/v1",
      sources: [{
        sourceId: "source-official",
        title: "官方文档",
        publisher: "官方技术组织",
        locator: "https://example.org/%25E6%2596%2587%25E6%25A1%25A3",
        normalizedLocator: "https://example.org/%2525E6%252596%252587%2525E6%2525A1%2525A3",
        publishedAt: "2026-03-19T00:00:00Z",
        fetchedAt: "2026-08-11T00:00:00Z",
        status: "FETCHED",
      }, {
        sourceId: "source-news",
        title: "独立报道",
        locator: "https://news.example/report",
        normalizedLocator: "https://news.example/report",
        publishedAt: null,
        fetchedAt: null,
        status: "UNKNOWN",
      }],
    });
    const api = {
      ...client(),
      bootstrap: vi.fn(async () => ({ ...bootstrap, capabilities: [...bootstrap.capabilities, "mission"] })),
      turns: vi.fn(async () => [...turns, reportTurn]),
      missions: vi.fn(async () => ({ items: [delivered], nextCursor: null })),
      missionSnapshot: vi.fn(async () => delivered),
      missionArtifact: vi.fn(async (_missionId: string, artifactId: string) => (
        artifactId === "artifact-sources" ? sources : report
      )),
    } satisfies PersonalAssistantClient;

    render(<App client={api} />);

    const toc = await screen.findByRole("navigation", { name: "报告目录" });
    const synthesisLink = within(toc).getByRole("link", { name: /综合分析/ });
    expect(synthesisLink.getAttribute("href")).toContain("section-synthesis");
    const sectionAnchor = document.querySelector(synthesisLink.getAttribute("href")!);
    const scrollIntoView = vi.fn();
    Object.defineProperty(sectionAnchor, "scrollIntoView", { configurable: true, value: scrollIntoView });
    const urlBeforeSectionNavigation = window.location.href;
    fireEvent.click(synthesisLink);
    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: "smooth", block: "start" });
    expect(window.location.href).toBe(urlBeforeSectionNavigation);
    expect(within(toc).getByRole("link", { name: /结论与建议/ })).toBeTruthy();
    const taskLink = screen.getByRole("button", { name: "打开研究任务 04 详情" });
    expect(taskLink.textContent).toContain("性能、成本与实证数据");
    const conversationCitation = screen.getByRole("button", { name: "查看引用来源 1, 2" });
    fireEvent.click(conversationCitation);
    const conversationEvidence = screen.getByRole("region", { name: "引用来源详情" });
    expect(within(conversationEvidence).getByText("官方技术组织")).toBeTruthy();
    const officialSourceLink = within(conversationEvidence).getByRole("link", { name: "打开“官方文档”" });
    expect(officialSourceLink.getAttribute("href")).toBe("https://example.org/%E6%96%87%E6%A1%A3");
    expect(screen.getByText("来源不可用")).toBeTruthy();
    expect(document.body.textContent).not.toContain("source-official");
    expect(document.body.textContent).not.toContain("performance-cost-evidence");
    fireEvent.click(within(conversationEvidence).getByRole("button", { name: "关闭引用来源" }));

    fireEvent.click(screen.getByRole("button", { name: "打开研究任务 04 详情" }));
    const dialog = await screen.findByRole("dialog", { name: "Mission" });
    let detail = within(dialog).getByRole("complementary", { name: "Mission 详情面板" });
    expect(await within(detail).findByRole("heading", { name: "性能、成本与实证数据" })).toBeTruthy();
    const embeddedReport = await within(dialog).findByRole("region", { name: "完整研究报告" });
    expect(within(embeddedReport).getByRole("navigation", { name: "报告目录" })).toBeTruthy();
    expect(within(dialog).getByRole("link", { name: "官方文档" })).toBeTruthy();
    expect(within(embeddedReport).getByRole("button", { name: "查看引用来源 1, 2" })).toBeTruthy();
    fireEvent.click(within(embeddedReport).getByRole("button", { name: "查看引用来源 1, 2" }));
    detail = within(dialog).getByRole("complementary", { name: "Mission 详情面板" });
    expect(within(detail).getByRole("region", { name: "引用来源详情" })).toBeTruthy();
    fireEvent.keyDown(within(detail).getByRole("button", { name: "关闭引用来源" }), { key: "Escape" });
    expect(screen.getByRole("dialog", { name: "Mission" })).toBeTruthy();
    expect(within(dialog).queryByRole("complementary", { name: "Mission 详情面板" })).toBeNull();
    const embeddedTaskLink = within(embeddedReport).getByRole("button", { name: "打开研究任务 04 详情" });
    expect(embeddedTaskLink.textContent).toContain("性能、成本与实证数据");
    fireEvent.click(embeddedTaskLink);
    detail = within(dialog).getByRole("complementary", { name: "Mission 详情面板" });
    expect(await within(detail).findByRole("heading", { name: "性能、成本与实证数据" })).toBeTruthy();
  });

  it("renders the current Standard Mission final result envelope", async () => {
    const delivered: MissionSnapshot = {
      ...mission,
      state: "COMPLETED",
      finalResult: JSON.stringify({
        schemaVersion: "pa.mission-final-result/v1",
        directAnswer: "候选技术方案各有适用场景，但上线前需要核实性能与迁移风险。",
        completionKind: "COMPLETE",
        completedItems: ["梳理历史发展阶段", "分析当前运营情况"],
        failedItems: [],
        artifactRefs: [],
        sourceRefs: ["开源项目官方版本说明"],
        unverifiedClaims: ["生产负载性能仍需验证"],
        residualRisks: ["迁移兼容性风险"],
        unresolvedQuestions: ["目标环境压测时间表"],
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

    expect(await within(dialog).findByText("Mission 最终报告 · 已完成")).toBeTruthy();
    expect(within(dialog).getByText("候选技术方案各有适用场景，但上线前需要核实性能与迁移风险。")).toBeTruthy();
    expect(within(dialog).getByText("开源项目官方版本说明")).toBeTruthy();
    expect(within(dialog).getByText("生产负载性能仍需验证")).toBeTruthy();
    expect(within(dialog).queryByText("最终报告版本不受支持")).toBeNull();
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
    expect(screen.getByRole("dialog", { name: "Mission" })).toBeTruthy();
    expect(within(dialog).queryByRole("complementary", { name: "Mission 详情面板" })).toBeNull();
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

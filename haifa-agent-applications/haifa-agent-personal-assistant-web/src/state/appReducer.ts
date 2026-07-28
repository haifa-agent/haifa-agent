import type {
  AppAction,
  Artifact,
  BootstrapSnapshot,
  ConversationDetail,
  ConversationSummary,
  TokenUsage,
  UiState,
} from "../types";

const activeStatuses = new Set([
  "running",
  "waiting_approval",
  "waiting_interaction",
]);

export function createUiState(snapshot: BootstrapSnapshot): UiState {
  return {
    ...structuredClone(snapshot),
    selectedConversationId: snapshot.conversations[0]?.id ?? "",
    searchQuery: "",
    composer: "",
    deliveryMode: "follow_up",
    taskPanelOpen: false,
    sidebarOpen: false,
    memoryDialogOpen: false,
    memoryTab: "preferences",
    artifactPreviewId: null,
    sessionMenuId: null,
    toast: null,
  };
}

export function createEmptyUiState(): UiState {
  return createUiState({
    conversations: [],
    details: {},
    preferences: {
      assistantName: "小海",
      responseStyle: "practical",
      detailLevel: 3,
      locale: "zh-CN",
      proactiveSuggestions: true,
      memoryEnabled: true,
      revision: 1,
    },
    memoryCandidates: [],
    memories: [],
  });
}

function updateDetail(
  state: UiState,
  conversationId: string,
  update: (detail: ConversationDetail) => ConversationDetail,
): UiState {
  const current = state.details[conversationId];
  if (!current) {
    return state;
  }
  const detail = update(current);
  return {
    ...state,
    details: { ...state.details, [conversationId]: detail },
    conversations: state.conversations.map((conversation) =>
      conversation.id === conversationId ? detail.summary : conversation,
    ),
  };
}

function currentDetail(state: UiState): ConversationDetail | undefined {
  return state.details[state.selectedConversationId];
}

function completeArtifact(): Artifact {
  return {
    id: "artifact-data-summary",
    version: 1,
    name: "数据整理摘要.csv",
    mediaType: "text/csv",
    sizeLabel: "8 KB",
    description: "已完成空值标记、字段标准化和统计摘要。",
    createdAt: "刚刚",
    preview:
      "字段,有效记录,空值,备注\n客户编号,128,0,已标准化\n地区,125,3,空值已标记\n订单金额,128,0,保留两位小数\n状态,128,0,统一为四种状态",
    previewMode: "safe_inline",
  };
}

function addProviderReportedUsage(
  current: TokenUsage | undefined,
  inputTokens: number,
  outputTokens: number,
  cacheReadInputTokens: number,
): TokenUsage {
  const nextInput = (current?.inputTokens ?? 0) + inputTokens;
  const nextOutput = (current?.outputTokens ?? 0) + outputTokens;
  return {
    inputTokens: nextInput,
    outputTokens: nextOutput,
    totalTokens: nextInput + nextOutput,
    cacheReadInputTokens:
      (current?.cacheReadInputTokens ?? 0) + cacheReadInputTokens,
    modelCalls: (current?.modelCalls ?? 0) + 1,
    providerReportedModelCalls:
      (current?.providerReportedModelCalls ?? 0) + 1,
    updatedAt: "刚刚",
  };
}

export function appReducer(state: UiState, action: AppAction): UiState {
  switch (action.type) {
    case "hydrate":
      return createUiState(action.snapshot);
    case "selectConversation":
      return {
        ...state,
        selectedConversationId: action.id,
        sidebarOpen: false,
        sessionMenuId: null,
        toast: null,
      };
    case "newConversation": {
      const id = `session-new-${Date.now()}`;
      const summary: ConversationSummary = {
        id,
        title: "新会话",
        preview: "还没有消息",
        status: "idle",
        updatedLabel: "刚刚",
        group: "今天",
        archived: false,
        revision: 1,
      };
      return {
        ...state,
        conversations: [summary, ...state.conversations],
        details: {
          ...state.details,
          [id]: { summary, messages: [], artifacts: [] },
        },
        selectedConversationId: id,
        sidebarOpen: false,
        composer: "",
        toast: "新会话已准备好",
      };
    }
    case "setSearch":
      return { ...state, searchQuery: action.value };
    case "setComposer":
      return { ...state, composer: action.value };
    case "setDeliveryMode":
      return { ...state, deliveryMode: action.value };
    case "submitMessage": {
      const text = action.text.trim();
      const detail = currentDetail(state);
      if (!text || !detail) {
        return state;
      }
      const isActive = Boolean(detail.run && activeStatuses.has(detail.run.status));
      const userMessage = {
        id: `msg-${Date.now()}`,
        role: "user" as const,
        content: text,
        time: action.now,
        state: isActive
          ? state.deliveryMode === "follow_up"
            ? ("queued" as const)
            : ("applied" as const)
          : ("committed" as const),
      };

      if (isActive) {
        return updateDetail(
          {
            ...state,
            composer: "",
            toast:
              state.deliveryMode === "follow_up"
                ? "消息已排队，将在当前任务完成后发送"
                : "补充信息已提交，将在安全点用于当前任务",
          },
          detail.summary.id,
          (value) => ({
            ...value,
            messages: [...value.messages, userMessage],
            run:
              state.deliveryMode === "steer" && value.run
                ? {
                    ...value.run,
                    activity: [
                      ...value.run.activity,
                      {
                        id: `activity-steer-${Date.now()}`,
                        title: "已收到当前任务补充",
                        detail: "将在 Runtime 安全点应用",
                        time: action.now,
                        kind: "status",
                        state: "running",
                      },
                    ],
                  }
                : value.run,
          }),
        );
      }

      const runId = `run-${Date.now()}`;
      return updateDetail(
        { ...state, composer: "", toast: "已收到，正在处理" },
        detail.summary.id,
        (value) => {
          const summary = {
            ...value.summary,
            title: value.messages.length === 0 ? text.slice(0, 18) : value.summary.title,
            preview: text.slice(0, 48),
            status: "running" as const,
            updatedLabel: "刚刚",
            revision: value.summary.revision + 1,
          };
          return {
            ...value,
            summary,
            messages: [
              ...value.messages,
              userMessage,
              {
                id: `assistant-${runId}`,
                role: "assistant",
                content: "",
                time: action.now,
                state: "streaming",
              },
            ],
            run: {
              id: runId,
              title: summary.title,
              status: "running",
              elapsed: "00:01",
              startedAt: action.now,
              steps: [
                { id: `${runId}-1`, title: "理解你的需求", state: "active" },
                { id: `${runId}-2`, title: "整理回答", state: "pending" },
              ],
              activity: [
                {
                  id: `${runId}-activity`,
                  title: "开始处理",
                  time: action.now,
                  kind: "status",
                  state: "running",
                },
              ],
            },
          };
        },
      );
    }
    case "appendAssistantDelta":
      return updateDetail(state, action.conversationId, (detail) => ({
        ...detail,
        messages: detail.messages.map((message) =>
          message.role === "assistant" && message.state === "streaming"
            ? { ...message, content: `${message.content}${action.delta}` }
            : message,
        ),
      }));
    case "completeAssistantReply":
      return updateDetail(
        { ...state, toast: "本轮任务已完成" },
        action.conversationId,
        (detail) => {
          const summary = {
            ...detail.summary,
            status: "completed" as const,
            preview: "已经整理好回答，可以继续提问",
            revision: detail.summary.revision + 1,
          };
          return {
            ...detail,
            summary,
            messages: detail.messages.map((message) =>
              message.role === "assistant" && message.state === "streaming"
                ? { ...message, state: "committed" as const }
                : message,
            ),
            tokenUsage: addProviderReportedUsage(
              detail.tokenUsage,
              692,
              184,
              420,
            ),
            run: detail.run
              ? {
                  ...detail.run,
                  status: "completed",
                  elapsed: "00:04",
                  steps: detail.run.steps.map((step) => ({
                    ...step,
                    state: "completed" as const,
                  })),
                  activity: detail.run.activity.map((item) => ({
                    ...item,
                    state: "completed" as const,
                  })),
                }
              : undefined,
          };
        },
      );
    case "toggleTaskPanel":
      return { ...state, taskPanelOpen: !state.taskPanelOpen };
    case "toggleSidebar":
      return { ...state, sidebarOpen: action.open ?? !state.sidebarOpen };
    case "openMemory":
      return {
        ...state,
        memoryDialogOpen: true,
        memoryTab: action.tab ?? state.memoryTab,
      };
    case "closeMemory":
      return { ...state, memoryDialogOpen: false };
    case "setMemoryTab":
      return { ...state, memoryTab: action.tab };
    case "updatePreferences":
      return {
        ...state,
        preferences: { ...action.value, revision: state.preferences.revision + 1 },
        memoryDialogOpen: false,
        toast: "偏好已保存，将从下一轮任务开始生效",
      };
    case "approveMemoryCandidate": {
      const candidate = state.memoryCandidates.find((item) => item.id === action.id);
      if (!candidate) {
        return state;
      }
      return {
        ...state,
        memoryCandidates: state.memoryCandidates.filter((item) => item.id !== action.id),
        memories: [
          {
            id: `memory-${candidate.id}`,
            content: candidate.content,
            category: "个人偏好",
            source: candidate.source,
            updatedAt: "刚刚",
            active: true,
            revision: 1,
          },
          ...state.memories,
        ],
        toast: "记忆已确认，将在允许范围内用于后续任务",
      };
    }
    case "rejectMemoryCandidate":
      return {
        ...state,
        memoryCandidates: state.memoryCandidates.filter((item) => item.id !== action.id),
        toast: "已拒绝这条记忆建议",
      };
    case "toggleMemory":
      return {
        ...state,
        memories: state.memories.map((memory) =>
          memory.id === action.id
            ? { ...memory, active: !memory.active, revision: memory.revision + 1 }
            : memory,
        ),
        toast: "记忆状态已更新",
      };
    case "approveInteraction": {
      const detail = currentDetail(state);
      if (!detail?.run?.interaction || detail.run.interaction.type !== "approval") {
        return state;
      }
      return updateDetail(
        {
          ...state,
          toast: action.approved ? "确认已受理，任务正在继续" : "已拒绝，本次任务已停止",
        },
        detail.summary.id,
        (value) => {
          if (!value.run || !value.run.interaction || value.run.interaction.type !== "approval") {
            return value;
          }
          const approved = action.approved;
          return {
            ...value,
            summary: {
              ...value.summary,
              status: approved ? "running" : "cancelled",
              revision: value.summary.revision + 1,
            },
            run: {
              ...value.run,
              status: approved ? "running" : "cancelled",
              interaction: {
                ...value.run.interaction,
                state: approved ? "approved" : "rejected",
              },
              steps: value.run.steps.map((step, index) => {
                if (index === 1) {
                  return { ...step, state: approved ? "completed" : "failed" };
                }
                if (index === 2 && approved) {
                  return { ...step, state: "active" };
                }
                return step;
              }),
              activity: [
                ...value.run.activity,
                {
                  id: `activity-approval-${Date.now()}`,
                  title: approved ? "已批准，仅限这一次" : "用户拒绝运行",
                  time: "刚刚",
                  kind: "approval",
                  state: approved ? "completed" : "failed",
                },
              ],
            },
          };
        },
      );
    }
    case "answerClarification": {
      const detail = currentDetail(state);
      if (!detail?.run?.interaction || detail.run.interaction.type !== "clarification") {
        return state;
      }
      const selected = detail.run.interaction.options.find(
        (option) => option.id === action.optionId,
      );
      return updateDetail(
        { ...state, toast: "回答已受理，任务正在继续" },
        detail.summary.id,
        (value) => {
          if (!value.run || !value.run.interaction || value.run.interaction.type !== "clarification") {
            return value;
          }
          return {
            ...value,
            summary: { ...value.summary, status: "running", revision: value.summary.revision + 1 },
            messages: [
              ...value.messages,
              {
                id: `msg-clarification-${Date.now()}`,
                role: "user",
                content: `补充信息：${selected?.label ?? "其他日期"}`,
                time: "刚刚",
                state: "applied",
              },
            ],
            run: {
              ...value.run,
              status: "running",
              interaction: { ...value.run.interaction, state: "answered" },
              steps: value.run.steps.map((step, index) =>
                index === 1
                  ? { ...step, state: "completed" }
                  : index === 2
                    ? { ...step, state: "active" }
                    : step,
              ),
            },
          };
        },
      );
    }
    case "completeApprovedRun": {
      const detail = currentDetail(state);
      if (!detail?.run || detail.run.status !== "running") {
        return state;
      }
      const artifact = completeArtifact();
      return updateDetail(
        { ...state, toast: "任务完成，已生成一份交付物" },
        detail.summary.id,
        (value) => ({
          ...value,
          summary: {
            ...value.summary,
            status: "completed",
            preview: "数据已经整理完成并生成摘要文件",
            revision: value.summary.revision + 1,
          },
          messages: [
            ...value.messages,
            {
              id: `msg-complete-${Date.now()}`,
              role: "assistant",
              content: "数据已经整理完成。我标记了 3 处空值，统一了字段格式，并生成了一份摘要文件。",
              time: "刚刚",
              state: "committed",
            },
          ],
          run: value.run
            ? {
                ...value.run,
                status: "completed",
                elapsed: "00:26",
                steps: value.run.steps.map((step) => ({ ...step, state: "completed" })),
                activity: [
                  ...value.run.activity,
                  {
                    id: "activity-artifact-complete",
                    title: "发布数据整理摘要",
                    detail: "CSV · 8 KB",
                    time: "刚刚",
                    kind: "artifact",
                    state: "completed",
                  },
                ],
              }
            : undefined,
          artifacts: [artifact, ...value.artifacts],
          tokenUsage: addProviderReportedUsage(
            value.tokenUsage,
            1_950,
            342,
            1_472,
          ),
        }),
      );
    }
    case "cancelRun": {
      const detail = currentDetail(state);
      if (!detail?.run) {
        return state;
      }
      return updateDetail(
        { ...state, toast: "已请求停止；已经执行的外部动作不一定能撤销" },
        detail.summary.id,
        (value) => ({
          ...value,
          summary: { ...value.summary, status: "cancelled", revision: value.summary.revision + 1 },
          run: value.run ? { ...value.run, status: "cancelled" } : undefined,
        }),
      );
    }
    case "openArtifact":
      return { ...state, artifactPreviewId: action.id };
    case "closeArtifact":
      return { ...state, artifactPreviewId: null };
    case "toggleSessionMenu":
      return { ...state, sessionMenuId: action.id };
    case "renameConversation": {
      const nextTitle = action.title.trim();
      if (!nextTitle) {
        return state;
      }
      return updateDetail(
        { ...state, sessionMenuId: null, toast: "会话已重命名" },
        action.id,
        (detail) => ({
          ...detail,
          summary: {
            ...detail.summary,
            title: nextTitle.slice(0, 120),
            revision: detail.summary.revision + 1,
          },
        }),
      );
    }
    case "archiveConversation": {
      const nextSelected =
        state.conversations.find(
          (conversation) => conversation.id !== action.id && !conversation.archived,
        )?.id ?? "";
      const updated = updateDetail(
        {
          ...state,
          selectedConversationId:
            state.selectedConversationId === action.id ? nextSelected : state.selectedConversationId,
          sessionMenuId: null,
          toast: "会话已归档",
        },
        action.id,
        (detail) => ({
          ...detail,
          summary: {
            ...detail.summary,
            archived: true,
            revision: detail.summary.revision + 1,
          },
        }),
      );
      return updated;
    }
    case "clearToast":
      return { ...state, toast: null };
    default:
      return state;
  }
}

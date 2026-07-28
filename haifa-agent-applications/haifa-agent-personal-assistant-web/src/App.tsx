import {
  Archive,
  Bot,
  Brain,
  Check,
  CheckCircle2,
  ChevronRight,
  Circle,
  CircleAlert,
  Clock3,
  Download,
  FileText,
  ListChecks,
  Menu,
  MessageSquarePlus,
  MoreHorizontal,
  PanelRight,
  Pause,
  Play,
  Search,
  Send,
  Settings2,
  ShieldCheck,
  Sparkles,
  Square,
  Tags,
  UserRound,
  X,
} from "lucide-react";
import {
  type FormEvent,
  type KeyboardEvent,
  useEffect,
  useMemo,
  useReducer,
  useRef,
  useState,
} from "react";
import { MockPersonalAssistantClient } from "./api/mockClient";
import { appReducer, createEmptyUiState } from "./state/appReducer";
import type {
  ActivityItem,
  Artifact,
  ConversationDetail,
  ConversationStatus,
  MemoryTab,
  Preferences,
  TaskStep,
  TokenUsage,
  UiState,
} from "./types";

const client = new MockPersonalAssistantClient();
const activeStatuses = new Set<ConversationStatus>([
  "running",
  "waiting_approval",
  "waiting_interaction",
]);

const statusLabels: Record<ConversationStatus, string> = {
  idle: "待开始",
  running: "处理中",
  waiting_approval: "等待确认",
  waiting_interaction: "需要补充",
  completed: "已完成",
  failed: "未完成",
  cancelled: "已停止",
};

const groupOrder = ["今天", "最近 7 天", "更早"] as const;
const tokenNumberFormat = new Intl.NumberFormat("zh-CN");

function StatusPill({ status }: { status: ConversationStatus }) {
  return (
    <span className={`status-pill status-${status}`}>
      <span className="status-dot" aria-hidden="true" />
      {statusLabels[status]}
    </span>
  );
}

function SessionSidebar({
  state,
  dispatch,
}: {
  state: UiState;
  dispatch: React.Dispatch<Parameters<typeof appReducer>[1]>;
}) {
  const filtered = state.conversations.filter(
    (conversation) =>
      !conversation.archived &&
      `${conversation.title} ${conversation.preview}`
        .toLocaleLowerCase()
        .includes(state.searchQuery.toLocaleLowerCase()),
  );

  return (
    <>
      {state.sidebarOpen && (
        <button
          className="drawer-scrim"
          aria-label="关闭会话列表"
          onClick={() => dispatch({ type: "toggleSidebar", open: false })}
        />
      )}
      <aside
        className={`session-sidebar ${state.sidebarOpen ? "is-open" : ""}`}
        data-testid="session-sidebar"
      >
        <div className="sidebar-heading">
          <div>
            <span className="eyebrow">会话</span>
            <strong>{filtered.length}</strong>
          </div>
          <button
            className="icon-button primary-icon"
            aria-label="新建会话"
            onClick={() => dispatch({ type: "newConversation" })}
          >
            <MessageSquarePlus size={18} />
          </button>
        </div>

        <label className="search-box">
          <Search size={16} aria-hidden="true" />
          <span className="sr-only">搜索会话</span>
          <input
            value={state.searchQuery}
            onChange={(event) =>
              dispatch({ type: "setSearch", value: event.target.value })
            }
            placeholder="搜索会话"
          />
        </label>

        <div className="session-groups">
          {groupOrder.map((group) => {
            const conversations = filtered.filter(
              (conversation) => conversation.group === group,
            );
            if (!conversations.length) {
              return null;
            }
            return (
              <section className="session-group" key={group}>
                <h2>{group}</h2>
                {conversations.map((conversation) => (
                  <div
                    className={`session-row ${
                      state.selectedConversationId === conversation.id ? "selected" : ""
                    }`}
                    key={conversation.id}
                  >
                    <button
                      className="session-select"
                      onClick={() =>
                        dispatch({ type: "selectConversation", id: conversation.id })
                      }
                    >
                      <span className="session-title">
                        <span>{conversation.title}</span>
                        {activeStatuses.has(conversation.status) && (
                          <span
                            className={`mini-state mini-${conversation.status}`}
                            title={statusLabels[conversation.status]}
                          />
                        )}
                      </span>
                      <span className="session-preview">{conversation.preview}</span>
                      <span className="session-time">{conversation.updatedLabel}</span>
                    </button>
                    <button
                      className="session-more"
                      aria-label={`管理会话：${conversation.title}`}
                      onClick={() =>
                        dispatch({
                          type: "toggleSessionMenu",
                          id:
                            state.sessionMenuId === conversation.id
                              ? null
                              : conversation.id,
                        })
                      }
                    >
                      <MoreHorizontal size={17} />
                    </button>
                    {state.sessionMenuId === conversation.id && (
                      <div className="session-menu">
                        <button
                          onClick={() => {
                            const title = window.prompt(
                              "为会话输入新名称",
                              conversation.title,
                            );
                            if (title) {
                              dispatch({
                                type: "renameConversation",
                                id: conversation.id,
                                title,
                              });
                            }
                          }}
                        >
                          <Settings2 size={15} /> 重命名
                        </button>
                        <button
                          onClick={() =>
                            dispatch({
                              type: "archiveConversation",
                              id: conversation.id,
                            })
                          }
                        >
                          <Archive size={15} /> 归档
                        </button>
                      </div>
                    )}
                  </div>
                ))}
              </section>
            );
          })}
          {!filtered.length && (
            <div className="sidebar-empty">没有找到匹配的会话</div>
          )}
        </div>

        <div className="sidebar-footer">
          <ShieldCheck size={17} />
          <div>
            <strong>本地演示环境</strong>
            <span>当前仅使用 Mock 数据，不连接后端</span>
          </div>
        </div>
      </aside>
    </>
  );
}

function StepIcon({ step }: { step: TaskStep }) {
  if (step.state === "completed") {
    return <CheckCircle2 size={18} />;
  }
  if (step.state === "active") {
    return <Play size={16} fill="currentColor" />;
  }
  if (step.state === "blocked") {
    return <Pause size={16} fill="currentColor" />;
  }
  if (step.state === "failed") {
    return <CircleAlert size={18} />;
  }
  return <Circle size={16} />;
}

function RunSummary({ detail }: { detail: ConversationDetail }) {
  if (!detail.run) {
    return null;
  }
  const completed = detail.run.steps.filter((step) => step.state === "completed").length;
  const percent = Math.round((completed / Math.max(detail.run.steps.length, 1)) * 100);

  return (
    <section className="run-summary" aria-label="任务进度">
      <div className="run-summary-top">
        <div>
          <ListChecks size={18} />
          <strong>{detail.run.title}</strong>
        </div>
        <StatusPill status={detail.run.status} />
      </div>
      <div
        className="progress-track"
        role="progressbar"
        aria-label="任务步骤进度"
        aria-valuenow={percent}
        aria-valuemin={0}
        aria-valuemax={100}
      >
        <span style={{ width: `${percent}%` }} />
      </div>
      <div className="run-summary-meta">
        <span>
          {completed}/{detail.run.steps.length} 个步骤
        </span>
        <span>
          <Clock3 size={14} /> {detail.run.elapsed}
        </span>
      </div>
    </section>
  );
}

function InteractionCard({
  detail,
  dispatch,
  onApproved,
}: {
  detail: ConversationDetail;
  dispatch: React.Dispatch<Parameters<typeof appReducer>[1]>;
  onApproved: () => void;
}) {
  const interaction = detail.run?.interaction;
  if (!interaction || interaction.state !== "pending") {
    return null;
  }

  if (interaction.type === "clarification") {
    return (
      <section className="interaction-card clarification-card">
        <div className="interaction-icon">
          <CircleAlert size={20} />
        </div>
        <div className="interaction-content">
          <span className="eyebrow">需要你补充</span>
          <h3>{interaction.title}</h3>
          <p>{interaction.detail}</p>
          <div className="choice-row">
            {interaction.options.map((option) => (
              <button
                className="secondary-button"
                key={option.id}
                onClick={() =>
                  dispatch({ type: "answerClarification", optionId: option.id })
                }
              >
                {option.label}
              </button>
            ))}
          </div>
        </div>
      </section>
    );
  }

  return (
    <section className="interaction-card approval-card">
      <div className="interaction-icon">
        <ShieldCheck size={21} />
      </div>
      <div className="interaction-content">
        <span className="eyebrow">等待你的确认</span>
        <h3>{interaction.title}</h3>
        <dl className="approval-details">
          <div>
            <dt>动作</dt>
            <dd>{interaction.action}</dd>
          </div>
          <div>
            <dt>目标</dt>
            <dd>{interaction.target}</dd>
          </div>
          <div>
            <dt>执行摘要</dt>
            <dd>{interaction.commandSummary}</dd>
          </div>
          <div>
            <dt>影响范围</dt>
            <dd>{interaction.risk}</dd>
          </div>
        </dl>
        <p className="approval-boundary">{interaction.boundary}</p>
        <div className="interaction-actions">
          <button
            className="secondary-button danger-text"
            onClick={() => dispatch({ type: "approveInteraction", approved: false })}
          >
            拒绝
          </button>
          <button
            className="primary-button"
            onClick={() => {
              dispatch({ type: "approveInteraction", approved: true });
              onApproved();
            }}
          >
            <Check size={17} /> 仅批准这一次
          </button>
        </div>
      </div>
    </section>
  );
}

function ArtifactCard({
  artifact,
  dispatch,
}: {
  artifact: Artifact;
  dispatch: React.Dispatch<Parameters<typeof appReducer>[1]>;
}) {
  return (
    <button
      className="artifact-card"
      onClick={() => dispatch({ type: "openArtifact", id: artifact.id })}
    >
      <span className="artifact-icon">
        <FileText size={20} />
      </span>
      <span className="artifact-copy">
        <strong>{artifact.name}</strong>
        <span>{artifact.description}</span>
        <small>
          v{artifact.version} · {artifact.sizeLabel} · {artifact.createdAt}
        </small>
      </span>
      <ChevronRight size={18} />
    </button>
  );
}

function TokenUsageBar({ usage }: { usage?: TokenUsage }) {
  if (!usage) {
    return null;
  }
  const fullyProviderReported =
    usage.modelCalls > 0 &&
    usage.providerReportedModelCalls === usage.modelCalls;
  const sourceLabel = fullyProviderReported
    ? "提供方实报"
    : `实报 ${usage.providerReportedModelCalls}/${usage.modelCalls} 次`;

  return (
    <section
      className="token-usage"
      aria-label={`会话 Token 消耗：输入 ${usage.inputTokens}，输出 ${usage.outputTokens}，总计 ${usage.totalTokens}，缓存读取 ${usage.cacheReadInputTokens}`}
      title={`累计至 ${usage.updatedAt}；仅汇总模型提供方返回的 usage，不使用文本长度估算`}
    >
      <Tags size={15} aria-hidden="true" />
      <span className="token-label">Tokens</span>
      <span>
        输入：<strong>{tokenNumberFormat.format(usage.inputTokens)}</strong>
      </span>
      <i aria-hidden="true" />
      <span>
        输出：<strong>{tokenNumberFormat.format(usage.outputTokens)}</strong>
      </span>
      <i aria-hidden="true" />
      <span>
        总计：<strong>{tokenNumberFormat.format(usage.totalTokens)}</strong>
      </span>
      <i aria-hidden="true" />
      <span>
        缓存读取：
        <strong>{tokenNumberFormat.format(usage.cacheReadInputTokens)}</strong>
      </span>
      <span className={fullyProviderReported ? "usage-source complete" : "usage-source"}>
        {sourceLabel}
      </span>
    </section>
  );
}

function Composer({
  state,
  detail,
  dispatch,
  onSubmit,
}: {
  state: UiState;
  detail?: ConversationDetail;
  dispatch: React.Dispatch<Parameters<typeof appReducer>[1]>;
  onSubmit: () => void;
}) {
  const active = Boolean(detail?.run && activeStatuses.has(detail.run.status));
  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    onSubmit();
  };
  const handleKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      onSubmit();
    }
  };

  return (
    <form className="composer" onSubmit={handleSubmit}>
      {active && (
        <div className="delivery-switch" aria-label="消息发送方式">
          <button
            type="button"
            className={state.deliveryMode === "follow_up" ? "active" : ""}
            onClick={() => dispatch({ type: "setDeliveryMode", value: "follow_up" })}
          >
            排队为下一条
          </button>
          <button
            type="button"
            className={state.deliveryMode === "steer" ? "active" : ""}
            onClick={() => dispatch({ type: "setDeliveryMode", value: "steer" })}
          >
            用于当前任务
          </button>
        </div>
      )}
      <div className="composer-row">
        <textarea
          aria-label="输入消息"
          value={state.composer}
          onChange={(event) =>
            dispatch({ type: "setComposer", value: event.target.value })
          }
          onKeyDown={handleKeyDown}
          placeholder={
            active
              ? state.deliveryMode === "steer"
                ? "补充当前任务的信息…"
                : "输入下一条消息…"
              : "给个人助理发送消息…"
          }
          rows={1}
        />
        {active && (
          <button
            type="button"
            className="icon-button stop-button"
            aria-label="停止当前任务"
            onClick={() => dispatch({ type: "cancelRun" })}
          >
            <Square size={15} fill="currentColor" />
          </button>
        )}
        <button
          className="send-button"
          type="submit"
          aria-label="发送消息"
          disabled={!state.composer.trim()}
        >
          <Send size={18} />
        </button>
      </div>
      <span className="composer-hint">Enter 发送 · Shift + Enter 换行</span>
    </form>
  );
}

function ConversationView({
  state,
  detail,
  dispatch,
  onSubmit,
  onApproved,
}: {
  state: UiState;
  detail?: ConversationDetail;
  dispatch: React.Dispatch<Parameters<typeof appReducer>[1]>;
  onSubmit: () => void;
  onApproved: () => void;
}) {
  const endRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (typeof endRef.current?.scrollIntoView === "function") {
      endRef.current.scrollIntoView({ block: "end" });
    }
  }, [detail?.messages.length]);

  if (!detail) {
    return (
      <main className="conversation-main">
        <div className="welcome-state">
          <span className="welcome-mark">
            <Sparkles size={26} />
          </span>
          <h1>今天想完成什么？</h1>
          <p>我可以帮你整理信息、制定计划和推进需要多个步骤的个人任务。</p>
          <button
            className="primary-button"
            onClick={() => dispatch({ type: "newConversation" })}
          >
            <MessageSquarePlus size={17} /> 开始新会话
          </button>
        </div>
      </main>
    );
  }

  return (
    <main className="conversation-main" data-testid="conversation-main">
      <div className="conversation-heading">
        <div>
          <span className="eyebrow">当前会话</span>
          <h1>{detail.summary.title}</h1>
        </div>
        <StatusPill status={detail.summary.status} />
      </div>
      <div className="conversation-scroll">
        <RunSummary detail={detail} />
        <div className="message-list">
          {detail.messages.map((message) => (
            <article className={`message message-${message.role}`} key={message.id}>
              <div className="message-avatar" aria-hidden="true">
                {message.role === "assistant" ? (
                  <Bot size={17} />
                ) : (
                  <UserRound size={17} />
                )}
              </div>
              <div className="message-body">
                <div className="message-meta">
                  <strong>
                    {message.role === "assistant"
                      ? state.preferences.assistantName
                      : "你"}
                  </strong>
                  <span>{message.time}</span>
                  {message.state === "queued" && <em>已排队</em>}
                  {message.state === "applied" && <em>用于当前任务</em>}
                </div>
                <p>
                  {message.content}
                  {message.state === "streaming" && (
                    <span className="typing-caret" aria-label="正在回复" />
                  )}
                </p>
              </div>
            </article>
          ))}
        </div>
        <InteractionCard
          detail={detail}
          dispatch={dispatch}
          onApproved={onApproved}
        />
        {!!detail.artifacts.length && (
          <section className="artifact-section">
            <div className="section-title">
              <FileText size={18} />
              <h2>交付物</h2>
            </div>
            <div className="artifact-list">
              {detail.artifacts.map((artifact) => (
                <ArtifactCard
                  artifact={artifact}
                  dispatch={dispatch}
                  key={artifact.id}
                />
              ))}
            </div>
          </section>
        )}
        <TokenUsageBar usage={detail.tokenUsage} />
        <div ref={endRef} />
      </div>
      <Composer
        state={state}
        detail={detail}
        dispatch={dispatch}
        onSubmit={onSubmit}
      />
    </main>
  );
}

function ActivityIcon({ item }: { item: ActivityItem }) {
  if (item.state === "waiting") {
    return <Pause size={15} />;
  }
  if (item.state === "running") {
    return <Play size={14} fill="currentColor" />;
  }
  if (item.state === "failed") {
    return <CircleAlert size={16} />;
  }
  return <Check size={15} />;
}

function TaskPanel({
  detail,
  state,
  dispatch,
}: {
  detail?: ConversationDetail;
  state: UiState;
  dispatch: React.Dispatch<Parameters<typeof appReducer>[1]>;
}) {
  const run = detail?.run;
  return (
    <>
      {state.taskPanelOpen && (
        <button
          className="task-scrim"
          aria-label="关闭任务详情"
          onClick={() => dispatch({ type: "toggleTaskPanel" })}
        />
      )}
      <aside
        className={`task-panel ${state.taskPanelOpen ? "is-open" : ""}`}
        data-testid="task-panel"
        aria-hidden={!state.taskPanelOpen}
        inert={!state.taskPanelOpen}
      >
        <div className="panel-heading">
          <div>
            <span className="eyebrow">任务详情</span>
            <h2>{run?.title ?? "暂无任务"}</h2>
          </div>
          <button
            className="icon-button panel-close"
            aria-label="关闭任务详情"
            onClick={() => dispatch({ type: "toggleTaskPanel" })}
          >
            <X size={18} />
          </button>
        </div>
        {!run ? (
          <div className="panel-empty">
            <ListChecks size={28} />
            <strong>还没有多步骤任务</strong>
            <p>任务开始后，这里会显示清晰的步骤和活动摘要。</p>
          </div>
        ) : (
          <div className="panel-scroll">
            <section className="task-overview">
              <div>
                <StatusPill status={run.status} />
                <span className="elapsed">
                  <Clock3 size={14} /> {run.elapsed}
                </span>
              </div>
              <ol className="step-list">
                {run.steps.map((step) => (
                  <li className={`step-${step.state}`} key={step.id}>
                    <span className="step-icon">
                      <StepIcon step={step} />
                    </span>
                    <span>{step.title}</span>
                  </li>
                ))}
              </ol>
            </section>
            <section className="activity-section">
              <div className="section-title">
                <Sparkles size={17} />
                <h3>活动</h3>
              </div>
              <div className="activity-list">
                {run.activity.map((item) => (
                  <article className={`activity-item activity-${item.state}`} key={item.id}>
                    <span className="activity-icon">
                      <ActivityIcon item={item} />
                    </span>
                    <div>
                      <strong>{item.title}</strong>
                      {item.detail && <p>{item.detail}</p>}
                      <time>{item.time}</time>
                    </div>
                  </article>
                ))}
              </div>
            </section>
            <div className="safe-note">
              <ShieldCheck size={17} />
              <p>
                仅展示用户可理解的任务信息。模型原文、内部节点与原始事件不会出现在个人助理界面。
              </p>
            </div>
          </div>
        )}
      </aside>
    </>
  );
}

function MemoryDialog({
  state,
  dispatch,
}: {
  state: UiState;
  dispatch: React.Dispatch<Parameters<typeof appReducer>[1]>;
}) {
  const [draft, setDraft] = useState<Preferences>(state.preferences);
  useEffect(() => setDraft(state.preferences), [state.preferences, state.memoryDialogOpen]);
  if (!state.memoryDialogOpen) {
    return null;
  }

  const tabs: Array<{ id: MemoryTab; label: string; count?: number }> = [
    { id: "preferences", label: "偏好设置" },
    { id: "confirmed", label: "已确认记忆", count: state.memories.length },
    {
      id: "candidates",
      label: "待确认",
      count: state.memoryCandidates.length,
    },
    { id: "privacy", label: "隐私说明" },
  ];

  return (
    <div className="modal-layer" role="presentation">
      <button
        className="modal-scrim"
        aria-label="关闭记忆与偏好"
        onClick={() => dispatch({ type: "closeMemory" })}
      />
      <section
        className="modal memory-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="memory-dialog-title"
        data-testid="memory-dialog"
      >
        <header className="modal-header">
          <div>
            <span className="eyebrow">由你掌控</span>
            <h2 id="memory-dialog-title">记忆与偏好</h2>
          </div>
          <button
            className="icon-button"
            aria-label="关闭"
            onClick={() => dispatch({ type: "closeMemory" })}
          >
            <X size={19} />
          </button>
        </header>
        <nav className="modal-tabs" aria-label="记忆与偏好分类">
          {tabs.map((tab) => (
            <button
              className={state.memoryTab === tab.id ? "active" : ""}
              key={tab.id}
              onClick={() => dispatch({ type: "setMemoryTab", tab: tab.id })}
            >
              {tab.label}
              {typeof tab.count === "number" && <span>{tab.count}</span>}
            </button>
          ))}
        </nav>
        <div className="modal-content">
          {state.memoryTab === "preferences" && (
            <form
              className="preferences-form"
              onSubmit={(event) => {
                event.preventDefault();
                dispatch({ type: "updatePreferences", value: draft });
              }}
            >
              <label>
                助理称呼
                <input
                  value={draft.assistantName}
                  onChange={(event) =>
                    setDraft({ ...draft, assistantName: event.target.value })
                  }
                />
              </label>
              <label>
                回答风格
                <select
                  value={draft.responseStyle}
                  onChange={(event) =>
                    setDraft({
                      ...draft,
                      responseStyle: event.target.value as Preferences["responseStyle"],
                    })
                  }
                >
                  <option value="practical">务实清晰</option>
                  <option value="warm">温和陪伴</option>
                  <option value="professional">专业简洁</option>
                </select>
              </label>
              <label>
                详细程度
                <input
                  type="range"
                  min={1}
                  max={5}
                  value={draft.detailLevel}
                  onChange={(event) =>
                    setDraft({ ...draft, detailLevel: Number(event.target.value) })
                  }
                />
                <span className="range-labels">
                  <span>简短</span>
                  <strong>{draft.detailLevel}/5</strong>
                  <span>详细</span>
                </span>
              </label>
              <label className="toggle-row">
                <span>
                  <strong>主动建议</strong>
                  <small>在合适的时候给出下一步建议</small>
                </span>
                <input
                  type="checkbox"
                  checked={draft.proactiveSuggestions}
                  onChange={(event) =>
                    setDraft({ ...draft, proactiveSuggestions: event.target.checked })
                  }
                />
              </label>
              <button className="primary-button align-right" type="submit">
                保存偏好
              </button>
            </form>
          )}
          {state.memoryTab === "confirmed" && (
            <div className="memory-list">
              {state.memories.map((memory) => (
                <article className="memory-item" key={memory.id}>
                  <div>
                    <span className="memory-category">{memory.category}</span>
                    <p>{memory.content}</p>
                    <small>
                      来源：{memory.source} · {memory.updatedAt}
                    </small>
                  </div>
                  <label className="switch">
                    <span className="sr-only">启用这条记忆</span>
                    <input
                      type="checkbox"
                      checked={memory.active}
                      onChange={() => dispatch({ type: "toggleMemory", id: memory.id })}
                    />
                    <span />
                  </label>
                </article>
              ))}
            </div>
          )}
          {state.memoryTab === "candidates" && (
            <div className="memory-list">
              {!state.memoryCandidates.length && (
                <div className="memory-empty">
                  <CheckCircle2 size={28} />
                  <strong>没有待确认的记忆</strong>
                  <p>新候选只有经过你的明确确认，才会成为长期记忆。</p>
                </div>
              )}
              {state.memoryCandidates.map((candidate) => (
                <article className="candidate-item" key={candidate.id}>
                  <span className="candidate-mark">
                    <Brain size={19} />
                  </span>
                  <div>
                    <p>{candidate.content}</p>
                    <small>{candidate.reason}</small>
                    <em>来源：{candidate.source}</em>
                    <div className="candidate-actions">
                      <button
                        className="secondary-button danger-text"
                        onClick={() =>
                          dispatch({ type: "rejectMemoryCandidate", id: candidate.id })
                        }
                      >
                        不记住
                      </button>
                      <button
                        className="primary-button"
                        onClick={() =>
                          dispatch({ type: "approveMemoryCandidate", id: candidate.id })
                        }
                      >
                        <Check size={16} /> 确认记住
                      </button>
                    </div>
                  </div>
                </article>
              ))}
            </div>
          )}
          {state.memoryTab === "privacy" && (
            <div className="privacy-panel">
              <span className="privacy-mark">
                <ShieldCheck size={25} />
              </span>
              <h3>长期记忆不会自动生效</h3>
              <p>
                助理可以提出记忆候选，但每一条都需要你明确确认。你可以随时停用或删除已确认记忆。
              </p>
              <ul>
                <li>偏好设置与长期记忆分开管理。</li>
                <li>记忆会保留来源说明，便于检查。</li>
                <li>关闭记忆后，新任务不会读取长期记忆。</li>
              </ul>
              <label className="toggle-row privacy-toggle">
                <span>
                  <strong>允许在新任务中使用长期记忆</strong>
                  <small>关闭不会删除已有记录</small>
                </span>
                <input
                  type="checkbox"
                  checked={draft.memoryEnabled}
                  onChange={(event) => {
                    const next = { ...draft, memoryEnabled: event.target.checked };
                    setDraft(next);
                    dispatch({ type: "updatePreferences", value: next });
                  }}
                />
              </label>
            </div>
          )}
        </div>
      </section>
    </div>
  );
}

function ArtifactDialog({
  artifact,
  dispatch,
}: {
  artifact?: Artifact;
  dispatch: React.Dispatch<Parameters<typeof appReducer>[1]>;
}) {
  if (!artifact) {
    return null;
  }
  const download = () => {
    const blob = new Blob([artifact.preview], { type: artifact.mediaType });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = artifact.name;
    anchor.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="modal-layer">
      <button
        className="modal-scrim"
        aria-label="关闭交付物预览"
        onClick={() => dispatch({ type: "closeArtifact" })}
      />
      <section
        className="modal artifact-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="artifact-title"
        data-testid="artifact-dialog"
      >
        <header className="modal-header">
          <div>
            <span className="eyebrow">交付物 · v{artifact.version}</span>
            <h2 id="artifact-title">{artifact.name}</h2>
            <p>
              {artifact.mediaType} · {artifact.sizeLabel}
            </p>
          </div>
          <button
            className="icon-button"
            aria-label="关闭"
            onClick={() => dispatch({ type: "closeArtifact" })}
          >
            <X size={19} />
          </button>
        </header>
        <pre className="artifact-preview">{artifact.preview}</pre>
        <footer className="modal-footer">
          <p>通过逻辑交付物 ID 获取内容，界面不会暴露服务器文件路径。</p>
          <button className="primary-button" onClick={download}>
            <Download size={17} /> 下载
          </button>
        </footer>
      </section>
    </div>
  );
}

export default function App() {
  const [state, dispatch] = useReducer(appReducer, undefined, createEmptyUiState);
  const [loading, setLoading] = useState(true);
  const timers = useRef<number[]>([]);

  useEffect(() => {
    let active = true;
    client.bootstrap().then((snapshot) => {
      if (active) {
        dispatch({ type: "hydrate", snapshot });
        setLoading(false);
      }
    });
    return () => {
      active = false;
      timers.current.forEach(window.clearTimeout);
    };
  }, []);

  useEffect(() => {
    if (!state.toast) {
      return;
    }
    const timer = window.setTimeout(() => dispatch({ type: "clearToast" }), 3200);
    return () => window.clearTimeout(timer);
  }, [state.toast]);

  const detail = state.details[state.selectedConversationId];
  const selectedArtifact = useMemo(
    () =>
      detail?.artifacts.find((artifact) => artifact.id === state.artifactPreviewId),
    [detail, state.artifactPreviewId],
  );
  const pendingMemoryCount = state.memoryCandidates.length;

  const simulateReply = (conversationId: string) => {
    const chunks = [
      "我已经收到你的任务。先把目标和限制整理清楚，",
      "再按优先级给出一个可执行的方案。",
      "\n\n当前是前端 Mock 演示；正式后端接入后，消息会通过统一的 Run 语义执行。",
    ];
    let delay = 450;
    chunks.forEach((delta) => {
      timers.current.push(
        window.setTimeout(
          () =>
            dispatch({
              type: "appendAssistantDelta",
              conversationId,
              delta,
            }),
          delay,
        ),
      );
      delay += 430;
    });
    timers.current.push(
      window.setTimeout(
        () => dispatch({ type: "completeAssistantReply", conversationId }),
        delay + 180,
      ),
    );
  };

  const submit = () => {
    const text = state.composer.trim();
    if (!text || !detail) {
      return;
    }
    const wasActive = Boolean(detail.run && activeStatuses.has(detail.run.status));
    dispatch({
      type: "submitMessage",
      text,
      now: new Intl.DateTimeFormat("zh-CN", {
        hour: "2-digit",
        minute: "2-digit",
        hour12: false,
      }).format(new Date()),
    });
    if (!wasActive) {
      simulateReply(detail.summary.id);
    }
  };

  const completeApprovedRun = () => {
    timers.current.push(
      window.setTimeout(() => dispatch({ type: "completeApprovedRun" }), 900),
    );
  };

  if (loading) {
    return (
      <div className="loading-screen">
        <span className="brand-mark">
          <Sparkles size={21} />
        </span>
        <strong>正在准备个人助理…</strong>
      </div>
    );
  }

  return (
    <div className="app-shell" data-testid="app-shell">
      <header className="topbar">
        <div className="topbar-left">
          <button
            className="icon-button mobile-only"
            aria-label="打开会话列表"
            onClick={() => dispatch({ type: "toggleSidebar", open: true })}
          >
            <Menu size={20} />
          </button>
          <span className="brand-mark">
            <Sparkles size={20} />
          </span>
          <div className="brand-copy">
            <strong>Haifa Personal</strong>
            <span>个人助理</span>
          </div>
        </div>
        <div className="topbar-actions">
          <span className="mock-badge">Mock 模式 · :20000</span>
          <button
            className="header-button"
            onClick={() => dispatch({ type: "openMemory", tab: "candidates" })}
          >
            <Brain size={17} />
            <span>记忆与偏好</span>
            {pendingMemoryCount > 0 && <b>{pendingMemoryCount}</b>}
          </button>
          <button
            className={`header-button ${state.taskPanelOpen ? "active" : ""}`}
            onClick={() => dispatch({ type: "toggleTaskPanel" })}
          >
            <PanelRight size={17} />
            <span>任务详情</span>
          </button>
          <span className="user-chip" title="当前可信调用者">
            <UserRound size={16} />
            公共用户
          </span>
        </div>
      </header>
      <div className="workspace">
        <SessionSidebar state={state} dispatch={dispatch} />
        <ConversationView
          state={state}
          detail={detail}
          dispatch={dispatch}
          onSubmit={submit}
          onApproved={completeApprovedRun}
        />
        <TaskPanel detail={detail} state={state} dispatch={dispatch} />
      </div>
      <MemoryDialog state={state} dispatch={dispatch} />
      <ArtifactDialog artifact={selectedArtifact} dispatch={dispatch} />
      {state.toast && (
        <div className="toast" role="status">
          <CheckCircle2 size={17} />
          {state.toast}
        </div>
      )}
    </div>
  );
}

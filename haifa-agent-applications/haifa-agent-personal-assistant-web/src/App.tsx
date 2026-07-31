import {
  Archive,
  Bot,
  Brain,
  Check,
  ChevronDown,
  ChevronRight,
  ChevronUp,
  CircleAlert,
  Copy,
  Cpu,
  Database,
  Menu,
  MessageSquarePlus,
  PanelRight,
  RefreshCw,
  Search,
  Send,
  ShieldCheck,
  Square,
  X,
  Zap,
} from "lucide-react";
import {
  type FormEvent,
  type MouseEvent,
  useCallback,
  useEffect,
  useReducer,
  useRef,
  useState,
} from "react";
import type {
  Activity,
  Conversation,
  Interaction,
  Memory,
  MemoryCandidate,
  Model,
  Run,
} from "./api/generated";
import {
  HttpPersonalAssistantClient,
  PersonalAssistantApiError,
  type PersonalAssistantClient,
} from "./api/client";
import { appReducer, initialState } from "./state/appReducer";
import { renderMarkdown } from "./utils/markdownRenderer";

const defaultClient = new HttpPersonalAssistantClient();
const terminalStatuses = new Set(["COMPLETED", "FAILED", "CANCELLED", "TIMEOUT"]);
const conversationIdParameter = "conversationId";
const approvalPreviewCharacters = 640;
const approvalPreviewLines = 14;
const number = new Intl.NumberFormat("zh-CN");
const dateTime = new Intl.DateTimeFormat("zh-CN", {
  month: "numeric",
  day: "numeric",
  hour: "2-digit",
  minute: "2-digit",
});

type SlashMenuState =
  | { stage: "commands" }
  | { stage: "providers" }
  | { stage: "models"; providerId: string };

interface ModelProviderGroup {
  id: string;
  displayName: string;
  models: Model[];
}

const slashCommands = [
  {
    id: "model",
    command: "/model",
    label: "选择模型",
    description: "按模型厂商和模型切换当前会话使用的 LLM",
  },
] as const;

function groupModelsByProvider(models: Model[]): ModelProviderGroup[] {
  const providers = new Map<string, ModelProviderGroup>();
  models.forEach((model) => {
    const existing = providers.get(model.providerId);
    if (existing) {
      existing.models.push(model);
      return;
    }
    providers.set(model.providerId, {
      id: model.providerId,
      displayName: model.providerDisplayName,
      models: [model],
    });
  });
  return [...providers.values()];
}

function safeError(error: unknown): string {
  if (error instanceof DOMException && error.name === "AbortError") return "";
  if (error instanceof PersonalAssistantApiError) {
    return `${error.message}（${error.code}，关联号 ${error.correlationId}）`;
  }
  return error instanceof Error ? error.message : "请求失败，请稍后重试";
}

function isTerminal(run: Run | null): boolean {
  return Boolean(run && terminalStatuses.has(run.status));
}

function conversationIdFromUrl(): string | null {
  const value = new URL(window.location.href).searchParams.get(conversationIdParameter)?.trim();
  return value || null;
}

function updateConversationUrl(conversationId: string | null, mode: "push" | "replace"): void {
  const url = new URL(window.location.href);
  if (conversationId) url.searchParams.set(conversationIdParameter, conversationId);
  else url.searchParams.delete(conversationIdParameter);
  window.history[mode === "push" ? "pushState" : "replaceState"](
    { conversationId },
    "",
    url,
  );
}

function MessageContent({ text }: { text: string }) {
  const handleClick = useCallback(async (event: MouseEvent<HTMLDivElement>) => {
    const target = event.target as HTMLElement;
    const button = target.closest<HTMLButtonElement>(".copy-code-button");
    if (!button) return;

    const code = button
      .closest(".code-block-wrapper")
      ?.querySelector("pre code")
      ?.textContent;
    if (code === undefined || !navigator.clipboard) return;

    try {
      await navigator.clipboard.writeText(code);
      button.dataset.copied = "true";
      button.setAttribute("aria-label", "代码已复制");
      button.setAttribute("title", "代码已复制");
      const label = button.querySelector(".copy-code-label");
      if (label) label.textContent = "已复制";
      window.setTimeout(() => {
        delete button.dataset.copied;
        button.setAttribute("aria-label", "复制代码");
        button.setAttribute("title", "复制代码");
        if (label) label.textContent = "复制";
      }, 2000);
    } catch {
      // Clipboard access can be denied by the browser; leave the control retryable.
    }
  }, []);

  return (
    <div
      className="message-content"
      onClick={handleClick}
      dangerouslySetInnerHTML={{ __html: renderMarkdown(text) }}
    />
  );
}

function MessageCopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);
  const resetTimer = useRef<number | null>(null);

  useEffect(() => () => {
    if (resetTimer.current !== null) window.clearTimeout(resetTimer.current);
  }, []);

  const copy = async () => {
    if (!navigator.clipboard) return;
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      if (resetTimer.current !== null) window.clearTimeout(resetTimer.current);
      resetTimer.current = window.setTimeout(() => setCopied(false), 2000);
    } catch {
      // Clipboard access can be denied by the browser; leave the control retryable.
    }
  };

  return (
    <button
      type="button"
      className="message-copy-button"
      onClick={copy}
      aria-label={copied ? "完整回答已复制" : "复制完整回答"}
      title={copied ? "完整回答已复制" : "复制完整回答"}
    >
      {copied ? <Check size={14} /> : <Copy size={14} />}
      <span>{copied ? "已复制" : "复制"}</span>
    </button>
  );
}

function statusLabel(status: string): string {
  const labels: Record<string, string> = {
    ACTIVE: "活跃",
    ARCHIVED: "已归档",
    CREATED: "已创建",
    RUNNING: "运行中",
    WAITING_FOR_INTERACTION: "等待回复",
    WAITING_FOR_APPROVAL: "等待确认",
    WAITING_INTERACTION: "等待回复",
    WAITING_APPROVAL: "等待确认",
    COMPLETED: "已完成",
    FAILED: "失败",
    CANCELLED: "已停止",
    TIMEOUT: "已超时",
    STARTED: "进行中",
    SUCCEEDED: "已完成",
    APPROVE: "批准",
    REJECT: "拒绝",
    SUBMIT: "提交",
  };
  return labels[status] ?? status;
}

function formatTime(value: string): string {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : dateTime.format(parsed);
}

function previewApprovalContent(value: string): {
  content: string;
  preview: string;
  truncated: boolean;
} {
  const content = value.trim();
  let previewEnd = Math.min(content.length, approvalPreviewCharacters);
  let lineCount = 0;
  for (let index = 0; index < content.length; index += 1) {
    if (content[index] !== "\n") continue;
    lineCount += 1;
    if (lineCount === approvalPreviewLines) {
      previewEnd = Math.min(previewEnd, index);
      break;
    }
  }
  return {
    content,
    preview: content.slice(0, previewEnd).trimEnd(),
    truncated: previewEnd < content.length,
  };
}

function Button({
  children,
  busy = false,
  ...props
}: React.ButtonHTMLAttributes<HTMLButtonElement> & { busy?: boolean }) {
  return (
    <button {...props} disabled={props.disabled || busy}>
      {busy ? <RefreshCw className="spin" size={16} aria-hidden="true" /> : children}
    </button>
  );
}

function ConversationSidebar({
  conversations,
  selectedId,
  search,
  showArchived,
  open,
  onSearch,
  onSelect,
  onNew,
  onToggleArchived,
  onRename,
  onArchive,
  onClose,
}: {
  conversations: Conversation[];
  selectedId: string | null;
  search: string;
  showArchived: boolean;
  open: boolean;
  onSearch(value: string): void;
  onSelect(id: string): void;
  onNew(): void;
  onToggleArchived(): void;
  onRename(value: Conversation): void;
  onArchive(value: Conversation): void;
  onClose(): void;
}) {
  const visible = conversations.filter((conversation) => {
    const archived = conversation.status === "ARCHIVED";
    return (
      (showArchived ? archived : !archived) &&
      conversation.displayName.toLocaleLowerCase().includes(search.toLocaleLowerCase())
    );
  });
  return (
    <>
      {open && <button className="scrim" aria-label="关闭会话列表" onClick={onClose} />}
      <aside className={`sidebar ${open ? "drawer-open" : ""}`} aria-label="会话列表">
        <div className="sidebar-title">
          <div><span className="eyebrow">CONVERSATIONS</span><strong>{visible.length} 个会话</strong></div>
          <button className="icon primary" aria-label="新建会话" onClick={onNew}>
            <MessageSquarePlus size={18} />
          </button>
        </div>
        <label className="search">
          <Search size={16} aria-hidden="true" />
          <span className="sr-only">搜索会话</span>
          <input value={search} onChange={(event) => onSearch(event.target.value)} placeholder="搜索会话" />
        </label>
        <button className="archive-filter" onClick={onToggleArchived}>
          {showArchived ? <RefreshCw size={15} /> : <Archive size={15} />}
          {showArchived ? "返回活跃会话" : "查看已归档会话"}
        </button>
        <div className="conversation-list">
          {visible.map((conversation) => (
            <article className={`conversation-row ${conversation.id === selectedId ? "selected" : ""}`} key={conversation.id}>
              <button className="conversation-select" onClick={() => onSelect(conversation.id)}>
                <strong>{conversation.displayName}</strong>
                <span>{formatTime(conversation.lastActivityAt)}</span>
                <small>{conversation.activeRunId ? "任务处理中" : statusLabel(conversation.status)}</small>
              </button>
              <div className="row-actions">
                <button aria-label={`重命名 ${conversation.displayName}`} onClick={() => onRename(conversation)}>编辑</button>
                <button
                  aria-label={`${conversation.status === "ARCHIVED" ? "取消归档" : "归档"} ${conversation.displayName}`}
                  onClick={() => onArchive(conversation)}
                >
                  {conversation.status === "ARCHIVED" ? "恢复" : "归档"}
                </button>
              </div>
            </article>
          ))}
          {!visible.length && (
            <div className="empty compact"><Bot size={28} /><span>{showArchived ? "还没有已归档会话" : "开始一次新对话吧"}</span></div>
          )}
        </div>
        <div className="privacy-note"><ShieldCheck size={17} /><span>身份由本机 Server 的可信上下文确定</span></div>
      </aside>
    </>
  );
}

function UsagePanel({ run }: { run: Run | null }) {
  if (!run || !isTerminal(run)) return <p className="muted">任务结束后显示后端报告的 Token 使用量。</p>;
  const usage = run.usage;
  return (
    <div className="usage-grid" aria-label="本次运行 Token 消耗">
      <span>输入<strong>{number.format(usage.inputTokens)}</strong></span>
      <span>输出<strong>{number.format(usage.outputTokens)}</strong></span>
      <span>总计<strong>{number.format(usage.totalTokens)}</strong></span>
      <span>缓存输入<strong>{number.format(usage.cachedInputTokens)}</strong></span>
      <span>模型调用<strong>{number.format(usage.modelCalls)}</strong></span>
      <span>工具调用<strong>{number.format(usage.toolCalls)}</strong></span>
    </div>
  );
}

function ActivityIcon({ kind }: { kind: Activity["kind"] }) {
  if (kind === "SKILL") return <Zap size={17} />;
  if (kind === "MCP") return <Database size={17} />;
  return <Cpu size={17} />;
}

function InteractionCard({
  interaction,
  pending,
  onRespond,
}: {
  interaction: Interaction;
  pending: boolean;
  onRespond(action: string, text: string): void;
}) {
  const [text, setText] = useState("");
  const [expanded, setExpanded] = useState(false);
  const approvalContent = previewApprovalContent(interaction.safePrompt);
  const isApproval = interaction.kind.toLowerCase().includes("approval")
    || interaction.allowedActions.some((action) => (
      ["APPROVE", "REJECT"].includes(action.toUpperCase())
    ));
  const titleId = `interaction-title-${interaction.id}`;
  const contentId = `interaction-content-${interaction.id}`;

  useEffect(() => {
    setText("");
    setExpanded(false);
  }, [interaction.id]);

  return (
    <section className="interaction-card" aria-labelledby={titleId}>
      <header className="interaction-heading">
        <span className="interaction-icon"><ShieldCheck size={20} /></span>
        <div>
          <span className="eyebrow">{isApproval ? "需要你的审批" : "需要你的回复"}</span>
          <h3 id={titleId}>{interaction.title}</h3>
        </div>
        <span className="interaction-state">待处理</span>
      </header>

      <div className="interaction-body">
        <div className="interaction-context">
          <span className="interaction-type">{interaction.kind}</span>
          {interaction.safePrompt.includes("Risks: HIGH") && (
            <span className="execution-risk-badge">
              <CircleAlert size={13} /> 高风险执行 · 每次必须审批
            </span>
          )}
        </div>

        <section className="approval-content-section" aria-labelledby={`${contentId}-label`}>
          <div className="approval-section-heading">
            <div>
              <h4 id={`${contentId}-label`}>{isApproval ? "审批内容" : "交互内容"}</h4>
              <span>请确认以下文本、命令或代码符合你的预期</span>
            </div>
          </div>
          <pre id={contentId} className="approval-summary">
            {expanded || !approvalContent.truncated
              ? approvalContent.content
              : `${approvalContent.preview}\n…`}
          </pre>
          {approvalContent.truncated && (
            <button
              type="button"
              className="approval-expand-button"
              aria-expanded={expanded}
              aria-controls={contentId}
              onClick={() => setExpanded((value) => !value)}
            >
              {expanded ? <ChevronUp size={15} /> : <ChevronDown size={15} />}
              {expanded ? "收起内容" : "展开查看全部内容"}
            </button>
          )}
        </section>

        {interaction.maximumCharacters > 0 && interaction.inputType !== "NONE" && (
          <label className="interaction-input">
            <span>补充信息</span>
            <textarea
              value={text}
              maxLength={interaction.maximumCharacters}
              onChange={(event) => setText(event.target.value)}
              placeholder="请输入必要信息"
            />
          </label>
        )}

        <section className="approval-options" aria-labelledby={`${contentId}-options`}>
          <div className="approval-section-heading">
            <div>
              <h4 id={`${contentId}-options`}>{isApproval ? "审批选项" : "回复选项"}</h4>
              <span>提交后任务将按你的选择继续执行</span>
            </div>
          </div>
          <div className="interaction-actions">
            {interaction.allowedActions.map((action) => {
              const normalizedAction = action.toUpperCase();
              const className = normalizedAction.includes("APPROVE")
                ? "button primary-button"
                : normalizedAction.includes("REJECT")
                  ? "button danger"
                  : "button";
              return (
                <Button
                  type="button"
                  className={className}
                  key={action}
                  busy={pending}
                  onClick={() => onRespond(action, text)}
                >
                  {statusLabel(normalizedAction)}
                </Button>
              );
            })}
          </div>
        </section>
        <small>该操作只会提交一次，并使用当前交互版本。</small>
      </div>
    </section>
  );
}

function ActivityPanel({
  open,
  run,
  activities,
  pending,
  onClose,
  onCancel,
}: {
  open: boolean;
  run: Run | null;
  activities: Activity[];
  pending: boolean;
  onClose(): void;
  onCancel(): void;
}) {
  return (
    <>
      {open && <button className="scrim right" aria-label="关闭运行详情" onClick={onClose} />}
      <aside className={`activity-panel ${open ? "drawer-open" : ""}`} aria-label="当前运行详情">
        <div className="panel-heading">
          <div><span className="eyebrow">CURRENT RUN</span><h2>{run ? statusLabel(run.status) : "暂无运行"}</h2></div>
          <button className="icon mobile-only" aria-label="关闭运行详情" onClick={onClose}><X size={18} /></button>
        </div>
        {run && !isTerminal(run) && (
          <Button className="button danger" busy={pending} onClick={onCancel}>
            <Square size={14} fill="currentColor" /> 停止当前任务
          </Button>
        )}
        <section className="panel-section">
          <h3>安全活动</h3>
          <div className="activity-list">
            {activities.map((activity) => (
              <article className="activity-card" key={activity.activityId}>
                <div className={`activity-kind kind-${activity.kind.toLowerCase()}`}>
                  <ActivityIcon kind={activity.kind} /><span>{activity.kind}</span><small>{statusLabel(activity.status)}</small>
                </div>
                <strong>{activity.displayName}</strong>
                {activity.safeTargetSummary && <pre className="activity-summary">{activity.safeTargetSummary}</pre>}
                {activity.safeResultSummary && <pre className="activity-summary safe-result">{activity.safeResultSummary}</pre>}
                <time>{formatTime(activity.startedAt)}</time>
              </article>
            ))}
            {!activities.length && <p className="muted">当前运行尚无 Tool、Skill 或 MCP 活动。</p>}
          </div>
        </section>
        <section className="panel-section"><h3>Token 使用</h3><UsagePanel run={run} /></section>
        {run?.errorCode && <div className="safe-error"><CircleAlert size={16} /><span>任务未完成：{run.errorCode}</span></div>}
      </aside>
    </>
  );
}

function MemoryDialog({
  candidates,
  memories,
  pending,
  onClose,
  onApprove,
  onReject,
  onInvalidate,
}: {
  candidates: MemoryCandidate[];
  memories: Memory[];
  pending: boolean;
  onClose(): void;
  onApprove(value: MemoryCandidate): void;
  onReject(value: MemoryCandidate): void;
  onInvalidate(value: Memory): void;
}) {
  const closeRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    closeRef.current?.focus();
    const escape = (event: KeyboardEvent) => event.key === "Escape" && onClose();
    window.addEventListener("keydown", escape);
    return () => window.removeEventListener("keydown", escape);
  }, [onClose]);
  return (
    <div className="dialog-backdrop" role="presentation" onMouseDown={onClose}>
      <section className="memory-dialog" role="dialog" aria-modal="true" aria-labelledby="memory-title" onMouseDown={(event) => event.stopPropagation()}>
        <div className="dialog-heading">
          <div><span className="eyebrow">MEMORY</span><h2 id="memory-title">记忆管理</h2></div>
          <button ref={closeRef} className="icon" aria-label="关闭记忆管理" onClick={onClose}><X size={19} /></button>
        </div>
        <p className="dialog-intro">候选记忆必须由你明确确认。停用记忆不会删除历史记录。</p>
        <div className="memory-columns">
          <section>
            <h3>待确认候选 <span>{candidates.length}</span></h3>
            {candidates.map((candidate) => (
              <article className="memory-card" key={candidate.id}>
                <small>{candidate.kind} · {candidate.subjectKey}</small><p>{candidate.content}</p><time>{formatTime(candidate.updatedAt)}</time>
                <div>
                  <Button className="button primary-button" busy={pending} onClick={() => onApprove(candidate)}>确认记住</Button>
                  <Button className="button" busy={pending} onClick={() => onReject(candidate)}>拒绝</Button>
                </div>
              </article>
            ))}
            {!candidates.length && <p className="muted">没有等待确认的候选。</p>}
          </section>
          <section>
            <h3>已确认记忆 <span>{memories.length}</span></h3>
            {memories.map((memory) => (
              <article className="memory-card" key={`${memory.id}-${memory.version}`}>
                <small>{memory.kind} · {memory.subjectKey}</small><p>{memory.content}</p>
                <div className="memory-status">
                  <span>{statusLabel(memory.status)}</span>
                  {memory.status === "ACTIVE" && <Button className="text-button" busy={pending} onClick={() => onInvalidate(memory)}>停用</Button>}
                </div>
              </article>
            ))}
            {!memories.length && <p className="muted">还没有已确认记忆。</p>}
          </section>
        </div>
      </section>
    </div>
  );
}

function TextPromptDialog({
  title,
  label,
  initialValue = "",
  onClose,
  onSubmit,
}: {
  title: string;
  label: string;
  initialValue?: string;
  onClose(): void;
  onSubmit(value: string): void;
}) {
  const [value, setValue] = useState(initialValue);
  const inputRef = useRef<HTMLInputElement>(null);
  useEffect(() => {
    inputRef.current?.focus();
    const escape = (event: KeyboardEvent) => event.key === "Escape" && onClose();
    window.addEventListener("keydown", escape);
    return () => window.removeEventListener("keydown", escape);
  }, [onClose]);
  return (
    <div className="dialog-backdrop" role="presentation" onMouseDown={onClose}>
      <form
        className="text-prompt-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="text-prompt-title"
        onSubmit={(event) => {
          event.preventDefault();
          if (value.trim()) onSubmit(value.trim());
        }}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <div className="dialog-heading">
          <h2 id="text-prompt-title">{title}</h2>
          <button type="button" className="icon" aria-label={`关闭${title}`} onClick={onClose}><X size={18} /></button>
        </div>
        <label>{label}<input ref={inputRef} value={value} onChange={(event) => setValue(event.target.value)} maxLength={256} /></label>
        <div className="prompt-actions">
          <button type="button" className="button" onClick={onClose}>取消</button>
          <button type="submit" className="button primary-button" disabled={!value.trim()}>确认</button>
        </div>
      </form>
    </div>
  );
}

export default function App({ client = defaultClient }: { client?: PersonalAssistantClient }) {
  const [state, dispatch] = useReducer(appReducer, initialState, (value) => ({
    ...value,
    selectedConversationId: conversationIdFromUrl(),
  }));
  const previousFocus = useRef<HTMLElement | null>(null);
  const interactionRequestGeneration = useRef(0);
  const [renameTarget, setRenameTarget] = useState<Conversation | null>(null);
  const [newModelId, setNewModelId] = useState("");
  const [slashMenu, setSlashMenu] = useState<SlashMenuState | null>(null);
  const [slashActiveIndex, setSlashActiveIndex] = useState(0);
  const [reasonTarget, setReasonTarget] = useState<
    { kind: "reject"; candidate: MemoryCandidate } | { kind: "invalidate"; memory: Memory } | null
  >(null);

  const loadMemories = useCallback(async (signal?: AbortSignal) => {
    const [candidates, memories] = await Promise.all([client.memoryCandidates(signal), client.memories(signal)]);
    dispatch({ type: "memoryLoaded", candidates, memories });
  }, [client]);

  const loadConversations = useCallback(async (signal?: AbortSignal) => {
    const conversations = await client.conversations("", signal);
    dispatch({ type: "conversationsLoaded", conversations });
    return conversations;
  }, [client]);

  const loadRun = useCallback(async (runId: string, signal?: AbortSignal) => {
    const run = await client.run(runId, signal);
    dispatch({ type: "runLoaded", run });
    return run;
  }, [client]);

  const loadActivities = useCallback(async (runId: string, signal?: AbortSignal) => {
    const activities = await client.activities(runId, signal);
    if (!signal?.aborted) dispatch({ type: "activitiesLoaded", activities });
    return activities;
  }, [client]);

  const loadInteraction = useCallback(async (
    runId: string,
    required: boolean,
    signal?: AbortSignal,
  ) => {
    const generation = ++interactionRequestGeneration.current;
    try {
      const interaction = await client.interaction(runId, signal);
      if (signal?.aborted || generation !== interactionRequestGeneration.current) return interaction;
      if (required && !interaction) {
        dispatch({
          type: "interactionLoadFailed",
          runId,
          message: "审批或交互详情尚未就绪，请重试；任务尚未继续执行。",
        });
        return interaction;
      }
      dispatch({ type: "interactionLoaded", interaction });
      return interaction;
    } catch (error) {
      if (!signal?.aborted && generation === interactionRequestGeneration.current && required) {
        dispatch({
          type: "interactionLoadFailed",
          runId,
          message: "审批或交互详情加载失败，请重试；任务尚未继续执行。",
        });
      }
      throw error;
    }
  }, [client]);

  const loadRunSnapshot = useCallback(async (runId: string, signal?: AbortSignal) => {
    const run = await loadRun(runId, signal);
    await Promise.allSettled([
      loadActivities(runId, signal),
      loadInteraction(
        runId,
        ["WAITING_APPROVAL", "WAITING_INTERACTION"].includes(run.status),
        signal,
      ),
    ]);
    return run;
  }, [loadActivities, loadInteraction, loadRun]);

  const selectConversation = useCallback(
    (conversationId: string | null, mode: "push" | "replace" = "push") => {
      updateConversationUrl(conversationId, mode);
      dispatch({ type: "selectConversation", conversationId });
    },
    [],
  );

  const loadConversation = useCallback(async (conversationId: string, signal?: AbortSignal) => {
    const [conversation, turns] = await Promise.all([
      client.conversation(conversationId, signal),
      client.turns(conversationId, signal),
    ]);
    dispatch({ type: "conversationLoaded", conversation });
    dispatch({ type: "turnsLoaded", turns });
    const latestRunId =
      conversation.activeRunId ??
      [...turns].reverse().find((turn) => turn.runId)?.runId ??
      null;
    if (latestRunId) await loadRunSnapshot(latestRunId, signal);
    else {
      interactionRequestGeneration.current += 1;
      dispatch({ type: "runLoaded", run: null });
      dispatch({ type: "activitiesLoaded", activities: [] });
      dispatch({ type: "interactionLoaded", interaction: null });
    }
    return conversation;
  }, [client, loadRunSnapshot]);

  useEffect(() => {
    const controller = new AbortController();
    void Promise.all([
      client.bootstrap(controller.signal),
      client.conversations("", controller.signal),
      client.memoryCandidates(controller.signal),
      client.memories(controller.signal),
    ]).then(([bootstrap, conversations, memoryCandidates, memories]) => {
      setNewModelId(bootstrap.defaultModelId);
      dispatch({ type: "bootstrapLoaded", bootstrap, conversations, memoryCandidates, memories });
    }).catch((error) => {
      const message = safeError(error);
      if (message) dispatch({ type: "error", message });
    });
    return () => controller.abort();
  }, [client]);

  useEffect(() => {
    if (state.loading || conversationIdFromUrl() === state.selectedConversationId) return;
    updateConversationUrl(state.selectedConversationId, "replace");
  }, [state.loading, state.selectedConversationId]);

  useEffect(() => {
    const restoreConversationFromUrl = () => {
      const requested = conversationIdFromUrl();
      const selected =
        requested === null
          ? null
          : state.conversations.some((conversation) => conversation.id === requested)
            ? requested
            : (state.conversations.find((conversation) => conversation.status !== "ARCHIVED")?.id ??
              null);
      dispatch({ type: "selectConversation", conversationId: selected });
    };
    window.addEventListener("popstate", restoreConversationFromUrl);
    return () => window.removeEventListener("popstate", restoreConversationFromUrl);
  }, [state.conversations]);

  useEffect(() => {
    if (!state.selectedConversationId) return;
    const controller = new AbortController();
    void loadConversation(state.selectedConversationId, controller.signal).catch((error) => {
      const message = safeError(error);
      if (message) dispatch({ type: "error", message });
    });
    return () => controller.abort();
  }, [loadConversation, state.selectedConversationId]);

  useEffect(() => {
    if (!state.run || isTerminal(state.run)) return;
    const runId = state.run.id;
    const conversationId = state.selectedConversationId;
    const controller = new AbortController();
    void (async () => {
      let retry = false;
      while (!controller.signal.aborted) {
        try {
          dispatch({ type: "setConnection", connection: retry ? "reconnecting" : "connecting" });
          await client.streamRun(runId, {
            onOpen: () => dispatch({ type: "setConnection", connection: "connected" }),
            onEvent: (event) => {
              dispatch({ type: "streamEvent", event });
              if (event.type === "run.status") {
                void loadRun(runId, controller.signal).catch(() => undefined);
              } else if (event.type === "interaction.status") {
                void loadInteraction(
                  runId,
                  event.value === "PENDING",
                  controller.signal,
                ).catch(() => undefined);
              } else if (event.type === "activity.committed" && !event.activity) {
                void loadActivities(runId, controller.signal).catch(() => undefined);
              }
            },
          }, controller.signal);
          if (controller.signal.aborted) return;
          const latest = await loadRunSnapshot(runId, controller.signal);
          if (isTerminal(latest)) {
            if (conversationId) {
              for (let attempt = 0; attempt < 60; attempt += 1) {
                const settled = await loadConversation(conversationId, controller.signal);
                await loadConversations(controller.signal);
                if (!settled.activeRunId) break;
                await new Promise((resolve) => window.setTimeout(resolve, 500));
              }
            } else {
              await loadConversations(controller.signal);
            }
            return;
          }
        } catch {
          if (controller.signal.aborted) return;
          dispatch({ type: "setConnection", connection: "reconnecting" });
          await loadRunSnapshot(runId, controller.signal).catch(() => undefined);
        }
        retry = true;
        await new Promise((resolve) => window.setTimeout(resolve, 800));
      }
    })();
    return () => controller.abort();
  }, [
    client,
    loadActivities,
    loadConversation,
    loadConversations,
    loadInteraction,
    loadRun,
    loadRunSnapshot,
    state.run?.id,
    state.selectedConversationId,
  ]);

  const execute = useCallback(async (label: string, operation: () => Promise<void>) => {
    dispatch({ type: "commandStarted", command: { id: crypto.randomUUID(), label } });
    try {
      await operation();
    } catch (error) {
      const message = safeError(error);
      if (message) dispatch({ type: "error", message });
    } finally {
      dispatch({ type: "commandFinished" });
    }
  }, []);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const message = state.composer.trim();
    if (!message || state.pending || state.selectedConversation?.activeRunId) return;
    const key = crypto.randomUUID();
    void execute("提交消息", async () => {
      const conversation = state.selectedConversation
        ? await client.submitMessage(state.selectedConversation, message, { idempotencyKey: key })
        : await client.createConversation(
            message.slice(0, 32),
            message,
            { idempotencyKey: key },
            newModelId || state.bootstrap?.defaultModelId,
          );
      dispatch({ type: "setComposer", value: "" });
      if (state.selectedConversationId !== conversation.id) {
        selectConversation(conversation.id, "replace");
      } else {
        dispatch({ type: "conversationLoaded", conversation });
        await loadConversation(conversation.id);
      }
      await loadConversations();
    });
  };

  const selectModel = (modelId: string) => {
    const conversation = state.selectedConversation;
    if (!conversation) {
      setNewModelId(modelId);
      return;
    }
    if (conversation.activeRunId || !client.selectModel) return;
    void execute("切换模型", async () => {
      await client.selectModel!(conversation, modelId, { idempotencyKey: crypto.randomUUID() });
      await loadConversation(conversation.id);
      await loadConversations();
    });
  };

  const configuredModels = state.bootstrap?.models ?? [];
  const modelProviders = groupModelsByProvider(configuredModels);
  const selectedModelId = state.selectedConversation?.model.model.id ?? newModelId;
  const slashQuery = slashMenu?.stage === "commands"
    ? state.composer.slice(1).trim().toLocaleLowerCase()
    : "";
  const visibleSlashCommands = slashCommands.filter((command) =>
    !slashQuery
    || command.command.slice(1).includes(slashQuery)
    || command.label.toLocaleLowerCase().includes(slashQuery)
  );
  const selectedSlashProvider = slashMenu?.stage === "models"
    ? modelProviders.find((provider) => provider.id === slashMenu.providerId) ?? null
    : null;
  const slashItemCount = slashMenu?.stage === "commands"
    ? visibleSlashCommands.length
    : slashMenu?.stage === "providers"
      ? modelProviders.length
      : selectedSlashProvider?.models.length ?? 0;

  const updateComposer = (value: string) => {
    dispatch({ type: "setComposer", value });
    const slashInput = value.startsWith("/") && !value.includes("\n");
    if (!slashInput) {
      setSlashMenu(null);
    } else if (!slashMenu || (slashMenu.stage !== "commands" && value !== "/model")) {
      setSlashMenu({ stage: "commands" });
    }
    setSlashActiveIndex(0);
  };

  const activateSlashItem = (index: number) => {
    if (!slashMenu) return;
    if (slashMenu.stage === "commands") {
      if (!visibleSlashCommands[index]) return;
      dispatch({ type: "setComposer", value: "/model" });
      setSlashMenu({ stage: "providers" });
      setSlashActiveIndex(0);
      return;
    }
    if (slashMenu.stage === "providers") {
      const provider = modelProviders[index];
      if (!provider) return;
      setSlashMenu({ stage: "models", providerId: provider.id });
      setSlashActiveIndex(0);
      return;
    }
    const model = selectedSlashProvider?.models[index];
    if (!model) return;
    selectModel(model.id);
    dispatch({ type: "setComposer", value: "" });
    setSlashMenu(null);
    setSlashActiveIndex(0);
  };

  const returnFromSlashMenu = () => {
    if (slashMenu?.stage === "models") {
      setSlashMenu({ stage: "providers" });
    } else {
      dispatch({ type: "setComposer", value: "/" });
      setSlashMenu({ stage: "commands" });
    }
    setSlashActiveIndex(0);
  };

  const rename = (conversation: Conversation, displayName: string) => {
    if (!displayName || displayName === conversation.displayName) return;
    const key = crypto.randomUUID();
    void execute("重命名会话", async () => {
      const updated = await client.updateConversation(conversation, { displayName }, { idempotencyKey: key });
      dispatch({ type: "conversationLoaded", conversation: updated });
      await loadConversations();
    });
  };

  const archive = (conversation: Conversation) => {
    const key = crypto.randomUUID();
    const status = conversation.status === "ARCHIVED" ? "ACTIVE" : "ARCHIVED";
    void execute(status === "ARCHIVED" ? "归档会话" : "恢复会话", async () => {
      await client.updateConversation(conversation, { status }, { idempotencyKey: key });
      const conversations = await loadConversations();
      if (conversation.id === state.selectedConversationId && status === "ARCHIVED") {
        selectConversation(
          conversations.find((value) => value.status !== "ARCHIVED")?.id ?? null,
          "replace",
        );
      }
    });
  };

  const cancel = () => {
    if (!state.run || isTerminal(state.run)) return;
    const runId = state.run.id;
    const key = crypto.randomUUID();
    void execute("停止任务", async () => {
      dispatch({ type: "runLoaded", run: await client.cancelRun(runId, { idempotencyKey: key }) });
      if (state.selectedConversationId) await loadConversation(state.selectedConversationId);
      await loadConversations();
    });
  };

  const respond = (action: string, text: string) => {
    if (!state.interaction) return;
    const interaction = state.interaction;
    const key = crypto.randomUUID();
    void execute("提交交互回复", async () => {
      await client.respondToInteraction(interaction, action, text, { idempotencyKey: key });
      await loadRunSnapshot(interaction.runId);
    });
  };

  const memoryCommand = (label: string, operation: (key: string) => Promise<unknown>) => {
    const key = crypto.randomUUID();
    void execute(label, async () => {
      await operation(key);
      await loadMemories();
    });
  };

  const closeMemory = useCallback(() => {
    dispatch({ type: "toggleMemory", open: false });
    window.setTimeout(() => previousFocus.current?.focus(), 0);
  }, []);

  const runActive = Boolean(state.selectedConversation?.activeRunId) && !isTerminal(state.run);
  const composerDisabled = Boolean(state.pending) || runActive;

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand">
          <button className="icon mobile-only" aria-label="打开会话列表" onClick={() => dispatch({ type: "toggleSidebar", open: true })}><Menu size={20} /></button>
          <div className="brand-mark"><Brain size={20} /></div>
          <div><strong>{state.bootstrap?.product ?? "Haifa Personal Assistant"}</strong><span>本机个人助理</span></div>
        </div>
        <div className="top-actions">
          <span className={`connection connection-${state.connection}`} role="status"><i />{
            state.connection === "connected" ? "Server 已连接" :
              state.connection === "reconnecting" ? "正在重新连接" :
                state.connection === "connecting" ? "正在连接" : "连接中断"
          }</span>
          <button className="button memory-button" onClick={() => {
            previousFocus.current = document.activeElement as HTMLElement;
            dispatch({ type: "toggleMemory", open: true });
            void loadMemories().catch((error) => dispatch({ type: "error", message: safeError(error) }));
          }}>
            <Brain size={16} /> 记忆{state.memoryCandidates.length > 0 && <b>{state.memoryCandidates.length}</b>}
          </button>
          <button className="icon mobile-only" aria-label="打开运行详情" onClick={() => dispatch({ type: "toggleActivity", open: true })}><PanelRight size={20} /></button>
        </div>
      </header>

      <div className="workspace">
        <ConversationSidebar
          conversations={state.conversations}
          selectedId={state.selectedConversationId}
          search={state.search}
          showArchived={state.showArchived}
          open={state.sidebarOpen}
          onSearch={(value) => dispatch({ type: "setSearch", value })}
          onSelect={(conversationId) => selectConversation(conversationId)}
          onNew={() => selectConversation(null)}
          onToggleArchived={() => dispatch({ type: "toggleArchived" })}
          onRename={setRenameTarget}
          onArchive={archive}
          onClose={() => dispatch({ type: "toggleSidebar", open: false })}
        />

        <main className="conversation">
          <div className="conversation-heading">
            <div><span className="eyebrow">PERSONAL ASSISTANT</span><h1>{state.selectedConversation?.displayName ?? "新会话"}</h1></div>
            <div className="conversation-model-controls">
              <label>
                <span className="sr-only">选择模型</span>
                <select
                  aria-label="选择模型"
                  value={state.selectedConversation?.model.model.id ?? newModelId}
                  disabled={composerDisabled}
                  onChange={(event) => selectModel(event.target.value)}
                >
                  {(state.bootstrap?.models ?? []).map((model) => (
                    <option key={model.id} value={model.id}>
                      {model.displayName} · {model.providerDisplayName}
                    </option>
                  ))}
                </select>
              </label>
              {state.run && <span className="run-state">{statusLabel(state.run.status)}</span>}
            </div>
          </div>
          {state.error && (
            <div className="error-banner" role="alert">
              <CircleAlert size={17} /><span>{state.error}</span><button onClick={() => window.location.reload()}>重新加载</button>
            </div>
          )}
          <div className="messages" aria-busy={state.loading}>
            {!state.turns.length && !state.streamDraft && !state.interaction ? (
              <div className="empty hero-empty">
                <div className="assistant-mark"><Brain size={28} /></div>
                <h2>今天需要我帮你做什么？</h2>
                <p>可以直接对话，也可以让助手调用 Tool、Skill 或本机 MCP 完成任务。</p>
                <div className="suggestions"><span>整理今天的待办</span><span>使用内置 Skill 制定计划</span><span>调用 MCP 检查本地服务</span></div>
              </div>
            ) : (
              <>
                {state.turns.map((turn) => (
                  <article className={`message ${turn.role.toLowerCase() === "user" ? "user" : "assistant"}`} key={turn.id}>
                    <span className="message-role">{turn.role.toLowerCase() === "user" ? "你" : "Haifa"}</span>
                    <MessageContent text={turn.text} /><time>{formatTime(turn.createdAt)}</time>
                    {turn.role.toLowerCase() === "assistant" && (
                      <div className="message-actions">
                        <MessageCopyButton text={turn.text} />
                      </div>
                    )}
                  </article>
                ))}
                {state.streamDraft && (
                  <article className="message assistant streaming" aria-live="polite">
                    <span className="message-role">Haifa</span><MessageContent text={state.streamDraft} /><i className="caret" />
                  </article>
                )}
                {state.interaction && (
                  <InteractionCard
                    interaction={state.interaction}
                    pending={Boolean(state.pending)}
                    onRespond={respond}
                  />
                )}
              </>
            )}
          </div>
          {state.interactionError && (
            <div className="error-banner" role="alert">
              <CircleAlert size={17} /><span>{state.interactionError}</span>
            </div>
          )}
          <form className="composer" onSubmit={submit}>
            {slashMenu && !composerDisabled && (
              <section
                className="slash-command-menu"
                role="dialog"
                aria-label={
                  slashMenu.stage === "commands"
                    ? "命令功能"
                    : slashMenu.stage === "providers"
                      ? "选择模型厂商"
                      : `选择 ${selectedSlashProvider?.displayName ?? ""} 模型`
                }
              >
                <header>
                  {slashMenu.stage !== "commands" && (
                    <button type="button" className="slash-back" onClick={returnFromSlashMenu}>
                      返回
                    </button>
                  )}
                  <div>
                    <strong>
                      {slashMenu.stage === "commands"
                        ? "命令功能"
                        : slashMenu.stage === "providers"
                          ? "选择模型厂商"
                          : selectedSlashProvider?.displayName}
                    </strong>
                    <span>
                      {slashMenu.stage === "commands"
                        ? "选择一个命令继续"
                        : slashMenu.stage === "providers"
                          ? "先选择提供模型服务的厂商"
                          : "选择本会话后续消息使用的模型"}
                    </span>
                  </div>
                  <kbd>Esc</kbd>
                </header>
                <div className="slash-command-options" role="listbox">
                  {slashMenu.stage === "commands" && visibleSlashCommands.map((command, index) => (
                    <button
                      type="button"
                      role="option"
                      aria-selected={index === slashActiveIndex}
                      className={index === slashActiveIndex ? "active" : ""}
                      key={command.id}
                      onMouseEnter={() => setSlashActiveIndex(index)}
                      onClick={() => activateSlashItem(index)}
                    >
                      <code>{command.command}</code>
                      <span><strong>{command.label}</strong><small>{command.description}</small></span>
                      <ChevronRight size={17} />
                    </button>
                  ))}
                  {slashMenu.stage === "providers" && modelProviders.map((provider, index) => (
                    <button
                      type="button"
                      role="option"
                      aria-selected={index === slashActiveIndex}
                      className={index === slashActiveIndex ? "active" : ""}
                      key={provider.id}
                      onMouseEnter={() => setSlashActiveIndex(index)}
                      onClick={() => activateSlashItem(index)}
                    >
                      <span className="slash-provider-mark">{provider.displayName.slice(0, 1)}</span>
                      <span>
                        <strong>{provider.displayName}</strong>
                        <small>{provider.models.length} 个可用模型 · {provider.id}</small>
                      </span>
                      <ChevronRight size={17} />
                    </button>
                  ))}
                  {slashMenu.stage === "models" && selectedSlashProvider?.models.map((model, index) => (
                    <button
                      type="button"
                      role="option"
                      aria-selected={index === slashActiveIndex}
                      className={index === slashActiveIndex ? "active" : ""}
                      key={model.id}
                      onMouseEnter={() => setSlashActiveIndex(index)}
                      onClick={() => activateSlashItem(index)}
                    >
                      <Bot size={18} />
                      <span>
                        <strong>{model.displayName}</strong>
                        <small>{model.id}</small>
                      </span>
                      {model.id === selectedModelId
                        ? <span className="slash-current"><Check size={13} /> 当前</span>
                        : <ChevronRight size={17} />}
                    </button>
                  ))}
                  {slashItemCount === 0 && <p>没有匹配的命令或可用模型。</p>}
                </div>
                <footer><span>↑↓ 选择</span><span>Enter 确认</span></footer>
              </section>
            )}
            {runActive && (
              <div className="active-run-note">
                <span><RefreshCw className="spin" size={15} /> 当前任务运行中，完成或停止后可继续输入。</span>
                <Button type="button" className="text-button danger-text" busy={Boolean(state.pending)} onClick={cancel}>停止</Button>
              </div>
            )}
            <label>
              <span className="sr-only">给个人助理发送消息</span>
              <textarea
                value={state.composer}
                disabled={composerDisabled}
                aria-expanded={Boolean(slashMenu)}
                aria-haspopup="dialog"
                onChange={(event) => updateComposer(event.target.value)}
                onKeyDown={(event) => {
                  if (slashMenu && event.key === "Escape") {
                    event.preventDefault();
                    setSlashMenu(null);
                    return;
                  }
                  if (slashMenu && (event.key === "ArrowDown" || event.key === "ArrowUp")) {
                    event.preventDefault();
                    if (slashItemCount > 0) {
                      setSlashActiveIndex((current) =>
                        event.key === "ArrowDown"
                          ? (current + 1) % slashItemCount
                          : (current - 1 + slashItemCount) % slashItemCount
                      );
                    }
                    return;
                  }
                  if (slashMenu && event.key === "Enter" && !event.shiftKey) {
                    event.preventDefault();
                    activateSlashItem(slashActiveIndex);
                    return;
                  }
                  if (event.key === "Enter" && !event.shiftKey) {
                    event.preventDefault();
                    event.currentTarget.form?.requestSubmit();
                  }
                }}
                placeholder={runActive ? "当前任务运行中" : "输入消息或 / 命令，Enter 发送"}
                rows={2}
              />
            </label>
            <Button type="submit" className="send-button" aria-label="发送消息" busy={Boolean(state.pending)} disabled={composerDisabled || !state.composer.trim()}>
              <Send size={18} />
            </Button>
          </form>
        </main>

        <ActivityPanel
          open={state.activityOpen}
          run={state.run}
          activities={state.activities}
          pending={Boolean(state.pending)}
          onClose={() => dispatch({ type: "toggleActivity", open: false })}
          onCancel={cancel}
        />
      </div>

      {state.memoryOpen && (
        <MemoryDialog
          candidates={state.memoryCandidates}
          memories={state.memories}
          pending={Boolean(state.pending)}
          onClose={closeMemory}
          onApprove={(candidate) => memoryCommand("确认记忆", (key) => client.approveMemory(candidate, { idempotencyKey: key }))}
          onReject={(candidate) => setReasonTarget({ kind: "reject", candidate })}
          onInvalidate={(memory) => setReasonTarget({ kind: "invalidate", memory })}
        />
      )}
      {renameTarget && (
        <TextPromptDialog
          title="重命名会话"
          label="会话名称"
          initialValue={renameTarget.displayName}
          onClose={() => setRenameTarget(null)}
          onSubmit={(value) => {
            rename(renameTarget, value);
            setRenameTarget(null);
          }}
        />
      )}
      {reasonTarget && (
        <TextPromptDialog
          title={reasonTarget.kind === "reject" ? "拒绝候选记忆" : "停用记忆"}
          label="原因"
          initialValue={reasonTarget.kind === "reject" ? "不需要保存" : "不再适用"}
          onClose={() => setReasonTarget(null)}
          onSubmit={(reason) => {
            if (reasonTarget.kind === "reject") {
              const candidate = reasonTarget.candidate;
              memoryCommand("拒绝记忆", (key) => client.rejectMemory(candidate, reason, { idempotencyKey: key }));
            } else {
              const memory = reasonTarget.memory;
              memoryCommand("停用记忆", (key) => client.invalidateMemory(memory, reason, { idempotencyKey: key }));
            }
            setReasonTarget(null);
          }}
        />
      )}
      <div className="sr-only" aria-live="polite">{state.pending ? `${state.pending.label}进行中` : ""}</div>
    </div>
  );
}

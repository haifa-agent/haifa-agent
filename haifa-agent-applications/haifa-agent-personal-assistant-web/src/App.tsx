import {
  Archive,
  Bot,
  Brain,
  Check,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  ChevronUp,
  CircleAlert,
  Copy,
  Cpu,
  Database,
  Image as ImageIcon,
  Menu,
  MessageSquarePlus,
  PauseCircle,
  Link,
  Paperclip,
  PanelRight,
  Plus,
  RefreshCw,
  Search,
  Send,
  ShieldCheck,
  Sparkles,
  Square,
  Timer,
  WifiOff,
  X,
  Zap,
} from "lucide-react";
import {
  type FormEvent,
  type DragEvent,
  type KeyboardEvent as ReactKeyboardEvent,
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
  ExecutionError,
  Interaction,
  ImageInput,
  Memory,
  MemoryCandidate,
  MissionSnapshot,
  Model,
  Run,
  TurnImage,
} from "./api/generated";
import {
  HttpPersonalAssistantClient,
  PersonalAssistantApiError,
  missionArtifactUrl,
  type PersonalAssistantClient,
} from "./api/client";
import { appReducer, initialState } from "./state/appReducer";
import type { ConnectionState, OutputPhase } from "./types";
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

type PendingImage = ImageInput & {
  key: string;
  label: string;
  previewUrl?: string;
};

const opaqueImageFilename = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.[a-z0-9]+$/i;

function imageHost(url: string): string {
  try {
    return new URL(url).hostname;
  } catch {
    return "外部图片";
  }
}

function uploadedImageLabel(image: TurnImage, index: number): string {
  const filename = image.originalFilename?.trim();
  return filename && !opaqueImageFilename.test(filename) ? filename : `已上传图片 ${index + 1}`;
}

function TurnImages({ images }: { images: TurnImage[] }) {
  return (
    <div className="turn-images" aria-label={`消息包含 ${images.length} 张图片`}>
      {images.map((image, index) => {
        if (image.kind === "url" && image.url) {
          return (
            <a
              className="turn-image turn-image-preview"
              href={image.url}
              key={`${image.url}-${index}`}
              target="_blank"
              rel="noreferrer"
              aria-label={`打开第 ${index + 1} 张图片`}
            >
              <img src={image.url} alt={`第 ${index + 1} 张图片`} />
              <span><b>{index + 1}</b>{imageHost(image.url)}</span>
            </a>
          );
        }
        return (
          <div className="turn-image turn-image-file" key={`${image.imageId}-${index}`}>
            <div><ImageIcon size={22} aria-hidden="true" /></div>
            <span><b>{index + 1}</b>{uploadedImageLabel(image, index)}</span>
          </div>
        );
      })}
    </div>
  );
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

interface RecommendedQuestionState {
  runId: string;
  turnId: string;
  loading: boolean;
  questions: string[];
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

function executionErrorGuidance(error: ExecutionError): string {
  if (error.code === "TOOL_OUTCOME_UNKNOWN") return "请先确认工具是否已经生效，不要直接重复执行。";
  if (error.code === "RUN_BUDGET_EXCEEDED") return "可缩小任务范围后重新发起。";
  if (error.retryability.startsWith("RETRYABLE")) return "可以稍后重试本次请求。";
  return "如需协助，请提供诊断编号。";
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

function RecommendedQuestionList({
  state,
  disabled,
  onSelect,
}: {
  state: RecommendedQuestionState;
  disabled: boolean;
  onSelect(question: string): void;
}) {
  if (!state.loading && !state.questions.length) return null;
  return (
    <section className="recommended-questions" aria-label="推荐问题">
      <div className="recommended-questions-heading">
        {state.loading
          ? <RefreshCw className="spin" size={13} aria-hidden="true" />
          : <Sparkles size={13} aria-hidden="true" />}
        <span>{state.loading ? "正在生成可能的后续问题…" : "你还可以问"}</span>
      </div>
      {!state.loading && (
        <div className="recommended-question-list">
          {state.questions.map((question) => (
            <button
              type="button"
              key={question}
              disabled={disabled}
              onClick={() => onSelect(question)}
            >
              <Sparkles size={11} aria-hidden="true" />
              <span>{question}</span>
            </button>
          ))}
        </div>
      )}
    </section>
  );
}

function statusLabel(status: string): string {
  const labels: Record<string, string> = {
    ACTIVE: "活跃",
    ARCHIVED: "已归档",
    CREATED: "已创建",
    PENDING: "准备中",
    QUEUED: "排队中",
    RUNNING: "运行中",
    SUSPENDING: "暂停中",
    SUSPENDED: "已暂停",
    WAITING_FOR_INTERACTION: "等待回复",
    WAITING_FOR_APPROVAL: "等待确认",
    WAITING_INTERACTION: "等待回复",
    WAITING_APPROVAL: "等待确认",
    COMPLETING: "整理结果中",
    COMPLETED: "已完成",
    FAILED: "失败",
    CANCELLED: "已停止",
    TIMEOUT: "已超时",
    STARTED: "进行中",
    REQUESTED: "准备调用",
    SUCCEEDED: "已完成",
    TIMED_OUT: "已超时",
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
  if (kind === "MODEL") return <Brain size={17} />;
  if (kind === "SKILL") return <Zap size={17} />;
  if (kind === "MCP") return <Database size={17} />;
  return <Cpu size={17} />;
}

type LiveRunTone = "active" | "attention" | "success" | "danger" | "muted";

interface LiveRunPresentation {
  tone: LiveRunTone;
  label: string;
  title: string;
  detail?: string;
  icon: "activity" | "approval" | "complete" | "error" | "pause" | "timer" | "connection" | "running";
  action: "interaction" | "details" | null;
}

function latestActivity(activities: Activity[]): Activity | null {
  return activities.reduce<Activity | null>(
    (latest, activity) => (!latest || activity.version > latest.version ? activity : latest),
    null,
  );
}

const finishedActivityStatuses = new Set(["SUCCEEDED", "COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT"]);
const finishedTodoStatuses = new Set(["COMPLETED", "CANCELLED", "SKIPPED"]);

function runPhaseLabel(
  run: Run,
  activities: Activity[],
  outputPhase: OutputPhase,
): string {
  if (run.status === "WAITING_APPROVAL") return "等待审批";
  if (run.status === "WAITING_INTERACTION") return "等待回复";
  if (run.status === "COMPLETING") return "整理结果";
  if (run.status === "COMPLETED") return "已完成";
  if (["FAILED", "CANCELLED", "TIMEOUT"].includes(run.status)) return "已结束";
  if (outputPhase !== "idle") return "生成回答";
  const active = [...activities]
    .reverse()
    .find((activity) => !finishedActivityStatuses.has(activity.status.toUpperCase()));
  if (active?.kind === "MODEL") return "模型处理";
  if (active?.kind === "MCP") return "调用 MCP";
  if (active?.kind === "SKILL") return "加载 Skill";
  if (active) return "执行工具";
  if (["PENDING", "QUEUED"].includes(run.status)) return "准备任务";
  return "运行任务";
}

function formatElapsedTime(seconds: number): string {
  if (seconds < 60) return `${seconds}秒`;
  const minutes = Math.floor(seconds / 60);
  const remainingSeconds = seconds % 60;
  if (minutes < 60) return `${minutes}分${String(remainingSeconds).padStart(2, "0")}秒`;
  const hours = Math.floor(minutes / 60);
  return `${hours}时${String(minutes % 60).padStart(2, "0")}分${String(remainingSeconds).padStart(2, "0")}秒`;
}

function useActivityElapsed(startedAt?: string | null): string | null {
  const [clock, setClock] = useState(() => Date.now());

  useEffect(() => {
    setClock(Date.now());
    if (!startedAt) return;
    const timer = window.setInterval(() => setClock(Date.now()), 1_000);
    return () => window.clearInterval(timer);
  }, [startedAt]);

  if (!startedAt) return null;
  const started = Date.parse(startedAt);
  if (!Number.isFinite(started)) return null;
  const seconds = Math.max(1, Math.floor((clock - started) / 1_000) + 1);
  return `耗时 ${formatElapsedTime(seconds)}`;
}

function RunProgress({
  run,
  activities,
  outputPhase,
}: {
  run: Run;
  activities: Activity[];
  outputPhase: OutputPhase;
}) {
  const phase = runPhaseLabel(run, activities, outputPhase);
  const finishedActivities = activities.filter((activity) =>
    finishedActivityStatuses.has(activity.status.toUpperCase()),
  ).length;
  const activeActivities = activities.length - finishedActivities;
  const plan = run.plan;
  const terminal = terminalStatuses.has(run.status);
  const timedActivity = [...activities]
    .reverse()
    .find((activity) => activity.startedAt && !finishedActivityStatuses.has(activity.status.toUpperCase()));
  const elapsed = useActivityElapsed(timedActivity?.startedAt);

  if (plan?.items.length) {
    const finishedSteps = plan.items.filter((item) => finishedTodoStatuses.has(item.status)).length;
    const current = terminal
      ? undefined
      : plan.items.find((item) => ["IN_PROGRESS", "BLOCKED"].includes(item.status))
        ?? plan.items.find((item) => item.status === "PENDING");
    const progress = Math.round((finishedSteps / plan.items.length) * 100);
    return (
      <div className="live-run-progress" aria-label={`运行阶段：${phase}；计划步骤 ${finishedSteps}/${plan.items.length}`}>
        <span className="live-run-phase">{phase}</span>
        <span className="live-run-progress-track" aria-hidden="true">
          <span style={{ width: `${progress}%` }} />
        </span>
        {elapsed && <span className="live-run-elapsed">{elapsed}</span>}
        <small>
          计划步骤 {finishedSteps}/{plan.items.length}
          {current ? ` · ${current.status === "BLOCKED" ? "受阻" : "当前"}：${current.title}` : ""}
        </small>
      </div>
    );
  }

  return (
    <div className="live-run-progress" aria-label={`运行阶段：${phase}`}>
      <span className="live-run-phase">{phase}</span>
      {elapsed && <span className="live-run-elapsed">{elapsed}</span>}
      <small>
        {terminal
          ? `本次共观察 ${activities.length} 个活动`
          : activities.length
          ? `已结束活动 ${finishedActivities} · 当前活动 ${activeActivities} · 已观察 ${activities.length}`
          : "等待首个可观察活动"}
      </small>
    </div>
  );
}

function activityPresentation(activity: Activity): LiveRunPresentation {
  const status = activity.status.toUpperCase();
  const name = activity.displayName;
  const detail = ["SUCCEEDED", "COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT"].includes(status)
    ? activity.safeResultSummary || activity.safeTargetSummary
    : activity.safeTargetSummary;
  const kindLabel = activity.kind === "MODEL"
    ? "模型"
    : activity.kind === "SKILL"
      ? "Skill"
      : activity.kind === "MCP"
        ? "MCP"
        : "工具";

  if (status === "REQUESTED") {
    return {
      tone: "active",
      label: `${kindLabel}活动`,
      title: `准备调用 ${name}`,
      detail,
      icon: "activity",
      action: "details",
    };
  }
  if (status === "STARTED") {
    const verb = activity.kind === "MODEL" ? "正在请求" : activity.kind === "MCP" ? "正在调用" : "正在运行";
    return {
      tone: "active",
      label: `${kindLabel}活动`,
      title: `${verb} ${name}`,
      detail,
      icon: "activity",
      action: "details",
    };
  }
  if (["SUCCEEDED", "COMPLETED"].includes(status)) {
    return {
      tone: "success",
      label: `${kindLabel}活动`,
      title: activity.kind === "MODEL" ? "模型响应已返回" : `${name} 已完成`,
      detail,
      icon: "complete",
      action: "details",
    };
  }
  if (status === "FAILED") {
    return {
      tone: "danger",
      label: `${kindLabel}活动`,
      title: activity.kind === "MODEL" ? "模型调用失败" : `${name} 执行失败`,
      detail,
      icon: "error",
      action: "details",
    };
  }
  if (status === "TIMED_OUT") {
    return {
      tone: "danger",
      label: `${kindLabel}活动`,
      title: `${name} 执行超时`,
      detail,
      icon: "timer",
      action: "details",
    };
  }
  if (status === "CANCELLED") {
    return {
      tone: "muted",
      label: `${kindLabel}活动`,
      title: `${name} 已取消`,
      detail,
      icon: "pause",
      action: "details",
    };
  }
  return {
    tone: "active",
    label: `${kindLabel}活动`,
    title: `${name} 有新的运行动态`,
    detail,
    icon: "activity",
    action: "details",
  };
}

function runPresentation(
  run: Run,
  activity: Activity | null,
  interaction: Interaction | null,
  outputPhase: OutputPhase,
  connection: ConnectionState,
): LiveRunPresentation {
  if (["WAITING_APPROVAL", "WAITING_INTERACTION"].includes(run.status)) {
    const approval = run.status === "WAITING_APPROVAL";
    return {
      tone: approval && interaction?.safePrompt.includes("Risks: HIGH") ? "danger" : "attention",
      label: approval ? "等待审批" : "等待回复",
      title: approval ? "需要你的审批" : "需要你的回复",
      detail: interaction?.title ?? (approval ? "审批详情正在加载" : "交互详情正在加载"),
      icon: "approval",
      action: interaction ? "interaction" : null,
    };
  }
  if (run.status === "FAILED") {
    return {
      tone: "danger",
      label: "任务未完成",
      title: "任务执行失败",
      detail: run.error ? `[${run.error.code}] ${run.error.message}` : run.errorCode ?? undefined,
      icon: "error",
      action: "details",
    };
  }
  if (run.status === "TIMEOUT") {
    return {
      tone: "danger",
      label: "任务未完成",
      title: "任务已超时",
      detail: run.error ? executionErrorGuidance(run.error) : "可以查看运行详情确认停止位置",
      icon: "timer",
      action: "details",
    };
  }
  if (run.status === "CANCELLED") {
    return {
      tone: "muted",
      label: "任务已结束",
      title: "任务已停止",
      detail: "当前执行已按请求停止",
      icon: "pause",
      action: "details",
    };
  }
  if (run.status === "COMPLETED") {
    return {
      tone: "success",
      label: "任务已结束",
      title: "任务已完成",
      detail: `模型调用 ${number.format(run.usage.modelCalls)} 次 · 工具调用 ${number.format(run.usage.toolCalls)} 次 · ${number.format(run.usage.totalTokens)} Token`,
      icon: "complete",
      action: "details",
    };
  }
  if (["reconnecting", "disconnected"].includes(connection)) {
    return {
      tone: "attention",
      label: "连接状态",
      title: "正在恢复运行连接",
      detail: "暂时无法接收最新动态，任务不一定已经停止",
      icon: "connection",
      action: "details",
    };
  }
  if (outputPhase !== "idle") {
    return {
      tone: "active",
      label: "生成回答",
      title: "正在生成回答",
      detail: outputPhase === "starting" ? "模型正在准备首段内容" : "回答内容正在实时生成",
      icon: "running",
      action: "details",
    };
  }
  if (activity) return activityPresentation(activity);

  const statusFallback: Record<string, Omit<LiveRunPresentation, "action">> = {
    PENDING: { tone: "muted", label: "准备任务", title: "正在创建任务", icon: "running" },
    QUEUED: { tone: "muted", label: "准备任务", title: "任务已进入队列", icon: "running" },
    RUNNING: { tone: "active", label: "当前任务", title: "任务运行中", icon: "running" },
    SUSPENDING: { tone: "attention", label: "任务控制", title: "正在暂停任务", icon: "pause" },
    SUSPENDED: { tone: "attention", label: "任务控制", title: "任务已暂停", icon: "pause" },
    COMPLETING: { tone: "active", label: "整理结果", title: "正在整理结果", icon: "running" },
  };
  return { ...(statusFallback[run.status] ?? statusFallback.RUNNING), action: "details" };
}

function LiveRunCard({
  run,
  activities,
  interaction,
  outputPhase,
  connection,
  onOpenDetails,
  onOpenInteraction,
}: {
  run: Run | null;
  activities: Activity[];
  interaction: Interaction | null;
  outputPhase: OutputPhase;
  connection: ConnectionState;
  onOpenDetails(): void;
  onOpenInteraction(): void;
}) {
  const latest = latestActivity(activities);
  const [suppressedActivityId, setSuppressedActivityId] = useState<string | null>(null);
  const [completedHidden, setCompletedHidden] = useState(false);

  useEffect(() => {
    setCompletedHidden(false);
    if (run?.status !== "COMPLETED") return;
    const timer = window.setTimeout(() => setCompletedHidden(true), 4_000);
    return () => window.clearTimeout(timer);
  }, [run?.id, run?.status, run?.version]);

  useEffect(() => {
    setSuppressedActivityId(null);
    if (!latest || latest.status !== "SUCCEEDED" || run?.status !== "RUNNING") return;
    const timer = window.setTimeout(() => setSuppressedActivityId(latest.activityId), 1_500);
    return () => window.clearTimeout(timer);
  }, [latest?.activityId, latest?.status, run?.status]);

  if (!run || completedHidden) return null;
  const visibleActivity = latest?.activityId === suppressedActivityId ? null : latest;
  const presentation = runPresentation(run, visibleActivity, interaction, outputPhase, connection);
  const primaryAction = presentation.action === "interaction" ? onOpenInteraction : onOpenDetails;
  const actionLabel = presentation.action === "interaction" ? "查看并处理" : "查看运行详情";

  const icon = presentation.icon === "activity" && visibleActivity
    ? <ActivityIcon kind={visibleActivity.kind} />
    : presentation.icon === "approval"
      ? <ShieldCheck size={18} />
      : presentation.icon === "complete"
        ? <CheckCircle2 size={18} />
        : presentation.icon === "error"
          ? <CircleAlert size={18} />
          : presentation.icon === "pause"
            ? <PauseCircle size={18} />
            : presentation.icon === "timer"
              ? <Timer size={18} />
              : presentation.icon === "connection"
                ? <WifiOff size={18} />
                : <RefreshCw className="spin" size={18} />;

  return (
    <section className={`live-run-card tone-${presentation.tone}`} aria-live="polite" aria-atomic="true">
      <span className="live-run-icon" aria-hidden="true">{icon}</span>
      <div className="live-run-copy">
        <strong>{presentation.title}</strong>
      </div>
      <div className="live-run-secondary">
        <span className="live-run-label">{presentation.label}</span>
        {presentation.detail && <span className="live-run-detail">{presentation.detail}</span>}
        <RunProgress run={run} activities={activities} outputPhase={outputPhase} />
      </div>
      {presentation.action && (
        <button
          type="button"
          className={`live-run-action${presentation.action === "details" ? " live-run-action-details" : ""}`}
          onClick={primaryAction}
        >
          {actionLabel}<ChevronRight size={15} aria-hidden="true" />
        </button>
      )}
    </section>
  );
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
    <section className="interaction-card" aria-labelledby={titleId} tabIndex={-1}>
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
  focusRequest,
  run,
  activities,
  pending,
  onClose,
  onCancel,
}: {
  open: boolean;
  focusRequest: number;
  run: Run | null;
  activities: Activity[];
  pending: boolean;
  onClose(): void;
  onCancel(): void;
}) {
  const panelRef = useRef<HTMLElement>(null);
  const [attention, setAttention] = useState(false);

  useEffect(() => {
    if (!activities.length || !panelRef.current) return;
    panelRef.current.scrollTop = panelRef.current.scrollHeight;
  }, [activities]);

  useEffect(() => {
    if (!focusRequest || !panelRef.current) return;
    const panel = panelRef.current;
    panel.focus({ preventScroll: true });
    if (typeof panel.scrollIntoView === "function") {
      panel.scrollIntoView({ behavior: "smooth", block: "nearest", inline: "nearest" });
    }
    setAttention(true);
    const timer = window.setTimeout(() => setAttention(false), 1_200);
    return () => window.clearTimeout(timer);
  }, [focusRequest]);

  return (
    <>
      {open && <button className="scrim right" aria-label="关闭运行详情" onClick={onClose} />}
      <aside
        ref={panelRef}
        className={`activity-panel ${open ? "drawer-open" : ""}${attention ? " activity-panel-attention" : ""}`}
        aria-label="当前运行详情"
        tabIndex={-1}
      >
        <div className="panel-heading">
          <div>
            <span className="eyebrow">CURRENT RUN</span>
            <div className="run-heading-row">
              <h2>{run ? statusLabel(run.status) : "暂无运行"}</h2>
              {run && !isTerminal(run) && (
                <Button
                  className="run-cancel-button"
                  busy={pending}
                  aria-label="停止当前任务"
                  title="停止当前任务"
                  onClick={onCancel}
                >
                  <Square size={11} fill="currentColor" aria-hidden="true" />
                  <span>停止当前任务</span>
                </Button>
              )}
            </div>
          </div>
          <button className="icon mobile-only" aria-label="关闭运行详情" onClick={onClose}><X size={18} /></button>
        </div>
        <section className="panel-section">
          <h3>安全活动</h3>
          <div className="activity-list">
            {activities.map((activity) => (
              <article className={`activity-card ${activity.parentActivityId ? "activity-child" : ""}`} key={activity.activityId}>
                <div className={`activity-kind kind-${activity.kind.toLowerCase()}`}>
                  <ActivityIcon kind={activity.kind} /><span>{activity.kind}</span><small>{statusLabel(activity.status)}</small>
                </div>
                <strong>{activity.displayName}</strong>
                {activity.safeTargetSummary && <pre className="activity-summary">{activity.safeTargetSummary}</pre>}
                {activity.safeResultSummary && <pre className="activity-summary safe-result">{activity.safeResultSummary}</pre>}
                {activity.parentActivityId && <small className="activity-relation">关联上级操作</small>}
                <time>{formatTime(activity.startedAt ?? activity.requestedAt ?? activity.occurredAt)}</time>
              </article>
            ))}
            {!activities.length && <p className="muted">当前运行尚无 Model、Tool、Skill 或 MCP 活动。</p>}
          </div>
        </section>
        <section className="panel-section"><h3>Token 使用</h3><UsagePanel run={run} /></section>
        {run?.error && (
          <div className="safe-error">
            <CircleAlert size={16} />
            <span>
              任务未完成：[{run.error.code}] {run.error.message}
              {run.error.diagnosticId && <> · 诊断编号：{run.error.diagnosticId}</>}
              <> · {executionErrorGuidance(run.error)}</>
            </span>
          </div>
        )}
        {!run?.error && run?.errorCode && <div className="safe-error"><CircleAlert size={16} /><span>任务未完成：{run.errorCode}</span></div>}
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

const missionTerminalStates = new Set([
  "COMPLETED",
  "PARTIALLY_COMPLETED",
  "FAILED",
  "CANCELLED",
]);

function missionStateLabel(state: string): string {
  return {
    PLANNING: "正在生成计划",
    WAITING_CONFIRMATION: "等待确认",
    RUNNING: "计划已确认",
    WAITING_USER: "等待用户",
    SYNTHESIZING: "正在整合",
    COMPLETED: "已完成",
    PARTIALLY_COMPLETED: "部分完成",
    FAILED: "失败",
    CANCELLED: "已取消",
  }[state] ?? state;
}

function parseMissionFinalResult(value: string | null): {
  directAnswer?: string;
  completionKind?: string;
  completedItems?: string[];
  failedItems?: string[];
  unverifiedClaims?: string[];
  residualRisks?: string[];
  unresolvedQuestions?: string[];
} | null {
  if (!value) return null;
  try {
    const parsed = JSON.parse(value) as Record<string, unknown>;
    const strings = (field: string): string[] => Array.isArray(parsed[field])
      ? parsed[field].filter((item): item is string => typeof item === "string")
      : [];
    return {
      directAnswer: typeof parsed.directAnswer === "string" ? parsed.directAnswer : undefined,
      completionKind: typeof parsed.completionKind === "string" ? parsed.completionKind : undefined,
      completedItems: strings("completedItems"),
      failedItems: strings("failedItems"),
      unverifiedClaims: strings("unverifiedClaims"),
      residualRisks: strings("residualRisks"),
      unresolvedQuestions: strings("unresolvedQuestions"),
    };
  } catch {
    return { directAnswer: value };
  }
}

function MissionDialog({
  client,
  conversation,
  onClose,
  onChanged,
}: {
  client: PersonalAssistantClient;
  conversation: Conversation | null;
  onClose(): void;
  onChanged(mission: MissionSnapshot | null): void;
}) {
  const [missions, setMissions] = useState<MissionSnapshot[]>([]);
  const [selected, setSelected] = useState<MissionSnapshot | null>(null);
  const [objective, setObjective] = useState("");
  const [criteria, setCriteria] = useState("");
  const [mode, setMode] = useState<"STANDARD" | "DEEP_RESEARCH">("STANDARD");
  const [researchQuestion, setResearchQuestion] = useState("");
  const [researchScope, setResearchScope] = useState("");
  const [researchTimeRange, setResearchTimeRange] = useState("");
  const [researchRegion, setResearchRegion] = useState("");
  const [researchAudience, setResearchAudience] = useState("");
  const [researchSources, setResearchSources] = useState("");
  const [researchExclusions, setResearchExclusions] = useState("");
  const [researchDelivery, setResearchDelivery] = useState("Markdown report");
  const [editingPlan, setEditingPlan] = useState(false);
  const [planJson, setPlanJson] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [missionInteraction, setMissionInteraction] = useState<Interaction | null>(null);
  const [missionInteractionText, setMissionInteractionText] = useState("");
  const [syncStatus, setSyncStatus] = useState<"loading" | "current" | "syncing" | "stale" | "recovering" | "offline">(
    navigator.onLine ? "loading" : "offline",
  );
  const [terminalAnnouncement, setTerminalAnnouncement] = useState("");
  const [reconnectEpoch, setReconnectEpoch] = useState(0);
  const pollFailures = useRef(0);
  const dialogRef = useRef<HTMLElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);

  const merge = useCallback((mission: MissionSnapshot) => {
    setSelected((current) => {
      if (current?.missionId === mission.missionId
        && !missionTerminalStates.has(current.state)
        && missionTerminalStates.has(mission.state)) {
        setTerminalAnnouncement(`Mission 已更新为${missionStateLabel(mission.state)}`);
      }
      return mission;
    });
    setMissions((current) => {
      const next = current.filter((value) => value.missionId !== mission.missionId);
      return [mission, ...next].sort((left, right) =>
        new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime());
    });
    onChanged(mission);
  }, [onChanged]);

  useEffect(() => {
    closeButtonRef.current?.focus({ preventScroll: true });
    const online = () => {
      setSyncStatus("recovering");
      setReconnectEpoch((value) => value + 1);
    };
    const offline = () => setSyncStatus("offline");
    window.addEventListener("online", online);
    window.addEventListener("offline", offline);
    return () => {
      window.removeEventListener("online", online);
      window.removeEventListener("offline", offline);
    };
  }, []);

  useEffect(() => {
    if (!client.missions) {
      setError("当前 Server 未发布 Mission 能力。");
      return;
    }
    const controller = new AbortController();
    let retryTimer: number | undefined;
    setBusy(true);
    setSyncStatus(navigator.onLine ? "loading" : "offline");
    client.missions(undefined, controller.signal)
      .then((page) => {
        setMissions(page.items);
        const current = conversation
          ? page.items.find((mission) => mission.conversationId === conversation.id)
          : page.items[0];
        setSelected(current ?? null);
        onChanged(current ?? null);
        setSyncStatus(navigator.onLine ? "current" : "offline");
        setError(null);
      })
      .catch((reason) => {
        if (!controller.signal.aborted) {
          setError(safeError(reason));
          setSyncStatus(navigator.onLine ? "stale" : "offline");
          if (navigator.onLine) {
            retryTimer = window.setTimeout(() => {
              setSyncStatus("recovering");
              setReconnectEpoch((value) => value + 1);
            }, 2_000);
          }
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setBusy(false);
      });
    return () => {
      controller.abort();
      if (retryTimer !== undefined) window.clearTimeout(retryTimer);
    };
  }, [client, conversation, onChanged, reconnectEpoch]);

  useEffect(() => {
    if (!selected || missionTerminalStates.has(selected.state) || !client.missionSnapshot) return;
    if (!navigator.onLine) {
      setSyncStatus("offline");
      return;
    }
    const controller = new AbortController();
    const baseDelay = document.hidden ? 10_000 : Math.max(2_000, selected.pollAfterMs || 5_000);
    const retryDelay = Math.min(30_000, baseDelay * Math.max(1, 2 ** pollFailures.current));
    const timer = window.setTimeout(() => {
      setSyncStatus(pollFailures.current > 0 ? "recovering" : "syncing");
      client.missionSnapshot?.(selected.missionId, controller.signal)
        .then((mission) => {
          pollFailures.current = 0;
          merge(mission);
          setError(null);
          setSyncStatus("current");
        })
        .catch((reason) => {
          if (!controller.signal.aborted) {
            pollFailures.current += 1;
            setError(safeError(reason));
            setSyncStatus(navigator.onLine ? "stale" : "offline");
            if (navigator.onLine) setReconnectEpoch((value) => value + 1);
          }
        });
    }, retryDelay);
    return () => {
      controller.abort();
      window.clearTimeout(timer);
    };
  }, [client, merge, reconnectEpoch, selected]);

  const handleDialogKeyDown = (event: ReactKeyboardEvent<HTMLElement>) => {
    if (event.key === "Escape") {
      event.preventDefault();
      onClose();
      return;
    }
    if (event.key !== "Tab" || !dialogRef.current) return;
    const focusable = Array.from(dialogRef.current.querySelectorAll<HTMLElement>(
      'button:not([disabled]), a[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
    ));
    if (focusable.length === 0) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  };

  const syncStatusLabel = {
    loading: "正在加载 Mission",
    current: "Mission 状态已同步",
    syncing: "正在同步 Mission",
    stale: "暂时无法同步，正在显示上次保存的状态",
    recovering: "网络已恢复，正在重新同步 Mission",
    offline: "当前离线，正在显示上次保存的状态",
  }[syncStatus];

  useEffect(() => {
    const runId = selected?.execution.latestAttempt?.runId;
    if (selected?.state !== "WAITING_USER" || !runId) {
      setMissionInteraction(null);
      return;
    }
    const controller = new AbortController();
    client.interaction(runId, controller.signal)
      .then(setMissionInteraction)
      .catch((reason) => {
        if (!controller.signal.aborted) setError(safeError(reason));
      });
    return () => controller.abort();
  }, [client, selected]);

  const command = async (operation: () => Promise<MissionSnapshot>) => {
    setBusy(true);
    setError(null);
    try {
      merge(await operation());
    } catch (reason) {
      setError(safeError(reason));
    } finally {
      setBusy(false);
    }
  };

  const createMission = (event: FormEvent) => {
    event.preventDefault();
    if (!conversation || !client.createMission || !objective.trim()) return;
    const acceptanceCriteria = criteria.split("\n").map((value) => value.trim()).filter(Boolean);
    const deepResearch = mode === "DEEP_RESEARCH";
    void command(() => client.createMission!({
      conversationId: conversation.id,
      objective: objective.trim(),
      acceptanceCriteria,
      mode,
      selectedSkillId: deepResearch ? "deep-research" : undefined,
      researchBrief: deepResearch ? {
        question: researchQuestion.trim() || objective.trim(),
        scope: researchScope.trim(),
        timeRange: researchTimeRange.trim(),
        region: researchRegion.trim(),
        audience: researchAudience.trim(),
        sourcePreferences: researchSources.split("\n").map((value) => value.trim()).filter(Boolean),
        exclusions: researchExclusions.split("\n").map((value) => value.trim()).filter(Boolean),
        deliveryFormat: researchDelivery.trim(),
      } : undefined,
    }, { idempotencyKey: crypto.randomUUID() })).then(() => {
      setObjective("");
      setCriteria("");
      setResearchQuestion("");
      setResearchScope("");
    });
  };

  const beginEdit = () => {
    if (!selected) return;
    setPlanJson(JSON.stringify({ tasks: selected.tasks }, null, 2));
    setEditingPlan(true);
  };

  const replacePlan = () => {
    if (!selected || !client.replaceMissionPlan) return;
    try {
      const parsed = JSON.parse(planJson) as { tasks?: MissionSnapshot["tasks"] };
      if (!Array.isArray(parsed.tasks)) throw new Error("计划必须包含 tasks 数组。");
      const tasks = parsed.tasks;
      void command(() => client.replaceMissionPlan!(selected, { plan: { tasks } }, {
        idempotencyKey: crypto.randomUUID(),
      })).then(() => setEditingPlan(false));
    } catch (reason) {
      setError(safeError(reason));
    }
  };

  const respondToMissionInteraction = (action: string) => {
    if (!missionInteraction || !selected) return;
    setBusy(true);
    setError(null);
    client.respondToInteraction(
      missionInteraction,
      action,
      missionInteractionText,
      { idempotencyKey: crypto.randomUUID() },
    ).then(() => client.missionSnapshot?.(selected.missionId))
      .then((mission) => {
        if (mission) merge(mission);
        setMissionInteraction(null);
        setMissionInteractionText("");
      })
      .catch((reason) => setError(safeError(reason)))
      .finally(() => setBusy(false));
  };

  return (
    <div className="dialog-backdrop mission-backdrop" role="presentation" onMouseDown={onClose}>
      <section ref={dialogRef} className="mission-dialog" role="dialog" aria-modal="true" aria-labelledby="mission-title" onKeyDown={handleDialogKeyDown} onMouseDown={(event) => event.stopPropagation()}>
        <header className="mission-dialog-header">
          <div><span className="eyebrow">LONG-RUNNING WORK</span><h2 id="mission-title">Mission</h2></div>
          <button ref={closeButtonRef} type="button" className="icon" aria-label="关闭 Mission" onClick={onClose}><X size={18} /></button>
        </header>
        {error && <div className="error-banner" role="alert"><CircleAlert size={16} /><span>{error}</span></div>}
        <div className="mission-layout">
          <aside className="mission-list" aria-label="Mission 列表">
            <strong>Mission 列表</strong>
            {missions.length === 0 && !busy && <p>还没有 Mission。</p>}
            {missions.map((mission) => (
              <button type="button" className={selected?.missionId === mission.missionId ? "active" : ""} key={mission.missionId} onClick={() => setSelected(mission)}>
                <span>{mission.objective}</span><small>{missionStateLabel(mission.state)} · {mission.tasks.length} 个任务</small>
              </button>
            ))}
          </aside>
          <div className="mission-content">
            {conversation && !missions.some((mission) => mission.conversationId === conversation.id && !missionTerminalStates.has(mission.state)) && (
              <form className="mission-create" onSubmit={createMission}>
                <h3>为“{conversation.displayName}”创建 Mission</h3>
                <label>任务模式<select value={mode} onChange={(event) => setMode(event.target.value as "STANDARD" | "DEEP_RESEARCH")}><option value="STANDARD">标准 Mission</option><option value="DEEP_RESEARCH">Deep Research</option></select></label>
                <label>目标<textarea value={objective} onChange={(event) => setObjective(event.target.value)} maxLength={8000} rows={3} placeholder="描述要持续推进并最终交付的目标" /></label>
                <label>验收标准<textarea value={criteria} onChange={(event) => setCriteria(event.target.value)} maxLength={4000} rows={3} placeholder="每行一条，可留空" /></label>
                {mode === "DEEP_RESEARCH" && <fieldset className="research-brief"><legend>Research brief</legend>
                  <label>研究问题<textarea value={researchQuestion} onChange={(event) => setResearchQuestion(event.target.value)} maxLength={8000} rows={2} placeholder="留空时使用 Mission 目标" /></label>
                  <label>范围<textarea value={researchScope} onChange={(event) => setResearchScope(event.target.value)} maxLength={2000} rows={2} /></label>
                  <div className="research-brief-grid"><label>时间范围<input value={researchTimeRange} onChange={(event) => setResearchTimeRange(event.target.value)} maxLength={256} /></label><label>地区<input value={researchRegion} onChange={(event) => setResearchRegion(event.target.value)} maxLength={256} /></label><label>受众<input value={researchAudience} onChange={(event) => setResearchAudience(event.target.value)} maxLength={256} /></label><label>交付格式<input value={researchDelivery} onChange={(event) => setResearchDelivery(event.target.value)} maxLength={256} /></label></div>
                  <label>来源偏好<textarea value={researchSources} onChange={(event) => setResearchSources(event.target.value)} rows={2} placeholder="每行一项" /></label>
                  <label>排除项<textarea value={researchExclusions} onChange={(event) => setResearchExclusions(event.target.value)} rows={2} placeholder="每行一项" /></label>
                </fieldset>}
                <button type="submit" className="button primary-button" disabled={busy || !objective.trim()}><Plus size={15} />创建并生成计划</button>
              </form>
            )}
            {selected ? (
              <article className="mission-detail">
                <div className="mission-title-row"><div><span className={`mission-state state-${selected.state.toLowerCase()}`}>{missionStateLabel(selected.state)}</span>{selected.mode === "DEEP_RESEARCH" && <span className="mission-mode">Deep Research · deep-research@1.0.0</span>}<h3>{selected.objective}</h3></div><button type="button" className="icon" title="刷新" aria-label="刷新 Mission" disabled={busy || !client.missionSnapshot} onClick={() => void command(() => client.missionSnapshot!(selected.missionId))}><RefreshCw size={16} /></button></div>
                {selected.researchBrief && <section className="research-brief-summary"><h4>Research brief</h4><p><b>问题：</b>{selected.researchBrief.question}</p>{selected.researchBrief.scope && <p><b>范围：</b>{selected.researchBrief.scope}</p>}<p><b>时间 / 地区 / 受众：</b>{[selected.researchBrief.timeRange, selected.researchBrief.region, selected.researchBrief.audience].filter(Boolean).join(" · ") || "未限定"}</p></section>}
                {selected.acceptanceCriteria.length > 0 && <section><h4>验收标准</h4><ul>{selected.acceptanceCriteria.map((item) => <li key={item}>{item}</li>)}</ul></section>}
                <section className="mission-execution-summary" aria-label="Mission 执行状态">
                  <span>Dispatcher：{selected.execution.dispatcherStatus}</span>
                  <span>已完成 {selected.execution.completedTasks}/{selected.tasks.length}</span>
                  {selected.execution.currentTaskId && <span>当前任务：{selected.execution.currentTaskId}</span>}
                  {selected.execution.recovering && <span>正在恢复执行状态</span>}
                </section>
                <section><h4>执行计划 · revision {selected.plan?.revision ?? "-"}</h4>
                  <ol className="mission-tasks">{selected.tasks.map((task) => <li key={task.taskId}><b>{task.ordinal}. {task.title}</b><span>{task.objective}</span>{task.dependsOn.length > 0 && <small>依赖：{task.dependsOn.join("、")}</small>}<em>{task.state}</em>{task.state === "BLOCKED" && client.retryMissionTask && <button type="button" className="button mission-task-retry" disabled={busy} onClick={() => void command(() => client.retryMissionTask!(selected, task.taskId, { idempotencyKey: crypto.randomUUID() }))}>重试任务</button>}</li>)}</ol>
                </section>
                {selected.finalResult && (() => { const result = parseMissionFinalResult(selected.finalResult); return <section className="research-result"><h4>最终报告{result?.completionKind && ` · ${result.completionKind}`}</h4>{result?.directAnswer && <p className="research-answer">{result.directAnswer}</p>}{(result?.completedItems?.length ?? 0) > 0 && <><h5>完成项</h5><ul>{result!.completedItems!.map((item) => <li key={item}>{item}</li>)}</ul></>}{(result?.failedItems?.length ?? 0) > 0 && <><h5>未完成项</h5><ul>{result!.failedItems!.map((item) => <li key={item}>{item}</li>)}</ul></>}{(result?.unverifiedClaims?.length ?? 0) > 0 && <><h5>未验证结论</h5><ul>{result!.unverifiedClaims!.map((item) => <li key={item}>{item}</li>)}</ul></>}{(result?.residualRisks?.length ?? 0) > 0 && <><h5>剩余风险</h5><ul>{result!.residualRisks!.map((item) => <li key={item}>{item}</li>)}</ul></>}{(result?.unresolvedQuestions?.length ?? 0) > 0 && <><h5>未决问题</h5><ul>{result!.unresolvedQuestions!.map((item) => <li key={item}>{item}</li>)}</ul></>}</section>; })()}
                {selected.sources.length > 0 && <section className="research-sources"><h4>来源与引用</h4><ol>{selected.sources.map((source) => <li key={source}><a href={source} target="_blank" rel="noreferrer">{source}</a></li>)}</ol></section>}
                {selected.artifacts.length > 0 && <section className="research-artifacts"><h4>交付文件</h4><ul>{selected.artifacts.map((artifact) => <li key={artifact}><a href={missionArtifactUrl(selected.missionId, artifact)} target="_blank" rel="noreferrer"><code>{artifact}</code></a></li>)}</ul></section>}
                {missionInteraction && <section className="mission-interaction"><h4>{missionInteraction.title}</h4><p>{missionInteraction.safePrompt}</p>{missionInteraction.inputType !== "NONE" && <textarea value={missionInteractionText} maxLength={missionInteraction.maximumCharacters} onChange={(event) => setMissionInteractionText(event.target.value)} rows={3} /> }<div>{missionInteraction.allowedActions.map((action) => <button key={action} type="button" className="button" disabled={busy} onClick={() => respondToMissionInteraction(action)}>{action}</button>)}</div></section>}
                {editingPlan && <section className="mission-plan-editor"><label>完整计划 JSON<textarea value={planJson} onChange={(event) => setPlanJson(event.target.value)} rows={12} spellCheck={false} /></label><div><button type="button" className="button" onClick={() => setEditingPlan(false)}>取消编辑</button><button type="button" className="button primary-button" disabled={busy} onClick={replacePlan}>替换整个计划</button></div></section>}
                <footer className="mission-actions">
                  {selected.state === "WAITING_CONFIRMATION" && <>
                    <button type="button" className="button" disabled={busy} onClick={() => void command(() => client.replaceMissionPlan!(selected, { regenerate: true }, { idempotencyKey: crypto.randomUUID() }))}>重新生成</button>
                    <button type="button" className="button" disabled={busy} onClick={beginEdit}>编辑计划</button>
                    <button type="button" className="button primary-button" disabled={busy} onClick={() => void command(() => client.confirmMission!(selected, { idempotencyKey: crypto.randomUUID() }))}><CheckCircle2 size={15} />确认计划</button>
                  </>}
                  {!missionTerminalStates.has(selected.state) && <button type="button" className="button danger" disabled={busy} onClick={() => void command(() => client.cancelMission!(selected, { idempotencyKey: crypto.randomUUID() }))}>取消 Mission</button>}
                </footer>
                {(selected.state === "RUNNING" || selected.state === "SYNTHESIZING") && <p className="mission-phase-note">Mission 正在后台{selected.state === "SYNTHESIZING" ? "整合最终结果" : "串行执行"}；关闭页面或重启服务后可从持久化状态继续恢复。</p>}
              </article>
            ) : <div className="empty"><h3>选择或创建 Mission</h3><p>Mission 用于需要拆解、持续运行并最终整合的大任务。</p></div>}
          </div>
        </div>
        <div className={`mission-sync-status sync-${syncStatus}`} role="status" aria-live="polite" aria-atomic="true">
          {syncStatus === "offline" && <WifiOff size={14} aria-hidden="true" />}{syncStatusLabel}
        </div>
        <div className="sr-only" aria-live="assertive">{terminalAnnouncement}</div>
      </section>
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
  const recommendationRequestGeneration = useRef(0);
  const [recommendedQuestions, setRecommendedQuestions] =
    useState<RecommendedQuestionState | null>(null);
  const [renameTarget, setRenameTarget] = useState<Conversation | null>(null);
  const [newModelId, setNewModelId] = useState("");
  const [slashMenu, setSlashMenu] = useState<SlashMenuState | null>(null);
  const [slashActiveIndex, setSlashActiveIndex] = useState(0);
  const [pendingImages, setPendingImages] = useState<PendingImage[]>([]);
  const [imageUrl, setImageUrl] = useState("");
  const [uploadingImage, setUploadingImage] = useState(false);
  const [imageToolsOpen, setImageToolsOpen] = useState(false);
  const [imageUrlInputOpen, setImageUrlInputOpen] = useState(false);
  const [draggingImages, setDraggingImages] = useState(false);
  const [activityFocusRequest, setActivityFocusRequest] = useState(0);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const imageToolsRef = useRef<HTMLDivElement>(null);
  const pendingImagePreviews = useRef(new Set<string>());
  const [reasonTarget, setReasonTarget] = useState<
    { kind: "reject"; candidate: MemoryCandidate } | { kind: "invalidate"; memory: Memory } | null
  >(null);
  const [missionOpen, setMissionOpen] = useState(false);
  const [conversationMission, setConversationMission] = useState<MissionSnapshot | null>(null);

  const handleMissionChanged = useCallback((mission: MissionSnapshot | null) => {
    if (!mission || mission.conversationId === state.selectedConversationId) {
      setConversationMission(mission);
    }
  }, [state.selectedConversationId]);

  useEffect(() => {
    if (!state.selectedConversationId || !client.missions) {
      setConversationMission(null);
      return;
    }
    const controller = new AbortController();
    client.missions(state.selectedConversationId, controller.signal)
      .then((page) => {
        if (!controller.signal.aborted) setConversationMission(page.items[0] ?? null);
      })
      .catch(() => {
        if (!controller.signal.aborted) setConversationMission(null);
      });
    return () => controller.abort();
  }, [client, state.selectedConversationId]);

  const closeImageTools = useCallback(() => {
    setImageToolsOpen(false);
    setImageUrlInputOpen(false);
    setImageUrl("");
  }, []);

  const revokePreview = useCallback((previewUrl?: string) => {
    if (!previewUrl || !pendingImagePreviews.current.delete(previewUrl)) return;
    URL.revokeObjectURL(previewUrl);
  }, []);

  useEffect(() => () => {
    pendingImagePreviews.current.forEach((previewUrl) => URL.revokeObjectURL(previewUrl));
    pendingImagePreviews.current.clear();
  }, []);

  useEffect(() => {
    if (!imageToolsOpen) return;
    const closeOnOutsidePointer = (event: PointerEvent) => {
      if (!imageToolsRef.current?.contains(event.target as Node)) closeImageTools();
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") closeImageTools();
    };
    document.addEventListener("pointerdown", closeOnOutsidePointer);
    document.addEventListener("keydown", closeOnEscape);
    return () => {
      document.removeEventListener("pointerdown", closeOnOutsidePointer);
      document.removeEventListener("keydown", closeOnEscape);
    };
  }, [closeImageTools, imageToolsOpen]);

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
              } else if (event.type === "activity.committed") {
                if (!event.activity) {
                  void loadActivities(runId, controller.signal).catch(() => undefined);
                }
                void loadRun(runId, controller.signal).catch(() => undefined);
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

  const completedAnswerTurn =
    state.run?.status === "COMPLETED"
      ? [...state.turns].reverse().find(
          (turn) =>
            turn.runId === state.run?.id &&
            turn.role.toLowerCase() === "assistant",
        ) ?? null
      : null;
  const recommendationTarget = completedAnswerTurn && state.run
    ? `${state.run.id}:${completedAnswerTurn.id}`
    : null;

  useEffect(() => {
    const conversationId = state.selectedConversationId;
    const runId = state.run?.id;
    const turnId = completedAnswerTurn?.id;
    const generation = ++recommendationRequestGeneration.current;
    if (!conversationId || !runId || !turnId || !recommendationTarget) {
      setRecommendedQuestions(null);
      return;
    }
    const controller = new AbortController();
    setRecommendedQuestions({ runId, turnId, loading: true, questions: [] });
    void client.recommendedQuestions(conversationId, runId, {
      idempotencyKey: crypto.randomUUID(),
      signal: controller.signal,
    }).then((response) => {
      if (generation !== recommendationRequestGeneration.current || controller.signal.aborted) return;
      setRecommendedQuestions({
        runId,
        turnId,
        loading: false,
        questions: response.questions.slice(0, 3),
      });
    }).catch(() => {
      if (generation !== recommendationRequestGeneration.current || controller.signal.aborted) return;
      setRecommendedQuestions({ runId, turnId, loading: false, questions: [] });
    });
    return () => controller.abort();
  }, [
    client,
    completedAnswerTurn?.id,
    recommendationTarget,
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

  const submitMessage = (value: string, retryImages?: TurnImage[]) => {
    const imageCount = retryImages?.length ?? pendingImages.length;
    const message = value.trim() || (imageCount > 1
      ? "请分别解释这些图片"
      : imageCount === 1
        ? "请解释这张图片"
        : "");
    if (!message || state.pending || state.selectedConversation?.activeRunId) return;
    const key = crypto.randomUUID();
    const sentImages = pendingImages.map((image) => ({ ...image }));
    recommendationRequestGeneration.current += 1;
    setRecommendedQuestions(null);
    void execute("提交消息", async () => {
      const images: ImageInput[] = retryImages
        ? retryImages.flatMap((image): ImageInput[] => {
          if (image.kind === "url" && image.url) return [{ kind: "url", url: image.url }];
          if (image.imageId) return [{ kind: "upload", imageId: image.imageId }];
          return [];
        })
        : sentImages.map(({ kind, url, imageId }) => ({ kind, url, imageId }));
      const conversation = state.selectedConversation
        ? images.length
          ? await client.submitMessage(state.selectedConversation, message, { idempotencyKey: key }, images)
          : await client.submitMessage(state.selectedConversation, message, { idempotencyKey: key })
        : images.length
          ? await client.createConversation(
              message.slice(0, 32),
              message,
              { idempotencyKey: key },
              newModelId || state.bootstrap?.defaultModelId,
              images,
            )
          : await client.createConversation(
              message.slice(0, 32),
              message,
              { idempotencyKey: key },
              newModelId || state.bootstrap?.defaultModelId,
            );
      if (!retryImages) {
        dispatch({ type: "setComposer", value: "" });
        sentImages.forEach((image) => revokePreview(image.previewUrl));
        setPendingImages([]);
        closeImageTools();
      }
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
  const imageCapable = (state.selectedConversation?.model.model
    ?? configuredModels.find((model) => model.id === (newModelId || state.bootstrap?.defaultModelId)))
    ?.capabilities.includes("IMAGE_INPUT") ?? false;

  const addImageUrl = () => {
    if (!imageCapable || pendingImages.length >= 4) return;
    try {
      const parsed = new URL(imageUrl.trim());
      if (parsed.protocol !== "https:") throw new Error("not HTTPS");
      setPendingImages((current) => [...current, {
        kind: "url",
        url: parsed.toString(),
        key: crypto.randomUUID(),
        label: parsed.hostname,
      }]);
      closeImageTools();
    } catch {
      dispatch({ type: "error", message: "图片 URL 必须是可公开访问的 HTTPS 地址。" });
    }
  };

  const uploadFiles = async (files: FileList | File[]) => {
    if (!imageCapable || !client.uploadImage || uploadingImage) return;
    const selected = Array.from(files).slice(0, Math.max(0, 4 - pendingImages.length));
    if (!selected.length) return;
    const allowed = new Set(["image/png", "image/jpeg", "image/webp", "image/gif"]);
    if (selected.some((file) => !allowed.has(file.type) || file.size < 1 || file.size > 10 * 1024 * 1024)) {
      dispatch({ type: "error", message: "仅支持不超过 10 MiB 的 PNG、JPEG、WEBP 或非动画 GIF。" });
      return;
    }
    setUploadingImage(true);
    const uploaded: PendingImage[] = [];
    try {
      for (const file of selected) {
        const result = await client.uploadImage(file, { idempotencyKey: crypto.randomUUID() });
        const previewUrl = typeof URL.createObjectURL === "function" ? URL.createObjectURL(file) : undefined;
        if (previewUrl) pendingImagePreviews.current.add(previewUrl);
        uploaded.push({
          kind: "upload",
          imageId: result.imageId,
          key: crypto.randomUUID(),
          label: file.name,
          previewUrl,
        });
      }
      setPendingImages((current) => [...current, ...uploaded].slice(0, 4));
      closeImageTools();
    } catch (error) {
      uploaded.forEach((image) => revokePreview(image.previewUrl));
      dispatch({ type: "error", message: safeError(error) || "图片上传失败。" });
    } finally {
      setUploadingImage(false);
      if (fileInputRef.current) fileInputRef.current.value = "";
    }
  };

  const removePendingImage = (key: string) => {
    setPendingImages((current) => {
      const removed = current.find((image) => image.key === key);
      revokePreview(removed?.previewUrl);
      return current.filter((image) => image.key !== key);
    });
  };

  const explainPendingImages = () => {
    if (!pendingImages.length) return;
    dispatch({
      type: "setComposer",
      value: pendingImages.length > 1 ? "请分别解释这些图片" : "请解释这张图片",
    });
    window.requestAnimationFrame(() => textareaRef.current?.focus());
  };
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
    if (slashInput) closeImageTools();
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
      const currentProviderIndex = modelProviders.findIndex((provider) =>
        provider.models.some((model) => model.id === selectedModelId)
      );
      setSlashActiveIndex(Math.max(0, currentProviderIndex));
      return;
    }
    if (slashMenu.stage === "providers") {
      const provider = modelProviders[index];
      if (!provider) return;
      setSlashMenu({ stage: "models", providerId: provider.id });
      const currentModelIndex = provider.models.findIndex((model) => model.id === selectedModelId);
      setSlashActiveIndex(Math.max(0, currentModelIndex));
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

  const submit = (event: FormEvent) => {
    event.preventDefault();
    submitMessage(state.composer);
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
  const closeMission = useCallback(() => {
    setMissionOpen(false);
    window.setTimeout(() => previousFocus.current?.focus(), 0);
  }, []);

  const runActive = Boolean(state.selectedConversation?.activeRunId) && !isTerminal(state.run);
  const composerDisabled = Boolean(state.pending) || runActive;
  const failedRunHasOutput = Boolean(
    state.run?.output?.trim()
      || state.streamDraft.trim()
      || state.turns.some((turn) =>
        turn.runId === state.run?.id
        && turn.role.toLowerCase() === "assistant"
        && turn.text.trim()),
  );
  const failedUserTurn = state.run?.status === "FAILED" && !failedRunHasOutput
    ? [...state.turns].reverse().find((turn) =>
      turn.runId === state.run?.id && turn.role.toLowerCase() === "user")
    : undefined;
  const openRunDetails = useCallback(() => {
    dispatch({ type: "toggleActivity", open: true });
    setActivityFocusRequest((value) => value + 1);
  }, []);
  const dropImages = (event: DragEvent<HTMLFormElement>) => {
    event.preventDefault();
    setDraggingImages(false);
    if (!composerDisabled) void uploadFiles(event.dataTransfer.files);
  };

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
          {state.bootstrap?.capabilities.includes("mission") && (
            <button className="button mission-button" onClick={(event) => {
              previousFocus.current = event.currentTarget;
              setMissionOpen(true);
            }}>
              <CheckCircle2 size={16} /> Mission
            </button>
          )}
          <button className="icon mobile-only" aria-label="打开运行详情" onClick={openRunDetails}><PanelRight size={20} /></button>
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
            {state.run && <span className="run-state">{statusLabel(state.run.status)}</span>}
          </div>
          {conversationMission && (
            <button type="button" className="conversation-mission-card" onClick={() => setMissionOpen(true)}>
              <span><b>Mission</b>{missionStateLabel(conversationMission.state)}</span>
              <strong>{conversationMission.objective}</strong>
              <small>{conversationMission.tasks.length} 个计划任务 · 点击查看详情</small>
            </button>
          )}
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
                {state.turns.map((turn) => {
                  const assistant = turn.role.toLowerCase() === "assistant";
                  const recommendation = recommendedQuestions?.turnId === turn.id
                    ? recommendedQuestions
                    : null;
                  return (
                    <article className={`message ${assistant ? "assistant" : "user"}${turn.images?.length ? " has-images" : ""}`} key={turn.id}>
                      <span className="message-role">{assistant ? "Haifa" : "你"}</span>
                      {turn.images?.length > 0 && <TurnImages images={turn.images} />}
                      <MessageContent text={turn.text} /><time>{formatTime(turn.createdAt)}</time>
                      {assistant && (
                        <div className="message-actions">
                          <MessageCopyButton text={turn.text} />
                        </div>
                      )}
                      {recommendation && (
                        <RecommendedQuestionList
                          state={recommendation}
                          disabled={composerDisabled}
                          onSelect={submitMessage}
                        />
                      )}
                    </article>
                  );
                })}
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
          <LiveRunCard
            run={state.run}
            activities={state.activities}
            interaction={state.interaction}
            outputPhase={state.outputPhase}
            connection={state.connection}
            onOpenDetails={openRunDetails}
            onOpenInteraction={() => {
              const card = document.querySelector<HTMLElement>(".interaction-card");
              if (!card) return;
              if (typeof card.scrollIntoView === "function") {
                card.scrollIntoView({ behavior: "smooth", block: "center" });
              }
              card.focus({ preventScroll: true });
            }}
          />
          {state.interactionError && (
            <div className="error-banner" role="alert">
              <CircleAlert size={17} /><span>{state.interactionError}</span>
            </div>
          )}
          <form
            className={`composer${imageCapable ? " image-capable" : ""}${pendingImages.length ? " has-pending-images" : ""}${draggingImages ? " image-dragging" : ""}`}
            onSubmit={submit}
            onDragEnter={(event) => {
              event.preventDefault();
              if (imageCapable && !composerDisabled) setDraggingImages(true);
            }}
            onDragOver={(event) => {
              event.preventDefault();
              if (imageCapable && !composerDisabled) setDraggingImages(true);
            }}
            onDragLeave={(event) => {
              if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setDraggingImages(false);
            }}
            onDrop={dropImages}
          >
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
            <input
              ref={fileInputRef}
              className="sr-only"
              type="file"
              accept="image/png,image/jpeg,image/webp,image/gif"
              multiple
              onChange={(event) => event.target.files && void uploadFiles(event.target.files)}
            />
            {pendingImages.length > 0 && (
              <section className="image-attachment-stage" aria-label="待发送图片">
                <div className="image-attachment-row">
                  {pendingImages.map((image, index) => (
                    <figure key={image.key} className="image-attachment">
                      {image.url || image.previewUrl
                        ? <img src={image.url ?? image.previewUrl} alt={`待发送图片 ${index + 1}`} />
                        : <span className="image-file-icon"><ImageIcon size={22} aria-hidden="true" /></span>}
                      <figcaption>{image.label}</figcaption>
                      <button
                        type="button"
                        aria-label={`移除图片 ${image.label}`}
                        onClick={() => removePendingImage(image.key)}
                      ><X size={13} /></button>
                    </figure>
                  ))}
                </div>
                <button className="explain-image-action" type="button" onClick={explainPendingImages}>
                  <Sparkles size={14} />解释图片 <span>→</span>
                </button>
                <span className="image-attachment-count">{pendingImages.length}/4</span>
              </section>
            )}
            {imageCapable && (
              <div className="image-add-control" ref={imageToolsRef}>
                <button
                  type="button"
                  className="image-add-trigger"
                  aria-label="添加图片"
                  aria-controls="image-add-menu"
                  aria-expanded={imageToolsOpen}
                  title="添加图片"
                  disabled={composerDisabled || pendingImages.length >= 4}
                  onClick={() => {
                    setSlashMenu(null);
                    if (imageToolsOpen) closeImageTools();
                    else setImageToolsOpen(true);
                  }}
                >
                  <Plus size={20} />
                </button>
                {imageToolsOpen && (
                  <section className="image-add-menu" id="image-add-menu" role="dialog" aria-label="添加图片">
                    <header>
                      <strong>添加图片</strong>
                      <button type="button" aria-label="关闭图片菜单" onClick={closeImageTools}><X size={15} /></button>
                    </header>
                    <button
                      type="button"
                      disabled={composerDisabled || uploadingImage || pendingImages.length >= 4}
                      onClick={() => fileInputRef.current?.click()}
                    >
                      <Paperclip size={17} />
                      <span><strong>{uploadingImage ? "正在上传…" : "上传图片"}</strong><small>选择或拖放，最多 4 张</small></span>
                    </button>
                    <button
                      type="button"
                      aria-expanded={imageUrlInputOpen}
                      onClick={() => setImageUrlInputOpen((open) => !open)}
                    >
                      <Link size={17} />
                      <span><strong>添加图片 URL</strong><small>仅支持 HTTPS 图片地址</small></span>
                    </button>
                    {imageUrlInputOpen && (
                      <div className="image-url-popover">
                        <header>
                          <label htmlFor="image-url-input">图片 URL</label>
                          <button type="button" aria-label="关闭图片 URL" onClick={() => {
                            setImageUrlInputOpen(false);
                            setImageUrl("");
                          }}><X size={14} /></button>
                        </header>
                        <div>
                          <input
                            id="image-url-input"
                            type="url"
                            value={imageUrl}
                            disabled={composerDisabled || pendingImages.length >= 4}
                            placeholder="https://…"
                            aria-label="图片 URL"
                            autoFocus
                            onChange={(event) => setImageUrl(event.target.value)}
                            onKeyDown={(event) => {
                              if (event.key === "Enter") {
                                event.preventDefault();
                                addImageUrl();
                              }
                            }}
                          />
                          <button
                            type="button"
                            aria-label="确认添加图片 URL"
                            disabled={!imageUrl.trim() || composerDisabled || pendingImages.length >= 4}
                            onClick={addImageUrl}
                          >添加</button>
                        </div>
                      </div>
                    )}
                  </section>
                )}
              </div>
            )}
            {draggingImages && (
              <div className="image-drop-hint" aria-live="polite">
                <ImageIcon size={19} /> 松开即可添加图片
              </div>
            )}
            <label>
              <span className="sr-only">给个人助理发送消息</span>
              <textarea
                ref={textareaRef}
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
            <span className="image-input-hint">{imageCapable ? "支持上传、URL 和拖放图片" : ""}</span>
            {failedUserTurn && (
              <button
                type="button"
                className="retry-send-button"
                aria-label="重新发送上一条失败消息"
                title="重新发送上一条失败消息"
                disabled={composerDisabled}
                onClick={() => submitMessage(failedUserTurn.text, failedUserTurn.images)}
              >
                <RefreshCw size={16} aria-hidden="true" />
              </button>
            )}
            <Button type="submit" className="send-button" aria-label="发送消息" busy={Boolean(state.pending)} disabled={composerDisabled || (!state.composer.trim() && !pendingImages.length)}>
              <Send size={18} />
            </Button>
          </form>
        </main>

        <ActivityPanel
          open={state.activityOpen}
          focusRequest={activityFocusRequest}
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
      {missionOpen && (
        <MissionDialog
          client={client}
          conversation={state.selectedConversation}
          onClose={closeMission}
          onChanged={handleMissionChanged}
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

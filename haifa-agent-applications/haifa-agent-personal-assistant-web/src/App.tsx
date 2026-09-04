import {
  Archive,
  AudioLines,
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
  FileText,
  Image as ImageIcon,
  KeyRound,
  Menu,
  MessageSquarePlus,
  PauseCircle,
  Link,
  Paperclip,
  PanelRight,
  Pencil,
  Plus,
  RefreshCw,
  LoaderCircle,
  Search,
  Save,
  Send,
  ShieldCheck,
  Sparkles,
  Square,
  Timer,
  Trash2,
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
  AudioInput,
  Conversation,
  ExecutionError,
  Interaction,
  ImageInput,
  Memory,
  MemoryCandidate,
  MissionSnapshot,
  Model,
  ModelConnection,
  ModelPreferences,
  Run,
  Turn,
  TurnAudio,
  TurnImage,
} from "./api/generated";
import {
  HttpPersonalAssistantClient,
  PersonalAssistantApiError,
  missionArtifactUrl,
  uploadedImageUrl,
  type PersonalAssistantClient,
} from "./api/client";
import { appReducer, initialState } from "./state/appReducer";
import { ModelConnectionsModal, type ModelConnectionsTab } from "./components/ModelConnectionsModal";
import type { ConnectionState, OutputPhase } from "./types";
import {
  defaultMissionAcceptanceCriteria,
  defaultResearchBrief,
  type MissionMode,
} from "./missionCreationDefaults";
import {
  hasEmbeddedMarkdownResearchSources,
  inferMarkdownResearchContext,
  renderMarkdownDocument,
  researchSourceDate,
  researchSourceSite,
  researchSourceStatus,
  researchSourceTier,
  type MarkdownResearchContext,
  type MarkdownResearchSource,
} from "./utils/markdownRenderer";

const defaultClient = new HttpPersonalAssistantClient();

import {
  conversationIdParameter,
  dateTime,
  executionErrorGuidance,
  formatElapsedTime,
  formatTime,
  isTerminal,
  number,
  safeError,
  statusLabel,
  terminalStatuses,
} from "./utils/formatters";
import { Button } from "./components/common/Button";
import {
  MessageContent,
  ResearchCitationPanel,
  safeResearchLocator,
  type ResearchCitationSelection,
} from "./components/conversation/MessageContent";
import {
  ConversationHeader,
  responseModeLabels,
} from "./components/conversation/ConversationHeader";
import {
  ActivityFeed,
  ActivityIcon,
  ActivityPanel,
  ActivityRightPanel,
  UsagePanel,
} from "./components/activity/ActivityRightPanel";
import {
  missionDeliveryId,
  missionDisplayTitle,
  missionStateLabel,
  missionTerminalStates,
  parseMissionFinalResult,
  parseResearchSourcesArtifact,
  type MissionArtifactItem,
  type MissionDraftRequest,
} from "./components/mission/missionUtils";
import { MissionDeliveryCard } from "./components/mission/MissionDeliveryCard";
import {
  MissionDialog,
  MissionWorkspaceModal,
} from "./components/mission/MissionWorkspaceModal";
import {
  useComposerState,
  type ComposerMode,
  type PendingAudio,
  type PendingImage,
} from "./state/useComposerState";
import {
  useSlashMenuState,
  type SlashMenuState,
} from "./state/useSlashMenuState";
import { useMissionState } from "./state/useMissionState";
import { useModelCenterState } from "./state/useModelCenterState";

const approvalPreviewCharacters = 640;
const approvalPreviewLines = 14;

interface ModelProviderGroup {
  id: string;
  displayName: string;
  modelGroups: ModelGroup[];
}

interface ModelGroup {
  id: string;
  displayName: string;
  bindings: Model[];
}


const opaqueImageFilename = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.[a-z0-9]+$/i;

function imageHost(url: string): string {
  try {
    return new URL(url).hostname;
  } catch {
    return "外部图片";
  }
}

function isPdfMedia(media: { mediaType?: string | null; originalFilename?: string | null; label?: string | null }): boolean {
  if (media.mediaType === "application/pdf") return true;
  const name = (media.originalFilename ?? media.label ?? "").toLowerCase();
  return name.endsWith(".pdf");
}

function uploadedImageLabel(image: TurnImage, index: number): string {
  const filename = image.originalFilename?.trim();
  if (filename && !opaqueImageFilename.test(filename)) return filename;
  return isPdfMedia(image) ? `已上传 PDF ${index + 1}` : `已上传图片 ${index + 1}`;
}

function uploadedAudioLabel(audio: TurnAudio, index: number): string {
  const filename = audio.originalFilename?.trim();
  return filename && !opaqueImageFilename.test(filename) ? filename : `已上传音频 ${index + 1}`;
}

function TurnImages({ images }: { images: TurnImage[] }) {
  return (
    <div className="turn-images" aria-label={`消息包含 ${images.length} 张图片`}>
      {images.map((image, index) => {
        if (isPdfMedia(image)) {
          const source = image.kind === "upload" && image.imageId ? uploadedImageUrl(image.imageId) : (image.url ?? undefined);
          return (
            <a
              className="turn-image turn-image-file turn-pdf-file"
              href={source}
              key={`${image.imageId ?? image.url}-${index}`}
              target="_blank"
              rel="noreferrer"
              aria-label={`打开第 ${index + 1} 个已上传 PDF: ${uploadedImageLabel(image, index)}`}
            >
              <div><FileText size={22} aria-hidden="true" /></div>
              <span><b>{index + 1}</b>{uploadedImageLabel(image, index)}</span>
            </a>
          );
        }
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
        if (image.kind === "upload" && image.imageId) {
          const source = uploadedImageUrl(image.imageId);
          return (
            <a
              className="turn-image turn-image-preview"
              href={source}
              key={`${image.imageId}-${index}`}
              target="_blank"
              rel="noreferrer"
              aria-label={`打开第 ${index + 1} 张已上传图片`}
            >
              <img src={source} alt={`第 ${index + 1} 张已上传图片`} />
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

function TurnAudios({ audios }: { audios: TurnAudio[] }) {
  return (
    <div className="turn-audios" aria-label={`消息包含 ${audios.length} 个音频`}>
      {audios.map((audio, index) => (
        <div className="turn-audio-file" key={`${audio.audioId}-${index}`}>
          <AudioLines size={22} aria-hidden="true" />
          <span><b>{index + 1}</b>{uploadedAudioLabel(audio, index)}</span>
          <small>{audio.mediaType}</small>
        </div>
      ))}
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
  {
    id: "deep-research",
    command: "/deep-research",
    label: "发起深度调研",
    description: "将当前目标带入 Mission，确认研究设置和计划后再执行",
  },
] as const;

function explicitDeepResearchObjective(value: string): string | null {
  const normalized = value.trim();
  const slash = /^\/deep-research(?:\s+)([\s\S]+)$/i.exec(normalized);
  if (slash?.[1]?.trim()) return slash[1].trim();
  const skillRequest = /^(?:请)?(?:调用|使用)\s*(?:deep-research|深度研究)\s*skill\s*(?:来|进行|做)?\s*[：:]?\s*([\s\S]+)$/i.exec(normalized);
  if (skillRequest?.[1]?.trim()) return skillRequest[1].trim();
  const chineseRequest = /^请(?:进行|做)?\s*深度研究\s*[：:]?\s*([\s\S]+)$/i.exec(normalized);
  return chineseRequest?.[1]?.trim() || null;
}

function explicitlyRequestsDeepResearch(value: string): boolean {
  const normalized = value.trim();
  return /^\/deep-research(?:\s|$)/i.test(normalized)
    || /^(?:请)?(?:调用|使用)\s*(?:deep-research|深度研究)\s*skill(?:\s|[：:]|$)/i.test(normalized)
    || /^请(?:进行|做)?\s*深度研究(?:\s|[：:]|$)/i.test(normalized);
}

function groupModelsByProvider(models: Model[]): ModelProviderGroup[] {
  const providers = new Map<string, ModelProviderGroup>();
  models.forEach((model) => {
    let provider = providers.get(model.providerId);
    if (!provider) {
      provider = { id: model.providerId, displayName: model.providerDisplayName, modelGroups: [] };
      providers.set(model.providerId, provider);
    }
    let group = provider.modelGroups.find((candidate) => candidate.id === model.modelGroupId);
    if (!group) {
      group = { id: model.modelGroupId, displayName: model.modelDisplayName, bindings: [] };
      provider.modelGroups.push(group);
    }
    group.bindings.push(model);
  });
  return [...providers.values()];
}

function availableBindings(group: ModelGroup): Model[] {
  return group.bindings.filter((binding) => binding.availability === "AVAILABLE");
}

function unavailableBindings(group: ModelGroup): Model[] {
  return group.bindings.filter((binding) => binding.availability === "UNAVAILABLE");
}

function unavailableModelReason(group: ModelGroup): string {
  return unavailableBindings(group)
    .map((binding) => binding.unavailableReason.trim())
    .find(Boolean) ?? "模型连接尚未通过验证";
}

function availableModelGroupCount(provider: ModelProviderGroup): number {
  return provider.modelGroups.filter((group) => availableBindings(group).length > 0).length;
}

const responseLengthLabels = { RECOMMENDED: "推荐", SHORT: "短", STANDARD: "标准", LONG: "长" } as const;
const effortLabels: Record<string, string> = { LOW: "Low", MEDIUM: "Medium", HIGH: "High", MAX: "Max" };

function recommendedBinding(group: ModelGroup): Model | null {
  const available = availableBindings(group);
  const recommendedId = available[0]?.controls.apiStyle.recommendedValue;
  return available.find((binding) => binding.id === recommendedId) ?? available[0] ?? null;
}

interface RecommendedQuestionState {
  runId: string;
  turnId: string;
  loading: boolean;
  questions: string[];
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
  const recommendationRequestGeneration = useRef(0);
  const messageSubmissionInFlight = useRef(false);
  const [recommendedQuestions, setRecommendedQuestions] =
    useState<RecommendedQuestionState | null>(null);
  const [renameTarget, setRenameTarget] = useState<Conversation | null>(null);
  const [activityFocusRequest, setActivityFocusRequest] = useState(0);
  const [reasonTarget, setReasonTarget] = useState<
    { kind: "reject"; candidate: MemoryCandidate } | { kind: "invalidate"; memory: Memory } | null
  >(null);

  const {
    composerMode,
    setComposerMode,
    pendingImages,
    setPendingImages,
    pendingAudios,
    setPendingAudios,
    imageUrl,
    setImageUrl,
    uploadingImage,
    setUploadingImage,
    uploadingAudio,
    setUploadingAudio,
    imageToolsOpen,
    setImageToolsOpen,
    imageUrlInputOpen,
    setImageUrlInputOpen,
    draggingImages,
    setDraggingImages,
    fileInputRef,
    audioInputRef,
    textareaRef,
    imageToolsRef,
    pendingImagePreviews,
    revokePreview,
    clearAttachments,
  } = useComposerState();

  const {
    slashMenu,
    setSlashMenu,
    slashActiveIndex,
    setSlashActiveIndex,
    slashFromPlus,
    setSlashFromPlus,
    modelDraftBindingId,
    setModelDraftBindingId,
    modelDraftPreferences,
    setModelDraftPreferences,
    slashMenuRef,
  } = useSlashMenuState();

  const {
    missionRouteId,
    setMissionRouteId,
    missionOpen,
    setMissionOpen,
    missionDraft,
    setMissionDraft,
    conversationMission,
    setConversationMission,
    conversationMissions,
    setConversationMissions,
    requestedMissionTaskId,
    setRequestedMissionTaskId,
    requestedMissionArtifact,
    setRequestedMissionArtifact,
    researchReadingContext,
    setResearchReadingContext,
    navigateToMission,
    openResearchTask,
    handleMissionChanged,
  } = useMissionState({
    client,
    selectedConversationId: state.selectedConversationId,
    previousFocusRef: previousFocus,
  });

  const {
    newModelId,
    setNewModelId,
    newModelPreferences,
    setNewModelPreferences,
    modelConnections,
    setModelConnections,
    modelConnectionsOpen,
    setModelConnectionsOpen,
    modelCenterTab,
    setModelCenterTab,
    openModelCenter,
    closeModelCenter: closeModelCenterState,
  } = useModelCenterState({ client });

  const closeImageTools = useCallback(() => {
    setImageToolsOpen(false);
    setImageUrlInputOpen(false);
    setImageUrl("");
  }, []);

  const closeSlashMenu = useCallback((restoreFocus = true) => {
    setSlashMenu(null);
    setSlashFromPlus(false);
    setSlashActiveIndex(0);
    if (restoreFocus) window.requestAnimationFrame(() => textareaRef.current?.focus());
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

  useEffect(() => {
    if (!slashMenu) return;
    const closeOnOutsidePointer = (event: PointerEvent) => {
      if (!slashMenuRef.current?.contains(event.target as Node)) closeSlashMenu(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      event.preventDefault();
      closeSlashMenu();
    };
    document.addEventListener("pointerdown", closeOnOutsidePointer);
    document.addEventListener("keydown", closeOnEscape);
    return () => {
      document.removeEventListener("pointerdown", closeOnOutsidePointer);
      document.removeEventListener("keydown", closeOnEscape);
    };
  }, [closeSlashMenu, slashMenu]);

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

  const startNewConversation = () => {
    pendingImages.forEach((image) => revokePreview(image.previewUrl));
    setPendingImages([]);
    setPendingAudios([]);
    setComposerMode("CHAT");
    dispatch({ type: "setComposer", value: "" });
    closeSlashMenu(false);
    closeImageTools();
    selectConversation(null);
  };

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

  const submitMessage = (value: string, retryImages?: TurnImage[], retryAudios?: TurnAudio[]) => {
    const imageCount = retryImages?.length ?? pendingImages.length;
    const audioCount = retryAudios?.length ?? pendingAudios.length;
    const mediaCount = imageCount + audioCount;
    const message = value.trim() || (imageCount && audioCount
      ? "请分析这些图片和音频"
      : imageCount > 1
        ? "请分别解释这些图片"
        : imageCount === 1
          ? "请解释这张图片"
          : audioCount > 1
            ? "请分别分析这些音频"
            : audioCount === 1
              ? "请转写并分析这段音频"
              : "");
    if (
      !message ||
      state.pending ||
      state.selectedConversation?.activeRunId ||
      messageSubmissionInFlight.current
    ) return;
    const conversationModel = state.selectedConversation?.model;
    if (
      conversationModel &&
      (conversationModel.available === false || conversationModel.selectionCompatibility === "UNAVAILABLE")
    ) {
      dispatch({
        type: "error",
        message: "当前会话模型已下线，请先选择可用模型后再发送消息。",
      });
      openModelCenter("catalog");
      return;
    }
    messageSubmissionInFlight.current = true;
    const key = crypto.randomUUID();
    const sentImages = pendingImages.map((image) => ({ ...image }));
    const sentAudios = pendingAudios.map((audio) => ({ ...audio }));
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
      const audios: AudioInput[] = retryAudios
        ? retryAudios.map((audio) => ({ kind: "upload", audioId: audio.audioId }))
        : sentAudios.map(({ kind, audioId }) => ({ kind, audioId }));
      const initialModel = state.bootstrap?.models.find((model) => model.id === newModelId);
      const initialModelSelection = initialModel && newModelPreferences
        ? { model: initialModel, preferences: newModelPreferences }
        : undefined;
      let conversation: Conversation;
      try {
        conversation = state.selectedConversation
          ? audios.length
            ? await client.submitMessage(state.selectedConversation, message, { idempotencyKey: key }, images, audios)
            : images.length
              ? await client.submitMessage(state.selectedConversation, message, { idempotencyKey: key }, images)
              : await client.submitMessage(state.selectedConversation, message, { idempotencyKey: key })
          : audios.length
            ? await client.createConversation(
                message.slice(0, 32),
                message,
                { idempotencyKey: key },
                newModelId || state.bootstrap?.defaultModelId,
                images,
                initialModelSelection,
                audios,
              )
            : images.length
              ? await client.createConversation(
                  message.slice(0, 32),
                  message,
                  { idempotencyKey: key },
                  newModelId || state.bootstrap?.defaultModelId,
                  images,
                  initialModelSelection,
                )
              : await client.createConversation(
                  message.slice(0, 32),
                  message,
                  { idempotencyKey: key },
                  newModelId || state.bootstrap?.defaultModelId,
                  [],
                  initialModelSelection,
                );
      } catch (error) {
        if (!(error instanceof PersonalAssistantApiError) || error.code !== "CONVERSATION_ACTIVE") throw error;
        const conversationId = state.selectedConversationId;
        if (conversationId) {
          await Promise.allSettled([loadConversation(conversationId), loadConversations()]);
        } else {
          await loadConversations().catch(() => undefined);
        }
        dispatch({
          type: "error",
          message: "当前会话已有任务在运行，已同步最新状态。请等待任务完成后再发送。",
        });
        return;
      }
      if (!retryImages && !retryAudios) {
        dispatch({ type: "setComposer", value: "" });
        sentImages.forEach((image) => revokePreview(image.previewUrl));
        setPendingImages([]);
        setPendingAudios([]);
        closeImageTools();
      }
      if (state.selectedConversationId !== conversation.id) {
        selectConversation(conversation.id, "replace");
      } else {
        dispatch({ type: "conversationLoaded", conversation });
        await loadConversation(conversation.id);
      }
      await loadConversations();
    }).finally(() => {
      messageSubmissionInFlight.current = false;
    });
  };

  const selectModel = (modelId: string, preferences: ModelPreferences) => {
    const conversation = state.selectedConversation;
    if (!conversation) {
      setNewModelId(modelId);
      setNewModelPreferences(preferences);
      return;
    }
    if (conversation.activeRunId || !client.selectModel) return;
    const target = state.bootstrap?.models.find((model) => model.id === modelId);
    if (!target) return;
    void execute("切换模型", async () => {
      await client.selectModel!(conversation, target, { idempotencyKey: crypto.randomUUID() }, preferences);
      await loadConversation(conversation.id);
      await loadConversations();
    });
  };

  const closeModelCenter = () => {
    closeModelCenterState(() => textareaRef.current?.focus());
  };

  const applyModelFromCenter = (model: Model, preferences: ModelPreferences) => {
    selectModel(model.id, preferences);
  };

  const configuredModels = state.bootstrap?.models ?? [];
  const modelProviders = groupModelsByProvider(configuredModels);
  const selectedModelId = state.selectedConversation?.model.model.id ?? newModelId;
  const selectedModel = state.selectedConversation?.model.model
    ?? configuredModels.find((model) => model.id === (selectedModelId || state.bootstrap?.defaultModelId));
  const selectedProviderConnected = modelConnections?.some(
    (connection) => connection.providerId === selectedModel?.providerId && connection.status === "AUTHENTICATED",
  );
  const conversationModelSelection = state.selectedConversation?.model;
  const isModelUnavailable = Boolean(
    conversationModelSelection && (
      conversationModelSelection.available === false ||
      conversationModelSelection.selectionCompatibility === "UNAVAILABLE"
    ),
  );
  const recommendedModel = configuredModels.find(
    (model) => model.id === state.bootstrap?.defaultModelId && model.availability === "AVAILABLE",
  ) ?? configuredModels.find((model) => model.availability === "AVAILABLE") ?? null;
  const applyRecommendedModel = () => {
    if (!recommendedModel || !state.selectedConversation) return;
    selectModel(recommendedModel.id, recommendedModel.recommendedPreferences);
  };
  const selectedModelPreferences = state.selectedConversation?.model.preferences
    ?? newModelPreferences
    ?? configuredModels.find((model) => model.id === (newModelId || state.bootstrap?.defaultModelId))?.recommendedPreferences
    ?? null;
  const selectedInputModel = state.selectedConversation?.model.model
    ?? configuredModels.find((model) => model.id === (newModelId || state.bootstrap?.defaultModelId));
  const selectedInputCapabilities = selectedInputModel?.capabilities ?? [];
  const selectedImageInput = selectedInputModel?.imageInput ?? null;
  const imageUploadCapable = selectedImageInput
    ? selectedImageInput.allowedSources.includes("UPLOAD")
    : selectedInputCapabilities.includes("IMAGE_UPLOAD_INPUT")
      || selectedInputCapabilities.includes("IMAGE_INPUT");
  const imageUrlCapable = selectedImageInput
    ? selectedImageInput.allowedSources.includes("URL")
    : selectedInputCapabilities.includes("IMAGE_URL_INPUT")
      || selectedInputCapabilities.includes("IMAGE_INPUT");
  const imageCapable = imageUploadCapable || imageUrlCapable;
  const audioCapable = selectedInputCapabilities.includes("AUDIO_INPUT");
  const imageMaxCount = selectedImageInput?.maxImagesPerRequest ?? 4;
  const imageMaxBytesPerItem = selectedImageInput?.maxBytesPerItem ?? 10 * 1024 * 1024;
  const imageMaxUrlCharacters = selectedImageInput?.maxUrlCharacters ?? 2048;
  const imageMediaTypes = new Set(
    selectedImageInput?.supportedMediaTypes ?? ["image/png", "image/jpeg", "image/webp", "image/gif"],
  );
  const imageTotalLabel = selectedImageInput
    ? `${Math.round(selectedImageInput.maxTotalBytes / (1024 * 1024))} MB`
    : "20 MB";
  const pendingMediaCount = pendingImages.length + pendingAudios.length;

  const addImageUrl = () => {
    if (!imageUrlCapable || pendingImages.length >= imageMaxCount) return;
    try {
      const parsed = new URL(imageUrl.trim());
      if (parsed.protocol !== "https:") throw new Error("not HTTPS");
      if (parsed.toString().length > imageMaxUrlCharacters) throw new Error("URL too long");
      setPendingImages((current) => [...current, {
        kind: "url",
        url: parsed.toString(),
        key: crypto.randomUUID(),
        label: parsed.hostname,
      }]);
      closeImageTools();
    } catch {
      dispatch({ type: "error", message: "图片 URL 必须是可公开访问的 HTTPS 地址，且长度符合当前模型限制。" });
    }
  };

  const uploadFiles = async (files: FileList | File[]) => {
    if (!imageUploadCapable || !client.uploadImage || uploadingImage) return;
    const selected = Array.from(files).slice(0, Math.max(0, imageMaxCount - pendingImages.length));
    if (!selected.length) return;
    if (selected.some((file) => {
      const type = file.type || (file.name.toLowerCase().endsWith(".pdf") ? "application/pdf" : "");
      return !imageMediaTypes.has(type) || file.size < 1 || file.size > imageMaxBytesPerItem;
    })) {
      dispatch({ type: "error", message: imageMediaTypes.has("application/pdf") ? "文件格式或单文件大小不符合当前模型的输入限制。" : "图片格式或单张大小不符合当前模型的图片输入限制。" });
      return;
    }
    setUploadingImage(true);
    const uploaded: PendingImage[] = [];
    try {
      for (const file of selected) {
        const result = await client.uploadImage(file, {
          idempotencyKey: crypto.randomUUID(),
        });
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
      setPendingImages((current) => [...current, ...uploaded].slice(0, imageMaxCount));
      closeImageTools();
    } catch (error) {
      uploaded.forEach((image) => revokePreview(image.previewUrl));
      dispatch({ type: "error", message: safeError(error) || (imageMediaTypes.has("application/pdf") ? "文件上传失败。" : "图片上传失败。") });
    } finally {
      setUploadingImage(false);
      if (fileInputRef.current) fileInputRef.current.value = "";
    }
  };

  const uploadAudioFiles = async (files: FileList | File[]) => {
    if (!audioCapable || !client.uploadAudio || uploadingAudio) return;
    const selected = Array.from(files).slice(0, Math.max(0, 4 - pendingMediaCount));
    if (!selected.length) return;
    const allowed = new Set([
      "audio/wav",
      "audio/mpeg",
      "audio/mp3",
      "audio/aiff",
      "audio/aac",
      "audio/ogg",
      "audio/flac",
    ]);
    if (selected.some((file) => !allowed.has(file.type) || file.size < 1 || file.size > 10 * 1024 * 1024)) {
      dispatch({ type: "error", message: "仅支持不超过 10 MiB 的 WAV、MP3、AIFF、AAC、OGG Vorbis 或 FLAC。" });
      return;
    }
    setUploadingAudio(true);
    const uploaded: PendingAudio[] = [];
    try {
      for (const file of selected) {
        const result = await client.uploadAudio(file, { idempotencyKey: crypto.randomUUID() });
        uploaded.push({
          kind: "upload",
          audioId: result.audioId,
          key: crypto.randomUUID(),
          label: file.name,
        });
      }
      setPendingAudios((current) => [...current, ...uploaded].slice(0, 4));
      closeImageTools();
    } catch (error) {
      dispatch({ type: "error", message: safeError(error) || "音频上传失败。" });
    } finally {
      setUploadingAudio(false);
      if (audioInputRef.current) audioInputRef.current.value = "";
    }
  };

  const removePendingImage = (key: string) => {
    setPendingImages((current) => {
      const removed = current.find((image) => image.key === key);
      revokePreview(removed?.previewUrl);
      return current.filter((image) => image.key !== key);
    });
  };

  const removePendingAudio = (key: string) => {
    setPendingAudios((current) => current.filter((audio) => audio.key !== key));
  };

  const explainPendingImages = () => {
    if (!pendingImages.length) return;
    const hasPdf = pendingImages.some(isPdfMedia);
    const hasImg = pendingImages.some((img) => !isPdfMedia(img));
    if (hasPdf && !hasImg) {
      dispatch({
        type: "setComposer",
        value: "请概述下PDF主要内容 观点 证据",
      });
    } else {
      dispatch({
        type: "setComposer",
        value: pendingImages.length > 1 ? "请分别解释这些图片内容" : "解释下图片内容",
      });
    }
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
  const selectedSlashProvider = slashMenu?.stage === "models" || slashMenu?.stage === "settings"
    ? modelProviders.find((provider) => provider.id === slashMenu.providerId) ?? null
    : null;
  const selectedSlashModelGroup = slashMenu?.stage === "settings"
    ? selectedSlashProvider?.modelGroups.find((group) => group.id === slashMenu.modelGroupId) ?? null
    : null;
  const modelDraftBinding = selectedSlashModelGroup?.bindings.find((binding) => binding.id === modelDraftBindingId)
    ?? (selectedSlashModelGroup ? recommendedBinding(selectedSlashModelGroup) : null);
  const slashItemCount = slashMenu?.stage === "commands"
    ? visibleSlashCommands.length
    : slashMenu?.stage === "providers"
      ? modelProviders.length
      : slashMenu?.stage === "models"
        ? selectedSlashProvider?.modelGroups.length ?? 0
        : 0;

  const updateComposer = (value: string) => {
    dispatch({ type: "setComposer", value });
    const slashInput = value.startsWith("/")
      && !value.includes("\n")
      && !/^\/deep-research\s+\S/i.test(value);
    if (slashInput) setSlashFromPlus(false);
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
      const command = visibleSlashCommands[index];
      if (!command) return;
      if (command.id === "deep-research") {
        dispatch({ type: "setComposer", value: "/deep-research " });
        setSlashMenu(null);
        setSlashActiveIndex(0);
        window.requestAnimationFrame(() => textareaRef.current?.focus());
        return;
      }
      dispatch({ type: "setComposer", value: "" });
      setSlashMenu(null);
      setSlashActiveIndex(0);
      openModelCenter("catalog");
      return;
    }
    if (slashMenu.stage === "providers") {
      const provider = modelProviders[index];
      if (!provider) return;
      setSlashMenu({ stage: "models", providerId: provider.id });
      const currentModelIndex = provider.modelGroups.findIndex((group) =>
        group.bindings.some((model) => model.id === selectedModelId)
      );
      setSlashActiveIndex(Math.max(0, currentModelIndex));
      return;
    }
    if (slashMenu.stage !== "models") return;
    const group = selectedSlashProvider?.modelGroups[index];
    if (!group) return;
    const current = availableBindings(group).find((binding) => binding.id === selectedModelId);
    const binding = current ?? recommendedBinding(group);
    if (!binding) return;
    setModelDraftBindingId(binding.id);
    setModelDraftPreferences(current && selectedModelPreferences
      ? selectedModelPreferences
      : binding.recommendedPreferences);
    setSlashMenu({ stage: "settings", providerId: slashMenu.providerId, modelGroupId: group.id });
    setSlashActiveIndex(0);
  };

  const updateDraftBinding = (bindingId: string) => {
    const binding = selectedSlashModelGroup?.bindings.find((candidate) => candidate.id === bindingId);
    if (!binding || binding.availability !== "AVAILABLE") return;
    setModelDraftBindingId(binding.id);
    setModelDraftPreferences(binding.recommendedPreferences);
  };

  const applyModelDraft = () => {
    if (!modelDraftBinding || !modelDraftPreferences) return;
    selectModel(modelDraftBinding.id, modelDraftPreferences);
    if (!slashFromPlus) dispatch({ type: "setComposer", value: "" });
    setSlashMenu(null);
    setSlashFromPlus(false);
    setSlashActiveIndex(0);
  };

  const returnFromSlashMenu = () => {
    if (slashMenu?.stage === "settings") {
      setSlashMenu({ stage: "models", providerId: slashMenu.providerId });
    } else if (slashMenu?.stage === "models") {
      setSlashMenu({ stage: "providers" });
    } else {
      dispatch({ type: "setComposer", value: "/" });
      setSlashMenu({ stage: "commands" });
    }
    setSlashActiveIndex(0);
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const raw = state.composer.trim();
    const explicitRequested = explicitlyRequestsDeepResearch(raw);
    const objective = composerMode === "DEEP_RESEARCH" ? raw : explicitDeepResearchObjective(raw);
    if (composerMode === "DEEP_RESEARCH" || explicitRequested) {
      if (!objective) {
        dispatch({ type: "error", message: "请补充 Deep Research 的目标。" });
        return;
      }
      if (!state.selectedConversation) {
        dispatch({ type: "error", message: "请先选择一个已有会话，再从该会话发起 Deep Research Mission。" });
        return;
      }
      if (isModelUnavailable) {
        dispatch({
          type: "error",
          message: "当前会话模型已下线，请先选择可用模型后再发起 Deep Research Mission。",
        });
        openModelCenter("catalog");
        return;
      }
      if (!state.bootstrap?.capabilities.includes("mission") || !client.createMission) {
        dispatch({ type: "error", message: "当前 Server 未发布 Mission 能力。" });
        return;
      }
      if (pendingMediaCount > 0) {
        dispatch({ type: "error", message: "Deep Research Mission 暂不支持图片或附件引用。请移除附件后再继续，附件不会被静默丢弃。" });
        return;
      }
      previousFocus.current = document.activeElement as HTMLElement;
      setMissionDraft({
        requestId: crypto.randomUUID(),
        idempotencyKey: crypto.randomUUID(),
        objective,
      });
      setMissionRouteId(null);
      setMissionOpen(true);
      setComposerMode("CHAT");
      setSlashMenu(null);
      dispatch({ type: "setComposer", value: "" });
      return;
    }
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
    setMissionDraft(null);
    setMissionRouteId(null);
    setRequestedMissionTaskId(null);
    setRequestedMissionArtifact(null);
    const query = new URLSearchParams(window.location.search);
    if (state.selectedConversationId) query.set(conversationIdParameter, state.selectedConversationId);
    const queryText = query.toString();
    const nextUrl = queryText ? `/?${queryText}` : "/";
    window.history.pushState(null, "", nextUrl);
    window.setTimeout(() => previousFocus.current?.focus(), 0);
  }, [state.selectedConversationId]);

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
  const dropMedia = (event: DragEvent<HTMLFormElement>) => {
    event.preventDefault();
    setDraggingImages(false);
    if (composerDisabled) return;
    const files = Array.from(event.dataTransfer.files);
    const images = files.filter((file) => file.type.startsWith("image/"));
    const audios = files.filter((file) => file.type.startsWith("audio/"));
    void (async () => {
      if (images.length) await uploadFiles(images);
      if (audios.length) await uploadAudioFiles(audios);
    })();
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
          {client.modelConnections && (
            <button className="button" onClick={() => openModelCenter("connections")}>
              <KeyRound size={16} /> 模型连接{modelConnections?.length ? <b>{modelConnections.length}</b> : null}
            </button>
          )}
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
              setMissionDraft(null);
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
          onNew={startNewConversation}
          onToggleArchived={() => dispatch({ type: "toggleArchived" })}
          onRename={setRenameTarget}
          onArchive={archive}
          onClose={() => dispatch({ type: "toggleSidebar", open: false })}
        />

        <main className="conversation">
          <ConversationHeader
            modelConnections={modelConnections}
            selectedModel={selectedModel}
            isModelUnavailable={isModelUnavailable}
            selectedProviderConnected={selectedProviderConnected}
            selectedConversation={state.selectedConversation}
            selectedModelPreferences={selectedModelPreferences}
            runStatus={state.run?.status}
            onOpenModelCenter={openModelCenter}
          />
          {conversationMission && !missionTerminalStates.has(conversationMission.state) && (
            <button type="button" className="conversation-mission-card" onClick={() => setMissionOpen(true)}>
              <span><b>Mission</b>{missionStateLabel(conversationMission.state)}</span>
              <strong>{missionDisplayTitle(conversationMission, state.selectedConversation, state.turns)}</strong>
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
                  const deliveryId = assistant ? missionDeliveryId(turn.text) : null;
                  const deliveryMission = deliveryId
                    ? conversationMissions.find((mission) => mission.missionId === deliveryId) ?? null
                    : null;
                  const research = assistant
                    && !deliveryMission
                    && turn.text.includes("<!-- haifa-section:")
                    && researchReadingContext
                    && researchReadingContext.missionId === conversationMission?.missionId
                    ? researchReadingContext
                    : undefined;
                  const embeddedResearch = assistant && hasEmbeddedMarkdownResearchSources(turn.text);
                  const recommendation = recommendedQuestions?.turnId === turn.id
                    ? recommendedQuestions
                    : null;
                  return (
                    <article className={`message ${assistant ? "assistant" : "user"}${turn.images?.length || turn.audios?.length ? " has-images" : ""}${research || embeddedResearch ? " research-report-message" : ""}${deliveryMission ? " mission-delivery-message" : ""}`} key={turn.id}>
                      <span className="message-role">{assistant ? "Haifa" : "你"}</span>
                      {turn.images?.length > 0 && <TurnImages images={turn.images} />}
                      {turn.audios?.length > 0 && <TurnAudios audios={turn.audios} />}
                      {deliveryMission
                        ? <MissionDeliveryCard
                            mission={deliveryMission}
                            title={missionDisplayTitle(deliveryMission, state.selectedConversation, state.turns)}
                            onOpenReport={() => {
                              setRequestedMissionArtifact("research-report.md");
                              navigateToMission(deliveryMission);
                              setMissionOpen(true);
                            }}
                            onOpenEvidence={() => {
                              setRequestedMissionArtifact("sources.json");
                              navigateToMission(deliveryMission);
                              setMissionOpen(true);
                            }}
                            onContinue={() => {
                              setComposerMode("CHAT");
                              dispatch({ type: "setComposer", value: `关于“${deliveryMission.objective}”，我想继续了解：` });
                              window.setTimeout(() => document.querySelector<HTMLTextAreaElement>(".composer textarea")?.focus(), 0);
                            }}
                          />
                        : <MessageContent text={turn.text} research={research} researchAnchorPrefix={`conversation-turn-${turn.id}`} onResearchTaskSelect={openResearchTask} />}
                      <time>{formatTime(turn.createdAt)}</time>
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
            className={`composer${composerMode === "DEEP_RESEARCH" ? " deep-research-mode" : ""}${imageCapable || audioCapable ? " image-capable" : ""}${pendingMediaCount ? " has-pending-images" : ""}${draggingImages ? " image-dragging" : ""}`}
            onSubmit={submit}
            onDragEnter={(event) => {
              event.preventDefault();
              if ((imageUploadCapable || audioCapable) && !composerDisabled) setDraggingImages(true);
            }}
            onDragOver={(event) => {
              event.preventDefault();
              if ((imageUploadCapable || audioCapable) && !composerDisabled) setDraggingImages(true);
            }}
            onDragLeave={(event) => {
              if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setDraggingImages(false);
            }}
            onDrop={dropMedia}
          >
            {composerMode === "DEEP_RESEARCH" && <div className="composer-mode-chip"><Sparkles size={13} aria-hidden="true" /><span>Deep Research</span><button type="button" aria-label="退出 Deep Research 模式" title="退出 Deep Research 模式" onClick={() => setComposerMode("CHAT")}><X size={12} /></button></div>}
            {slashMenu && !composerDisabled && (
              <section
                ref={slashMenuRef}
                className="slash-command-menu"
                role="dialog"
                aria-label={
                  slashMenu.stage === "commands"
                    ? "命令功能"
                    : slashMenu.stage === "providers"
                      ? "选择模型厂商"
                      : slashMenu.stage === "models"
                        ? `选择 ${selectedSlashProvider?.displayName ?? ""} 模型`
                        : `设置 ${selectedSlashModelGroup?.displayName ?? ""}`
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
                          : slashMenu.stage === "models"
                            ? selectedSlashProvider?.displayName
                            : selectedSlashModelGroup?.displayName}
                    </strong>
                    <span>
                      {slashMenu.stage === "commands"
                        ? "选择一个命令继续"
                        : slashMenu.stage === "providers"
                          ? "先选择提供模型服务的厂商"
                          : slashMenu.stage === "models"
                            ? "选择本会话后续消息使用的模型"
                            : "优先使用推荐设置，需要时再展开高级连接方式"}
                    </span>
                  </div>
                  <button
                    type="button"
                    className="slash-close"
                    aria-label={slashMenu.stage === "commands" ? "关闭命令菜单" : "关闭模型选择"}
                    title="关闭（Esc）"
                    onClick={() => closeSlashMenu()}
                  >
                    <X size={13} aria-hidden="true" />
                    <span>Esc</span>
                  </button>
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
                        <small>
                          {availableModelGroupCount(provider)} 个可用模型
                          {provider.modelGroups.length > availableModelGroupCount(provider)
                            ? ` · ${provider.modelGroups.length - availableModelGroupCount(provider)} 个不可用`
                            : ""}
                          {` · ${provider.id}`}
                        </small>
                      </span>
                      <ChevronRight size={17} />
                    </button>
                  ))}
                  {slashMenu.stage === "models" && selectedSlashProvider?.modelGroups.map((group, index) => (
                    <button
                      type="button"
                      role="option"
                      aria-selected={index === slashActiveIndex}
                      aria-disabled={availableBindings(group).length === 0}
                      disabled={availableBindings(group).length === 0}
                      className={index === slashActiveIndex ? "active" : ""}
                      key={group.id}
                      onMouseEnter={() => setSlashActiveIndex(index)}
                      onClick={() => activateSlashItem(index)}
                    >
                      <Bot size={18} />
                      <span>
                        <strong>{group.displayName}</strong>
                        <small>
                          {availableBindings(group).length === 0
                            ? `不可用 · ${unavailableModelReason(group)}`
                            : `${availableBindings(group).length} 种可用连接方式${unavailableBindings(group).length > 0 ? ` · ${unavailableBindings(group).length} 种不可用` : ""}`}
                        </small>
                      </span>
                      {group.bindings.some((binding) => binding.id === selectedModelId)
                        ? <span className="slash-current"><Check size={13} /> 当前</span>
                        : <ChevronRight size={17} />}
                    </button>
                  ))}
                  {slashMenu.stage === "settings" && modelDraftBinding && modelDraftPreferences && (
                    <div className="model-settings-panel">
                      <div className="model-setting-summary">
                        <Bot size={20} aria-hidden="true" />
                        <span><strong>{modelDraftBinding.modelDisplayName}</strong><small>{modelDraftBinding.providerDisplayName} · {modelDraftBinding.controls.responseMode.effectiveSummary}</small></span>
                      </div>
                      {modelDraftBinding.controls.responseMode.visible && (
                        <fieldset disabled={modelDraftBinding.controls.responseMode.readOnly}>
                          <legend>响应模式</legend>
                          <div className="model-segmented-control">
                            {modelDraftBinding.controls.responseMode.allowedValues.map((value) => (
                              <button type="button" key={value} aria-pressed={modelDraftPreferences.responseMode === value} onClick={() => setModelDraftPreferences({
                                ...modelDraftPreferences,
                                responseMode: value,
                                effort: value === "DEEP" ? modelDraftPreferences.effort : null,
                              })}>{responseModeLabels[value]}</button>
                            ))}
                          </div>
                          <small>{modelDraftBinding.controls.responseMode.helpText}</small>
                        </fieldset>
                      )}
                      {modelDraftBinding.controls.responseLength.visible && (
                        <fieldset disabled={modelDraftBinding.controls.responseLength.readOnly}>
                          <legend>回答长度</legend>
                          <div className="model-segmented-control">
                            {modelDraftBinding.controls.responseLength.allowedValues.map((value) => (
                              <button type="button" key={value} aria-pressed={modelDraftPreferences.responseLength === value} onClick={() => setModelDraftPreferences({
                                ...modelDraftPreferences,
                                responseLength: value,
                              })}>{responseLengthLabels[value]}</button>
                            ))}
                          </div>
                        </fieldset>
                      )}
                      {modelDraftBinding.controls.reasoningEffort.visible && modelDraftPreferences.responseMode === "DEEP" && (
                        <fieldset disabled={modelDraftBinding.controls.reasoningEffort.readOnly}>
                          <legend>推理强度</legend>
                          <div className="model-segmented-control">
                            {modelDraftBinding.controls.reasoningEffort.allowedValues.map((value) => (
                              <button type="button" key={value} aria-pressed={(modelDraftPreferences.effort ?? modelDraftBinding.controls.reasoningEffort.recommendedValue) === value} onClick={() => setModelDraftPreferences({
                                ...modelDraftPreferences,
                                effort: value,
                              })}>{effortLabels[value] ?? value}</button>
                            ))}
                          </div>
                          <small>{modelDraftBinding.controls.reasoningEffort.helpText}</small>
                        </fieldset>
                      )}
                      {modelDraftBinding.controls.apiStyle.visible && (
                        <details className="model-advanced-settings">
                          <summary>高级连接方式</summary>
                          <label>
                            <span>API 风格</span>
                            <select aria-label="API 风格" disabled={modelDraftBinding.controls.apiStyle.readOnly} value={modelDraftBinding.id} onChange={(event) => updateDraftBinding(event.target.value)}>
                              {selectedSlashModelGroup?.bindings
                                .filter((binding) => modelDraftBinding.controls.apiStyle.allowedValues.includes(binding.id)
                                  || binding.availability === "UNAVAILABLE")
                                .map((binding) => (
                                  <option key={binding.id} value={binding.id} disabled={binding.availability !== "AVAILABLE"}>
                                    {binding.apiStyleDisplayName}{binding.availability === "UNAVAILABLE" ? ` · 不可用：${binding.unavailableReason || "尚未通过验证"}` : ""}
                                  </option>
                                ))}
                            </select>
                          </label>
                          <small>{modelDraftBinding.controls.apiStyle.helpText}</small>
                        </details>
                      )}
                      <div className="model-settings-actions">
                        <button type="button" className="button" onClick={() => setModelDraftPreferences(modelDraftBinding.recommendedPreferences)}>恢复推荐</button>
                        <button type="button" className="button primary-button" disabled={Boolean(state.selectedConversation?.activeRunId)} onClick={applyModelDraft}>应用设置</button>
                      </div>
                      {state.selectedConversation?.activeRunId && <p className="model-settings-locked">当前任务运行中，完成后可修改下一次任务的模型设置。</p>}
                    </div>
                  )}
                  {slashMenu.stage !== "settings" && slashItemCount === 0 && <p>没有匹配的命令或可用模型。</p>}
                </div>
                {slashMenu.stage !== "settings" && <footer><span>↑↓ 选择</span><span>Enter 确认</span></footer>}
              </section>
            )}
            <input
              ref={fileInputRef}
              className="sr-only"
              type="file"
              accept={Array.from(imageMediaTypes).map((t) => (t === "application/pdf" ? ".pdf,application/pdf" : t)).join(",")}
              multiple
              onChange={(event) => event.target.files && void uploadFiles(event.target.files)}
            />
            <input
              ref={audioInputRef}
              className="sr-only"
              type="file"
              accept="audio/wav,audio/mpeg,audio/mp3,audio/aiff,audio/aac,audio/ogg,audio/flac"
              multiple
              onChange={(event) => event.target.files && void uploadAudioFiles(event.target.files)}
            />
            {pendingMediaCount > 0 && (() => {
              const hasPdf = pendingImages.some(isPdfMedia);
              const hasImg = pendingImages.some((img) => !isPdfMedia(img));
              const hasAudio = pendingAudios.length > 0;
              return (
                <section className="image-attachment-stage" aria-label={hasPdf ? "待发送文件" : pendingAudios.length ? "待发送媒体" : "待发送图片"}>
                  <div className="image-attachment-row">
                    {pendingImages.map((image, index) => {
                      const isPdf = isPdfMedia(image);
                      return (
                        <figure key={image.key} className={isPdf ? "image-attachment pdf-attachment" : "image-attachment"}>
                          {isPdf ? (
                            <span className="image-file-icon pdf-file-icon"><FileText size={22} aria-hidden="true" /></span>
                          ) : image.url || image.previewUrl ? (
                            <img src={image.url ?? image.previewUrl} alt={`待发送图片 ${index + 1}`} />
                          ) : (
                            <span className="image-file-icon"><ImageIcon size={22} aria-hidden="true" /></span>
                          )}
                          <figcaption>{image.label}</figcaption>
                          <button
                            type="button"
                            aria-label={isPdf ? `移除 PDF ${image.label}` : `移除图片 ${image.label}`}
                            onClick={() => removePendingImage(image.key)}
                          ><X size={13} /></button>
                        </figure>
                      );
                    })}
                    {pendingAudios.map((audio, index) => (
                      <div key={audio.key} className="audio-attachment">
                        <AudioLines size={22} aria-hidden="true" />
                        <span><b>{index + 1}</b>{audio.label}</span>
                        <button
                          type="button"
                          aria-label={`移除音频 ${audio.label}`}
                          onClick={() => removePendingAudio(audio.key)}
                        ><X size={13} /></button>
                      </div>
                    ))}
                  </div>
                  <div className="explain-image-actions">
                    {hasPdf && !hasImg && !hasAudio ? (
                      <button
                        className="explain-image-action"
                        type="button"
                        onClick={() => {
                          dispatch({
                            type: "setComposer",
                            value: "请概述下PDF主要内容 观点 证据",
                          });
                          window.requestAnimationFrame(() => textareaRef.current?.focus());
                        }}
                      >
                        <Sparkles size={14} />请概述下PDF主要内容 观点 证据 <span>→</span>
                      </button>
                    ) : hasImg && !hasPdf && !hasAudio ? (
                      <button
                        className="explain-image-action"
                        type="button"
                        onClick={explainPendingImages}
                      >
                        <Sparkles size={14} />解释下图片内容 <span>→</span>
                      </button>
                    ) : (
                      <button
                        className="explain-image-action"
                        type="button"
                        onClick={() => {
                          dispatch({
                            type: "setComposer",
                            value: hasAudio && !hasImg && !hasPdf
                              ? (pendingAudios.length > 1 ? "请分别分析这些音频" : "请转写并分析这段音频")
                              : "请分析这些上传的内容",
                          });
                          window.requestAnimationFrame(() => textareaRef.current?.focus());
                        }}
                      >
                        <Sparkles size={14} />{hasAudio && !hasImg && !hasPdf ? "转写并分析音频" : "分析媒体"} <span>→</span>
                      </button>
                    )}
                  </div>
                  <span className="image-attachment-count">{pendingMediaCount}/4</span>
                </section>
              );
            })()}
            <div className="image-add-control" ref={imageToolsRef}>
                <button
                  type="button"
                  className="image-add-trigger"
                  aria-label="更多功能"
                  aria-controls="composer-add-menu"
                  aria-expanded={imageToolsOpen}
                  title="更多功能"
                  disabled={composerDisabled}
                  onClick={() => {
                    setSlashMenu(null);
                    if (imageToolsOpen) closeImageTools();
                    else setImageToolsOpen(true);
                  }}
                >
                  <Plus size={20} />
                </button>
                {imageToolsOpen && (
                  <section className="image-add-menu" id="composer-add-menu" role="dialog" aria-label="更多功能">
                    <header>
                      <strong>更多功能</strong>
                      <button type="button" aria-label="关闭更多功能" onClick={closeImageTools}><X size={15} /></button>
                    </header>
                    <button type="button" onClick={() => {
                      setComposerMode("DEEP_RESEARCH");
                      closeImageTools();
                      window.requestAnimationFrame(() => textareaRef.current?.focus());
                    }}>
                      <Sparkles size={17} />
                      <span><strong>Deep Research</strong><small>输入研究目标后打开 Mission 确认页</small></span>
                    </button>
                    <button type="button" onClick={() => {
                      openModelCenter("catalog");
                      closeImageTools();
                    }}>
                      <Bot size={17} />
                      <span><strong>选择模型</strong><small>选择当前会话后续消息使用的模型</small></span>
                    </button>
                    {imageUploadCapable && <button
                      type="button"
                      disabled={composerDisabled || uploadingImage || pendingImages.length >= imageMaxCount}
                      onClick={() => fileInputRef.current?.click()}
                    >
                      <Paperclip size={17} />
                      <span><strong>{uploadingImage ? "正在上传…" : (imageMediaTypes.has("application/pdf") ? "上传图片或PDF" : "上传图片")}</strong><small>选择或拖放，最多 {imageMaxCount} 个/{imageTotalLabel}</small></span>
                    </button>}
                    {audioCapable && <button
                      type="button"
                      disabled={composerDisabled || uploadingAudio || pendingMediaCount >= 4}
                      onClick={() => audioInputRef.current?.click()}
                    >
                      <AudioLines size={17} />
                      <span><strong>{uploadingAudio ? "正在上传…" : "上传音频"}</strong><small>WAV、MP3、AIFF、AAC、OGG 或 FLAC</small></span>
                    </button>}
                    {!imageCapable && (
                      <button
                        type="button"
                        disabled
                        title="当前所选模型不支持图片输入。如需分析图片，请在模型目录中切换至多模态模型。"
                      >
                        <ImageIcon size={17} />
                        <span><strong>附带图片</strong><small>当前模型不支持</small></span>
                      </button>
                    )}
                    {imageUrlCapable && <button
                      type="button"
                      aria-expanded={imageUrlInputOpen}
                      onClick={() => setImageUrlInputOpen((open) => !open)}
                    >
                      <Link size={17} />
                      <span><strong>添加图片 URL</strong><small>仅支持 HTTPS 图片地址</small></span>
                    </button>}
                    {imageUrlCapable && imageUrlInputOpen && (
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
                            disabled={composerDisabled || pendingImages.length >= imageMaxCount}
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
                            disabled={!imageUrl.trim() || composerDisabled || pendingImages.length >= imageMaxCount}
                            onClick={addImageUrl}
                          >添加</button>
                        </div>
                      </div>
                    )}
                  </section>
                )}
            </div>
            {draggingImages && (
              <div className="image-drop-hint" aria-live="polite">
                {audioCapable ? <AudioLines size={19} /> : <ImageIcon size={19} />} 松开即可添加媒体
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
                    if (slashMenu.stage === "settings") applyModelDraft();
                    else activateSlashItem(slashActiveIndex);
                    return;
                  }
                  if (event.key === "Enter" && !event.shiftKey) {
                    event.preventDefault();
                    event.currentTarget.form?.requestSubmit();
                  }
                }}
                placeholder={runActive ? "当前任务运行中" : isModelUnavailable ? "当前模型已下线，请先选择可用模型" : composerMode === "DEEP_RESEARCH" ? "描述调研目标，Enter 打开 Mission 确认页" : "输入消息，Enter 发送"}
                rows={4}
              />
            </label>
            <span className="image-input-hint">{isModelUnavailable ? "当前模型不可用，请重新选择模型后再发送消息" : composerMode === "DEEP_RESEARCH" ? "将打开 Mission 确认页" : audioCapable ? "支持原生图片、音频与 Deep Research" : imageCapable ? "支持图片与 Deep Research" : "点击 + 使用 Deep Research"}</span>
            {failedUserTurn && (
              <button
                type="button"
                className="retry-send-button"
                aria-label="重新发送上一条失败消息"
                title="重新发送上一条失败消息"
                disabled={composerDisabled}
                onClick={() => submitMessage(failedUserTurn.text, failedUserTurn.images, failedUserTurn.audios)}
              >
                <RefreshCw size={16} aria-hidden="true" />
              </button>
            )}
            <Button type="submit" className="send-button" aria-label={composerMode === "DEEP_RESEARCH" ? "准备 Deep Research" : "发送消息"} busy={Boolean(state.pending)} disabled={composerDisabled || (!state.composer.trim() && !pendingMediaCount)}>
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
          conversationTurns={state.turns}
          initialMissionId={missionRouteId}
          initialTaskId={requestedMissionTaskId}
          initialArtifactFileName={requestedMissionArtifact}
          initialDraft={missionDraft}
          webResearchAvailable={Boolean(state.bootstrap?.capabilities.includes("web-research"))}
          onDraftCreated={() => setMissionDraft(null)}
          onClose={closeMission}
          onChanged={handleMissionChanged}
          onSelected={navigateToMission}
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
      <ModelConnectionsModal
        client={client}
        open={modelConnectionsOpen}
        initialTab={modelCenterTab}
        models={configuredModels}
        modelConnections={modelConnections}
        selectedModelId={selectedModelId}
        activeRun={Boolean(state.selectedConversation?.activeRunId)}
        currentPreferences={state.selectedConversation?.model.preferences ?? null}
        selectionCompatibility={state.selectedConversation?.model.selectionCompatibility}
        onClose={closeModelCenter}
        onConnectionsChanged={setModelConnections}
        onSelectModel={applyModelFromCenter}
      />
      <div className="sr-only" aria-live="polite">{state.pending ? `${state.pending.label}进行中` : ""}</div>
    </div>
  );
}

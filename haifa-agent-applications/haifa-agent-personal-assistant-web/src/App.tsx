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
  Conversation,
  ExecutionError,
  Interaction,
  ImageInput,
  Memory,
  MemoryCandidate,
  MissionSnapshot,
  Model,
  ModelPreferences,
  Run,
  Turn,
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
const terminalStatuses = new Set(["COMPLETED", "FAILED", "CANCELLED", "TIMEOUT"]);
const conversationIdParameter = "conversationId";
const missionPathPattern = /^\/missions\/([^/]+)$/;
const approvalPreviewCharacters = 640;
const approvalPreviewLines = 14;
const number = new Intl.NumberFormat("zh-CN");
const dateTime = new Intl.DateTimeFormat("zh-CN", {
  month: "numeric",
  day: "numeric",
  hour: "2-digit",
  minute: "2-digit",
});

function missionIdFromLocation(): string | null {
  const match = missionPathPattern.exec(window.location.pathname);
  if (!match) return null;
  try {
    return decodeURIComponent(match[1]);
  } catch {
    return null;
  }
}

type SlashMenuState =
  | { stage: "commands" }
  | { stage: "providers" }
  | { stage: "models"; providerId: string }
  | { stage: "settings"; providerId: string; modelGroupId: string };

type ComposerMode = "CHAT" | "DEEP_RESEARCH";

interface MissionDraftRequest {
  requestId: string;
  idempotencyKey: string;
  objective: string;
}

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

const responseModeLabels = { RECOMMENDED: "推荐", FAST: "快速", DEEP: "深度" } as const;
const responseLengthLabels = { RECOMMENDED: "推荐", SHORT: "短", STANDARD: "标准", LONG: "长" } as const;
const effortLabels: Record<string, string> = { LOW: "Low", MEDIUM: "Medium", HIGH: "High", MAX: "Max" };

function recommendedBinding(group: ModelGroup): Model | null {
  const recommendedId = group.bindings[0]?.controls.apiStyle.recommendedValue;
  return group.bindings.find((binding) => binding.id === recommendedId) ?? group.bindings[0] ?? null;
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

type ResearchCitationSelection = {
  sources: MarkdownResearchSource[];
  numbers: number[];
  unavailable: boolean;
};

function safeResearchLocator(source: MarkdownResearchSource): string | null {
  for (const locator of [source.locator, source.normalizedLocator]) {
    if (!locator) continue;
    try {
      let readableLocator = locator;
      for (let pass = 0; pass < 3 && /(?:%25[0-9a-f]{2}){2,}/i.test(readableLocator); pass += 1) {
        readableLocator = decodeURI(readableLocator);
      }
      const url = new URL(readableLocator);
      if (["http:", "https:"].includes(url.protocol)) return url.toString();
    } catch {
      // Try the normalized locator when the original value is malformed.
    }
  }
  return null;
}

function ResearchCitationPanel({
  selection,
  onClose,
}: {
  selection: ResearchCitationSelection;
  onClose(): void;
}) {
  return (
    <section
      className="research-evidence-panel"
      aria-label="引用来源详情"
      onKeyDown={(event) => {
        if (event.key === "Escape") {
          event.preventDefault();
          event.stopPropagation();
          onClose();
        }
      }}
    >
      <header>
        <div><span className="eyebrow">EVIDENCE</span><h4>引用来源</h4></div>
        <button type="button" className="icon" aria-label="关闭引用来源" onClick={onClose} autoFocus><X size={16} /></button>
      </header>
      <div className="research-evidence-list">
        {selection.sources.map((source, index) => {
          const tier = researchSourceTier(source);
          const locator = safeResearchLocator(source);
          return <article className="research-source-entry" key={source.sourceId}>
            <div className="research-source-heading">
              <span>[{selection.numbers[index]}]</span>
              <span className={`research-source-tier tier-${tier.key}`}>{tier.label}</span>
            </div>
            <h5>{source.title || "未命名来源"}</h5>
            <dl>
              <div><dt>发布方</dt><dd>{source.publisher?.trim() || "未提供"}</dd></div>
              <div><dt>站点</dt><dd>{researchSourceSite(source)}</dd></div>
              <div><dt>日期</dt><dd>{researchSourceDate(source)}</dd></div>
              <div><dt>核验状态</dt><dd>{researchSourceStatus(source.status)}</dd></div>
            </dl>
            <p>{tier.note}</p>
            <p className="research-source-claim-note">支持关系：报告已引用，尚未独立复核该网页是否充分支持当前结论。</p>
            {locator
              ? <a href={locator} target="_blank" rel="noopener noreferrer"><Link size={13} />打开“{source.title || researchSourceSite(source)}”</a>
              : <span className="research-source-link-unavailable">网页链接不可用</span>}
          </article>;
        })}
        {selection.unavailable && <article className="research-source-entry unavailable"><h5>来源不可用</h5><p>报告引用未能在来源清单中匹配，因此不生成伪链接。</p></article>}
      </div>
    </section>
  );
}

function MessageContent({
  text,
  research,
  researchAnchorPrefix = "conversation-report",
  onResearchTaskSelect,
  onResearchCitationSelect,
}: {
  text: string;
  research?: MarkdownResearchContext;
  researchAnchorPrefix?: string;
  onResearchTaskSelect?(ordinal: number): void;
  onResearchCitationSelect?(selection: ResearchCitationSelection): void;
}) {
  const [localCitation, setLocalCitation] = useState<ResearchCitationSelection | null>(null);
  const inferenceKey = `${researchAnchorPrefix}:${text}`;
  const shouldInferResearch = !research && hasEmbeddedMarkdownResearchSources(text);
  const [inferredResearch, setInferredResearch] = useState<{
    key: string;
    context: MarkdownResearchContext;
  } | null>(null);
  useEffect(() => {
    if (!shouldInferResearch) {
      setInferredResearch(null);
      return;
    }
    let cancelled = false;
    void inferMarkdownResearchContext(text, researchAnchorPrefix)
      .then((context) => {
        if (cancelled) return;
        setInferredResearch({
          key: inferenceKey,
          context: context ?? {
            anchorPrefix: researchAnchorPrefix,
            tasks: [],
            sources: [],
            sourceState: "failed",
          },
        });
      })
      .catch(() => {
        if (!cancelled) {
          setInferredResearch({
            key: inferenceKey,
            context: {
              anchorPrefix: researchAnchorPrefix,
              tasks: [],
              sources: [],
              sourceState: "failed",
            },
          });
        }
      });
    return () => { cancelled = true; };
  }, [inferenceKey, researchAnchorPrefix, shouldInferResearch, text]);
  const effectiveResearch = research ?? (
    shouldInferResearch
      ? inferredResearch?.key === inferenceKey
        ? inferredResearch.context
        : { anchorPrefix: researchAnchorPrefix, tasks: [], sources: [], sourceState: "loading" }
      : undefined
  );
  const rendered = renderMarkdownDocument(text, effectiveResearch);
  const handleClick = useCallback(async (event: MouseEvent<HTMLDivElement>) => {
    const target = event.target as HTMLElement;
    const taskButton = target.closest<HTMLButtonElement>(".research-task-reference[data-task-ordinal]");
    if (taskButton) {
      const ordinal = Number(taskButton.dataset.taskOrdinal);
      if (Number.isInteger(ordinal) && ordinal > 0) onResearchTaskSelect?.(ordinal);
      return;
    }
    const citationButton = target.closest<HTMLButtonElement>(".research-citation-button[data-source-indexes]");
    if (citationButton && effectiveResearch) {
      const sourceIndexes = (citationButton.dataset.sourceIndexes ?? "")
        .split(",")
        .map((value) => Number(value))
        .filter(Number.isInteger);
      const numbers = (citationButton.dataset.sourceNumbers ?? "")
        .split(",")
        .map((value) => Number(value))
        .filter(Number.isFinite);
      const selection = {
        sources: sourceIndexes.flatMap((sourceIndex) => {
          const source = effectiveResearch.sources[sourceIndex];
          return source ? [source] : [];
        }),
        numbers,
        unavailable: citationButton.dataset.sourceUnavailable === "true",
      };
      if (onResearchCitationSelect) onResearchCitationSelect(selection);
      else setLocalCitation(selection);
      return;
    }
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
  }, [effectiveResearch, onResearchCitationSelect, onResearchTaskSelect]);

  const content = (
    <div
      className="message-content"
      onClick={handleClick}
      dangerouslySetInnerHTML={{ __html: rendered.html }}
    />
  );
  const documentView = rendered.sections.length === 0 ? content : (
    <div className="research-document">
      <nav className="research-document-toc" aria-label="报告目录">
        <span>报告目录</span>
        <ol>{rendered.sections.map((section, index) => (
          <li key={section.anchorId}>
            <a
              href={`#${section.anchorId}`}
              onClick={(event) => {
                event.preventDefault();
                document.getElementById(section.anchorId)?.scrollIntoView({ behavior: "smooth", block: "start" });
              }}
            >
              <span>{String(index + 1).padStart(2, "0")}</span>{section.label}
            </a>
          </li>
        ))}</ol>
      </nav>
      <div className="research-document-body">{content}</div>
    </div>
  );
  if (!localCitation) return documentView;
  return <div className="research-document-with-evidence">{documentView}<ResearchCitationPanel selection={localCitation} onClose={() => setLocalCitation(null)} /></div>;
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
          <ActivityFeed activities={activities} emptyText="当前运行尚无 Model、Tool、Skill 或 MCP 活动。" />
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

function ActivityFeed({ activities, emptyText }: { activities: Activity[]; emptyText: string }) {
  return <div className="activity-list">
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
    {!activities.length && <p className="muted">{emptyText}</p>}
  </div>;
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
const missionAnimatedStates = new Set(["PLANNING", "RUNNING", "SYNTHESIZING"]);
const genericMissionObjective = /^(?:(?:开始|启动|发起)(?:一轮)?(?:深度)?(?:研究|调研)|(?:开始|启动|发起)\s*deep\s*research)\s*(?:任务|mission)?[。！!]?$/i;

function normalizeMissionTitle(value: string): string {
  return value
    .trim()
    .replace(/^(?:请)?(?:调用|使用)\s*(?:deep-research|深度研究)\s*skill\s*(?:来|进行|做)?\s*[：:]?\s*/i, "")
    .replace(/^请(?:进行|做)?\s*深度研究\s*[：:]?\s*/i, "")
    .replace(/\s+/g, " ")
    .trim();
}

function missionDisplayTitle(
  mission: MissionSnapshot,
  conversation: Conversation | null,
  conversationTurns: Turn[] = [],
): string {
  const objective = mission.objective.trim();
  if (!genericMissionObjective.test(objective) || mission.conversationId !== conversation?.id) return objective;
  const createdAt = new Date(mission.createdAt).getTime();
  const userGoal = [...conversationTurns]
    .filter((turn) => turn.role.toLowerCase() === "user" && new Date(turn.createdAt).getTime() <= createdAt)
    .sort((left, right) => new Date(right.createdAt).getTime() - new Date(left.createdAt).getTime())
    .map((turn) => normalizeMissionTitle(turn.text))
    .find((candidate) => candidate && !genericMissionObjective.test(candidate));
  const fallback = normalizeMissionTitle(conversation.displayName);
  return userGoal || (fallback && !genericMissionObjective.test(fallback) ? fallback : objective);
}

function missionStateLabel(state: string): string {
  return {
    PLANNING: "正在生成计划",
    WAITING_CONFIRMATION: "等待确认",
    RUNNING: "执行中",
    WAITING_USER: "等待你的回复",
    SYNTHESIZING: "正在生成报告",
    COMPLETED: "已完成",
    PARTIALLY_COMPLETED: "部分完成",
    FAILED: "失败",
    CANCELLED: "已取消",
  }[state] ?? state;
}

function missionStateAccessibleLabel(mission: MissionSnapshot): string {
  const label = missionStateLabel(mission.state);
  const progress = mission.tasks.length
    ? `，任务进度 ${mission.execution.completedTasks}/${mission.tasks.length}`
    : "";
  const currentTask = mission.tasks.find((task) => task.taskId === mission.execution.currentTaskId);
  const phase = mission.state === "SYNTHESIZING"
    ? "，正在综合结论并生成报告"
    : currentTask ? `，当前任务 ${currentTask.title}` : "";
  return `${label}${progress}${phase}`;
}

function MissionStateBadge({ mission, detailed = false, live = false }: {
  mission: MissionSnapshot;
  detailed?: boolean;
  live?: boolean;
}) {
  const active = missionAnimatedStates.has(mission.state);
  const progress = detailed && mission.tasks.length
    ? ` · ${mission.execution.completedTasks}/${mission.tasks.length}`
    : "";
  return <span
    className={`mission-state state-${mission.state.toLowerCase()}${active ? " is-active" : ""}`}
    role={live ? "status" : undefined}
    aria-live={live ? "polite" : undefined}
    aria-label={missionStateAccessibleLabel(mission)}
  >
    {active && <LoaderCircle className="mission-state-spinner" size={13} aria-hidden="true" />}
    <span>{missionStateLabel(mission.state)}{progress}</span>
  </span>;
}

function missionExecutionActivity(mission: MissionSnapshot, currentTask?: MissionSnapshot["tasks"][number]): string {
  if (mission.execution.recovering) return "正在恢复 Mission 执行状态";
  if (mission.state === "PLANNING") return "正在生成并校验执行计划";
  if (mission.state === "SYNTHESIZING") return "研究任务已完成，正在综合结论并生成报告";
  if (mission.state === "RUNNING" && currentTask) return `正在执行：${currentTask.title}`;
  if (mission.state === "RUNNING") return "正在准备下一项任务";
  return "";
}

function missionTaskStateLabel(state: string): string {
  return {
    PLANNED: "已规划",
    WAITING_DEPENDENCY: "等待前置任务",
    READY: "等待执行",
    COMPLETED: "已完成",
    BLOCKED: "需要处理",
    CANCELLED: "已取消",
  }[state] ?? "状态待确认";
}

function missionModeLabel(mode: MissionSnapshot["mode"]): string {
  return mode === "DEEP_RESEARCH" ? "深度调研" : "标准任务";
}

function missionSourceFallbackTitle(source: string): string {
  try {
    return `网页来源 · ${new URL(source).hostname.replace(/^www\./, "")}`;
  } catch {
    return "网页来源";
  }
}

function missionFailureMessage(mission: MissionSnapshot): string {
  if (mission.blocker === "MISSION_PLAN_DEPENDENCY_DEPTH_EXCEEDED") {
    return "Mission 规划失败：任务依赖层级超过限制。";
  }
  if (mission.blocker === "MISSION_LIMIT_EXCEEDED" && mission.tasks.length === 0) {
    return "Mission 规划失败：任务数量或依赖层级超过限制。";
  }
  if (mission.blocker === "MISSION_LIMIT_EXCEEDED") {
    return "Mission 执行失败：已达到资源、调用次数或时间限制。";
  }
  return "Mission 执行失败，请查看技术详情。";
}

type MissionArtifactReference = {
  artifactId: string;
  title?: string;
  mediaType?: string;
};

type ParsedMissionFinalResult = {
  schemaVersion?: string;
  unsupportedVersion?: boolean;
  directAnswer?: string;
  completionKind?: string;
  degraded?: boolean;
  degradationReasons?: string[];
  affectedTaskIds?: string[];
  reportArtifactRef?: MissionArtifactReference;
  sourcesArtifactRef?: MissionArtifactReference;
  claimEvidenceArtifactRef?: MissionArtifactReference;
  unresolvedArtifactRef?: MissionArtifactReference;
  sourceCount?: number;
  unverifiedClaimCount?: number;
  unresolvedQuestionCount?: number;
  evidenceSummary?: {
    totalClaimCount: number;
    unverifiedClaimCount: number;
    singleSourceClaimCount: number;
    counterevidenceClaimCount: number;
    unresolvedQuestionCount: number;
  };
  efficiencyMetrics?: {
    tokensPerValidSource: number;
    duplicateSearchFetchRatio: number;
    evidencePerMaterialClaim: number;
    singleSourceClaimRatio: number;
    synthesisTokenRatio: number;
    qualityGateRevisionCount: number;
  };
  qualityGate?: { passed?: boolean; failedChecks?: string[] };
  completedItems?: string[];
  failedItems?: string[];
  sourceRefs?: string[];
  unverifiedClaims?: string[];
  residualRisks?: string[];
  unresolvedQuestions?: string[];
};

function parseArtifactReference(value: unknown): MissionArtifactReference | undefined {
  if (typeof value !== "object" || value === null) return undefined;
  const record = value as Record<string, unknown>;
  if (typeof record.artifactId !== "string") return undefined;
  return {
    artifactId: record.artifactId,
    title: typeof record.title === "string" ? record.title : undefined,
    mediaType: typeof record.mediaType === "string" ? record.mediaType : undefined,
  };
}

function parseMissionFinalResult(value: string | null): ParsedMissionFinalResult | null {
  if (!value) return null;
  try {
    const parsed = JSON.parse(value) as Record<string, unknown>;
    const strings = (field: string): string[] => Array.isArray(parsed[field])
      ? parsed[field].filter((item): item is string => typeof item === "string")
      : [];
    const schemaVersion = typeof parsed.schemaVersion === "string" ? parsed.schemaVersion : undefined;
    if (schemaVersion && ![
      "pa.mission-final-result/v1",
      "pa.research-delivery/v2",
    ].includes(schemaVersion)) {
      return { schemaVersion, unsupportedVersion: true };
    }
    const numericRecord = (field: string): Record<string, number> | undefined => {
      const candidate = parsed[field];
      if (typeof candidate !== "object" || candidate === null) return undefined;
      const entries = Object.entries(candidate).filter((entry): entry is [string, number] => typeof entry[1] === "number");
      return Object.fromEntries(entries);
    };
    const evidence = numericRecord("evidenceSummary");
    const efficiency = numericRecord("efficiencyMetrics");
    return {
      schemaVersion,
      directAnswer: typeof parsed.directAnswer === "string" ? parsed.directAnswer : undefined,
      completionKind: typeof parsed.completionKind === "string" ? parsed.completionKind : undefined,
      degraded: typeof parsed.degraded === "boolean" ? parsed.degraded : undefined,
      degradationReasons: strings("degradationReasons"),
      affectedTaskIds: strings("affectedTaskIds"),
      reportArtifactRef: parseArtifactReference(parsed.reportArtifactRef),
      sourcesArtifactRef: parseArtifactReference(parsed.sourcesArtifactRef),
      claimEvidenceArtifactRef: parseArtifactReference(parsed.claimEvidenceArtifactRef),
      unresolvedArtifactRef: parseArtifactReference(parsed.unresolvedArtifactRef),
      sourceCount: typeof parsed.sourceCount === "number" ? parsed.sourceCount : undefined,
      unverifiedClaimCount: typeof parsed.unverifiedClaimCount === "number" ? parsed.unverifiedClaimCount : undefined,
      unresolvedQuestionCount: typeof parsed.unresolvedQuestionCount === "number" ? parsed.unresolvedQuestionCount : undefined,
      evidenceSummary: evidence ? {
        totalClaimCount: evidence.totalClaimCount ?? 0,
        unverifiedClaimCount: evidence.unverifiedClaimCount ?? 0,
        singleSourceClaimCount: evidence.singleSourceClaimCount ?? 0,
        counterevidenceClaimCount: evidence.counterevidenceClaimCount ?? 0,
        unresolvedQuestionCount: evidence.unresolvedQuestionCount ?? 0,
      } : undefined,
      efficiencyMetrics: efficiency ? {
        tokensPerValidSource: efficiency.tokensPerValidSource ?? 0,
        duplicateSearchFetchRatio: efficiency.duplicateSearchFetchRatio ?? 0,
        evidencePerMaterialClaim: efficiency.evidencePerMaterialClaim ?? 0,
        singleSourceClaimRatio: efficiency.singleSourceClaimRatio ?? 0,
        synthesisTokenRatio: efficiency.synthesisTokenRatio ?? 0,
        qualityGateRevisionCount: efficiency.qualityGateRevisionCount ?? 0,
      } : undefined,
      qualityGate: typeof parsed.qualityGate === "object" && parsed.qualityGate !== null
        ? {
            passed: typeof (parsed.qualityGate as Record<string, unknown>).passed === "boolean"
              ? (parsed.qualityGate as Record<string, unknown>).passed as boolean
              : undefined,
            failedChecks: Array.isArray((parsed.qualityGate as Record<string, unknown>).failedChecks)
              ? ((parsed.qualityGate as Record<string, unknown>).failedChecks as unknown[])
                  .filter((item): item is string => typeof item === "string")
              : [],
          }
        : undefined,
      completedItems: strings("completedItems"),
      failedItems: strings("failedItems"),
      sourceRefs: strings("sourceRefs"),
      unverifiedClaims: strings("unverifiedClaims"),
      residualRisks: strings("residualRisks"),
      unresolvedQuestions: strings("unresolvedQuestions"),
    };
  } catch {
    return { directAnswer: value };
  }
}

const missionDeliveryMarker = /^<!--\s*haifa-mission-delivery:\s*([a-zA-Z0-9._:-]+)\s*-->/;

function missionDeliveryId(text: string): string | null {
  return missionDeliveryMarker.exec(text.trimStart())?.[1] ?? null;
}

function MissionDeliveryCard({
  mission,
  title,
  onOpenReport,
  onOpenEvidence,
  onContinue,
}: {
  mission: MissionSnapshot;
  title: string;
  onOpenReport(): void;
  onOpenEvidence(): void;
  onContinue(): void;
}) {
  const result = parseMissionFinalResult(mission.finalResult);
  if (!result || result.schemaVersion !== "pa.research-delivery/v2") return null;
  const evidence = result.evidenceSummary;
  const status = result.degraded
    ? "降级完成"
    : result.completionKind === "PARTIAL" ? "部分完成" : "已完成";
  return <section className="mission-delivery-card" aria-label="Deep Research Mission 交付">
    <header><div><span className="mission-delivery-status"><CheckCircle2 size={15} />{status}</span><h3>{title}</h3></div><span>Deep Research</span></header>
    <p>调研报告与完整证据链已生成。普通对话保留摘要，全文与技术交付文件在 Mission 中查看。</p>
    <div className="mission-delivery-metrics" aria-label="证据汇总">
      <span><b>{mission.tasks.filter((task) => task.state === "COMPLETED").length}</b>任务</span>
      <span><b>{evidence?.totalClaimCount ?? 0}</b>结论</span>
      <span><b>{result.sourceCount ?? 0}</b>来源</span>
      <span><b>{evidence?.unresolvedQuestionCount ?? result.unresolvedQuestionCount ?? 0}</b>未决</span>
    </div>
    {(evidence?.unverifiedClaimCount ?? result.unverifiedClaimCount ?? 0) > 0 && <p className="mission-delivery-warning"><CircleAlert size={15} />本报告包含尚未充分核实的判断，不应解读为所有关键结论均已确认。</p>}
    <div className="mission-delivery-actions">
      <button type="button" className="button primary" onClick={onOpenReport}>查看完整报告</button>
      <button type="button" className="button" onClick={onOpenEvidence}>证据与来源</button>
      <button type="button" className="button" onClick={onContinue}>继续追问</button>
    </div>
  </section>;
}

function parseResearchSourcesArtifact(value: string): MarkdownResearchSource[] {
  try {
    const parsed = JSON.parse(value) as Record<string, unknown>;
    if (parsed.schemaVersion !== "pa.research-sources/v1" || !Array.isArray(parsed.sources)) return [];
    return parsed.sources.flatMap((candidate) => {
      if (typeof candidate !== "object" || candidate === null) return [];
      const source = candidate as Record<string, unknown>;
      if (typeof source.sourceId !== "string"
        || typeof source.title !== "string"
        || typeof source.locator !== "string") return [];
      return [{
        sourceId: source.sourceId,
        title: source.title,
        locator: source.locator,
        normalizedLocator: typeof source.normalizedLocator === "string"
          ? source.normalizedLocator
          : undefined,
        publisher: typeof source.publisher === "string" ? source.publisher : undefined,
        publishedAt: typeof source.publishedAt === "string" ? source.publishedAt : null,
        fetchedAt: typeof source.fetchedAt === "string" ? source.fetchedAt : null,
        status: typeof source.status === "string" ? source.status : undefined,
      }];
    });
  } catch {
    return [];
  }
}

type MissionArtifactItem = {
  artifactId: string;
  title: string;
  fileName: string;
  mediaType: string;
};

function artifactDisplayName(fileName: string): string {
  return {
    "research-report.md": "完整研究报告",
    "sources.json": "来源清单",
    "claim-evidence.json": "结论与证据关系",
    "unresolved-questions.json": "未决问题",
    "research-delivery.json": "交付清单",
  }[fileName] ?? fileName.replace(/[-_]+/g, " ").replace(/\.[^.]+$/, "");
}

function missionArtifactItems(mission: MissionSnapshot): MissionArtifactItem[] {
  const result = parseMissionFinalResult(mission.finalResult);
  const references = [
    result?.reportArtifactRef,
    result?.sourcesArtifactRef,
    result?.claimEvidenceArtifactRef,
    result?.unresolvedArtifactRef,
  ].filter((reference): reference is MissionArtifactReference => Boolean(reference));
  const byId = new Map(references.map((reference) => [reference.artifactId, reference]));
  return mission.artifacts.map((artifactId, index) => {
    const reference = byId.get(artifactId);
    const fallbackManifest = result?.schemaVersion === "pa.research-delivery/v2" && index === mission.artifacts.length - 1;
    const fileName = reference?.title || (fallbackManifest ? "research-delivery.json" : `交付文件-${index + 1}`);
    const mediaType = reference?.mediaType || (fileName.endsWith(".md") ? "text/markdown" : "application/json");
    return {
      artifactId,
      title: artifactDisplayName(fileName),
      fileName,
      mediaType,
    };
  });
}

function ArtifactJsonDocument({ text }: { text: string }) {
  try {
    const value = JSON.parse(text) as Record<string, unknown>;
    const schemaVersion = typeof value.schemaVersion === "string" ? value.schemaVersion : "";
    if (schemaVersion === "pa.research-sources/v1") {
      const sources = parseResearchSourcesArtifact(text);
      return <div className="artifact-readable-list">{sources.map((source, index) => {
        const tier = researchSourceTier(source);
        const locator = safeResearchLocator(source);
        return <article key={source.sourceId}><div><span>来源 {String(index + 1).padStart(2, "0")}</span><span className={`research-source-tier tier-${tier.key}`}>{tier.label}</span></div><h4>{source.title}</h4><p>{source.publisher || researchSourceSite(source)} · {researchSourceDate(source)} · {researchSourceStatus(source.status)}</p>{locator && <a href={locator} target="_blank" rel="noopener noreferrer">打开“{source.title}”</a>}</article>;
      })}</div>;
    }
    if (schemaVersion === "pa.unresolved-questions/v1") {
      const questions = Array.isArray(value.unresolvedQuestions)
        ? value.unresolvedQuestions.filter((item): item is string => typeof item === "string")
        : [];
      return questions.length > 0
        ? <ol className="artifact-question-list">{questions.map((question) => <li key={question}>{question}</li>)}</ol>
        : <p className="mission-task-empty">没有未决问题。</p>;
    }
    if (schemaVersion === "pa.claim-evidence/v1") {
      const claims = Array.isArray(value.claims)
        ? value.claims.filter((item): item is Record<string, unknown> => typeof item === "object" && item !== null)
        : [];
      return <div className="artifact-readable-list">{claims.map((claim, index) => <article key={index}><div><span>结论 {String(index + 1).padStart(2, "0")}</span>{claim.unverified === true && <span className="research-unverified">待核实</span>}</div><h4>{typeof claim.claim === "string" ? claim.claim : "未命名结论"}</h4>{typeof claim.limitations === "string" && claim.limitations && <p>限制：{claim.limitations}</p>}<p>支持来源 {Array.isArray(claim.supportingSourceIds) ? claim.supportingSourceIds.length : 0} 个 · 反向来源 {Array.isArray(claim.opposingSourceIds) ? claim.opposingSourceIds.length : 0} 个</p></article>)}</div>;
    }
    if (schemaVersion === "pa.research-delivery/v2") {
      return <div className="artifact-delivery-summary"><p><b>交付状态：</b>{value.completionKind === "COMPLETE" ? "完整完成" : "部分完成"}</p><p><b>来源：</b>{typeof value.sourceCount === "number" ? `${value.sourceCount} 个` : "未提供"}</p><p><b>待核实结论：</b>{typeof value.unverifiedClaimCount === "number" ? `${value.unverifiedClaimCount} 个` : "未提供"}</p><p><b>未决问题：</b>{typeof value.unresolvedQuestionCount === "number" ? `${value.unresolvedQuestionCount} 个` : "未提供"}</p></div>;
    }
    return <details className="artifact-technical-data"><summary>查看结构化内容</summary><pre>{JSON.stringify(value, null, 2)}</pre></details>;
  } catch {
    return <pre className="artifact-plain-text">{text}</pre>;
  }
}

function MissionArtifactReader({
  client,
  mission,
  artifact,
}: {
  client: PersonalAssistantClient;
  mission: MissionSnapshot;
  artifact: MissionArtifactItem;
}) {
  const [content, setContent] = useState<
    { state: "loading" } | { state: "failed" } | { state: "ready"; text: string; research?: MarkdownResearchContext }
  >({ state: "loading" });
  useEffect(() => {
    if (!client.missionArtifact) {
      setContent({ state: "failed" });
      return;
    }
    let cancelled = false;
    setContent({ state: "loading" });
    const result = parseMissionFinalResult(mission.finalResult);
    const sourcesId = result?.sourcesArtifactRef?.artifactId;
    const sourcesRequest = sourcesId && sourcesId !== artifact.artifactId
      ? client.missionArtifact(mission.missionId, sourcesId)
      : Promise.resolve<string | null>(null);
    void Promise.allSettled([
      client.missionArtifact(mission.missionId, artifact.artifactId),
      sourcesRequest,
    ]).then(([artifactOutcome, sourcesOutcome]) => {
      if (cancelled) return;
      if (artifactOutcome.status === "rejected") {
        setContent({ state: "failed" });
        return;
      }
      const sources = sourcesOutcome.status === "fulfilled" && sourcesOutcome.value
        ? parseResearchSourcesArtifact(sourcesOutcome.value)
        : [];
      setContent({
        state: "ready",
        text: artifactOutcome.value,
        research: artifact.mediaType.startsWith("text/markdown") ? {
          anchorPrefix: `mission-artifact-${mission.missionId}-${artifact.artifactId}`,
          tasks: mission.tasks.map((task) => ({ ordinal: task.ordinal, taskId: task.taskId, title: task.title })),
          sources,
          sourceState: sourcesId ? (sourcesOutcome.status === "fulfilled" ? "ready" : "failed") : "failed",
        } : undefined,
      });
    });
    return () => { cancelled = true; };
  }, [artifact, client, mission]);
  if (content.state === "loading") return <div className="mission-embedded-report-status" role="status">正在加载交付文件…</div>;
  if (content.state === "failed") return <div className="mission-embedded-report-status failed" role="alert">交付文件加载失败，请稍后重试。</div>;
  if (artifact.mediaType.startsWith("text/markdown") || artifact.fileName.endsWith(".md")) {
    return <div className="artifact-markdown-reader"><MessageContent text={content.text} research={content.research} /></div>;
  }
  return <ArtifactJsonDocument text={content.text} />;
}

const degradationLabels: Record<string, string> = {
  REPORT_EMPTY: "综合没有返回正文",
  REPORT_TOO_LARGE: "报告超过安全大小限制",
  REPORT_REQUIRED_SECTION_MISSING: "报告缺少必要章节",
  REPORT_SECTION_EMPTY: "报告存在空章节",
  REPORT_TASK_COVERAGE_MISSING: "部分研究任务未被报告覆盖",
  REPORT_SOURCES_MISSING: "报告缺少可解析来源引用",
  REPORT_CITATION_INVALID: "报告包含无法闭合的来源引用",
  REPORT_ONLY_METADATA: "报告内容不足，仅包含执行元数据",
  REPORT_EVIDENCE_SUMMARY_INVALID: "证据汇总与可信计数不一致",
  REPORT_UNVERIFIED_WARNING_MISSING: "报告缺少待核实结论警告",
  REPORT_UNRESOLVED_COVERAGE_MISSING: "报告未覆盖全部未决问题",
  REPORT_SINGLE_SOURCE_RISK_MISSING: "报告未披露单一来源结论风险",
  REPORT_SYNTHESIS_DEGRADED: "模型综合过程发生降级",
};

function MissionFinalResult({
  client,
  mission,
  onTaskSelect,
  onCitationSelect,
  onCreateFollowUp,
}: {
  client: PersonalAssistantClient;
  mission: MissionSnapshot;
  onTaskSelect(taskId: string): void;
  onCitationSelect(selection: ResearchCitationSelection): void;
  onCreateFollowUp(): void;
}) {
  const result = parseMissionFinalResult(mission.finalResult);
  const [copyState, setCopyState] = useState<"idle" | "copying" | "copied" | "failed">("idle");
  const [downloadState, setDownloadState] = useState<"idle" | "downloading" | "downloaded" | "failed">("idle");
  const [embeddedReport, setEmbeddedReport] = useState<
    | { status: "idle" | "loading" }
    | { status: "failed" }
    | { status: "ready"; text: string; research: MarkdownResearchContext }
  >({ status: "idle" });
  const reportId = result?.reportArtifactRef?.artifactId;
  const sourcesId = result?.sourcesArtifactRef?.artifactId;
  const reportSectionId = `mission-report-${mission.missionId}`;

  useEffect(() => {
    if (result?.schemaVersion !== "pa.research-delivery/v2" || !reportId || !client.missionArtifact) {
      setEmbeddedReport({ status: "idle" });
      return;
    }
    let cancelled = false;
    setEmbeddedReport({ status: "loading" });
    const reportRequest = client.missionArtifact(mission.missionId, reportId);
    const sourcesRequest = sourcesId
      ? client.missionArtifact(mission.missionId, sourcesId)
      : Promise.resolve<string | null>(null);
    void Promise.allSettled([reportRequest, sourcesRequest]).then(([reportOutcome, sourcesOutcome]) => {
      if (cancelled) return;
      if (reportOutcome.status === "rejected") {
        setEmbeddedReport({ status: "failed" });
        return;
      }
      const sources = sourcesOutcome.status === "fulfilled" && sourcesOutcome.value
        ? parseResearchSourcesArtifact(sourcesOutcome.value)
        : [];
      setEmbeddedReport({
        status: "ready",
        text: reportOutcome.value,
        research: {
          anchorPrefix: `mission-workspace-${mission.missionId}`,
          tasks: mission.tasks.map((task) => ({
            ordinal: task.ordinal,
            taskId: task.taskId,
            title: task.title,
          })),
          sources,
          sourceState: sourcesId && sourcesOutcome.status === "fulfilled" ? "ready" : "failed",
        },
      });
    });
    return () => {
      cancelled = true;
    };
  }, [client, mission.missionId, mission.tasks, reportId, result?.schemaVersion, sourcesId]);

  if (!result) return null;
  if (result.unsupportedVersion) {
    return <section className="research-result" role="alert"><h4>最终报告版本不受支持</h4><p>为避免错误解释交付状态，当前客户端不会推断未知版本。请升级客户端后重试。</p><details><summary>技术详情</summary><code>{result.schemaVersion}</code></details></section>;
  }
  const v2 = result.schemaVersion === "pa.research-delivery/v2";
  const status = result.degraded
    ? "调研降级完成"
    : result.completionKind === "PARTIAL"
      ? "调研部分完成"
      : "调研已完成";
  const copyReport = async () => {
    if (!reportId || !client.missionArtifact) return;
    setCopyState("copying");
    try {
      await navigator.clipboard.writeText(await client.missionArtifact(mission.missionId, reportId));
      setCopyState("copied");
    } catch {
      setCopyState("failed");
    }
  };
  const downloadReport = async (event: MouseEvent<HTMLAnchorElement>) => {
    event.preventDefault();
    if (!reportId || !client.missionArtifact) return;
    setDownloadState("downloading");
    try {
      const report = await client.missionArtifact(mission.missionId, reportId);
      const objectUrl = URL.createObjectURL(new Blob([report], {
        type: "text/markdown;charset=utf-8",
      }));
      const anchor = document.createElement("a");
      anchor.href = objectUrl;
      anchor.download = result.reportArtifactRef?.title ?? "research-report.md";
      anchor.hidden = true;
      document.body.append(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(objectUrl);
      setDownloadState("downloaded");
    } catch {
      setDownloadState("failed");
    }
  };
  if (v2) {
    const visibleSourceCount = result.sourceCount
      ?? (embeddedReport.status === "ready" ? embeddedReport.research.sources.length : 0);
    const evidence = result.evidenceSummary;
    return <section className="research-result" aria-label="Deep Research 最终交付">
      <div className="research-result-heading"><div><span className="eyebrow">FINAL DELIVERY</span><h4>{status}</h4></div><div className="research-result-metrics"><span>{visibleSourceCount} 个来源</span><span>{evidence?.totalClaimCount ?? 0} 个主要结论</span><span>{evidence?.unverifiedClaimCount ?? result.unverifiedClaimCount ?? 0} 个待核实</span><span>{evidence?.singleSourceClaimCount ?? 0} 个单一来源</span><span>{evidence?.counterevidenceClaimCount ?? 0} 个有反向证据</span><span>{evidence?.unresolvedQuestionCount ?? result.unresolvedQuestionCount ?? 0} 个未决问题</span></div></div>
      {result.degraded && <p className="warning-banner">最终综合未完全达到质量门禁，已保留可读报告和已收集证据。</p>}
      {(evidence?.unverifiedClaimCount ?? result.unverifiedClaimCount ?? 0) > 0 && <p className="warning-banner">本报告包含尚未充分核实的判断，不应解读为所有关键结论均已确认。</p>}
      {(result.degradationReasons?.length ?? 0) > 0 && <><h5>降级原因</h5><ul>{result.degradationReasons!.map((reason) => <li key={reason}>{degradationLabels[reason] ?? "综合质量检查未通过"}<details><summary>技术详情</summary><code>{reason}</code></details></li>)}</ul></>}
      {(result.affectedTaskIds?.length ?? 0) > 0 && <p><b>受影响任务：</b>{result.affectedTaskIds!.join("、")}</p>}
      {result.efficiencyMetrics && <details className="research-efficiency"><summary>质量与成本指标</summary><dl><div><dt>每个有效来源 Token</dt><dd>{result.efficiencyMetrics.tokensPerValidSource}</dd></div><div><dt>重复 Search/Fetch 比率</dt><dd>{number.format(result.efficiencyMetrics.duplicateSearchFetchRatio * 100)}%</dd></div><div><dt>每个结论证据数</dt><dd>{result.efficiencyMetrics.evidencePerMaterialClaim}</dd></div><div><dt>单一来源结论比率</dt><dd>{number.format(result.efficiencyMetrics.singleSourceClaimRatio * 100)}%</dd></div><div><dt>Synthesis Token 比率</dt><dd>{number.format(result.efficiencyMetrics.synthesisTokenRatio * 100)}%</dd></div><div><dt>质量门禁修订次数</dt><dd>{result.efficiencyMetrics.qualityGateRevisionCount}</dd></div></dl></details>}
      {reportId && <div className="research-report-actions">
        <button className="button" type="button" onClick={() => document.getElementById(reportSectionId)?.scrollIntoView({ behavior: "smooth", block: "start" })}>阅读全文</button>
        <a className="button" href={missionArtifactUrl(mission.missionId, reportId)} download={result.reportArtifactRef?.title ?? "research-report.md"} aria-disabled={downloadState === "downloading" || !client.missionArtifact} onClick={(event) => void downloadReport(event)}>{downloadState === "downloading" ? "下载中…" : downloadState === "downloaded" ? "已下载" : downloadState === "failed" ? "下载失败" : "下载 Markdown"}</a>
        <button type="button" className="button" disabled={copyState === "copying" || !client.missionArtifact} onClick={() => void copyReport()}><Copy size={14} />{copyState === "copied" ? "已复制" : copyState === "failed" ? "复制失败" : "复制完整报告"}</button>
      </div>}
      {embeddedReport.status === "loading" && <div className="mission-embedded-report-status" role="status">正在加载完整研究报告…</div>}
      {embeddedReport.status === "failed" && <div className="mission-embedded-report-status failed" role="alert">完整研究报告加载失败，可使用上方“查看完整报告”打开原始 Markdown。</div>}
      {embeddedReport.status === "ready" && <section id={reportSectionId} className="mission-embedded-report" aria-label="完整研究报告">
        <header><div><span className="eyebrow">RESEARCH REPORT</span><h5>完整研究报告</h5></div><span>文档阅读模式</span></header>
        <MessageContent
          text={embeddedReport.text}
          research={embeddedReport.research}
          onResearchCitationSelect={onCitationSelect}
          onResearchTaskSelect={(ordinal) => {
            const task = mission.tasks.find((candidate) => candidate.ordinal === ordinal);
            if (task) onTaskSelect(task.taskId);
          }}
        />
      </section>}
      {embeddedReport.status === "ready" && embeddedReport.research.sources.length > 0 && <section className="research-sources" aria-label="来源与引用">
        <div className="research-source-list-heading"><div><span className="eyebrow">SOURCES</span><h5>来源与引用</h5></div><span>{embeddedReport.research.sources.length} 个网页来源</span></div>
        <ol>{embeddedReport.research.sources.map((source, index) => {
          const locator = safeResearchLocator(source);
          const tier = researchSourceTier(source);
          return <li key={source.sourceId}><span>{String(index + 1).padStart(2, "0")}</span><div><strong>{locator ? <a href={locator} target="_blank" rel="noopener noreferrer">{source.title}</a> : source.title}</strong><small>{source.publisher || researchSourceSite(source)} · {researchSourceDate(source)} · {researchSourceStatus(source.status)}</small></div><span className={`research-source-tier tier-${tier.key}`}>{tier.label}</span></li>;
        })}</ol>
      </section>}
      {((result.unresolvedQuestionCount ?? 0) > 0 || (result.unverifiedClaimCount ?? 0) > 0 || result.degraded) && <div className="research-follow-up"><div><b>还有需要继续核实的内容</b><span>创建新的 Mission，并继承本次研究主题与范围。</span></div><button type="button" className="button" onClick={onCreateFollowUp}>继续研究未决问题</button></div>}
      {result.degraded && <p>下一步：查看已完成内容；如需重新调研，请按现有产品能力重新创建 Mission。</p>}
    </section>;
  }
  const resultTitle = result.schemaVersion === "pa.mission-final-result/v1"
    ? "Mission 最终报告"
    : "历史最终报告";
  const completionLabel = result.completionKind === "COMPLETE"
    ? "已完成"
    : result.completionKind === "PARTIAL"
      ? "部分完成"
      : result.completionKind;
  return <section className="research-result"><h4>{resultTitle}{completionLabel && ` · ${completionLabel}`}</h4>{result.directAnswer && <p className="research-answer">{result.directAnswer}</p>}{(result.completedItems?.length ?? 0) > 0 && <><h5>完成项</h5><ul>{result.completedItems!.map((item) => <li key={item}>{item}</li>)}</ul></>}{(result.failedItems?.length ?? 0) > 0 && <><h5>未完成项</h5><ul>{result.failedItems!.map((item) => <li key={item}>{item}</li>)}</ul></>}{(result.sourceRefs?.length ?? 0) > 0 && <><h5>参考来源</h5><ul>{result.sourceRefs!.map((item) => <li key={item}>{item}</li>)}</ul></>}{(result.unverifiedClaims?.length ?? 0) > 0 && <><h5>未验证结论</h5><ul>{result.unverifiedClaims!.map((item) => <li key={item}>{item}</li>)}</ul></>}{(result.residualRisks?.length ?? 0) > 0 && <><h5>剩余风险</h5><ul>{result.residualRisks!.map((item) => <li key={item}>{item}</li>)}</ul></>}{(result.unresolvedQuestions?.length ?? 0) > 0 && <><h5>未决问题</h5><ul>{result.unresolvedQuestions!.map((item) => <li key={item}>{item}</li>)}</ul></>}</section>;
}

type MissionPlanTask = MissionSnapshot["tasks"][number];

function copyMissionPlanTasks(tasks: MissionPlanTask[]): MissionPlanTask[] {
  return tasks.map((task) => ({
    ...task,
    acceptanceCriteria: [...task.acceptanceCriteria],
    dependsOn: [...task.dependsOn],
    requiredSkillIds: [...task.requiredSkillIds],
  }));
}

function missionPlanDependencyDepth(tasks: MissionPlanTask[]): number {
  const byId = new Map(tasks.map((task) => [task.taskId, task]));
  const memo = new Map<string, number>();
  const visiting = new Set<string>();
  const depth = (taskId: string): number => {
    const known = memo.get(taskId);
    if (known !== undefined) return known;
    if (visiting.has(taskId)) return Number.POSITIVE_INFINITY;
    visiting.add(taskId);
    const task = byId.get(taskId);
    const value = task?.dependsOn.length
      ? 1 + Math.max(...task.dependsOn.map((dependency) => byId.has(dependency) ? depth(dependency) : Number.POSITIVE_INFINITY))
      : 1;
    visiting.delete(taskId);
    memo.set(taskId, value);
    return value;
  };
  return tasks.length ? Math.max(...tasks.map((task) => depth(task.taskId))) : 0;
}

function normalizeMissionPlanTasks(tasks: MissionPlanTask[]): MissionPlanTask[] {
  return tasks.map((task, index) => ({
    ...task,
    ordinal: index + 1,
    title: task.title.trim(),
    objective: task.objective.trim(),
    acceptanceCriteria: task.acceptanceCriteria.map((criterion) => criterion.trim()).filter(Boolean),
    state: "PLANNED",
  }));
}

function MissionDialog({
  client,
  conversation,
  conversationTurns,
  initialMissionId,
  initialTaskId,
  initialArtifactFileName,
  initialDraft,
  webResearchAvailable,
  onDraftCreated,
  onClose,
  onChanged,
  onSelected,
}: {
  client: PersonalAssistantClient;
  conversation: Conversation | null;
  conversationTurns: Turn[];
  initialMissionId: string | null;
  initialTaskId: string | null;
  initialArtifactFileName: string | null;
  initialDraft: MissionDraftRequest | null;
  webResearchAvailable: boolean;
  onDraftCreated(): void;
  onClose(): void;
  onChanged(mission: MissionSnapshot | null): void;
  onSelected(mission: MissionSnapshot): void;
}) {
  const [missions, setMissions] = useState<MissionSnapshot[]>([]);
  const [selected, setSelected] = useState<MissionSnapshot | null>(null);
  const [selectedTaskId, setSelectedTaskId] = useState<string | null>(null);
  const [selectedArtifact, setSelectedArtifact] = useState<MissionArtifactItem | null>(null);
  const [selectedCitation, setSelectedCitation] = useState<ResearchCitationSelection | null>(null);
  const [detailPanelOpen, setDetailPanelOpen] = useState(true);
  const [mobileView, setMobileView] = useState<"missions" | "content" | "detail">("content");
  const [missionQuery, setMissionQuery] = useState("");
  const [missionFilter, setMissionFilter] = useState<"ALL" | "ACTIVE" | "ACTION" | "COMPLETED" | "FAILED">("ALL");
  const [missionSort, setMissionSort] = useState<"UPDATED" | "PROGRESS">("UPDATED");
  const [creatingMission, setCreatingMission] = useState(false);
  const [objective, setObjective] = useState("");
  const [criteria, setCriteria] = useState("");
  const [mode, setMode] = useState<MissionMode>("STANDARD");
  const [researchQuestion, setResearchQuestion] = useState("");
  const [researchScope, setResearchScope] = useState("");
  const [researchTimeRange, setResearchTimeRange] = useState("");
  const [researchRegion, setResearchRegion] = useState("");
  const [researchAudience, setResearchAudience] = useState("");
  const [researchSources, setResearchSources] = useState("");
  const [researchExclusions, setResearchExclusions] = useState("");
  const [researchDelivery, setResearchDelivery] = useState("");
  const [creationSettingsOpen, setCreationSettingsOpen] = useState(false);
  const [editingPlan, setEditingPlan] = useState(false);
  const [planDraft, setPlanDraft] = useState<MissionPlanTask[]>([]);
  const [editingTaskId, setEditingTaskId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [missionInteraction, setMissionInteraction] = useState<Interaction | null>(null);
  const [missionInteractionText, setMissionInteractionText] = useState("");
  const [missionTaskRun, setMissionTaskRun] = useState<Run | null>(null);
  const [missionTaskActivities, setMissionTaskActivities] = useState<Activity[]>([]);
  const [missionTaskActivityStatus, setMissionTaskActivityStatus] = useState<"idle" | "loading" | "current" | "error">("idle");
  const [syncStatus, setSyncStatus] = useState<"loading" | "current" | "syncing" | "stale" | "recovering" | "offline">(
    navigator.onLine ? "loading" : "offline",
  );
  const [terminalAnnouncement, setTerminalAnnouncement] = useState("");
  const [reconnectEpoch, setReconnectEpoch] = useState(0);
  const pollFailures = useRef(0);
  const dialogRef = useRef<HTMLElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const maxPlanTasks = selected?.constraints.maxTasks ?? 8;
  const maxPlanDependencyDepth = selected?.constraints.maxDependencyDepth ?? 4;
  const generatedCriteria = defaultMissionAcceptanceCriteria(mode);
  const effectiveAcceptanceCriteria = criteria.trim()
    ? criteria.split("\n").map((value) => value.trim()).filter(Boolean)
    : generatedCriteria;
  const generatedResearch = defaultResearchBrief(objective);
  const effectiveResearchScope = researchScope.trim() || generatedResearch.scope;
  const effectiveResearchTimeRange = researchTimeRange.trim() || generatedResearch.timeRange;
  const effectiveResearchRegion = researchRegion.trim() || generatedResearch.region;
  const effectiveResearchAudience = researchAudience.trim() || generatedResearch.audience;
  const latestMissionActivityAttempt = selected?.execution.latestAttempt ?? null;
  const missionActivityAttempt = latestMissionActivityAttempt
    && (!selected?.execution.currentTaskId || latestMissionActivityAttempt.taskId === selected.execution.currentTaskId)
    ? latestMissionActivityAttempt
    : null;
  const missionActivityRunId = missionActivityAttempt?.runId ?? null;
  const missionActivityPolling = Boolean(selected && !missionTerminalStates.has(selected.state));

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
    onSelected(mission);
  }, [onChanged, onSelected]);

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
    const tasks = selected?.tasks ?? [];
    setSelectedTaskId((current) =>
      tasks.some((task) => task.taskId === initialTaskId)
        ? initialTaskId
        : tasks.some((task) => task.taskId === current)
          ? current
          : (tasks[0]?.taskId ?? null));
  }, [initialTaskId, selected]);

  useEffect(() => {
    if (!selected || !initialArtifactFileName) return;
    const artifact = missionArtifactItems(selected).find((candidate) => candidate.fileName === initialArtifactFileName);
    if (!artifact) return;
    setSelectedArtifact(artifact);
    setSelectedCitation(null);
    setDetailPanelOpen(true);
    setMobileView("detail");
  }, [initialArtifactFileName, selected]);

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
        const current = page.items.find((mission) => mission.missionId === initialMissionId)
          ?? (conversation
            ? page.items.find((mission) => mission.conversationId === conversation.id)
            : page.items[0]);
        const active = conversation
          ? page.items.find((mission) => mission.conversationId === conversation.id
            && !missionTerminalStates.has(mission.state))
          : undefined;
        if (initialDraft && conversation && !active) {
          setSelected(current ?? null);
          setMode("DEEP_RESEARCH");
          setObjective(initialDraft.objective);
          setCriteria("");
          setResearchQuestion(initialDraft.objective);
          setResearchScope("");
          setResearchTimeRange("");
          setResearchRegion("");
          setResearchAudience("");
          setResearchSources("");
          setResearchExclusions("");
          setResearchDelivery("");
          setCreationSettingsOpen(false);
          setCreatingMission(true);
          setDetailPanelOpen(false);
          setMobileView("content");
          setError(null);
          onChanged(current ?? null);
        } else {
          const selectedMission = active ?? current ?? null;
          setSelected(selectedMission);
          setCreatingMission(selectedMission == null);
          onChanged(selectedMission);
          if (selectedMission) onSelected(selectedMission);
          setError(initialDraft && active
            ? "当前会话已有进行中的 Mission。请打开当前 Mission，取消后重建，或换一个会话。"
            : null);
        }
        setSyncStatus(navigator.onLine ? "current" : "offline");
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
  }, [client, conversation, initialDraft, initialMissionId, onChanged, onSelected, reconnectEpoch, webResearchAvailable]);

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

  useEffect(() => {
    if (!missionActivityRunId) {
      setMissionTaskRun(null);
      setMissionTaskActivities([]);
      setMissionTaskActivityStatus("idle");
      return;
    }
    const controller = new AbortController();
    let timer: number | undefined;
    const refresh = async () => {
      setMissionTaskActivityStatus((current) => current === "idle" ? "loading" : current);
      try {
        const [run, activities] = await Promise.all([
          client.run(missionActivityRunId, controller.signal),
          client.activities(missionActivityRunId, controller.signal),
        ]);
        if (controller.signal.aborted) return;
        setMissionTaskRun(run);
        setMissionTaskActivities(activities);
        setMissionTaskActivityStatus("current");
        if (!isTerminal(run)) {
          timer = window.setTimeout(refresh, document.hidden ? 10_000 : 2_000);
        }
      } catch {
        if (controller.signal.aborted) return;
        setMissionTaskActivityStatus("error");
        if (missionActivityPolling) {
          timer = window.setTimeout(refresh, document.hidden ? 15_000 : 5_000);
        }
      }
    };
    setMissionTaskRun(null);
    setMissionTaskActivities([]);
    setMissionTaskActivityStatus("loading");
    void refresh();
    return () => {
      controller.abort();
      if (timer !== undefined) window.clearTimeout(timer);
    };
  }, [client, missionActivityPolling, missionActivityRunId]);

  const handleDialogKeyDown = (event: ReactKeyboardEvent<HTMLElement>) => {
    if (event.key === "Escape") {
      event.preventDefault();
      if (detailPanelOpen) {
        setDetailPanelOpen(false);
        setSelectedCitation(null);
        setSelectedArtifact(null);
        setMobileView("content");
        return;
      }
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

  const openTaskDetail = (taskId: string) => {
    setSelectedTaskId(taskId);
    setSelectedArtifact(null);
    setSelectedCitation(null);
    setDetailPanelOpen(true);
    setMobileView("detail");
  };

  const openArtifact = (artifact: MissionArtifactItem) => {
    setSelectedArtifact(artifact);
    setSelectedCitation(null);
    setDetailPanelOpen(true);
    setMobileView("detail");
  };

  const openCitation = (selection: ResearchCitationSelection) => {
    setSelectedCitation(selection);
    setSelectedArtifact(null);
    setDetailPanelOpen(true);
    setMobileView("detail");
  };

  const closeDetailPanel = () => {
    setDetailPanelOpen(false);
    setSelectedCitation(null);
    setSelectedArtifact(null);
    setMobileView("content");
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

  const command = async (operation: () => Promise<MissionSnapshot>): Promise<boolean> => {
    setBusy(true);
    setError(null);
    try {
      merge(await operation());
      return true;
    } catch (reason) {
      setError(safeError(reason));
      try {
        const reconciled = await client.missions?.(conversation?.id);
        const latest = reconciled?.items[0];
        if (latest) merge(latest);
      } catch {
        // Preserve the command failure; normal polling/reopen can reconcile later.
      }
      return false;
    } finally {
      setBusy(false);
    }
  };

  const selectMissionMode = (nextMode: MissionMode) => {
    if (nextMode === mode) return;
    setMode(nextMode);
    setCriteria("");
    setCreationSettingsOpen(false);
    setResearchQuestion("");
    setResearchScope("");
    setResearchTimeRange("");
    setResearchRegion("");
    setResearchAudience("");
    setResearchSources("");
    setResearchExclusions("");
    setResearchDelivery("");
  };

  const toggleCreationSettings = () => {
    if (!creationSettingsOpen) {
      if (!criteria.trim()) setCriteria(generatedCriteria.join("\n"));
      if (mode === "DEEP_RESEARCH") {
        if (!researchQuestion.trim()) setResearchQuestion(objective.trim());
        if (!researchScope.trim()) setResearchScope(generatedResearch.scope);
        if (!researchTimeRange.trim()) setResearchTimeRange(generatedResearch.timeRange);
        if (!researchRegion.trim()) setResearchRegion(generatedResearch.region);
        if (!researchAudience.trim()) setResearchAudience(generatedResearch.audience);
        if (!researchSources.trim()) setResearchSources(generatedResearch.sourcePreferences.join("\n"));
        if (!researchExclusions.trim()) setResearchExclusions(generatedResearch.exclusions.join("\n"));
        if (!researchDelivery.trim()) setResearchDelivery(generatedResearch.deliveryFormat);
      }
    }
    setCreationSettingsOpen((value) => !value);
  };

  const createMission = (event: FormEvent) => {
    event.preventDefault();
    if (!conversation || !client.createMission || !objective.trim()) return;
    const acceptanceCriteria = effectiveAcceptanceCriteria;
    if (acceptanceCriteria.length > 20) {
      setError("验收标准不能超过 20 条。");
      return;
    }
    if (acceptanceCriteria.some((value) => value.length > 1_000)) {
      setError("每条验收标准不能超过 1000 个字符。");
      return;
    }
    const deepResearch = mode === "DEEP_RESEARCH";
    void command(() => client.createMission!({
      conversationId: conversation.id,
      objective: objective.trim(),
      acceptanceCriteria,
      mode,
      selectedSkillId: deepResearch ? "deep-research" : undefined,
      researchBrief: deepResearch ? {
        question: researchQuestion.trim() || objective.trim(),
        scope: effectiveResearchScope,
        timeRange: effectiveResearchTimeRange,
        region: effectiveResearchRegion,
        audience: effectiveResearchAudience,
        sourcePreferences: researchSources.trim()
          ? researchSources.split("\n").map((value) => value.trim()).filter(Boolean)
          : generatedResearch.sourcePreferences,
        exclusions: researchExclusions.trim()
          ? researchExclusions.split("\n").map((value) => value.trim()).filter(Boolean)
          : generatedResearch.exclusions,
        deliveryFormat: researchDelivery.trim() || generatedResearch.deliveryFormat,
      } : undefined,
    }, { idempotencyKey: initialDraft?.idempotencyKey ?? crypto.randomUUID() })).then((succeeded) => {
      if (succeeded) {
        onDraftCreated();
        setCreatingMission(false);
        setObjective("");
        setCriteria("");
        setResearchQuestion("");
        setResearchScope("");
        setResearchTimeRange("");
        setResearchRegion("");
        setResearchAudience("");
        setResearchSources("");
        setResearchExclusions("");
        setResearchDelivery("");
        setCreationSettingsOpen(false);
      }
    });
  };

  const beginFollowUp = () => {
    if (!selected || !conversation) return;
    if (missions.some((mission) => mission.conversationId === conversation.id && !missionTerminalStates.has(mission.state))) {
      setError("当前会话已有进行中的 Mission，请先完成或取消后再继续研究。");
      return;
    }
    setMode(selected.mode);
    setObjective(`继续研究：${selected.objective}，重点解决报告中的未决问题与待核实结论。`);
    setCriteria([
      "逐项核实上一份报告中的未决问题与待核实结论",
      "新增结论必须提供可追溯来源",
      "说明相对上一份报告发生的结论变化",
    ].join("\n"));
    setResearchQuestion(selected.researchBrief?.question ?? selected.objective);
    setResearchScope(selected.researchBrief?.scope ?? "");
    setResearchTimeRange(selected.researchBrief?.timeRange ?? "");
    setResearchRegion(selected.researchBrief?.region ?? "");
    setResearchAudience(selected.researchBrief?.audience ?? "");
    setResearchSources(selected.researchBrief?.sourcePreferences.join("\n") ?? "政府、监管机构与一手来源");
    setResearchExclusions(selected.researchBrief?.exclusions.join("\n") ?? "无法追溯出处的营销材料");
    setResearchDelivery(selected.researchBrief?.deliveryFormat ?? "中文 Markdown 报告");
    setCreationSettingsOpen(false);
    setCreatingMission(true);
    setEditingPlan(false);
    setDetailPanelOpen(false);
    setMobileView("content");
    setError(null);
  };

  const beginEdit = () => {
    if (!selected) return;
    const tasks = copyMissionPlanTasks(selected.tasks);
    setPlanDraft(tasks);
    setEditingTaskId(tasks[0]?.taskId ?? null);
    setError(null);
    setEditingPlan(true);
  };

  const cancelEdit = () => {
    setEditingPlan(false);
    setPlanDraft([]);
    setEditingTaskId(null);
    setError(null);
  };

  const updateDraftTask = (taskId: string, patch: Partial<MissionPlanTask>) => {
    setPlanDraft((current) => current.map((task) => task.taskId === taskId ? { ...task, ...patch } : task));
  };

  const addDraftTask = () => {
    if (!selected || planDraft.length >= maxPlanTasks) return;
    const prefix = selected.mode === "DEEP_RESEARCH" ? "manual-research" : "manual-task";
    let suffix = planDraft.length + 1;
    while (planDraft.some((task) => task.taskId === `${prefix}-${suffix}`)) suffix += 1;
    const task: MissionPlanTask = {
      taskId: `${prefix}-${suffix}`,
      ordinal: planDraft.length + 1,
      title: "新增研究任务",
      objective: "说明这项任务需要回答的问题和预期结果。",
      acceptanceCriteria: ["给出可核验的结论与来源"],
      dependsOn: [],
      taskType: selected.mode === "DEEP_RESEARCH" ? "RESEARCH" : "GENERAL",
      requiredSkillIds: selected.mode === "DEEP_RESEARCH" ? ["deep-research"] : [],
      resultSchemaId: selected.mode === "DEEP_RESEARCH" ? "pa.research-task-result" : "pa.task-result",
      resultSchemaVersion: "v1",
      state: "PLANNED",
    };
    setPlanDraft((current) => [...current, task]);
    setEditingTaskId(task.taskId);
  };

  const removeDraftTask = (taskId: string) => {
    const dependent = planDraft.find((task) => task.dependsOn.includes(taskId));
    if (dependent) {
      setError(`请先移除“${dependent.title}”对当前任务的依赖。`);
      return;
    }
    if (planDraft.length <= 1) {
      setError("计划至少需要保留一个任务。");
      return;
    }
    const currentIndex = planDraft.findIndex((task) => task.taskId === taskId);
    const next = planDraft.filter((task) => task.taskId !== taskId);
    setPlanDraft(next);
    setEditingTaskId(next[Math.min(currentIndex, next.length - 1)]?.taskId ?? null);
    setError(null);
  };

  const moveDraftTask = (taskId: string, direction: -1 | 1) => {
    const currentIndex = planDraft.findIndex((task) => task.taskId === taskId);
    const targetIndex = currentIndex + direction;
    if (currentIndex < 0 || targetIndex < 0 || targetIndex >= planDraft.length) return;
    const current = planDraft[currentIndex];
    const target = planDraft[targetIndex];
    if (direction < 0 && current.dependsOn.includes(target.taskId)) {
      setError("任务不能移动到它所依赖的任务之前。请先调整依赖关系。");
      return;
    }
    if (direction > 0 && target.dependsOn.includes(current.taskId)) {
      setError("被依赖的任务不能移动到其下游任务之后。请先调整依赖关系。");
      return;
    }
    const next = [...planDraft];
    next.splice(currentIndex, 1);
    next.splice(targetIndex, 0, current);
    setPlanDraft(normalizeMissionPlanTasks(next));
    setError(null);
  };

  const replacePlan = () => {
    if (!selected || !client.replaceMissionPlan) return;
    const tasks = normalizeMissionPlanTasks(planDraft);
    const depth = missionPlanDependencyDepth(tasks);
    if (tasks.length < 1 || tasks.length > maxPlanTasks) {
      setError(`计划需要包含 1～${maxPlanTasks} 个任务。`);
      return;
    }
    if (depth > maxPlanDependencyDepth) {
      setError(`任务依赖深度不能超过 ${maxPlanDependencyDepth}。`);
      return;
    }
    if (tasks.some((task) => !task.title || !task.objective)) {
      setError("每个任务都需要标题和任务目标。");
      return;
    }
    void command(() => client.replaceMissionPlan!(selected, { plan: { tasks } }, {
      idempotencyKey: crypto.randomUUID(),
    })).then((succeeded) => {
      if (succeeded) cancelEdit();
    });
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

  const visibleMissions = missions
    .filter((mission) => mission.objective.toLocaleLowerCase().includes(missionQuery.trim().toLocaleLowerCase()))
    .filter((mission) => {
      if (missionFilter === "ACTIVE") return !missionTerminalStates.has(mission.state);
      if (missionFilter === "ACTION") return ["WAITING_CONFIRMATION", "WAITING_USER"].includes(mission.state);
      if (missionFilter === "COMPLETED") return ["COMPLETED", "PARTIALLY_COMPLETED"].includes(mission.state);
      if (missionFilter === "FAILED") return ["FAILED", "CANCELLED"].includes(mission.state);
      return true;
    })
    .sort((left, right) => {
      if (missionSort === "PROGRESS") {
        const leftProgress = left.tasks.length ? left.execution.completedTasks / left.tasks.length : 0;
        const rightProgress = right.tasks.length ? right.execution.completedTasks / right.tasks.length : 0;
        if (leftProgress !== rightProgress) return rightProgress - leftProgress;
      }
      return new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime();
    });
  const editingTaskIndex = planDraft.findIndex((task) => task.taskId === editingTaskId);
  const editingTask = editingTaskIndex >= 0 ? planDraft[editingTaskIndex] : null;
  const planDraftDepth = missionPlanDependencyDepth(planDraft);
  const planDraftValid = !!selected
    && planDraft.length >= 1
    && planDraft.length <= maxPlanTasks
    && planDraftDepth <= maxPlanDependencyDepth
    && planDraft.every((task) => task.title.trim() && task.objective.trim());
  const selectedTask = selected?.tasks.find((task) => task.taskId === selectedTaskId)
    ?? selected?.tasks[0]
    ?? null;
  const displayedTask = editingPlan ? editingTask : selectedTask;
  const displayedPlanTasks = editingPlan ? planDraft : (selected?.tasks ?? []);
  const selectedTaskIndex = selected && selectedTask
    ? selected.tasks.findIndex((task) => task.taskId === selectedTask.taskId)
    : -1;
  const nextTask = selected && selectedTaskIndex >= 0
    ? selected.tasks[selectedTaskIndex + 1] ?? null
    : null;
  const selectedFinalResult = parseMissionFinalResult(selected?.finalResult ?? null);
  const selectedArtifacts = selected ? missionArtifactItems(selected) : [];
  const selectedCurrentTask = selected?.tasks.find((task) => task.taskId === selected.execution.currentTaskId);
  const selectedActivity = selected ? missionExecutionActivity(selected, selectedCurrentTask) : "";
  const selectedTerminal = selected ? missionTerminalStates.has(selected.state) : false;
  const canCreateMission = Boolean(conversation)
    && !missions.some((mission) => mission.conversationId === conversation?.id && !missionTerminalStates.has(mission.state));
  const missionProcess = selected ? <>
    {selected.researchBrief && <section className="research-brief-summary"><h4>研究说明</h4><p><b>问题：</b>{selected.researchBrief.question}</p>{selected.researchBrief.scope && <p><b>范围：</b>{selected.researchBrief.scope}</p>}<p><b>时间 / 地区 / 受众：</b>{[selected.researchBrief.timeRange, selected.researchBrief.region, selected.researchBrief.audience].filter(Boolean).join(" · ") || "未限定"}</p></section>}
    {selected.acceptanceCriteria.length > 0 && <section><h4>验收标准</h4><ul>{selected.acceptanceCriteria.map((item) => <li key={item}>{item}</li>)}</ul></section>}
    <section className="mission-execution-summary" aria-label="Mission 执行状态">
      <div className="mission-progress-copy"><span>任务进度</span><b>{selected.execution.completedTasks}/{selected.tasks.length}</b></div>
      <span className="mission-progress-track" aria-hidden="true"><span style={{ width: `${selected.tasks.length ? Math.round((selected.execution.completedTasks / selected.tasks.length) * 100) : 0}%` }} /></span>
      {selectedActivity && <span className="mission-execution-activity" aria-live="polite"><LoaderCircle className="mission-state-spinner" size={14} aria-hidden="true" />{selectedActivity}</span>}
      {missionActivityRunId && missionActivityAttempt?.taskId && <button type="button" className="mission-activity-link" onClick={() => openTaskDetail(missionActivityAttempt.taskId)}><Bot size={13} aria-hidden="true" />查看执行活动{missionTaskActivities.length > 0 ? ` · ${missionTaskActivities.length}` : ""}<ChevronRight size={13} aria-hidden="true" /></button>}
      <details><summary>技术详情</summary><span>调度状态：{selected.execution.dispatcherStatus}</span>{selected.execution.currentTaskId && <span>内部任务标识已隐藏</span>}</details>
    </section>
    {!editingPlan && <section className="mission-plan-section"><div className="mission-plan-heading"><div><span className="eyebrow">当前计划</span><h4>执行计划 · 第 {selected.plan?.revision ?? "-"} 版</h4></div><span>{selected.tasks.length} 个任务</span></div>
      <ol className="mission-tasks">{selected.tasks.map((task) => <li className={selectedTask?.taskId === task.taskId && detailPanelOpen && !selectedArtifact && !selectedCitation ? "active" : ""} key={task.taskId}><button type="button" className="mission-task-select" aria-pressed={selectedTask?.taskId === task.taskId && detailPanelOpen} onClick={() => openTaskDetail(task.taskId)}><span className="mission-task-ordinal">{String(task.ordinal).padStart(2, "0")}</span><span className="mission-task-copy"><b>{task.title}</b><small>{task.objective}</small></span><em>{missionTaskStateLabel(task.state)}</em><ChevronRight size={15} aria-hidden="true" /></button></li>)}</ol>
    </section>}
  </> : null;

  return (
    <div className="dialog-backdrop mission-backdrop" role="presentation" onMouseDown={onClose}>
      <section ref={dialogRef} className="mission-dialog" role="dialog" aria-modal="true" aria-labelledby="mission-title" onKeyDown={handleDialogKeyDown} onMouseDown={(event) => event.stopPropagation()}>
        <header className="mission-dialog-header">
          <div className="mission-dialog-brand"><span className="brand-mark"><Brain size={19} /></span><div><strong>Haifa Assistant</strong><small>Mission 工作台</small></div></div>
          <div className="mission-dialog-heading"><span className="eyebrow">LONG-RUNNING WORK</span><h2 id="mission-title">Mission</h2></div>
          <button ref={closeButtonRef} type="button" className="button mission-return" aria-label="回到对话" onClick={onClose}><MessageSquarePlus size={15} />回到对话</button>
        </header>
        {error && <div className="error-banner" role="alert"><CircleAlert size={16} /><span>{error}</span></div>}
        <nav className="mission-mobile-tabs" aria-label="Mission 工作台视图">
          <button type="button" className={mobileView === "missions" ? "active" : ""} onClick={() => setMobileView("missions")}>Mission</button>
          <button type="button" className={mobileView === "content" ? "active" : ""} onClick={() => setMobileView("content")}>报告</button>
          <button type="button" className={mobileView === "detail" ? "active" : ""} disabled={!detailPanelOpen} onClick={() => setMobileView("detail")}>详情</button>
        </nav>
        <div className={`mission-layout ${detailPanelOpen ? "" : "detail-closed"}`} data-mobile-view={mobileView}>
          <aside className="mission-list" aria-label="Mission 列表">
            <div className="mission-list-heading"><div><span className="eyebrow">工作空间</span><strong>Mission 列表</strong></div><button type="button" disabled={!canCreateMission} title={canCreateMission ? "创建 Mission" : "当前会话已有进行中的 Mission"} onClick={() => setCreatingMission(true)}><Plus size={13} />新建</button></div>
            <label className="mission-list-search"><Search size={14} aria-hidden="true" /><input value={missionQuery} onChange={(event) => setMissionQuery(event.target.value)} placeholder="搜索 Mission" aria-label="搜索 Mission" /></label>
            <div className="mission-list-controls"><label>状态<select aria-label="按状态筛选 Mission" value={missionFilter} onChange={(event) => setMissionFilter(event.target.value as typeof missionFilter)}><option value="ALL">全部</option><option value="ACTIVE">进行中</option><option value="ACTION">需要我处理</option><option value="COMPLETED">已交付</option><option value="FAILED">失败或取消</option></select></label><label>排序<select aria-label="Mission 排序" value={missionSort} onChange={(event) => setMissionSort(event.target.value as typeof missionSort)}><option value="UPDATED">最近更新</option><option value="PROGRESS">完成进度</option></select></label></div>
            {missions.length === 0 && !busy && <p>还没有 Mission。</p>}
            {missions.length > 0 && visibleMissions.length === 0 && <p>没有匹配的 Mission。</p>}
            {visibleMissions.map((mission) => (
              <button type="button" className={!creatingMission && selected?.missionId === mission.missionId ? "active" : ""} key={mission.missionId} onClick={() => {
                setSelected(mission);
                onSelected(mission);
                setCreatingMission(false);
                setEditingPlan(false);
                setPlanDraft([]);
                setEditingTaskId(null);
                setSelectedArtifact(null);
                setSelectedCitation(null);
                setDetailPanelOpen(true);
                setMobileView("content");
                setError(null);
              }}>
                <small><MissionStateBadge mission={mission} detailed /><time>{dateTime.format(new Date(mission.updatedAt))}</time></small>
                <span title={missionDisplayTitle(mission, conversation, conversationTurns)}>{missionDisplayTitle(mission, conversation, conversationTurns)}</span>
                <span className="mission-card-progress" aria-label={`任务进度 ${mission.execution.completedTasks}/${mission.tasks.length}`}><span style={{ width: `${mission.tasks.length ? Math.round((mission.execution.completedTasks / mission.tasks.length) * 100) : 0}%` }} /></span>
                <small><span>{missionModeLabel(mission.mode)}</span><span>最近更新 · {missionStateLabel(mission.state)}</span></small>
              </button>
            ))}
          </aside>
          <div className="mission-content">
            {conversation && creatingMission && (
              <form className="mission-create" onSubmit={createMission}>
                <div className="mission-create-heading">
                  <div><span className="eyebrow">新建任务</span><h3>你希望 Mission 最终交付什么？</h3><small>先描述结果；系统会准备通用默认值，需要时再调整。</small></div>
                  <p><span>所属会话</span><strong title={conversation.displayName}>{conversation.displayName}</strong></p>
                </div>
                <div className="mission-create-field">
                  <span>任务模式</span>
                  <div className="mission-mode-options" role="radiogroup" aria-label="任务模式">
                    <label className={mode === "STANDARD" ? "selected" : ""}><input type="radio" name="mission-mode" value="STANDARD" checked={mode === "STANDARD"} onChange={() => selectMissionMode("STANDARD")} /><span className="mission-mode-icon"><Zap size={17} /></span><span className="mission-mode-copy"><b>标准 Mission</b><small>规划并完成多步骤任务，适合分析、整理和方案执行。</small></span><Check className="mission-mode-check" size={15} /></label>
                    <label className={mode === "DEEP_RESEARCH" ? "selected" : ""}><input type="radio" name="mission-mode" value="DEEP_RESEARCH" checked={mode === "DEEP_RESEARCH"} onChange={() => selectMissionMode("DEEP_RESEARCH")} /><span className="mission-mode-icon"><Sparkles size={17} /></span><span className="mission-mode-copy"><b>Deep Research</b><small>基于外部来源形成完整报告，包含来源、引用与交付文件。</small></span><Check className="mission-mode-check" size={15} /></label>
                  </div>
                </div>
                {mode === "DEEP_RESEARCH" && !webResearchAvailable && <div className="mission-capability-warning" role="alert"><WifiOff size={16} /><span><b>Deep Research 暂不可用</b>请先配置 Web Search/Fetch Provider；当前草稿不会生成计划或产生外部调用。</span></div>}
                <label className="mission-objective-field">目标<textarea aria-label="目标" value={objective} onChange={(event) => setObjective(event.target.value)} maxLength={8000} rows={3} placeholder="例如：梳理以太坊近三年的重要技术迭代，并分析其影响" /><small>用结果语言描述即可，不需要自己拆任务或填写技术参数。</small></label>

                <section className={`mission-generated-brief ${objective.trim() ? "ready" : "empty"}`} aria-live="polite">
                  <header>
                    <span className="mission-generated-brief-icon"><Sparkles size={17} /></span>
                    <div><span className="eyebrow">{mode === "DEEP_RESEARCH" ? "RESEARCH DEFAULTS" : "EXECUTION DEFAULTS"}</span><h4>{objective.trim() ? (mode === "DEEP_RESEARCH" ? "研究默认值已准备" : "执行默认值已准备") : "填写目标后准备默认值"}</h4><p>{objective.trim() ? "已准备通用、可编辑的默认值；计划生成时会结合完整目标。" : "无需先填写研究范围、验收标准或来源偏好。"}</p></div>
                    {objective.trim() && <span className="mission-generated-ready">默认值已准备</span>}
                  </header>
                  {objective.trim() && <>
                    <div className="mission-generated-grid">
                      <div><small>{mode === "DEEP_RESEARCH" ? "研究范围" : "任务范围"}</small><b>{mode === "DEEP_RESEARCH" ? effectiveResearchScope : "围绕目标规划并完成多步骤任务"}</b></div>
                      {mode === "DEEP_RESEARCH" && <div><small>时间</small><b>{effectiveResearchTimeRange}</b></div>}
                      {mode === "DEEP_RESEARCH" && <div><small>地区</small><b>{effectiveResearchRegion}</b></div>}
                      <div><small>交付</small><b>{mode === "DEEP_RESEARCH" ? "完整报告、来源与引用" : "任务结果与交付文件"}</b></div>
                    </div>
                    <footer><span><CheckCircle2 size={14} />验收标准 {effectiveAcceptanceCriteria.length} 项已准备</span><button type="button" className={creationSettingsOpen ? "open" : ""} onClick={toggleCreationSettings}><Pencil size={13} />{creationSettingsOpen ? "收起设置" : mode === "DEEP_RESEARCH" ? "调整研究设置" : "调整执行设置"}<ChevronDown size={14} /></button></footer>
                  </>}
                </section>

                {objective.trim() && creationSettingsOpen && <section className="mission-create-settings">
                  <header><div><span className="eyebrow">OPTIONAL SETTINGS</span><h4>{mode === "DEEP_RESEARCH" ? "调整研究设置" : "调整执行设置"}</h4><p>以下是通用默认值，不修改也可以直接继续。</p></div><span>可选</span></header>
                  <label>验收标准<textarea aria-label="验收标准" value={criteria} onChange={(event) => setCriteria(event.target.value)} maxLength={4000} rows={4} /><small>每行一条，最多 20 条；确认计划时仍可调整任务级验收标准。</small></label>
                  {mode === "DEEP_RESEARCH" && <>
                    <label>研究问题<textarea value={researchQuestion} onChange={(event) => setResearchQuestion(event.target.value)} maxLength={8000} rows={2} /></label>
                    <label>研究范围<textarea value={researchScope} onChange={(event) => setResearchScope(event.target.value)} maxLength={2000} rows={2} /></label>
                    <div className="research-brief-grid"><label>时间范围<input value={researchTimeRange} onChange={(event) => setResearchTimeRange(event.target.value)} maxLength={256} /></label><label>地区<input value={researchRegion} onChange={(event) => setResearchRegion(event.target.value)} maxLength={256} /></label><label>交付受众<input value={researchAudience} onChange={(event) => setResearchAudience(event.target.value)} maxLength={256} /></label><label>交付格式<input value={researchDelivery} onChange={(event) => setResearchDelivery(event.target.value)} maxLength={256} /></label></div>
                    <details className="mission-source-settings"><summary><span><b>来源与边界</b><small>仅在需要约束来源时调整</small></span><ChevronDown size={15} /></summary><div className="research-brief-grid"><label>优先来源<textarea value={researchSources} onChange={(event) => setResearchSources(event.target.value)} rows={3} /></label><label>排除项<textarea value={researchExclusions} onChange={(event) => setResearchExclusions(event.target.value)} rows={3} /></label></div></details>
                  </>}
                </section>}
                <div className="mission-create-actions">{selected && <button type="button" className="button" onClick={() => setCreatingMission(false)}>返回当前 Mission</button>}<button type="submit" className="button primary-button" disabled={busy || !objective.trim() || (mode === "DEEP_RESEARCH" && !webResearchAvailable)}><Sparkles size={15} />生成计划</button></div>
              </form>
            )}
            {!creatingMission && (selected ? (
              <article className="mission-detail">
                <div className="mission-title-row"><div><MissionStateBadge mission={selected} detailed live /><span className="mission-mode">{missionModeLabel(selected.mode)}</span><span className="mission-mode">{selected.modelBinding.providerDisplayName} · {selected.modelBinding.modelDisplayName}</span><h3>{missionDisplayTitle(selected, conversation, conversationTurns)}</h3></div><button type="button" className="icon" title="刷新" aria-label="刷新 Mission" disabled={busy || !client.missionSnapshot} onClick={() => void command(() => client.missionSnapshot!(selected.missionId))}><RefreshCw size={16} /></button></div>
                {selected.blocker && <div className="error-banner" role="alert"><span>{missionFailureMessage(selected)}</span><details><summary>技术详情</summary><code>{selected.blocker}</code></details></div>}
                {selectedTerminal && selected.finalResult && <MissionFinalResult client={client} mission={selected} onTaskSelect={openTaskDetail} onCitationSelect={openCitation} onCreateFollowUp={beginFollowUp} />}
                {selectedTerminal ? <details className="mission-process"><summary><span><b>研究过程</b><small>研究说明、验收标准、执行进度与任务计划</small></span><ChevronDown size={16} /></summary><div>{missionProcess}</div></details> : missionProcess}
                {!selectedTerminal && selected.finalResult && <MissionFinalResult client={client} mission={selected} onTaskSelect={openTaskDetail} onCitationSelect={openCitation} onCreateFollowUp={beginFollowUp} />}
                {selectedFinalResult?.schemaVersion !== "pa.research-delivery/v2" && selected.sources.length > 0 && <section className="research-sources"><h4>来源与引用</h4><ol>{selected.sources.map((source, index) => <li key={source}><span>{String(index + 1).padStart(2, "0")}</span><div><strong><a href={source} target="_blank" rel="noreferrer">{missionSourceFallbackTitle(source)}</a></strong></div></li>)}</ol></section>}
                {selectedArtifacts.length > 0 && <section className="research-artifacts"><div className="research-artifact-heading"><div><span className="eyebrow">DELIVERABLES</span><h4>交付文件</h4></div><span>{selectedArtifacts.length} 个文件</span></div><ul>{selectedArtifacts.map((artifact) => <li key={artifact.artifactId}><button type="button" onClick={() => openArtifact(artifact)}><span className="research-artifact-icon">{artifact.fileName.endsWith(".md") ? "MD" : "JSON"}</span><span><b>{artifact.title}</b><small>{artifact.fileName}</small></span><ChevronRight size={15} /></button></li>)}</ul></section>}
                {missionInteraction && <section className="mission-interaction"><h4>{missionInteraction.title}</h4><p>{missionInteraction.safePrompt}</p>{missionInteraction.inputType !== "NONE" && <textarea value={missionInteractionText} maxLength={missionInteraction.maximumCharacters} onChange={(event) => setMissionInteractionText(event.target.value)} rows={3} /> }<div>{missionInteraction.allowedActions.map((action) => <button key={action} type="button" className="button" disabled={busy} onClick={() => respondToMissionInteraction(action)}>{action}</button>)}</div></section>}
                {editingPlan && editingTask && <section className="mission-plan-adjuster" aria-label="适度调整计划">
                  <header className="mission-plan-adjuster-heading"><div><span className="eyebrow">PLAN REVIEW · REVISION {selected.plan?.revision ?? "-"}</span><h4>适度调整计划</h4><p>只调整任务结构和内容；Mission 目标、研究范围与交付格式保持不变。</p></div><button type="button" className="button" onClick={cancelEdit}><X size={14} />退出调整</button></header>
                  <div className="mission-plan-guardrail"><ShieldCheck size={15} /><span><b>系统持续校验：</b>最多 {maxPlanTasks} 个任务、依赖深度不超过 {maxPlanDependencyDepth}；任务只能依赖排在它之前的任务。</span></div>
                  <div className="mission-plan-adjuster-layout">
                    <aside className="mission-plan-draft-list" aria-label="待确认任务顺序">
                      <div><span>任务顺序</span><b>{planDraft.length}/{maxPlanTasks}</b></div>
                      {planDraft.map((task, index) => <button type="button" className={task.taskId === editingTaskId ? "active" : ""} key={task.taskId} aria-label={`编辑任务 ${String(index + 1).padStart(2, "0")} ${task.title}`} onClick={() => setEditingTaskId(task.taskId)}><span>{String(index + 1).padStart(2, "0")}</span><span><b>{task.title || "未命名任务"}</b><small>{task.dependsOn.length ? `依赖 ${task.dependsOn.length} 项` : "可直接执行"}</small></span><ChevronRight size={14} /></button>)}
                      <button type="button" className="mission-plan-add" disabled={planDraft.length >= maxPlanTasks} onClick={addDraftTask}><Plus size={14} />增加任务</button>
                    </aside>
                    <div className="mission-plan-task-form">
                      <header><div><span className="eyebrow">任务 {String(editingTaskIndex + 1).padStart(2, "0")}</span><h4>{editingTask.title || "未命名任务"}</h4></div><div className="mission-plan-order-actions"><button type="button" aria-label="上移任务" title="上移任务" disabled={editingTaskIndex <= 0} onClick={() => moveDraftTask(editingTask.taskId, -1)}><ChevronUp size={15} /></button><button type="button" aria-label="下移任务" title="下移任务" disabled={editingTaskIndex >= planDraft.length - 1} onClick={() => moveDraftTask(editingTask.taskId, 1)}><ChevronDown size={15} /></button><button type="button" aria-label="删除任务" title="删除任务" disabled={planDraft.length <= 1 || planDraft.some((task) => task.dependsOn.includes(editingTask.taskId))} onClick={() => removeDraftTask(editingTask.taskId)}><Trash2 size={15} /></button></div></header>
                      <label>任务标题<input aria-label="任务标题" value={editingTask.title} maxLength={200} onChange={(event) => updateDraftTask(editingTask.taskId, { title: event.target.value })} /><small>{editingTask.title.length}/200</small></label>
                      <label>任务目标<textarea aria-label="任务目标" value={editingTask.objective} maxLength={4000} rows={3} onChange={(event) => updateDraftTask(editingTask.taskId, { objective: event.target.value })} /><small>{editingTask.objective.length}/4000</small></label>
                      <label>验收标准 <em>每行一项，最多 20 项</em><textarea aria-label="任务验收标准" value={editingTask.acceptanceCriteria.join("\n")} rows={4} onChange={(event) => updateDraftTask(editingTask.taskId, { acceptanceCriteria: event.target.value.split("\n").slice(0, 20) })} /><small>{editingTask.acceptanceCriteria.filter(Boolean).length}/20</small></label>
                      <fieldset><legend>依赖任务 <em>只能选择当前任务之前的任务</em></legend>{editingTaskIndex > 0 ? <div className="mission-plan-dependencies">{planDraft.slice(0, editingTaskIndex).map((task, index) => <label key={task.taskId}><input type="checkbox" checked={editingTask.dependsOn.includes(task.taskId)} onChange={(event) => updateDraftTask(editingTask.taskId, { dependsOn: event.target.checked ? [...editingTask.dependsOn, task.taskId] : editingTask.dependsOn.filter((dependency) => dependency !== task.taskId) })} /><span><b>{String(index + 1).padStart(2, "0")} · {task.title}</b></span></label>)}</div> : <p className="mission-plan-no-dependency"><CheckCircle2 size={14} />首个任务无需依赖，可以直接执行。</p>}</fieldset>
                      <p className="mission-plan-system-fixed"><ShieldCheck size={14} />任务类型与交付格式由系统保持不变，不需要人工配置。</p>
                    </div>
                  </div>
                  <footer className="mission-plan-adjuster-actions"><div className={planDraftValid ? "valid" : "invalid"}><ShieldCheck size={15} /><span><b>{planDraftValid ? "计划约束通过" : "请补全必填内容或调整依赖"}</b>{planDraft.length} 个任务 · 依赖深度 {Number.isFinite(planDraftDepth) ? planDraftDepth : "无效"}/{maxPlanDependencyDepth} · 保存后生成 revision {(selected.plan?.revision ?? 0) + 1}</span></div><button type="button" className="button" onClick={cancelEdit}>放弃修改</button><button type="button" className="button primary-button" disabled={busy || !planDraftValid} onClick={replacePlan}><Save size={14} />保存调整</button></footer>
                </section>}
                <footer className="mission-actions">
                  {!editingPlan && selected.state === "WAITING_CONFIRMATION" && <>
                    <button type="button" className="button" disabled={busy} onClick={() => void command(() => client.replaceMissionPlan!(selected, { regenerate: true }, { idempotencyKey: crypto.randomUUID() }))}>重新生成</button>
                    <button type="button" className="button" disabled={busy} onClick={beginEdit}><Pencil size={14} />适度调整计划</button>
                    <button type="button" className="button primary-button" disabled={busy} onClick={() => void command(() => client.confirmMission!(selected, { idempotencyKey: crypto.randomUUID() }))}><CheckCircle2 size={15} />确认计划</button>
                  </>}
                  {!missionTerminalStates.has(selected.state) && <button type="button" className="mission-cancel-button" disabled={busy} onClick={() => void command(() => client.cancelMission!(selected, { idempotencyKey: crypto.randomUUID() }))}><Square size={10} fill="currentColor" aria-hidden="true" />取消 Mission</button>}
                </footer>
                {(selected.state === "RUNNING" || selected.state === "SYNTHESIZING") && <p className="mission-phase-note">Mission 正在后台{selected.state === "SYNTHESIZING" ? "整合最终结果" : "串行执行"}；关闭页面或重启服务后可从持久化状态继续恢复。</p>}
              </article>
            ) : <div className="empty"><h3>选择或创建 Mission</h3><p>Mission 用于需要拆解、持续运行并最终整合的大任务。</p></div>)}
          </div>
          {detailPanelOpen && <aside className="mission-task-detail" aria-label="Mission 详情面板">
            {creatingMission ? <div className="mission-creation-guide"><span className="eyebrow">创建后会发生什么</span><h3>先规划，不会立即执行</h3><ol><li><span><Sparkles size={16} /></span><div><b>生成任务计划</b><small>把目标拆成可验证的任务与依赖</small></div></li><li><span><Pencil size={16} /></span><div><b>由你确认或适度调整</b><small>可以修改任务、顺序和早期依赖</small></div></li><li><span><CheckCircle2 size={16} /></span><div><b>确认后后台执行</b><small>关闭页面也不会丢失进度</small></div></li></ol><p><ShieldCheck size={16} /><span><b>保持简单</b><small>只展示模式、计划和交付结果。</small></span></p></div> : selectedCitation ? <ResearchCitationPanel selection={selectedCitation} onClose={closeDetailPanel} /> : selected && selectedArtifact ? <>
              <header className="mission-task-detail-header"><span className="mission-task-detail-number"><Paperclip size={16} /></span><div><span className="eyebrow">交付文件</span><h3>{selectedArtifact.title}</h3><small>{selectedArtifact.fileName}</small></div><button type="button" className="icon" aria-label="关闭交付文件" onClick={closeDetailPanel}><X size={16} /></button></header>
              <div className="mission-task-detail-scroll artifact-reader-scroll"><MissionArtifactReader client={client} mission={selected} artifact={selectedArtifact} /></div>
            </> : !creatingMission && displayedTask ? <>
              <header className="mission-task-detail-header"><span className="mission-task-detail-number">{String(displayedTask.ordinal).padStart(2, "0")}</span><div><span className="eyebrow">{editingPlan ? "正在调整" : "任务详情"}</span><h3>{displayedTask.title}</h3></div><button type="button" className="icon" aria-label="关闭任务详情" onClick={closeDetailPanel}><X size={16} /></button></header>
              <div className="mission-task-detail-scroll">
                <section><h4>任务目标</h4><p>{displayedTask.objective}</p></section>
                <dl className="mission-task-metadata">
                  <div><dt>当前状态</dt><dd>{editingPlan ? "待保存" : missionTaskStateLabel(displayedTask.state)}</dd></div>
                </dl>
                {!editingPlan && missionActivityRunId && missionActivityAttempt?.taskId === displayedTask.taskId && <section className="mission-task-activity" aria-label="任务执行活动">
                  <div className="mission-task-section-heading"><h4>执行活动</h4><span aria-live="polite">{missionTaskActivityStatus === "loading" ? "正在加载" : missionTaskActivityStatus === "error" ? "暂时无法同步" : `${missionTaskActivities.length} 项`}</span></div>
                  {missionTaskActivityStatus === "loading"
                    ? <p className="mission-task-activity-loading"><LoaderCircle className="mission-state-spinner" size={14} aria-hidden="true" />正在读取 Model、Tool、Skill 与 MCP 调用…</p>
                    : <ActivityFeed activities={missionTaskActivities} emptyText={missionTaskActivityStatus === "error" ? missionActivityPolling ? "执行活动暂时无法同步，页面会自动重试。" : "执行活动暂时无法同步，请刷新后重试。" : "当前任务尚未产生可展示的执行活动。"} />}
                  {missionTaskRun && <details className="mission-task-usage"><summary>Token 使用</summary><UsagePanel run={missionTaskRun} /></details>}
                </section>}
                <section><div className="mission-task-section-heading"><h4>验收标准</h4><span>{displayedTask.acceptanceCriteria.length} 项</span></div>{displayedTask.acceptanceCriteria.length > 0 ? <ol className="mission-task-criteria">{displayedTask.acceptanceCriteria.map((criterion) => <li key={criterion}>{criterion}</li>)}</ol> : <p className="mission-task-empty">未定义任务级验收标准。</p>}</section>
                <section><div className="mission-task-section-heading"><h4>依赖任务</h4><span>{displayedTask.dependsOn.length} 项</span></div>{displayedTask.dependsOn.length > 0 ? <div className="mission-task-dependencies">{displayedTask.dependsOn.map((dependencyId) => {
                  const dependency = displayedPlanTasks.find((task) => task.taskId === dependencyId);
                  return dependency
                    ? <button type="button" key={dependencyId} onClick={() => editingPlan ? setEditingTaskId(dependencyId) : openTaskDetail(dependencyId)}>{String(dependency.ordinal).padStart(2, "0")} {dependency.title}<ChevronRight size={14} aria-hidden="true" /></button>
                    : <span className="mission-task-empty" key={dependencyId}>依赖任务详情不可用</span>;
                })}</div> : <p className="mission-task-empty">无依赖，可直接执行。</p>}</section>
              </div>
              <footer className="mission-task-detail-actions">
                {!editingPlan && selected && selectedTask?.state === "BLOCKED" && client.retryMissionTask && <button type="button" className="button" disabled={busy} onClick={() => void command(() => client.retryMissionTask!(selected, selectedTask.taskId, { idempotencyKey: crypto.randomUUID() }))}>重试任务</button>}
                {!editingPlan && nextTask && <button type="button" className="button primary-button" onClick={() => openTaskDetail(nextTask.taskId)}>下一个任务<ChevronRight size={15} /></button>}
              </footer>
            </> : <div className="mission-task-detail-empty"><PanelRight size={24} /><h3>选择任务或交付文件</h3><p>在中间区域选择任务、引用或交付文件，在这里查看详情。</p></div>}
          </aside>}
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
  const [newModelPreferences, setNewModelPreferences] = useState<ModelPreferences | null>(null);
  const [slashMenu, setSlashMenu] = useState<SlashMenuState | null>(null);
  const [slashActiveIndex, setSlashActiveIndex] = useState(0);
  const [slashFromPlus, setSlashFromPlus] = useState(false);
  const [modelDraftBindingId, setModelDraftBindingId] = useState("");
  const [modelDraftPreferences, setModelDraftPreferences] = useState<ModelPreferences | null>(null);
  const [composerMode, setComposerMode] = useState<ComposerMode>("CHAT");
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
  const [missionRouteId, setMissionRouteId] = useState<string | null>(() => missionIdFromLocation());
  const [missionOpen, setMissionOpen] = useState(() => missionIdFromLocation() != null);
  const [missionDraft, setMissionDraft] = useState<MissionDraftRequest | null>(null);
  const [conversationMission, setConversationMission] = useState<MissionSnapshot | null>(null);
  const [conversationMissions, setConversationMissions] = useState<MissionSnapshot[]>([]);
  const [requestedMissionTaskId, setRequestedMissionTaskId] = useState<string | null>(null);
  const [requestedMissionArtifact, setRequestedMissionArtifact] = useState<string | null>(null);
  const [researchReadingContext, setResearchReadingContext] = useState<
    (MarkdownResearchContext & { missionId: string }) | null
  >(null);

  const handleMissionChanged = useCallback((mission: MissionSnapshot | null) => {
    if (!mission || mission.conversationId === state.selectedConversationId) {
      setConversationMission(mission);
      setConversationMissions((current) => {
        if (!mission) return current;
        return [mission, ...current.filter((candidate) => candidate.missionId !== mission.missionId)];
      });
    }
  }, [state.selectedConversationId]);

  const navigateToMission = useCallback((mission: MissionSnapshot) => {
    setMissionRouteId(mission.missionId);
    const query = new URLSearchParams(window.location.search);
    query.set(conversationIdParameter, mission.conversationId);
    const nextUrl = `/missions/${encodeURIComponent(mission.missionId)}?${query.toString()}`;
    if (`${window.location.pathname}${window.location.search}` !== nextUrl) {
      window.history.pushState(null, "", nextUrl);
    }
  }, []);

  const openResearchTask = useCallback((ordinal: number) => {
    const mission = conversationMission;
    const task = mission?.tasks.find((candidate) => candidate.ordinal === ordinal);
    if (!mission || !task) return;
    previousFocus.current = document.activeElement as HTMLElement;
    setRequestedMissionTaskId(task.taskId);
    setMissionOpen(true);
    navigateToMission(mission);
  }, [conversationMission, navigateToMission]);

  useEffect(() => {
    const syncMissionRoute = () => {
      const missionId = missionIdFromLocation();
      setMissionRouteId(missionId);
      setMissionOpen(missionId != null);
    };
    window.addEventListener("popstate", syncMissionRoute);
    return () => window.removeEventListener("popstate", syncMissionRoute);
  }, []);

  useEffect(() => {
    if (!state.selectedConversationId || !client.missions) {
      setConversationMission(null);
      setConversationMissions([]);
      return;
    }
    const controller = new AbortController();
    client.missions(state.selectedConversationId, controller.signal)
      .then((page) => {
        if (!controller.signal.aborted) {
          setConversationMissions(page.items);
          setConversationMission(page.items[0] ?? null);
        }
      })
      .catch(() => {
        if (!controller.signal.aborted) {
          setConversationMissions([]);
          setConversationMission(null);
        }
      });
    return () => controller.abort();
  }, [client, state.selectedConversationId]);

  useEffect(() => {
    const mission = conversationMission;
    const finalResult = parseMissionFinalResult(mission?.finalResult ?? null);
    if (!mission || finalResult?.schemaVersion !== "pa.research-delivery/v2") {
      setResearchReadingContext(null);
      return;
    }
    const baseContext: MarkdownResearchContext & { missionId: string } = {
      missionId: mission.missionId,
      anchorPrefix: `mission-${mission.missionId}`,
      tasks: mission.tasks.map((task) => ({
        ordinal: task.ordinal,
        taskId: task.taskId,
        title: task.title,
      })),
      sources: [],
      sourceState: "loading",
    };
    const artifactId = finalResult.sourcesArtifactRef?.artifactId;
    if (!artifactId || !client.missionArtifact) {
      setResearchReadingContext({ ...baseContext, sourceState: "failed" });
      return;
    }
    let cancelled = false;
    setResearchReadingContext(baseContext);
    client.missionArtifact(mission.missionId, artifactId)
      .then((artifact) => {
        if (!cancelled) {
          setResearchReadingContext({
            ...baseContext,
            sources: parseResearchSourcesArtifact(artifact),
            sourceState: "ready",
          });
        }
      })
      .catch(() => {
        if (!cancelled) setResearchReadingContext({ ...baseContext, sourceState: "failed" });
      });
    return () => {
      cancelled = true;
    };
  }, [client, conversationMission]);

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
      const initialModel = state.bootstrap?.models.find((model) => model.id === newModelId);
      const initialModelSelection = initialModel && newModelPreferences
        ? { model: initialModel, preferences: newModelPreferences }
        : undefined;
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

  const configuredModels = state.bootstrap?.models ?? [];
  const modelProviders = groupModelsByProvider(configuredModels);
  const selectedModelId = state.selectedConversation?.model.model.id ?? newModelId;
  const selectedModelPreferences = state.selectedConversation?.model.preferences
    ?? newModelPreferences
    ?? configuredModels.find((model) => model.id === (newModelId || state.bootstrap?.defaultModelId))?.recommendedPreferences
    ?? null;
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
      dispatch({ type: "setComposer", value: "/model" });
      setSlashMenu({ stage: "providers" });
      const currentProviderIndex = modelProviders.findIndex((provider) =>
        provider.modelGroups.some((group) => group.bindings.some((model) => model.id === selectedModelId))
      );
      setSlashActiveIndex(Math.max(0, currentProviderIndex));
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
    const current = group.bindings.find((binding) => binding.id === selectedModelId);
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
    if (!binding) return;
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
      if (!state.bootstrap?.capabilities.includes("mission") || !client.createMission) {
        dispatch({ type: "error", message: "当前 Server 未发布 Mission 能力。" });
        return;
      }
      if (pendingImages.length > 0) {
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
                    <article className={`message ${assistant ? "assistant" : "user"}${turn.images?.length ? " has-images" : ""}${research || embeddedResearch ? " research-report-message" : ""}${deliveryMission ? " mission-delivery-message" : ""}`} key={turn.id}>
                      <span className="message-role">{assistant ? "Haifa" : "你"}</span>
                      {turn.images?.length > 0 && <TurnImages images={turn.images} />}
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
            className={`composer${composerMode === "DEEP_RESEARCH" ? " deep-research-mode" : ""}${imageCapable ? " image-capable" : ""}${pendingImages.length ? " has-pending-images" : ""}${draggingImages ? " image-dragging" : ""}`}
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
            {composerMode === "DEEP_RESEARCH" && <div className="composer-mode-chip"><Sparkles size={13} aria-hidden="true" /><span>Deep Research</span><button type="button" aria-label="退出 Deep Research 模式" title="退出 Deep Research 模式" onClick={() => setComposerMode("CHAT")}><X size={12} /></button></div>}
            {slashMenu && !composerDisabled && (
              <section
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
                        <small>{provider.modelGroups.length} 个可用模型 · {provider.id}</small>
                      </span>
                      <ChevronRight size={17} />
                    </button>
                  ))}
                  {slashMenu.stage === "models" && selectedSlashProvider?.modelGroups.map((group, index) => (
                    <button
                      type="button"
                      role="option"
                      aria-selected={index === slashActiveIndex}
                      className={index === slashActiveIndex ? "active" : ""}
                      key={group.id}
                      onMouseEnter={() => setSlashActiveIndex(index)}
                      onClick={() => activateSlashItem(index)}
                    >
                      <Bot size={18} />
                      <span>
                        <strong>{group.displayName}</strong>
                        <small>{group.bindings.length > 1 ? `${group.bindings.length} 种已验证连接方式` : group.bindings[0]?.apiStyleDisplayName}</small>
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
                              {modelDraftBinding.controls.apiStyle.allowedValues.map((bindingId) => {
                                const binding = selectedSlashModelGroup?.bindings.find((candidate) => candidate.id === bindingId);
                                return binding ? <option key={binding.id} value={binding.id}>{binding.apiStyleDisplayName}</option> : null;
                              })}
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
                      setSlashFromPlus(true);
                      setSlashMenu({ stage: "providers" });
                      const currentProviderIndex = modelProviders.findIndex((provider) =>
                        provider.modelGroups.some((group) => group.bindings.some((model) => model.id === selectedModelId))
                      );
                      setSlashActiveIndex(Math.max(0, currentProviderIndex));
                      closeImageTools();
                      window.requestAnimationFrame(() => textareaRef.current?.focus());
                    }}>
                      <Bot size={17} />
                      <span><strong>选择模型</strong><small>选择当前会话后续消息使用的模型</small></span>
                    </button>
                    {imageCapable && <button
                      type="button"
                      disabled={composerDisabled || uploadingImage || pendingImages.length >= 4}
                      onClick={() => fileInputRef.current?.click()}
                    >
                      <Paperclip size={17} />
                      <span><strong>{uploadingImage ? "正在上传…" : "上传图片"}</strong><small>选择或拖放，最多 4 张</small></span>
                    </button>}
                    {imageCapable && <button
                      type="button"
                      aria-expanded={imageUrlInputOpen}
                      onClick={() => setImageUrlInputOpen((open) => !open)}
                    >
                      <Link size={17} />
                      <span><strong>添加图片 URL</strong><small>仅支持 HTTPS 图片地址</small></span>
                    </button>}
                    {imageCapable && imageUrlInputOpen && (
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
                    if (slashMenu.stage === "settings") applyModelDraft();
                    else activateSlashItem(slashActiveIndex);
                    return;
                  }
                  if (event.key === "Enter" && !event.shiftKey) {
                    event.preventDefault();
                    event.currentTarget.form?.requestSubmit();
                  }
                }}
                placeholder={runActive ? "当前任务运行中" : composerMode === "DEEP_RESEARCH" ? "描述调研目标，Enter 打开 Mission 确认页" : "输入消息，Enter 发送"}
                rows={4}
              />
            </label>
            <span className="image-input-hint">{composerMode === "DEEP_RESEARCH" ? "将打开 Mission 确认页" : imageCapable ? "支持图片与 Deep Research" : "点击 + 使用 Deep Research"}</span>
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
            <Button type="submit" className="send-button" aria-label={composerMode === "DEEP_RESEARCH" ? "准备 Deep Research" : "发送消息"} busy={Boolean(state.pending)} disabled={composerDisabled || (!state.composer.trim() && !pendingImages.length)}>
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
      <div className="sr-only" aria-live="polite">{state.pending ? `${state.pending.label}进行中` : ""}</div>
    </div>
  );
}

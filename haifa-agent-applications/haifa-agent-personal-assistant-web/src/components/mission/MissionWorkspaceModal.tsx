import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type FormEvent,
  type MouseEvent,
  type KeyboardEvent as ReactKeyboardEvent,
} from "react";
import {
  Bot,
  Brain,
  Check,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  ChevronUp,
  CircleAlert,
  Copy,
  LoaderCircle,
  MessageSquarePlus,
  PanelRight,
  Paperclip,
  Pencil,
  Plus,
  RefreshCw,
  Save,
  Search,
  ShieldCheck,
  Sparkles,
  Square,
  Trash2,
  WifiOff,
  X,
  Zap,
} from "lucide-react";
import type {
  Activity,
  Conversation,
  Interaction,
  MissionSnapshot,
  Run,
  Turn,
} from "../../api/generated";
import {
  missionArtifactUrl,
  type PersonalAssistantClient,
} from "../../api/client";
import {
  defaultMissionAcceptanceCriteria,
  defaultResearchBrief,
  type MissionMode,
} from "../../missionCreationDefaults";
import {
  researchSourceDate,
  researchSourceSite,
  researchSourceStatus,
  researchSourceTier,
  type MarkdownResearchContext,
  type MarkdownResearchSource,
} from "../../utils/markdownRenderer";
import {
  dateTime,
  formatTime,
  isTerminal,
  number,
  safeError,
} from "../../utils/formatters";
import { Button } from "../common/Button";
import {
  MessageContent,
  ResearchCitationPanel,
  safeResearchLocator,
  type ResearchCitationSelection,
} from "../conversation/MessageContent";
import { ActivityFeed, UsagePanel } from "../activity/ActivityRightPanel";
import {
  copyMissionPlanTasks,
  degradationLabels,
  missionAnimatedStates,
  missionArtifactItems,
  missionDisplayTitle,
  missionExecutionActivity,
  missionFailureMessage,
  missionModeLabel,
  missionPlanDependencyDepth,
  missionSourceFallbackTitle,
  missionStateAccessibleLabel,
  missionStateLabel,
  missionTaskStateLabel,
  missionTerminalStates,
  normalizeMissionPlanTasks,
  parseArtifactReference,
  parseMissionFinalResult,
  parseResearchSourcesArtifact,
  type MissionArtifactItem,
  type MissionDraftRequest,
  type MissionPlanTask,
} from "./missionUtils";

export function MissionStateBadge({ mission, detailed = false, live = false }: {
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


export function ArtifactJsonDocument({ text }: { text: string }) {
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


export function MissionArtifactReader({
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


export function MissionFinalResult({
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
  const isStandardResult = result.schemaVersion === "pa.mission-final-result/v1" || result.schemaVersion === "pa.mission-final-result/v2";
  const resultTitle = isStandardResult
    ? "Mission 最终报告"
    : "历史最终报告";
  const completionLabel = result.completionKind === "COMPLETE"
    ? "已完成"
    : result.completionKind === "PARTIAL"
      ? "部分完成"
      : result.completionKind;
  const hasAuditItems = (result.completedItems?.length ?? 0) > 0 ||
    (result.failedItems?.length ?? 0) > 0 ||
    (result.unverifiedClaims?.length ?? 0) > 0 ||
    (result.residualRisks?.length ?? 0) > 0 ||
    (result.unresolvedQuestions?.length ?? 0) > 0;
  return <section className="research-result">
    <h4>{resultTitle}{completionLabel && ` · ${completionLabel}`}</h4>
    {result.directAnswer && (!result.answerMarkdown || result.answerMarkdown.trim() !== result.directAnswer.trim()) && <p className="research-answer">{result.directAnswer}</p>}
    {result.answerMarkdown && <div className="artifact-markdown-reader"><MessageContent text={result.answerMarkdown} /></div>}
    {(result.sourceRefs?.length ?? 0) > 0 && <><h5>参考来源</h5><ul>{result.sourceRefs!.map((item) => {
      const isUrl = /^https?:\/\//i.test(item.trim());
      return <li key={item}>{isUrl ? <a href={item.trim()} target="_blank" rel="noopener noreferrer">{item}</a> : item}</li>;
    })}</ul></>}
    {hasAuditItems && <details className="artifact-technical-data"><summary>执行结果审计与详情</summary>{(result.completedItems?.length ?? 0) > 0 && <><h5>完成项</h5><ul>{result.completedItems!.map((item) => <li key={item}>{item}</li>)}</ul></>}{(result.failedItems?.length ?? 0) > 0 && <><h5>未完成项</h5><ul>{result.failedItems!.map((item) => <li key={item}>{item}</li>)}</ul></>}{(result.unverifiedClaims?.length ?? 0) > 0 && <><h5>未验证结论</h5><ul>{result.unverifiedClaims!.map((item) => <li key={item}>{item}</li>)}</ul></>}{(result.residualRisks?.length ?? 0) > 0 && <><h5>剩余风险</h5><ul>{result.residualRisks!.map((item) => <li key={item}>{item}</li>)}</ul></>}{(result.unresolvedQuestions?.length ?? 0) > 0 && <><h5>未决问题</h5><ul>{result.unresolvedQuestions!.map((item) => <li key={item}>{item}</li>)}</ul></>}</details>}
  </section>;
}


export function MissionWorkspaceModal({
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


export { MissionWorkspaceModal as MissionDialog };

import type {
  Conversation,
  MissionSnapshot,
  Turn,
} from "../../api/generated";
import type {
  MarkdownResearchSource,
} from "../../utils/markdownRenderer";

export interface MissionDraftRequest {
  requestId: string;
  idempotencyKey: string;
  objective: string;
}

export const missionTerminalStates = new Set([
  "COMPLETED",
  "PARTIALLY_COMPLETED",
  "FAILED",
  "CANCELLED",
]);
export const missionAnimatedStates = new Set(["PLANNING", "RUNNING", "SYNTHESIZING"]);
export const genericMissionObjective = /^(?:(?:开始|启动|发起)(?:一轮)?(?:深度)?(?:研究|调研)|(?:开始|启动|发起)\s*deep\s*research)\s*(?:任务|mission)?[。！!]?$/i;

export function normalizeMissionTitle(value: string): string {
  return value
    .trim()
    .replace(/^(?:请)?(?:调用|使用)\s*(?:deep-research|深度研究)\s*skill\s*(?:来|进行|做)?\s*[：:]?\s*/i, "")
    .replace(/^请(?:进行|做)?\s*深度研究\s*[：:]?\s*/i, "")
    .replace(/\s+/g, " ")
    .trim();
}

export function missionDisplayTitle(
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

export function missionStateLabel(state: string): string {
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

export function missionStateAccessibleLabel(mission: MissionSnapshot): string {
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


export function missionExecutionActivity(mission: MissionSnapshot, currentTask?: MissionSnapshot["tasks"][number]): string {
  if (mission.execution.recovering) return "正在恢复 Mission 执行状态";
  if (mission.state === "PLANNING") return "正在生成并校验执行计划";
  if (mission.state === "SYNTHESIZING") return "研究任务已完成，正在综合结论并生成报告";
  if (mission.state === "RUNNING" && currentTask) return `正在执行：${currentTask.title}`;
  if (mission.state === "RUNNING") return "正在准备下一项任务";
  return "";
}

export function missionTaskStateLabel(state: string): string {
  return {
    PLANNED: "已规划",
    WAITING_DEPENDENCY: "等待前置任务",
    READY: "等待执行",
    COMPLETED: "已完成",
    BLOCKED: "需要处理",
    CANCELLED: "已取消",
  }[state] ?? "状态待确认";
}

export function missionModeLabel(mode: MissionSnapshot["mode"]): string {
  return mode === "DEEP_RESEARCH" ? "深度调研" : "标准任务";
}

export function missionSourceFallbackTitle(source: string): string {
  try {
    return `网页来源 · ${new URL(source).hostname.replace(/^www\./, "")}`;
  } catch {
    return "网页来源";
  }
}

export function missionFailureMessage(mission: MissionSnapshot): string {
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

export type MissionArtifactReference = {
  artifactId: string;
  title?: string;
  mediaType?: string;
};

export type SourceReference = {
  sourceId: string;
  title: string;
  locator: string;
};

export type TaskOutcome = {
  taskId: string;
  status: "COMPLETED" | "FAILED" | "PARTIAL" | string;
};

export type AcceptanceOutcome = {
  criterionIndex: number;
  status: "SATISFIED" | "PARTIALLY_SATISFIED" | "UNSATISFIED" | string;
  taskIds?: string[];
};

export type SectionSource = {
  sectionHeading: string;
  sourceIds: string[];
};

export type ParsedMissionFinalResult = {
  schemaVersion?: string;
  unsupportedVersion?: boolean;
  directAnswer?: string;
  answerMarkdown?: string;
  completionKind?: string;
  degraded?: boolean;
  degradationReasons?: string[];
  affectedTaskIds?: string[];
  reportArtifactRef?: MissionArtifactReference;
  resultArtifactRef?: MissionArtifactReference;
  sourcesArtifactRef?: MissionArtifactReference;
  claimEvidenceArtifactRef?: MissionArtifactReference;
  unresolvedArtifactRef?: MissionArtifactReference;
  sources?: SourceReference[];
  taskOutcomes?: TaskOutcome[];
  acceptanceOutcomes?: AcceptanceOutcome[];
  sectionSources?: SectionSource[];
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

export function parseArtifactReference(value: unknown): MissionArtifactReference | undefined {
  if (typeof value !== "object" || value === null) return undefined;
  const record = value as Record<string, unknown>;
  if (typeof record.artifactId !== "string") return undefined;
  return {
    artifactId: record.artifactId,
    title: typeof record.title === "string" ? record.title : undefined,
    mediaType: typeof record.mediaType === "string" ? record.mediaType : undefined,
  };
}

export function parseMissionFinalResult(value: string | null): ParsedMissionFinalResult | null {
  if (!value) return null;
  try {
    const parsed = JSON.parse(value) as Record<string, unknown>;
    const strings = (field: string): string[] => Array.isArray(parsed[field])
      ? parsed[field].filter((item): item is string => typeof item === "string")
      : [];
    const schemaVersion = typeof parsed.schemaVersion === "string" ? parsed.schemaVersion : undefined;
    if (schemaVersion && ![
      "pa.mission-final-result/v1",
      "pa.mission-final-result/v2",
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
      answerMarkdown: typeof parsed.answerMarkdown === "string" ? parsed.answerMarkdown : undefined,
      completionKind: typeof parsed.completionKind === "string" ? parsed.completionKind : undefined,
      degraded: typeof parsed.degraded === "boolean" ? parsed.degraded : undefined,
      degradationReasons: strings("degradationReasons"),
      affectedTaskIds: strings("affectedTaskIds"),
      reportArtifactRef: parseArtifactReference(parsed.reportArtifactRef),
      resultArtifactRef: parseArtifactReference(parsed.resultArtifactRef),
      sourcesArtifactRef: parseArtifactReference(parsed.sourcesArtifactRef),
      claimEvidenceArtifactRef: parseArtifactReference(parsed.claimEvidenceArtifactRef),
      unresolvedArtifactRef: parseArtifactReference(parsed.unresolvedArtifactRef),
      sources: Array.isArray(parsed.sources)
        ? parsed.sources.flatMap((s) => {
            if (typeof s !== "object" || s === null) return [];
            const r = s as Record<string, unknown>;
            if (typeof r.sourceId === "string" && typeof r.title === "string" && typeof r.locator === "string") {
              return [{ sourceId: r.sourceId, title: r.title, locator: r.locator }];
            }
            return [];
          })
        : undefined,
      taskOutcomes: Array.isArray(parsed.taskOutcomes)
        ? parsed.taskOutcomes.flatMap((t) => {
            if (typeof t !== "object" || t === null) return [];
            const r = t as Record<string, unknown>;
            if (typeof r.taskId === "string" && typeof r.status === "string") {
              return [{ taskId: r.taskId, status: r.status }];
            }
            return [];
          })
        : undefined,
      acceptanceOutcomes: Array.isArray(parsed.acceptanceOutcomes)
        ? parsed.acceptanceOutcomes.flatMap((a) => {
            if (typeof a !== "object" || a === null) return [];
            const r = a as Record<string, unknown>;
            if (typeof r.criterionIndex === "number" && typeof r.status === "string") {
              const taskIds = Array.isArray(r.taskIds)
                ? r.taskIds.filter((id): id is string => typeof id === "string")
                : undefined;
              return [{ criterionIndex: r.criterionIndex, status: r.status, taskIds }];
            }
            return [];
          })
        : undefined,
      sectionSources: Array.isArray(parsed.sectionSources)
        ? parsed.sectionSources.flatMap((sec) => {
            if (typeof sec !== "object" || sec === null) return [];
            const r = sec as Record<string, unknown>;
            if (typeof r.sectionHeading === "string" && Array.isArray(r.sourceIds)) {
              return [{
                sectionHeading: r.sectionHeading,
                sourceIds: r.sourceIds.filter((id): id is string => typeof id === "string"),
              }];
            }
            return [];
          })
        : undefined,
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

export const missionDeliveryMarker = /^<!--\s*haifa-mission-delivery:\s*([a-zA-Z0-9._:-]+)\s*-->/;

export function missionDeliveryId(text: string): string | null {
  return missionDeliveryMarker.exec(text.trimStart())?.[1] ?? null;
}


export function parseResearchSourcesArtifact(value: string): MarkdownResearchSource[] {
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

export type MissionArtifactItem = {
  artifactId: string;
  title: string;
  fileName: string;
  mediaType: string;
};

export function artifactDisplayName(fileName: string): string {
  return {
    "research-report.md": "完整研究报告",
    "sources.json": "来源清单",
    "claim-evidence.json": "结论与证据关系",
    "unresolved-questions.json": "未决问题",
    "research-delivery.json": "交付清单",
    "mission-report.md": "完整任务报告",
    "mission-result.json": "任务执行结果",
  }[fileName] ?? fileName.replace(/[-_]+/g, " ").replace(/\.[^.]+$/, "");
}

export function missionArtifactItems(mission: MissionSnapshot): MissionArtifactItem[] {
  const result = parseMissionFinalResult(mission.finalResult);
  const references = [
    result?.reportArtifactRef,
    result?.resultArtifactRef,
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


export const degradationLabels: Record<string, string> = {
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


export type MissionPlanTask = MissionSnapshot["tasks"][number];

export function copyMissionPlanTasks(tasks: MissionPlanTask[]): MissionPlanTask[] {
  return tasks.map((task) => ({
    ...task,
    acceptanceCriteria: [...task.acceptanceCriteria],
    dependsOn: [...task.dependsOn],
    requiredSkillIds: [...task.requiredSkillIds],
  }));
}

export function missionPlanDependencyDepth(tasks: MissionPlanTask[]): number {
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

export function normalizeMissionPlanTasks(tasks: MissionPlanTask[]): MissionPlanTask[] {
  return tasks.map((task, index) => ({
    ...task,
    ordinal: index + 1,
    title: task.title.trim(),
    objective: task.objective.trim(),
    acceptanceCriteria: task.acceptanceCriteria.map((criterion) => criterion.trim()).filter(Boolean),
    state: "PLANNED",
  }));
}


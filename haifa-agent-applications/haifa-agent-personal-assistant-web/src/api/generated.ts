/* Generated from Server web.v1 OpenAPI. Run `npm run contract:generate`; do not edit. */

export interface Bootstrap {
  product: string;
  apiVersion: string;
  connection: string;
  caller: string;
  capabilities: Array<string>;
  assemblyDigest: string;
  defaultModelId: string;
  models: Array<Model>;
}

export interface CreateConversation {
  displayName: string;
  message: string;
  modelId?: string;
  modelSelection?: SelectModel;
  images?: Array<ImageInput>;
  audios?: Array<AudioInput>;
}

export interface SubmitMessage {
  message: string;
  images?: Array<ImageInput>;
  audios?: Array<AudioInput>;
}

export interface ImageInput {
  kind: "url" | "upload";
  url?: string;
  imageId?: string;
}

export interface UploadedImage {
  imageId: string;
  mediaType: string;
  sizeBytes: number;
  originalFilename: string;
  sha256: string;
}

export interface AudioInput {
  kind: "upload";
  audioId: string;
}

export interface UploadedAudio {
  audioId: string;
  mediaType: string;
  sizeBytes: number;
  originalFilename: string;
  sha256: string;
}

export interface RecommendedQuestions {
  questions: Array<string>;
}

export interface UpdateConversation {
  displayName?: string;
  status?: string;
}

export interface Model {
  id: string;
  modelGroupId: string;
  modelDisplayName: string;
  displayName: string;
  providerId: string;
  providerDisplayName: string;
  apiStyle: string;
  apiStyleDisplayName: string;
  availability: "AVAILABLE" | "UNAVAILABLE";
  unavailableReason: string;
  capabilities: Array<string>;
  contextWindow: number;
  maxOutputTokens: number;
  preferenceSchemaVersion: string;
  profileVersion: string;
  profileDigest: string;
  controls: ModelControls;
  recommendedPreferences: ModelPreferences;
}

export interface ModelConnection {
  connectionId: string;
  providerId: string;
  method: "API_KEY" | "EXTERNAL_LOGIN";
  status: "AUTHENTICATED" | "REAUTH_REQUIRED" | "RATE_LIMITED";
  accountLabel: string;
  expiresAtEpochMillis?: number | null;
  reasonCode?: string | null;
  apiKeySupported: boolean;
  externalLoginSupported: boolean;
  logoutSupported: boolean;
  unofficialLocalCompatibility: boolean;
}

export interface SaveModelApiKey {
  providerId: string;
  apiKey: string;
}

export interface ExternalLoginAttempt {
  attemptId: string;
  methodId: string;
  mode: "BROWSER" | "DEVICE_CODE";
  state: "CREATED" | "AUTHORIZING" | "WAITING_USER" | "EXCHANGING" | "SUCCEEDED" | "FAILED" | "CANCELLED" | "EXPIRED";
  verificationUri?: string | null;
  userCode?: string | null;
  expiresAtEpochMillis: number;
  reasonCode?: string | null;
}

export interface ModelControls {
  responseMode: ResponseModeControl;
  reasoningEffort: ReasoningEffortControl;
  responseLength: ResponseLengthControl;
  apiStyle: ApiStyleControl;
}

export interface ResponseModeControl {
  kind: "responseMode";
  visible: boolean;
  readOnly: boolean;
  allowedValues: Array<"RECOMMENDED" | "FAST" | "DEEP">;
  recommendedValue: "RECOMMENDED" | "FAST" | "DEEP";
  effectiveSummary: string;
  helpText: string;
}

export interface ReasoningEffortControl {
  kind: "reasoningEffort";
  visible: boolean;
  readOnly: boolean;
  allowedValues: Array<"LOW" | "MEDIUM" | "HIGH" | "MAX">;
  recommendedValue?: string | null;
  effectiveSummary: string;
  helpText: string;
}

export interface ResponseLengthControl {
  kind: "responseLength";
  visible: boolean;
  readOnly: boolean;
  allowedValues: Array<"RECOMMENDED" | "SHORT" | "STANDARD" | "LONG">;
  recommendedValue: "RECOMMENDED" | "SHORT" | "STANDARD" | "LONG";
  effectiveSummary: string;
  helpText: string;
}

export interface ApiStyleControl {
  kind: "apiStyle";
  visible: boolean;
  readOnly: boolean;
  allowedValues: Array<string>;
  recommendedValue: string;
  effectiveSummary: string;
  helpText: string;
}

export interface ModelPreferences {
  responseMode: "RECOMMENDED" | "FAST" | "DEEP";
  effort?: string | null;
  responseLength: "RECOMMENDED" | "SHORT" | "STANDARD" | "LONG";
}

export interface ModelSelection {
  model: Model;
  preferences: ModelPreferences;
  revision: number;
  available: boolean;
}

export interface SelectModel {
  modelBindingId: string;
  preferenceSchemaVersion: string;
  profileVersion: string;
  profileDigest: string;
  preferences: ModelPreferences;
}

export interface Conversation {
  id: string;
  displayName: string;
  status: string;
  activeRunId?: string | null;
  createdAt: string;
  lastActivityAt: string;
  revision: number;
  model: ModelSelection;
}

export interface Turn {
  id: string;
  role: string;
  runId?: string | null;
  sequence: number;
  text: string;
  images: Array<TurnImage>;
  audios: Array<TurnAudio>;
  createdAt: string;
}

export interface TurnImage {
  kind: string;
  url?: string | null;
  imageId?: string | null;
  mediaType?: string | null;
  sizeBytes: number;
  originalFilename: string;
}

export interface TurnAudio {
  audioId: string;
  mediaType: string;
  sizeBytes: number;
  originalFilename: string;
}

export interface Usage {
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  cachedInputTokens: number;
  modelCalls: number;
  toolCalls: number;
}

export interface ExecutionError {
  code: string;
  message: string;
  category: string;
  retryability: string;
  details: Record<string, unknown>;
  diagnosticId?: string | null;
  occurredAt: string;
}

export interface Run {
  id: string;
  conversationId: string;
  status: string;
  version: number;
  updatedAt: string;
  output?: string | null;
  resultSummary?: string | null;
  errorCode?: string | null;
  error?: ExecutionError | null;
  plan?: Plan | null;
  usage: Usage;
}

export interface Plan {
  id: string;
  objective: string;
  items: Array<Todo>;
  revision: number;
  updatedAt: string;
}

export interface Todo {
  id: string;
  title: string;
  priority: string;
  status: "PENDING" | "IN_PROGRESS" | "BLOCKED" | "COMPLETED" | "CANCELLED" | "SKIPPED";
  startedAt?: string | null;
  completedAt?: string | null;
}

export interface Activity {
  activityId: string;
  eventId: string;
  parentActivityId?: string | null;
  runId: string;
  kind: "MODEL" | "TOOL" | "SKILL" | "MCP";
  displayName: string;
  safeTargetSummary: string;
  status: string;
  requestedAt?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
  occurredAt: string;
  safeResultSummary: string;
  interactionRef?: string | null;
  version: number;
}

export interface Interaction {
  id: string;
  runId: string;
  conversationId: string;
  revision: number;
  kind: string;
  state: string;
  title: string;
  safePrompt: string;
  allowedActions: Array<string>;
  inputType: string;
  maximumCharacters: number;
  createdAt: string;
  expiresAt?: string | null;
}

export interface InteractionResponse {
  action: string;
  text: string;
}

export interface InteractionReceipt {
  responseId: string;
  interactionId: string;
  runId: string;
  status: string;
  interactionState: string;
  revision: number;
  runVersion: number;
}

export interface MemoryCandidate {
  id: string;
  kind: string;
  subjectKey: string;
  content: string;
  status: string;
  updatedAt: string;
  revision: number;
}

export interface Memory {
  id: string;
  version: number;
  kind: string;
  subjectKey: string;
  content: string;
  status: string;
  createdAt: string;
  updatedAt: string;
}

export interface RejectMemory {
  reason: string;
}

export interface InvalidateMemory {
  reason: string;
}

export interface CreateMission {
  conversationId: string;
  objective: string;
  acceptanceCriteria: Array<string>;
  constraints?: MissionConstraints;
  mode?: "STANDARD" | "DEEP_RESEARCH";
  selectedSkillId?: string;
  researchBrief?: ResearchBrief;
}

export interface ResearchBrief {
  question: string;
  scope?: string;
  timeRange?: string;
  region?: string;
  audience?: string;
  sourcePreferences: Array<string>;
  exclusions: Array<string>;
  deliveryFormat?: string;
}

export interface MissionConstraints {
  maxTasks?: number;
  maxDependencyDepth?: number;
  deadlineAt?: string;
}

export interface ReplaceMissionPlan {
  plan?: MissionPlan;
  regenerate?: boolean;
}

export interface MissionPlan {
  tasks: Array<MissionTask>;
}

export interface MissionTask {
  taskId: string;
  ordinal: number;
  title: string;
  objective: string;
  acceptanceCriteria: Array<string>;
  dependsOn: Array<string>;
  taskType: "GENERAL" | "RESEARCH";
  requiredSkillIds: Array<string>;
  resultSchemaId: string;
  resultSchemaVersion: string;
  state: "PLANNED" | "WAITING_DEPENDENCY" | "READY" | "COMPLETED" | "BLOCKED" | "CANCELLED";
}

export interface MissionPlanRevision {
  revision: number;
  schemaId: string;
  schemaVersion: string;
  tasks: Array<MissionTask>;
  plannerSessionId: string | null;
  plannerRunId: string | null;
  createdAt: string;
}

export interface MissionSnapshot {
  schemaVersion: string;
  missionId: string;
  conversationId: string;
  modelBinding: MissionModelBinding;
  objective: string;
  acceptanceCriteria: Array<string>;
  constraints: MissionConstraints;
  mode: "STANDARD" | "DEEP_RESEARCH";
  researchBrief: ResearchBrief | null;
  selectedSkillId: string | null;
  selectedSkillBinding: string | null;
  state: "PLANNING" | "WAITING_CONFIRMATION" | "RUNNING" | "WAITING_USER" | "SYNTHESIZING" | "COMPLETED" | "PARTIALLY_COMPLETED" | "FAILED" | "CANCELLED";
  plan: MissionPlanRevision | null;
  tasks: Array<MissionTask>;
  blocker: string | null;
  artifacts: Array<string>;
  sources: Array<string>;
  finalResult: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
  confirmedAt: string | null;
  finishedAt: string | null;
  pollAfterMs: number;
  execution: MissionExecution;
}

export interface MissionModelBinding {
  modelId: string;
  modelDisplayName: string;
  providerId: string;
  providerDisplayName: string;
  configurationDigest: string;
}

export interface MissionExecution {
  dispatcherStatus: "READY" | "NOT_READY" | "NOT_CONFIGURED";
  recovering: boolean;
  allTasksSettled: boolean;
  completedTasks: number;
  blockedTasks: number;
  currentTaskId: string | null;
  latestAttempt: MissionAttempt | null;
}

export interface MissionAttempt {
  taskId: string;
  attemptNo: number;
  state: "CREATED" | "DISPATCH_PENDING" | "BOUND" | "SETTLEMENT_PENDING" | "SETTLED" | "FAILED" | "CANCELLED" | "OUTCOME_UNKNOWN";
  sessionId: string | null;
  runId: string | null;
  failureCode: string | null;
  updatedAt: string;
}

export interface MissionPage {
  items: Array<MissionSnapshot>;
  nextCursor: string | null;
}

export interface CancelMission {
  reason?: string;
}

export interface StreamEvent {
  eventId: string;
  type: string;
  runId: string;
  occurredAt: string;
  value: string;
  activity?: Activity | null;
  source: "durable" | "transient" | "snapshot";
  sequence: number;
}

export interface ApiError {
  code: string;
  message: string;
  correlationId: string;
  diagnosticId: string;
  actions: Array<string>;
}

export type OperationId = "bootstrap" | "listModels" | "listModelConnections" | "saveModelApiKey" | "startCodexBrowserAttempt" | "getCodexBrowserAttempt" | "cancelCodexBrowserAttempt" | "deleteModelConnection" | "uploadImage" | "getUploadedImageContent" | "uploadAudio" | "listConversations" | "createConversation" | "getConversation" | "updateConversation" | "listTurns" | "selectConversationModel" | "submitMessage" | "recommendQuestions" | "getRun" | "cancelRun" | "listSafeActivities" | "getPendingInteraction" | "respondToInteraction" | "streamRun" | "listMemoryCandidates" | "approveMemoryCandidate" | "rejectMemoryCandidate" | "listMemories" | "invalidateMemory" | "listMissions" | "createMission" | "getMission" | "getMissionSnapshot" | "downloadMissionArtifact" | "replaceMissionPlan" | "confirmMission" | "cancelMission" | "retryMissionTask";

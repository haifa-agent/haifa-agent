// Generated from coding-presentation.schema.json; framework/runtime independent.
export type Target = Readonly<{
  sessionId: string;
  runId: string | null;
  interactionId: string | null;
  targetRevision: number | null;
}>;
export type Action = Readonly<{
  kind: "submit" | "cancel" | "respond" | "allowForSession" | "resetConsent";
  target: Target;
  choice: string | null;
  available: boolean;
  reason: string | null;
}>;
export type InputOption = Readonly<{
  id: string;
  label: string;
  description: string | null;
}>;
export type Input = Readonly<{
  type: string;
  maximumCharacters: number;
  minimumSelections: number;
  maximumSelections: number;
  options: ReadonlyArray<InputOption>;
  questions: ReadonlyArray<Question>;
}>;
export type ReviewFact = Readonly<{
  label: string;
  value: string;
}>;
export type Review = Readonly<{
  purpose: string;
  contentType: string;
  content: string;
  environment: ReadonlyArray<ReviewFact>;
  technical: ReadonlyArray<ReviewFact>;
  risk: string | null;
  targetComplete: boolean;
  contentTruncated: boolean;
}>;
export type Interaction = Readonly<{
  id: string;
  revision: number;
  kind: string;
  input: Input;
  review: Review | null;
  actions: ReadonlyArray<Action>;
}>;
export type ToolOutput = Readonly<{
  text: string;
  truncated: boolean;
  byteCount: number;
  lineCount: number;
}>;
export type ToolDetails = Readonly<{
  name: string;
  intent: string;
  reasonCode: string;
  resultRef: string;
  processState: string | null;
  exitCode: number | null;
  startedAtEpochMillis: number | null;
  durationMillis: number | null;
  output: ToolOutput | null;
}>;
export type Entry = Readonly<{
  id: string;
  kind: "message" | "tool" | "interaction" | "notice";
  runId: string | null;
  role: string;
  title: string;
  text: string;
  status: string;
  sequence: number | null;
  truncated: boolean;
  interaction: Interaction | null;
  occurredAtEpochMillis: number | null;
  toolCallId: string | null;
  interactionId: string | null;
  noticeCode: string | null;
  diagnosticId: string | null;
  toolDetails?: ToolDetails | null;
}>;
export type Run = Readonly<{
  id: string;
  status: string;
  revision: number;
}>;
export type Session = Readonly<{
  id: string;
  title: string;
  revision: number;
  status: string;
  run: Run | null;
  modelId: string;
  modelName: string;
  context: string;
  modelRevision: number;
  modelAvailable: boolean;
}>;
export type Scope = Readonly<{
  viewId: string;
  hostIncarnation: string;
  clientId: string;
  sessionId: string;
  schemaVersion: 1;
  source: "native" | "browser";
}>;
export type History = Readonly<{
  hasEarlier: boolean;
  beforeSequence: number | null;
  complete: boolean;
}>;
export type Live = Readonly<{
  runId: string;
  generationId: string;
  sequence: number;
  text: string;
  truncated: boolean;
  modelCallId: string;
  physicalAttempt: number;
}>;
export type Consent = Readonly<{
  state: "ASK" | "PENDING" | "ACTIVE";
  expiresAtEpochMillis: number | null;
  available: boolean;
}>;
export type Snapshot = Readonly<{
  kind: "snapshot";
  scope: Scope;
  revision: number;
  hydration: "ready" | "refreshing";
  session: Session;
  entries: ReadonlyArray<Entry>;
  history: History;
  live: Live | null;
  consent: Consent;
  actions: ReadonlyArray<Action>;
  notices: ReadonlyArray<string>;
  activity?: ActivityHead | null;
}>;
export type Update = Readonly<{
  kind: "update";
  viewId: string;
  baseRevision: number;
  revision: number;
  replacement: Snapshot;
}>;
export type Resync = Readonly<{
  kind: "resync";
  viewId: string;
  reason: string;
}>;
export type Question = Readonly<{
  header: string;
  question: string;
  multiple: boolean;
  allowCustom: boolean;
  options: ReadonlyArray<InputOption>;
}>;
export type HistoryPage = Readonly<{
  sessionId: string;
  entries: ReadonlyArray<Entry>;
  history: History;
}>;
export type CommandTarget = Readonly<{
  projectId: string | null;
  sessionId: string | null;
  runId: string | null;
  interactionId: string | null;
  targetRevision: number | null;
}>;
export type Command = Readonly<{
  kind: "command";
  operation: "create" | "submit" | "steer" | "followUp" | "restore" | "respond" | "cancel" | "rename" | "archive" | "delete" | "compact" | "export" | "selectModel" | "shellPlan" | "shellStart" | "shellGet" | "shellCancel" | "shellDiscard" | "workspaceRevoke" | "resourcesReload" | "resetConsent" | "allowForSession" | "prepareApproval" | "logout" | "shellExecute" | "reconcile";
  clientRequestId: string;
  idempotencyKey: string | null;
  target: CommandTarget;
  text: string | null;
  optionId: string | null;
  executionId: string | null;
  selections: ReadonlyArray<string>;
}>;
export type Receipt = Readonly<{
  clientRequestId: string;
  disposition: "accepted" | "rejected" | "unknown";
  operationId: string | null;
  runId: string | null;
  reasonCode: string | null;
}>;
export type Preview = Readonly<{
  sessionId: string;
  runId: string;
  toolCallId: string;
  channel: "STDOUT" | "STDERR";
  text: string;
  outputTruncated: boolean;
  previewDropped: boolean;
}>;
export type Subscribed = Readonly<{
  subscriptionId: string;
  snapshot: Snapshot;
}>;
export type ConnectionInfo = Readonly<{
  hostIncarnation: string;
  clientId: string;
  projectId: string;
  protocolVersion: string;
  displayName: string;
  capabilities: ReadonlyArray<string>;
  source: "native" | "browser" | "embedded";
}>;
export type SessionSummary = Readonly<{
  sessionId: string;
  projectId: string;
  title: string;
  status: string;
  runId: string | null;
  runStatus: string | null;
  queuedCount: number;
  lastActivityAtEpochMillis: number;
  revision: number;
}>;
export type RunUsage = Readonly<{
  inputTokens: number;
  outputTokens: number;
  cachedInputTokens: number;
  modelCalls: number;
  toolCalls: number;
  childRuns: number;
  costMinorUnits: number;
  wallTimeMillis: number;
}>;
export type Artifact = Readonly<{
  id: string;
  type: string;
  version: string;
  title: string;
}>;
export type RunResult = Readonly<{
  outcome: string;
  summary: string;
  outputSchemaId: string;
  outputSchemaVersion: string;
  structuredOutputDisplay: string;
  artifacts: ReadonlyArray<Artifact>;
  warnings: ReadonlyArray<string>;
  truncated: boolean;
}>;
export type RunFact = Readonly<{
  id: string;
  status: string;
  revision: number;
  updatedAtEpochMillis: number;
  output: string | null;
  errorCode: string | null;
  errorMessage: string | null;
  diagnosticId: string | null;
  terminationCode: string | null;
  terminationDescription: string | null;
  usage: RunUsage;
  result: RunResult | null;
  truncated: boolean;
}>;
export type RunOutcome = Readonly<{
  runId: string;
  protocolStatus: string;
  diagnosticCodes: ReadonlyArray<string>;
}>;
export type ModelControl = Readonly<{
  kind: string;
  visible: boolean;
  readOnly: boolean;
  allowedValues: ReadonlyArray<string>;
  recommendedValue: string;
  effectiveSummary: string;
}>;
export type ImageProfile = Readonly<{
  allowedSources: ReadonlyArray<string>;
  supportedMediaTypes: ReadonlyArray<string>;
  maxImagesPerRequest: number;
  maxBytesPerItem: number;
  maxTotalBytes: number;
  maxUrlCharacters: number;
  detailSupported: boolean;
  allowedDetails: ReadonlyArray<string>;
}>;
export type ModelChoice = Readonly<{
  id: string;
  displayName: string;
  providerId: string;
  providerDisplayName: string;
  capabilities: ReadonlyArray<string>;
  contextWindow: number;
  maxOutputTokens: number;
  connection: string;
  bindingAvailability: string;
  runtimeHealth: string;
  runScope: string;
  unavailableReason: string | null;
  responseMode: ModelControl;
  reasoningEffort: ModelControl;
  recommendedResponseMode: string;
  recommendedEffort: string | null;
  imageInput: ImageProfile | null;
}>;
export type ModelSelection = Readonly<{
  model: ModelChoice;
  revision: number;
  available: boolean;
}>;
export type QueuedMessage = Readonly<{
  id: string;
  sessionId: string;
  summary: string;
  sequence: number;
  revision: number;
}>;
export type Attachment = Readonly<{
  id: string;
  mediaType: string;
  filename: string;
}>;
export type RestoredMessage = Readonly<{
  id: string;
  sessionId: string;
  text: string;
  attachments: ReadonlyArray<Attachment>;
  revision: number;
}>;
export type ShellPlan = Readonly<{
  token: string;
  sessionId: string;
  safeCommand: string;
  includeInContext: boolean;
  state: string;
  reasonCode: string;
}>;
export type ShellResult = Readonly<{
  processState: string;
  exitCode: number | null;
  safeSummary: string;
  outputReference: string | null;
  truncated: boolean;
  includedInContext: boolean;
}>;
export type ShellExecution = Readonly<{
  id: string;
  sessionId: string;
  acceptedAtEpochMillis: number;
  state: string;
  cancellationRequested: boolean;
  result: ShellResult | null;
  errorCode: string | null;
}>;
export type Workspace = Readonly<{
  id: string;
  safeDisplayName: string;
  mode: string;
  status: string;
  revocable: boolean;
}>;
export type Compaction = Readonly<{
  summaryReference: string | null;
  summaryVersion: number;
  sourceMessageCount: number;
  estimatedTokens: number;
  throughSequence: number;
}>;
export type Export = Readonly<{
  logicalPath: string;
  messageCount: number;
  schemaVersion: string;
}>;
export type AuthenticationMethod = Readonly<{
  id: string;
  type: string;
  available: boolean;
  unavailableReason: string | null;
}>;
export type AuthenticationProvider = Readonly<{
  id: string;
  displayName: string;
  category: string;
  connected: boolean;
  recommended: boolean;
  current: boolean;
  accountLabel: string | null;
  planLabel: string | null;
  methods: ReadonlyArray<AuthenticationMethod>;
  unavailableReason: string | null;
}>;
export type AuthenticationConnection = Readonly<{
  id: string;
  providerId: string;
  method: string;
  status: string;
  accountLabel: string;
  planType: string | null;
  resetAtEpochMillis: number | null;
  unofficialLocalCompatibility: boolean;
}>;
export type Authentication = Readonly<{
  connectionRequired: boolean;
  providers: ReadonlyArray<AuthenticationProvider>;
  connections: ReadonlyArray<AuthenticationConnection>;
  localManagementCommand: string | null;
}>;
export type QuestionAnswer = Readonly<{
  header: string;
  optionIds: ReadonlyArray<string>;
  customText: string | null;
}>;
export type CommandOptions = Readonly<{
  approved: boolean;
  includeInContext: boolean;
  answers: ReadonlyArray<QuestionAnswer>;
}>;
export type CommandRequest = Readonly<{
  command: Command;
  options: CommandOptions;
}>;
export type InteractionReceipt = Readonly<{
  interactionId: string;
  runId: string;
  status: string;
  state: string;
  interactionRevision: number;
  runRevision: number;
}>;
export type CommandResult = Readonly<{
  receipt: Receipt;
  session: SessionSummary | null;
  queuedMessage: QueuedMessage | null;
  restoredMessage: RestoredMessage | null;
  shellPlan: ShellPlan | null;
  shellExecution: ShellExecution | null;
  shellResult: ShellResult | null;
  compaction: Compaction | null;
  export: Export | null;
  modelSelection: ModelSelection | null;
  interactionReceipt: InteractionReceipt | null;
  interaction: Entry | null;
}>;
export type Query = Readonly<{
  operation: "sessions" | "run" | "outcome" | "models" | "queuedMessages" | "shellGet" | "shellList" | "workspaces" | "logicalPaths" | "resources" | "configuration" | "authentication";
  target: CommandTarget;
  search: string | null;
  limit: number;
  executionId: string | null;
}>;
export type QueryResult = Readonly<{
  sessions: ReadonlyArray<SessionSummary>;
  run: RunFact | null;
  outcome: RunOutcome | null;
  models: ReadonlyArray<ModelChoice>;
  queuedMessages: ReadonlyArray<QueuedMessage>;
  shellExecutions: ReadonlyArray<ShellExecution>;
  workspaces: ReadonlyArray<Workspace>;
  lines: ReadonlyArray<string>;
  authentication: Authentication | null;
  truncated: boolean;
}>;
export type PairingRequest = Readonly<{
  challenge: string;
  hostIncarnation: string;
}>;
export type BrowserGrant = Readonly<{
  grant: string;
  clientId: string;
  hostIncarnation: string;
  expiresAtEpochMillis: number;
}>;
export type BrowserInfo = Readonly<{
  hostIncarnation: string;
  protocolVersion: "1";
  browserAuthorization: "browser-grant-v1";
  displayName: string;
}>;
export type ActivityHead = Readonly<{
  runId: string;
  sequence: number;
}>;
export type ActivityRequest = Readonly<{
  sessionId: string;
  runId: string;
  afterSequence: number;
}>;
export type ActivityPage = Readonly<{
  sessionId: string;
  runId: string;
  afterSequence: number;
  nextSequence: number;
  hasMore: boolean;
  entries: ReadonlyArray<Entry>;
}>;
export type TerminalContext = Readonly<{
  workingDirectory: string;
  gitBranch: string;
  worktree: string;
  worktreeBranch: string;
  executionDirectory: string;
  localManagementCommand: string;
}>;
export type TerminalSession = Readonly<{
  summary: SessionSummary;
  activeRun: Run | null;
  model: ModelSelection;
  activeTask: string | null;
  profile: string;
}>;
export type CodingEnvelope = Snapshot | Update | Resync;

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
  operation: "create" | "submit" | "steer" | "followUp" | "restore" | "respond" | "cancel" | "rename" | "archive" | "delete" | "compact" | "export" | "selectModel" | "shellPlan" | "shellStart" | "shellGet" | "shellCancel" | "shellDiscard" | "workspaceRevoke" | "resourcesReload" | "resetConsent" | "allowForSession" | "prepareApproval";
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
export type CodingEnvelope = Snapshot | Update | Resync;

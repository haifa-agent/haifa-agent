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
  images?: Array<ImageInput>;
}

export interface SubmitMessage {
  message: string;
  images?: Array<ImageInput>;
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

export interface RecommendedQuestions {
  questions: Array<string>;
}

export interface UpdateConversation {
  displayName?: string;
  status?: string;
}

export interface Model {
  id: string;
  displayName: string;
  providerId: string;
  providerDisplayName: string;
  capabilities: Array<string>;
  contextWindow: number;
}

export interface ModelSelection {
  model: Model;
  revision: number;
  available: boolean;
}

export interface SelectModel {
  modelId: string;
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
  usage: Usage;
}

export interface Activity {
  activityId: string;
  runId: string;
  kind: "MODEL" | "TOOL" | "SKILL" | "MCP";
  displayName: string;
  safeTargetSummary: string;
  status: string;
  startedAt: string;
  completedAt?: string | null;
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
  expiresAt: string;
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
}

export type OperationId = "bootstrap" | "listModels" | "uploadImage" | "listConversations" | "createConversation" | "getConversation" | "updateConversation" | "listTurns" | "selectConversationModel" | "submitMessage" | "recommendQuestions" | "getRun" | "cancelRun" | "listSafeActivities" | "getPendingInteraction" | "respondToInteraction" | "streamRun" | "listMemoryCandidates" | "approveMemoryCandidate" | "rejectMemoryCandidate" | "listMemories" | "invalidateMemory";

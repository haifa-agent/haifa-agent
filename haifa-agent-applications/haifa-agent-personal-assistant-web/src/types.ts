export type ConversationStatus =
  | "idle"
  | "running"
  | "waiting_approval"
  | "waiting_interaction"
  | "completed"
  | "failed"
  | "cancelled";

export type StepState = "completed" | "active" | "pending" | "blocked" | "failed";

export interface ConversationSummary {
  id: string;
  title: string;
  preview: string;
  status: ConversationStatus;
  updatedLabel: string;
  group: "今天" | "最近 7 天" | "更早";
  archived: boolean;
  revision: number;
}

export interface Message {
  id: string;
  role: "user" | "assistant";
  content: string;
  time: string;
  state?: "streaming" | "committed" | "queued" | "applied";
}

export interface TaskStep {
  id: string;
  title: string;
  state: StepState;
}

export interface ActivityItem {
  id: string;
  title: string;
  detail?: string;
  time: string;
  kind: "status" | "tool" | "skill" | "mcp" | "approval" | "artifact";
  state: "completed" | "running" | "waiting" | "failed";
}

export interface Artifact {
  id: string;
  version: number;
  name: string;
  mediaType: string;
  sizeLabel: string;
  description: string;
  createdAt: string;
  preview: string;
  previewMode: "safe_inline" | "download_only";
}

export interface TokenUsage {
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  cacheReadInputTokens: number;
  modelCalls: number;
  providerReportedModelCalls: number;
  updatedAt: string;
}

export interface ApprovalInteraction {
  id: string;
  type: "approval";
  title: string;
  action: string;
  target: string;
  commandSummary: string;
  risk: string;
  boundary: string;
  revision: number;
  state: "pending" | "approved" | "rejected";
}

export interface ClarificationInteraction {
  id: string;
  type: "clarification";
  title: string;
  detail: string;
  options: Array<{ id: string; label: string }>;
  revision: number;
  state: "pending" | "answered";
}

export type Interaction = ApprovalInteraction | ClarificationInteraction;

export interface RunView {
  id: string;
  title: string;
  status: ConversationStatus;
  elapsed: string;
  startedAt: string;
  steps: TaskStep[];
  activity: ActivityItem[];
  interaction?: Interaction;
}

export interface ConversationDetail {
  summary: ConversationSummary;
  messages: Message[];
  run?: RunView;
  artifacts: Artifact[];
  tokenUsage?: TokenUsage;
}

export interface Preferences {
  assistantName: string;
  responseStyle: "practical" | "warm" | "professional";
  detailLevel: number;
  locale: "zh-CN" | "en-US";
  proactiveSuggestions: boolean;
  memoryEnabled: boolean;
  revision: number;
}

export interface MemoryCandidate {
  id: string;
  content: string;
  reason: string;
  source: string;
  updatedAt: string;
  revision: number;
}

export interface MemoryRecord {
  id: string;
  content: string;
  category: string;
  source: string;
  updatedAt: string;
  active: boolean;
  revision: number;
}

export interface BootstrapSnapshot {
  conversations: ConversationSummary[];
  details: Record<string, ConversationDetail>;
  preferences: Preferences;
  memoryCandidates: MemoryCandidate[];
  memories: MemoryRecord[];
}

export type MemoryTab = "preferences" | "confirmed" | "candidates" | "privacy";
export type DeliveryMode = "follow_up" | "steer";

export interface UiState extends BootstrapSnapshot {
  selectedConversationId: string;
  searchQuery: string;
  composer: string;
  deliveryMode: DeliveryMode;
  taskPanelOpen: boolean;
  sidebarOpen: boolean;
  memoryDialogOpen: boolean;
  memoryTab: MemoryTab;
  artifactPreviewId: string | null;
  sessionMenuId: string | null;
  toast: string | null;
}

export type AppAction =
  | { type: "hydrate"; snapshot: BootstrapSnapshot }
  | { type: "selectConversation"; id: string }
  | { type: "newConversation" }
  | { type: "setSearch"; value: string }
  | { type: "setComposer"; value: string }
  | { type: "setDeliveryMode"; value: DeliveryMode }
  | { type: "submitMessage"; text: string; now: string }
  | { type: "appendAssistantDelta"; conversationId: string; delta: string }
  | { type: "completeAssistantReply"; conversationId: string }
  | { type: "toggleTaskPanel" }
  | { type: "toggleSidebar"; open?: boolean }
  | { type: "openMemory"; tab?: MemoryTab }
  | { type: "closeMemory" }
  | { type: "setMemoryTab"; tab: MemoryTab }
  | { type: "updatePreferences"; value: Preferences }
  | { type: "approveMemoryCandidate"; id: string }
  | { type: "rejectMemoryCandidate"; id: string }
  | { type: "toggleMemory"; id: string }
  | { type: "approveInteraction"; approved: boolean }
  | { type: "answerClarification"; optionId: string }
  | { type: "completeApprovedRun" }
  | { type: "cancelRun" }
  | { type: "openArtifact"; id: string }
  | { type: "closeArtifact" }
  | { type: "toggleSessionMenu"; id: string | null }
  | { type: "renameConversation"; id: string; title: string }
  | { type: "archiveConversation"; id: string }
  | { type: "clearToast" };

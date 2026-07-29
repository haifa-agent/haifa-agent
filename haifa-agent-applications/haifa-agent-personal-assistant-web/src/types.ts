import type {
  Activity,
  Bootstrap,
  Conversation,
  Interaction,
  Memory,
  MemoryCandidate,
  Run,
  StreamEvent,
  Turn,
} from "./api/generated";

export type ConnectionState =
  | "connecting"
  | "connected"
  | "reconnecting"
  | "disconnected";

export interface CommandState {
  id: string;
  label: string;
}

export interface UiState {
  bootstrap: Bootstrap | null;
  conversations: Conversation[];
  selectedConversationId: string | null;
  selectedConversation: Conversation | null;
  turns: Turn[];
  run: Run | null;
  activities: Activity[];
  interaction: Interaction | null;
  memoryCandidates: MemoryCandidate[];
  memories: Memory[];
  streamDraft: string;
  streamSequences: {
    durable: number;
    transient: number;
  };
  connection: ConnectionState;
  search: string;
  composer: string;
  sidebarOpen: boolean;
  activityOpen: boolean;
  memoryOpen: boolean;
  showArchived: boolean;
  loading: boolean;
  pending: CommandState | null;
  error: string | null;
}

export type AppAction =
  | {
      type: "bootstrapLoaded";
      bootstrap: Bootstrap;
      conversations: Conversation[];
      memoryCandidates: MemoryCandidate[];
      memories: Memory[];
    }
  | { type: "conversationsLoaded"; conversations: Conversation[] }
  | { type: "selectConversation"; conversationId: string | null }
  | { type: "conversationLoaded"; conversation: Conversation }
  | { type: "turnsLoaded"; turns: Turn[] }
  | { type: "runLoaded"; run: Run | null }
  | { type: "activitiesLoaded"; activities: Activity[] }
  | { type: "interactionLoaded"; interaction: Interaction | null }
  | { type: "memoryLoaded"; candidates: MemoryCandidate[]; memories: Memory[] }
  | { type: "streamEvent"; event: StreamEvent }
  | { type: "setConnection"; connection: ConnectionState }
  | { type: "setSearch"; value: string }
  | { type: "setComposer"; value: string }
  | { type: "toggleSidebar"; open?: boolean }
  | { type: "toggleActivity"; open?: boolean }
  | { type: "toggleMemory"; open?: boolean }
  | { type: "toggleArchived" }
  | { type: "commandStarted"; command: CommandState }
  | { type: "commandFinished" }
  | { type: "loading"; value: boolean }
  | { type: "error"; message: string | null };

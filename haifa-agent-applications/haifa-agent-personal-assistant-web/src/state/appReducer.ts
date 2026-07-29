import type { Activity, Conversation, Run } from "../api/generated";
import type { AppAction, UiState } from "../types";

export const initialState: UiState = {
  bootstrap: null,
  conversations: [],
  selectedConversationId: null,
  selectedConversation: null,
  turns: [],
  run: null,
  activities: [],
  interaction: null,
  memoryCandidates: [],
  memories: [],
  streamDraft: "",
  streamSequence: 0,
  connection: "connecting",
  search: "",
  composer: "",
  sidebarOpen: false,
  activityOpen: false,
  memoryOpen: false,
  showArchived: false,
  loading: true,
  pending: null,
  error: null,
};

function newestConversation(current: Conversation | null, next: Conversation): Conversation {
  if (!current || current.id !== next.id || next.revision >= current.revision) return next;
  return current;
}

function newestRun(current: Run | null, next: Run | null): Run | null {
  if (!next) return null;
  if (!current || current.id !== next.id || next.version >= current.version) return next;
  return current;
}

function mergeActivities(current: Activity[], incoming: Activity[]): Activity[] {
  const values = new Map(current.map((activity) => [activity.activityId, activity]));
  for (const activity of incoming) {
    const previous = values.get(activity.activityId);
    if (!previous || activity.version >= previous.version) values.set(activity.activityId, activity);
  }
  return [...values.values()].sort(
    (left, right) =>
      new Date(left.startedAt).getTime() - new Date(right.startedAt).getTime(),
  );
}

export function appReducer(state: UiState, action: AppAction): UiState {
  switch (action.type) {
    case "bootstrapLoaded": {
      const selected =
        state.selectedConversationId &&
        action.conversations.some((value) => value.id === state.selectedConversationId)
          ? state.selectedConversationId
          : (action.conversations.find((value) => value.status !== "ARCHIVED")?.id ?? null);
      return {
        ...state,
        bootstrap: action.bootstrap,
        conversations: action.conversations,
        memoryCandidates: action.memoryCandidates,
        memories: action.memories,
        selectedConversationId: selected,
        connection: "connected",
        loading: false,
        error: null,
      };
    }
    case "conversationsLoaded":
      return {
        ...state,
        conversations: action.conversations,
        selectedConversation:
          action.conversations.find(
            (conversation) => conversation.id === state.selectedConversationId,
          ) ?? state.selectedConversation,
      };
    case "selectConversation":
      return {
        ...state,
        selectedConversationId: action.conversationId,
        selectedConversation: null,
        turns: [],
        run: null,
        activities: [],
        interaction: null,
        streamDraft: "",
        streamSequence: 0,
        sidebarOpen: false,
        error: null,
      };
    case "conversationLoaded": {
      const selected = newestConversation(state.selectedConversation, action.conversation);
      return {
        ...state,
        selectedConversation: selected,
        conversations: state.conversations.map((value) =>
          value.id === selected.id && selected.revision >= value.revision ? selected : value,
        ),
      };
    }
    case "turnsLoaded": {
      const turns = [...action.turns].sort((a, b) => a.sequence - b.sequence);
      const streamCommitted = Boolean(
        state.run &&
          turns.some(
            (turn) =>
              turn.runId === state.run?.id && turn.role.toLowerCase() === "assistant",
          ),
      );
      return {
        ...state,
        turns,
        streamDraft: streamCommitted ? "" : state.streamDraft,
      };
    }
    case "runLoaded": {
      const next = newestRun(state.run, action.run);
      const changed = Boolean(next && state.run && next.id !== state.run.id);
      return {
        ...state,
        run: next,
        activities: changed ? [] : state.activities,
        interaction: changed ? null : state.interaction,
        streamSequence: changed ? 0 : state.streamSequence,
        streamDraft:
          !next || changed || ["FAILED", "CANCELLED", "TIMEOUT"].includes(next.status)
            ? ""
            : state.streamDraft,
      };
    }
    case "activitiesLoaded":
      return { ...state, activities: mergeActivities(state.activities, action.activities) };
    case "interactionLoaded":
      return {
        ...state,
        interaction:
          !state.interaction ||
          !action.interaction ||
          action.interaction.id !== state.interaction.id ||
          action.interaction.revision >= state.interaction.revision
            ? action.interaction
            : state.interaction,
      };
    case "memoryLoaded":
      return {
        ...state,
        memoryCandidates: action.candidates,
        memories: action.memories,
      };
    case "streamEvent":
      if (action.event.sequence <= state.streamSequence) return state;
      return {
        ...state,
        streamSequence: action.event.sequence,
        streamDraft:
          action.event.type === "answer.delta"
            ? state.streamDraft + action.event.value
            : state.streamDraft,
        activities: action.event.activity
          ? mergeActivities(state.activities, [action.event.activity])
          : state.activities,
      };
    case "setConnection":
      return { ...state, connection: action.connection };
    case "setSearch":
      return { ...state, search: action.value };
    case "setComposer":
      return { ...state, composer: action.value };
    case "toggleSidebar":
      return {
        ...state,
        sidebarOpen: action.open ?? !state.sidebarOpen,
        activityOpen: action.open ? false : state.activityOpen,
      };
    case "toggleActivity":
      return {
        ...state,
        activityOpen: action.open ?? !state.activityOpen,
        sidebarOpen: action.open ? false : state.sidebarOpen,
      };
    case "toggleMemory":
      return { ...state, memoryOpen: action.open ?? !state.memoryOpen };
    case "toggleArchived":
      return { ...state, showArchived: !state.showArchived };
    case "commandStarted":
      return { ...state, pending: action.command, error: null };
    case "commandFinished":
      return { ...state, pending: null };
    case "loading":
      return { ...state, loading: action.value };
    case "error":
      return { ...state, error: action.message, connection: action.message ? "disconnected" : state.connection };
  }
}

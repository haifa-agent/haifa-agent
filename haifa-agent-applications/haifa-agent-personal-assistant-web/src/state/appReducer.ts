import type { Activity, Conversation, Run } from "../api/generated";
import type { AppAction, ToolOutputPreviewState, UiState } from "../types";

const MAX_PREVIEW_BYTES = 16 * 1024;
const MAX_PREVIEW_LINES = 200;
const terminalActivityStatuses = new Set(["SUCCEEDED", "COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT"]);

export const initialState: UiState = {
  bootstrap: null,
  conversations: [],
  selectedConversationId: null,
  selectedConversation: null,
  turns: [],
  run: null,
  activities: [],
  toolPreviews: {},
  interaction: null,
  interactionError: null,
  memories: [],
  streamDraft: "",
  outputPhase: "idle",
  streamSequences: { durable: 0, transient: 0 },
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

function earlier(left?: string | null, right?: string | null): string | undefined {
  if (!left) return right ?? undefined;
  if (!right) return left;
  return new Date(left).getTime() <= new Date(right).getTime() ? left : right;
}

function mergeActivity(previous: Activity, next: Activity): Activity {
  if (next.version < previous.version) return previous;
  return {
    ...previous,
    ...next,
    parentActivityId: next.parentActivityId ?? previous.parentActivityId,
    requestedAt: earlier(previous.requestedAt, next.requestedAt),
    startedAt: earlier(previous.startedAt, next.startedAt),
    completedAt: next.completedAt ?? previous.completedAt,
    safeTargetSummary: next.safeTargetSummary || previous.safeTargetSummary,
    safeResultSummary: next.safeResultSummary || previous.safeResultSummary,
    interactionRef: next.interactionRef ?? previous.interactionRef,
  };
}

function activityTime(activity: Activity): number {
  return new Date(activity.requestedAt ?? activity.startedAt ?? activity.occurredAt).getTime();
}

function mergeActivities(current: Activity[], incoming: Activity[]): Activity[] {
  const values = new Map(current.map((activity) => [activity.activityId, activity]));
  for (const activity of incoming) {
    const previous = values.get(activity.activityId);
    values.set(activity.activityId, previous ? mergeActivity(previous, activity) : activity);
  }
  return [...values.values()].sort(
    (left, right) => activityTime(left) - activityTime(right),
  );
}

function trimUtf8Tail(value: string): { text: string; truncated: boolean } {
  const lines = value.split("\n");
  let text = lines.length > MAX_PREVIEW_LINES ? lines.slice(-MAX_PREVIEW_LINES).join("\n") : value;
  let truncated = text !== value;
  const encoder = new TextEncoder();
  if (encoder.encode(text).length <= MAX_PREVIEW_BYTES) return { text, truncated };
  let low = 0;
  let high = text.length;
  while (low < high) {
    const middle = Math.floor((low + high) / 2);
    if (encoder.encode(text.slice(middle)).length <= MAX_PREVIEW_BYTES) high = middle;
    else low = middle + 1;
  }
  if (low < text.length && /[\uDC00-\uDFFF]/u.test(text[low])) low++;
  text = text.slice(low);
  return { text, truncated: true };
}

function appendPreview(
  current: ToolOutputPreviewState | undefined,
  value: string,
  channel: "stdout" | "stderr",
  outputTruncated: boolean,
  previewDropped: boolean,
): ToolOutputPreviewState {
  const channelMarker = current
    ? current.channel !== channel ? `\n[${channel}]\n` : ""
    : channel === "stderr" ? "[stderr]\n" : "";
  const bounded = trimUtf8Tail(`${current?.text ?? ""}${channelMarker}${value}`);
  return {
    text: bounded.text,
    channel,
    outputTruncated: Boolean(current?.outputTruncated || outputTruncated),
    previewDropped: Boolean(current?.previewDropped || previewDropped || bounded.truncated),
  };
}

function withoutTerminalPreviews(
  previews: Record<string, ToolOutputPreviewState>,
  activities: Activity[],
): Record<string, ToolOutputPreviewState> {
  const terminal = new Set(
    activities
      .filter((activity) => terminalActivityStatuses.has(activity.status.toUpperCase()))
      .map((activity) => activity.activityId),
  );
  if (!terminal.size) return previews;
  return Object.fromEntries(Object.entries(previews).filter(([activityId]) => !terminal.has(activityId)));
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
        toolPreviews: {},
        interaction: null,
        interactionError: null,
        streamDraft: "",
        outputPhase: "idle",
        streamSequences: { durable: 0, transient: 0 },
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
        toolPreviews:
          !next || changed || ["COMPLETED", "FAILED", "CANCELLED", "TIMEOUT"].includes(next.status)
            ? {}
            : state.toolPreviews,
        interaction: changed ? null : state.interaction,
        interactionError: changed ? null : state.interactionError,
        streamSequences: changed
          ? { durable: 0, transient: 0 }
          : state.streamSequences,
        streamDraft:
          !next || changed || ["FAILED", "CANCELLED", "TIMEOUT"].includes(next.status)
            ? ""
            : state.streamDraft,
        outputPhase:
          !next || changed || ["COMPLETED", "FAILED", "CANCELLED", "TIMEOUT"].includes(next.status)
            ? "idle"
            : state.outputPhase,
      };
    }
    case "activitiesLoaded": {
      const activities = mergeActivities(state.activities, action.activities);
      return {
        ...state,
        activities,
        toolPreviews: withoutTerminalPreviews(state.toolPreviews, activities),
      };
    }
    case "interactionLoaded":
      return {
        ...state,
        interactionError: null,
        interaction:
          !state.interaction ||
          !action.interaction ||
          action.interaction.id !== state.interaction.id ||
          action.interaction.revision >= state.interaction.revision
            ? action.interaction
            : state.interaction,
      };
    case "interactionLoadFailed":
      return state.run?.id === action.runId
        ? { ...state, interactionError: action.message }
        : state;
    case "memoryLoaded":
      return {
        ...state,
        memories: action.memories,
      };
    case "streamEvent": {
      if (action.event.type === "tool.output.preview") {
        if (!action.event.toolCallId || (state.run && action.event.runId !== state.run.id)) return state;
        const activityId = `tool:${action.event.toolCallId}`;
        const activity = state.activities.find((value) => value.activityId === activityId);
        if (activity && terminalActivityStatuses.has(activity.status.toUpperCase())) return state;
        if (state.run && ["COMPLETED", "FAILED", "CANCELLED", "TIMEOUT"].includes(state.run.status)) return state;
        const channel = action.event.outputChannel === "stderr" ? "stderr" : "stdout";
        return {
          ...state,
          toolPreviews: {
            ...state.toolPreviews,
            [activityId]: appendPreview(
              state.toolPreviews[activityId],
              action.event.value,
              channel,
              Boolean(action.event.outputTruncated),
              Boolean(action.event.previewDropped),
            ),
          },
        };
      }
      const source = action.event.source;
      if (
        source !== "snapshot" &&
        action.event.sequence <= state.streamSequences[source]
      ) {
        return state;
      }
      const resetDraft = [
        "answer.started",
        "answer.failed",
        "answer.superseded",
      ].includes(action.event.type);
      const outputPhase = action.event.type === "answer.started"
        ? "starting"
        : action.event.type === "answer.delta"
          ? "streaming"
          : ["answer.committed", "answer.failed", "answer.superseded"].includes(action.event.type)
            ? "idle"
            : state.outputPhase;
      const activities = action.event.activity
        ? mergeActivities(state.activities, [action.event.activity])
        : state.activities;
      return {
        ...state,
        streamSequences:
          source === "snapshot"
            ? state.streamSequences
            : { ...state.streamSequences, [source]: action.event.sequence },
        streamDraft:
          action.event.type === "answer.delta"
            ? state.streamDraft + action.event.value
            : resetDraft
              ? ""
              : state.streamDraft,
        outputPhase,
        activities,
        toolPreviews:
          action.event.type === "run.final"
            ? {}
            : withoutTerminalPreviews(state.toolPreviews, activities),
      };
    }
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

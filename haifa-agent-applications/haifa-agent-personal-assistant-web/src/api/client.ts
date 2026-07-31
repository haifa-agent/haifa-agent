import type {
  Activity,
  ApiError,
  Bootstrap,
  Conversation,
  Interaction,
  InteractionReceipt,
  Memory,
  MemoryCandidate,
  ModelSelection,
  RecommendedQuestions,
  Run,
  StreamEvent,
  Turn,
  UpdateConversation,
} from "./generated";

const DEFAULT_API_ROOT = "http://127.0.0.1:20001/api/v1";
const API_ROOT = (import.meta.env.VITE_PERSONAL_ASSISTANT_API_BASE_URL?.trim() || DEFAULT_API_ROOT)
  .replace(/\/+$/, "");
const DEFAULT_TIMEOUT_MS = 12_000;

export class PersonalAssistantApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly correlationId: string,
  ) {
    super(message);
    this.name = "PersonalAssistantApiError";
  }
}

export interface CommandOptions {
  idempotencyKey?: string;
  signal?: AbortSignal;
}

export interface StreamHandlers {
  onEvent(event: StreamEvent): void;
  onOpen?(): void;
}

export interface PersonalAssistantClient {
  bootstrap(signal?: AbortSignal): Promise<Bootstrap>;
  conversations(query?: string, signal?: AbortSignal): Promise<Conversation[]>;
  createConversation(
    displayName: string,
    message: string,
    options?: CommandOptions,
    modelId?: string,
  ): Promise<Conversation>;
  selectModel?(
    conversation: Conversation,
    modelId: string,
    options?: CommandOptions,
  ): Promise<ModelSelection>;
  conversation(id: string, signal?: AbortSignal): Promise<Conversation>;
  updateConversation(
    conversation: Conversation,
    update: UpdateConversation,
    options?: CommandOptions,
  ): Promise<Conversation>;
  turns(id: string, signal?: AbortSignal): Promise<Turn[]>;
  submitMessage(
    conversation: Conversation,
    message: string,
    options?: CommandOptions,
  ): Promise<Conversation>;
  recommendedQuestions(
    conversationId: string,
    runId: string,
    options?: CommandOptions,
  ): Promise<RecommendedQuestions>;
  run(id: string, signal?: AbortSignal): Promise<Run>;
  cancelRun(id: string, options?: CommandOptions): Promise<Run>;
  activities(id: string, signal?: AbortSignal): Promise<Activity[]>;
  interaction(id: string, signal?: AbortSignal): Promise<Interaction | null>;
  respondToInteraction(
    interaction: Interaction,
    action: string,
    text: string,
    options?: CommandOptions,
  ): Promise<InteractionReceipt>;
  memoryCandidates(signal?: AbortSignal): Promise<MemoryCandidate[]>;
  memories(signal?: AbortSignal): Promise<Memory[]>;
  approveMemory(candidate: MemoryCandidate, options?: CommandOptions): Promise<Memory>;
  rejectMemory(
    candidate: MemoryCandidate,
    reason: string,
    options?: CommandOptions,
  ): Promise<MemoryCandidate>;
  invalidateMemory(memory: Memory, reason: string, options?: CommandOptions): Promise<Memory>;
  streamRun(
    runId: string,
    handlers: StreamHandlers,
    signal: AbortSignal,
  ): Promise<void>;
}

function commandHeaders(revision?: number, key?: string): HeadersInit {
  return {
    "Content-Type": "application/json",
    "X-Haifa-CSRF": "1",
    "Idempotency-Key": key ?? crypto.randomUUID(),
    ...(revision === undefined ? {} : { "If-Match": String(revision) }),
  };
}

function encoded(value: string): string {
  return encodeURIComponent(value);
}

function boundedSignal(parent?: AbortSignal): { signal: AbortSignal; dispose: () => void } {
  const controller = new AbortController();
  const timer = window.setTimeout(
    () => controller.abort(new DOMException("Request timed out", "TimeoutError")),
    DEFAULT_TIMEOUT_MS,
  );
  const abort = () => controller.abort(parent?.reason);
  parent?.addEventListener("abort", abort, { once: true });
  return {
    signal: controller.signal,
    dispose: () => {
      window.clearTimeout(timer);
      parent?.removeEventListener("abort", abort);
    },
  };
}

export class HttpPersonalAssistantClient implements PersonalAssistantClient {
  private readonly streamCursors = new Map<string, string>();

  private async request<T>(
    path: string,
    init: RequestInit = {},
    parentSignal?: AbortSignal,
  ): Promise<T> {
    const bounded = boundedSignal(parentSignal);
    try {
      const response = await fetch(`${API_ROOT}${path}`, {
        ...init,
        signal: bounded.signal,
      });
      if (!response.ok) {
        const safe = (await response.json().catch(() => null)) as ApiError | null;
        throw new PersonalAssistantApiError(
          response.status,
          safe?.code ?? "HTTP_ERROR",
          safe?.message ?? `请求失败（HTTP ${response.status}）`,
          safe?.correlationId ?? "unavailable",
        );
      }
      if (response.status === 204) return null as T;
      return (await response.json()) as T;
    } finally {
      bounded.dispose();
    }
  }

  bootstrap(signal?: AbortSignal) {
    return this.request<Bootstrap>("/bootstrap", {}, signal);
  }

  conversations(query = "", signal?: AbortSignal) {
    const parameters = new URLSearchParams();
    parameters.append("status", "ACTIVE");
    parameters.append("status", "ARCHIVED");
    parameters.set("limit", "100");
    if (query.trim()) parameters.set("q", query.trim());
    return this.request<Conversation[]>(`/conversations?${parameters}`, {}, signal);
  }

  createConversation(
    displayName: string,
    message: string,
    options: CommandOptions = {},
    modelId?: string,
  ) {
    return this.request<Conversation>(
      "/conversations",
      {
        method: "POST",
        headers: commandHeaders(undefined, options.idempotencyKey),
        body: JSON.stringify({ displayName, message, modelId }),
      },
      options.signal,
    );
  }

  selectModel(
    conversation: Conversation,
    modelId: string,
    options: CommandOptions = {},
  ) {
    return this.request<ModelSelection>(
      `/conversations/${encoded(conversation.id)}/model`,
      {
        method: "PATCH",
        headers: commandHeaders(conversation.model.revision, options.idempotencyKey),
        body: JSON.stringify({ modelId }),
      },
      options.signal,
    );
  }

  conversation(id: string, signal?: AbortSignal) {
    return this.request<Conversation>(`/conversations/${encoded(id)}`, {}, signal);
  }

  updateConversation(
    conversation: Conversation,
    update: UpdateConversation,
    options: CommandOptions = {},
  ) {
    return this.request<Conversation>(
      `/conversations/${encoded(conversation.id)}`,
      {
        method: "PATCH",
        headers: commandHeaders(conversation.revision, options.idempotencyKey),
        body: JSON.stringify(update),
      },
      options.signal,
    );
  }

  turns(id: string, signal?: AbortSignal) {
    return this.request<Turn[]>(`/conversations/${encoded(id)}/turns`, {}, signal);
  }

  submitMessage(
    conversation: Conversation,
    message: string,
    options: CommandOptions = {},
  ) {
    return this.request<Conversation>(
      `/conversations/${encoded(conversation.id)}/messages`,
      {
        method: "POST",
        headers: commandHeaders(conversation.revision, options.idempotencyKey),
        body: JSON.stringify({ message }),
      },
      options.signal,
    );
  }

  recommendedQuestions(
    conversationId: string,
    runId: string,
    options: CommandOptions = {},
  ) {
    return this.request<RecommendedQuestions>(
      `/conversations/${encoded(conversationId)}/runs/${encoded(runId)}/recommend-questions`,
      {
        method: "POST",
        headers: commandHeaders(undefined, options.idempotencyKey),
        body: "{}",
      },
      options.signal,
    );
  }

  run(id: string, signal?: AbortSignal) {
    return this.request<Run>(`/runs/${encoded(id)}`, {}, signal);
  }

  cancelRun(id: string, options: CommandOptions = {}) {
    return this.request<Run>(
      `/runs/${encoded(id)}/cancel`,
      {
        method: "POST",
        headers: commandHeaders(undefined, options.idempotencyKey),
        body: "{}",
      },
      options.signal,
    );
  }

  activities(id: string, signal?: AbortSignal) {
    return this.request<Activity[]>(`/runs/${encoded(id)}/activities`, {}, signal);
  }

  interaction(id: string, signal?: AbortSignal) {
    return this.request<Interaction | null>(`/runs/${encoded(id)}/interaction`, {}, signal);
  }

  respondToInteraction(
    interaction: Interaction,
    action: string,
    text: string,
    options: CommandOptions = {},
  ) {
    return this.request<InteractionReceipt>(
      `/runs/${encoded(interaction.runId)}/interactions/${encoded(interaction.id)}/response`,
      {
        method: "POST",
        headers: commandHeaders(interaction.revision, options.idempotencyKey),
        body: JSON.stringify({ action, text }),
      },
      options.signal,
    );
  }

  memoryCandidates(signal?: AbortSignal) {
    return this.request<MemoryCandidate[]>("/memory/candidates", {}, signal);
  }

  memories(signal?: AbortSignal) {
    return this.request<Memory[]>("/memory", {}, signal);
  }

  approveMemory(candidate: MemoryCandidate, options: CommandOptions = {}) {
    return this.request<Memory>(
      `/memory/candidates/${encoded(candidate.id)}/approve`,
      {
        method: "POST",
        headers: commandHeaders(candidate.revision, options.idempotencyKey),
        body: "{}",
      },
      options.signal,
    );
  }

  rejectMemory(
    candidate: MemoryCandidate,
    reason: string,
    options: CommandOptions = {},
  ) {
    return this.request<MemoryCandidate>(
      `/memory/candidates/${encoded(candidate.id)}/reject`,
      {
        method: "POST",
        headers: commandHeaders(candidate.revision, options.idempotencyKey),
        body: JSON.stringify({ reason }),
      },
      options.signal,
    );
  }

  invalidateMemory(memory: Memory, reason: string, options: CommandOptions = {}) {
    return this.request<Memory>(
      `/memory/${encoded(memory.id)}/versions/${memory.version}/invalidate`,
      {
        method: "POST",
        headers: commandHeaders(undefined, options.idempotencyKey),
        body: JSON.stringify({ reason }),
      },
      options.signal,
    );
  }

  async streamRun(
    runId: string,
    handlers: StreamHandlers,
    signal: AbortSignal,
  ): Promise<void> {
    const response = await fetch(`${API_ROOT}/runs/${encoded(runId)}/stream`, {
      headers: {
        Accept: "text/event-stream",
        ...(this.streamCursors.has(runId)
          ? { "Last-Event-ID": this.streamCursors.get(runId)! }
          : {}),
      },
      signal,
    });
    if (!response.ok || !response.body) {
      throw new PersonalAssistantApiError(
        response.status,
        "STREAM_UNAVAILABLE",
        "运行事件流暂时不可用",
        "unavailable",
      );
    }
    handlers.onOpen?.();
    const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
    let buffer = "";
    while (!signal.aborted) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += value.replaceAll("\r\n", "\n");
      let boundary = buffer.indexOf("\n\n");
      while (boundary >= 0) {
        const frame = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);
        const data = frame
          .split("\n")
          .filter((line) => line.startsWith("data:"))
          .map((line) => line.slice(5).trimStart())
          .join("\n");
        if (data) {
          const event = JSON.parse(data) as StreamEvent;
          this.streamCursors.set(runId, event.eventId);
          handlers.onEvent(event);
          if (event.type === "run.final") this.streamCursors.delete(runId);
        }
        boundary = buffer.indexOf("\n\n");
      }
    }
  }
}

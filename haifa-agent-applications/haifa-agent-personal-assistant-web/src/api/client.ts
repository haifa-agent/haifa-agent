import type {
  Activity,
  ApiError,
  Bootstrap,
  Conversation,
  Interaction,
  InteractionReceipt,
  ImageInput,
  Memory,
  MemoryCandidate,
  CreateMission,
  MissionPage,
  MissionSnapshot,
  ReplaceMissionPlan,
  ModelSelection,
  RecommendedQuestions,
  Run,
  StreamEvent,
  Turn,
  UploadedImage,
  UpdateConversation,
} from "./generated";

const DEFAULT_API_ROOT = "http://127.0.0.1:20001/api/v1";
const API_ROOT = (import.meta.env.VITE_PERSONAL_ASSISTANT_API_BASE_URL?.trim() || DEFAULT_API_ROOT)
  .replace(/\/+$/, "");
const DEFAULT_TIMEOUT_MS = 12_000;
const MISSION_PLANNING_TIMEOUT_MS = 190_000;

export function missionArtifactUrl(missionId: string, artifactId: string): string {
  return `${API_ROOT}/missions/${encoded(missionId)}/artifacts/${encoded(artifactId)}`;
}

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
    images?: ImageInput[],
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
    images?: ImageInput[],
  ): Promise<Conversation>;
  uploadImage?(file: File, options?: CommandOptions): Promise<UploadedImage>;
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
  missions?(conversationId?: string, signal?: AbortSignal): Promise<MissionPage>;
  createMission?(request: CreateMission, options?: CommandOptions): Promise<MissionSnapshot>;
  mission?(id: string, signal?: AbortSignal): Promise<MissionSnapshot>;
  missionSnapshot?(id: string, signal?: AbortSignal): Promise<MissionSnapshot>;
  missionArtifact?(missionId: string, artifactId: string, signal?: AbortSignal): Promise<string>;
  replaceMissionPlan?(
    mission: MissionSnapshot,
    request: ReplaceMissionPlan,
    options?: CommandOptions,
  ): Promise<MissionSnapshot>;
  confirmMission?(mission: MissionSnapshot, options?: CommandOptions): Promise<MissionSnapshot>;
  cancelMission?(mission: MissionSnapshot, options?: CommandOptions): Promise<MissionSnapshot>;
  retryMissionTask?(
    mission: MissionSnapshot,
    taskId: string,
    options?: CommandOptions,
  ): Promise<MissionSnapshot>;
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

function boundedSignal(
  parent?: AbortSignal,
  timeoutMs = DEFAULT_TIMEOUT_MS,
): { signal: AbortSignal; dispose: () => void } {
  const controller = new AbortController();
  const timer = window.setTimeout(
    () => controller.abort(new DOMException("Request timed out", "TimeoutError")),
    timeoutMs,
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
    timeoutMs = DEFAULT_TIMEOUT_MS,
  ): Promise<T> {
    const bounded = boundedSignal(parentSignal, timeoutMs);
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
      const body = typeof response.text === "function"
        ? await response.text()
        : JSON.stringify(await response.json());
      if (new Blob([body]).size > 2 * 1024 * 1024) {
        throw new PersonalAssistantApiError(
          response.status,
          "RESPONSE_TOO_LARGE",
          "服务器响应超过 2 MiB 安全上限。",
          "unavailable",
        );
      }
      return JSON.parse(body) as T;
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
    images: ImageInput[] = [],
  ) {
    return this.request<Conversation>(
      "/conversations",
      {
        method: "POST",
        headers: commandHeaders(undefined, options.idempotencyKey),
        body: JSON.stringify({ displayName, message, modelId, ...(images.length ? { images } : {}) }),
      },
      options.signal,
    );
  }

  selectModel(
    conversation: Conversation,
    modelId: string,
    options: CommandOptions = {},
    images: ImageInput[] = [],
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
    images: ImageInput[] = [],
  ) {
    return this.request<Conversation>(
      `/conversations/${encoded(conversation.id)}/messages`,
      {
        method: "POST",
        headers: commandHeaders(conversation.revision, options.idempotencyKey),
        body: JSON.stringify({ message, ...(images.length ? { images } : {}) }),
      },
      options.signal,
    );
  }

  uploadImage(file: File, options: CommandOptions = {}) {
    return this.request<UploadedImage>(
      "/images",
      {
        method: "POST",
        headers: {
          "Content-Type": file.type,
          "X-Haifa-CSRF": "1",
          "Idempotency-Key": options.idempotencyKey ?? crypto.randomUUID(),
          "X-Image-Filename": encodeURIComponent(file.name),
        },
        body: file,
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

  missions(conversationId?: string, signal?: AbortSignal) {
    const parameters = new URLSearchParams({ size: "50" });
    if (conversationId) parameters.set("conversationId", conversationId);
    return this.request<MissionPage>(`/missions?${parameters}`, {}, signal);
  }

  createMission(request: CreateMission, options: CommandOptions = {}) {
    return this.request<MissionSnapshot>(
      "/missions",
      {
        method: "POST",
        headers: commandHeaders(undefined, options.idempotencyKey),
        body: JSON.stringify(request),
      },
      options.signal,
      MISSION_PLANNING_TIMEOUT_MS,
    );
  }

  mission(id: string, signal?: AbortSignal) {
    return this.request<MissionSnapshot>(`/missions/${encoded(id)}`, {}, signal);
  }

  missionSnapshot(id: string, signal?: AbortSignal) {
    return this.request<MissionSnapshot>(`/missions/${encoded(id)}/snapshot`, {}, signal);
  }

  async missionArtifact(missionId: string, artifactId: string, parentSignal?: AbortSignal) {
    const bounded = boundedSignal(parentSignal, DEFAULT_TIMEOUT_MS);
    try {
      const response = await fetch(missionArtifactUrl(missionId, artifactId), { signal: bounded.signal });
      if (!response.ok) {
        throw new PersonalAssistantApiError(
          response.status,
          "MISSION_ARTIFACT_UNAVAILABLE",
          `报告文件读取失败（HTTP ${response.status}）`,
          "unavailable",
        );
      }
      const body = await response.text();
      if (new Blob([body]).size > 2 * 1024 * 1024) {
        throw new PersonalAssistantApiError(
          response.status,
          "RESPONSE_TOO_LARGE",
          "报告文件超过 2 MiB 安全上限。",
          "unavailable",
        );
      }
      return body;
    } finally {
      bounded.dispose();
    }
  }

  replaceMissionPlan(
    mission: MissionSnapshot,
    request: ReplaceMissionPlan,
    options: CommandOptions = {},
  ) {
    return this.request<MissionSnapshot>(
      `/missions/${encoded(mission.missionId)}/plan`,
      {
        method: "PUT",
        headers: commandHeaders(mission.version, options.idempotencyKey),
        body: JSON.stringify(request),
      },
      options.signal,
      MISSION_PLANNING_TIMEOUT_MS,
    );
  }

  confirmMission(mission: MissionSnapshot, options: CommandOptions = {}) {
    return this.request<MissionSnapshot>(
      `/missions/${encoded(mission.missionId)}/confirm`,
      {
        method: "POST",
        headers: commandHeaders(mission.version, options.idempotencyKey),
        body: "{}",
      },
      options.signal,
    );
  }

  cancelMission(mission: MissionSnapshot, options: CommandOptions = {}) {
    return this.request<MissionSnapshot>(
      `/missions/${encoded(mission.missionId)}/cancel`,
      {
        method: "POST",
        headers: commandHeaders(mission.version, options.idempotencyKey),
        body: "{}",
      },
      options.signal,
    );
  }

  retryMissionTask(mission: MissionSnapshot, taskId: string, options: CommandOptions = {}) {
    return this.request<MissionSnapshot>(
      `/missions/${encoded(mission.missionId)}/tasks/${encoded(taskId)}/retry`,
      {
        method: "POST",
        headers: commandHeaders(mission.version, options.idempotencyKey),
        body: "{}",
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

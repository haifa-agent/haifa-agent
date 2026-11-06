import type {
  Action,
  BrowserGrant,
  BrowserInfo,
  Command,
  CommandRequest,
  CommandResult,
  ConnectionInfo,
  Preview,
  HistoryPage,
  Interaction,
  Query,
  QueryResult,
  QuestionAnswer,
  Snapshot,
  Subscribed,
} from "./generated/protocol.js";
import {
  applyPresentation,
  checkEnvelope,
  commandValid,
  decode,
  freeze,
  snapshot,
  utf8Bytes,
} from "./contract.js";
import { CodingClientError, fail } from "./errors.js";
import { readSse } from "./sse.js";
import { RequestTracker } from "./requests.js";
import type { RequestState } from "./requests.js";

export type ConnectionState =
  | "connected"
  | "offline"
  | "pairing-required"
  | "incompatible"
  | "closed";
export type ObservationState = "live" | "recovering" | "unavailable" | "closed";
export type BrowserClientOptions = Readonly<{
  origin?: string;
  fetch?: typeof globalThis.fetch;
  requestTimeoutMs?: number;
}>;
export type ActionInput = Readonly<{
  text?: string;
  selections?: ReadonlyArray<string>;
  answers?: ReadonlyArray<QuestionAnswer>;
}>;
export interface CodingObservation {
  readonly snapshot: Snapshot;
  readonly state: ObservationState;
  readonly reason: string | null;
  onChange(listener: () => void): () => void;
  refresh(): Promise<void>;
  act(action: Action, input?: ActionInput): Promise<CommandResult>;
  close(): void;
}
const OFFERED = [
  "coding-product-v1",
  "session-presentation-v1",
  "session-consent-v1",
  "preview-v1",
];
const REQUIRED = OFFERED.slice(0, 2);
const AUTH = new Set([
  "BROWSER_AUTH_REQUIRED",
  "BROWSER_AUTH_EXPIRED",
  "BROWSER_ORIGIN_REJECTED",
  "BROWSER_HOST_REJECTED",
  "CLIENT_CONTEXT_EXPIRED",
  "CLIENT_CONTEXT_REQUIRED",
]);
const PRE_ADMISSION = new Set([
  ...AUTH,
  "INVALID_PARAMS",
  "HOST_BUSY",
  "REMOTE_METHOD_UNSUPPORTED",
  "PAIRING_REJECTED",
  "PAIRING_LIMIT",
  "PAIRING_RATE_LIMIT",
  "BROWSER_CLIENT_LIMIT",
]);
const code = (error: unknown) =>
  error instanceof CodingClientError ? error.code : "TRANSPORT_UNAVAILABLE";
const object = (value: unknown): Record<string, unknown> => {
  if (!value || typeof value !== "object" || Array.isArray(value))
    return fail("PROTOCOL_INCOMPATIBLE");
  return value as Record<string, unknown>;
};
const uid = () => globalThis.crypto.randomUUID();
const emptyTarget = () => ({
  projectId: null,
  sessionId: null,
  runId: null,
  interactionId: null,
  targetRevision: null,
});
function notify(listeners: ReadonlySet<() => void>): void {
  for (const listener of listeners)
    try {
      listener();
    } catch {
      /* Consumer rendering cannot change transport ownership. */
    }
}
function delay(milliseconds: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    if (signal.aborted) {
      resolve();
      return;
    }
    const done = () => {
      clearTimeout(timer);
      signal.removeEventListener("abort", done);
      resolve();
    };
    const timer = setTimeout(done, milliseconds);
    signal.addEventListener("abort", done, { once: true });
  });
}

/** Browser-only same-origin client. Proofs remain private page memory and are never diagnostic data. */
export class CodingBrowserClient {
  #grant: string | null = null;
  #expires = 0;
  #connection: ConnectionInfo | null = null;
  #state: ConnectionState = "pairing-required";
  #reason: string | null = null;
  #fetch: typeof globalThis.fetch;
  #origin: string;
  #timeout: number;
  #listeners = new Set<() => void>();
  #controllers = new Set<AbortController>();
  #views = new Set<SessionView>();
  #tracker = new RequestTracker();
  #foreground: Promise<void> | null = null;

  private constructor(options: BrowserClientOptions) {
    let origin: URL;
    try {
      origin = new URL(options.origin ?? globalThis.location.origin);
    } catch {
      throw new CodingClientError("SAME_ORIGIN_REQUIRED");
    }
    if (
      !["http:", "https:"].includes(origin.protocol) ||
      origin.username ||
      origin.password ||
      origin.search ||
      origin.hash ||
      origin.pathname !== "/" ||
      (globalThis.location && origin.origin !== globalThis.location.origin)
    )
      fail("SAME_ORIGIN_REQUIRED");
    if (!globalThis.crypto?.randomUUID) fail("BROWSER_CRYPTO_REQUIRED");
    this.#origin = origin.origin;
    this.#fetch = options.fetch ?? globalThis.fetch.bind(globalThis);
    this.#timeout = options.requestTimeoutMs ?? 30000;
    if (
      !Number.isSafeInteger(this.#timeout) ||
      this.#timeout < 100 ||
      this.#timeout > 600000
    )
      fail("INVALID_TIMEOUT");
  }
  static async pair(
    challenge: string,
    options: BrowserClientOptions = {},
  ): Promise<CodingBrowserClient> {
    const client = new CodingBrowserClient(options);
    try {
      const info = decode<BrowserInfo>(
        "BrowserInfo",
        await client.#request("/browser/v1/info", undefined, null, false, null),
        16384,
      );
      const proof = decode("PairingRequest", {
        challenge,
        hostIncarnation: info.hostIncarnation,
      });
      const grant = decode<BrowserGrant>(
        "BrowserGrant",
        await client.#request("/browser/v1/pair", proof, null, true, null),
        16384,
      );
      if (
        grant.hostIncarnation !== info.hostIncarnation ||
        grant.expiresAtEpochMillis <= Date.now()
      )
        fail("PAIRING_RESPONSE_INVALID");
      client.#grant = grant.grant;
      client.#expires = grant.expiresAtEpochMillis;
      const initialized = object(
        await client.#rpc("initialize", {
          protocolVersion: "1",
          clientInfo: { name: "coding-browser-client", version: "0.1.5-b3.0" },
          optionalCapabilities: OFFERED,
        }),
      );
      const selected = initialized.optionalCapabilities;
      if (
        !Array.isArray(selected) ||
        selected.some((x) => typeof x !== "string" || !OFFERED.includes(x)) ||
        new Set(selected).size !== selected.length ||
        REQUIRED.some((x) => !selected.includes(x)) ||
        initialized.browserAuthorization !== "browser-grant-v1"
      )
        fail("BROWSER_CAPABILITY_UNSUPPORTED");
      const connection = decode<ConnectionInfo>(
        "ConnectionInfo",
        {
          hostIncarnation: initialized.hostIncarnation,
          clientId: initialized.clientId,
          projectId: initialized.projectId,
          protocolVersion: initialized.protocolVersion,
          displayName: initialized.displayName,
          capabilities: selected,
          source: initialized.clientSource,
        },
        16384,
      );
      if (
        connection.hostIncarnation !== grant.hostIncarnation ||
        connection.clientId !== grant.clientId ||
        connection.source !== "browser"
      )
        fail("CONNECTION_SCOPE_MISMATCH");
      client.#connection = freeze(connection);
      client.#status("connected", null);
      return client;
    } catch (error) {
      await client.close();
      if (error instanceof CodingClientError) throw error;
      throw new CodingClientError("PAIRING_FAILED");
    }
  }
  get connection(): ConnectionInfo {
    this.#ensure();
    return this.#connection ?? fail("CLIENT_NOT_INITIALIZED");
  }
  get state(): ConnectionState {
    return this.#state;
  }
  get reason(): string | null {
    return this.#reason;
  }
  get expiresAtEpochMillis(): number {
    return this.#expires;
  }
  get requests(): ReadonlyArray<RequestState> {
    return this.#tracker.list();
  }
  onChange(listener: () => void): () => void {
    this.#listeners.add(listener);
    return () => this.#listeners.delete(listener);
  }
  toJSON(): object {
    return {
      state: this.#state,
      reason: this.#reason,
      connection: this.#connection,
    };
  }
  #status(state: ConnectionState, reason: string | null): void {
    this.#state = state;
    this.#reason = reason;
    notify(this.#listeners);
  }
  #invalidate(reason: string): void {
    if (this.#state === "closed") return;
    this.#grant = null;
    for (const controller of this.#controllers) controller.abort();
    for (const view of [...this.#views]) view.terminate(reason);
    this.#status(
      AUTH.has(reason) || reason === "GRANT_EXPIRED"
        ? "pairing-required"
        : "incompatible",
      reason,
    );
  }
  #ensure(): void {
    if (this.#state === "closed") fail("CLIENT_CLOSED");
    if (this.#grant && Date.now() >= this.#expires)
      this.#invalidate("GRANT_EXPIRED");
    if (!this.#grant) fail("PAIRING_REQUIRED");
  }
  async #request(
    path: string,
    params: unknown,
    id: string | null,
    mutation: boolean,
    grant: string | null,
    timeoutMs = this.#timeout,
  ): Promise<unknown> {
    const controller = new AbortController();
    this.#controllers.add(controller);
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    let sent = false;
    try {
      const body = params === undefined ? undefined : JSON.stringify(params);
      if (body && new TextEncoder().encode(body).length > 262144)
        fail("REQUEST_BUDGET_EXCEEDED");
      const headers: Record<string, string> = { Accept: "application/json" };
      if (body !== undefined) headers["Content-Type"] = "application/json";
      if (grant) headers.Authorization = "Bearer " + grant;
      sent = true;
      const response = await this.#fetch(this.#origin + path, {
        method: body === undefined ? "GET" : "POST",
        headers,
        body,
        cache: "no-store",
        credentials: "omit",
        redirect: "error",
        referrerPolicy: "no-referrer",
        signal: controller.signal,
      });
      if (
        !response.headers
          .get("Content-Type")
          ?.toLowerCase()
          .startsWith("application/json")
      )
        throw new CodingClientError("PROTOCOL_INCOMPATIBLE", mutation);
      if (!response.body)
        throw new CodingClientError("PROTOCOL_INCOMPATIBLE", mutation);
      const reader = response.body.getReader();
      const decoder = new TextDecoder("utf-8", { fatal: true });
      let bytes = 0,
        text = "";
      const cancel = () => {
        void reader.cancel().catch(() => {});
      };
      controller.signal.addEventListener("abort", cancel, { once: true });
      try {
        for (;;) {
          const part = await reader.read();
          if (controller.signal.aborted)
            throw new CodingClientError("TRANSPORT_TIMEOUT", mutation);
          if (part.done) break;
          bytes += part.value.byteLength;
          if (bytes > 1048576)
            throw new CodingClientError("RESPONSE_BUDGET_EXCEEDED", mutation);
          text += decoder.decode(part.value, { stream: true });
        }
        text += decoder.decode();
      } finally {
        controller.signal.removeEventListener("abort", cancel);
        cancel();
        reader.releaseLock();
      }
      const envelope = object(JSON.parse(text));
      if (
        envelope.jsonrpc !== "2.0" ||
        ("error" in envelope && "result" in envelope)
      )
        throw new CodingClientError("PROTOCOL_INCOMPATIBLE", mutation);
      if ("error" in envelope) {
        const failure = object(envelope.error);
        const reason = object(failure.data).reasonCode;
        const safe =
          typeof reason === "string" && /^[A-Z0-9_:.]{1,128}$/.test(reason)
            ? reason
            : "REMOTE_REJECTED";
        // The Host can reject authentication before reading the request ID.
        if (
          envelope.id !== id &&
          !(envelope.id === null && response.status === 403 && AUTH.has(safe))
        )
          throw new CodingClientError("PROTOCOL_INCOMPATIBLE", mutation);
        if (AUTH.has(safe)) this.#invalidate(safe);
        throw new CodingClientError(safe, mutation && !PRE_ADMISSION.has(safe));
      }
      if (
        response.status !== 200 ||
        envelope.id !== id ||
        !("result" in envelope)
      )
        throw new CodingClientError("PROTOCOL_INCOMPATIBLE", mutation);
      return envelope.result;
    } catch (error) {
      if (error instanceof CodingClientError) {
        if (mutation && sent && error.code === "PROTOCOL_INCOMPATIBLE")
          throw new CodingClientError(error.code, true);
        throw error;
      }
      throw new CodingClientError(
        controller.signal.aborted
          ? "TRANSPORT_ABORTED"
          : "TRANSPORT_UNAVAILABLE",
        mutation && sent,
      );
    } finally {
      clearTimeout(timer);
      this.#controllers.delete(controller);
    }
  }
  #rpc(
    method: string,
    params: unknown = {},
    mutation = false,
  ): Promise<unknown> {
    this.#ensure();
    const id = uid();
    return this.#request(
      "/browser/v1/rpc",
      { jsonrpc: "2.0", id, method, params },
      id,
      mutation,
      this.#grant,
    );
  }
  async query(query: Query): Promise<QueryResult> {
    decode("Query", query);
    const result = freeze(
      decode<QueryResult>(
        "QueryResult",
        await this.#rpc("product/query", query),
      ),
    );
    if (
      result.sessions.some((s) => s.projectId !== this.connection.projectId) ||
      (query.operation === "run" &&
        result.run &&
        result.run.id !== query.target.runId) ||
      (query.operation === "outcome" &&
        result.outcome &&
        result.outcome.runId !== query.target.runId)
    )
      return fail("QUERY_SCOPE_MISMATCH");
    return result;
  }
  listSessions(search: string | null = null, limit = 32): Promise<QueryResult> {
    return this.query({
      operation: "sessions",
      target: emptyTarget(),
      search,
      limit,
      executionId: null,
    });
  }
  async execute(request: CommandRequest): Promise<CommandResult> {
    this.#ensure();
    decode("CommandRequest", request);
    commandValid(request.command);
    this.#tracker.begin(request.command);
    notify(this.#listeners);
    try {
      const raw = await this.#rpc("product/command", request, true);
      let result: CommandResult;
      try {
        result = freeze(decode<CommandResult>("CommandResult", raw));
        if (
          result.receipt.clientRequestId !== request.command.clientRequestId ||
          (result.session &&
            result.session.projectId !== this.connection.projectId)
        )
          fail("COMMAND_REPLY_INVALID");
      } catch {
        throw new CodingClientError("COMMAND_REPLY_INVALID", true);
      }
      this.#tracker.receipt(result.receipt, result.session?.sessionId ?? null);
      notify(this.#listeners);
      return result;
    } catch (error) {
      const unknown =
        !(error instanceof CodingClientError) || error.outcomeUnknown;
      const result: CommandResult = freeze({
        receipt: {
          clientRequestId: request.command.clientRequestId,
          disposition: unknown ? "unknown" : "rejected",
          operationId: null,
          runId: null,
          reasonCode: unknown ? "COMMAND_OUTCOME_UNKNOWN" : code(error),
        },
        session: null,
        queuedMessage: null,
        restoredMessage: null,
        shellPlan: null,
        shellExecution: null,
        shellResult: null,
        compaction: null,
        export: null,
        modelSelection: null,
        interactionReceipt: null,
        interaction: null,
      });
      this.#tracker.receipt(result.receipt, null);
      notify(this.#listeners);
      return result;
    }
  }
  createSession(
    text: string,
    modelId: string | null = null,
  ): Promise<CommandResult> {
    return this.execute({
      command: makeCommand("create", emptyTarget(), text, modelId),
      options: { approved: false, includeInContext: false, answers: [] },
    });
  }
  async history(
    sessionId: string,
    limit = 32,
    beforeSequence: number | null = null,
  ): Promise<HistoryPage> {
    const params: Record<string, unknown> = { sessionId, limit };
    if (beforeSequence !== null) params.beforeSequence = beforeSequence;
    const result = freeze(
      decode<HistoryPage>(
        "HistoryPage",
        await this.#rpc("presentation/history", params),
        262144,
      ),
    );
    if (result.sessionId !== sessionId) return fail("HISTORY_SCOPE_MISMATCH");
    return result;
  }
  async inspectRequest(requestId: string): Promise<RequestState> {
    const request = this.requests.find((r) => r.requestId === requestId);
    if (!request) return fail("REQUEST_NOT_FOUND");
    if (request.runId) {
      const facts = await this.query({
        operation: "run",
        target: {
          ...emptyTarget(),
          sessionId: request.sessionId,
          runId: request.runId,
        },
        search: null,
        limit: 1,
        executionId: null,
      });
      if (facts.run) this.#tracker.observeRun(requestId, facts.run);
    }
    return this.requests.find((r) => r.requestId === requestId)!;
  }
  async renew(): Promise<void> {
    const result = object(await this.#rpc("client/renew"));
    if (result.renewed !== true) fail("RENEWAL_REJECTED");
    this.#status("connected", null);
  }
  foreground(): Promise<void> {
    if (this.#foreground) return this.#foreground;
    const views = [...this.#views];
    for (const view of views) view.pause("FOREGROUND_VERIFY");
    this.#foreground = (async () => {
      try {
        await this.renew();
        for (const view of views)
          if (view.state !== "closed") await view.refresh();
      } catch (error) {
        if (this.#grant) this.#status("offline", code(error));
        for (const view of views) view.unavailable(code(error));
        throw error;
      }
    })().finally(() => {
      this.#foreground = null;
    });
    return this.#foreground;
  }
  async #subscribe(sessionId: string): Promise<Subscribed> {
    const subscribed = decode<Subscribed>(
      "Subscribed",
      await this.#rpc("presentation/subscribe", { sessionId }),
    );
    const value = snapshot(subscribed.snapshot),
      scope = value.scope,
      connection = this.connection;
    if (
      scope.clientId !== connection.clientId ||
      scope.hostIncarnation !== connection.hostIncarnation ||
      scope.sessionId !== sessionId ||
      scope.source !== "browser"
    ) {
      this.#invalidate("PRESENTATION_SCOPE_MISMATCH");
      return fail("PRESENTATION_SCOPE_MISMATCH");
    }
    return freeze({ ...subscribed, snapshot: value });
  }
  async #closeSubscription(
    id: string,
    method: "presentation/close" | "preview/unsubscribe" = "presentation/close",
  ): Promise<void> {
    if (!this.#grant || this.#state === "closed") return;
    const requestId = uid();
    try {
      await this.#request(
        "/browser/v1/rpc",
        {
          jsonrpc: "2.0",
          id: requestId,
          method,
          params: { subscriptionId: id },
        },
        requestId,
        false,
        this.#grant,
        2000,
      );
    } catch {
      /* Observation cleanup never cancels execution. */
    }
  }
  async #events(id: string, signal: AbortSignal): Promise<Response> {
    this.#ensure();
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.#timeout);
    const abort = () => controller.abort();
    signal.addEventListener("abort", abort, { once: true });
    if (signal.aborted) abort();
    try {
      const response = await this.#fetch(
        this.#origin +
          "/browser/v1/events?subscriptionId=" +
          encodeURIComponent(id),
        {
          headers: {
            Authorization: "Bearer " + this.#grant,
            Accept: "text/event-stream",
          },
          cache: "no-store",
          credentials: "omit",
          redirect: "error",
          referrerPolicy: "no-referrer",
          signal: controller.signal,
        },
      );
      if (response.status === 401 || response.status === 403) {
        void response.body?.cancel();
        this.#invalidate("BROWSER_AUTH_REQUIRED");
        throw new CodingClientError("BROWSER_AUTH_REQUIRED");
      }
      if (
        response.status !== 200 ||
        !response.headers
          .get("Content-Type")
          ?.toLowerCase()
          .startsWith("text/event-stream") ||
        !response.body
      ) {
        void response.body?.cancel();
        fail("SSE_RESPONSE_INVALID");
      }
      return response;
    } finally {
      clearTimeout(timeout);
      signal.removeEventListener("abort", abort);
    }
  }
  async observe(sessionId: string): Promise<CodingObservation> {
    const initial = await this.#subscribe(sessionId);
    const view = new SessionView(initial, {
      subscribe: () => this.#subscribe(sessionId),
      close: (id) => this.#closeSubscription(id),
      events: (id, signal) => this.#events(id, signal),
      renew: () => this.renew(),
      execute: (request) => this.execute(request),
      removed: (value) => this.#views.delete(value),
      offline: () => {
        if (this.#grant) this.#status("offline", "CONNECTION_INTERRUPTED");
      },
    });
    this.#views.add(view);
    view.start();
    return view;
  }
  async *preview(
    sessionId: string,
    runId: string,
    signal: AbortSignal,
  ): AsyncGenerator<Preview> {
    this.#ensure();
    if (!this.connection.capabilities.includes("preview-v1"))
      fail("PREVIEW_UNSUPPORTED");
    const subscription = object(
      await this.#rpc("preview/subscribe", { sessionId, runId }),
    );
    const id = subscription.subscriptionId;
    if (typeof id !== "string" || !id || id.length > 256)
      fail("PROTOCOL_INCOMPATIBLE");
    const controller = new AbortController();
    this.#controllers.add(controller);
    const abort = () => controller.abort();
    signal.addEventListener("abort", abort, { once: true });
    if (signal.aborted) abort();
    try {
      const response = await this.#events(id, controller.signal);
      for await (const data of readSse(
        response.body!,
        "tool-preview",
        16384,
        controller.signal,
      )) {
        let value: unknown;
        try {
          value = JSON.parse(data);
        } catch {
          return fail("PREVIEW_INVALID");
        }
        const frame = decode<Preview>("Preview", value, 16384);
        if (
          frame.sessionId !== sessionId ||
          frame.runId !== runId ||
          new TextEncoder().encode(frame.text).length > 4096
        )
          fail("PREVIEW_SCOPE_OR_BUDGET");
        yield freeze(frame);
      }
    } finally {
      signal.removeEventListener("abort", abort);
      controller.abort();
      this.#controllers.delete(controller);
      await this.#closeSubscription(id, "preview/unsubscribe");
    }
  }
  async close(): Promise<void> {
    if (this.#state === "closed") return;
    const grant = this.#grant;
    this.#grant = null;
    for (const controller of this.#controllers) controller.abort();
    for (const view of [...this.#views]) view.close();
    this.#status("closed", null);
    this.#listeners.clear();
    if (grant) {
      const id = uid();
      try {
        await this.#request(
          "/browser/v1/rpc",
          { jsonrpc: "2.0", id, method: "client/close", params: {} },
          id,
          false,
          grant,
          2000,
        );
      } catch {
        /* Host idle expiry also bounds page close. */
      }
    }
  }
}
function makeCommand(
  operation: Command["operation"],
  target: Command["target"],
  text: string | null,
  optionId: string | null,
): Command {
  return {
    kind: "command",
    operation,
    clientRequestId: uid(),
    idempotencyKey: uid(),
    target,
    text,
    optionId,
    executionId: null,
    selections: [],
  };
}
/** Supported critical review/input lanes; rendering every unfamiliar payload as approvable is unsafe. */
export function supportsInteraction(value: Interaction): boolean {
  if (
    ![
      "approval",
      "clarification",
      "confirmation",
      "selection",
      "input-required",
      "artifact-review",
      "conflict-resolution",
    ].includes(value.kind)
  )
    return false;
  if (
    !["none", "text", "single-choice", "multi-choice"].includes(
      value.input.type,
    )
  )
    return false;
  const content = value.review?.contentType.toLowerCase();
  return (
    content === undefined ||
    [
      "file content",
      "patch",
      "path",
      "bash",
      "sh",
      "powershell",
      "pwsh",
      "shell",
      "text/plain",
      "text/x-diff",
      "application/json",
    ].includes(content)
  );
}
interface ViewBackend {
  subscribe: () => Promise<Subscribed>;
  close: (id: string) => Promise<void>;
  events: (id: string, signal: AbortSignal) => Promise<Response>;
  renew: () => Promise<void>;
  execute: (request: CommandRequest) => Promise<CommandResult>;
  removed: (view: SessionView) => void;
  offline: () => void;
}
class SessionView implements CodingObservation {
  #subscription: Subscribed;
  #backend: ViewBackend;
  #state: ObservationState = "live";
  #reason: string | null = null;
  #listeners = new Set<() => void>();
  #controller = new AbortController();
  #life = new AbortController();
  #generation = 0;
  #attempts = 0;
  #recovery: Promise<void> | null = null;
  constructor(subscription: Subscribed, backend: ViewBackend) {
    this.#subscription = subscription;
    this.#backend = backend;
  }
  get snapshot(): Snapshot {
    return this.#subscription.snapshot;
  }
  get state(): ObservationState {
    return this.#state;
  }
  get reason(): string | null {
    return this.#reason;
  }
  onChange(listener: () => void): () => void {
    this.#listeners.add(listener);
    return () => this.#listeners.delete(listener);
  }
  start(): void {
    const generation = ++this.#generation;
    void this.#consume(generation);
  }
  async #consume(generation: number): Promise<void> {
    try {
      const signal = this.#controller.signal;
      const response = await this.#backend.events(
        this.#subscription.subscriptionId,
        signal,
      );
      if (generation !== this.#generation || signal.aborted) {
        void response.body?.cancel();
        return;
      }
      for await (const frame of readSse(
        response.body!,
        "presentation",
        65536,
        signal,
      )) {
        if (generation !== this.#generation || this.#state === "closed") return;
        let envelope: unknown;
        try {
          envelope = JSON.parse(frame);
        } catch {
          return fail("SSE_JSON_INVALID");
        }
        if (!checkEnvelope(envelope)) return fail("PRESENTATION_INVALID");
        if (envelope.kind === "resync") {
          if (envelope.viewId !== this.snapshot.scope.viewId)
            return fail("PRESENTATION_SCOPE_MISMATCH");
          await this.#recover(false, "VIEW_RESYNC");
          return;
        }
        if (envelope.kind !== "update") return fail("SSE_ENVELOPE_INVALID");
        const next = applyPresentation(this.snapshot, envelope);
        if (next !== this.snapshot) {
          this.#subscription = freeze({
            ...this.#subscription,
            snapshot: next,
          });
          notify(this.#listeners);
        }
      }
      if (generation === this.#generation && this.#state !== "closed")
        await this.#recover(false, "CONNECTION_INTERRUPTED");
    } catch (error) {
      if (generation !== this.#generation || this.#state === "closed") return;
      const reason = code(error);
      if (
        reason === "TRANSPORT_UNAVAILABLE" ||
        reason === "CONNECTION_INTERRUPTED" ||
        reason === "SSE_STREAM_UNAVAILABLE"
      )
        await this.#recover(false, reason);
      else if (reason === "PRESENTATION_RESYNC_REQUIRED")
        await this.#recover(false, reason);
      else this.terminate(reason);
    }
  }
  async #recover(manual: boolean, reason: string): Promise<void> {
    if (this.#state === "closed") return fail("OBSERVATION_CLOSED");
    if (this.#recovery) return this.#recovery;
    if (manual) this.#attempts = 0;
    this.#recovery = (async () => {
      this.#generation++;
      this.#controller.abort();
      const old = this.#subscription.subscriptionId;
      this.#state = "recovering";
      this.#reason = reason;
      this.#backend.offline();
      notify(this.#listeners);
      await this.#backend.close(old);
      while (this.#attempts < 3 && !this.#life.signal.aborted) {
        if (!manual)
          await delay([100, 500, 1500][this.#attempts]!, this.#life.signal);
        this.#attempts++;
        try {
          await this.#backend.renew();
          const next = await this.#backend.subscribe();
          if (this.#life.signal.aborted) {
            void this.#backend.close(next.subscriptionId);
            return;
          }
          this.#subscription = next;
          this.#controller = new AbortController();
          this.#state = "live";
          this.#reason = null;
          notify(this.#listeners);
          return;
        } catch (error) {
          const reason = code(error);
          if (
            reason !== "TRANSPORT_UNAVAILABLE" &&
            reason !== "TRANSPORT_ABORTED" &&
            reason !== "TRANSPORT_TIMEOUT"
          ) {
            this.terminate(reason);
            return;
          }
        }
      }
      if (!this.#life.signal.aborted) {
        this.#state = "unavailable";
        this.#reason = "RECOVERY_EXHAUSTED";
        notify(this.#listeners);
      }
    })().finally(() => {
      this.#recovery = null;
      if (this.#state === "live") this.start();
    });
    return this.#recovery;
  }
  pause(reason: string): void {
    if (this.#state === "closed") return;
    this.#generation++;
    this.#controller.abort();
    this.#state = "recovering";
    this.#reason = reason;
    notify(this.#listeners);
  }
  unavailable(reason: string): void {
    if (this.#state !== "closed") {
      this.#state = "unavailable";
      this.#reason = reason;
      notify(this.#listeners);
    }
  }
  refresh(): Promise<void> {
    return this.#recover(true, "EXPLICIT_REFRESH");
  }
  async act(action: Action, input: ActionInput = {}): Promise<CommandResult> {
    if (this.#state !== "live" || this.snapshot.hydration !== "ready")
      return fail("VIEW_NOT_READY");
    const entries = this.snapshot.entries;
    const available = [
      ...this.snapshot.actions,
      ...entries.flatMap((e) => e.interaction?.actions ?? []),
    ];
    const exact = available.find(
      (a) => JSON.stringify(a) === JSON.stringify(action),
    );
    if (!exact?.available) return fail("ACTION_UNAVAILABLE");
    const interaction = entries.find(
      (e) => e.interaction?.id === action.target.interactionId,
    )?.interaction;
    if (interaction) {
      if (!supportsInteraction(interaction))
        return fail("INTERACTION_UNSUPPORTED");
      if (
        (action.choice === "approve" || action.kind === "allowForSession") &&
        (!interaction.review?.targetComplete ||
          interaction.review.contentTruncated)
      )
        return fail("REVIEW_INCOMPLETE");
      if (
        input.text !== undefined &&
        [...input.text].length > interaction.input.maximumCharacters
      )
        return fail("INTERACTION_INPUT_INVALID");
    }
    const target = { projectId: null, ...action.target };
    const command = {
      ...makeCommand(action.kind, target, input.text ?? null, action.choice),
      selections: input.selections ?? [],
    };
    return this.#backend.execute({
      command,
      options: {
        approved: false,
        includeInContext: false,
        answers: input.answers ?? [],
      },
    });
  }
  terminate(reason: string): void {
    this.#reason = reason;
    this.close();
  }
  close(): void {
    if (this.#state === "closed") return;
    this.#state = "closed";
    this.#generation++;
    this.#life.abort();
    this.#controller.abort();
    this.#backend.removed(this);
    void this.#backend.close(this.#subscription.subscriptionId);
    notify(this.#listeners);
    this.#listeners.clear();
  }
}

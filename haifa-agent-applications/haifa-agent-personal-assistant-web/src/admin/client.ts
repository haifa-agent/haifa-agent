import type { AdminRun, AdminSession, AdminTrace } from "./types";

const DEFAULT_ADMIN_API_ROOT = "http://127.0.0.1:20001/v1/admin";
const ADMIN_API_ROOT = (
  import.meta.env.VITE_PERSONAL_ASSISTANT_ADMIN_API_BASE_URL?.trim() ||
  DEFAULT_ADMIN_API_ROOT
).replace(/\/+$/, "");
const REQUEST_TIMEOUT_MILLIS = 12_000;

export interface PersonalAdminClient {
  sessions(signal?: AbortSignal): Promise<AdminSession[]>;
  runs(sessionId: string, signal?: AbortSignal): Promise<AdminRun[]>;
  trace(sessionId: string, runId: string, signal?: AbortSignal): Promise<AdminTrace>;
}

function encoded(value: string): string {
  return encodeURIComponent(value);
}

export class HttpPersonalAdminClient implements PersonalAdminClient {
  private async get<T>(path: string, parentSignal?: AbortSignal): Promise<T> {
    const controller = new AbortController();
    const timer = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MILLIS);
    const abort = () => controller.abort(parentSignal?.reason);
    parentSignal?.addEventListener("abort", abort, { once: true });
    try {
      const response = await fetch(`${ADMIN_API_ROOT}${path}`, {
        headers: { Accept: "application/json" },
        signal: controller.signal,
      });
      if (!response.ok) throw new Error(`Admin API request failed (HTTP ${response.status})`);
      return (await response.json()) as T;
    } finally {
      window.clearTimeout(timer);
      parentSignal?.removeEventListener("abort", abort);
    }
  }

  sessions(signal?: AbortSignal) {
    return this.get<AdminSession[]>("/sessions?limit=200", signal);
  }

  runs(sessionId: string, signal?: AbortSignal) {
    return this.get<AdminRun[]>(
      `/sessions/${encoded(sessionId)}/runs?limit=200`,
      signal,
    );
  }

  trace(sessionId: string, runId: string, signal?: AbortSignal) {
    return this.get<AdminTrace>(
      `/sessions/${encoded(sessionId)}/runs/${encoded(runId)}/tree`,
      signal,
    );
  }
}

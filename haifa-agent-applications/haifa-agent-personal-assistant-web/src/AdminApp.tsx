import {
  AlertTriangle,
  Bot,
  CircleAlert,
  RefreshCw,
  Search,
} from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { AdminTraceTree } from "./admin/AdminTraceTree";
import {
  HttpPersonalAdminClient,
  type PersonalAdminClient,
} from "./admin/client";
import type { AdminRun, AdminSession, AdminTrace } from "./admin/types";

const defaultClient = new HttpPersonalAdminClient();
const sessionParameter = "sessionId";
const runParameter = "runId";

function updateUrl(sessionId: string | null, runId: string | null): void {
  const url = new URL(window.location.href);
  if (sessionId) url.searchParams.set(sessionParameter, sessionId);
  else url.searchParams.delete(sessionParameter);
  if (runId) url.searchParams.set(runParameter, runId);
  else url.searchParams.delete(runParameter);
  window.history.replaceState({ sessionId, runId }, "", url);
}

function initialSelection() {
  const url = new URL(window.location.href);
  return {
    sessionId: url.searchParams.get(sessionParameter),
    runId: url.searchParams.get(runParameter),
  };
}

function formatted(value: string): string {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString("zh-CN");
}

function statusTone(status: string | null): string {
  const normalized = status?.toUpperCase() ?? "";
  if (["FAILED", "TIMEOUT", "DENIED", "CANCELLED", "CORRUPTED"].includes(normalized)) {
    return "failed";
  }
  if (["COMPLETED", "SUCCEEDED"].includes(normalized)) return "succeeded";
  if (["RUNNING", "STARTED"].includes(normalized)) return "running";
  if (normalized.includes("WAITING") || normalized === "PENDING") return "waiting";
  return "neutral";
}

export default function AdminApp({
  client = defaultClient,
}: {
  client?: PersonalAdminClient;
}) {
  const initial = initialSelection();
  const [sessions, setSessions] = useState<AdminSession[]>([]);
  const [runs, setRuns] = useState<AdminRun[]>([]);
  const [trace, setTrace] = useState<AdminTrace | null>(null);
  const [selectedSessionId, setSelectedSessionId] = useState<string | null>(
    initial.sessionId,
  );
  const [selectedRunId, setSelectedRunId] = useState<string | null>(initial.runId);
  const [query, setQuery] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [refreshVersion, setRefreshVersion] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    void client.sessions(controller.signal)
      .then(async (availableSessions) => {
        setSessions(availableSessions);
        const sessionId = availableSessions.some((item) => item.id === selectedSessionId)
          ? selectedSessionId
          : availableSessions[0]?.id ?? null;
        setSelectedSessionId(sessionId);
        if (!sessionId) {
          setRuns([]);
          setTrace(null);
          updateUrl(null, null);
          return;
        }
        const availableRuns = await client.runs(sessionId, controller.signal);
        setRuns(availableRuns);
        const runId = availableRuns.some((item) => item.id === selectedRunId)
          ? selectedRunId
          : availableRuns[0]?.id ?? null;
        setSelectedRunId(runId);
        updateUrl(sessionId, runId);
        setTrace(runId
          ? await client.trace(sessionId, runId, controller.signal)
          : null);
      })
      .catch((value: unknown) => {
        if (!controller.signal.aborted) {
          setError(value instanceof Error ? value.message : "Admin 数据加载失败");
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [client, refreshVersion]);

  const chooseSession = async (sessionId: string) => {
    setLoading(true);
    setError(null);
    try {
      const availableRuns = await client.runs(sessionId);
      const runId = availableRuns[0]?.id ?? null;
      setSelectedSessionId(sessionId);
      setSelectedRunId(runId);
      setRuns(availableRuns);
      updateUrl(sessionId, runId);
      setTrace(runId ? await client.trace(sessionId, runId) : null);
    } catch (value) {
      setError(value instanceof Error ? value.message : "Run 列表加载失败");
    } finally {
      setLoading(false);
    }
  };

  const chooseRun = async (runId: string) => {
    if (!selectedSessionId) return;
    setLoading(true);
    setError(null);
    try {
      setSelectedRunId(runId);
      updateUrl(selectedSessionId, runId);
      setTrace(await client.trace(selectedSessionId, runId));
    } catch (value) {
      setError(value instanceof Error ? value.message : "诊断树加载失败");
    } finally {
      setLoading(false);
    }
  };

  const visibleSessions = useMemo(() => {
    const normalized = query.trim().toLocaleLowerCase();
    return normalized
      ? sessions.filter((session) => session.id.toLocaleLowerCase().includes(normalized))
      : sessions;
  }, [query, sessions]);

  const selectedRun = runs.find((run) => run.id === selectedRunId) ?? null;

  return (
    <main className="admin-app">
      <header className="admin-topbar">
        <div className="admin-brand">
          <span className="admin-brand-icon"><Bot size={19} /></span>
          <div><strong>Haifa Agent</strong><span>Run Diagnostics</span></div>
        </div>
        <div className="admin-sensitive-warning">
          <AlertTriangle size={16} />
          <span>本机敏感视图 · 包含完整 Prompt、Tool 参数与结果</span>
        </div>
        <button
          className="admin-refresh"
          type="button"
          onClick={() => setRefreshVersion((value) => value + 1)}
        >
          <RefreshCw className={loading ? "spin" : ""} size={16} />
          刷新
        </button>
      </header>

      <div className="admin-workspace">
        <aside className="admin-rail sessions" aria-label="Sessions">
          <div className="admin-rail-heading">
            <span>SESSIONS</span><strong>{sessions.length}</strong>
          </div>
          <label className="admin-search">
            <Search size={15} />
            <input
              aria-label="搜索 Session"
              placeholder="按 Session ID 搜索"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
            />
          </label>
          <div className="admin-list">
            {visibleSessions.map((session) => (
              <button
                className={`admin-list-item ${session.id === selectedSessionId ? "selected" : ""}`}
                key={session.id}
                type="button"
                onClick={() => void chooseSession(session.id)}
              >
                <span className="admin-list-title">{session.id}</span>
                <span>{formatted(session.updatedAt)}</span>
                <span>
                  {session.runCount} runs
                  <i className={`admin-status ${statusTone(session.latestRunStatus)}`}>
                    {session.latestRunStatus ?? session.status}
                  </i>
                </span>
              </button>
            ))}
            {!visibleSessions.length && <p className="admin-empty">没有 Session</p>}
          </div>
        </aside>

        <aside className="admin-rail runs" aria-label="Runs">
          <div className="admin-rail-heading">
            <span>RUNS</span><strong>{runs.length}</strong>
          </div>
          <div className="admin-list">
            {runs.map((run) => (
              <button
                className={`admin-list-item run ${run.id === selectedRunId ? "selected" : ""}`}
                key={run.id}
                type="button"
                onClick={() => void chooseRun(run.id)}
              >
                <span className="admin-list-title">{run.id}</span>
                <span className="admin-objective">{run.objective}</span>
                <span>
                  {formatted(run.createdAt)}
                  <i className={`admin-status ${statusTone(run.status)}`}>{run.status}</i>
                </span>
                {run.errorCode && <em>{run.errorCode}</em>}
              </button>
            ))}
            {!runs.length && <p className="admin-empty">该 Session 没有 Run</p>}
          </div>
        </aside>

        <section className="admin-main">
          <div className="admin-page-heading">
            <div>
              <span className="eyebrow">ONE SESSION · ONE RUN</span>
              <h1>{selectedRun ? `Run ${selectedRun.id.slice(0, 12)}` : "Run 诊断树"}</h1>
              <p>{selectedRun?.objective ?? "选择一个 Session 和 Run 查看执行链路。"}</p>
            </div>
            {trace?.failureNodeId && (
              <div className="admin-failure-focus">
                <CircleAlert size={17} />
                已定位到失败节点
              </div>
            )}
          </div>
          {error && <div className="admin-error"><CircleAlert size={17} />{error}</div>}
          {trace && <AdminTraceTree trace={trace} />}
          {!trace && !loading && !error && (
            <div className="admin-empty large">当前没有可展示的 Run 诊断数据。</div>
          )}
          {loading && <div className="admin-loading"><RefreshCw className="spin" size={20} />正在读取诊断事实…</div>}
        </section>
      </div>
    </main>
  );
}

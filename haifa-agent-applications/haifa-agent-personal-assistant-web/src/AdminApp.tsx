import {
  AlertTriangle,
  Bot,
  Boxes,
  CircleAlert,
  Database,
  ListTree,
  RefreshCw,
  Search,
  Sparkles,
  Wrench,
} from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { AdminCapabilityDetail } from "./admin/AdminCapabilityDetail";
import { AdminTraceTree } from "./admin/AdminTraceTree";
import {
  HttpPersonalAdminClient,
  type PersonalAdminClient,
} from "./admin/client";
import type {
  AdminCapabilities,
  AdminCapabilityKind,
  AdminRun,
  AdminSession,
  AdminTrace,
} from "./admin/types";

const defaultClient = new HttpPersonalAdminClient();
const sessionParameter = "sessionId";
const runParameter = "runId";
const capabilityParameter = "capabilityId";
const capabilityKindParameter = "kind";

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

function initialCapabilitySelection() {
  const url = new URL(window.location.href);
  const value = url.searchParams.get(capabilityKindParameter)?.toUpperCase();
  return {
    kind: value === "MCP" || value === "SKILL" ? value : "TOOL",
    capabilityId: url.searchParams.get(capabilityParameter),
  } satisfies { kind: AdminCapabilityKind; capabilityId: string | null };
}

function updateCapabilityUrl(kind: AdminCapabilityKind, capabilityId: string | null): void {
  const url = new URL(window.location.href);
  url.searchParams.delete(sessionParameter);
  url.searchParams.delete(runParameter);
  url.searchParams.set(capabilityKindParameter, kind.toLowerCase());
  if (capabilityId) url.searchParams.set(capabilityParameter, capabilityId);
  else url.searchParams.delete(capabilityParameter);
  window.history.replaceState({ kind, capabilityId }, "", url);
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
  const capabilityMode = window.location.pathname.replace(/\/+$/, "")
    .endsWith("/capabilities");
  const initial = initialSelection();
  const initialCapability = initialCapabilitySelection();
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
  const [capabilities, setCapabilities] = useState<AdminCapabilities | null>(null);
  const [selectedKind, setSelectedKind] = useState<AdminCapabilityKind>(
    initialCapability.kind,
  );
  const [selectedCapabilityId, setSelectedCapabilityId] = useState<string | null>(
    initialCapability.capabilityId,
  );
  const [capabilityQuery, setCapabilityQuery] = useState("");

  useEffect(() => {
    if (capabilityMode) return;
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
  }, [capabilityMode, client, refreshVersion]);

  useEffect(() => {
    if (!capabilityMode) return;
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    void client.capabilities(controller.signal)
      .then((value) => {
        setCapabilities(value);
        const sameKind = value.registrations.filter((item) => item.kind === selectedKind);
        const capabilityId = sameKind.some((item) => item.id === selectedCapabilityId)
          ? selectedCapabilityId
          : sameKind[0]?.id ?? null;
        setSelectedCapabilityId(capabilityId);
        updateCapabilityUrl(selectedKind, capabilityId);
      })
      .catch((value: unknown) => {
        if (!controller.signal.aborted) {
          setError(value instanceof Error ? value.message : "注册能力加载失败");
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [capabilityMode, client, refreshVersion]);

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
  const counts = useMemo(() => {
    const values = new Map<AdminCapabilityKind, number>([
      ["TOOL", 0],
      ["MCP", 0],
      ["SKILL", 0],
    ]);
    capabilities?.registrations.forEach((item) => {
      values.set(item.kind, (values.get(item.kind) ?? 0) + 1);
    });
    return values;
  }, [capabilities]);
  const visibleCapabilities = useMemo(() => {
    const normalized = capabilityQuery.trim().toLocaleLowerCase();
    return (capabilities?.registrations ?? [])
      .filter((item) => item.kind === selectedKind)
      .filter((item) => !normalized ||
        item.name.toLocaleLowerCase().includes(normalized) ||
        item.displayName.toLocaleLowerCase().includes(normalized) ||
        item.source.toLocaleLowerCase().includes(normalized));
  }, [capabilities, capabilityQuery, selectedKind]);
  const selectedCapability = capabilities?.registrations
    .find((item) => item.id === selectedCapabilityId) ?? null;

  const chooseCapabilityKind = (kind: AdminCapabilityKind) => {
    const capabilityId = capabilities?.registrations
      .find((item) => item.kind === kind)?.id ?? null;
    setSelectedKind(kind);
    setSelectedCapabilityId(capabilityId);
    setCapabilityQuery("");
    updateCapabilityUrl(kind, capabilityId);
  };

  const chooseCapability = (capabilityId: string) => {
    setSelectedCapabilityId(capabilityId);
    updateCapabilityUrl(selectedKind, capabilityId);
  };

  return (
    <main className="admin-app">
      <header className="admin-topbar">
        <div className="admin-brand-group">
          <div className="admin-brand">
            <span className="admin-brand-icon"><Bot size={19} /></span>
            <div>
              <strong>Haifa Agent</strong>
              <span>{capabilityMode ? "Registered Capabilities" : "Run Diagnostics"}</span>
            </div>
          </div>
          <nav className="admin-primary-nav" aria-label="Admin 功能">
            <a className={!capabilityMode ? "selected" : ""} href="/admin/">
              <ListTree size={14} />Runs
            </a>
            <a className={capabilityMode ? "selected" : ""} href="/admin/capabilities">
              <Boxes size={14} />Capabilities
            </a>
          </nav>
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

      {!capabilityMode && <div className="admin-workspace">
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
      </div>}

      {capabilityMode && (
        <div className="admin-capability-workspace">
          <aside className="admin-rail admin-kind-rail" aria-label="能力类型">
            <div className="admin-rail-heading">
              <span>CAPABILITY TYPES</span>
              <strong>{capabilities?.registrations.length ?? 0}</strong>
            </div>
            <div className="admin-kind-list">
              {([
                ["TOOL", Wrench, "Tools"],
                ["MCP", Database, "MCP Servers"],
                ["SKILL", Sparkles, "Skills"],
              ] as const).map(([kind, Icon, label]) => (
                <button
                  className={kind === selectedKind ? "selected" : ""}
                  key={kind}
                  type="button"
                  onClick={() => chooseCapabilityKind(kind)}
                >
                  <span><Icon size={16} />{label}</span>
                  <strong>{counts.get(kind) ?? 0}</strong>
                </button>
              ))}
            </div>
            {capabilities && (
              <dl className="admin-catalog-digests">
                <div>
                  <dt>Tool catalog</dt>
                  <dd>{capabilities.toolCatalogDigest}</dd>
                </div>
                <div>
                  <dt>Skill catalog</dt>
                  <dd>{capabilities.skillCatalogDigest}</dd>
                </div>
                <div>
                  <dt>Skill resolution</dt>
                  <dd>{capabilities.skillResolutionPolicy}</dd>
                </div>
              </dl>
            )}
          </aside>

          <aside className="admin-rail admin-capability-list-rail" aria-label="注册能力">
            <div className="admin-rail-heading">
              <span>{selectedKind} REGISTRATIONS</span>
              <strong>{counts.get(selectedKind) ?? 0}</strong>
            </div>
            <label className="admin-search">
              <Search size={15} />
              <input
                aria-label="搜索注册能力"
                placeholder="按名称或来源搜索"
                value={capabilityQuery}
                onChange={(event) => setCapabilityQuery(event.target.value)}
              />
            </label>
            <div className="admin-list">
              {visibleCapabilities.map((capability) => (
                <button
                  className={`admin-list-item admin-capability-item ${
                    capability.id === selectedCapabilityId ? "selected" : ""
                  }`}
                  key={capability.id}
                  type="button"
                  onClick={() => chooseCapability(capability.id)}
                >
                  <span className="admin-list-title">{capability.name}</span>
                  <span className="admin-objective">{capability.displayName}</span>
                  <span>
                    {capability.source}
                    <i className="admin-status succeeded">{capability.status}</i>
                  </span>
                </button>
              ))}
              {!visibleCapabilities.length && (
                <p className="admin-empty">没有匹配的注册能力</p>
              )}
            </div>
          </aside>

          <section className="admin-main admin-capability-main">
            {error && <div className="admin-error"><CircleAlert size={17} />{error}</div>}
            {selectedCapability && (
              <AdminCapabilityDetail capability={selectedCapability} />
            )}
            {!selectedCapability && !loading && !error && (
              <div className="admin-empty large">当前没有可展示的注册能力。</div>
            )}
            {loading && (
              <div className="admin-loading">
                <RefreshCw className="spin" size={20} />正在读取冻结注册快照…
              </div>
            )}
          </section>
        </div>
      )}
    </main>
  );
}

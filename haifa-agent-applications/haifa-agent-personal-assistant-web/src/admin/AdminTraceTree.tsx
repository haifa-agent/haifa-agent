import {
  Bot,
  Braces,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  CircleDot,
  Cpu,
  Database,
  FileClock,
  FolderTree,
  MessageSquareText,
  ShieldQuestion,
  Sparkles,
  Wrench,
  XCircle,
} from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import type { AdminTrace, AdminTraceNode } from "./types";

const failureStatuses = new Set([
  "FAILED",
  "TIMEOUT",
  "DENIED",
  "CANCELLED",
  "CORRUPTED",
  "ABANDONED",
]);

function icon(kind: string) {
  if (kind === "run" || kind === "attempt" || kind === "step") return Bot;
  if (kind === "message") return MessageSquareText;
  if (kind === "configuration") return Braces;
  if (kind === "tool" || kind === "skill_tool" || kind === "execution_tool") return Wrench;
  if (kind === "mcp") return Database;
  if (kind === "checkpoint") return FileClock;
  if (kind === "interaction" || kind === "approval" || kind === "interaction_response") {
    return ShieldQuestion;
  }
  if (kind === "skill") return Sparkles;
  if (kind === "group") return FolderTree;
  if (kind === "event") return CircleDot;
  return Cpu;
}

function formatDuration(value: number | null): string | null {
  if (value === null) return null;
  if (value < 1_000) return `${value} ms`;
  return `${(value / 1_000).toFixed(value < 10_000 ? 1 : 0)} s`;
}

function formatDate(value: string | null): string {
  if (!value) return "—";
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString("zh-CN");
}

function statusTone(status: string | null): string {
  if (!status) return "neutral";
  const normalized = status.toUpperCase();
  if (failureStatuses.has(normalized)) return "failed";
  if (["COMPLETED", "SUCCEEDED", "FROZEN", "ACTIVATED", "APPLIED"].includes(normalized)) {
    return "succeeded";
  }
  if (["RUNNING", "STARTED", "STREAMING"].includes(normalized)) return "running";
  if (normalized.includes("WAITING") || normalized === "PENDING") return "waiting";
  return "neutral";
}

export function AdminTraceTree({ trace }: { trace: AdminTrace }) {
  const allNodes = useMemo(() => [trace.root, ...trace.nodes], [trace]);
  const byId = useMemo(
    () => new Map(allNodes.map((node) => [node.id, node])),
    [allNodes],
  );
  const children = useMemo(() => {
    const values = new Map<string, AdminTraceNode[]>();
    for (const node of trace.nodes) {
      const parent = node.parentId ?? trace.root.id;
      values.set(parent, [...(values.get(parent) ?? []), node]);
    }
    for (const list of values.values()) {
      list.sort((left, right) => {
        const sequence = (left.sequence ?? Number.MAX_SAFE_INTEGER) -
          (right.sequence ?? Number.MAX_SAFE_INTEGER);
        return sequence || (left.startedAt ?? "").localeCompare(right.startedAt ?? "");
      });
    }
    return values;
  }, [trace]);
  const [selectedId, setSelectedId] = useState(trace.failureNodeId ?? trace.root.id);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());

  useEffect(() => {
    setSelectedId(trace.failureNodeId ?? trace.root.id);
    setCollapsed(new Set());
  }, [trace]);

  const selected = byId.get(selectedId) ?? trace.root;

  const renderNode = (node: AdminTraceNode, depth: number): React.ReactNode => {
    const nested = children.get(node.id) ?? [];
    const hasChildren = nested.length > 0;
    const isCollapsed = collapsed.has(node.id);
    const Icon = icon(node.kind);
    const tone = statusTone(node.status);
    return (
      <div key={node.id}>
        <button
          className={`admin-tree-node ${selected.id === node.id ? "selected" : ""}`}
          style={{ paddingLeft: `${10 + depth * 22}px` }}
          type="button"
          onClick={() => setSelectedId(node.id)}
        >
          <span
            className="admin-tree-toggle"
            onClick={(event) => {
              event.stopPropagation();
              if (!hasChildren) return;
              setCollapsed((current) => {
                const next = new Set(current);
                if (next.has(node.id)) next.delete(node.id);
                else next.add(node.id);
                return next;
              });
            }}
          >
            {hasChildren && (isCollapsed
              ? <ChevronRight size={15} />
              : <ChevronDown size={15} />)}
          </span>
          <span className={`admin-node-icon ${tone}`}><Icon size={15} /></span>
          <span className="admin-node-label">{node.label}</span>
          {node.status && <span className={`admin-status ${tone}`}>{node.status}</span>}
          {formatDuration(node.durationMillis) && (
            <span className="admin-duration">{formatDuration(node.durationMillis)}</span>
          )}
        </button>
        {hasChildren && !isCollapsed && nested.map((child) => renderNode(child, depth + 1))}
      </div>
    );
  };

  const SelectedStatusIcon = statusTone(selected.status) === "failed"
    ? XCircle
    : CheckCircle2;

  return (
    <div className="admin-trace-grid">
      <section className="admin-tree-card" aria-label="Run 诊断树">
        <div className="admin-tree-legend">
          <span><Bot size={14} /> Agent Loop</span>
          <span><MessageSquareText size={14} /> Prompt</span>
          <span><Wrench size={14} /> Tool</span>
          <span><CircleDot size={14} /> Event</span>
        </div>
        <div className="admin-tree-scroll">{renderNode(trace.root, 0)}</div>
      </section>
      <aside className="admin-detail-card" aria-label="节点详情">
        <div className="admin-detail-heading">
          <span className={`admin-node-icon ${statusTone(selected.status)}`}>
            <SelectedStatusIcon size={16} />
          </span>
          <div>
            <small>SELECTED NODE</small>
            <h2>{selected.label}</h2>
          </div>
        </div>
        <dl className="admin-detail-grid">
          <div><dt>类型</dt><dd>{selected.kind}</dd></div>
          <div><dt>状态</dt><dd>{selected.status ?? "—"}</dd></div>
          <div><dt>开始</dt><dd>{formatDate(selected.startedAt)}</dd></div>
          <div><dt>耗时</dt><dd>{formatDuration(selected.durationMillis) ?? "—"}</dd></div>
        </dl>
        {selected.summary && (
          <div className="admin-summary">
            <small>摘要</small>
            <p>{selected.summary}</p>
          </div>
        )}
        <div className="admin-raw">
          <small>完整持久化内容</small>
          <pre>{JSON.stringify(selected.details, null, 2)}</pre>
        </div>
      </aside>
    </div>
  );
}

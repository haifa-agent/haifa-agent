import { useState, useEffect, useRef } from "react";
import { Brain, Zap, Database, Cpu, Square, X, CircleAlert } from "lucide-react";
import type { Run, Activity } from "../../api/generated";
import { Button } from "../common/Button";
import {
  number,
  formatTime,
  isTerminal,
  executionErrorGuidance,
  statusLabel,
} from "../../utils/formatters";

export function ActivityIcon({ kind }: { kind: Activity["kind"] }) {
  if (kind === "MODEL") return <Brain size={17} />;
  if (kind === "SKILL") return <Zap size={17} />;
  if (kind === "MCP") return <Database size={17} />;
  return <Cpu size={17} />;
}

export function UsagePanel({ run }: { run: Run | null }) {
  if (!run || !isTerminal(run)) return <p className="muted">任务结束后显示后端报告的 Token 使用量。</p>;
  const usage = run.usage;
  return (
    <div className="usage-grid" aria-label="本次运行 Token 消耗">
      <span>输入<strong>{number.format(usage.inputTokens)}</strong></span>
      <span>输出<strong>{number.format(usage.outputTokens)}</strong></span>
      <span>总计<strong>{number.format(usage.totalTokens)}</strong></span>
      <span>缓存输入<strong>{number.format(usage.cachedInputTokens)}</strong></span>
      <span>模型调用<strong>{number.format(usage.modelCalls)}</strong></span>
      <span>工具调用<strong>{number.format(usage.toolCalls)}</strong></span>
    </div>
  );
}

export function ActivityFeed({ activities, emptyText }: { activities: Activity[]; emptyText: string }) {
  return (
    <div className="activity-list">
      {activities.map((activity) => (
        <article className={`activity-card ${activity.parentActivityId ? "activity-child" : ""}`} key={activity.activityId}>
          <div className={`activity-kind kind-${activity.kind.toLowerCase()}`}>
            <ActivityIcon kind={activity.kind} /><span>{activity.kind}</span><small>{statusLabel(activity.status)}</small>
          </div>
          <strong>{activity.displayName}</strong>
          {activity.safeTargetSummary && <pre className="activity-summary">{activity.safeTargetSummary}</pre>}
          {activity.safeResultSummary && <pre className="activity-summary safe-result">{activity.safeResultSummary}</pre>}
          {activity.parentActivityId && <small className="activity-relation">关联上级操作</small>}
          <time>{formatTime(activity.startedAt ?? activity.requestedAt ?? activity.occurredAt)}</time>
        </article>
      ))}
      {!activities.length && <p className="muted">{emptyText}</p>}
    </div>
  );
}

export interface ActivityRightPanelProps {
  open: boolean;
  focusRequest: number;
  run: Run | null;
  activities: Activity[];
  pending: boolean;
  onClose(): void;
  onCancel(): void;
}

export function ActivityRightPanel({
  open,
  focusRequest,
  run,
  activities,
  pending,
  onClose,
  onCancel,
}: ActivityRightPanelProps) {
  const panelRef = useRef<HTMLElement>(null);
  const [attention, setAttention] = useState(false);

  useEffect(() => {
    if (!activities.length || !panelRef.current) return;
    panelRef.current.scrollTop = panelRef.current.scrollHeight;
  }, [activities]);

  useEffect(() => {
    if (!focusRequest || !panelRef.current) return;
    const panel = panelRef.current;
    panel.focus({ preventScroll: true });
    if (typeof panel.scrollIntoView === "function") {
      panel.scrollIntoView({ behavior: "smooth", block: "nearest", inline: "nearest" });
    }
    setAttention(true);
    const timer = window.setTimeout(() => setAttention(false), 1_200);
    return () => window.clearTimeout(timer);
  }, [focusRequest]);

  return (
    <>
      {open && <button className="scrim right" aria-label="关闭运行详情" onClick={onClose} />}
      <aside
        ref={panelRef}
        className={`activity-panel ${open ? "drawer-open" : ""}${attention ? " activity-panel-attention" : ""}`}
        aria-label="当前运行详情"
        tabIndex={-1}
      >
        <div className="panel-heading">
          <div>
            <span className="eyebrow">CURRENT RUN</span>
            <div className="run-heading-row">
              <h2>{run ? statusLabel(run.status) : "暂无运行"}</h2>
              {run && !isTerminal(run) && (
                <Button
                  className="run-cancel-button"
                  busy={pending}
                  aria-label="停止当前任务"
                  title="停止当前任务"
                  onClick={onCancel}
                >
                  <Square size={11} fill="currentColor" aria-hidden="true" />
                  <span>停止当前任务</span>
                </Button>
              )}
            </div>
          </div>
          <button className="icon mobile-only" aria-label="关闭运行详情" onClick={onClose}><X size={18} /></button>
        </div>
        <section className="panel-section">
          <h3>安全活动</h3>
          <ActivityFeed activities={activities} emptyText="当前运行尚无 Model、Tool、Skill 或 MCP 活动。" />
        </section>
        <section className="panel-section"><h3>Token 使用</h3><UsagePanel run={run} /></section>
        {run?.error && (
          <div className="safe-error">
            <CircleAlert size={16} />
            <span>
              任务未完成：[{run.error.code}] {run.error.message}
              {run.error.diagnosticId && <> · 诊断编号：{run.error.diagnosticId}</>}
              <> · {executionErrorGuidance(run.error)}</>
            </span>
          </div>
        )}
        {!run?.error && run?.errorCode && <div className="safe-error"><CircleAlert size={16} /><span>任务未完成：{run.errorCode}</span></div>}
      </aside>
    </>
  );
}

export { ActivityRightPanel as ActivityPanel };

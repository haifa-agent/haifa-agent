import { CheckCircle2, CircleAlert } from "lucide-react";
import type { MissionSnapshot } from "../../api/generated";
import { parseMissionFinalResult } from "./missionUtils";

export function MissionDeliveryCard({
  mission,
  title,
  onOpenReport,
  onOpenEvidence,
  onContinue,
}: {
  mission: MissionSnapshot;
  title: string;
  onOpenReport(): void;
  onOpenEvidence(): void;
  onContinue(): void;
}) {
  const result = parseMissionFinalResult(mission.finalResult);
  if (!result || result.schemaVersion !== "pa.research-delivery/v2") return null;
  const evidence = result.evidenceSummary;
  const status = result.degraded
    ? "降级完成"
    : result.completionKind === "PARTIAL" ? "部分完成" : "已完成";
  return <section className="mission-delivery-card" aria-label="Deep Research Mission 交付">
    <header><div><span className="mission-delivery-status"><CheckCircle2 size={15} />{status}</span><h3>{title}</h3></div><span>Deep Research</span></header>
    <p>调研报告与完整证据链已生成。普通对话保留摘要，全文与技术交付文件在 Mission 中查看。</p>
    <div className="mission-delivery-metrics" aria-label="证据汇总">
      <span><b>{mission.tasks.filter((task) => task.state === "COMPLETED").length}</b>任务</span>
      <span><b>{evidence?.totalClaimCount ?? 0}</b>结论</span>
      <span><b>{result.sourceCount ?? 0}</b>来源</span>
      <span><b>{evidence?.unresolvedQuestionCount ?? result.unresolvedQuestionCount ?? 0}</b>未决</span>
    </div>
    {(evidence?.unverifiedClaimCount ?? result.unverifiedClaimCount ?? 0) > 0 && <p className="mission-delivery-warning"><CircleAlert size={15} />本报告包含尚未充分核实的判断，不应解读为所有关键结论均已确认。</p>}
    <div className="mission-delivery-actions">
      <button type="button" className="button primary" onClick={onOpenReport}>查看完整报告</button>
      <button type="button" className="button" onClick={onOpenEvidence}>证据与来源</button>
      <button type="button" className="button" onClick={onContinue}>继续追问</button>
    </div>
  </section>;
}


import type { ExecutionError, Run } from "../api/generated";
import { PersonalAssistantApiError } from "../api/client";

export const conversationIdParameter = "conversationId";

export const number = new Intl.NumberFormat("zh-CN");

export const dateTime = new Intl.DateTimeFormat("zh-CN", {
  month: "numeric",
  day: "numeric",
  hour: "2-digit",
  minute: "2-digit",
});

export const terminalStatuses = new Set(["COMPLETED", "FAILED", "CANCELLED", "TIMEOUT"]);

export function formatTime(value: string): string {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : dateTime.format(parsed);
}

export function formatElapsedTime(seconds: number): string {
  if (seconds < 60) return `${seconds}秒`;
  const minutes = Math.floor(seconds / 60);
  const remainingSeconds = seconds % 60;
  if (minutes < 60) return `${minutes}分${String(remainingSeconds).padStart(2, "0")}秒`;
  const hours = Math.floor(minutes / 60);
  return `${hours}时${String(minutes % 60).padStart(2, "0")}分${String(remainingSeconds).padStart(2, "0")}秒`;
}

export function isTerminal(run: Run | null): boolean {
  return Boolean(run && terminalStatuses.has(run.status));
}

export function executionErrorGuidance(error: ExecutionError): string {
  if (error.code === "TOOL_OUTCOME_UNKNOWN") return "请先确认工具是否已经生效，不要直接重复执行。";
  if (error.code === "RUN_BUDGET_EXCEEDED") return "可缩小任务范围后重新发起。";
  if (error.retryability.startsWith("RETRYABLE")) return "可以稍后重试本次请求。";
  return "如需协助，请提供诊断编号。";
}

export function statusLabel(status: string): string {
  const labels: Record<string, string> = {
    ACTIVE: "活跃",
    ARCHIVED: "已归档",
    CREATED: "已创建",
    PENDING: "准备中",
    QUEUED: "排队中",
    RUNNING: "运行中",
    SUSPENDING: "暂停中",
    SUSPENDED: "已暂停",
    WAITING_FOR_INTERACTION: "等待回复",
    WAITING_FOR_APPROVAL: "等待确认",
    WAITING_INTERACTION: "等待回复",
    WAITING_APPROVAL: "等待确认",
    COMPLETING: "整理结果中",
    COMPLETED: "已完成",
    FAILED: "失败",
    CANCELLED: "已停止",
    TIMEOUT: "已超时",
    STARTED: "进行中",
    REQUESTED: "准备调用",
    SUCCEEDED: "已完成",
    TIMED_OUT: "已超时",
    APPROVE: "批准",
    REJECT: "拒绝",
    SUBMIT: "提交",
  };
  return labels[status] ?? status;
}

export function safeError(error: unknown): string {
  if (error instanceof DOMException && error.name === "AbortError") return "";
  if (error instanceof PersonalAssistantApiError) {
    return `${error.message}（${error.code}，关联号 ${error.correlationId}）`;
  }
  return error instanceof Error ? error.message : "请求失败，请稍后重试";
}

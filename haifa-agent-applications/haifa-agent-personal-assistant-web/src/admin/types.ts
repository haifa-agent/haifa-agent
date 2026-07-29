export interface AdminSession {
  id: string;
  status: string;
  createdAt: string;
  updatedAt: string;
  runCount: number;
  latestRunStatus: string | null;
}

export interface AdminRun {
  id: string;
  sessionId: string;
  status: string;
  objective: string;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
  errorCode: string | null;
}

export interface AdminTraceNode {
  id: string;
  parentId: string | null;
  kind: string;
  label: string;
  status: string | null;
  startedAt: string | null;
  completedAt: string | null;
  durationMillis: number | null;
  sequence: number | null;
  summary: string | null;
  details: Record<string, unknown>;
}

export interface AdminTrace {
  sessionId: string;
  runId: string;
  root: AdminTraceNode;
  nodes: AdminTraceNode[];
  failureNodeId: string | null;
}

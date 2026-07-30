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

export type AdminCapabilityKind = "TOOL" | "MCP" | "SKILL";

export interface AdminCapabilityAttribute {
  label: string;
  value: string;
  tone: "neutral" | "failed" | "succeeded" | "running" | "waiting";
}

export interface AdminCapability {
  id: string;
  kind: AdminCapabilityKind;
  name: string;
  displayName: string;
  description: string;
  status: string;
  source: string;
  tags: string[];
  attributes: AdminCapabilityAttribute[];
  details: Record<string, unknown>;
}

export interface AdminCapabilities {
  toolCatalogDigest: string;
  skillCatalogDigest: string;
  skillResolutionPolicy: string;
  registrations: AdminCapability[];
}

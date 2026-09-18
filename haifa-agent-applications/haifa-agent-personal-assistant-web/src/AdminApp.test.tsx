import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import AdminApp from "./AdminApp";
import type { PersonalAdminClient } from "./admin/client";
import type {
  AdminCapabilities,
  AdminModels,
  AdminRun,
  AdminSession,
  AdminTrace,
} from "./admin/types";

const session: AdminSession = {
  id: "session-sensitive",
  status: "ACTIVE",
  createdAt: "2026-07-29T01:00:00Z",
  updatedAt: "2026-07-29T01:01:00Z",
  runCount: 1,
  latestRunStatus: "FAILED",
};

const run: AdminRun = {
  id: "run-failed",
  sessionId: session.id,
  status: "FAILED",
  objective: "Objective hidden · 27 characters",
  createdAt: "2026-07-29T01:00:00Z",
  updatedAt: "2026-07-29T01:01:00Z",
  completedAt: "2026-07-29T01:01:00Z",
  errorCode: "TOOL_FAILED",
};

const trace: AdminTrace = {
  sessionId: session.id,
  runId: run.id,
  root: {
    id: `run:${run.id}`,
    parentId: null,
    kind: "run",
    label: "Run run-failed",
    status: "FAILED",
    startedAt: run.createdAt,
    completedAt: run.completedAt,
    durationMillis: 60_000,
    sequence: null,
    summary: "TOOL_FAILED",
    details: { objectiveCharacterCount: 27 },
  },
  nodes: [
    {
      id: "tool:tool-call-1",
      parentId: `run:${run.id}`,
      kind: "tool",
      label: "personal_checklist",
      status: "FAILED",
      startedAt: run.createdAt,
      completedAt: run.completedAt,
      durationMillis: 1_200,
      sequence: 3,
      summary: "TOOL_FAILED",
      details: {
        arguments: { schemaVersion: "v1", contentHidden: true },
        error: {
          schemaVersion: "v1",
          code: "TOOL_FAILED",
          diagnosticId: "diag-admin-safe",
        },
      },
    },
  ],
  failureNodeId: "tool:tool-call-1",
};

const capabilities: AdminCapabilities = {
  toolCatalogDigest: "tool-digest",
  skillCatalogDigest: "skill-digest",
  skillResolutionPolicy: "personal-default",
  registrations: [
    {
      id: "tool:execution_run",
      kind: "TOOL",
      name: "execution_run",
      displayName: "Run reviewed local execution",
      description: "Runs a reviewed execution request.",
      status: "FROZEN",
      source: "personal-execution",
      tags: ["execution"],
      attributes: [
        { label: "Risk", value: "HIGH", tone: "failed" },
      ],
      details: { behavior: { approvalRequirement: "REQUIRED" } },
    },
    {
      id: "mcp:personal-local",
      kind: "MCP",
      name: "personal-local",
      displayName: "Personal local MCP",
      description: "Reviewed MCP server registration.",
      status: "READY",
      source: "http://127.0.0.1:20002/mcp",
      tags: ["mcp"],
      attributes: [
        { label: "Protocol", value: "2025-11-25", tone: "succeeded" },
      ],
      details: { importedTools: [{ alias: "personal_mcp_echo" }] },
    },
    {
      id: "skill:daily-planning",
      kind: "SKILL",
      name: "daily-planning",
      displayName: "daily-planning",
      description: "Create a daily plan.",
      status: "FROZEN",
      source: "classpath:daily-planning",
      tags: ["personal_checklist"],
      attributes: [
        { label: "Resources", value: "1", tone: "neutral" },
      ],
      details: { package: { resources: [{ relativePath: "SKILL.md" }] } },
    },
  ],
};

const models: AdminModels = {
  bindings: [
    {
      id: "deepseek-v4-flash-openai-chat",
      modelGroupId: "deepseek:deepseek-v4-flash",
      modelDisplayName: "DeepSeek V4 Flash",
      displayName: "DeepSeek V4 Flash Chat",
      providerId: "deepseek",
      providerDisplayName: "DeepSeek",
      apiStyle: "openai-chat-completions",
      apiStyleDisplayName: "Chat Completions",
      availability: "AVAILABLE",
      safeErrorCode: null,
      capabilities: ["TEXT_CHAT", "TOOL_CALLING", "REASONING"],
      contextWindow: 131072,
      maxOutputTokens: 8192,
      preferenceSchemaVersion: "pa-model-preference-v1",
      profileVersion: "deepseek-v4-flash-chat-v1",
      profileDigest: "sha256:admin-safe-profile-digest",
      validationStatus: "VERIFIED",
      lastVerifiedOn: "2026-08-13",
    },
  ],
};

function client(): PersonalAdminClient {
  return {
    sessions: vi.fn(async () => [session]),
    runs: vi.fn(async () => [run]),
    trace: vi.fn(async () => trace),
    capabilities: vi.fn(async () => capabilities),
    models: vi.fn(async () => models),
  };
}

describe("Personal Assistant Admin application", () => {
  beforeEach(() => {
    window.history.replaceState(null, "", "/admin/");
  });

  it("loads one session run tree and shows only safe diagnostic content", async () => {
    const api = client();
    render(<AdminApp client={api} />);

    expect(await screen.findByText("已定位到失败节点")).toBeTruthy();
    expect(screen.getAllByText("personal_checklist").length).toBeGreaterThan(0);
    expect(screen.getByText(/contentHidden/)).toBeTruthy();
    expect(screen.getByText(/diag-admin-safe/)).toBeTruthy();
    expect(screen.queryByText(/完整敏感 Prompt|tool-secret|原始工具错误/)).toBeNull();
    expect(api.sessions).toHaveBeenCalled();
    expect(api.runs).toHaveBeenCalledWith(session.id, expect.any(AbortSignal));
    expect(api.trace).toHaveBeenCalledWith(session.id, run.id, expect.any(AbortSignal));
    expect(new URL(window.location.href).searchParams.get("runId")).toBe(run.id);
  });

  it("refreshes only through the Admin client", async () => {
    const api = client();
    render(<AdminApp client={api} />);
    await screen.findByText("已定位到失败节点");

    fireEvent.click(screen.getByRole("button", { name: "刷新" }));

    await waitFor(() => expect(api.sessions).toHaveBeenCalledTimes(2));
  });

  it("browses registered tools MCP servers and skills without loading run data", async () => {
    window.history.replaceState(null, "", "/admin/capabilities");
    const api = client();
    render(<AdminApp client={api} />);

    expect((await screen.findAllByText("execution_run")).length).toBeGreaterThan(0);
    expect(screen.getAllByText("Run reviewed local execution").length).toBeGreaterThan(0);
    expect(screen.getByText(/approvalRequirement/)).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: /MCP Servers/ }));
    expect(screen.getAllByText("personal-local").length).toBeGreaterThan(0);
    expect(screen.getByText(/personal_mcp_echo/)).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: /Skills/ }));
    expect(screen.getAllByText("daily-planning").length).toBeGreaterThan(0);
    expect(screen.getByText(/SKILL.md/)).toBeTruthy();
    expect(api.capabilities).toHaveBeenCalled();
    expect(api.sessions).not.toHaveBeenCalled();
    expect(new URL(window.location.href).searchParams.get("kind")).toBe("skill");
  });

  it("shows safe model profile and validation metadata without loading run data", async () => {
    window.history.replaceState(null, "", "/admin/models");
    const api = client();
    render(<AdminApp client={api} />);

    expect((await screen.findAllByText("DeepSeek V4 Flash")).length).toBeGreaterThan(0);
    expect(screen.getAllByText("deepseek-v4-flash-chat-v1").length).toBeGreaterThan(0);
    expect(screen.getByText("pa-model-preference-v1")).toBeTruthy();
    expect(screen.getByText("2026-08-13")).toBeTruthy();
    expect(screen.getByText("sha256:admin-safe-profile-digest")).toBeTruthy();
    expect(api.models).toHaveBeenCalled();
    expect(api.sessions).not.toHaveBeenCalled();
    expect(screen.queryByText(/apiKey|credential|reasoning_content/)).toBeNull();
  });
});

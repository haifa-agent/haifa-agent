import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { Model, ModelConnection, ModelPreferences } from "../api/generated";
import type { PersonalAssistantClient } from "../api/client";
import { ModelConnectionsModal } from "./ModelConnectionsModal";

const baseModel: Model = {
  id: "qwen3.8-max",
  modelGroupId: "aliyun:qwen3.8-max-preview",
  modelDisplayName: "Qwen 3.8 Max (Preview)",
  displayName: "Qwen 3.8 Max (Preview)",
  providerId: "aliyun",
  providerDisplayName: "阿里云百炼",
  apiStyle: "openai-chat-completions",
  apiStyleDisplayName: "Chat Completions",
  availability: "AVAILABLE",
  unavailableReason: "",
  capabilities: ["TEXT_CHAT", "TOOL_CALLING", "REASONING"],
  contextWindow: 128_000,
  maxOutputTokens: 16_384,
  preferenceSchemaVersion: "1.0",
  controls: {
    responseMode: {
      kind: "responseMode",
      visible: true,
      readOnly: false,
      allowedValues: ["FAST", "RECOMMENDED", "DEEP"],
      recommendedValue: "RECOMMENDED",
      effectiveSummary: "Balanced",
      helpText: "选择响应模式",
    },
    reasoningEffort: {
      kind: "reasoningEffort",
      visible: false,
      readOnly: true,
      allowedValues: ["MEDIUM"],
      recommendedValue: "MEDIUM",
      effectiveSummary: "Standard",
      helpText: "思考深度",
    },
    responseLength: {
      kind: "responseLength",
      visible: true,
      readOnly: false,
      allowedValues: ["SHORT", "RECOMMENDED", "LONG"],
      recommendedValue: "RECOMMENDED",
      effectiveSummary: "Standard",
      helpText: "选择回复长度",
    },
    apiStyle: {
      kind: "apiStyle",
      visible: false,
      readOnly: true,
      allowedValues: ["qwen3.8-max"],
      recommendedValue: "qwen3.8-max",
      effectiveSummary: "Recommended",
      helpText: "连接方式",
    },
  },
  recommendedPreferences: {
    responseMode: "RECOMMENDED",
    effort: null,
    responseLength: "RECOMMENDED",
  },
  imageInput: null,
};

const connection: ModelConnection = {
  connectionId: "configured://aliyun/default",
  providerId: "aliyun",
  method: "API_KEY",
  status: "AUTHENTICATED",
  accountLabel: "Saved key",
  apiKeySupported: true,
  externalLoginSupported: false,
  logoutSupported: true,
  unofficialLocalCompatibility: false,
  networkProxyMode: "SYSTEM",
};

function client(): PersonalAssistantClient {
  return {
    modelConnections: vi.fn(async () => [connection]),
  } as unknown as PersonalAssistantClient;
}

function renderModal(props: Partial<Parameters<typeof ModelConnectionsModal>[0]> = {}) {
  const onClose = vi.fn();
  const onSelectModel = vi.fn(async () => undefined);
  const onConnectionsChanged = vi.fn();
  render(
    <ModelConnectionsModal
      client={client()}
      open
      models={[baseModel]}
      modelConnections={[connection]}
      selectedModelId={baseModel.id}
      activeRun={false}
      currentPreferences={baseModel.recommendedPreferences}
      onClose={onClose}
      onConnectionsChanged={onConnectionsChanged}
      onSelectModel={onSelectModel}
      {...props}
    />,
  );
  return { onClose, onSelectModel, onConnectionsChanged };
}

describe("ModelConnectionsModal", () => {
  it("renders the model catalog with provider connection badge and limits", () => {
    renderModal();

    expect(screen.getByRole("dialog", { name: "模型与连接" })).toBeTruthy();
    expect(screen.getByRole("tab", { name: "模型目录" })).toBeTruthy();
    expect(screen.getByRole("tab", { name: "账号连接" })).toBeTruthy();
    expect(screen.getByText("阿里云百炼")).toBeTruthy();
    expect(screen.getByText("已连接 ✓")).toBeTruthy();
    expect(screen.getByText("Qwen 3.8 Max (Preview)")).toBeTruthy();
    expect(screen.getByText("当前使用")).toBeTruthy();
    expect(screen.getByText(/上下文 128K/)).toBeTruthy();
    expect(screen.getByText(/输出 16K/)).toBeTruthy();
  });

  it("opens the detail drawer and applies through the callback", async () => {
    const handlers = renderModal();

    fireEvent.click(screen.getByRole("button", { name: "查看详情与设置" }));
    expect(screen.getByRole("dialog", { name: /模型详情与设置/ })).toBeTruthy();
    expect(screen.getByText(/上下文窗口/)).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "确认并应用" }));
    await waitFor(() => expect(handlers.onSelectModel).toHaveBeenCalledWith(
      baseModel,
      baseModel.recommendedPreferences,
    ));
  });

  it("switches to the account connections tab", async () => {
    renderModal();
    fireEvent.click(screen.getByRole("tab", { name: "账号连接" }));
    expect(await screen.findByText("本机连接")).toBeTruthy();
    expect(screen.getByText(/Saved key/)).toBeTruthy();
  });

  it("filters the catalog by search", () => {
    renderModal();
    const search = screen.getByPlaceholderText(/请输入模型名称或能力筛选/);
    fireEvent.change(search, { target: { value: "不存在的模型" } });
    expect(screen.getByText("没有匹配的模型。")).toBeTruthy();
    fireEvent.change(search, { target: { value: "" } });
    expect(screen.getByText("Qwen 3.8 Max (Preview)")).toBeTruthy();
  });

  it("shows a re-confirmation hint for a stale current selection", () => {
    renderModal({
      selectionCompatibility: "RESELECTION_REQUIRED",
      currentPreferences: {
        responseMode: "RECOMMENDED",
        effort: null,
        responseLength: "RECOMMENDED",
      } as ModelPreferences,
    });
    fireEvent.click(screen.getByRole("button", { name: "查看详情与设置" }));
    expect(screen.getByRole("alert").textContent).toContain("该模型配置已升级，请重新确认设置后再应用。");
  });

  it("disables apply while a Run is active", () => {
    renderModal({ activeRun: true });
    fireEvent.click(screen.getByRole("button", { name: "查看详情与设置" }));
    expect(screen.getByRole("button", { name: "确认并应用" }).getAttribute("disabled")).not.toBeNull();
  });

  it("closes on Escape", () => {
    const handlers = renderModal();
    fireEvent.keyDown(document, { key: "Escape" });
    expect(handlers.onClose).toHaveBeenCalledTimes(1);
  });
});

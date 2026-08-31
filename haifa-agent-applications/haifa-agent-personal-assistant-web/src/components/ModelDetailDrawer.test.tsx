import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { Model, ModelPreferences } from "../api/generated";
import { ModelDetailDrawer } from "./ModelDetailDrawer";

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
      visible: true,
      readOnly: false,
      allowedValues: ["LOW", "MEDIUM", "HIGH"],
      recommendedValue: "MEDIUM",
      effectiveSummary: "Standard",
      helpText: "控制思考深度",
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
      visible: true,
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
  imageInput: {
    allowedSources: ["UPLOAD", "URL"],
    supportedMediaTypes: ["image/png", "image/jpeg", "image/webp", "image/gif"],
    maxImagesPerRequest: 4,
    maxBytesPerItem: 10 * 1024 * 1024,
    maxTotalBytes: 20 * 1024 * 1024,
    maxUrlCharacters: 2048,
    detailSupported: true,
    allowedDetails: ["AUTO", "LOW", "HIGH"],
  },
};

const textOnlyModel: Model = {
  ...baseModel,
  id: "deepseek-v4",
  modelDisplayName: "DeepSeek V4",
  displayName: "DeepSeek V4",
  capabilities: ["TEXT_CHAT", "TOOL_CALLING"],
  imageInput: null,
};

function renderDrawer(props: Partial<Parameters<typeof ModelDetailDrawer>[0]> = {}) {
  const onPreferencesChange = vi.fn();
  const onReset = vi.fn();
  const onApply = vi.fn();
  const onClose = vi.fn();
  const preferences: ModelPreferences = baseModel.recommendedPreferences;
  render(
    <ModelDetailDrawer
      model={baseModel}
      preferences={preferences}
      onPreferencesChange={onPreferencesChange}
      onReset={onReset}
      onApply={onApply}
      onClose={onClose}
      {...props}
    />,
  );
  return { onPreferencesChange, onReset, onApply, onClose };
}

describe("ModelDetailDrawer", () => {
  it("renders safe limits, capabilities, and the image IO profile from the projection", () => {
    renderDrawer();

    expect(screen.getByText("Qwen 3.8 Max (Preview)")).toBeTruthy();
    expect(screen.getByText("阿里云百炼")).toBeTruthy();
    expect(screen.getByText("128K Tokens（只读）")).toBeTruthy();
    expect(screen.getByText("16K Tokens（只读）")).toBeTruthy();
    expect(screen.getByText("文本")).toBeTruthy();
    expect(screen.getByText("工具调用")).toBeTruthy();
    expect(screen.getByText("深度思考")).toBeTruthy();
    expect(screen.getByText(/单请求最多 4 张图片/)).toBeTruthy();
    expect(screen.getByText(/单张 ≤ 10 MB，总计 ≤ 20 MB/)).toBeTruthy();
    expect(
      screen.getByText((content) => content.includes("支持 Detail 模式（AUTO / LOW / HIGH）")),
    ).toBeTruthy();
    fireEvent.click(screen.getByText("高级连接方式"));
    expect(screen.getByText("Chat Completions")).toBeTruthy();
  });

  it("shows a text-only notice when the model has no image input profile", () => {
    renderDrawer({ model: textOnlyModel });
    expect(screen.getByText(/该模型不支持图片输入（纯文本）/)).toBeTruthy();
  });

  it("drives response settings entirely from backend controls", () => {
    const handlers = renderDrawer();
    fireEvent.click(screen.getByRole("button", { name: "深度" }));
    expect(handlers.onPreferencesChange).toHaveBeenCalledWith(
      expect.objectContaining({ responseMode: "DEEP" }),
    );
    fireEvent.click(screen.getByRole("button", { name: "长" }));
    expect(handlers.onPreferencesChange).toHaveBeenCalledWith(
      expect.objectContaining({ responseLength: "LONG" }),
    );
  });

  it("switches only among the safe bindings allowed by the selected model profile", () => {
    const onBindingChange = vi.fn();
    const alternateBinding: Model = {
      ...baseModel,
      id: "qwen3.8-max-responses",
      apiStyle: "openai-responses",
      apiStyleDisplayName: "Responses API",
    };
    const modelWithSelectableApiStyle: Model = {
      ...baseModel,
      controls: {
        ...baseModel.controls,
        apiStyle: {
          ...baseModel.controls.apiStyle,
          readOnly: false,
          allowedValues: [baseModel.id, alternateBinding.id],
        },
      },
    };

    renderDrawer({
      model: modelWithSelectableApiStyle,
      bindings: [modelWithSelectableApiStyle, alternateBinding],
      onBindingChange,
    });

    fireEvent.click(screen.getByText("高级连接方式"));
    fireEvent.change(screen.getByRole("combobox", { name: "API 风格" }), {
      target: { value: alternateBinding.id },
    });
    expect(onBindingChange).toHaveBeenCalledWith(alternateBinding);
  });

  it("exposes reasoning effort only in Deep mode", () => {
    renderDrawer({ preferences: { ...baseModel.recommendedPreferences, responseMode: "DEEP" } });
    expect(screen.getByText("思考强度（仅深度模式）")).toBeTruthy();
    expect(screen.getByRole("button", { name: "High" })).toBeTruthy();
  });

  it("warns when the persisted selection needs re-confirmation", () => {
    renderDrawer({ selectionCompatibility: "RESELECTION_REQUIRED" });
    expect(screen.getByRole("alert").textContent).toContain("该模型配置已升级，请重新确认设置后再应用。");
  });

  it("disables apply while a Run is active", () => {
    const handlers = renderDrawer({ applyingDisabled: true });
    expect(screen.getByRole("button", { name: "确认并应用" }).getAttribute("disabled")).not.toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "确认并应用" }));
    expect(handlers.onApply).not.toHaveBeenCalled();
  });

  it("disables apply for an unavailable model", () => {
    const handlers = renderDrawer({
      model: { ...textOnlyModel, availability: "UNAVAILABLE", unavailableReason: "policy" },
    });
    expect(screen.getByRole("button", { name: "确认并应用" }).getAttribute("disabled")).not.toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "确认并应用" }));
    expect(handlers.onApply).not.toHaveBeenCalled();
  });

  it("applies, resets, and closes through the exposed callbacks", () => {
    const handlers = renderDrawer();
    fireEvent.click(screen.getByRole("button", { name: "确认并应用" }));
    expect(handlers.onApply).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole("button", { name: "恢复默认推荐" }));
    expect(handlers.onReset).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole("button", { name: "关闭模型详情" }));
    expect(handlers.onClose).toHaveBeenCalledTimes(1);
  });

  it("closes on Escape", () => {
    const handlers = renderDrawer();
    fireEvent.keyDown(document, { key: "Escape" });
    expect(handlers.onClose).toHaveBeenCalledTimes(1);
  });
});

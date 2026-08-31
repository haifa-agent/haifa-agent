import {
  Bot,
  Check,
  Image as ImageIcon,
  RotateCcw,
  X,
} from "lucide-react";
import { useEffect } from "react";
import type { Model, ModelPreferences } from "../api/generated";

const responseModeLabels: Record<string, string> = {
  RECOMMENDED: "推荐",
  FAST: "快速",
  DEEP: "深度",
};
const responseLengthLabels: Record<string, string> = {
  RECOMMENDED: "推荐",
  SHORT: "短",
  STANDARD: "标准",
  LONG: "长",
};
const effortLabels: Record<string, string> = {
  LOW: "Low",
  MEDIUM: "Medium",
  HIGH: "High",
  MAX: "Max",
};

function formatTokens(value: number): string {
  if (value >= 1_000_000) return `${Math.round(value / 1_000_000)}M`;
  if (value >= 1_000) return `${Math.round(value / 1_000)}K`;
  return String(value);
}

function formatBytes(value: number): string {
  if (value >= 1024 * 1024) return `${Math.round(value / (1024 * 1024))} MB`;
  if (value >= 1024) return `${Math.round(value / 1024)} KB`;
  return `${value} B`;
}

function capabilityLabel(capability: string): string | null {
  if (capability === "TEXT_CHAT") return "文本";
  if (capability === "TOOL_CALLING") return "工具调用";
  if (capability === "IMAGE_UPLOAD_INPUT" || capability === "IMAGE_URL_INPUT") return "图片";
  if (capability === "REASONING") return "深度思考";
  if (capability === "AUDIO_INPUT") return "音频";
  return null;
}

export interface ModelDetailDrawerProps {
  model: Model;
  preferences: ModelPreferences;
  /** Server-computed compatibility of the currently persisted selection for this binding. */
  selectionCompatibility?: "CURRENT" | "RESELECTION_REQUIRED" | "UNAVAILABLE";
  /** Safe connection label for this model's provider, when known. */
  connectionLabel?: string | null;
  /** Whether the current session is running; apply is then deferred to the next new Run. */
  applyingDisabled?: boolean;
  onPreferencesChange(preferences: ModelPreferences): void;
  onReset(): void;
  onApply(): void;
  onClose(): void;
}

/** Model detail and response-settings drawer, driven entirely by the safe PA model projection. */
export function ModelDetailDrawer({
  model,
  preferences,
  selectionCompatibility,
  connectionLabel,
  applyingDisabled = false,
  onPreferencesChange,
  onReset,
  onApply,
  onClose,
}: ModelDetailDrawerProps) {
  const unavailable = model.availability === "UNAVAILABLE";
  const imageInput = model.imageInput ?? null;
  const imageCapable = imageInput
    ? imageInput.allowedSources.length > 0
    : model.capabilities.some(
        (capability) => capability === "IMAGE_UPLOAD_INPUT" || capability === "IMAGE_URL_INPUT",
      );
  const capabilities = model.capabilities
    .map(capabilityLabel)
    .filter((value): value is string => value !== null);

  useEffect(() => {
    if (!document) return;
    const handler = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    document.addEventListener("keydown", handler);
    return () => document.removeEventListener("keydown", handler);
  }, [onClose]);

  return (
    <div className="model-drawer-backdrop" role="presentation" onClick={onClose}>
      <section
        className="model-detail-drawer"
        role="dialog"
        aria-modal="true"
        aria-label={`模型详情与设置：${model.displayName}`}
        onClick={(event) => event.stopPropagation()}
      >
        <header>
          <div className="model-drawer-title">
            <Bot size={20} aria-hidden="true" />
            <div>
              <h2>{model.displayName}</h2>
              <p>{model.providerDisplayName}</p>
            </div>
          </div>
          <button type="button" className="icon" aria-label="关闭模型详情" onClick={onClose}>
            <X size={18} />
          </button>
        </header>

        <div className="model-drawer-status">
          <span className={unavailable ? "model-badge unavailable" : "model-badge available"}>
            {unavailable ? "不可用" : "可用"}
          </span>
          {connectionLabel && <span className="model-drawer-connection">{connectionLabel}</span>}
        </div>

        {selectionCompatibility === "RESELECTION_REQUIRED" && (
          <p className="model-compat-warning" role="alert">
            该模型配置已升级，请重新确认设置后再应用。
          </p>
        )}
        {selectionCompatibility === "UNAVAILABLE" && (
          <p className="model-compat-warning" role="alert">
            该模型已不可选，请切换到其他模型。
          </p>
        )}

        <div className="model-drawer-section">
          <h3>基础限制</h3>
          <dl className="model-drawer-limits">
            <div>
              <dt>上下文窗口</dt>
              <dd>{formatTokens(model.contextWindow)} Tokens（只读）</dd>
            </div>
            <div>
              <dt>最大输出限制</dt>
              <dd>{formatTokens(model.maxOutputTokens)} Tokens（只读）</dd>
            </div>
          </dl>
        </div>

        <div className="model-drawer-section">
          <h3>支持能力</h3>
          <div className="model-capability-chips">
            {capabilities.map((label) => (
              <span key={label}>{label}</span>
            ))}
          </div>
        </div>

        <div className="model-drawer-section">
          <h3>多模态图片输入</h3>
          {imageInput ? (
            <dl className="model-drawer-limits">
              <div>
                <dt>支持来源</dt>
                <dd>
                  {imageInput.allowedSources.includes("UPLOAD") ? "本地上传 ✓" : ""}
                  {imageInput.allowedSources.includes("UPLOAD") &&
                  imageInput.allowedSources.includes("URL")
                    ? " · "
                    : ""}
                  {imageInput.allowedSources.includes("URL") ? "图片 URL (HTTPS) ✓" : ""}
                </dd>
              </div>
              <div>
                <dt>格式支持</dt>
                <dd>{imageInput.supportedMediaTypes.join(", ")}</dd>
              </div>
              <div>
                <dt>数量上限</dt>
                <dd>单请求最多 {imageInput.maxImagesPerRequest} 张图片</dd>
              </div>
              <div>
                <dt>容量上限</dt>
                <dd>
                  单张 ≤ {formatBytes(imageInput.maxBytesPerItem)}，总计 ≤{" "}
                  {formatBytes(imageInput.maxTotalBytes)}
                </dd>
              </div>
              {imageInput.detailSupported && (
                <div>
                  <dt>图片清晰度</dt>
                  <dd>支持 Detail 模式（{imageInput.allowedDetails.join(" / ")}）</dd>
                </div>
              )}
            </dl>
          ) : (
            <p className="model-drawer-muted">
              <ImageIcon size={14} aria-hidden="true" />
              该模型不支持图片输入（纯文本）
            </p>
          )}
        </div>

        <div className="model-drawer-section">
          <h3>响应设置</h3>
          {model.controls.responseMode.visible && (
            <fieldset disabled={model.controls.responseMode.readOnly}>
              <legend>响应模式</legend>
              <div className="model-segmented-control">
                {model.controls.responseMode.allowedValues.map((value) => (
                  <button
                    type="button"
                    key={value}
                    aria-pressed={preferences.responseMode === value}
                    onClick={() =>
                      onPreferencesChange({
                        ...preferences,
                        responseMode: value,
                        effort: value === "DEEP" ? preferences.effort : null,
                      })
                    }
                  >
                    {responseModeLabels[value] ?? value}
                  </button>
                ))}
              </div>
              <small>{model.controls.responseMode.helpText}</small>
            </fieldset>
          )}
          {model.controls.responseLength.visible && (
            <fieldset disabled={model.controls.responseLength.readOnly}>
              <legend>回复长度</legend>
              <div className="model-segmented-control">
                {model.controls.responseLength.allowedValues.map((value) => (
                  <button
                    type="button"
                    key={value}
                    aria-pressed={preferences.responseLength === value}
                    onClick={() => onPreferencesChange({ ...preferences, responseLength: value })}
                  >
                    {responseLengthLabels[value] ?? value}
                  </button>
                ))}
              </div>
              <small>{model.controls.responseLength.helpText}</small>
            </fieldset>
          )}
          {model.controls.reasoningEffort.visible && preferences.responseMode === "DEEP" && (
            <fieldset disabled={model.controls.reasoningEffort.readOnly}>
              <legend>思考强度（仅深度模式）</legend>
              <div className="model-segmented-control">
                {model.controls.reasoningEffort.allowedValues.map((value) => (
                  <button
                    type="button"
                    key={value}
                    aria-pressed={
                      (preferences.effort ?? model.controls.reasoningEffort.recommendedValue) ===
                      value
                    }
                    onClick={() => onPreferencesChange({ ...preferences, effort: value })}
                  >
                    {effortLabels[value] ?? value}
                  </button>
                ))}
              </div>
              <small>{model.controls.reasoningEffort.helpText}</small>
            </fieldset>
          )}
          {model.controls.apiStyle.visible && (
            <details className="model-advanced-settings">
              <summary>高级连接方式</summary>
              <p className="model-drawer-muted">{model.apiStyleDisplayName}</p>
              <small>{model.controls.apiStyle.helpText}</small>
            </details>
          )}
        </div>

        <p className="model-drawer-effective">
          {applyingDisabled
            ? "当前任务运行中，应用将在本次任务完成后、下一次新提问生效。"
            : "切换模型或更新参数将在当前对话的下一次新提问生效，不影响历史记录。"}
        </p>

        <footer className="model-drawer-actions">
          <button type="button" className="button" onClick={onReset}>
            <RotateCcw size={14} aria-hidden="true" />
            恢复默认推荐
          </button>
          <button type="button" className="button" onClick={onClose}>
            取消
          </button>
          <button
            type="button"
            className="button primary-button"
            disabled={applyingDisabled || unavailable}
            onClick={onApply}
          >
            <Check size={14} aria-hidden="true" />
            确认并应用
          </button>
        </footer>
      </section>
    </div>
  );
}

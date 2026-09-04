import { Bot, ChevronDown, KeyRound } from "lucide-react";
import type { Conversation, Model, ModelConnection, ModelPreferences } from "../../api/generated";
import type { ModelConnectionsTab } from "../ModelConnectionsModal";
import { statusLabel } from "../../utils/formatters";

export const responseModeLabels = { RECOMMENDED: "推荐", FAST: "快速", DEEP: "深度" } as const;

export interface ConversationHeaderProps {
  displayName?: string | null;
  activeRunId?: string | null;
  selectedConversation?: Conversation | null;
  runStatus?: string | null;
  modelConnections: ModelConnection[] | null;
  selectedModel?: Model | null;
  selectedModelPreferences?: ModelPreferences | null;
  isModelUnavailable?: boolean;
  selectedProviderConnected?: boolean;
  onOpenModelCenter(tab: ModelConnectionsTab): void;
}

export function ConversationHeader({
  displayName,
  activeRunId,
  selectedConversation,
  runStatus,
  modelConnections,
  selectedModel,
  selectedModelPreferences,
  isModelUnavailable = false,
  selectedProviderConnected = true,
  onOpenModelCenter,
}: ConversationHeaderProps) {
  const title = displayName ?? selectedConversation?.displayName ?? "新会话";
  const runActiveId = activeRunId ?? selectedConversation?.activeRunId ?? null;
  return (
    <>
      {modelConnections && selectedModel && !isModelUnavailable && !selectedProviderConnected && (
        <button type="button" className="model-connection-notice" onClick={() => onOpenModelCenter("connections")}>
          <KeyRound size={14} /><span><strong>尚未连接模型</strong><small>（{selectedModel.providerDisplayName || selectedModel.providerId} 凭据未就绪，点击前往连接）</small></span>
        </button>
      )}
      <div className="conversation-heading">
        <div>
          <span className="eyebrow">PERSONAL ASSISTANT</span>
          <div className="conversation-title-row">
            <h1>{title}</h1>
            {selectedModel && (
              <button
                type="button"
                className={`conversation-model-badge${isModelUnavailable ? " unavailable" : ""}${!selectedProviderConnected ? " unauthenticated" : ""}`}
                onClick={() => onOpenModelCenter("catalog")}
                title={runActiveId ? "任务执行中，完成后可切换模型" : "当前对话模型，点击查看或切换"}
                aria-label={`当前模型：${selectedModel.modelDisplayName || selectedModel.displayName}，点击查看详情或切换模型`}
              >
                <Bot size={14} className="conversation-model-icon" aria-hidden="true" />
                <span className="conversation-model-name">{selectedModel.modelDisplayName || selectedModel.displayName}</span>
                <span className="conversation-model-provider">{selectedModel.providerDisplayName}</span>
                {selectedModelPreferences?.responseMode && selectedModelPreferences.responseMode !== "RECOMMENDED" && (
                  <span className="conversation-model-mode">{responseModeLabels[selectedModelPreferences.responseMode]}</span>
                )}
                <ChevronDown size={12} className="conversation-model-arrow" aria-hidden="true" />
              </button>
            )}
          </div>
        </div>
        {runStatus && <span className="run-state">{statusLabel(runStatus)}</span>}
      </div>
    </>
  );
}

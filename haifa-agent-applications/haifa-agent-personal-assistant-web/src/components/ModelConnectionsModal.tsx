import { Bot, Search, X } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import type { Model, ModelConnection, ModelPreferences } from "../api/generated";
import type { PersonalAssistantClient } from "../api/client";
import { ModelDetailDrawer } from "./ModelDetailDrawer";
import { ModelConnectionTab } from "./ModelConnectionTab";

export type ModelConnectionsTab = "catalog" | "connections";

interface ModelGroup {
  id: string;
  displayName: string;
  bindings: Model[];
}

interface ModelProvider {
  id: string;
  displayName: string;
  modelGroups: ModelGroup[];
}

function groupModelsByProvider(models: Model[]): ModelProvider[] {
  const providers = new Map<string, ModelProvider>();
  models.forEach((model) => {
    let provider = providers.get(model.providerId);
    if (!provider) {
      provider = { id: model.providerId, displayName: model.providerDisplayName, modelGroups: [] };
      providers.set(model.providerId, provider);
    }
    let group = provider.modelGroups.find((candidate) => candidate.id === model.modelGroupId);
    if (!group) {
      group = { id: model.modelGroupId, displayName: model.modelDisplayName, bindings: [] };
      provider.modelGroups.push(group);
    }
    group.bindings.push(model);
  });
  return [...providers.values()];
}

function providerConnectionLabel(
  providerId: string,
  connections: ModelConnection[] | null,
): string | null {
  const connection = connections?.find((candidate) => candidate.providerId === providerId);
  if (!connection) return "尚未配置连接";
  if (connection.status === "AUTHENTICATED") return "已连接 ✓";
  if (connection.status === "REAUTH_REQUIRED") return "重新认证 ⚠";
  if (connection.status === "RATE_LIMITED") return "限流 ⏳";
  return null;
}

function capabilityLabel(capability: string): string | null {
  if (capability === "TEXT_CHAT") return "文本";
  if (capability === "TOOL_CALLING") return "工具";
  if (capability === "IMAGE_UPLOAD_INPUT" || capability === "IMAGE_URL_INPUT") return "图片";
  if (capability === "REASONING") return "思考";
  return null;
}

function formatTokens(value: number): string {
  if (value >= 1_000_000) return `${Math.round(value / 1_000_000)}M`;
  if (value >= 1_000) return `${Math.round(value / 1_000)}K`;
  return String(value);
}

export interface ModelConnectionsModalProps {
  client: PersonalAssistantClient;
  open: boolean;
  initialTab?: ModelConnectionsTab;
  models: Model[];
  modelConnections: ModelConnection[] | null;
  selectedModelId: string;
  activeRun: boolean;
  currentPreferences: ModelPreferences | null;
  /** Server-computed compatibility for the currently selected conversation model, if known. */
  selectionCompatibility?: "CURRENT" | "RESELECTION_REQUIRED" | "UNAVAILABLE";
  onClose(): void;
  onConnectionsChanged?(connections: ModelConnection[]): void;
  onSelectModel(model: Model, preferences: ModelPreferences): Promise<void> | void;
}

/** Unified "模型与连接" window: model catalog (Tab 1) and account connections (Tab 2). */
export function ModelConnectionsModal({
  client,
  open,
  initialTab = "catalog",
  models,
  modelConnections,
  selectedModelId,
  activeRun,
  currentPreferences,
  selectionCompatibility,
  onClose,
  onConnectionsChanged,
  onSelectModel,
}: ModelConnectionsModalProps) {
  const [tab, setTab] = useState<ModelConnectionsTab>(initialTab);
  const [search, setSearch] = useState("");
  const [inspectedModel, setInspectedModel] = useState<Model | null>(null);
  const [inspectedBindings, setInspectedBindings] = useState<Model[]>([]);
  const [draftPreferences, setDraftPreferences] = useState<ModelPreferences | null>(null);

  useEffect(() => {
    if (open) setTab(initialTab);
  }, [open, initialTab]);

  useEffect(() => {
    if (!document) return;
    const handler = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        if (inspectedModel) setInspectedModel(null);
        else onClose();
      }
    };
    document.addEventListener("keydown", handler);
    return () => document.removeEventListener("keydown", handler);
  }, [inspectedModel, onClose]);

  const providers = useMemo(() => groupModelsByProvider(models), [models]);
  const query = search.trim().toLowerCase();
  const visibleProviders = useMemo(
    () =>
      query
        ? providers
            .map((provider) => ({
              ...provider,
              modelGroups: provider.modelGroups.filter(
                (group) =>
                  group.displayName.toLowerCase().includes(query) ||
                  provider.displayName.toLowerCase().includes(query) ||
                  group.bindings.some((binding) =>
                    binding.capabilities.some((capability) =>
                      capability.toLowerCase().includes(query),
                    ),
                  ),
              ),
            }))
            .filter((provider) => provider.modelGroups.length > 0)
        : providers,
    [providers, query],
  );

  if (!open) return null;

  const inspect = (model: Model, bindings: Model[]) => {
    const isCurrent = model.id === selectedModelId;
    setInspectedModel(model);
    setInspectedBindings(bindings);
    setDraftPreferences(
      isCurrent && currentPreferences ? currentPreferences : model.recommendedPreferences,
    );
  };

  const apply = async () => {
    if (!inspectedModel || !draftPreferences) return;
    await onSelectModel(inspectedModel, draftPreferences);
    setInspectedModel(null);
    setDraftPreferences(null);
  };

  return (
    <div
      className="model-connection-backdrop"
      role="presentation"
      onClick={onClose}
      onPointerDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <section
        className="model-connections-window"
        role="dialog"
        aria-modal="true"
        aria-label="模型与连接"
        onClick={(event) => event.stopPropagation()}
      >
        <header>
          <div>
            <span>MODEL &amp; CONNECTIONS</span>
            <h2>模型与连接</h2>
          </div>
          <button type="button" className="icon" aria-label="关闭模型与连接" onClick={onClose}>
            <X size={18} />
          </button>
        </header>

        <nav className="model-connections-tabs" role="tablist" aria-label="模型与连接">
          <button
            type="button"
            role="tab"
            aria-selected={tab === "catalog"}
            onClick={() => setTab("catalog")}
          >
            模型目录
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={tab === "connections"}
            onClick={() => setTab("connections")}
          >
            账号连接
          </button>
        </nav>

        {tab === "catalog" ? (
          <div className="model-catalog-tab" role="tabpanel">
            <label className="model-catalog-search">
              <Search size={15} aria-hidden="true" />
              <span className="sr-only">搜索模型</span>
              <input
                type="search"
                placeholder="请输入模型名称或能力筛选..."
                value={search}
                onChange={(event) => setSearch(event.target.value)}
              />
            </label>

            {visibleProviders.length === 0 && <p className="model-catalog-empty">没有匹配的模型。</p>}

            {visibleProviders.map((provider) => {
              const connectionLabel = providerConnectionLabel(provider.id, modelConnections);
              return (
                <section className="model-provider-group" key={provider.id}>
                  <h3>
                    <span>{provider.displayName}</span>
                    {connectionLabel && <small>{connectionLabel}</small>}
                  </h3>
                  <div className="model-card-grid">
                    {provider.modelGroups.map((group) => {
                      const current = group.bindings.some((binding) => binding.id === selectedModelId);
                      return (
                        <article
                          className={`model-card${current ? " current" : ""}`}
                          key={group.id}
                        >
                          <div className="model-card-head">
                            <strong>{group.displayName}</strong>
                            {current && <span className="model-badge current">当前使用</span>}
                          </div>
                          <div className="model-card-facts">
                            <span>上下文 {formatTokens(group.bindings[0].contextWindow)}</span>
                            <span>输出 {formatTokens(group.bindings[0].maxOutputTokens)}</span>
                          </div>
                          <div className="model-card-caps">
                            {[
                              ...new Set(
                                group.bindings
                                  .flatMap((binding) => binding.capabilities)
                                  .map(capabilityLabel)
                                  .filter((value): value is string => value !== null),
                              ),
                            ].map((label) => (
                              <span key={label}>{label}</span>
                            ))}
                          </div>
                          <button
                            type="button"
                            className="button"
                            onClick={() => inspect(group.bindings[0], group.bindings)}
                          >
                            查看详情与设置
                          </button>
                        </article>
                      );
                    })}
                  </div>
                </section>
              );
            })}

            <p className="model-catalog-effective">
              提示：切换模型或更新参数将在当前对话的「下一次新提问」生效，不影响历史记录。
            </p>
          </div>
        ) : (
          <div className="model-connections-tab" role="tabpanel">
            <ModelConnectionTab client={client} onConnectionsChanged={onConnectionsChanged} />
          </div>
        )}
      </section>

      {inspectedModel && draftPreferences && (
        <ModelDetailDrawer
          model={inspectedModel}
          bindings={inspectedBindings}
          preferences={draftPreferences}
          selectionCompatibility={
            inspectedModel.id === selectedModelId ? selectionCompatibility : undefined
          }
          connectionLabel={providerConnectionLabel(inspectedModel.providerId, modelConnections)}
          applyingDisabled={activeRun}
          onBindingChange={(binding) => {
            setInspectedModel(binding);
            setDraftPreferences(
              binding.id === selectedModelId && currentPreferences
                ? currentPreferences
                : binding.recommendedPreferences,
            );
          }}
          onPreferencesChange={setDraftPreferences}
          onReset={() => setDraftPreferences(inspectedModel.recommendedPreferences)}
          onApply={() => void apply()}
          onClose={() => {
            setInspectedModel(null);
            setInspectedBindings([]);
            setDraftPreferences(null);
          }}
        />
      )}
    </div>
  );
}

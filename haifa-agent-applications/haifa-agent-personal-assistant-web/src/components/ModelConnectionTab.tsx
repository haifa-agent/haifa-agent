import { CheckCircle2, CircleAlert, KeyRound, LogIn, LogOut } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import type { ExternalLoginAttempt, ModelConnection } from "../api/generated";
import type { PersonalAssistantClient } from "../api/client";

const terminalStates = new Set(["SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED"]);
type ExternalMethodId = "codex" | "antigravity";

export interface ModelConnectionTabProps {
  client: PersonalAssistantClient;
  providerId?: string | null;
  onConnectionsChanged?(connections: ModelConnection[]): void;
}

interface ProviderMeta {
  displayName: string;
  tag: string;
  tagClass: string;
  envVarName?: string;
}

const KNOWN_PROVIDERS: Record<string, ProviderMeta> = {
  deepseek: {
    displayName: "DeepSeek (深度求索)",
    tag: "DS",
    tagClass: "tag-deepseek",
    envVarName: "DEEPSEEK_API_KEY",
  },
  aliyun: {
    displayName: "阿里云百炼 (DashScope)",
    tag: "阿里",
    tagClass: "tag-aliyun",
    envVarName: "DASHSCOPE_API_KEY",
  },
  openai: {
    displayName: "OpenAI",
    tag: "OpenAI",
    tagClass: "tag-openai",
    envVarName: "OPENAI_API_KEY",
  },
  "openai-codex": {
    displayName: "ChatGPT (Codex)",
    tag: "GPT",
    tagClass: "tag-openai",
  },
  "google-antigravity": {
    displayName: "Google Antigravity",
    tag: "AG",
    tagClass: "tag-google",
  },
  gemini: {
    displayName: "Google Gemini",
    tag: "Gemini",
    tagClass: "tag-google",
    envVarName: "GEMINI_API_KEY",
  },
  anthropic: {
    displayName: "Anthropic Claude",
    tag: "Claude",
    tagClass: "tag-anthropic",
    envVarName: "ANTHROPIC_API_KEY",
  },
};

function getProviderMeta(providerId: string): ProviderMeta {
  if (KNOWN_PROVIDERS[providerId]) {
    return KNOWN_PROVIDERS[providerId];
  }
  const clean = providerId.replace(/[-_]/g, " ");
  return {
    displayName: clean.charAt(0).toUpperCase() + clean.slice(1),
    tag: providerId.slice(0, 4).toUpperCase(),
    tagClass: "tag-custom",
  };
}

function getStatusBadge(status: string) {
  switch (status) {
    case "AUTHENTICATED":
      return { label: "已就绪", badgeClass: "status-ready" };
    case "CONFIGURED":
      return { label: "已配置", badgeClass: "status-ready" };
    case "REAUTH_REQUIRED":
      return { label: "需要重新授权", badgeClass: "status-warning" };
    case "EXPIRED":
      return { label: "已过期", badgeClass: "status-error" };
    case "NOT_CONFIGURED":
    case "UNAVAILABLE":
    default:
      return { label: "未连接", badgeClass: "status-not-configured" };
  }
}

function formatAccountDetail(accountLabel: string, method: string, providerId: string): string {
  const meta = getProviderMeta(providerId);
  if (!accountLabel) return "未配置任何凭据";
  const lower = accountLabel.toLowerCase();
  if (lower.includes("environment") || lower.includes("env")) {
    return `系统环境变量 (${meta.envVarName ?? "系统 ENV"})`;
  }
  if (lower.includes("saved")) {
    return "本机保存的专属 Key";
  }
  if (method === "EXTERNAL_LOGIN") {
    return `共享本机账户`;
  }
  return accountLabel;
}

/** Embeddable account-connection management content used by the model & connections window. */
export function ModelConnectionTab({ client, providerId, onConnectionsChanged }: ModelConnectionTabProps) {
  const [connections, setConnections] = useState<ModelConnection[]>([]);
  const [targetProviderId, setTargetProviderId] = useState<string>(providerId ?? "deepseek");
  const [apiKey, setApiKey] = useState("");
  const [attempt, setAttempt] = useState<ExternalLoginAttempt | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const attemptRef = useRef<{ methodId: ExternalMethodId; attemptId: string } | null>(null);
  const operationGeneration = useRef(0);
  const mounted = useRef(true);
  const apiKeyInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (providerId) {
      setTargetProviderId(providerId);
    }
  }, [providerId]);

  const externalConnections = connections.filter((connection) => connection.externalLoginSupported);
  const hasApiKeySupportInConnections = connections.some((connection) => connection.providerId && connection.apiKeySupported);
  const apiKeySupported = connections.length === 0 ? true : hasApiKeySupportInConnections;

  // Build provider options for API key configuration
  const apiKeyProviders = Array.from(
    new Set(
      connections
        .filter((c) => c.apiKeySupported)
        .map((c) => c.providerId)
    )
  );
  if (targetProviderId && !apiKeyProviders.includes(targetProviderId)) {
    apiKeyProviders.unshift(targetProviderId);
  }
  if (apiKeyProviders.length === 0) {
    apiKeyProviders.push("deepseek", "aliyun", "openai");
  }

  const targetConnection = connections.find(
    (c) => c.providerId === targetProviderId && c.apiKeySupported
  );
  const isTargetConfigured = targetConnection?.status === "AUTHENTICATED";

  const refresh = useCallback(async () => {
    if (!client.modelConnections) return;
    const values = await client.modelConnections();
    if (!mounted.current) return;
    setConnections(values);
    onConnectionsChanged?.(values);
  }, [client, onConnectionsChanged]);

  useEffect(() => {
    mounted.current = true;
    void refresh().catch(() => setError("连接状态暂时无法读取。"));
    return () => {
      mounted.current = false;
      operationGeneration.current += 1;
      const active = attemptRef.current;
      if (active && client.cancelModelLogin) {
        void client.cancelModelLogin(active.methodId, active.attemptId).catch(() => undefined);
      }
    };
  }, [client, refresh]);

  const save = async () => {
    if (!client.saveModelApiKey || !apiKey) return;
    const effectiveProviderId =
      targetProviderId ||
      connections.find((value) => value.apiKeySupported)?.providerId ||
      "deepseek";
    const generation = ++operationGeneration.current;
    setBusy(true);
    setError(null);
    try {
      await client.saveModelApiKey(effectiveProviderId, apiKey);
      if (generation !== operationGeneration.current) return;
      setApiKey("");
      await refresh();
    } catch {
      if (generation === operationGeneration.current) setError("API Key 保存失败，请检查输入后重试。");
    } finally {
      if (generation === operationGeneration.current && mounted.current) {
        setApiKey("");
        setBusy(false);
      }
    }
  };

  const login = async (connection: ModelConnection) => {
    if (!client.startModelBrowserLogin || !client.modelLoginAttempt) return;
    const methodId: ExternalMethodId = connection.providerId === "google-antigravity" ? "antigravity" : "codex";
    const displayName = methodId === "antigravity" ? "Antigravity" : "ChatGPT";
    const generation = ++operationGeneration.current;
    setBusy(true);
    setError(null);
    try {
      let current = await client.startModelBrowserLogin(methodId);
      if (generation !== operationGeneration.current) return;
      attemptRef.current = { methodId, attemptId: current.attemptId };
      setAttempt(current);
      while (!terminalStates.has(current.state)) {
        await new Promise((resolve) => window.setTimeout(resolve, 500));
        current = await client.modelLoginAttempt(methodId, current.attemptId);
        if (generation !== operationGeneration.current) return;
        setAttempt(current);
      }
      attemptRef.current = null;
      if (current.state === "SUCCEEDED") await refresh();
      else setError(`登录未完成：${current.reasonCode ?? current.state}`);
    } catch {
      if (generation === operationGeneration.current) {
        setError(`${displayName} 登录暂时不可用；本地兼容开关或 Client 注册未配置时会保持关闭。`);
      }
    } finally {
      if (generation === operationGeneration.current) {
        attemptRef.current = null;
        if (mounted.current) setBusy(false);
      }
    }
  };

  const logout = async (connectionId: string) => {
    if (!client.logoutModelConnection) return;
    const generation = ++operationGeneration.current;
    setBusy(true);
    try {
      await client.logoutModelConnection(connectionId);
      if (generation !== operationGeneration.current) return;
      await refresh();
    } catch {
      if (generation === operationGeneration.current) setError("退出登录失败。");
    } finally {
      if (generation === operationGeneration.current && mounted.current) setBusy(false);
    }
  };

  return (
    <div className="model-connection-tab">
      {error && (
        <p className="model-connection-error" role="alert">
          <CircleAlert size={15} />
          {error}
        </p>
      )}

      <div className="model-connection-options">
        {externalConnections.map((connection) => {
          const antigravity = connection.providerId === "google-antigravity";
          const authenticated = connection.status === "AUTHENTICATED";
          const displayName = antigravity ? "Antigravity" : "ChatGPT";
          return (
            <section key={`external-${connection.providerId}`}>
              <LogIn size={20} aria-hidden="true" />
              <div>
                <h3>使用 {displayName} 登录</h3>
                <p>
                  {authenticated
                    ? `已连接共享的 ${displayName} 本机账户。`
                    : antigravity
                      ? "使用 Google Antigravity 订阅。登录在浏览器完成。"
                      : "使用 ChatGPT 订阅中的 Codex 额度。登录在浏览器完成。"}
                </p>
              </div>
              <button
                type="button"
                className="button primary"
                aria-busy={busy}
                disabled={busy || authenticated || !client.startModelBrowserLogin}
                onClick={() => void login(connection)}
              >
                {authenticated ? "已登录" : "登录"}
              </button>
            </section>
          );
        })}
        <section>
          <KeyRound size={20} aria-hidden="true" />
          <div>
            <h3>配置 API Key</h3>
            <p>
              {apiKeySupported
                ? "Key 仅保存在本机认证文件，不会回显或云端同步。"
                : "当前模型连接由运行环境或外部登录管理，不能在此保存 API Key。"}
            </p>
          </div>

          <div className="model-connection-provider-select">
            <label htmlFor="model-connection-provider-choice">目标供应商：</label>
            <select
              id="model-connection-provider-choice"
              value={targetProviderId}
              disabled={busy}
              onChange={(e) => setTargetProviderId(e.target.value)}
            >
              {apiKeyProviders.map((pid) => (
                <option key={pid} value={pid}>
                  {KNOWN_PROVIDERS[pid]?.displayName ?? pid}
                </option>
              ))}
            </select>
          </div>

          {isTargetConfigured && (
            <div className="model-connection-saved-badge">
              <CheckCircle2 size={15} />
              <span>本机已保存 {getProviderMeta(targetProviderId).displayName} 的 API Key</span>
            </div>
          )}

          <label>
            <span className="sr-only">API Key</span>
            <input
              ref={apiKeyInputRef}
              type="password"
              autoComplete="off"
              value={apiKey}
              disabled={busy || !apiKeySupported}
              placeholder="输入 API Key"
              onChange={(event) => setApiKey(event.target.value)}
            />
          </label>
          <div className="model-connection-save-actions">
            <button
              type="button"
              className="button"
              aria-busy={busy}
              disabled={busy || !apiKey || !client.saveModelApiKey || !apiKeySupported}
              onClick={() => void save()}
            >
              保存
            </button>
            {isTargetConfigured && targetConnection?.logoutSupported && (
              <button
                type="button"
                className="button subtle"
                disabled={busy}
                onClick={() => void logout(targetConnection.connectionId)}
                title="删除本机保存的该供应商 API Key"
              >
                清除已存 Key
              </button>
            )}
          </div>
        </section>
      </div>

      {attempt && (
        <p className="model-connection-attempt" role="status">
          登录状态：{attempt.state}
          {attempt.userCode ? ` · 验证码 ${attempt.userCode}` : ""}
        </p>
      )}

      <div className="model-connection-list">
        <h3>本机连接</h3>
        {connections.length === 0 && (
          <p>尚未保存连接。模型目录仍可查看，使用模型前再连接即可。</p>
        )}
        {connections.map((connection) => {
          const meta = getProviderMeta(connection.providerId);
          const statusBadge = getStatusBadge(connection.status);
          const detailText = formatAccountDetail(
            connection.accountLabel,
            connection.method,
            connection.providerId
          );
          return (
            <div key={connection.connectionId}>
              <div className={`connection-tag ${meta.tagClass}`}>
                {meta.tag}
              </div>
              <div className="connection-info">
                <div className="connection-title">
                  <span>{meta.displayName}</span>
                  <span className={`connection-status-badge ${statusBadge.badgeClass}`}>
                    {statusBadge.label}
                  </span>
                </div>
                <div className="connection-sub">
                  <span>凭证来源：{detailText}</span>
                  <span className="connection-account-label"> · {connection.accountLabel}</span>
                </div>
              </div>
              {connection.unofficialLocalCompatibility && <i>仅本地兼容测试</i>}
              <div className="connection-actions">
                {connection.apiKeySupported && (
                  <button
                    type="button"
                    className="button subtle small"
                    disabled={busy}
                    onClick={() => {
                      setTargetProviderId(connection.providerId);
                      apiKeyInputRef.current?.focus();
                    }}
                    title="配置或更换此供应商的 API Key"
                  >
                    更换 Key
                  </button>
                )}
                {connection.logoutSupported && (
                  <button
                    type="button"
                    className="icon"
                    aria-label={`退出 ${connection.providerId}`}
                    disabled={busy}
                    onClick={() => void logout(connection.connectionId)}
                    title="退出登录 / 清除凭据"
                  >
                    <LogOut size={16} />
                  </button>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

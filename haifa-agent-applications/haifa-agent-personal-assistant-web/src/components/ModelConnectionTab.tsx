import { CircleAlert, KeyRound, LogIn, LogOut } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import type { ExternalLoginAttempt, ModelConnection } from "../api/generated";
import type { PersonalAssistantClient } from "../api/client";

const terminalStates = new Set(["SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED"]);
type ExternalMethodId = "codex" | "antigravity";

export interface ModelConnectionTabProps {
  client: PersonalAssistantClient;
  onConnectionsChanged?(connections: ModelConnection[]): void;
}

/** Embeddable account-connection management content used by the model & connections window. */
export function ModelConnectionTab({ client, onConnectionsChanged }: ModelConnectionTabProps) {
  const [connections, setConnections] = useState<ModelConnection[]>([]);
  const [apiKey, setApiKey] = useState("");
  const [attempt, setAttempt] = useState<ExternalLoginAttempt | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const attemptRef = useRef<{ methodId: ExternalMethodId; attemptId: string } | null>(null);
  const operationGeneration = useRef(0);
  const mounted = useRef(true);

  const externalConnections = connections.filter((connection) => connection.externalLoginSupported);
  const apiKeySupported =
    connections.some((connection) => connection.providerId && connection.apiKeySupported) ?? false;

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
    const generation = ++operationGeneration.current;
    setBusy(true);
    setError(null);
    try {
      await client.saveModelApiKey(connections.find((value) => value.apiKeySupported)?.providerId ?? "", apiKey);
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
            <h3>使用 API Key</h3>
            <p>
              {apiKeySupported
                ? "Key 只写入本机认证文件，不会回显或用于保存前验证。"
                : "当前模型连接由运行环境或外部登录管理，不能在此保存 API Key。"}
            </p>
          </div>
          <label>
            <span className="sr-only">API Key</span>
            <input
              type="password"
              autoComplete="off"
              value={apiKey}
              disabled={busy || !apiKeySupported}
              placeholder="输入 API Key"
              onChange={(event) => setApiKey(event.target.value)}
            />
          </label>
          <button
            type="button"
            className="button"
            aria-busy={busy}
            disabled={busy || !apiKey || !client.saveModelApiKey || !apiKeySupported}
            onClick={() => void save()}
          >
            保存
          </button>
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
        {connections.map((connection) => (
          <div key={connection.connectionId}>
            <span>
              <strong>{connection.providerId}</strong>
              <small>
                {connection.accountLabel} · {connection.status}
              </small>
            </span>
            {connection.unofficialLocalCompatibility && <i>仅本地兼容测试</i>}
            {connection.logoutSupported && (
              <button
                type="button"
                className="icon"
                aria-label={`退出 ${connection.providerId}`}
                disabled={busy}
                onClick={() => void logout(connection.connectionId)}
              >
                <LogOut size={16} />
              </button>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

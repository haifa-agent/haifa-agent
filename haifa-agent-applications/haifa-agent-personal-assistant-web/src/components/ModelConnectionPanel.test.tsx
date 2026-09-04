import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { ModelConnection } from "../api/generated";
import type { PersonalAssistantClient } from "../api/client";
import { ModelConnectionPanel } from "./ModelConnectionPanel";

const connection: ModelConnection = {
  connectionId: "model-auth://deepseek/default",
  providerId: "deepseek",
  method: "API_KEY",
  status: "AUTHENTICATED",
  accountLabel: "Saved API key",
  apiKeySupported: true,
  externalLoginSupported: false,
  logoutSupported: true,
  unofficialLocalCompatibility: false,
  networkProxyMode: "SYSTEM",
};

describe("ModelConnectionPanel", () => {
  it("uses masked transient input and never renders the submitted key", async () => {
    const saveModelApiKey = vi.fn(async () => connection);
    const modelConnections = vi.fn()
      .mockResolvedValueOnce([{ ...connection, status: "REAUTH_REQUIRED", logoutSupported: false }])
      .mockResolvedValueOnce([connection]);
    const client = { modelConnections, saveModelApiKey } as unknown as PersonalAssistantClient;
    const user = userEvent.setup();

    render(<ModelConnectionPanel client={client} open providerId="deepseek" onClose={() => undefined} />);
    const input = await screen.findByPlaceholderText("输入 API Key");
    await waitFor(() => expect((input as HTMLInputElement).disabled).toBe(false));
    expect(input.getAttribute("type")).toBe("password");

    await user.type(input, "secret-canary-value");
    await user.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() => expect(saveModelApiKey).toHaveBeenCalledWith("deepseek", "secret-canary-value"));
    await waitFor(() => expect((input as HTMLInputElement).value).toBe(""));
    expect(screen.queryByText("secret-canary-value")).toBeNull();
    expect(await screen.findByText(/Saved API key/)).toBeTruthy();
  });

  it("saves a provider-specific HTTP network proxy from its connection row", async () => {
    const saveModelNetworkProxy = vi.fn(async () => undefined);
    const client = {
      modelConnections: vi.fn(async () => [connection]),
      saveModelNetworkProxy,
      resetModelNetworkProxy: vi.fn(async () => undefined),
    } as unknown as PersonalAssistantClient;
    const user = userEvent.setup();

    render(<ModelConnectionPanel client={client} open providerId="deepseek" onClose={() => undefined} />);
    await screen.findByText("本机连接");

    await user.click(screen.getByRole("button", { name: "网络代理" }));
    await user.click(screen.getByRole("radio", { name: /使用专属 HTTP 代理/ }));
    await user.type(screen.getByPlaceholderText("http://127.0.0.1:2081"), "http://127.0.0.1:2081");
    await user.click(screen.getByRole("button", { name: "保存网络代理" }));

    await waitFor(() => expect(saveModelNetworkProxy).toHaveBeenCalledWith("deepseek", "http://127.0.0.1:2081"));
  });

  it("cancels an active external attempt when closed", async () => {
    const cancelModelLogin = vi.fn(async () => undefined);
    const client = {
      modelConnections: vi.fn(async () => [{
        ...connection,
        providerId: "openai-codex",
        status: "REAUTH_REQUIRED",
        apiKeySupported: false,
        externalLoginSupported: true,
        logoutSupported: false,
      }]),
      startModelBrowserLogin: vi.fn(async () => ({
        attemptId: "01890f6c-7b2a-7cc0-8000-000000000001",
        methodId: "openai-codex",
        mode: "BROWSER",
        state: "WAITING_USER",
        expiresAtEpochMillis: 10_000,
      })),
      modelLoginAttempt: vi.fn(() => new Promise(() => undefined)),
      cancelModelLogin,
    } as unknown as PersonalAssistantClient;
    const user = userEvent.setup();

    render(<ModelConnectionPanel client={client} open providerId="deepseek" onClose={() => undefined} />);
    const login = await screen.findByRole("button", { name: "登录" });
    await waitFor(() => expect((login as HTMLButtonElement).disabled).toBe(false));
    await user.click(login);
    await screen.findByText(/WAITING_USER/);
    await user.click(screen.getByRole("button", { name: "关闭模型连接" }));

    expect(cancelModelLogin).toHaveBeenCalledWith("codex", "01890f6c-7b2a-7cc0-8000-000000000001");
  });

  it("shows the shared Codex account as signed in regardless of the selected model provider", async () => {
    const startModelBrowserLogin = vi.fn();
    const client = {
      modelConnections: vi.fn(async () => [{
        ...connection,
        providerId: "openai-codex",
        method: "EXTERNAL_LOGIN",
        apiKeySupported: false,
        externalLoginSupported: true,
        accountLabel: "Account 173fb463",
      }]),
      startModelBrowserLogin,
    } as unknown as PersonalAssistantClient;

    render(<ModelConnectionPanel client={client} open providerId="openai-codex" onClose={() => undefined} />);

    const signedIn = await screen.findByRole("button", { name: "已登录" });
    expect((signedIn as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText("已连接共享的 ChatGPT 本机账户。")).toBeTruthy();
    expect(startModelBrowserLogin).not.toHaveBeenCalled();
  });

  it("offers Antigravity browser login when the local compatibility method is enabled", async () => {
    const startModelBrowserLogin = vi.fn(async () => ({
      attemptId: "01890f6c-7b2a-7cc0-8000-000000000002",
      methodId: "google-antigravity",
      mode: "BROWSER" as const,
      state: "SUCCEEDED" as const,
      expiresAtEpochMillis: 10_000,
    }));
    const client = {
      modelConnections: vi.fn(async () => [{
        ...connection,
        connectionId: "configured://google-antigravity/default",
        providerId: "google-antigravity",
        method: "EXTERNAL_LOGIN",
        status: "REAUTH_REQUIRED",
        apiKeySupported: false,
        externalLoginSupported: true,
        logoutSupported: false,
      }]),
      startModelBrowserLogin,
      modelLoginAttempt: vi.fn(),
    } as unknown as PersonalAssistantClient;
    const user = userEvent.setup();

    render(<ModelConnectionPanel client={client} open providerId="google-antigravity" onClose={() => undefined} />);
    const login = await screen.findByRole("button", { name: "登录" });
    await user.click(login);

    await waitFor(() => expect(startModelBrowserLogin).toHaveBeenCalledWith("antigravity"));
    expect(screen.getByText("使用 Antigravity 登录")).toBeTruthy();
  });
});

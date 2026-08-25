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
      startCodexBrowserLogin: vi.fn(async () => ({
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
    const login = screen.getByRole("button", { name: "登录" });
    await waitFor(() => expect((login as HTMLButtonElement).disabled).toBe(false));
    await user.click(login);
    await screen.findByText(/WAITING_USER/);
    await user.click(screen.getByRole("button", { name: "关闭模型连接" }));

    expect(cancelModelLogin).toHaveBeenCalledWith("01890f6c-7b2a-7cc0-8000-000000000001");
  });

  it("shows the shared Codex account as signed in regardless of the selected model provider", async () => {
    const startCodexBrowserLogin = vi.fn();
    const client = {
      modelConnections: vi.fn(async () => [{
        ...connection,
        providerId: "openai-codex",
        method: "EXTERNAL_LOGIN",
        apiKeySupported: false,
        externalLoginSupported: true,
        accountLabel: "Account 173fb463",
      }]),
      startCodexBrowserLogin,
    } as unknown as PersonalAssistantClient;

    render(<ModelConnectionPanel client={client} open providerId="cliproxyapi-antigravity" onClose={() => undefined} />);

    const signedIn = await screen.findByRole("button", { name: "已登录" });
    expect((signedIn as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText("已连接共享的 ChatGPT/Codex 本机账户。")).toBeTruthy();
    expect(startCodexBrowserLogin).not.toHaveBeenCalled();
  });
});

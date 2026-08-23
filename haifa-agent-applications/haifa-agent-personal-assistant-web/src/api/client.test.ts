import { afterEach, describe, expect, it, vi } from "vitest";
import type { MissionSnapshot } from "./generated";
import { HttpPersonalAssistantClient } from "./client";

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("HttpPersonalAssistantClient deployment boundary", () => {
  it("calls the standalone loopback Server directly without browser credentials", async () => {
    const fetch = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>(
      async () => ({
        json: async () => ({
          apiVersion: "v1",
          assemblyDigest: "digest",
          caller: "public-user",
          capabilities: [],
          connection: "connected",
          product: "Haifa Personal Assistant",
        }),
        ok: true,
        status: 200,
      }) as Response,
    );
    vi.stubGlobal("fetch", fetch);

    await new HttpPersonalAssistantClient().bootstrap();

    expect(fetch).toHaveBeenCalledTimes(1);
    const [url, init] = fetch.mock.calls[0]!;
    expect(url).toBe("http://127.0.0.1:20001/api/v1/bootstrap");
    expect(init).not.toHaveProperty("credentials");
  });

  it("requests recommendations for the exact completed conversation run", async () => {
    const fetch = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>(
      async () => ({
        json: async () => ({ questions: ["继续深入吗？", "还要比较其他方案吗？"] }),
        ok: true,
        status: 200,
      }) as Response,
    );
    vi.stubGlobal("fetch", fetch);

    await new HttpPersonalAssistantClient().recommendedQuestions("conversation/1", "run/1", {
      idempotencyKey: "recommendation-1",
    });

    const [url, init] = fetch.mock.calls[0]!;
    expect(url).toBe(
      "http://127.0.0.1:20001/api/v1/conversations/conversation%2F1/runs/run%2F1/recommend-questions",
    );
    expect(init?.method).toBe("POST");
    expect(init?.headers).toMatchObject({
      "Idempotency-Key": "recommendation-1",
      "X-Haifa-CSRF": "1",
    });
  });

  it("sends model credentials only in the protected mutation body", async () => {
    const fetch = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>(
      async () => ({
        text: async () => JSON.stringify({
          connectionId: "model-auth://deepseek/default",
          providerId: "deepseek",
          method: "API_KEY",
          status: "AUTHENTICATED",
          accountLabel: "Saved API key",
          apiKeySupported: true,
          externalLoginSupported: false,
          logoutSupported: true,
          unofficialLocalCompatibility: false,
        }),
        ok: true,
        status: 201,
      }) as Response,
    );
    vi.stubGlobal("fetch", fetch);

    await new HttpPersonalAssistantClient().saveModelApiKey("deepseek", "secret-canary", {
      idempotencyKey: "model-key-1",
    });

    const [url, init] = fetch.mock.calls[0]!;
    expect(url).toBe("http://127.0.0.1:20001/api/v1/model-connections/api-key");
    expect(init?.headers).toMatchObject({ "Idempotency-Key": "model-key-1", "X-Haifa-CSRF": "1" });
    expect(init?.body).toBe(JSON.stringify({ providerId: "deepseek", apiKey: "secret-canary" }));
    expect(url).not.toContain("secret-canary");
  });

  it("sends Mission commands with the frozen idempotency and revision headers", async () => {
    const mission = {
      missionId: "mission/1",
      version: 7,
      state: "WAITING_CONFIRMATION",
    } as unknown as MissionSnapshot;
    const fetch = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>(
      async () => ({ json: async () => mission, ok: true, status: 200 }) as Response,
    );
    vi.stubGlobal("fetch", fetch);

    await new HttpPersonalAssistantClient().confirmMission(mission, {
      idempotencyKey: "confirm-1",
    });

    const [url, init] = fetch.mock.calls[0]!;
    expect(url).toBe("http://127.0.0.1:20001/api/v1/missions/mission%2F1/confirm");
    expect(init?.method).toBe("POST");
    expect(init?.headers).toMatchObject({
      "Idempotency-Key": "confirm-1",
      "If-Match": "7",
      "X-Haifa-CSRF": "1",
    });
  });

  it("allows synchronous Mission planning to use the Server planning window", async () => {
    const timeout = vi.spyOn(window, "setTimeout");
    vi.stubGlobal("fetch", vi.fn(async () => ({
      json: async () => ({ missionId: "mission-1", state: "WAITING_CONFIRMATION" }),
      ok: true,
      status: 202,
    }) as Response));

    await new HttpPersonalAssistantClient().createMission({
      conversationId: "conversation-1",
      objective: "Research",
      acceptanceCriteria: [],
      constraints: {},
      mode: "STANDARD",
    });

    expect(timeout).toHaveBeenCalledWith(expect.any(Function), 190_000);
  });

  it("rejects JSON responses larger than the browser safety limit", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => ({
      text: async () => `"${"x".repeat(2 * 1024 * 1024)}"`,
      ok: true,
      status: 200,
    }) as Response));

    await expect(new HttpPersonalAssistantClient().bootstrap()).rejects.toMatchObject({
      code: "RESPONSE_TOO_LARGE",
    });
  });
});

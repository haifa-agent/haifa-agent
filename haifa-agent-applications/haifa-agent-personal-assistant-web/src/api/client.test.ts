import { afterEach, describe, expect, it, vi } from "vitest";
import type { MissionSnapshot } from "./generated";
import { HttpPersonalAssistantClient } from "./client";

afterEach(() => {
  vi.unstubAllGlobals();
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

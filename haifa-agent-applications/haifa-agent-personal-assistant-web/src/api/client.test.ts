import { afterEach, describe, expect, it, vi } from "vitest";
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
});

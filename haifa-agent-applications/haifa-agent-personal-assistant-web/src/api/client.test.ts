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
});

import { bootstrapFixture } from "../data/fixtures";
import type { BootstrapSnapshot } from "../types";
import type { PersonalAssistantClient } from "./client";

function cloneSnapshot(): BootstrapSnapshot {
  return structuredClone(bootstrapFixture);
}
export class MockPersonalAssistantClient implements PersonalAssistantClient {
  async bootstrap(): Promise<BootstrapSnapshot> {
    await new Promise((resolve) => window.setTimeout(resolve, 120));
    return cloneSnapshot();
  }
}

import type { BootstrapSnapshot } from "../types";

export interface PersonalAssistantClient {
  bootstrap(): Promise<BootstrapSnapshot>;
}

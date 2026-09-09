import { expect, it } from "vitest";
import { statusLabel } from "./formatters";

it("labels EXITED without presenting a failure", () => {
  expect(statusLabel("EXITED")).toBe("已退出");
});

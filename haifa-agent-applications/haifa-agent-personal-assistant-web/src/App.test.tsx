import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import App from "./App";

describe("Personal Assistant application", () => {
  it("renders the product shell without Deep Research surfaces", async () => {
    render(<App />);

    expect(await screen.findByText("Haifa Personal")).toBeTruthy();
    expect(screen.getAllByText("整理示例数据").length).toBeGreaterThan(0);
    expect(screen.queryByText("Deep Research")).toBeNull();
    expect(screen.queryByText("Sources")).toBeNull();
    expect(screen.queryByText("View JSON")).toBeNull();
    const usage = screen.getByLabelText(/会话 Token 消耗/);
    expect(usage.textContent).toContain("输入：4,832");
    expect(usage.textContent).toContain("输出：286");
    expect(usage.textContent).toContain("总计：5,118");
    expect(usage.textContent).toContain("提供方实报");
  });

  it("opens memory candidates and requires an explicit confirmation", async () => {
    render(<App />);
    await screen.findByText("Haifa Personal");

    fireEvent.click(screen.getByRole("button", { name: /记忆与偏好/ }));
    expect(screen.getByRole("dialog", { name: "记忆与偏好" })).toBeTruthy();
    expect(screen.getByText("通常从杭州出发，国内出行偏好高铁。")).toBeTruthy();

    fireEvent.click(screen.getAllByRole("button", { name: /确认记住/ })[0]);
    await waitFor(() => {
      expect(screen.queryByText("通常从杭州出发，国内出行偏好高铁。")).toBeNull();
    });
  });

  it("completes a one-time approval and exposes the generated artifact", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    render(<App />);
    await screen.findByText("Haifa Personal");

    fireEvent.click(screen.getByRole("button", { name: "仅批准这一次" }));
    await vi.advanceTimersByTimeAsync(1_000);

    expect(await screen.findByText("数据整理摘要.csv")).toBeTruthy();
    expect(screen.getByLabelText(/会话 Token 消耗/).textContent).toContain(
      "总计：7,410",
    );
    vi.useRealTimers();
  });
});

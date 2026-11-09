import {
  CodingBrowserClient,
  type CodingObservation,
} from "@haifa-agent/coding-client";
const find = <T extends HTMLElement>(id: string) =>
  document.getElementById(id) as T;
let client: CodingBrowserClient | null = null,
  view: CodingObservation | null = null,
  generation = 0,
  creating = false;
async function list() {
  if (!client) return;
  const selected = find<HTMLSelectElement>("sessions");
  const result = await client.listSessions();
  selected.replaceChildren(
    new Option("选择会话", ""),
    ...result.sessions.map((s) => new Option(s.title, s.sessionId)),
  );
  selected.disabled = false;
}
async function open(id: string) {
  const current = ++generation;
  view?.close();
  view = null;
  if (!id || !client) return;
  const observed = await client.observe(id);
  if (current !== generation) {
    observed.close();
    return;
  }
  view = observed;
  const render = () => {
    if (current !== generation) return;
    find("output").textContent =
      observed.snapshot.entries
        .map((e) => e.title + "\n" + e.text)
        .join("\n\n") + (observed.snapshot.live?.text ?? "");
  };
  observed.onChange(render);
  render();
}
find("pair").onsubmit = (event) => {
  event.preventDefault();
  void (async () => {
    const proof = find<HTMLInputElement>("proof");
    try {
      await client?.close();
      client = await CodingBrowserClient.pair(proof.value);
      client.onChange(() => {
        if (client?.state !== "connected") {
          find<HTMLButtonElement>("send").disabled = true;
          find("status").textContent = "连接状态改变，请核对授权后重新配对。";
        }
      });
      find("status").textContent = "已连接 " + client.connection.displayName;
      find<HTMLButtonElement>("refresh").disabled = false;
      find<HTMLButtonElement>("send").disabled = false;
      await list();
    } catch {
      find("status").textContent =
        "连接失败，请确认兼容 Host 和有效的一次性配对码。";
    } finally {
      proof.value = "";
    }
  })();
};
find("refresh").onclick = () => {
  void list().catch(() => {
    find("status").textContent = "无法刷新，请核对连接。";
  });
};
find<HTMLSelectElement>("sessions").onchange = (event) => {
  void open((event.target as HTMLSelectElement).value).catch(() => {
    find("status").textContent = "无法打开会话，请核对连接。";
  });
};
find("create").onsubmit = (event) => {
  event.preventDefault();
  if (!client || creating || find<HTMLButtonElement>("send").disabled) return;
  creating = true;
  const text = find<HTMLTextAreaElement>("text");
  void (async () => {
    try {
      const reply = await client!.createSession(text.value);
      find("status").textContent =
        reply.receipt.disposition === "unknown"
          ? "创建结果未知：请先核对会话列表，本页不会重发。"
          : reply.receipt.disposition === "accepted"
            ? "任务已接收，等待权威执行结果。"
            : "任务未被接受。";
      if (reply.receipt.disposition === "unknown")
        find<HTMLButtonElement>("send").disabled = true;
      if (reply.receipt.disposition === "accepted") {
        text.value = "";
        await list();
        if (reply.session) await open(reply.session.sessionId);
      }
    } catch {
      find("status").textContent = "无法发送，请核对会话后再操作。";
    } finally {
      creating = false;
    }
  })();
};
window.addEventListener("pagehide", () => {
  view?.close();
  void client?.close();
});

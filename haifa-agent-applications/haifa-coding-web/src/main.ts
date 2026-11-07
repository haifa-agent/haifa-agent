import {
  CodingBrowserClient,
  supportsInteraction,
  CodingClientError,
  type Action,
  type CodingObservation,
  type CommandResult,
  type Entry,
  type QuestionAnswer,
  type SessionSummary,
  type Preview,
} from "@haifa-agent/coding-client";
import { reanchorHistory, WorkspaceState, type CachedHistory } from "./state";
import "./style.css";

const app = document.querySelector<HTMLDivElement>("#app")!;
app.innerHTML = `<div class="workspace">
  <aside class="sidebar" id="sidebar"><a class="brand" href="/browser/" aria-label="Haifa Coding 首页"><span class="brand-mark" aria-hidden="true">H</span><span>haifa<span class="brand-sub">CODING</span></span></a>
    <button class="new-session" id="new-session"><span aria-hidden="true">＋</span> 新建会话 <kbd>N</kbd></button>
    <label class="search"><span class="sr-only">搜索会话</span><input id="search" placeholder="搜索会话" type="search" autocomplete="off"></label>
    <div class="section-label">工作会话 <button class="icon-button" id="refresh-list" aria-label="刷新会话列表">↻</button></div>
    <nav class="session-list" id="session-list" aria-label="会话列表"><p class="muted list-empty">连接工作区后，你的会话会显示在这里。</p></nav>
    <div class="sidebar-footer"><span class="status-dot" id="connection-dot"></span><span id="connection-label">等待连接</span><button id="disconnect" class="text-button" hidden>断开</button></div>
  </aside>
  <div class="main"><header class="topbar"><button class="icon-button mobile-menu" id="menu" aria-label="展开会话列表" aria-expanded="false">☰</button><span class="breadcrumb">工作区 <span>/</span> <strong id="workspace-name">Coding</strong></span><span id="connection-badge" class="pill">未配对</span></header>
    <div class="main-columns"><main class="conversation" id="conversation">
      <div class="conversation-heading"><div><div class="eyebrow">YOUR CODING WORKSPACE</div><h1 id="session-title">从一个想法开始</h1><p id="session-subtitle">和 Agent 一起理解代码、解决问题、完成开发。</p></div><div class="heading-actions"><button id="refresh-view" class="secondary" hidden>重新获取</button><button id="cancel-run" class="danger" hidden>停止任务</button></div></div>
      <section class="pairing" id="pairing" aria-labelledby="pair-title"><div class="pair-icon" aria-hidden="true">⌘</div><div><h2 id="pair-title">连接你的工作区</h2><p>在可信的 Host 启动器中生成一次性配对码，再在这里连接。</p><form id="pair-form"><label for="pair-code" class="sr-only">一次性配对码</label><input id="pair-code" type="password" placeholder="输入一次性配对码" autocomplete="off" required><button type="submit" id="pair-button" class="primary">连接工作区 <span aria-hidden="true">↗</span></button></form><p class="fine-print">授权仅保留在当前页面。刷新后需重新配对，并核对会话历史。</p></div></section>
      <div id="notice" class="notice" role="status" aria-live="polite" hidden></div>
      <div class="transcript" id="transcript" tabindex="0" aria-label="会话内容"><section class="welcome" id="welcome"><div class="welcome-symbol" aria-hidden="true">✳</div><h2>把下一步，变成进展。</h2><p>连接工作区后，描述你想完成的事。<br>工具执行、审批与结果，会在同一个会话里呈现。</p><div class="suggestions"><button data-suggestion="帮我理解这个项目的结构和主要入口">理解项目结构 <span>↗</span></button><button data-suggestion="检查现有测试，帮我定位需要修复的问题">排查测试问题 <span>↗</span></button><button data-suggestion="帮我规划下一项功能的实现步骤">规划下一项功能 <span>↗</span></button></div></section></div>
      <div class="composer-wrap"><div id="session-toolbar" class="session-toolbar"></div><form id="compose-form" class="composer"><label for="draft" class="sr-only">描述你的任务</label><textarea id="draft" placeholder="描述你想完成的事…" rows="3" maxlength="16000"></textarea><div class="composer-footer"><span id="compose-hint">先连接工作区，再开始新的会话</span><button class="send" id="send" type="submit" aria-label="发送任务" disabled>↑</button></div></form><p class="composer-note">Agent 的结果需要核对。敏感工具操作将在执行前请求确认。</p></div>
    </main><aside class="context-panel" aria-label="工作区上下文"><div class="context-title">上下文 <span aria-hidden="true">⌁</span></div><dl id="context"><dt>工作区</dt><dd>等待连接</dd><dt>授权范围</dt><dd>当前页面</dd></dl><div class="context-note"><span aria-hidden="true">◈</span><p>你的执行由 Host 持有。关闭页面会结束观察，已经接收的任务继续运行。</p></div></aside></div>
  </div></div>`;
const $ = <T extends HTMLElement>(id: string) =>
  document.getElementById(id) as T;
const state = new WorkspaceState();
const draft = $<HTMLTextAreaElement>("draft"),
  transcript = $("transcript");
let client: CodingBrowserClient | null = null,
  view: CodingObservation | null = null,
  offView: (() => void) | null = null;
let connectionGeneration = 0;
let sessions: ReadonlyArray<SessionSummary> = [],
  searchGeneration = 0,
  busy = new Set<string>(),
  loading = false;
const history = new Map<string, CachedHistory>();
const interactionInputs = new Map<
  string,
  { value: string; checked: boolean }
>();
const previewBuffers = new Map<
  string,
  { stdout: string; stderr: string; dropped: boolean; truncated: boolean }
>();
let previewController: AbortController | null = null,
  previewRun: string | null = null,
  renderQueued = false;
function el<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  className = "",
  text?: string,
): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}
const message = (text: string, error = false) => {
  const node = $("notice");
  node.textContent = text;
  node.hidden = !text;
  node.classList.toggle("notice-error", error);
};
function explain(error: unknown): string {
  const code =
    error instanceof CodingClientError ? error.code : "CONNECTION_UNAVAILABLE";
  if (["PAIRING_REJECTED", "PAIRING_RESPONSE_INVALID"].includes(code))
    return "配对码无效、已使用或已过期。请从可信的 Host 启动器重新生成。";
  if (
    [
      "PAIRING_REQUIRED",
      "BROWSER_AUTH_REQUIRED",
      "BROWSER_AUTH_EXPIRED",
      "GRANT_EXPIRED",
    ].includes(code)
  )
    return "连接授权已失效。请重新配对；已接收的任务会继续运行。";
  if (code.includes("CAPABILITY") || code === "PROTOCOL_INCOMPATIBLE")
    return "此 Host 暂不支持所需协议。请确认从兼容 Host 提供的页面打开。";
  if (code === "REQUEST_TRACKING_LIMIT")
    return "本页请求关联已达上限。请先核对任务结果，再重新打开页面。";
  return "暂时无法完成操作。请核对连接和会话状态，再手动重试。";
}
function saveDraft() {
  try {
    state.update(state.selected, {
      text: draft.value,
      selectionStart: draft.selectionStart,
      selectionEnd: draft.selectionEnd,
      scrollTop: transcript.scrollTop,
    });
  } catch {
    message("当前页面的草稿数量已达上限，请先完成已有会话。", true);
  }
}
function restoreDraft() {
  const value = state.get(state.selected);
  draft.value = value.text;
  draft.setSelectionRange(value.selectionStart, value.selectionEnd);
  transcript.scrollTop = value.scrollTop;
}
function sessionKey(session: string | null) {
  return session ?? "new";
}
function scheduleRender() {
  if (renderQueued) return;
  renderQueued = true;
  requestAnimationFrame(() => {
    renderQueued = false;
    render();
  });
}
function action(kind: Action["kind"]) {
  return view?.snapshot.actions.find((a) => a.kind === kind && a.available);
}
function renderList() {
  const list = $("session-list");
  list.replaceChildren();
  if (!sessions.length) {
    list.append(
      el(
        "p",
        "muted list-empty",
        client
          ? "还没有匹配的会话。创建一个新会话开始吧。"
          : "连接工作区后，你的会话会显示在这里。",
      ),
    );
    return;
  }
  for (const session of sessions) {
    const button = el("button", "session-row");
    button.type = "button";
    button.setAttribute(
      "aria-current",
      session.sessionId === state.selected ? "page" : "false",
    );
    const icon = el(
      "span",
      "session-icon",
      session.runStatus === "RUNNING" ? "◌" : "◇",
    );
    icon.setAttribute("aria-hidden", "true");
    const body = el("span", "session-row-body");
    body.append(
      el("span", "session-row-title", session.title || "未命名会话"),
      el("span", "session-row-meta", session.runStatus ?? session.status),
    );
    button.append(icon, body);
    button.onclick = () => void selectSession(session.sessionId);
    list.append(button);
  }
  renderSelectedSessionStatus();
}
function renderSelectedSessionStatus() {
  const snapshot = view?.state === "live" ? view.snapshot : null;
  if (
    snapshot?.hydration !== "ready" ||
    snapshot.scope.sessionId !== state.selected
  )
    return;
  const row = $("session-list").querySelector('[aria-current="page"]');
  if (!row) return;
  const runStatus = snapshot.session.run?.status;
  const label = row.querySelector(".session-row-meta")!;
  const icon = row.querySelector(".session-icon")!;
  const status = runStatus ?? snapshot.session.status;
  if (label.textContent !== status) {
    label.textContent = status;
    icon.textContent = runStatus === "RUNNING" ? "◌" : "◇";
  }
}
function fact(term: string, value: string) {
  const list = $("context");
  list.append(el("dt", "", term), el("dd", "", value));
}
function renderContext() {
  const list = $("context");
  list.replaceChildren();
  if (!client || client.state === "closed") {
    fact("工作区", "等待连接");
    fact("授权范围", "当前页面");
    return;
  }
  const info = client.toJSON() as {
    connection?: {
      displayName: string;
      projectId: string;
      hostIncarnation: string;
    };
  };
  if (info.connection) {
    fact("Host", info.connection.displayName);
    fact("项目", info.connection.projectId);
  }
  if (view) {
    const s = view.snapshot.session;
    fact("模型", s.modelName || s.modelId);
    fact("模型状态", s.modelAvailable ? "可用" : "需要在 Host 端修复后刷新");
    fact("工作区来源", s.context);
    fact(
      "会话授权",
      view.snapshot.consent.state === "ACTIVE"
        ? "当前客户端已明确同意"
        : "每次操作需确认",
    );
    if (s.run) fact("当前任务", s.run.status);
  }
  fact(
    "连接有效至",
    new Date(client.expiresAtEpochMillis).toLocaleTimeString(),
  );
}
function rememberInput(
  input: HTMLInputElement | HTMLTextAreaElement,
  key: string,
) {
  input.dataset.field = key;
  const radio =
    input instanceof HTMLInputElement && input.type === "radio" ? input : null;
  const storageKey = radio?.name ?? key;
  const previous = interactionInputs.get(storageKey);
  if (previous) {
    if (radio) {
      radio.checked = radio.value === previous.value;
    } else {
      input.value = previous.value;
      if (input instanceof HTMLInputElement) input.checked = previous.checked;
    }
  }
  input.addEventListener("input", () => {
    if (radio && !radio.checked) return;
    if (!interactionInputs.has(storageKey) && interactionInputs.size >= 512)
      interactionInputs.delete(interactionInputs.keys().next().value!);
    interactionInputs.set(storageKey, {
      value: input.value,
      checked: input instanceof HTMLInputElement && input.checked,
    });
  });
}
function reviewSupported(entry: Entry): boolean {
  return !!entry.interaction && supportsInteraction(entry.interaction);
}
function renderInteraction(entry: Entry, card: HTMLElement) {
  const interaction = entry.interaction!;
  if (!reviewSupported(entry)) {
    card.append(
      el("p", "warning", "当前版本暂不支持此交互，请在支持的客户端处理。"),
    );
    return;
  }
  const review = interaction.review;
  if (review) {
    card.append(el("p", "review-purpose", review.purpose));
    const detail = el("details", "review");
    detail.open = true;
    detail.append(
      el("summary", "", "查看操作内容"),
      el("pre", "review-content", review.content),
    );
    for (const item of [...review.environment, ...review.technical])
      detail.append(el("p", "review-fact", item.label + " · " + item.value));
    card.append(detail);
    if (!review.targetComplete || review.contentTruncated)
      card.append(
        el(
          "p",
          "warning",
          "审批内容不完整，敏感操作不可用。请获取完整内容后处理。",
        ),
      );
  }
  const form = el("form", "interaction-form");
  const prefix = interaction.id + "/";
  const questions = interaction.input.questions;
  if (questions.length) {
    for (const [index, question] of questions.entries()) {
      const fieldset = el("fieldset");
      fieldset.append(
        el("legend", "", question.header),
        el("p", "", question.question),
      );
      for (const option of question.options) {
        const label = el("label", "option");
        const input = el("input");
        input.type = question.multiple ? "checkbox" : "radio";
        input.name = prefix + index;
        input.value = option.id;
        input.dataset.question = String(index);
        rememberInput(input, prefix + index + "/" + option.id);
        label.append(input, el("span", "", option.label));
        if (option.description)
          label.append(el("small", "", option.description));
        fieldset.append(label);
      }
      if (question.allowCustom) {
        const input = el("textarea");
        input.placeholder = "补充你的选择";
        input.maxLength = 16000;
        input.dataset.custom = String(index);
        rememberInput(input, prefix + index + "/custom");
        fieldset.append(input);
      }
      form.append(fieldset);
    }
  } else if (interaction.input.type === "text") {
    const input = el("textarea");
    input.placeholder = "输入你的答复";
    input.maxLength = Math.min(interaction.input.maximumCharacters, 16000);
    input.dataset.response = "text";
    rememberInput(input, prefix + "text");
    form.append(input);
  } else if (interaction.input.options.length) {
    for (const option of interaction.input.options) {
      const label = el("label", "option");
      const input = el("input");
      input.type =
        interaction.input.type === "single-choice" ? "radio" : "checkbox";
      input.name = prefix + "choice";
      input.value = option.id;
      input.dataset.selection = "true";
      rememberInput(input, prefix + "choice/" + option.id);
      label.append(input, el("span", "", option.label));
      form.append(label);
    }
  }
  let reviewed: HTMLInputElement | null = null;
  if (interaction.kind === "approval") {
    const label = el("label", "review-confirm");
    reviewed = el("input");
    reviewed.type = "checkbox";
    rememberInput(reviewed, prefix + "reviewed");
    label.append(
      reviewed,
      el("span", "", "我已核对本次工具操作的内容和作用范围"),
    );
    form.append(label);
  }
  const controls = el("div", "interaction-actions");
  for (const available of interaction.actions) {
    const approve =
      available.choice === "approve" || available.kind === "allowForSession";
    const button = el(
      "button",
      approve ? "primary" : "secondary",
      available.kind === "allowForSession"
        ? "本会话自动批准"
        : available.choice === "approve"
          ? "批准执行"
          : available.choice === "reject"
            ? "拒绝"
            : available.choice === "submit"
              ? "提交答复"
              : (available.choice ?? available.kind),
    );
    button.type = "button";
    const update = () => {
      button.disabled =
        !available.available ||
        view?.state !== "live" ||
        busy.has(sessionKey(state.selected)) ||
        (approve &&
          (!review?.targetComplete ||
            review.contentTruncated ||
            !reviewed?.checked));
    };
    update();
    reviewed?.addEventListener("change", update);
    if (!available.available) button.title = available.reason ?? "当前不可用";
    button.onclick = () => {
      const answers: QuestionAnswer[] = questions.map((question, index) => ({
        header: question.header,
        optionIds: [
          ...form.querySelectorAll<HTMLInputElement>(
            `input[data-question="${index}"]:checked`,
          ),
        ].map((x) => x.value),
        customText:
          form.querySelector<HTMLTextAreaElement>(
            `textarea[data-custom="${index}"]`,
          )?.value || null,
      }));
      const text =
        form.querySelector<HTMLTextAreaElement>("[data-response]")?.value;
      const selections = [
        ...form.querySelectorAll<HTMLInputElement>("[data-selection]:checked"),
      ].map((x) => x.value);
      void perform(available, { text, selections, answers });
    };
    controls.append(button);
  }
  form.addEventListener("submit", (event) => event.preventDefault());
  form.append(controls);
  card.append(form);
}
function renderEntry(entry: Entry, older = false) {
  const card = el("article", "entry entry-" + entry.kind);
  card.dataset.entry = entry.id;
  const heading = el("div", "entry-heading");
  heading.append(
    el(
      "span",
      "entry-role",
      entry.kind === "message"
        ? entry.role === "user"
          ? "你"
          : "Agent"
        : entry.kind === "tool"
          ? "工具"
          : entry.kind === "interaction"
            ? "需要你的确认"
            : "提示",
    ),
    el("span", "entry-status", entry.status),
  );
  card.append(heading);
  if (entry.title && entry.kind !== "message")
    card.append(el("h3", "", entry.title));
  card.append(el("pre", "entry-text", entry.text));
  if (entry.truncated)
    card.append(el("small", "warning", "展示内容已截断，请核对完整结果。"));
  if (entry.kind === "interaction" && entry.interaction && !older)
    renderInteraction(entry, card);
  const preview = entry.toolCallId
    ? previewBuffers.get(entry.toolCallId)
    : undefined;
  if (preview) {
    const detail = el("details", "tool-preview");
    detail.open = true;
    detail.append(
      el("summary", "", "实时输出 · 不保存"),
      el("pre", "", preview.stdout),
      el("pre", "stderr", preview.stderr),
    );
    if (preview.dropped || preview.truncated)
      detail.append(
        el("small", "warning", "部分输出已省略；预览不代表最终工具结果。"),
      );
    card.append(detail);
  }
  return card;
}
function renderTranscript() {
  const wasNearEnd =
      transcript.scrollHeight - transcript.scrollTop - transcript.clientHeight <
      80,
    previousTop = transcript.scrollTop;
  const top = transcript.getBoundingClientRect().top;
  const anchor = [
    ...transcript.querySelectorAll<HTMLElement>("[data-entry]"),
  ].find((node) => node.getBoundingClientRect().bottom > top + 10);
  const anchorId = anchor?.dataset.entry;
  const anchorOffset = anchor ? anchor.getBoundingClientRect().top - top : 0;
  const focus = document.activeElement as
    | HTMLInputElement
    | HTMLTextAreaElement;
  const focusKey = focus?.dataset?.field;
  const caret =
    focus instanceof HTMLTextAreaElement
      ? [focus.selectionStart, focus.selectionEnd]
      : null;
  if (!view) {
    if (!$("welcome")) {
      const welcome = el("section", "welcome");
      welcome.id = "welcome";
      transcript.replaceChildren(welcome);
      transcript.firstElementChild!.append(
        el("h2", "", "准备好下一项任务了吗？"),
        el("p", "", "在下方描述你的目标，开始一个新会话。"),
      );
    }
    return;
  }
  const snapshot = view.snapshot;
  const session = snapshot.scope.sessionId;
  const cached = history.get(session);
  const saved = cached && snapshot.hydration === "ready"
    ? reanchorHistory(cached, snapshot.history)
    : cached;
  if (saved && saved !== cached) history.set(session, saved);
  transcript.replaceChildren();
  if (saved?.hasEarlier ?? view.snapshot.history.hasEarlier) {
    const older = el("button", "history-button", "加载更早历史");
    older.onclick = () => void loadHistory();
    older.disabled = loading || snapshot.hydration !== "ready";
    transcript.append(older);
  }
  if (saved?.shifted)
    transcript.append(
      el(
        "p",
        "fine-print",
        "已加载的历史窗口有变化，可从当前游标继续查看更早历史。",
      ),
    );
  const activeIds = new Set(view.snapshot.entries.map((entry) => entry.id));
  for (const page of [...(saved?.pages ?? [])].reverse())
    for (const entry of page.entries)
      if (!activeIds.has(entry.id)) {
        transcript.append(renderEntry(entry, true));
        activeIds.add(entry.id);
      }
  for (const entry of view.snapshot.entries)
    transcript.append(renderEntry(entry));
  const live = view.snapshot.live;
  if (live) {
    const lane = el("article", "entry live-entry");
    lane.append(
      el("div", "entry-role", "Agent · 进行中"),
      el("pre", "entry-text", live.text),
    );
    if (live.truncated)
      lane.append(el("small", "warning", "实时正文已截断；请以最终历史为准。"));
    transcript.append(lane);
  }
  if (!view.snapshot.entries.length && !live)
    transcript.append(el("p", "list-empty muted", "当前展示窗口暂无内容。"));
  if (focusKey) {
    const restored = [
      ...transcript.querySelectorAll<HTMLInputElement | HTMLTextAreaElement>(
        "[data-field]",
      ),
    ].find((node) => node.dataset.field === focusKey);
    restored?.focus({ preventScroll: true });
    if (restored instanceof HTMLTextAreaElement && caret)
      restored.setSelectionRange(caret[0]!, caret[1]!);
  }
  const restoredAnchor = anchorId
    ? [...transcript.querySelectorAll<HTMLElement>("[data-entry]")].find(
        (node) => node.dataset.entry === anchorId,
      )
    : undefined;
  if (wasNearEnd && !focusKey) transcript.scrollTop = transcript.scrollHeight;
  else if (restoredAnchor)
    transcript.scrollTop +=
      restoredAnchor.getBoundingClientRect().top - top - anchorOffset;
  else transcript.scrollTop = previousTop;
}
function render() {
  const connected =
    client?.state === "connected" || client?.state === "offline";
  $("pairing").hidden = !!connected;
  $("connection-label").textContent =
    client?.state === "connected"
      ? "已连接工作区"
      : client?.state === "offline"
        ? "连接暂时中断"
        : "等待连接";
  $("connection-dot").classList.toggle("online", client?.state === "connected");
  $("connection-badge").textContent =
    client?.state === "connected"
      ? "本页已授权"
      : client?.state === "offline"
        ? "恢复连接中"
        : "未配对";
  $("disconnect").hidden = !client || client.state === "closed";
  const current = view?.snapshot;
  $("session-title").textContent = current?.session.title ?? "从一个想法开始";
  $("session-subtitle").textContent = current
    ? view!.state === "live"
      ? "基于当前工作区的会话与任务"
      : "正在确认连接和权威状态，请稍候。"
    : "和 Agent 一起理解代码、解决问题、完成开发。";
  $("refresh-view").hidden = !view;
  $("refresh-view").onclick = () => void recover();
  const cancel = action("cancel");
  $("cancel-run").hidden = !cancel;
  $<HTMLButtonElement>("cancel-run").disabled =
    !cancel || view?.state !== "live";
  $("cancel-run").onclick = () => {
    if (cancel) void perform(cancel);
  };
  const key = sessionKey(state.selected),
    uncertain = state.get(state.selected).uncertain;
  $<HTMLButtonElement>("send").disabled =
    !client ||
    client.state !== "connected" ||
    busy.has(key) ||
    uncertain ||
    !draft.value.trim() ||
    (!!view && (view.state !== "live" || !action("submit"))) ||
    (!view && state.selected !== null);
  $("compose-hint").textContent = uncertain
    ? "发送结果未知；先核对会话与历史，再开始新的输入"
    : view?.snapshot.session.run?.status === "RUNNING"
      ? "任务进行中，可通过上方按钮精确停止"
      : client?.state === "connected"
        ? "Enter 发送 · Shift + Enter 换行"
        : "先连接工作区，再开始新的会话";
  const toolbar = $("session-toolbar");
  toolbar.replaceChildren();
  if (uncertain) {
    const reset = el("button", "text-button", "已核对结果，开始新的输入");
    reset.onclick = () => {
      state.update(state.selected, { uncertain: false, text: "" });
      restoreDraft();
      message("已清空旧输入。新的发送需要你的明确操作。");
      scheduleRender();
    };
    toolbar.append(reset);
  }
  for (const reset of view?.snapshot.actions.filter(
    (a) => a.kind === "resetConsent",
  ) ?? []) {
    const button = el("button", "text-button", "撤销本会话自动批准");
    button.disabled = !reset.available || view?.state !== "live";
    button.onclick = () => void perform(reset);
    toolbar.append(button);
  }
  renderSelectedSessionStatus();
  renderTranscript();
  renderContext();
  startPreview();
  void applySessionConsent();
}
async function applySessionConsent() {
  const current = view,
    requester = client;
  if (
    !current ||
    !requester ||
    requester.state !== "connected" ||
    document.visibilityState !== "visible" ||
    current.state !== "live" ||
    current.snapshot.hydration !== "ready"
  )
    return;
  const snapshot = current.snapshot;
  const session = snapshot.scope.sessionId;
  const entry = snapshot.entries.find((value) =>
    value.interaction?.actions.some((action) => action.kind === "allowForSession"),
  );
  const action = entry?.interaction?.actions.find(
    (candidate) => candidate.kind === "allowForSession",
  );
  const target = action?.target;
  const key = target
    ? JSON.stringify([
        session,
        target.runId,
        target.interactionId,
        target.targetRevision,
      ])
    : null;
  state.reconcileApprovalPreparation(session, key);
  if (
    !target ||
    !key ||
    !entry ||
    !action?.available ||
    snapshot.consent.state !== "ACTIVE" ||
    busy.has(sessionKey(session)) ||
    !reviewSupported(entry) ||
    !entry.interaction?.review?.targetComplete ||
    entry.interaction.review.contentTruncated ||
    !state.claimApprovalPreparation(session, key)
  )
    return;
  const requestId = crypto.randomUUID();
  try {
    const result = await requester.execute({
      command: {
        kind: "command",
        operation: "prepareApproval",
        clientRequestId: requestId,
        idempotencyKey: requestId,
        target: { projectId: requester.connection.projectId, ...target },
        text: null,
        optionId: null,
        executionId: null,
        selections: [],
      },
      options: { approved: false, includeInContext: false, answers: [] },
    });
    if (requester !== client) return;
    const unknown = result.receipt.disposition === "unknown";
    state.settleApprovalPreparation(key, unknown);
    if (unknown) acknowledge(result, session);
  } catch (error) {
    if (requester !== client) return;
    state.settleApprovalPreparation(
      key,
      !(error instanceof CodingClientError) || error.outcomeUnknown,
    );
    if (view === current) message(explain(error), true);
  } finally {
    if (requester === client) scheduleRender();
  }
}
async function refreshList() {
  if (!client || client.state !== "connected") return;
  const requester = client,
    generation = ++searchGeneration;
  try {
    const result = await requester.listSessions(
      $<HTMLInputElement>("search").value || null,
    );
    if (
      generation !== searchGeneration ||
      client !== requester ||
      requester.state !== "connected"
    )
      return;
    sessions = result.sessions;
    renderList();
    if (result.truncated)
      message("列表仅显示当前窗口；可以搜索更具体的会话名称。");
  } catch (error) {
    message(explain(error), true);
    scheduleRender();
  }
}
async function selectSession(session: string | null) {
  saveDraft();
  const generation = state.select(session);
  offView?.();
  view?.close();
  view = null;
  stopPreview();
  restoreDraft();
  renderList();
  render();
  $("sidebar").classList.remove("open");
  $("menu").setAttribute("aria-expanded", "false");
  sidebarAccess();
  if (!session || !client) return;
  try {
    const opened = await client.observe(session);
    if (!state.current(generation, session)) {
      opened.close();
      return;
    }
    view = opened;
    offView = opened.onChange(scheduleRender);
    render();
    restoreDraft();
  } catch (error) {
    if (state.current(generation, session)) message(explain(error), true);
  }
}
function acknowledge(result: CommandResult, session: string | null) {
  if (result.receipt.disposition === "unknown") {
    state.update(session, { uncertain: true });
    if (state.selected === session)
      message(
        "操作结果未知。任务可能已被接收，请先核对权威会话和历史；本页不会自动重发。",
        true,
      );
  } else if (state.selected === session)
    message(
      result.receipt.disposition === "accepted"
        ? "已接收操作。执行结果以会话中的最新状态为准。"
        : "操作未被接受，请刷新权威状态后核对。",
      result.receipt.disposition === "rejected",
    );
}
async function perform(
  available: Action,
  input: {
    text?: string;
    selections?: ReadonlyArray<string>;
    answers?: ReadonlyArray<QuestionAnswer>;
  } = {},
) {
  const current = view;
  if (!current || !client) return;
  const session = current.snapshot.scope.sessionId,
    key = sessionKey(session);
  if (busy.has(key)) return;
  busy.add(key);
  render();
  try {
    acknowledge(await current.act(available, input), session);
    await refreshList();
  } catch (error) {
    if (state.selected === session) message(explain(error), true);
  } finally {
    busy.delete(key);
    scheduleRender();
  }
}
async function loadHistory() {
  if (!client || !view || loading || view.snapshot.hydration !== "ready") return;
  const session = view.snapshot.scope.sessionId,
    generation = state.generation;
  const snapshot = view.snapshot;
  const cached = history.get(session);
  const previous = cached ? reanchorHistory(cached, snapshot.history) : {
    pages: [], cursor: snapshot.history.beforeSequence,
    liveBefore: snapshot.history.beforeSequence,
    hasEarlier: snapshot.history.hasEarlier, shifted: false,
  };
  loading = true;
  try {
    const page = await client.history(
      session,
      32,
      previous.cursor,
    );
    if (
      !state.current(generation, session) ||
      !view ||
      view.snapshot.hydration !== "ready"
    )
      return;
    const current = reanchorHistory(previous, view.snapshot.history);
    if (current !== previous) {
      history.set(session, current);
      return;
    }
    if (
      previous.cursor !== null &&
      page.history.hasEarlier &&
      page.history.beforeSequence === previous.cursor
    ) {
      message("历史游标未前进，请重新获取会话。", true);
      return;
    }
    const pages = [...previous.pages, page];
    let shifted = previous.shifted;
    while (pages.length > 4) {
      pages.shift();
      shifted = true;
    }
    if (!history.has(session) && history.size >= 8)
      history.delete(history.keys().next().value!);
    history.set(session, {
      pages,
      cursor: page.history.beforeSequence,
      liveBefore: previous.liveBefore,
      hasEarlier: page.history.hasEarlier,
      shifted,
    });
  } catch (error) {
    message(explain(error), true);
  } finally {
    loading = false;
    scheduleRender();
  }
}
function stopPreview() {
  previewController?.abort();
  previewController = null;
  previewRun = null;
  previewBuffers.clear();
}
function startPreview() {
  const run = view?.snapshot.actions.find(
    (item) => item.kind === "cancel" && item.available,
  )?.target.runId;
  if (
    !client ||
    client.state !== "connected" ||
    view?.state !== "live" ||
    !run
  ) {
    if (previewRun) stopPreview();
    return;
  }
  if (previewRun === run) return;
  stopPreview();
  try {
    if (!client.connection.capabilities.includes("preview-v1")) return;
  } catch {
    return;
  }
  const session = view.snapshot.scope.sessionId,
    generation = state.generation;
  previewRun = run;
  const controller = new AbortController();
  previewController = controller;
  void (async () => {
    try {
      for await (const frame of client!.preview(
        session,
        run,
        controller.signal,
      )) {
        if (
          controller.signal.aborted ||
          !state.current(generation, session) ||
          previewRun !== frame.runId
        )
          return;
        appendPreview(frame);
        scheduleRender();
      }
    } catch {
      if (!controller.signal.aborted && previewRun === run)
        message("实时工具预览已断开；会话与最终工具结果仍可通过权威状态核对。");
    }
  })();
}
function appendPreview(frame: Preview) {
  if (!previewBuffers.has(frame.toolCallId) && previewBuffers.size >= 8)
    previewBuffers.delete(previewBuffers.keys().next().value!);
  const buffer = previewBuffers.get(frame.toolCallId) ?? {
    stdout: "",
    stderr: "",
    dropped: false,
    truncated: false,
  };
  const channel = frame.channel === "STDOUT" ? "stdout" : "stderr";
  const text = buffer[channel] + frame.text;
  buffer[channel] = text.slice(-16000);
  buffer.dropped ||= frame.previewDropped || text.length > 16000;
  buffer.truncated ||= frame.outputTruncated;
  previewBuffers.set(frame.toolCallId, buffer);
}
async function recover() {
  if (!client) return;
  try {
    await client.foreground();
    await refreshList();
    message("已重新核对连接与会话状态。");
  } catch (error) {
    message(explain(error), true);
  } finally {
    scheduleRender();
  }
}
$("pair-form").addEventListener("submit", (event) => {
  event.preventDefault();
  void (async () => {
    const input = $<HTMLInputElement>("pair-code"),
      button = $<HTMLButtonElement>("pair-button");
    const challenge = input.value.trim();
    if (!challenge || button.disabled) return;
    button.disabled = true;
    const pairGeneration = ++connectionGeneration;
    searchGeneration++;
    try {
      offView?.();
      view?.close();
      view = null;
      stopPreview();
      if (client) await client.close();
      const paired = await CodingBrowserClient.pair(challenge);
      if (pairGeneration !== connectionGeneration) {
        await paired.close();
        return;
      }
      client = paired;
      state.bindWorkspace(paired.connection.projectId);
      history.clear();
      interactionInputs.clear();
      client.onChange(() => {
        if (
          client?.state === "pairing-required" ||
          client?.state === "incompatible"
        ) {
          message(
            explain(new CodingClientError(client.reason ?? "PAIRING_REQUIRED")),
            true,
          );
          stopPreview();
        }
        scheduleRender();
      });
      sessions = [];
      state.select(null);
      restoreDraft();
      await refreshList();
      message("工作区已连接。请选择已有会话，或开始一个新任务。");
    } catch (error) {
      message(explain(error), true);
    } finally {
      input.value = "";
      button.disabled = false;
      scheduleRender();
    }
  })();
});
$("compose-form").addEventListener("submit", (event) => {
  event.preventDefault();
  void (async () => {
    if (!client || $<HTMLButtonElement>("send").disabled) return;
    const requester = client,
      connection = connectionGeneration,
      selection = state.generation,
      session = state.selected,
      text = draft.value,
      current = view,
      key = sessionKey(session);
    saveDraft();
    busy.add(key);
    render();
    try {
      const result = current
        ? await current.act(action("submit")!, { text })
        : await requester.createSession(text);
      if (requester !== client || connection !== connectionGeneration) return;
      acknowledge(result, session);
      if (result.receipt.disposition === "accepted") {
        state.accepted(session, text);
        if (state.current(selection, session)) restoreDraft();
        await refreshList();
        if (
          !current &&
          result.session &&
          connection === connectionGeneration &&
          state.current(selection, null)
        )
          await selectSession(result.session.sessionId);
      }
    } catch (error) {
      message(explain(error), true);
    } finally {
      busy.delete(key);
      scheduleRender();
    }
  })();
});
draft.addEventListener("input", () => {
  saveDraft();
  scheduleRender();
});
draft.addEventListener("select", saveDraft);
draft.addEventListener("keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    $("compose-form").dispatchEvent(new Event("submit", { cancelable: true }));
  }
});
transcript.addEventListener("scroll", saveDraft, { passive: true });
$("new-session").onclick = () => void selectSession(null);
$("refresh-list").onclick = () => void refreshList();
let searchTimer: ReturnType<typeof setTimeout>;
$("search").addEventListener("input", () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => void refreshList(), 250);
});
$("disconnect").onclick = () => {
  connectionGeneration++;
  searchGeneration++;
  void (async () => {
    saveDraft();
    offView?.();
    view?.close();
    view = null;
    stopPreview();
    await client?.close();
    sessions = [];
    renderList();
    render();
    message("已断开观察。已接收的执行会继续由 Host 持有。");
  })();
};
$("menu").onclick = () => {
  const open = $("sidebar").classList.toggle("open");
  $("menu").setAttribute("aria-expanded", String(open));
};
for (const button of document.querySelectorAll<HTMLButtonElement>(
  "[data-suggestion]",
))
  button.onclick = () => {
    draft.value = button.dataset.suggestion!;
    saveDraft();
    draft.focus();
    scheduleRender();
  };
function recoverVisibleConnection() {
  if (
    document.visibilityState === "visible" &&
    (client?.state === "connected" || client?.state === "offline")
  )
    void recover();
}
document.addEventListener("visibilitychange", recoverVisibleConnection);
window.addEventListener("online", recoverVisibleConnection);
setInterval(() => {
  if (document.visibilityState === "visible" && client?.state === "connected")
    void client.renew().catch((error) => {
      message(explain(error), true);
      scheduleRender();
    });
}, 20000);
window.addEventListener("pagehide", () => {
  offView?.();
  view?.close();
  stopPreview();
  void client?.close();
});
render();

const compact = matchMedia("(max-width:760px)");
function sidebarAccess() {
  const open = $("sidebar").classList.contains("open");
  $("sidebar").inert = compact.matches && !open;
}
compact.addEventListener("change", sidebarAccess);
sidebarAccess();
$("menu").addEventListener("click", sidebarAccess);
document.addEventListener("keydown", (event) => {
  if (event.key === "Escape" && compact.matches) {
    $("sidebar").classList.remove("open");
    $("menu").setAttribute("aria-expanded", "false");
    sidebarAccess();
    $("menu").focus();
  }
});
document.addEventListener("pointerdown", (event) => {
  if (
    compact.matches &&
    !$("sidebar").contains(event.target as Node) &&
    !$("menu").contains(event.target as Node)
  ) {
    $("sidebar").classList.remove("open");
    $("menu").setAttribute("aria-expanded", "false");
    sidebarAccess();
  }
});

import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { CodingBrowserClient, RequestTracker } from "../dist/index.js";
const fixture = JSON.parse(
  await readFile(
    new URL(
      "../../haifa-coding-api/contract/fixtures/snapshot.json",
      import.meta.url,
    ),
    "utf8",
  ),
);
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((a, b) => {
    resolve = a;
    reject = b;
  });
  return { promise, resolve, reject };
};
const envelope = (id, result, status = 200) =>
  new Response(JSON.stringify({ jsonrpc: "2.0", id, result }), {
    status,
    headers: { "Content-Type": "application/json" },
  });
const error = (id, reasonCode, status = 403) =>
  new Response(
    JSON.stringify({
      jsonrpc: "2.0",
      id,
      error: { code: -32000, data: { reasonCode } },
    }),
    { status, headers: { "Content-Type": "application/json" } },
  );
const command = (id = "once") => ({
  command: {
    kind: "command",
    operation: "create",
    clientRequestId: id,
    idempotencyKey: "domain-" + id,
    target: {
      projectId: null,
      sessionId: null,
      runId: null,
      interactionId: null,
      targetRevision: null,
    },
    text: "synthetic turn",
    optionId: null,
    executionId: null,
    selections: [],
  },
  options: { approved: false, includeInContext: false, answers: [] },
});
const result = (id, disposition = "accepted") => ({
  receipt: {
    clientRequestId: id,
    disposition,
    operationId: "operation",
    runId: "new-run",
    reasonCode: null,
  },
  session: null,
  queuedMessage: null,
  restoredMessage: null,
  shellPlan: null,
  shellExecution: null,
  shellResult: null,
  compaction: null,
  export: null,
  modelSelection: null,
  interactionReceipt: null,
  interaction: null,
});
const emptyQuery = () => ({
  sessions: [],
  run: null,
  outcome: null,
  models: [],
  queuedMessages: [],
  shellExecutions: [],
  workspaces: [],
  lines: [],
  authentication: null,
  truncated: false,
});
function changed(view, predicate) {
  if (predicate()) return Promise.resolve();
  const ready = deferred();
  const off = view.onChange(() => {
    if (predicate()) {
      off();
      ready.resolve();
    }
  });
  return ready.promise;
}
class Wire {
  origin = "http://127.0.0.1:19744";
  credentials = "omit";
  calls = [];
  subscriptions = 0;
  streams = [];
  waiting = [];
  mutations = 0;
  denied = false;
  legacy = false;
  onCommand = null;
  onRenew = null;
  onEvents = null;
  value() {
    const value = structuredClone(fixture);
    value.scope.source = "browser";
    value.scope.viewId = "view-" + this.subscriptions;
    return value;
  }
  nextStream() {
    if (this.streams.length) return Promise.resolve(this.streams.shift());
    const next = deferred();
    this.waiting.push(next);
    return next.promise;
  }
  fetch = async (url, options = {}) => {
    assert.equal(new URL(url).origin, this.origin);
    assert.equal(options.credentials, this.credentials);
    assert.equal(options.redirect, "error");
    assert.equal(new URL(url).searchParams.has("grant"), false);
    if (url.endsWith("/info"))
      return envelope(null, {
        hostIncarnation: "host-1",
        protocolVersion: "1",
        browserAuthorization: "browser-grant-v1",
        displayName: "Fixture",
      });
    if (url.endsWith("/pair")) {
      this.calls.push("pair");
      return envelope(null, {
        grant: "fixture-grant-NOT-REAL",
        clientId: "client-1",
        hostIncarnation: "host-1",
        expiresAtEpochMillis: Date.now() + 60000,
      });
    }
    assert.ok(options.headers.Authorization?.startsWith("Bearer "));
    if (new URL(url).pathname.endsWith("/events")) {
      this.calls.push("events");
      if (this.denied) return error(null, "BROWSER_AUTH_REQUIRED");
      if (this.onEvents) return this.onEvents(options);
      const body = new ReadableStream({
        start: (controller) => {
          const queued = this.waiting.shift();
          if (queued) queued.resolve(controller);
          else this.streams.push(controller);
        },
      });
      return new Response(body, {
        headers: { "Content-Type": "text/event-stream" },
      });
    }
    const rpc = JSON.parse(options.body);
    this.calls.push(rpc.method);
    if (this.denied) return error(null, "BROWSER_AUTH_REQUIRED");
    switch (rpc.method) {
      case "initialize":
        return envelope(rpc.id, {
          protocolVersion: "1",
          hostIncarnation: "host-1",
          clientId: "client-1",
          projectId: "project-1",
          displayName: "Fixture",
          clientSource: "browser",
          browserAuthorization: "browser-grant-v1",
          optionalCapabilities: this.legacy
            ? []
            : [
                "coding-product-v1",
                "session-presentation-v1",
                "session-consent-v1",
              ],
        });
      case "product/query":
        return envelope(rpc.id, emptyQuery());
      case "product/command":
        this.mutations++;
        return this.onCommand
          ? this.onCommand(rpc, options)
          : envelope(rpc.id, result(rpc.params.command.clientRequestId));
      case "presentation/subscribe":
        this.subscriptions++;
        return envelope(rpc.id, {
          subscriptionId: "sub-" + this.subscriptions,
          snapshot: this.value(),
        });
      case "client/renew":
        if (this.onRenew) await this.onRenew();
        return envelope(rpc.id, { renewed: true });
      default:
        return envelope(rpc.id, { closed: true });
    }
  };
  pair(options = {}) {
    return CodingBrowserClient.pair("fixture-challenge-NOT-REAL", {
      origin: this.origin,
      fetch: this.fetch,
      ...options,
    });
  }
  frame(controller, update) {
    controller.enqueue(
      new TextEncoder().encode(
        "event: presentation\r\ndata: " + JSON.stringify(update) + "\r\n\r\n",
      ),
    );
  }
  update(view, base = 0, revision = 1) {
    return {
      kind: "update",
      viewId: view.snapshot.scope.viewId,
      baseRevision: base,
      revision,
      replacement: { ...structuredClone(view.snapshot), revision },
    };
  }
}
test("single pairing negotiates its bound context; proofs are absent from public diagnostics", async () => {
  const wire = new Wire(),
    client = await wire.pair();
  try {
    assert.equal(client.connection.source, "browser");
    assert.equal(client.connection.clientId, "client-1");
    assert.equal(JSON.stringify(client).includes("fixture-grant"), false);
    await client.listSessions();
    assert.equal(wire.calls.filter((x) => x === "pair").length, 1);
    assert.equal(wire.calls.filter((x) => x === "initialize").length, 1);
  } finally {
    await client.close();
  }
});
test("old capabilities fail explicitly without mutation or native fallback", async () => {
  const wire = new Wire();
  wire.legacy = true;
  await assert.rejects(wire.pair(), { code: "BROWSER_CAPABILITY_UNSUPPORTED" });
  assert.equal(wire.mutations, 0);
});
test("lost or invalid mutation receipts remain unknown, and identical request IDs cannot be resent", async () => {
  for (const invalid of ["lost", "wrong-id", "invalid-root"]) {
    const wire = new Wire(),
      client = await wire.pair();
    try {
      wire.onCommand = (rpc) =>
        invalid === "lost"
          ? Promise.reject(new TypeError("synthetic connection loss"))
          : invalid === "wrong-id"
            ? envelope(rpc.id, result("another-request"))
            : new Response("[]", {
                headers: { "Content-Type": "application/json" },
              });
      assert.equal(
        (await client.execute(command())).receipt.disposition,
        "unknown",
      );
      assert.equal(client.requests[0].disposition, "unknown");
      await assert.rejects(client.execute(command()), {
        code: "REQUEST_ALREADY_SENT",
      });
      assert.equal(wire.mutations, 1);
    } finally {
      await client.close();
    }
  }
});
test("stalled response body times out with a single unknown side effect", async () => {
  const wire = new Wire(),
    client = await wire.pair({ requestTimeoutMs: 100 });
  let cancelled = 0;
  wire.onCommand = () =>
    new Response(
      new ReadableStream({
        start(c) {
          c.enqueue(new TextEncoder().encode("{"));
        },
        cancel() {
          cancelled++;
        },
      }),
      { headers: { "Content-Type": "application/json" } },
    );
  try {
    assert.equal(
      (await client.execute(command())).receipt.disposition,
      "unknown",
    );
    assert.equal(wire.mutations, 1);
    assert.equal(cancelled, 1);
  } finally {
    await client.close();
  }
});
test("updates before receipts do not resolve requests; duplicates apply atomically and no text is treated as authority", async () => {
  const wire = new Wire(),
    client = await wire.pair(),
    view = await client.observe("session-1");
  const channel = await wire.nextStream();
  try {
    const receipt = deferred();
    let rpcId;
    wire.onCommand = (rpc) => {
      rpcId = rpc.id;
      return receipt.promise;
    };
    const pending = client.execute(command());
    const ready = changed(view, () => view.snapshot.revision === 1);
    wire.frame(channel, wire.update(view));
    await ready;
    assert.equal(client.requests[0].disposition, "sending");
    receipt.resolve(envelope(rpcId, result("once")));
    assert.equal((await pending).receipt.disposition, "accepted");
    const next = changed(view, () => view.snapshot.revision === 2);
    wire.frame(channel, wire.update(view, 0, 1));
    wire.frame(channel, wire.update(view, 1, 2));
    await next;
    assert.equal(view.snapshot.revision, 2);
    assert.ok(Object.isFrozen(view.snapshot.entries));
  } finally {
    view.close();
    await client.close();
  }
});
test("foreign scope causes a fresh view before publishing, never partial application", async () => {
  const wire = new Wire(),
    client = await wire.pair(),
    view = await client.observe("session-1"),
    channel = await wire.nextStream();
  try {
    const old = view.snapshot.scope.viewId;
    const ready = changed(view, () => view.snapshot.scope.viewId !== old);
    const foreign = wire.update(view);
    foreign.replacement.scope.clientId = "foreign";
    wire.frame(channel, foreign);
    await ready;
    assert.equal(view.snapshot.revision, 0);
    assert.equal(view.snapshot.scope.clientId, "client-1");
    assert.equal(wire.mutations, 0);
  } finally {
    view.close();
    await client.close();
  }
});
test("critical protocol errors stop automatic recovery and auth denial terminates observation", async () => {
  const wire = new Wire(),
    client = await wire.pair(),
    view = await client.observe("session-1"),
    channel = await wire.nextStream();
  try {
    const stopped = changed(view, () => view.state === "closed");
    const invalid = wire.update(view);
    invalid.replacement.entries[0].kind = "unknown-critical";
    wire.frame(channel, invalid);
    await stopped;
    assert.equal(wire.subscriptions, 1);
    assert.equal(view.reason, "PRESENTATION_INVALID");
    wire.denied = true;
    await assert.rejects(client.listSessions(), {
      code: "BROWSER_AUTH_REQUIRED",
    });
    assert.equal(client.state, "pairing-required");
    assert.equal(wire.calls.filter((x) => x === "pair").length, 1);
  } finally {
    await client.close();
  }
});
test("foreground recovery is single-flight and disables stale sensitive actions before renewal", async () => {
  const wire = new Wire(),
    client = await wire.pair(),
    view = await client.observe("session-1");
  await wire.nextStream();
  try {
    const renewal = deferred();
    wire.onRenew = () => renewal.promise;
    const first = client.foreground(),
      second = client.foreground();
    assert.equal(first, second);
    assert.equal(view.state, "recovering");
    await assert.rejects(
      view.act({
        kind: "cancel",
        target: {
          sessionId: "session-1",
          runId: "run-1",
          interactionId: null,
          targetRevision: null,
        },
        choice: null,
        available: true,
        reason: null,
      }),
      { code: "VIEW_NOT_READY" },
    );
    renewal.resolve();
    await first;
    assert.equal(view.state, "live");
    assert.equal(view.snapshot.consent.state, "ASK");
    assert.equal(wire.mutations, 0);
  } finally {
    view.close();
    await client.close();
  }
});
test("automatic network recovery is bounded and does not create a new client or replay commands", async () => {
  const wire = new Wire();
  wire.onEvents = () =>
    new Response(
      new ReadableStream({
        start(c) {
          c.close();
        },
      }),
      { headers: { "Content-Type": "text/event-stream" } },
    );
  const client = await wire.pair(),
    view = await client.observe("session-1");
  try {
    await changed(view, () => view.state === "unavailable");
    assert.equal(view.reason, "RECOVERY_EXHAUSTED");
    assert.equal(wire.subscriptions, 4);
    assert.equal(wire.calls.filter((x) => x === "initialize").length, 1);
    assert.equal(wire.mutations, 0);
  } finally {
    view.close();
    await client.close();
  }
});

test("gateway session is HTTPS opt-in and a marked ingress denial is a known rejection", async () => {
  const wire = new Wire();
  await assert.rejects(wire.pair({ gatewaySession: true }), {
    code: "GATEWAY_HTTPS_REQUIRED",
  });
  wire.origin = "https://127.0.0.1:19744";
  wire.credentials = "same-origin";
  const client = await wire.pair({ gatewaySession: true });
  try {
    const view = await client.observe("session-1");
    await changed(view, () => wire.calls.includes("events"));
    wire.onCommand = () => new Response(null, { status: 401 });
    const unmarked = await client.execute(command("unmarked"));
    assert.equal(unmarked.receipt.disposition, "unknown");
    assert.equal(client.state, "connected");
    wire.onCommand = () =>
      new Response(null, {
        status: 401,
        headers: { "X-Haifa-Gateway-Session": "required" },
      });
    const commandResult = await client.execute(command());
    assert.equal(commandResult.receipt.disposition, "rejected");
    assert.equal(commandResult.receipt.reasonCode, "GATEWAY_SESSION_REQUIRED");
    assert.equal(client.state, "pairing-required");
    assert.equal(wire.mutations, 2);
  } finally {
    await client.close();
  }
});
test("healthy presentation updates restore the budget for subsequent network interruptions", async () => {
  const wire = new Wire(),
    client = await wire.pair(),
    view = await client.observe("session-1");
  try {
    for (let interruption = 0; interruption < 4; interruption++) {
      const stream = await wire.nextStream();
      const { revision, scope } = view.snapshot;
      const delivered = changed(view, () => view.snapshot.revision > revision);
      wire.frame(stream, wire.update(view, revision, revision + 1));
      await delivered;

      const recovered = changed(
        view,
        () =>
          view.state === "unavailable" ||
          (view.state === "live" &&
            view.snapshot.scope.viewId !== scope.viewId),
      );
      stream.close();
      await recovered;
      assert.equal(view.state, "live");
    }
    assert.equal(wire.subscriptions, 5);
  } finally {
    view.close();
    await client.close();
  }
});
test("unknown request association is not upgraded by observing a terminal Run", () => {
  const tracker = new RequestTracker(),
    c = command();
  c.command.target.sessionId = "session-1";
  c.command.target.runId = "known-run";
  tracker.begin(c.command);
  tracker.receipt({ ...result("once", "unknown").receipt, runId: null }, null);
  tracker.observeRun("once", { id: "known-run", status: "COMPLETED" });
  assert.equal(tracker.list()[0].disposition, "unknown");
  assert.equal(tracker.list()[0].relatedRun.status, "COMPLETED");
  assert.throws(() => tracker.observeRun("once", { id: "another-run" }), {
    code: "OUTCOME_SCOPE_MISMATCH",
  });
});

test("known receipts retire without losing unknown or in-flight command associations", () => {
  const tracker = new RequestTracker();
  const pending = (id) => ({ ...command().command, clientRequestId: id });
  tracker.begin(pending("in-flight"));
  tracker.begin(pending("unknown"));
  tracker.receipt(result("unknown", "unknown").receipt, null);
  for (let i = 0; i < 256; i++) {
    const id = "accepted-" + i;
    tracker.begin(pending(id));
    tracker.receipt(result(id).receipt, null);
  }
  const tracked = tracker.list();
  assert.equal(tracked.length, 128);
  assert.equal(
    tracked.find((r) => r.requestId === "in-flight")?.disposition,
    "sending",
  );
  assert.equal(
    tracked.find((r) => r.requestId === "unknown")?.disposition,
    "unknown",
  );
  assert.equal(
    tracked.some((r) => r.requestId === "accepted-0"),
    false,
  );
  assert.equal(tracked.at(-1).requestId, "accepted-255");
});

test("local grant expiry closes observations and never renews or pairs silently", async (t) => {
  const wire = new Wire(),
    client = await wire.pair(),
    view = await client.observe("session-1");
  await wire.nextStream();
  try {
    const future = client.expiresAtEpochMillis + 1;
    t.mock.method(Date, "now", () => future);
    await assert.rejects(client.renew(), { code: "PAIRING_REQUIRED" });
    assert.equal(client.state, "pairing-required");
    assert.equal(view.state, "closed");
    assert.equal(wire.calls.filter((x) => x === "client/renew").length, 0);
    assert.equal(wire.calls.filter((x) => x === "pair").length, 1);
  } finally {
    await client.close();
  }
});
test("foreground recovery ignores an observation explicitly closed while renewal is pending", async () => {
  const wire = new Wire(),
    client = await wire.pair(),
    view = await client.observe("session-1");
  await wire.nextStream();
  try {
    const renewal = deferred();
    wire.onRenew = () => renewal.promise;
    const pending = client.foreground();
    view.close();
    renewal.resolve();
    await pending;
    assert.equal(client.state, "connected");
    assert.equal(wire.subscriptions, 1);
  } finally {
    await client.close();
  }
});
test("approval uses an available exact target and refuses incomplete review before sending", async () => {
  const wire = new Wire(),
    original = wire.value.bind(wire);
  wire.value = () => {
    const value = original();
    const item = value.entries.find((e) => e.interaction);
    item.interaction.input.type = "none";
    item.interaction.review = {
      purpose: "synthetic review",
      contentType: "File content",
      content: "synthetic only",
      environment: [],
      technical: [],
      risk: null,
      targetComplete: false,
      contentTruncated: true,
    };
    item.interaction.actions[0].available = true;
    item.interaction.actions[0].reason = null;
    return value;
  };
  const client = await wire.pair(),
    view = await client.observe("session-1");
  await wire.nextStream();
  try {
    const exact = view.snapshot.entries.find((e) => e.interaction).interaction
      .actions[0];
    await assert.rejects(view.act(exact), { code: "REVIEW_INCOMPLETE" });
    await assert.rejects(
      view.act({ ...exact, target: { ...exact.target, targetRevision: 8 } }),
      { code: "ACTION_UNAVAILABLE" },
    );
    assert.equal(wire.mutations, 0);
  } finally {
    view.close();
    await client.close();
  }
});

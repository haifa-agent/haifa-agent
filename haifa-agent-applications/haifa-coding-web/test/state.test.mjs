import test from "node:test";
import assert from "node:assert/strict";
import {
  orderTranscriptEntries,
  reanchorHistory,
  WorkspaceState,
} from "../src/state.ts";
test("switching preserves drafts, caret and reading position without sharing them", () => {
  const state = new WorkspaceState();
  state.select("A");
  state.update("A", {
    text: "中文 draft",
    selectionStart: 2,
    selectionEnd: 3,
    scrollTop: 120,
  });
  const old = state.select("B");
  state.update("B", { text: "another" });
  state.select("A");
  assert.deepEqual(state.get("A"), {
    text: "中文 draft",
    selectionStart: 2,
    selectionEnd: 3,
    scrollTop: 120,
    uncertain: false,
  });
  assert.equal(state.current(old, "B"), false);
  assert.equal(state.get("B").text, "another");
});
test("a late accepted receipt cannot clear a newer draft or mark unknown as success", () => {
  const state = new WorkspaceState();
  state.update("A", { text: "sent" });
  state.update("A", { text: "new draft", uncertain: true });
  state.accepted("A", "sent");
  assert.equal(state.get("A").text, "new draft");
  assert.equal(state.get("A").uncertain, true);
  state.accepted("A", "new draft");
  assert.equal(state.get("A").text, "");
  assert.equal(state.get("A").uncertain, true);
});

test("workspace changes isolate local drafts and invalidate old selection generations", () => {
  const state = new WorkspaceState();
  state.update(null, { text: "unsent before pairing" });
  state.bindWorkspace("project-A");
  assert.equal(state.get(null).text, "unsent before pairing");
  state.select("session-1");
  state.update("session-1", { text: "project A draft" });
  const old = state.generation;
  state.bindWorkspace("project-B");
  assert.equal(state.get("session-1").text, "");
  assert.equal(state.current(old, "session-1"), false);
});

test("confirmed new approval targets retire settled work while unknowns stay protected", () => {
  const state = new WorkspaceState();
  state.bindWorkspace("project");
  const session = "session-A";
  state.claimApprovalPreparation(session, "unknown-target");
  state.settleApprovalPreparation("unknown-target", true);
  state.claimApprovalPreparation(session, "in-flight-target");
  for (let i = 0; i < 256; i++) {
    const target = "approval-" + i;
    state.reconcileApprovalPreparation(session, target);
    assert.equal(state.claimApprovalPreparation(session, target), true);
    state.settleApprovalPreparation(target, false);
    assert.equal(state.claimApprovalPreparation(session, target), false);
  }
  assert.equal(state.claimApprovalPreparation(session, "unknown-target"), false);
  assert.equal(state.claimApprovalPreparation(session, "in-flight-target"), false);
  state.bindWorkspace("project");
  assert.equal(state.claimApprovalPreparation(session, "unknown-target"), true);
});

test("an advancing live window invalidates stale pages and reopens pagination", () => {
  const cached = {
    pages: [{ entries: [{ id: "old-message" }] }],
    cursor: null,
    liveBefore: 10,
    hasEarlier: false,
    shifted: false,
  };
  const unchanged = { beforeSequence: 10, hasEarlier: true };
  assert.equal(reanchorHistory(cached, unchanged), cached);
  assert.deepEqual(reanchorHistory(cached, { ...unchanged, beforeSequence: 11 }), {
    pages: [], cursor: 11, liveBefore: 11, hasEarlier: true, shifted: true,
  });
});

test("a final reply follows its transient tools without reordering durable history", () => {
  const entries = [
    { id: "input", kind: "message", role: "user", runId: "old", sequence: 1 },
    { id: "saved-tool", kind: "tool", runId: "old", sequence: 2 },
    {
      id: "final", kind: "message", role: "assistant", status: "FINAL",
      runId: "old", sequence: 3,
    },
    { id: "next", kind: "message", role: "user", runId: "new", sequence: 4 },
    { id: "tool-1", kind: "tool", runId: "old", sequence: null },
    { id: "tool-2", kind: "tool", runId: "old", sequence: null },
    { id: "current-tool", kind: "tool", runId: "new", sequence: null },
  ];
  assert.deepEqual(orderTranscriptEntries(entries).map((entry) => entry.id), [
    "input",
    "saved-tool",
    "tool-1",
    "tool-2",
    "final",
    "next",
    "current-tool",
  ]);
});

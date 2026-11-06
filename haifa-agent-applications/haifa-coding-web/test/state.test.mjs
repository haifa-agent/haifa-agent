import test from "node:test";
import assert from "node:assert/strict";
import { WorkspaceState } from "../src/state.ts";
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

test("an exact consent preparation is not replayed when an observation reopens", () => {
  const state = new WorkspaceState();
  state.bindWorkspace("project");
  state.select("session-A");
  assert.equal(state.claimApprovalPreparation("session-A/run-A/approval/1"), true);
  state.select("session-B");
  state.select("session-A");
  assert.equal(state.claimApprovalPreparation("session-A/run-A/approval/1"), false);
  assert.equal(state.claimApprovalPreparation("session-A/run-A/approval/2"), true);
  state.bindWorkspace("project");
  assert.equal(state.claimApprovalPreparation("session-A/run-A/approval/1"), true);
});

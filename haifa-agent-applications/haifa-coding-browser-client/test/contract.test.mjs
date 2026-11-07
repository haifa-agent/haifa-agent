import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import {
  checkEnvelope,
  checkCommand,
  applyPresentation,
} from "../dist/index.js";
const canonical = new URL("../../haifa-coding-api/contract/", import.meta.url);
const json = async (name) =>
  JSON.parse(await readFile(new URL(name, canonical), "utf8"));

test("packaged browser validator consumes the canonical shared fixtures", async () => {
  for (const fixture of await json("fixtures/index.json"))
    assert.equal(
      checkEnvelope(await json("fixtures/" + fixture.file)),
      fixture.valid,
      fixture.file,
    );
  for (const fixture of await json("fixtures/commands.json"))
    assert.equal(checkCommand(fixture.command), fixture.valid);
});
test("atomic reduction rejects foreign scope, gaps and unknown critical content without changing authority", async () => {
  const initial = await json("fixtures/snapshot.json"),
    update = await json("fixtures/update.json");
  const next = applyPresentation(initial, update);
  assert.equal(next.revision, 1);
  assert.equal(applyPresentation(next, update), next);
  assert.ok(Object.isFrozen(next.entries));
  for (const invalid of [
    {
      ...update,
      baseRevision: 2,
      revision: 3,
      replacement: { ...update.replacement, revision: 3 },
    },
    {
      ...update,
      replacement: {
        ...update.replacement,
        scope: { ...update.replacement.scope, clientId: "foreign" },
      },
    },
    {
      ...update,
      replacement: {
        ...update.replacement,
        entries: [
          { ...update.replacement.entries[0], kind: "unfamiliar-critical" },
        ],
      },
    },
  ])
    assert.throws(() => applyPresentation(initial, invalid), {
      code: "PRESENTATION_RESYNC_REQUIRED",
    });
  assert.equal(initial.revision, 0);
});

test("unfamiliar critical review/input lanes stay unavailable", async () => {
  const { supportsInteraction } = await import("../dist/index.js");
  const source = (await json("fixtures/snapshot.json")).entries.find(
    (e) => e.interaction,
  ).interaction;
  const supported = {
    ...source,
    kind: "approval",
    input: { ...source.input, type: "none" },
    review: {
      purpose: "synthetic review",
      contentType: "File content",
      content: "synthetic only",
      environment: [],
      technical: [],
      risk: null,
      targetComplete: true,
      contentTruncated: false,
    },
  };
  assert.equal(supportsInteraction(supported), true);
  assert.equal(
    supportsInteraction({ ...supported, kind: "unknown-kind" }),
    false,
  );
  assert.equal(
    supportsInteraction({
      ...supported,
      input: { ...supported.input, type: "new-critical-input" },
    }),
    false,
  );
  assert.equal(
    supportsInteraction({
      ...supported,
      review: {
        ...supported.review,
        contentType: "unknown-executable-content",
      },
    }),
    false,
  );
});

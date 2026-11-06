import { readFileSync } from "node:fs";
import { Ajv2020 } from "ajv/dist/2020.js";
import type { Snapshot, Update } from "./generated.js";
import { createContract } from "./invariants.ts";
const root = new URL("./", import.meta.url);
const ajv = new Ajv2020({ strict: true, allErrors: true });
const validate = ajv.compile(JSON.parse(readFileSync(new URL("coding-presentation.schema.json", root), "utf8")));
const {checkEnvelope, checkCommand, apply} = createContract({Envelope: validate, Command: ajv.getSchema("https://haifa.agent/contracts/coding-presentation-v1#/$defs/Command")!});
const fixtures: Array<{file: string; valid: boolean}> = JSON.parse(readFileSync(new URL("fixtures/index.json", root), "utf8"));
for (const fixture of fixtures) {
  const value: unknown = JSON.parse(readFileSync(new URL("fixtures/" + fixture.file, root), "utf8"));
  if (checkEnvelope(value) !== fixture.valid) throw new Error("Fixture mismatch: " + fixture.file);
}
const commands: Array<{valid: boolean; command: unknown}> = JSON.parse(readFileSync(new URL("fixtures/commands.json", root), "utf8"));
for (const c of commands) if (checkCommand(c.command) !== c.valid) throw new Error("Command fixture mismatch");
const extras: Array<{schema: string; valid: boolean; value: unknown}> = JSON.parse(readFileSync(new URL("fixtures/extras.json", root), "utf8"));
for (const fixture of extras) {
  const checker = ajv.getSchema("https://haifa.agent/contracts/coding-presentation-v1#/$defs/" + fixture.schema)!;
  if (Boolean(checker(fixture.value)) !== fixture.valid) throw new Error("Extra fixture mismatch: " + fixture.schema);
}
const productFixtures: Array<{schema: string; valid: boolean; value: unknown}> = JSON.parse(readFileSync(new URL("fixtures/product.json", root), "utf8"));
for (const fixture of productFixtures) {
  const checker = ajv.getSchema("https://haifa.agent/contracts/coding-presentation-v1#/$defs/" + fixture.schema)!;
  let valid = Boolean(checker(fixture.value));
  if (valid && fixture.schema === "CommandRequest") valid = checkCommand((fixture.value as {command: unknown}).command);
  if (valid !== fixture.valid) throw new Error("Product fixture mismatch: " + fixture.schema);
}
const initial = JSON.parse(readFileSync(new URL("fixtures/snapshot.json", root), "utf8")) as Snapshot;
const update = JSON.parse(readFileSync(new URL("fixtures/update.json", root), "utf8")) as Update;
if (apply(initial, update).revision !== 1 || apply(update.replacement, update) !== update.replacement) throw new Error("Atomic reducer failed");
try { apply(initial, {...update, baseRevision: 2, revision: 3, replacement: {...update.replacement, revision: 3}}); throw new Error("gap accepted"); }
catch (e) { if (!(e instanceof Error) || e.message !== "VIEW_GAP_RESYNC") throw e; }
console.log(`Verified ${fixtures.length} shared fixtures, 3 command fixtures, ${productFixtures.length} product fixtures and atomic reducer behavior.`);

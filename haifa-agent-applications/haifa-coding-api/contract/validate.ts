import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { Ajv2020 } from "ajv/dist/2020.js";
import type { Snapshot, Update, CodingEnvelope, Command } from "./generated.js";
const root = new URL("./", import.meta.url);
const ajv = new Ajv2020({ strict: true, allErrors: true });
const validate = ajv.compile(JSON.parse(readFileSync(new URL("coding-presentation.schema.json", root), "utf8")));
export function checkEnvelope(value: unknown): value is CodingEnvelope {
  if (!validate(value)) return false;
  const item = value as CodingEnvelope;
  if (Buffer.byteLength(JSON.stringify(item), "utf8") > (item.kind === "snapshot" ? 262144 : 65536)) return false;
  if (item.kind === "snapshot" && !checkSnapshot(item)) return false;
  if (item.kind === "update" && !checkSnapshot(item.replacement)) return false;
  if (item.kind === "update" && (item.revision !== item.baseRevision + 1 || item.revision !== item.replacement.revision || item.viewId !== item.replacement.scope.viewId)) return false;
  return true;
}
function checkSnapshot(value: Snapshot): boolean {
  if (value.scope.sessionId !== value.session.id || new Set(value.entries.map(e => e.id)).size !== value.entries.length) return false;
  if (!value.history.hasEarlier && value.history.beforeSequence !== null) return false;
  if ((value.consent.state === "ASK") !== (value.consent.expiresAtEpochMillis === null)) return false;
  const actions = [...value.actions];
  for (const entry of value.entries) {
    if ((entry.kind === "interaction") !== (entry.interaction !== null)) return false;
    if (entry.interaction) for (const action of entry.interaction.actions) {
      if (action.target.sessionId !== value.scope.sessionId || action.target.runId !== entry.runId || action.target.interactionId !== entry.interaction.id || action.target.targetRevision !== entry.interaction.revision) return false;
      actions.push(action);
    }
  }
  for (const entry of value.entries) {
    if (entry.kind === "tool" && (!entry.toolCallId || !entry.runId)) return false;
    if (entry.interactionId) {
      const linked = value.entries.find(e => e.interaction?.id === entry.interactionId);
      if (entry.kind !== "tool" || !linked || linked.runId !== entry.runId) return false;
    }
  }
  for (const action of actions) {
    if (action.target.sessionId !== value.scope.sessionId || (action.available && action.reason !== null) || (!action.available && !action.reason)) return false;
    if (action.kind === "cancel" && !action.target.runId) return false;
    if (value.hydration === "refreshing" && action.available) return false;
  }
  return true;
}
const commandSchema = ajv.getSchema("https://haifa.agent/contracts/coding-presentation-v1#/$defs/Command")!;
function checkCommand(value: unknown): boolean {
  if (!commandSchema(value)) return false;
  const c = value as Command;
  if (!c.clientRequestId.trim()) return false;
  if (!["create", "resourcesReload", "workspaceRevoke", "logout"].includes(c.operation) && !c.target.sessionId?.trim()) return false;
  if (["cancel", "steer", "followUp", "respond", "allowForSession", "prepareApproval"].includes(c.operation) && !c.target.runId?.trim()) return false;
  if (["respond", "allowForSession", "prepareApproval"].includes(c.operation) && (!c.target.interactionId?.trim() || c.target.targetRevision === null)) return false;
  if (["rename", "archive", "delete", "restore", "selectModel"].includes(c.operation) && c.target.targetRevision === null) return false;
  if (["create", "submit", "steer", "followUp", "respond", "cancel", "selectModel", "allowForSession"].includes(c.operation) && !c.idempotencyKey?.trim()) return false;
  return true;
}
export function apply(current: Snapshot, update: Update): Snapshot {
  if (!checkEnvelope(update) || (["viewId", "hostIncarnation", "clientId", "sessionId", "schemaVersion", "source"] as const).some(k => current.scope[k] !== update.replacement.scope[k])) throw new Error("VIEW_SCOPE_MISMATCH");
  if (update.revision <= current.revision) return current;
  if (update.baseRevision !== current.revision) throw new Error("VIEW_GAP_RESYNC");
  return update.replacement;
}
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

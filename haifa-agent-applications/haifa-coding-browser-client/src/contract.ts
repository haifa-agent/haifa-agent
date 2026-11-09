import * as validators from "./generated/validators.js";
import { createContract } from "./generated/invariants.js";
import type {
  Snapshot,
  Update,
  CodingEnvelope,
  Command,
} from "./generated/protocol.js";
import { CodingClientError, fail } from "./errors.js";
const shared = createContract({
  Envelope: validators.Envelope,
  Command: validators.Command,
});
export const checkEnvelope: (value: unknown) => value is CodingEnvelope =
  shared.checkEnvelope;
export const checkCommand = shared.checkCommand;
export function utf8Bytes(value: unknown): number {
  try {
    const encoded = JSON.stringify(value);
    if (encoded === undefined) return fail("PROTOCOL_INCOMPATIBLE");
    return new TextEncoder().encode(encoded).length;
  } catch {
    throw new CodingClientError("PROTOCOL_INCOMPATIBLE");
  }
}
export function decode<T>(
  name: keyof typeof validators,
  value: unknown,
  maximum = 1048576,
): T {
  if (
    utf8Bytes(value) > maximum ||
    !(validators[name] as (value: unknown) => boolean)(value)
  )
    return fail("PROTOCOL_INCOMPATIBLE");
  return value as T;
}
export function freeze<T>(value: T): T {
  if (value && typeof value === "object" && !Object.isFrozen(value)) {
    Object.freeze(value);
    for (const child of Object.values(value)) freeze(child);
  }
  return value;
}
export function snapshot(value: unknown): Snapshot {
  if (!checkEnvelope(value) || value.kind !== "snapshot")
    return fail("PRESENTATION_INVALID");
  return freeze(structuredClone(value));
}
export function applyPresentation(current: Snapshot, update: Update): Snapshot {
  try {
    return freeze(shared.apply(current, update));
  } catch {
    throw new CodingClientError("PRESENTATION_RESYNC_REQUIRED");
  }
}
export function commandValid(value: Command): void {
  if (!checkCommand(value)) fail("COMMAND_INVALID");
}

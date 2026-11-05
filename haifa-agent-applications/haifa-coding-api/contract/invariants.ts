import type { Snapshot, Update, CodingEnvelope, Command } from "./generated.js";

/** Shared browser-safe cross-field rules, used by Node fixtures and the packaged SDK. */
export function createContract(
  guards: Readonly<{
    Envelope: (value: unknown) => boolean;
    Command: (value: unknown) => boolean;
  }>,
) {
  function checkEnvelope(value: unknown): value is CodingEnvelope {
    if (!guards.Envelope(value)) return false;
    const item = value as CodingEnvelope;
    if (
      new TextEncoder().encode(JSON.stringify(item)).length >
      (item.kind === "snapshot" ? 262144 : 65536)
    )
      return false;
    if (item.kind === "snapshot" && !checkSnapshot(item)) return false;
    if (item.kind === "update" && !checkSnapshot(item.replacement))
      return false;
    if (
      item.kind === "update" &&
      (item.revision !== item.baseRevision + 1 ||
        item.revision !== item.replacement.revision ||
        item.viewId !== item.replacement.scope.viewId)
    )
      return false;
    return true;
  }
  function checkSnapshot(value: Snapshot): boolean {
    if (
      value.scope.sessionId !== value.session.id ||
      new Set(value.entries.map((e) => e.id)).size !== value.entries.length
    )
      return false;
    if (!value.history.hasEarlier && value.history.beforeSequence !== null)
      return false;
    if (
      (value.consent.state === "ASK") !==
      (value.consent.expiresAtEpochMillis === null)
    )
      return false;
    const actions = [...value.actions];
    for (const entry of value.entries) {
      if ((entry.kind === "interaction") !== (entry.interaction !== null))
        return false;
      if (entry.interaction)
        for (const action of entry.interaction.actions) {
          if (
            action.target.sessionId !== value.scope.sessionId ||
            action.target.runId !== entry.runId ||
            action.target.interactionId !== entry.interaction.id ||
            action.target.targetRevision !== entry.interaction.revision
          )
            return false;
          actions.push(action);
        }
    }
    for (const entry of value.entries) {
      if (entry.kind === "tool" && (!entry.toolCallId || !entry.runId))
        return false;
      if (entry.interactionId) {
        const linked = value.entries.find(
          (e) => e.interaction?.id === entry.interactionId,
        );
        if (entry.kind !== "tool" || !linked || linked.runId !== entry.runId)
          return false;
      }
    }
    for (const action of actions) {
      if (
        action.target.sessionId !== value.scope.sessionId ||
        (action.available && action.reason !== null) ||
        (!action.available && !action.reason)
      )
        return false;
      if (action.kind === "cancel" && !action.target.runId) return false;
      if (value.hydration === "refreshing" && action.available) return false;
    }
    return true;
  }
  function checkCommand(value: unknown): boolean {
    if (!guards.Command(value)) return false;
    const c = value as Command;
    if (!c.clientRequestId.trim()) return false;
    if (
      !["create", "resourcesReload", "workspaceRevoke", "logout"].includes(
        c.operation,
      ) &&
      !c.target.sessionId?.trim()
    )
      return false;
    if (
      [
        "cancel",
        "steer",
        "followUp",
        "respond",
        "allowForSession",
        "prepareApproval",
      ].includes(c.operation) &&
      !c.target.runId?.trim()
    )
      return false;
    if (
      ["respond", "allowForSession", "prepareApproval"].includes(c.operation) &&
      (!c.target.interactionId?.trim() || c.target.targetRevision === null)
    )
      return false;
    if (
      ["rename", "archive", "delete", "restore", "selectModel"].includes(
        c.operation,
      ) &&
      c.target.targetRevision === null
    )
      return false;
    if (
      [
        "create",
        "submit",
        "steer",
        "followUp",
        "respond",
        "cancel",
        "selectModel",
        "allowForSession",
      ].includes(c.operation) &&
      !c.idempotencyKey?.trim()
    )
      return false;
    return true;
  }
  function apply(current: Snapshot, update: Update): Snapshot {
    if (
      !checkEnvelope(current) ||
      !checkEnvelope(update) ||
      (
        [
          "viewId",
          "hostIncarnation",
          "clientId",
          "sessionId",
          "schemaVersion",
          "source",
        ] as const
      ).some((k) => current.scope[k] !== update.replacement.scope[k])
    )
      throw new Error("VIEW_SCOPE_MISMATCH");
    if (update.revision <= current.revision) return current;
    if (update.baseRevision !== current.revision)
      throw new Error("VIEW_GAP_RESYNC");
    return update.replacement;
  }
  return { checkEnvelope, checkCommand, apply };
}

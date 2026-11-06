import type { Command, Receipt, RunFact } from "./generated/protocol.js";
import { freeze } from "./contract.js";
import { fail } from "./errors.js";
export type RequestState = Readonly<{
  requestId: string;
  operation: Command["operation"];
  sessionId: string | null;
  runId: string | null;
  disposition: "sending" | "accepted" | "rejected" | "unknown";
  reasonCode: string | null;
  relatedRun: RunFact | null;
}>;
/** Current-page correlation only. Neither a durable command store nor a second Run state machine. */
export class RequestTracker {
  readonly #requests = new Map<string, RequestState>();
  begin(command: Command): void {
    if (this.#requests.has(command.clientRequestId))
      fail("REQUEST_ALREADY_SENT");
    if (this.#requests.size >= 128) {
      const removable = [...this.#requests.values()].find(
        (r) => r.disposition === "accepted" || r.disposition === "rejected",
      );
      if (!removable) fail("REQUEST_TRACKING_LIMIT");
      this.#requests.delete(removable.requestId);
    }
    this.#requests.set(
      command.clientRequestId,
      freeze({
        requestId: command.clientRequestId,
        operation: command.operation,
        sessionId: command.target.sessionId,
        runId: command.target.runId,
        disposition: "sending",
        reasonCode: null,
        relatedRun: null,
      }),
    );
  }
  receipt(receipt: Receipt, sessionId: string | null): void {
    const previous = this.#requests.get(receipt.clientRequestId);
    if (!previous) fail("RECEIPT_REQUEST_MISMATCH");
    this.#requests.set(
      receipt.clientRequestId,
      freeze({
        ...previous,
        sessionId: sessionId ?? previous.sessionId,
        runId: receipt.runId ?? previous.runId,
        disposition: receipt.disposition,
        reasonCode: receipt.reasonCode,
      }),
    );
  }
  observeRun(requestId: string, fact: RunFact): void {
    const previous = this.#requests.get(requestId);
    if (!previous || previous.runId !== fact.id) fail("OUTCOME_SCOPE_MISMATCH");
    // A matching Run fact is informative. It never proves an unknown command was the cause.
    this.#requests.set(
      requestId,
      freeze({ ...previous, relatedRun: structuredClone(fact) }),
    );
  }
  list(): ReadonlyArray<RequestState> {
    return Object.freeze([...this.#requests.values()]);
  }
}

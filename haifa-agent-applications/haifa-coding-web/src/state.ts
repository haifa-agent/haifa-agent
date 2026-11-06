/** Local UI state, never authority or a durable send queue. All data disappears on page reload. */
export type Draft = Readonly<{
  text: string;
  selectionStart: number;
  selectionEnd: number;
  scrollTop: number;
  uncertain: boolean;
}>;
const empty = (): Draft => ({
  text: "",
  selectionStart: 0,
  selectionEnd: 0,
  scrollTop: 0,
  uncertain: false,
});
export class WorkspaceState {
  #drafts = new Map<string, Draft>();
  #preparedApprovals = new Set<string>();
  #selection: string | null = null;
  #generation = 0;
  #workspace: string | null = null;
  bindWorkspace(projectId: string): void {
    if (this.#workspace !== null && this.#workspace !== projectId)
      this.#drafts.clear();
    this.#workspace = projectId;
    this.#preparedApprovals.clear();
    this.#selection = null;
    this.#generation++;
  }

  get generation(): number {
    return this.#generation;
  }
  get selected(): string | null {
    return this.#selection;
  }
  select(session: string | null): number {
    this.#selection = session;
    return ++this.#generation;
  }
  current(generation: number, session: string | null): boolean {
    return generation === this.#generation && session === this.#selection;
  }
  get(session: string | null): Draft {
    return this.#drafts.get(session ?? "new") ?? empty();
  }
  update(session: string | null, patch: Partial<Draft>): void {
    const key = session ?? "new";
    if (!this.#drafts.has(key) && this.#drafts.size >= 64)
      throw Error("DRAFT_LIMIT");
    const value = { ...this.get(session), ...patch };
    if (value.text.length > 16384) throw Error("DRAFT_TOO_LARGE");
    this.#drafts.set(key, Object.freeze(value));
  }
  claimApprovalPreparation(exactTarget: string): boolean {
    if (
      this.#preparedApprovals.has(exactTarget) ||
      this.#preparedApprovals.size >= 128
    )
      return false;
    this.#preparedApprovals.add(exactTarget);
    return true;
  }
  accepted(session: string | null, sentText: string): void {
    if (this.get(session).text === sentText)
      this.update(session, { text: "", selectionStart: 0, selectionEnd: 0 });
  }
}

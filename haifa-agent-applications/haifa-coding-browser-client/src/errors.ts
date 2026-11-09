/** Safe codes only: no response body, prompt, proof, URL query or underlying cause in diagnostics. */
export class CodingClientError extends Error {
  constructor(
    public readonly code: string,
    public readonly outcomeUnknown = false,
  ) {
    super(code);
    this.name = "CodingClientError";
  }
}
export function fail(code: string): never {
  throw new CodingClientError(code);
}

# Delegation Tool Result Metadata

The Runtime-owned `task` Tool returns the authoritative terminal Child Run status in its structured Tool Result. Consumers must not infer child success from model text, from Tool invocation completion alone, or from the presence of a summary.

`ChildRunResults` preserves the existing model-visible Tool Result summary. The following additive fields are present only for a `COMPLETED` child when the Runtime's complete output is available:

| Field | Meaning |
| --- | --- |
| `outputPreview` | Display-only preview, at most 2,000 Unicode code points. Long output retains approximately two thirds at the head and one third at the tail, including a five-code-point `\n...\n` marker within that budget. |
| `outputSha256` | Lowercase hexadecimal SHA-256 of the complete output's UTF-8 bytes, computed before preview truncation. Java UTF-8 encoding replaces unpaired UTF-16 surrogates with `?`; the digest identifies those encoded bytes. |
| `outputTruncated` | Whether `outputPreview` was shortened or changed by public display projection; independent of the pre-existing `truncated` field for the Tool Result summary. |

The source is `RuntimeStateRepository.output(child.id())`, supplied by the existing delegation completion path. No additional Run, event, table, cache or output authority is created. There is no fallback from missing output to the run summary for the digest. An available empty output has the standard empty-input digest; missing output has no output metadata. Failed, cancelled and timed-out children do not acquire successful-output metadata.

The pre-existing `status`, `childRunId`, `childAgent`, `outcome`, `reasonCode`, `summary`, `usage`, `artifacts` and `truncated` semantics remain unchanged. Display readers consume these fields through the authorized, bounded and redacted public `ToolResultView.structuredData()` projection. The public projection sets `outputTruncated=true` if it further shortens or redacts the preview, and omits `outputSha256` when redaction changes output preview or result summary. Also check `ToolResultView.truncated()` and `structuredData().truncated()` before treating a preview as complete; size budgets may omit fields entirely. The digest is output identity, not a secrecy-preserving fingerprint, and must never verify a redacted preview. If fields are absent or redacted, their values are unknown; consumers must not manufacture them or hash a bounded display sample as though it were the full output.

`ModelMessageAssembler` excludes the three new display fields from the `task` Tool Result sent to the parent model. The authoritative result and public display projection retain them. Ordinary Tool Results keep their complete structured data. Internal context capacity estimates still count authoritative structured data, including display metadata; they are conservative estimates rather than exact counts of parent model input.

This change does not implement non-interactive child approvals, a child transcript API, step replay, a directory, or product-specific protocol names. Parent-child execution, queue admission, cancellation and recovery are unchanged.

Successful Runtime-owned `task` results retain the exact nonnegative integral `inputTokens`,
`outputTokens` and `cachedInputTokens` in public display projection, including explicit zero.
Other token-named fields remain redacted. Failed, cancelled and timed-out Children do not expose
partial counters as complete token usage; products must retain unknown rather than infer zero.
Model-visible result data remains unchanged.

# Error handling

Haifa Agent tries to expose stable error semantics without leaking provider payloads, credentials, prompts, or internal stack traces through public APIs.

## Synchronous versus Run failures

Failures before a Run is successfully accepted are API/command failures.

Failures during asynchronous execution become Run terminal state with a structured Agent error.

Callers should use stable error codes, category/retryability metadata, and diagnostic IDs rather than parsing English messages.

## Retryability

"Retryable" does not mean "always replay".

Runtime retry policies additionally consider whether a physical call can be safely repeated. Authentication errors, invalid requests, cancellation, context-too-long failures, and requests that already produced unsafe partial effects are not blindly replayed.

Side-effecting Tool calls with unknown outcome are never converted into ordinary automatic retries.

## Provider errors

Provider integrations normalize known protocol failures into bounded provider-neutral errors. Raw response bodies and secrets should not be returned to application clients or logs.

## Diagnostics

Use diagnostic IDs to correlate a safe public failure with trusted internal diagnostics.

Public diagnostics intentionally avoid:

- full prompts;
- model reasoning;
- Tool arguments/results unless explicitly safe and bounded;
- credentials;
- raw provider payloads;
- arbitrary exception messages containing user data.

# Conversations and Runs

The SDK Conversation API and the Runtime Run API solve different problems.

## Conversation

A Conversation is the product-facing multi-turn container. It uses the Core Session identity as its authoritative identity and keeps lightweight display/index metadata such as name, timestamps, and revision.

The Runtime remains authoritative for Sessions, Runs, Turns, and execution state.

A Conversation can contain multiple Runs over time, but a normal user interaction path has at most one active Run for that Conversation.

## Run

A Run is one execution of an Agent definition under a frozen configuration. Starting a new user turn normally creates a new Run; resuming an intentional pause continues the existing Run.

Typical lifecycle paths include:

~~~text
PENDING -> QUEUED -> RUNNING
RUNNING -> WAITING_INTERACTION -> RUNNING
RUNNING -> WAITING_APPROVAL -> RUNNING
RUNNING -> SUSPENDING -> SUSPENDED -> RUNNING
RUNNING -> COMPLETING -> COMPLETED
non-terminal -> FAILED | CANCELLED | TIMEOUT
~~~

The Core domain model is authoritative for legal transitions. Runtime coordinates those transitions but must not maintain an alternative lifecycle table.

## Asynchronous start

Runtime start accepts/persists the Run and returns before the work necessarily completes. Callers that want the terminal state must explicitly await or observe the Run.

Waiting in the client is not equivalent to cancelling the Run.

## Attempts

A Run can have physical execution Attempts. Intentional resume can create a new Attempt for the same logical Run. Abnormal loss of an executing owner is not transparently taken over: recovery settles the interrupted execution safely instead of guessing whether external side effects happened.

## Interactions

Clarification and approval use persistent Interaction state. Human waiting time is treated separately from active execution time so an operator does not accidentally exhaust the Run just by taking time to respond.

## Idempotency

Runtime start and Conversation write operations use caller-scoped idempotency and request digests. Reusing an idempotency key for a different request fails closed instead of silently returning unrelated work.

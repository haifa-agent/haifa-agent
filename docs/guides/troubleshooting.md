# Troubleshooting

## Starter says DEEPSEEK_API_KEY is not configured

The safe-default Starter requires the environment variable before build/create succeeds.

macOS/Linux:

~~~bash
export DEEPSEEK_API_KEY="<your-api-key>"
~~~

Windows PowerShell:

~~~powershell
$env:DEEPSEEK_API_KEY = '<your-api-key>'
~~~

Do not put the key into source code to work around this check.

## A Tool exists but the model cannot call it

Check all relevant layers:

1. the Tool is registered in the actual product assembly;
2. its alias/name is allowed by the ProductProfile/current frozen binding;
3. the selected model supports Tool Calling;
4. input schema and Tool definition are valid;
5. Policy/Approval permits the exact request.

MCP discovery alone does not guarantee that a discovered Tool becomes available to a Run.

## An approved command still fails

Approval only authorizes an exact request. Execution can still fail because of workspace authorization, missing executable, invalid cwd, timeout, host process error, resource limit, or cancellation.

## A command returned a non-zero exit code

A process exit code is part of the command result. Many developer tools use non-zero codes for meaningful outcomes.

Do not automatically classify every non-zero exit as a Runtime exception. Inspect bounded stdout/stderr and the command semantics.

## A Run did not resume after a crash

Unexpected loss of an actively executing owner is not transparent failover. Runtime settles abandoned execution safely.

Intentional pauses/Interactions have different continuation semantics. See [Persistence and recovery](../core-components/persistence-and-recovery.md).

## Browser or client cannot see provider details

This is generally intentional. Product APIs expose safe model metadata rather than credentials, raw endpoints, provider payloads, or internal snapshot digests.

## Host execution cannot access a path

Coding Agent and other host products enforce explicit workspace/authorized-directory boundaries. Attaching a directory for READ does not imply DEVELOP/execution authority.

## Need deeper module diagnostics

Use the adjacent module README and tests as the current implementation authority. Start from the module index in the repository root README.

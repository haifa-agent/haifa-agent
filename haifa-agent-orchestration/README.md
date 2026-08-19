# Haifa Agent Orchestration

Provider-neutral, pure Java Workflow/Graph definition and execution semantics above Runtime API.

```text
haifa-agent-orchestration-api <- haifa-agent-orchestration-core -> haifa-agent-runtime-api
```

The API is Incubating. This module does not contain a third-party Graph adapter; the optional M2 implementation lives
in `haifa-agent-integrations/haifa-agent-graph-langgraph4j` and depends inward on this boundary. There is still no
durable SQLite Workflow Store, product workflow, Spring integration, or automatic interception of SDK/Runtime requests.

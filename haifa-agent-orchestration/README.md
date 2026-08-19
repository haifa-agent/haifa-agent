# Haifa Agent Orchestration

Provider-neutral, pure Java Workflow/Graph definition and execution semantics above Runtime API.

```text
haifa-agent-orchestration-api <- haifa-agent-orchestration-core -> haifa-agent-runtime-api
```

The API is Incubating. This module does not contain a third-party Graph adapter; the optional M2 implementation lives
in `haifa-agent-integrations/haifa-agent-graph-langgraph4j` and depends inward on this boundary. M3 adds the
provider-neutral durable coordinator and persistence ports; `haifa-agent-store-sqlite` implements them with SQLite V8.
There is still no product workflow, Spring integration, or automatic interception of SDK/Runtime requests.

# Haifa Agent LangGraph4j Adapter

Pure Java M2/M5 Integration Adapter for `org.bsc.langgraph4j:langgraph4j-core:1.8.24`.

- Haifa `WorkflowDefinition` remains authoritative; Provider types stay inside this module.
- Supports sequence, explicit condition routing, bounded loops, fixed `ALL_OF`, wait/resume, cancellation, and the
  existing Haifa Action/Agent gateways.
- M5 maps statically frozen subgraphs to separate provider graph invocations while Haifa owns the parent/child Run,
  State mapping, wait/resume, cancellation, timeout, attempt, and event semantics. Non-interrupting subgraphs are also
  supported inside fixed branches.
- Fixed branches may complete in any physical order, but State merge, attempts, and event projection are committed by
  stable branch ordinal and Node ID.
- Provider `MemorySaver` is only an in-process continuation detail. It is not a Haifa durable store or a production
  recovery claim.
- Dynamic fan-out, `ANY_OF`, interrupting subgraphs inside fixed branches, Provider-native model/tool actions, Spring
  AI, Studio, and Provider database savers are not supported.

This module is not pulled into Starter, SDK, Coding Agent, Personal Assistant, or any existing product by default.

# Skills and MCP

Skills and MCP extend what an Agent can do, but neither one bypasses the Runtime capability and safety boundaries.

## Skills

A Skill is a reviewed instruction/resource package. Haifa Agent supports SKILL.md-based packages with frozen identity, metadata disclosure, activation, and controlled resource reads.

A Skill can help the model understand *how* to perform a task. It does not automatically grant:

- filesystem access;
- process execution;
- network access;
- credentials;
- approval bypass;
- arbitrary Tools.

The product still decides which Tool aliases are available to a Run.

## MCP

The MCP integration connects to external MCP servers, discovers Tools, reviews/maps their metadata and schemas, and imports approved entries into the local Tool catalog.

MCP is therefore a Tool source, not a second execution runtime.

Imported MCP Tools still use the same Runtime Tool Pipeline as local Java Tools.

## Transports and protocol versions

The current MCP integration supports the transports and protocol versions explicitly covered by the module implementation/tests. Do not infer support for a newer protocol feature merely because it exists in the MCP specification.

For precise transport/version behavior, use the adjacent [MCP module README](../../haifa-agent-integrations/haifa-agent-mcp/README.md).

## stdio

stdio MCP servers are hosted through the Execution Broker rather than by giving the model a raw Process handle. That keeps process lifetime, cancellation, output limits, and host execution rules in one boundary.

## Product guidance

Use MCP when an external server already owns useful Tool contracts or integrations. Use a native Java Tool when the capability is application-local and a direct typed integration is simpler.

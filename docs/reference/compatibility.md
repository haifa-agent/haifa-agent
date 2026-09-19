# Compatibility

## Java

Haifa Agent 0.1.1-SNAPSHOT targets Java 21.

## Build

The repository includes Maven Wrapper 3.9.15. Repository builds should prefer the wrapper so contributors run the expected Maven line.

## Spring

The 0.1.1 baseline uses Spring Boot 3.5.x in the Spring adapter/application layer. Pure Java Kernel, Runtime, capability APIs, and SDK do not require Spring.

## Operating systems

The project is developed for Windows, Linux, and macOS.

Host process behavior naturally depends on the local operating system and installed executables. The current host execution provider aims for a consistent product contract but does not claim OS-level sandbox parity.

## Model providers

Provider compatibility is binding-specific and can evolve faster than the SDK API.

Use the integration module README/tests for the precise currently reviewed provider/model/style combinations. Do not infer support solely from API similarity.

## MCP

MCP compatibility is protocol-version and transport specific. Use the [MCP module README](../../haifa-agent-integrations/haifa-agent-mcp/README.md) for the exact supported matrix.

## API stability

The project is still pre-1.0. Some convenience APIs, structured-output surfaces, provider bindings, and product APIs are not yet declared Stable and may change between minor development releases.

# Haifa Agent SDK Examples

Runnable pure Java examples used as the source of truth for the SDK website. The examples are
organized by learning depth:

| Package | Scope |
| --- | --- |
| `io.haifa.example.sdk.basic` | Real-provider Hello World, multi-turn Conversation, and Agent reuse/lifecycle |
| `io.haifa.example.sdk.intermediate` | Typed and multi-Tool loops, complex record schemas, Starter customization, and multi-model selection |
| `io.haifa.example.sdk.advanced` | Safe errors, Conversation management, Runtime observation/control, trusted host diagnostics, and SQLite reference assembly |

Every example is network-free unless its Javadoc explicitly opts into a real provider. Only
`basic.HelloHaifa` requires `DEEPSEEK_API_KEY`.

`SqliteDurableReferenceAssemblyExample` is a single-process durable reference assembly: applications
assemble the existing SQLite contributions and provide their own `HaifaAgent`. This application
example module is not a published SDK artifact, its classes are not Stable API, and application code
must not depend on the example classes. Haifa does not publish a provider-specific production Starter.

Complete external-consumer applications live under `examples/haifa-agent-example`; they do not
duplicate this teaching catalog or join the main Reactor.

Run the network-free examples and tests:

```bash
./mvnw -pl :haifa-agent-sdk-example -am test
```

Run the real-provider Basic example explicitly:

```bash
DEEPSEEK_API_KEY="<your-api-key>" ./mvnw -pl :haifa-agent-sdk-example -am \
  compile org.codehaus.mojo:exec-maven-plugin:3.5.1:java \
  -Dexec.mainClass=io.haifa.example.sdk.basic.HelloHaifa
```

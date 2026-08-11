# Haifa Agent SDK Examples

Runnable pure Java examples used as the source of truth for the SDK website. Every example uses a
deterministic local model unless its documentation explicitly opts into a real provider.

`SqliteDurableReferenceAssemblyExample` is a single-process durable reference assembly: applications
assemble the existing SQLite contributions and provide their own `HaifaAgent`. This application
example module is not a published SDK artifact, its classes are not Stable API, and application code
must not depend on the example classes. Haifa does not publish a provider-specific production Starter.

```bash
./mvnw -pl :haifa-agent-sdk-example -am test
```

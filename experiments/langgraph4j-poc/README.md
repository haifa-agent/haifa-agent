# LangGraph4j Contract PoC

This is the reproducible HAIFA-ARCH-012 M0.5 experiment. It is intentionally independent from the Haifa Maven
Reactor, parent POM, production BOMs, release profiles, and published artifacts.

It verifies LangGraph4j Core `1.8.24` against the frozen Provider-neutral fixture in
`docs/engineering/workflow-graph-contract-fixture.md`. The code is evidence for an adapter decision; production
modules must not depend on it.

Run from the repository root:

```bash
./mvnw -f experiments/langgraph4j-poc/pom.xml test
./mvnw -f experiments/langgraph4j-poc/pom.xml dependency:tree
```

The tests use a fake Agent gateway and never call a model, Tool, network service, or credential. Provider-native
checkpoint storage is used only to verify interruption/resume mechanics and is not a Haifa persistence design.

Supported by the PoC adapter boundary:

- sequence and conditional routing;
- bounded loop;
- fixed `ALL_OF` fan-out/fan-in;
- interruption and explicit resume;
- fake Agent node gateway;
- provider failure normalization;
- compile-time rejection of subgraphs, dynamic fan-out, and `ANY_OF`.

`target/` and all runtime output are disposable and must not be committed.

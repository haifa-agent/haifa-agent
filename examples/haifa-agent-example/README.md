# Haifa Agent Standalone Examples

This non-Reactor Maven build consumes installed Haifa Agent `0.1.0-SNAPSHOT` artifacts exactly as an
external application would. It is tracked by the main repository so that it cannot drift out of
view, but it deliberately does not inherit the main repository parent POM or join its Reactor.

The standalone build contains two complete consumer applications rather than a second catalog of
SDK teaching snippets:

- `pure-java-quickstart`: one pure Java lightweight Chat and typed Tool application;
- `spring-boot-quickstart`: one Spring Boot application using auto-configured `HaifaAgent` and Tool
  Bean discovery.

Detailed, deterministic SDK topics remain exclusively in
`haifa-agent-applications/haifa-agent-sdk-example`. Direct Runtime Core assembly remains exclusively
in `haifa-agent-applications/haifa-agent-runtime-demo`.

## Verify without network access

Install the public artifacts from the repository root, then build this independent project:

```bash
../../mvnw \
  -pl :haifa-agent-bom,:haifa-agent-spring-bom,:haifa-agent-sdk-starter,:haifa-agent-spring-boot-starter \
  -am -DskipTests install

./mvnw clean verify
```

The tests assemble both consumers without calling a model Provider.

## Run the applications

Both applications use the default DeepSeek model and require an explicit credential:

```bash
export DEEPSEEK_API_KEY="<your-api-key>"

./mvnw -pl :haifa-agent-pure-java-quickstart \
  -Dexec.mainClass=io.haifa.example.consumer.plain.PureJavaQuickstartApplication exec:java

./mvnw -pl :haifa-agent-spring-boot-quickstart spring-boot:run
```

These commands make real external calls and may incur cost. Thinking remains disabled. Never place
credentials in source, POM files, application properties, logs, or test reports.

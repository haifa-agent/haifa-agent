# Standalone Example Rules

This directory is tracked by the main `haifa-agent` Git repository but is an independent Maven
consumer build. All repository-level `AGENTS.md` rules continue to apply, with these additions:

- Do not add these modules to the main Reactor or inherit `haifa-agent-parent`.
- Consume Haifa modules only through BOM-managed artifact coordinates.
- Keep one cohesive pure Java application and one cohesive Spring Boot application; detailed SDK
  teaching topics belong in `haifa-agent-applications/haifa-agent-sdk-example`.
- Default tests must not call model Providers or require secrets.
- Run this build only after the matching Haifa artifacts have been installed locally.
- Do not commit `target/`, IDE state, credentials, logs, or host-specific paths.

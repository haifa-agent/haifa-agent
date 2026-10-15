# SDK Consumer Smoke

Standalone Maven, Gradle, and Spring Boot projects that consume installed Haifa artifacts without
joining the Reactor. The Maven consumer executes an offline Conversation -> Java Tool -> output
path; Spring Boot and Gradle consumers compile their public integration entry points. Run
`verify.sh` after publishing the SDK locally through the repository publication script. CI provisions
current Gradle. For a release bundle, run:

```bash
python3 build-support/sdk-consumer-smoke/verify.py \
  --repository /absolute/path/haifa-agent-sdk-v0.1.2/repository --version 0.1.2
```

The bundle route uses an empty temporary Maven local repository and the extracted file repository;
it also builds the standalone Java/Spring examples. Gradle resolves `io.haifa` exclusively from the
bundle. Third-party dependencies still require Maven Central access.

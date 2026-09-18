# SDK Consumer Smoke

Standalone Maven, Gradle, and Spring Boot projects that consume installed Haifa artifacts without
joining the Reactor. The Maven consumer executes an offline Conversation -> Java Tool -> output
path; Spring Boot and Gradle consumers compile their public integration entry points. Run
`verify.sh` after installing the SDK release artifacts locally. CI provisions current Gradle.

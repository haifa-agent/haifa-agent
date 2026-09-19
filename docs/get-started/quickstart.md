# Quickstart

The shortest path to a working Haifa Agent is the pure Java Starter.

The Starter currently uses DeepSeek V4 Flash, reads DEEPSEEK_API_KEY, disables Thinking for the built-in default model, and stores Runtime and Conversation state only in the current process. It does **not** implicitly enable files, Shell, Git, MCP, Web, Memory, Artifact, or Execution.

A real model call can incur provider charges.

## 1. Set the credential

macOS/Linux:

~~~bash
export DEEPSEEK_API_KEY="<your-api-key>"
~~~

Windows PowerShell:

~~~powershell
$env:DEEPSEEK_API_KEY = '<your-api-key>'
~~~

Do not put the API key in source code, prompts, checked-in YAML, or logs.

## 2. Create an Agent

~~~java
import io.haifa.agent.starter.HaifaAgentStarter;

public final class HelloHaifa {
    public static void main(String[] args) throws Exception {
        try (var agent = HaifaAgentStarter.create()) {
            System.out.println(
                    agent.chat("Introduce Haifa Agent in one sentence.")
                            .await()
                            .text());
        }
    }
}
~~~

HaifaAgent owns Runtime resources and should be closed by the host application.

## 3. Run the repository example

The repository contains the same path as an executable example.

macOS/Linux:

~~~bash
./mvnw -pl :haifa-agent-sdk-example -am compile org.codehaus.mojo:exec-maven-plugin:3.5.1:java -Dexec.mainClass=io.haifa.example.sdk.basic.HelloHaifa
~~~

Windows PowerShell:

~~~powershell
.\mvnw.cmd -pl :haifa-agent-sdk-example -am compile org.codehaus.mojo:exec-maven-plugin:3.5.1:java '-Dexec.mainClass=io.haifa.example.sdk.basic.HelloHaifa'
~~~

## What the Starter gives you

The default Starter intentionally optimizes for first use:

- a single process-local HaifaAgent;
- DeepSeek V4 Flash as the built-in model;
- environment-variable credential resolution;
- process-local Runtime and Conversation persistence;
- the standard approval policy preset;
- a default Agent name and fallback instructions.

Its state is lost when the process exits. Production applications should explicitly choose persistence, identity, credentials, policy, and capabilities rather than treating Starter defaults as a production profile.

## Next steps

- [Configuration](configuration.md)
- [Key concepts](key-concepts.md)
- [Java Tools](../advanced/tools.md)
- [Structured output](../advanced/structured-output.md)

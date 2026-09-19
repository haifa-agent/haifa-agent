# Model providers

Haifa Agent separates provider-neutral model contracts from provider protocol adapters.

A Run freezes the model snapshot and adapter coordinate it will use. Runtime does not silently move an in-flight Run to a different provider/model binding.

## Built-in Starter

The safe-default SDK Starter currently creates a DeepSeek V4 Flash binding:

- endpoint: https://api.deepseek.com
- credential: env://DEEPSEEK_API_KEY
- built-in snapshot Thinking: disabled
- process-local persistence

These are Starter defaults, not global restrictions on the model integrations.

## Explicit model registration

Trusted application code can register:

- a typed OpenAI-compatible model configuration; or
- an explicit AgentChatModel plus ResolvedModelSnapshot.

Registering custom models replaces the Starter's built-in model catalog. Multiple model IDs can be registered, and defaultModel(...) chooses the default.

## Provider integrations

The repository currently contains integrations for OpenAI-compatible protocols, Anthropic-style APIs, Google Gemini, and local-auth compatibility components.

OpenAI-compatible support includes reviewed dialects/bindings for several providers. Exact provider/model compatibility changes faster than the public architecture, so the module README and tests are the authority:

- [OpenAI-compatible integration](../../haifa-agent-integrations/haifa-agent-model-openai-compatible/README.md)
- [Anthropic integration](../../haifa-agent-integrations/haifa-agent-model-anthropic/README.md)
- [Google Gemini integration](../../haifa-agent-integrations/haifa-agent-google-gemini/README.md)

## No implicit fallback

Haifa Agent does not treat a provider catalog as a best-effort router. Unknown, unavailable, or mismatched bindings fail explicitly. Authentication failures do not automatically switch to a different model.

## Reasoning / continuation

Reasoning support is provider- and binding-specific. Protected provider continuation needed for Tool-call protocol correctness is not public reasoning output.

Public applications should rely on the normalized final answer, Tool Calls, usage, and safe Runtime lifecycle events rather than provider-private chain-of-thought data.

## Credentials

Model credentials are referenced indirectly and resolved at the adapter boundary. Do not place secret values in ProductProfile, Run inputs, prompts, logs, or public diagnostics.

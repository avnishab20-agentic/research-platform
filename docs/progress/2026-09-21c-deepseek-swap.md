# 2026-09-21 (part 3) — swapped Claude for DeepSeek in agent-service

Third entry today. Short one.

## Why

Real cost math: an Anthropic key with $5-10 of credit is enough for a clean
run, but this week involves iterating on the Researcher, Writer, and Critic
loops — meaning the same run gets re-triggered many times while a prompt or
a bug gets fixed. That adds up fast. DeepSeek's current model is roughly
20x cheaper per token than Claude Sonnet, so the same week of iteration
costs a fraction as much.

## Which model, and why

Asked to pick between DeepSeek V4.1 Flash and GLM 5.3 Flash (a newer,
cheaper model than the V4 Flash retired on 2026-09-11). Looked up real
numbers rather than guessing:

| | DeepSeek V4.1 Flash | GLM 5.3 Flash |
|---|---|---|
| Input / output price per M tokens | $0.04 / $0.16 | $0.075 / $0.25 |
| Speed | 214 tok/s, 1.17s to first token | 107 tok/s, 2.45s to first token |
| Extra capability | images | images + video |

DeepSeek is about **2x cheaper and 2x faster**. GLM's only real advantage
— video input — is irrelevant here; this project only ever reads text
articles and writes text reports. DeepSeek was the clear pick.

## How the swap works, mechanically

DeepSeek's API is **OpenAI-compatible** — it accepts the same request/
response shape as OpenAI's Chat Completions API, just at a different web
address (`https://api.deepseek.com`) with a different model name
(`deepseek-flash`). That means no DeepSeek-specific library was needed:
Spring AI ships an OpenAI client (`spring-ai-starter-model-openai`), and
pointing it at a different `base-url` in config is the entire integration.
This is a common trick — lots of "OpenAI-compatible" providers exist
specifically so tools built for OpenAI's API work against them unmodified.

**Changes made:**
- `agent-service/pom.xml`: removed `spring-ai-starter-model-anthropic`,
  added `spring-ai-starter-model-openai`.
- `agent-service/application.yml`: added the DeepSeek connection —
  ```yaml
  spring:
    ai:
      openai:
        api-key: ${DEEPSEEK_API_KEY}
        base-url: https://api.deepseek.com
        chat:
          options:
            model: deepseek-flash
  ```
  `${DEEPSEEK_API_KEY}` means "read this from an environment variable
  named `DEEPSEEK_API_KEY`" — same pattern as the Anthropic key would have
  used, nothing committed to the repo.

## A real bug this caused, and the actual fix

Having both `spring-ai-starter-model-openai` (for chat) and
`spring-ai-starter-model-transformers` (for local embeddings, unrelated
decision — see part 1's RAG entry) on the classpath at once broke the app:

```
NoUniqueBeanDefinitionException: No qualifying bean of type
'org.springframework.ai.embedding.EmbeddingModel' available:
expected single matching bean but found 2: openAiEmbeddingModel, embeddingModel
```

**What this means:** both starters automatically register their own
"here's how to produce embeddings" component. Spring found two candidates
for the same job and had no rule for which one to actually use, so it
refused to guess and failed loudly instead — which is the right failure
mode (a silent wrong guess would be worse).

First attempt at a fix — `spring.ai.openai.embedding.enabled: false` —
didn't work; that flag didn't stop the bean from being registered on this
version. The real fix is a newer, more direct mechanism Spring AI provides
exactly for this situation: a top-level selector that says which provider
wins for each *kind* of model, independent of which starters happen to be
on the classpath:

```yaml
spring:
  ai:
    model:
      chat: openai
      embedding: transformers
```

This reads as "for chat, only ever activate the openai-flavored
autoconfiguration; for embeddings, only ever activate the
transformers-flavored one" — deterministic, not a per-provider on/off
switch that has to be remembered and kept in sync everywhere. Test passed
clean after this.

## What's still true

`agent-service`'s full Spring context boots successfully **even without
`DEEPSEEK_API_KEY` set** — Spring AI doesn't validate the key at startup,
only when an actual chat call is made. That's convenient: it means
everything is now mechanically wired and proven correct, and the one
remaining step is purely "get the key, then the real calls will work,"
not "debug more plumbing."

## Docs updated

- `CLAUDE.md` — Stack line and the RAG architecture-decision paragraph
  updated to say DeepSeek; a new paragraph added laying out the swap, the
  reasoning, and — explicitly — the accepted risk: the Critic's grading
  quality is the one place this could hurt the thing `CLAUDE.md` calls
  "the point of the project." If the Week 4 fabrication-catch-rate eval
  comes back below the 0.85 target, this swap is the first thing to
  revisit, not the prompts.
- `docs/PLAN.md` — one deviation note near the top, pointing every
  "(Haiku)"/"(Sonnet)" annotation in the rest of the document at
  `CLAUDE.md`'s explanation, rather than rewriting each one individually.

## Next

Get `DEEPSEEK_API_KEY` set, then the actual researcher loop, RAG
chunk/embed/retrieve utility, planner, and fan-in counter can be built and
*tested* — not just compiled.

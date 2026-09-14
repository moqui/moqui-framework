# A2A interoperability checks

Black-box checks for the A2A 1.0 JSON-RPC binding (`POST /llm/a2a/jsonrpc`) and the public Agent Card
(`GET /.well-known/agent-card.json`) against a running Moqui instance. The Spock suites `A2ACoreTests` and
`A2AJsonRpcTests` cover the same behavior in-process; these files exercise the real HTTP, auth, and SSE path.

| File | Purpose |
| --- | --- |
| `moqui-a2a-collection.json` | Hoppscotch collection: discovery, auth, A2A error codes, SendMessage, SendStreamingMessage (SSE), ListTasks, GetTask, CancelTask, SubscribeToTask |
| `env-local.json` | Hoppscotch environment for `http://localhost:8080` with the demo user (`john.doe`) |
| `env-ngrok.json` | Hoppscotch environment template for a public tunnel; fill in `baseUrl`, `user`, `password` |
| `a2a-smoke.sh` | The same checks with curl: `./a2a-smoke.sh [baseUrl] [user:password]` |
| `ngrok-a2a-policy.yml` | ngrok traffic policy that exposes only `/llm/a2a/*` and the Agent Card; everything else is 404 |
| `interop_a2a_sdk.py` | interoperability check driven by the official `a2a-sdk` client (discovery, SendMessage, GetTask, ListTasks, CancelTask, streaming, extended card) |

A2A is off by default (`a2a_enabled=false`), so start the instance with it enabled, for example
`a2a_enabled=true ./gradlew run`; otherwise both endpoints answer 404. The user needs the `LlmGateway` permission
(ADMIN has it in the demo data). Every JSON-RPC request needs the header `A2A-Version: 1.0`. `SendMessage` completes only when the `a2a_default_profile` LLM profile (`assist` by
default) has a model configured (`llm_openai_url`, `llm_openai_model`, `llm_openai_api_key`); otherwise the task ends
in `TASK_STATE_FAILED`, which the collection accepts.

## Local run

```
a2a_enabled=true ./gradlew run
npx @hoppscotch/cli test framework/test/a2a/moqui-a2a-collection.json -e framework/test/a2a/env-local.json
```

With `a2a_enabled` unset or false, `GET /.well-known/agent-card.json` and `POST /llm/a2a/jsonrpc` both answer 404.

## Against the official SDK client

```
pip install a2a-sdk
python framework/test/a2a/interop_a2a_sdk.py http://localhost:8080 john.doe:moqui
```

The script drives the reference client: it resolves the Agent Card, picks the JSON-RPC interface the card
advertises, and exercises SendMessage, GetTask, ListTasks, CancelTask, streaming and the extended card. The task
completes only when the LLM profile has a model, so point `llm_openai_url`/`llm_openai_model` at a provider (a
local OpenAI-compatible mock is enough).

## Through a public tunnel

```
ngrok http 8080 --traffic-policy-file framework/test/a2a/ngrok-a2a-policy.yml
```

Set `baseUrl` in `env-ngrok.json` to the tunnel URL, and `user`/`password` to a dedicated account rather than the demo
user. Forwarded headers are not trusted by default, so set `a2a_public_url` to the tunnel URL (or
`a2a_trust_forwarded_headers=true` when the proxy in front is trusted); otherwise the Agent Card keeps advertising the
local URL. To run the collection from hoppscotch.io, use the Hoppscotch browser extension or desktop app (CORS). Close
the tunnel when done.

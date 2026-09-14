#!/usr/bin/env bash
# A2A 1.0 JSON-RPC smoke test against a running Moqui (default http://localhost:8080).
# Usage: a2a-smoke.sh [baseUrl] [user:password]
BASE="${1:-http://localhost:8080}"
AUTH="${2:-john.doe:moqui}"
RPC="$BASE/llm/a2a/jsonrpc"
H=(-H 'Content-Type: application/json' -H 'A2A-Version: 1.0' -u "$AUTH")

step() { printf '\n=== %s\n' "$1"; }

if [ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/.well-known/agent-card.json")" = "404" ]; then
  printf 'Agent Card returns 404: A2A is disabled. Start Moqui with a2a_enabled=true.\n' >&2
fi

step "1. Public Agent Card (no auth)"
curl -s -i "$BASE/.well-known/agent-card.json" | sed -n '1p;/^[Ee][Tt]ag/p;/^[Cc]ache-[Cc]ontrol/p'
curl -s "$BASE/.well-known/agent-card.json"; echo

step "2. No credentials -> HTTP 401 from LlmAuthFilter"
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/json' -H 'A2A-Version: 1.0' \
  -d '{"jsonrpc":"2.0","id":1,"method":"GetTask","params":{"id":"x"}}' "$RPC"

step "3. Missing A2A-Version -> -32009 at HTTP 200"
curl -s -w '  [HTTP %{http_code}]\n' -X POST -H 'Content-Type: application/json' -u "$AUTH" \
  -d '{"jsonrpc":"2.0","id":2,"method":"GetTask","params":{"id":"x"}}' "$RPC"

step "4. Legacy 0.3 method name -> -32601"
curl -s -w '  [HTTP %{http_code}]\n' -X POST "${H[@]}" \
  -d '{"jsonrpc":"2.0","id":3,"method":"message/send","params":{}}' "$RPC"

step "5. Batch -> -32600, malformed JSON -> -32700"
curl -s -w '  [HTTP %{http_code}]\n' -X POST "${H[@]}" -d '[{"jsonrpc":"2.0","id":4,"method":"GetTask"}]' "$RPC"
curl -s -w '  [HTTP %{http_code}]\n' -X POST "${H[@]}" -d '{"jsonrpc":' "$RPC"

step "6. Unknown task -> -32001 TaskNotFound"
curl -s -w '  [HTTP %{http_code}]\n' -X POST "${H[@]}" \
  -d '{"jsonrpc":"2.0","id":5,"method":"GetTask","params":{"id":"no-such-task"}}' "$RPC"

step "7. Push config -> -32003 PushNotificationNotSupported"
curl -s -w '  [HTTP %{http_code}]\n' -X POST "${H[@]}" \
  -d '{"jsonrpc":"2.0","id":6,"method":"CreateTaskPushNotificationConfig","params":{"taskId":"x","url":"https://example.invalid/hook"}}' "$RPC"

step "8. GetExtendedAgentCard (authenticated)"
curl -s -X POST "${H[@]}" -d '{"jsonrpc":"2.0","id":7,"method":"GetExtendedAgentCard","params":{}}' "$RPC" | head -c 600; echo

step "9. SendMessage (uses the a2a_default_profile LLM)"
MID="smoke-$(date +%s)"
curl -s -w '  [HTTP %{http_code}]\n' --max-time 120 -X POST "${H[@]}" \
  -d "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"SendMessage\",\"params\":{\"message\":{\"messageId\":\"$MID\",\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"Hello Moqui Agent\"}]}}}" "$RPC" | head -c 1500; echo

step "10. SendStreamingMessage over SSE on the same endpoint"
curl -s -N -i --max-time 120 -X POST "${H[@]}" -H 'Accept: text/event-stream' \
  -d "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"SendStreamingMessage\",\"params\":{\"message\":{\"messageId\":\"$MID-s\",\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"Stream please\"}]}}}" "$RPC" | cut -c1-400

step "11. ListTasks"
curl -s -X POST "${H[@]}" -d '{"jsonrpc":"2.0","id":10,"method":"ListTasks","params":{"pageSize":5,"historyLength":0}}' "$RPC" | head -c 800; echo

# ec.ai — AI/LLM Facade for Moqui

`ec.ai` is Moqui's built-in facade for calling AI language model providers
(OpenAI and OpenAI-compatible APIs). It follows the same lifecycle and
configuration pattern as `ec.elastic` — a singleton initialized by
`ExecutionContextFactory` and available on every `ExecutionContext` thread.
Any Groovy service, screen action, or scheduled job can call an LLM with:

```groovy
String reply = ec.ai.getDefault().generate(messages)
```

---

## Configuration

### MoquiConf.xml

Override any of the six properties in your `MoquiConf.xml`:

```xml
<default-property name="ai_provider"  value="openai"/>
<default-property name="ai_model"     value="gpt-4o-mini"/>
<default-property name="ai_base_url"  value=""/>
<default-property name="ai_api_key"   value="sk-..."/>
<default-property name="ai_timeout"   value="60"/>
<default-property name="ai_pool_max"  value="10"/>
```

| Property       | Description                                                    | Default   |
|----------------|----------------------------------------------------------------|-----------|
| `ai_provider`  | Provider name (currently `openai` or compatible)               | _(empty)_ |
| `ai_model`     | Model identifier (e.g. `gpt-4o-mini`, `gpt-4o`)               | _(empty)_ |
| `ai_base_url`  | Base URL override for compatible APIs; omit for OpenAI default | _(empty)_ |
| `ai_api_key`   | API key — marked `is-secret="true"`, never logged              | _(empty)_ |
| `ai_timeout`   | Request timeout in seconds                                     | `60`      |
| `ai_pool_max`  | Max HTTP connections (reserved for future use)                 | `10`      |

### Environment variables

Each property can alternatively be supplied as a system property or
environment variable. Moqui resolves `${ai_api_key}` by checking system
properties and the environment at startup, so:

```bash
export ai_api_key=sk-...
export ai_provider=openai
export ai_model=gpt-4o-mini
```

---

## Multiple providers

To configure more than one model, override `<ai-facade>` in `MoquiConf.xml`
with multiple `<model-config>` children:

```xml
<ai-facade default-config="fast">
    <model-config name="fast"
                  provider="openai"
                  model="gpt-4o-mini"
                  api-key="${ai_api_key}"
                  timeout="30"
                  pool-max="10"/>
    <model-config name="powerful"
                  provider="openai"
                  model="gpt-4o"
                  api-key="${ai_api_key}"
                  timeout="120"
                  pool-max="5"/>
</ai-facade>
```

Access a named config:

```groovy
String response = ec.ai.getConfig("powerful").generate(messages)
```

---

## Usage

### generate()

Send a list of chat messages; receive the model's reply as a plain `String`.

```groovy
List messages = [
    [role: "system", content: "You are a concise assistant."],
    [role: "user",   content: "Summarize in one sentence: ${text}"]
]
String response = ec.ai.getDefault().generate(messages)
```

Each message map requires:
- `role` — `"system"`, `"user"`, or `"assistant"`
- `content` — the message text as a `String`

### generateStructured()

Send messages and receive a `Map` parsed from a structured JSON response
conforming to a schema you define.

```groovy
List messages = [
    [role: "user", content: "Extract order details from: ${orderText}"]
]
Map schema = [
    status:    [type: "string"],
    amount:    [type: "number"],
    quantity:  [type: "integer"],
    approved:  [type: "boolean"],
    lineItems: [type: "array", items: [
        productId: [type: "string"],
        qty:       [type: "integer"]
    ]]
]
Map result = ec.ai.getDefault().generateStructured(messages, schema)
String status   = result.status
BigDecimal amt  = result.amount as BigDecimal
```

---

## Schema reference

The `schema` parameter uses JSON Schema vocabulary. Each entry maps a field
name to a type-definition map.

| Type      | Description              | Additional keys                                     |
|-----------|--------------------------|-----------------------------------------------------|
| `string`  | Plain text               | —                                                   |
| `number`  | Decimal number           | —                                                   |
| `integer` | Whole number             | —                                                   |
| `boolean` | `true` or `false`        | —                                                   |
| `array`   | List of structured items | `items` — map of field definitions for each element |
| `object`  | Nested object            | `properties` — map of field definitions             |

Scalar example:
```groovy
[status: [type: "string"], count: [type: "integer"]]
```

Nested array example:
```groovy
[lineItems: [type: "array", items: [
    productId: [type: "string"],
    qty:       [type: "integer"]
]]]
```

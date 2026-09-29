
<h3 align="center">Stringer</h3>

<p align="center">
  <strong>An AI agent runtime middleware for the Java ecosystem.<br>Add one starter: inject StringerAgent to call AI, annotate a method with @Tool to let AI call you. Orchestration, tool governance, knowledge base and the ops console all live in the server.</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/version-v1.0--beta.1-blue?style=flat-square" alt="version">
  <img src="https://img.shields.io/badge/license-Apache--2.0-yellow?style=flat-square" alt="license">
  <img src="https://img.shields.io/badge/Java-21-orange?style=flat-square&logo=openjdk&logoColor=white" alt="java">
  <img src="https://img.shields.io/badge/Spring%20Boot-3.5.7-6DB33F?style=flat-square&logo=springboot&logoColor=white" alt="spring-boot">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/LangChain4j-1.18.1-7B68EE?style=flat-square" alt="langchain4j">
  <img src="https://img.shields.io/badge/LangGraph4j-1.8.17-008080?style=flat-square" alt="langgraph4j">
  <img src="https://img.shields.io/badge/Elasticsearch-9.x-005571?style=flat-square&logo=elasticsearch&logoColor=white" alt="elasticsearch">
  <img src="https://img.shields.io/badge/Redis-6%2B-DC382D?style=flat-square&logo=redis&logoColor=white" alt="redis">
</p>

<p align="center">
  <a href="#why-choose-stringer">Capabilities</a>
  &nbsp;·&nbsp;
  <a href="#how-it-compares">Comparison</a>
  &nbsp;·&nbsp;
  <a href="#quick-start">Quick start</a>
  &nbsp;·&nbsp;
  <a href="#documentation">Documentation</a>
</p>

---

## Why choose stringer?

| Capability | What it actually gives you |
| --- | --- |
| **State-graph orchestration** | Every step is explicit (`agent → conditional routing → tools/review → agent`), with transparent state, interrupt and resume |
| **Human-in-the-loop (HITL)** | Tools that declare an approval policy pause before execution; the interrupt point is persisted in Redis and **survives a server restart** |
| **Profile visibility** | Each turn declares its profile; the model only sees that profile's tools and prompt. An unknown profile is an error — **never a silent fallback to every tool** |
| **Remote tool registry** | Tool instances report their full declaration on a heartbeat; the server keeps per-instance replicas. Instances that drop off are removed automatically, reconnecting ones are restored, and several instances of the same tool can be online at once |
| **Instance availability control** | Mute (fuse) / restore / force-offline a single instance from the console: muting removes every tool replica that instance reports, without touching the process itself; a force-offlined instance gets a 410 and stops heartbeating |
| **Hybrid retrieval** | Vector and keyword search run in parallel and are fused after normalization; chunks are split on Chinese section boundaries and carry source metadata |
| **Knowledge upload and rebuild** | Upload documents straight from the console (default `md` / `txt`, extension whitelist configurable), inspect ingestion status and rebuild the whole index; a missing ES IK analyzer is detected by the "test connection" on the Storage page (three states: available / confirmed missing / not probed) |
| **Dual-constraint memory** | Caps both message count and estimated tokens, stored per session |
| **Streaming and cancellation** | Events are pushed frame by frame; a running turn can be stopped at any time |
| **Built-in console** | 8 pages: overview, models, storage, domains, instances, prompts, knowledge base, account |
| **Runtime metrics snapshot** | `GET /admin/metrics` returns registered tool count, running sessions, heap usage and core metric snapshots — ready for your existing monitoring collector (admin surface, credential required) |
| **Starts with zero configuration** | ES / Redis / models can all be missing at startup; missing configuration is reported **at call time** with a pointer to the exact console page |

## How it compares

| Dimension | Stringer | Dify / FastGPT | Spring AI / LangChain4j |
| --- | --- | --- | --- |
| Form factor | **Standalone server + thin starter** | Standalone platform (container-deployed) | Library, inside your process |
| Stack | Java 21 / Spring Boot | Mostly Python | Java |
| How you write a tool | Your existing Spring bean: annotate a method with `@Tool` | Configure it in the platform / plugin marketplace | Write code and wire the routing yourself |
| Where tools run | **Inside your process**, reusing your transactions, permissions and `@Service`s | In the platform process, called over HTTP across systems | Inside your process |
| Business code change | Inject `StringerAgent` to call the agent — none | Separate process, integrate over REST / iframe | Orchestration and state code lands in your business project |
| Orchestration & state | Graph orchestration + Redis checkpoints, **resumable across instances** | Visual workflows | Build it yourself |
| Tool governance | **Profile visibility + approval interrupts + a multi-instance registry** | Plugins / tool marketplace | None built in; build it yourself |
| Ops console | Built-in, 8 pages, plus a runtime metrics snapshot | Its own visual UI | None |
| Storage | Namespace-isolated — **you can share one ES / Redis with your app** | Separate storage | Depends on your implementation |
| Deployment cost | One extra server to run (ES / Redis can be shared with your app) | The platform plus its own dependencies | Nothing extra |
| Best fit | Existing Java apps — monolith or microservices — with human approval and operability required | No-code drag-and-drop app building | Calling a model API a handful of times |

## Who it's for / Who it isn't

**For you if**: you already run a Java application — a monolith or a set of distributed services — and do not want a second stack for AI; whether your tools live in a single process or are scattered across services, they need one place to register and govern them; you have on-prem, data-residency or compliance constraints; high-risk operations (refunds, order edits, broadcasts) need human approval; ops needs to know which tools and instances are live.

**Not for you if**: you want visual no-code building; you are a pure Python shop; or you need a one-off script that calls a model once.

## Deployment shape: one JAR, every platform

The Stringer server is a **platform-neutral single-file fat JAR**: one build artifact runs directly with `java -jar` on Linux, Windows and macOS (and other Unix-like systems) — no per-target rebuild.

- **OS detection at startup**: `RuntimeEnvironment` reads `System.getProperty("os.name")` before Spring wires up, picks the config and log directories for the detected OS family, and creates them up front.
  - Linux / other Unix: `/var/lib/stringer/config`, `/var/log/stringer`
  - Windows: `%ProgramData%\Stringer\config`, `%ProgramData%\Stringer\logs`
  - macOS: `/Library/Application Support/Stringer/config`, `/Library/Logs/Stringer`
- **Override precedence**: CLI `--key` ＞ JVM system property `-Dkey` ＞ env var `STRINGER_SETTINGS_PATH` / `STRINGER_LOG_PATH` ＞ platform default.
- **Containers**: for Docker / K8s just swap the base image (e.g. `eclipse-temurin:21-jre`); the JAR stays the same. The container runtime (kubernetes / docker / podman) is shown on the startup banner for easier troubleshooting.

## Quick start

### Prerequisites

- JDK 21+, Maven 3.8+
- Elasticsearch 9.x (verified; 8.x works; older versions are unverified) — with the IK analyzer plugin installed
- Redis 6+
- An OpenAI-compatible model service (a chat model and an embedding model)

> ES / Redis **can be shared with your existing application**: data is separated by private namespaces (Redis keys use the `stringer:` prefix, ES indices the `stringer_` prefix) and each side opens its own connection. Dedicated instances work too.

Any **OpenAI-compatible endpoint** will do — a hosted API and a local deployment are treated the same, and **a local Ollama works directly**

| Field on the "Models" page | What to enter for a local Ollama |
| --- | --- |
| Chat · base URL | `http://localhost:11434/v1` (**the `/v1` is required**) |
| Chat · API key | Any non-empty value, e.g. `ollama` (Ollama does not check it, but this system requires the field) |
| Chat · model name | `qwen2.5:7b`, `llama3.1:8b` and other local models; with the right base URL the dropdown lists them |
| Embedding · model name | `nomic-embed-text`, `bge-m3`, … |
| Embedding · dimensions | **Leave empty**: Ollama's `/v1/embeddings` does not accept OpenAI's `dimensions` parameter, so it would be ignored |

Two things to keep in mind:

- **The chat model must support tool calling (function calling)** (e.g. `qwen2.5`, `llama3.1`). Without it the agent can still chat, but it will never call the tools you registered.
- Switching embedding models changes the vector dimension, and the ES index dimension is fixed at index-creation time — rebuild the index from the "Knowledge base" page. When the server runs in a container, `localhost` means the container itself, so use the host address instead.

### Step 1: Start the server

```bash
mvn -pl stringer-server -am install
mvn -pl stringer-server spring-boot:run     # port 9527 by default
```

**No configuration file is required up front.** The server starts even with nothing configured (it only skips the actions that need a dependency instead of refusing to boot). Then open the console and fill things in:

```
http://localhost:9527/admin.html      # default account stringer / stringer
```

Enter the chat and embedding models under "Models", and the ES / Redis connections under "Storage" (both pages have a "test connection" button so you can verify on the spot).

> Moving this configuration out of yaml and into the console is deliberate: connection details and secrets no longer travel with source code or images, and you can still fix a broken connection because the console does not depend on it. Settings are persisted to `config/*.json`, outside the artifact.

### Step 2: Integrate into your application

```xml
<dependency>
    <groupId>com.zzkingcc</groupId>
    <artifactId>stringer-agent-client</artifactId>
    <version>v1.0-beta.1</version>
</dependency>
```

> **One dependency is enough.** `stringer-agent-client` brings three things at once: calling the agent (`StringerAgent`, obtained via `StringerAgentFactory.forDomain(...)`), handing your own methods to the agent as tools (tool instance SDK, **off by default** — set `stringer.tool-instance.enabled` to turn it on), and the shared exception / input-sanitization support. No web container is included — your existing Spring MVC or WebFlux stack simply stays as it is. Tool-provider-only deployments (tool microservices, non-Java apps) can depend on `stringer-tool-provider` alone. See [instance doc §1.1](docs/INSTANCE.md#11-一个依赖跑起来).

```yaml
stringer:
  server:                       # one address and one account, shared by client and tool instance
    host: localhost
    port: 9527
    username: stringer          # keep in sync if the server password changes
    password: stringer
```

> **The server must start first** (same habit as integrating Redis or Nacos). Before startup completes, an app that uses the starter exchanges a signed credential and probes the server health; if it cannot connect, or the credentials are wrong, **startup is aborted** with troubleshooting hints — there is no switch to turn this off: letting an app start before the middleware means serving in a state that is guaranteed to be broken.
>
> Credentials do not expire. On the normal path the login happens once; if the server password changed, the client logs in once more automatically, and aborts startup with an explanation if that also fails.

### Step 3: Inject a bean, ask a question

The SDK exposes exactly one entry point, `StringerAgent`: bind a domain first, then call.

```java
@Service
public class MyService {
    private final StringerAgent agent;                // bound to the "customer" domain, reusable

    public MyService(StringerAgentFactory factory) {  // auto-configured by the starter; no annotation needed
        this.agent = factory.forDomain("customer");
    }

    /** Just the final answer (~70% of cases): TOKEN deltas are concatenated internally */
    public String ask(String sessionId, String question) {
        return agent.ask(sessionId, question);
    }

    /** Token-by-token output */
    public Flux<String> stream(String sessionId, String question) {
        return agent.stream(sessionId, question);
    }

    /** The full event stream (tool calls / approval interrupts / error codes) */
    public Flux<AgentEvent> events(String sessionId, String question) {
        return agent.events(sessionId, question);
    }
}
```

When human approval is required, `ask` / `stream` throw `ApprovalRequiredException` carrying the pending tool calls; once the user confirms, continue with `agent.resume(sessionId, true)`.

The **domain** decides which tools the model can see and which prompt it receives: `forDomain(null)` / blank falls back to the `default` domain, and the same domain always yields the same instance. None of the three methods takes a domain argument, so "forgetting to pass the domain" is unwritable.

### Step 4: Annotate a method, turn it into a tool

Set `stringer.tool-instance.enabled=true`, then declare on any Spring bean method:

```java
// Read-only: visible in the customer domain; the parameter schema is derived from the signature
@Tool(desc = "Look up an order by number. Call when the user asks about shipping or logistics",
        value = "queryOrder", domains = {"customer"})
public String queryOrder(@ToolParam("Order number, e.g. FR2024001") String orderNo) { ... }

// Write: side effect declared + interrupts for human approval before every call
@Tool(desc = "Refund an order. Call only when the user explicitly asks for a refund",
        value = "refundOrder", domains = {"admin"},
        effect = Tool.Effect.WRITE,
        approval = Tool.Approval.ALWAYS, approvalReason = "Refunds need human sign-off")
public String refundOrder(@ToolParam("Order number") String orderNo,
                          @ToolParam("Refund amount, in CNY") BigDecimal amount) { ... }
```

The signature is the parameter schema, the annotation is the governance policy, the body is the implementation — all three in one place. When the tool list has to be assembled dynamically at startup, register programmatically with `ToolInstanceContributor` instead (programmatic wins on name conflicts) — see [instance doc §4.4](docs/INSTANCE.md#44-声明工具编程式工具清单要在启动期动态拼装时用).

> A domain has **three sources**: created manually in the ops console (deletable, persisted to `config/domains.json`), derived from a tool declaration (writing `domains` creates it), and the built-in fallback domain **`default`** (tools with no declaration and calls with no domain land here; it cannot be deleted). An empty declaration means **`default` only**; to be usable in every domain you must write `{"*"}` explicitly.
>
> It is also a **caller-declared, platform-trusted** governance mechanism (it keeps the model from misusing tools and keeps prompts aligned with the visible tool set), **not a security boundary**: the client picks the domain and the platform cannot verify it. End-user identity and authorization remain the host's own IAM.

### Demo

`stringer-example` demo module **has been removed** (beta is a breaking change; the demo will be rewritten later). See `SDK-USAGE.md` / `INSTANCE.md` for integration.

```bash
mvn -pl stringer-example spring-boot:run
```

Open `http://localhost:8080/test.html` to walk the full path (including an approval interrupt → resume).

## Architecture

```
Business system (starter added, `StringerAgent` injected)
   │  HTTP + SSE
   ▼
stringer-server
   ├─ graph orchestration · tool registry
   ├─ knowledge base · chat memory
   ├─ ES · Redis · model service
   └─ console http://localhost:9527/admin.html
   ▲
   │  register + heartbeat
Tool provider (tool-provider SDK, or your own HTTP implementation)
```

## Modules

| Module | Description |
| --- | --- |
| `stringer-api` | Contracts: `StringerAgent` / annotations / events / tool descriptors / error codes |
| `stringer-common` | Common support: exceptions / input security |
| `stringer-domain` | Domain capabilities: knowledge retrieval / hybrid search with score fusion / memory policy |
| `stringer-infrastructure` | Infrastructure: ES retrieval and index management / document ingestion and splitting / Redis / embedding |
| `stringer-runtime` | Agent runtime core: graph orchestration / tool registry and routing / instance registry / streaming / prompts |
| `stringer-server` | **Server**: standalone deployable, hosts all heavy logic and the console |
| `stringer-agent-client` | **Consumer-side single coordinate**: remote calls + tool instance SDK + shared exceptions and input security |
| `stringer-tool-provider` | **Tool instance SDK**: registration and heartbeat keep-alive plus the invocation endpoint; depends only on the contract module `stringer-api`, no internal implementation (delivered transitively by the starter) |

## API

Business systems call through `StringerAgent` and never hand-write HTTP; when you do need raw HTTP, these are the ones that matter:

| Method | Path | Description |
| --- | --- | --- |
| POST | `/api/agent/login` | Exchange credentials for a signed credential (auth-exempt) |
| GET | `/api/agent/health` | Health probe |
| POST | `/api/agent/chat` | Start a turn; returns an SSE event stream |
| POST | `/api/agent/resume` | Resume a session interrupted by an approval |
| POST | `/api/agent/stop/{sessionId}` | Stop a running task |
| POST | `/api/agent/tools/register` | Tool instance registration and heartbeat |

The admin surface used by the console (`/admin/*`: settings, models, profiles, instance mute and offline, knowledge upload and rebuild, metrics snapshot, account) is not listed above. Full endpoints, the SSE event contract, the error code table and SDK usage are in the [API documentation](docs/API.md).

## Documentation

- [Design](docs/DESIGN.md) — form factor and modules, profiles and tool visibility, the tool system, storage and model configuration, concurrency, configuration reference
- [API](docs/API.md) — all HTTP endpoints, SSE event contract, error code table, starter and tool instance SDK
- [Instance](docs/INSTANCE.md) — configuration and integration walkthrough: server config, client starter integration, tool instance SDK, local Bean tools, the profile mechanism, end-to-end run

## License

[Apache-2.0](LICENSE)

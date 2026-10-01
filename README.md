
<h3 align="center">Stringer</h3>

<p align="center">
  <strong>Java 生态的 AI Agent 运行时中间件。<br>引一个 starter：注入 StringerAgent 就能调 AI，方法上加 @Tool 就能让 AI 调你。编排、工具治理、知识库、管控台都在服务端。</strong>
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
  <a href="#为什么选择-stringer">能力一览</a>
  &nbsp;·&nbsp;
  <a href="#与主流方案相比">方案对比</a>
  &nbsp;·&nbsp;
  <a href="#快速开始">快速开始</a>
  &nbsp;·&nbsp;
  <a href="#文档">文档</a>
</p>

---

## 为什么选择 stringer？

| 能力 | 具体到能做什么 |
| --- | --- |
| **图编排状态机** | 每一步显式可控（`agent → 条件路由 → tools/review → agent`），状态透明、可中断、可恢复 |
| **人工审批（HITL）** | 工具声明审批策略后，调用前自动中断等待确认；中断点落 Redis，**服务端重启后仍可恢复** |
| **域（profile）可见性** | 一次对话必须声明所处的域，模型只能看到该域的工具、只能拿到该域的提示词；域不存在直接报错，**绝不静默降级成全量工具** |
| **远程工具注册中心** | 工具实例周期整包上报声明，服务端按实例维护副本；实例掉线自动摘除、重连自动恢复，同名工具多实例可同时在线 |
| **实例可用性管控** | 在线实例页可对单个实例**静音（熔断）/ 恢复 / 强制下线**：静音即摘除该实例上报的全部工具副本，不必去改动它所在的进程；强制下线的实例会收到 410 并停止心跳 |
| **混合检索（双轨融合）** | 向量与关键词两路并行召回后融合重排。单索引走**分数制**（归一化加权，带标题 / 文件名 boost）；沿父域链取到**多索引**时自动切到 **RRF 排名制**——BM25 的分跨索引不可比，只有看名次才不失真 |
| **一域一索引的知识库** | 每个域一个独立 ES 索引（按需创建），检索域 D 只查「D + 全部祖先」链上的索引，与工具可见性同一套累加语义；删域即删索引。管控台按域上传（默认 `md` / `txt`，白名单可配）、查看导入状态、整库重建；ES 缺 IK 分词器会在「存储配置」的测试连接里判读出来（可用 / 确认未安装 / 未探测三态） |
| **双约束会话记忆** | 同时约束消息条数与 Token 估算，按会话隔离存储 |
| **流式输出与中断** | 事件流逐帧下发；任务可随时停止 |
| **内置管控台** | 8 页：概览、模型设置、存储配置、域空间、在线实例、提示词设定、知识库、账号 |
| **运行指标快照** | `GET /admin/metrics` 返回注册工具数、运行中会话数、堆内存与内核指标快照，可接进现有监控采集（管理面，需凭证） |
| **零配置可启动** | 未填 ES / Redis / 模型也能启动，缺配置只在**调用时**报明确错误并指向该去哪一页填 |

## 与主流方案相比

| 维度 | Stringer                              | Dify / FastGPT | Spring AI / LangChain4j |
| --- |---------------------------------------| --- | --- |
| 形态 | **独立服务端 + 薄 starter**                 | 独立平台（容器部署） | 库，随业务进程 |
| 技术栈 | Java 21 / Spring Boot                 | Python 为主 | Java |
| 工具怎么写 | 你现有的 Spring Bean：方法上加 `@Tool` | 平台内配置 / 插件市场 | 写代码，自己接路由 |
| 工具在哪跑 | **你的进程内**，复用事务、权限与 `@Service`         | 平台进程，跨系统 HTTP 调用 | 你的进程内 |
| 业务代码改动 | 注入 `StringerAgent` 调 AI 即可，零改动         | 另起进程，走 REST / iframe | 编排与状态代码写进业务工程 |
| 编排与状态 | 图编排 + Redis 检查点，**中断后可跨实例恢复**         | 可视化工作流 | 需自行实现 |
| 工具治理 | **域可见性 + 审批中断 + 多实例注册中心**             | 插件 / 工具市场 | 无内置治理，需自行实现 |
| 运维界面 | 内置 8 页管控台 + 运行指标快照                    | 自有可视化界面 | 无 |
| 存储 | 命名空间隔离，**可与业务共用同一套 ES / Redis**       | 独立存储 | 取决于业务侧实现 |
| 部署成本 | 需多跑一个服务端（ES / Redis 可与业务复用）           | 需独立平台及其依赖 | 无额外部署 |
| 适合 | 已有 Java 应用（单体或分布式微服务均可），需人工审批与可运维      | 无代码拖拽搭应用 | 只调几次模型 API |

## 适合谁 / 不适合谁

**适合**：已有 Java 应用（单体或分布式微服务均可）且不想为 AI 另起一套技术栈；工具无论集中在一个进程里，还是分散在多个服务中，都需要统一注册与治理；有 on-prem、数据不出域或信创要求；需要人工审批介入高风险操作（退款、改单、群发）；需要知道"现在有哪些工具、哪些实例在线"。

**不适合**：以可视化拖拽为主的低代码搭建；纯 Python 技术栈；单机脚本级的一次性调用。

## 部署形态：一个 jar，跑遍各端

Stringer 服务端是**平台无关的单文件 fat jar**：一份构建产物，在 Linux、Windows、 macOS（含其他 Unix 类）上直接 `java -jar` 即可运行，无需为目标系统重新构建。

- **启动期自动探测运行系统**：`RuntimeEnvironment` 在 Spring 装配前用 `System.getProperty("os.name")` 判定 OS 族，自动选定配置 / 日志 / 切片导出目录并提前建好。
  - Linux / 其他 Unix：`/var/lib/stringer/config`、`/var/log/stringer`、`/var/lib/stringer/chunks`
  - Windows：`%ProgramData%\Stringer\config`、`%ProgramData%\Stringer\logs`、`%ProgramData%\Stringer\chunks`
  - macOS：`/Library/Application Support/Stringer/config`、`/Library/Logs/Stringer`、`/Library/Application Support/Stringer/chunks`
- **覆盖优先级**：命令行 `--key` ＞ JVM 系统属性 `-Dkey` ＞ 环境变量 `STRINGER_SETTINGS_PATH` / `STRINGER_LOG_PATH` / `STRINGER_EXPORT_PATH` ＞ 平台默认。
- **容器部署**：Docker / K8s 只需换对应基底镜像（如 `eclipse-temurin:21-jre`），jar 不变；容器运行时（kubernetes / docker / podman）会在启动横幅中显示，便于排障。

## 快速开始

### 环境要求

- JDK 21+、Maven 3.8+
- Elasticsearch 9.x（已验证；8.x 可用；更低版本需自行验证）—— 需安装 IK 分词器插件
- Redis 6+
- 一个 OpenAI 兼容的模型服务（对话模型 + 向量模型）

> ES / Redis **可与业务系统共用同一套实例**：数据层通过私有命名空间隔离（Redis key 统一 `stringer:` 前缀、ES 索引统一 `stringer_` 前缀），双方各自建立独立连接，互不影响。

模型服务只要是 **OpenAI 兼容端点**就行，公有云与本地部署没有区别——**本机 Ollama 直接可用**

| 「模型设置」页字段 | 本机 Ollama 的填法 |
| --- | --- |
| 对话 · 服务商地址 | `http://localhost:11434/v1`（**必须带 `/v1`**） |
| 对话 · API Key | 任意非空值，如 `ollama`（Ollama 不校验，但本系统要求该字段非空） |
| 对话 · 模型名 | `qwen2.5:7b`、`llama3.1:8b` 等本地已有模型；地址填对后下拉可直接拉到列表 |
| 向量 · 模型名 | `nomic-embed-text`、`bge-m3` 等 |
| 向量 · 维度 | **留空**：Ollama 的 `/v1/embeddings` 不接受 OpenAI 的 `dimensions` 参数，填了会被忽略 |

两点提醒：

- **对话模型需支持工具调用（function calling）**（如 `qwen2.5`、`llama3.1`）。不支持时 Agent 仍能对话，但不会调用你注册的工具。
- 换了向量模型、维度随之一变时，ES 索引的维度是建索引时定死的，需在「知识库」页**重建索引**；服务端跑在容器里时 `localhost` 指容器自身，要写宿主地址。

### 第一步：启动服务端

```bash
mvn -pl stringer-server -am install
mvn -pl stringer-server spring-boot:run     # 默认端口 9527
```

**不需要预先准备配置文件。** 服务端在未配置状态下即可启动（只跳过需要依赖的动作，不阻断启动），启动后打开管控台填写：

```
http://localhost:9527/admin.html      # 默认账号 stringer / stringer
```

依次在「模型设置」填对话模型与向量模型、「存储配置」填 ES 与 Redis（每页都有"测试连接"，填完可当场验证）。

> 配置从 yaml 搬到管控台是刻意的：连接信息与密钥不随源码、镜像分发，且在依赖不可用时仍能进调控台改回来。落盘位置 `config/*.json`，与源码分离。

### 第二步：业务系统接入

```xml
<dependency>
    <groupId>com.zzkingcc</groupId>
    <artifactId>stringer-agent-client</artifactId>
    <version>v1.0-beta.1</version>
</dependency>
```

> **一个依赖就够。** `stringer-agent-client` 同时带来三件事：调 AI（`StringerAgent`，用 `StringerAgentFactory.forDomain(...)` 取）、把本进程的方法作为工具交给 Agent（工具实例 SDK，**默认关闭**，需要时打开 `stringer.tool-instance.enabled`）、公共异常与输入安全。Web 容器不在其中——宿主原有的 Spring MVC / WebFlux 栈保持不变即可。只想当工具方（工具微服务、非 Java 应用）可只引 `stringer-tool-provider`。详见[实例文档 §1.1](docs/INSTANCE.md#11-一个依赖跑起来)。

```yaml
stringer:
  server:                       # 客户端与工具实例共用这一份地址与账号
    host: localhost
    port: 9527
    username: stringer          # 服务端改过密码后需同步
    password: stringer
```

> **服务端必须先启动**（与 Redis / Nacos 的接入习惯一致）。引入 starter 的应用在启动完成前会换取签名凭证并探测服务端健康状态，连不上或账号密码错误会**直接中断启动**并给出排查提示——不提供关闭开关：允许应用先于中间件启动，等于让它在必然不可用的状态下对外服务。
>
> 凭证不设有效期，正常路径下登录只发生一次；服务端改过密码后客户端会自动重登一次，仍失败则中断启动并提示原因。

### 第三步：注入一个 Bean，问一句话

SDK 只提供一个入口 `StringerAgent`：**先绑域，再调用**。

```java
@Service
public class MyService {
    private final StringerAgent agent;                // 已绑定 customer 域，可缓存复用

    public MyService(StringerAgentFactory factory) {  // starter 自动装配，不需要任何注解
        this.agent = factory.forDomain("customer");
    }

    /** 只要最终答案（大多数场景）：内部把 TOKEN 增量拼成整段文本 */
    public String ask(String sessionId, String question) {
        return agent.ask(sessionId, question);
    }

    /** 要逐字输出 */
    public Flux<String> stream(String sessionId, String question) {
        return agent.stream(sessionId, question);
    }

    /** 要完整事件流（工具调用 / 审批中断 / 错误码） */
    public Flux<AgentEvent> events(String sessionId, String question) {
        return agent.events(sessionId, question);
    }
}
```

命中人工审批时 `ask` / `stream` 抛 `ApprovalRequiredException`（带待审批工具清单），用户确认后 `agent.resume(sessionId, true)` 继续。

**域**决定模型能看到哪些工具、用哪份提示词：`forDomain(null)` / 空白 → 兜底域 `default`，同一域永远拿到同一个门面。三个方法的签名里都**没有域参数**，所以"忘传域"写不出来。

### 第四步：方法上加个注解，把业务方法变成工具

打开 `stringer.tool-instance.enabled=true`，然后在任意 Spring Bean 的方法上声明：

```java
// 只读工具：客服域可见，参数 schema 由方法签名推导
@Tool(desc = "按订单号查询订单状态。用户追问发货/物流时调用",
        value = "queryOrder", domains = {"customer"})
public String queryOrder(@ToolParam("订单号，如 FR2024001") String orderNo) { ... }

// 写操作：声明副作用等级 + 调用前中断等人工确认
@Tool(desc = "按订单号退款。仅在用户明确要求退款时调用",
        value = "refundOrder", domains = {"admin"},
        effect = Tool.Effect.WRITE,
        approval = Tool.Approval.ALWAYS, approvalReason = "退款会真实出金，需人工确认")
public String refundOrder(@ToolParam("订单号") String orderNo,
                          @ToolParam("退款金额，单位：元，须 ≤ 订单实付") BigDecimal amount) { ... }
```

方法签名即参数 schema、注解即治理策略、方法体即执行逻辑——三件事写在同一个地方。更多注解与对话 SDK 的完整示例（参数 DTO、`@ToolDomains`、`@ToolAdvanced`、审批恢复、SSE 裸调）见 [SDK 使用手册](docs/SDK-USAGE.md)。

工具清单需要在启动期动态拼装时，改用 `ToolInstanceContributor` 编程式注册（重名以编程式为准），见[实例文档 §4.4](docs/INSTANCE.md#44-声明工具编程式工具清单要在启动期动态拼装时用)。

> 域有**三个来源**：管控台**人工创建**（可删，落盘 `config/domains.json`）、**工具声明派生**（写下 `domains` 即创建）、以及内置兜底域 **`default`**（工具声明留空、调用未指定域都落到它，不可删）。声明留空＝**只属于 `default`**；要全域可用须显式写 `{"*"}`。
>
> 它也是**调用方自行声明、平台信任**的治理机制（防止模型误用工具、防止提示词与工具集错位），**不是安全边界**——用哪个域由客户端决定，平台无法验证真伪。终端用户的身份与授权属于宿主自己的 IAM。

### 联调示例

`stringer-example` 示例模块**已移除**（测试版破坏性改造，例子后期重写）。接入方式见 `SDK-USAGE.md` / `INSTANCE.md`。

## 架构

```
业务系统（引入 starter，注入 StringerAgent）
   │  HTTP + SSE
   ▼
stringer-server
   ├─ 图编排 · 工具注册中心
   ├─ 知识库 · 会话记忆
   ├─ ES · Redis · 模型服务
   └─ 管控台 http://localhost:9527/admin.html
   ▲
   │  注册 + 心跳
工具提供方（tool-provider SDK，或按 HTTP 协议自实现）
```

## 模块结构

| 模块 | 说明 |
| --- | --- |
| `stringer-api` | 对外契约：`StringerAgent` / 注解 / 事件 / 工具描述符 / 错误码 |
| `stringer-common` | 公共支撑：异常体系 / 输入安全 |
| `stringer-domain` | 领域能力：知识检索 / 混合检索与融合排序 / 记忆策略 |
| `stringer-infrastructure` | 基础设施：ES 检索与索引管理 / 文档摄取切片 / Redis / 向量化 |
| `stringer-runtime` | Agent 运行时内核：图编排 / 工具注册表与路由 / 实例注册表 / 流式 / 提示词 |
| `stringer-server` | **服务端**：可独立部署，承载全部重逻辑与管控台 |
| `stringer-agent-client` | **消费侧唯一坐标**：`StringerAgent`（`forDomain` → `ask`/`stream`/`events`/`resume`/`stop`）+ 工具实例 SDK + 公共异常与输入安全 |
| `stringer-tool-provider` | **工具实例 SDK**：注册与心跳保活 + 工具调用端点，只依赖契约层 `stringer-api`，不含内部实现（随 starter 传递） |

## 接口

业务系统通过 `StringerAgent` 调用，无需拼 HTTP；需要裸 HTTP 时看这几个：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/agent/login` | 账号密码换签名凭证（免鉴权） |
| GET | `/api/agent/health` | 健康探测 |
| POST | `/api/agent/chat` | 发起对话，SSE 返回事件流 |
| POST | `/api/agent/resume` | 恢复因审批而中断的会话 |
| POST | `/api/agent/stop/{sessionId}` | 停止执行中的任务 |
| POST | `/api/agent/tools/register` | 工具实例注册与心跳 |

管控台调用的管理面（`/admin/*`：设置、模型、域空间、实例静音与下线、知识库上传与重建、指标快照、账号）不在上表内，完整端点、SSE 事件契约、错误码总表、SDK 用法见 [API 文档](docs/API.md)。

## 文档

- [设计文档](docs/DESIGN.md) —— 形态与模块、域与工具可见性、工具体系、存储与模型配置、并发模型、配置项总表
- [API 文档](docs/API.md) —— 全部 HTTP 端点、SSE 事件契约、错误码总表、starter 与工具实例 SDK
- [实例文档](docs/INSTANCE.md) —— 配置与接入实操：服务端配置、客户端 starter 接入、工具实例 SDK、本地 Bean 工具、域机制、端到端跑通
- [SDK 使用手册](docs/SDK-USAGE.md) —— 注解与对话 SDK 的可复制示例：最小工具、`@ToolParam`/`@ToolDomains`/`@ToolAdvanced`、审批恢复、事件流、SSE 裸调
- [TXT 入库清洗与切片设计](docs/TXT-INGESTION-DESIGN.md) —— 编码探测、页眉页脚识别与清洗、结构推断、预算打包、切片元数据契约、质量验证（设计稿，待落地）

## License

[Apache-2.0](LICENSE)

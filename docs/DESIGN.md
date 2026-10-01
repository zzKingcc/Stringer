# Stringer 设计文档

本文件描述 Stringer 的静态设计：形态、模块、组件职责、数据模型、状态机、约束与配置。所有接口签名见 `API.md`，配置与接入实操见 `INSTANCE.md`。

---

## 1 系统定位与交付形态

Stringer 是面向 **AI Agent 编排与工具治理** 的中间件，交付形态为三件套：

| 交付物 | 模块 | 部署位置 |
| --- | --- | --- |
| 服务端（独立进程） | `stringer-server` | 客户自部署，端口 `9527`（`server.port` / `STRINGER_SERVER_PORT`） |
| 消费侧 SDK | `stringer-agent-client` | 引入调用方业务应用，提供 `StringerAgentFactory`（唯一入口：`forDomain` → `ask`/`stream`/`events`/`resume`/`stop`）与 `KnowledgeBaseClient` Bean，并传递 `sdk-core` 与工具实例 SDK（工具能力默认关闭） |
| 工具实例 SDK | `stringer-tool-provider` | 引入工具提供方应用，把本地方法注册到服务端；也可单独引入（只当工具方、不调 AI） |
| 共享契约层 | `stringer-sdk-core` | 被 `agent-client` 与 `tool-provider` 共同依赖，承载 `ServerProperties`（`stringer.server.*`）等共用配置 |

形态约束：

- 服务端不提供开箱业务 Controller，只暴露 `AgentService` 契约；消费侧对应 **`StringerAgent` 门面**（`forDomain(...)` 时绑定域，之后 `ask`/`stream`/`events`/`resume`/`stop` 都不带域参数），租户 / 用户随各方法显式传入。裸 HTTP 入口仍接受 `profile`。
- 服务端 **不内置任何业务知识文档**；文档由部署方通过上传接口导入。
- 服务端不做 SaaS：**单实例部署**——工具注册表在进程内存、知识库导入为进程内串行锁、判死扫描为进程内定时器，因此多实例不成立；扩容只能纵向。
- 停机为**优雅停机**（`server.shutdown=graceful`，等待上限 30s）：先停止接收新请求，在途请求（含 SSE 长连接）收尾后再退出。
- 工具可来自两个位置——调方进程内的本地 Bean，或独立进程的工具实例（通过 HTTP 注册）。

---

## 2 模块划分

| 模块 | 职责 | 主要包 |
| --- | --- | --- |
| `stringer-api` | 对外契约：错误码、注解、`ToolDescriptor`、`AgentRequest`/`CallerContext`/`AgentEvent`、`TraceId`、`AgentService`（`StringerAgent` / `StringerAgentFactory` / `ApprovalRequiredException`）、`Domains`、`ModelResolver` | `api.code` `api.annotation` `api.tool` `api.agent` `api.support` |
| `stringer-common` | 异常基类与通用工具 | `common.exception` `common.util` |
| `stringer-domain` | 领域能力：知识检索、混合检索与融合排序、会话记忆约束、域注册表 | `domain.capability.knowledge` `domain.rag` `domain.memory` `domain` |
| `stringer-infrastructure` | 外部依赖适配：ES 检索器与索引管理、文档摄取与切片、Redis 记忆与检查点、向量化 | `infrastructure.elasticsearch` `infrastructure.ingestion` `infrastructure.redis` `infrastructure.embedding` |
| `stringer-runtime` | 运行时内核：编排图、工具注册表与路由、实例注册表、流式上下文、提示词解析、取消、模型解析 | `runtime.graph` `runtime.tool` `runtime.stream` `runtime.prompt` `runtime.cancellation` `runtime.orchestration` `runtime.model` `runtime.domain` |
| `stringer-server` | 服务端：配置装配、管控接口、鉴权、设置存储、异常处理出口、静态管控台、模型档案与域管理 | `server.config` `server.controller` `server.auth` `server.settings` `server.knowledge` `server.advice` `server.prompt` `server.model` |
| `stringer-sdk-core` | 共享契约层：被两个 SDK 共同依赖，承载 `ServerProperties`（`stringer.server.*`）、`ClientProperties` 等共用配置 | `sdkcore` |
| `stringer-agent-client` | 消费侧 SDK：凭证管理、`AgentServiceClient`（内部通道）、**唯一入口** `StringerAgent`（`DefaultStringerAgentFactory` / `DefaultStringerAgent`）、`KnowledgeBaseClient`、启动连通性探测 | `agentclient` |
| `stringer-tool-provider` | 工具实例 SDK：注解扫描、注册与心跳、反向调用端点 | `toolprovider` |
| ~~`stringer-example`~~ | **已移除**（例子后期重写） | — |

依赖方向：`api → common → domain → infrastructure → runtime → server`；`sdk-core` 依赖 `api`+`common`；`agent-client` 与 `tool-provider` 都依赖 `sdk-core`（两者互不依赖，可单独或同时引入）；`tool-provider` 不依赖任何其它 Stringer 模块（与服务端只通过 HTTP 报文耦合）。

消费侧依赖边界：`stringer-agent-client` 是接入坐标，聚合 `stringer-api`（契约）、`stringer-common`（异常与输入安全）、`stringer-sdk-core`（共用配置）与 `stringer-tool-provider`（工具实例 SDK），引入即同时具备「调 AI」与「提供工具」两种能力；工具能力默认关闭——`tool-provider` 的自动装配整体受 `stringer.tool-instance.enabled=true` 约束，未开启时不注册回调端点、不启动心跳。聚合的依赖成本为零：`common`/`sdk-core` 只依赖 `api`，`tool-provider` 的依赖（`spring-web` / `spring-boot-autoconfigure` / `jackson-databind` / `slf4j-api`）全部已在 agent-client 既有依赖树内。`stringer-tool-provider` 仍保留独立坐标供纯工具方（工具微服务、非 Java 应用）使用，其「不依赖任何 Stringer 模块」的契约不变。**Web 容器始终归宿主**：agent-client 与 `tool-provider` 都只用 `spring-web` 的注解模型，不引容器；宿主已有 Servlet 栈时两者共存仍判定为 SERVLET，若把容器写进 SDK，纯 WebFlux 宿主会被判成 SERVLET 而失去 `DispatcherHandler` 装配。

---

## 3 运行时调用链路

```
调用方（业务应用，含 starter）
  │  POST /api/agent/chat    Header: X-Stringer-Credential
  ▼
ServerAgentController ──► AgentOrchestrationService ──► agentExecutor 线程池
                                    │
                                    ▼
                        LangGraph4j 编排图（检查点存 Redis）
                        ┌─────────────┐
                        │  agentNode  │ 注入 SystemMessage + 本轮可见工具集
                        └──────┬──────┘
                               │ 有工具调用
                        ┌──────▼──────┐        ┌──────────────┐
                        │  toolsNode  │───────►│  ToolRouter   │  实时读注册表
                        └──────┬──────┘        └──────┬───────┘
                               │                      │ 本地 Bean / 远程实例
                               │ 命中审批策略          ▼
                        ┌──────▼──────┐        POST {endpoint}
                        │ reviewNode  │        （工具实例）
                        └─────────────┘
                               │ 中断
                               ▼
                 SSE 事件流（TOKEN / TOOL_CALL / TOOL_RESULT / INTERRUPT / STOPPED / ERROR / DONE）
```

- 会话身份：`sessionId` 为唯一键；`profile` 是 per-request 参数，会话不绑定域。
- 每个执行单元（一次 `orchestrate` 及其全部 `resume`）内，`profile` 与提示词冻结；单元之间取最新值。
- 记忆、检查点、流式上下文三者相互独立：记忆存 Redis（`stringer:chat:memory:*`），检查点存 Redis（`graph:checkpoint:*`），事件流只走 HTTP 响应。

---

## 4 域（Domain）与工具可见性

域是一棵**树**，标识是**从根域出发的完整路径**（点分，如 `default.sales.order`）。主键与展示同形，
因此不存在"同名不同父"的歧义。根域是 `default`，即基层域。

| 项 | 规定 |
| --- | --- |
| 定义 | 域＝一次对话的场景，同时绑定【工具集 + 系统提示词 + 知识范围 + 模型】 |
| 结构与路径 | 标识必须是完整路径；登记时**沿链补齐**——链上缺失的祖先一并建出，因此不留悬空节点。单段只允许 `[A-Za-z0-9_-]`，自身链上不得重复段 |
| 创建与销毁 | 三个**来源**（`BUILTIN` 根域 / `MANUAL` 人工创建 / `DERIVED` 工具声明派生）**同级，不构成等级**；差异只在生命周期——`MANUAL` 落盘重启仍在，`DERIVED` 重启随声明重建 |
| 删除 | **递归**带走全部子孙，不向上提升层级；**只有根域不可删**（它是整棵树起点）。删前先删该域及子孙的知识库索引，未删干净则拒绝删域 |
| 可见性判定 | **累加**：工具声明命中该域或它的任一祖先即见。挂在父域上的工具，其所有后代域都能用 |
| 声明留空 | 挂在根域 `default`；根域在每个域的祖先链里，故留空＝**全树可见**。想收紧就显式写完整路径 |
| 通配 | **没有通配写法**。旧版 `{"*"}` 已删除，"全域可见"的写法就是挂根域 |
| 维度数量 | 域是工具可见性的 **唯一维度**，不叠加第二个权限维度 |
| `profile` 缺失 / 空 | 归一化为根域 `default`（恒存在、不可删），不报错 |
| `profile` 非空但域不存在 | fail-fast 返回 `10004`，**绝不回退为全量工具** |
| 同源性约束 | 模型可见工具集与需审批工具集必须来自同一判定（`ToolRegistry.toolSpecifications` 与 `toolsRequiringApproval` 同源）；拒绝文案分两种：域外 `10001`、工具已下线 `80001` |
| 工具视图不取快照 | 每轮实时读注册表；不缓存工具集快照 |

四个维度共用同一套**沿链累加**语义：工具可见性（声明命中自身或祖先）、知识库（自身 ∪ 全部祖先）、
提示词（从根到自身依次拼接）、模型绑定（自身没绑就向上找最近的绑定）。公共内容只需在根域写一次。

路径运算（`parentOf` / `chainOf` / `ancestorsOf` / `validatePath`）是 `Domains` 上的**静态纯函数**，
不依赖任何注册表——基础设施层因此能自行展开祖先链，无需反向依赖运行期容器。

---

## 5 工具体系

### 5.1 注解契约（`stringer-api/annotation`）

工具注解只有一个入口：**`@Tool` 全家桶**（`@Tool` / `@ToolParam` / `@ToolDomains` / `@ToolAdvanced`）。

| 注解 | 目标 | 字段 | 默认值 |
| --- | --- | --- | --- |
| `@Tool` | METHOD | `desc` | **必填**（唯一必填；给模型的用途说明） |
| | | `value` | `""`（工具名，留空取方法名；全局唯一，重名注册失败） |
| | | `domains` | `{}`（每项为完整路径；留空＝挂根域＝全树可见；无通配） |
| | | `effect` | `Effect.READ`（`READ`/`WRITE`/`DESTRUCTIVE`） |
| | | `approval` | `Approval.NONE`（`NONE`/`ALWAYS`，当前仅此两态生效） |
| | | `approvalReason` | `""`（`approval != NONE` 时建议填写，展示给审批人） |
| `@ToolParam` | PARAMETER / FIELD / RECORD_COMPONENT | `value` | `""`（参数说明，推荐写法；不写会被统计为"未描述"并 WARN） |
| | | `name` | `""`（留空取形参名 / 字段名） |
| | | `required` | `true`（`Optional<T>` 自动判为可选） |
| `@ToolDomains` | TYPE | `value` | `{}`（类级默认域；方法级 `domains` 就近覆盖） |
| `@ToolAdvanced` | METHOD | `example` | `{}`（`参数名=示例值`，如 `{"orderNo=FR2024001"}`） |
| | | `allowValues` | `{}`（`参数名=值1\|值2`，如 `{"channel=SMS\|APP"}`） |
| | | `sensitive` | `{}`（需掩码的参数名清单） |

> `@ToolParam` 只保留"每个参数都该写"的三项（说明 / 名字 / 必填）；示例、白名单、脱敏属于长尾，
> 统一在方法级的 `@ToolAdvanced` 上写，三个字段一律 `参数名=值`，**不做位置对齐**。

实际生效范围（当前实现）：

- 扫描：`AnnotatedToolScanner` 只识别 `@Tool`（服务端进程内与工具实例 SDK 两侧规则一致）；`StringerToolProvider` 接口已退化为可选标记。
- 参数结构由反射推导（`String`/`int`/`boolean`/`enum`/`List<T>`/`record DTO` → JSON Schema 的 `type`/`properties`/`required`）；`@ToolParam` 只补说明。
- `@ToolAdvanced` 在参数树建好后**按名字**套上（名字既可是形参名，也可是 DTO 展开出的字段名）：`allowValues` → 模型 schema 的 `enum`；`example` → `Param.example` **并追加进模型可见的参数说明**（底层 schema 只有 description 一个自由文本位，没有 example 槽）；`sensitive` → `Param.sensitive` + 事件与审批 payload 的**值掩码**。
- 审批判定只看 `@Tool#approval` 是否非 `NONE`；新注解只暴露 `NONE` / `ALWAYS` 两态。
- 仅登记、不参与运行行为：`idempotent`、`toModel`、`ToolDescriptor.Approval` 的 `condition`/`approverRoles`/`timeoutSeconds`（新注解不再暴露这三个字段，记录结构保留以备后续真落地）。
- 域归属合并：`@Tool(domains=)` 与 `@ToolDomains`（类级默认）合并判定，方法级优先。

### 5.2 工具来源

| 来源 | 触发时机 | 注册路径 | 副本表示 |
| --- | --- | --- | --- |
| 本地 Bean | 启动期扫描 | Spring Bean（可选实现 `StringerToolProvider`）→ `AnnotatedToolScanner` | 单元素 `local` |
| 远程实例 | 运行期整包心跳 | `POST /api/agent/tools/register` | `instanceId` + `endpoint` |

两个来源写入同一个内存注册表 `ToolRegistry`；注册表不落盘。本地工具与远程工具对模型和管控台完全透明。

### 5.3 注册表结构

| 项 | 规定 |
| --- | --- |
| 条目 | `Registered(ToolDescriptor descriptor, ToolSpecification specification, ToolExecutor executor, List<InstanceEndpoint> endpoints)`，不可变 |
| 副本端点 | `InstanceEndpoint(String instanceId, String endpoint)` |
| 反向索引 | `byInstance`：`instanceId → 该实例声明的工具名集合` |
| 更新语义 | 按 `instanceId` 整包替换：本次心跳的声明为准（地址无条件校对；描述、域归属等变更以本次为准） |
| 移除语义 | 只删该实例的副本；副本列表为空才整条移除条目，域随之消失 |
| 重名 | 同名工具的多个实例＝多副本；路由在副本间轮选，传输层失败换下一个副本（最多 `invoke-max-attempts` 次，默认 2）；业务失败不重试 |
| 并发 | 不可变对象 + `compute` 原子替换；读端无锁 |
| `.source()` | 本地 `包名.类名#方法名`；远程 `remote://{instanceId}@{endpoint}`（仅排障展示，不参与路由） |
| 声明不一致 | 多副本声明不一致时取首份，其余仅告警 |

### 5.4 实例生命周期

| 状态 | 含义 |
| --- | --- |
| `ONLINE` | 在线，副本可路由 |
| `DRAINING` | 已判死，正在清理副本（不截断会话） |
| `OFFLINE` | 清理完成，从在线表移除；重新心跳可回 `ONLINE` |
| `FORCE_OFFLINE` | 管理员强制下线：立即清副本，再次心跳返回 `410`；标记有保留窗，过期后可重新注册 |

| 参数 | 默认值 | 行为 |
| --- | --- | --- |
| 心跳周期 | 5s | 实例侧定时整包上报 |
| `stringer.instance.timeout-seconds` | 35 | 超过该时长未收到心跳即判死（≈心跳×7） |
| `stringer.instance.scan-interval-ms` | 5000 | 服务端定时扫描判死，与流量解耦 |
| `stringer.instance.force-offline-retention-seconds` | 3600 | 强制下线标记保留时长 |
| `stringer.instance.invoke-timeout-ms` | 30000 | 单次反向调用超时 |
| `stringer.instance.invoke-max-attempts` | 2 | 传输层失败时尝试的不同副本数上限 |

- 实例下线 **不截断会话**：会话属于域，不属于实例。
- 同一实例的状态标记与副本清理必须在同一把实例锁内一次完成。

### 5.5 工具执行与审批

- 执行前检查取消标志；工具不可见或已下线时不执行，回文本给模型。
- 命中审批的工具：发出 `INTERRUPT` 事件并中断图，等待调用方 `resume`。
- `resume` 必须携带 `profile`；执行 `resume` 时按**本次域**重新校验工具可见性（工具已下线也拦下）。
- `resume` 只接受中断时的那个域；域不一致直接拒绝。
- 请求入口若发现断点停在审批点，拒绝该轮 `chat`（`30002`），不清理断点。
- 同一 `sessionId` 不允许并发：已在执行时直接拒绝（`30003`），不排队。

---

## 6 会话、记忆与检查点

| 项 | 规定 |
| --- | --- |
| 会话键 | `sessionId`，字符串，由调用方提供 |
| 记忆内容 | 仅用户消息与最终 AI 回答；工具调用与工具结果不进记忆 |
| 记忆约束 | `stringer.memory.max-messages`＝100、`max-tokens`＝30000、`ttl`＝72h |
| 检查点 | LangGraph4j 检查点存 Redis，`stringer.memory.checkpoint-ttl`＝24h |
| 记忆与检查点的域关系 | 域是 per-request；记忆按 `sessionId` 唯一键，跨域共享同一份 |
| 停止语义 | `stop` 只置取消标志，由 `agentNode.onPartialResponse` 与 `toolsNode` 在工具执行前检查后抛出；随后回滚本轮记忆、清检查点、推 `STOPPED`、结束流 |

消息通道：图的 `messages` 通道使用去重被禁用的追加器（`appenderWithDuplicate`），允许同内容消息重复入列。

---

## 7 系统提示词

| 项 | 规定 |
| --- | --- |
| 组成 | **沿域链拼接**：从根域到当前域依次取出每段片段，拼成一条 `SystemMessage`。根域的片段即基底，后代的追加在后 |
| 来源与优先级 | `config/prompts.json`（高） > yaml（`stringer.ai.prompt.prompts.*`，低）。**没有 `base` 这一层**，根域片段就是 `prompts["default"]` |
| 拼接标记 | 无边界标记。链上每段都是平台自配的提示词，加"非系统指令"标记只会削弱根域内容的权威性 |
| 缺省 | 整条链都为空时不下发系统提示词，并记一条 WARN |
| 生效时机 | 执行单元内冻结；单元之间取最新值；`resume` 沿用中断时注入的那份 |
| 替换语义 | 整体替换，不支持追加 |
| 注入方式 | 编排层注入"提示词解析器"而非提示词字符串 |

提示词是软引导，不构成能力边界：工具不可见时不会出现在提示词与工具集中。

---

## 8 知识库与检索

### 8.1 一域一索引

**一个域 = 一个 ES 索引**，与域树、工具可见性、提示词共用同一套沿链累加语义。

| 项 | 规定 |
| --- | --- |
| 索引粒度 | 每个域一个独立索引；文档"属于哪个域"＝它被上传到哪个域的索引 |
| 索引名 | 由域路径**确定性派生**：`stringer_kb_<安全前缀>_<8位哈希>`（`KbIndexes.nameOf`，纯函数、不依赖任何注册表）。不用域路径直连做索引名——点号在 ES 通配 / 日期数学 / 隐藏索引场景下有歧义，且大小写敏感的域（`a` 与 `A`）转小写后会撞名，故用哈希后缀保证唯一 |
| 索引创建 | **按需创建**：首次向该域上传文档时建出，映射含 IK 中文分词配置与向量维度。不再有"启动期建全局索引"——域是运行期由用户创建的，索引跟着域走 |
| 通配 | `stringer_kb_*` 覆盖全部知识库索引（枚举、ES 连接探测、重建用） |
| 删除 | 删域时**先删该域及全部子孙的索引**，删不干净则拒绝删域（顺序不能反：域先没了，索引就成了没人认领的孤儿） |
| 重建 | 删掉全部知识库索引并按当前维度重建；一个都没有时建出根域的索引。**索引会被清空，文档需重新上传** |

### 8.2 文档模型

| 项 | 规定 |
| --- | --- |
| 导入方式 | 部署方上传（管控台 / HTTP / starter），服务端不内置文档 |
| 支持类型 | `stringer.rag.allowed-extensions`，当前只有 `txt`（md / pdf / html 属后续扩展，策略工厂已留好位置） |
| 大小上限 | `stringer.rag.max-file-size`，默认 10MB |
| 字符集 | **全链路统一 UTF-8**：入口探测一次（BOM → 严格 UTF-8 → GB18030）并转码；判不出编码则**拒绝该文件**，不猜 |
| 归属域 | 上传时指定单个域（完整路径，留空＝根域 `default`）；路径非法直接拒绝 |
| 同一性判定 | **同一域内**同名不区分大小写；默认拒绝，带 `replace=true` 则先删旧再写入 |
| 删除语义 | 删除该文档全部切片，并释放文件名（删除后可重新上传同名）。docId 里看不出所在域，故逐个索引查找 |
| 唯一键 | `doc_id`（UUID，删除与聚合的依据）与 `file_name`（展示与同名校验） |
| 切片元数据 | `doc_id`、`file_name`、`file_name_lower`、`upload_time`、`domain`、`section_path`、`section_title`、`chunk_seq`、`chunk_total`、`content_hash`。`metadata` 在 mapping 里是 `dynamic:false` + 全部显式声明 |
| 去重键 | `content_hash = SHA256(切片正文)`，**不含文件名** —— 改了文件名重传也算同一份内容 |
| 切片规则 | 四步：统一字符集 → 清洗 → 认标题 → 按句子切片。`max-chars` 是**上限不是固定长度**（撞标题即断）。完整规则见 [`TXT-INGESTION-DESIGN.md`](TXT-INGESTION-DESIGN.md) |
| 切片预览 | 上传后把该文档的切片写成 UTF-8 txt 落到 `stringer.export.path`（默认 `%ProgramData%\Stringer\chunks`），管控台只展示路径 |
| 并发 | 导入全局串行，等待上限 `stringer.rag.ingest-lock-wait-seconds`（默认 60s） |
| 失败处理 | 导入失败回滚本次已写入的切片；失败必须上抛，不得返回成功计数。切片导出失败不影响入库 |

### 8.3 检索：按域解析通道 + 双轨融合

检索域 D 时，召回通道 = **`chainOf(D)` 上每个索引 × 两路模态**（自身 + 全部祖先，含根域）。

| 项 | 规定 |
| --- | --- |
| 域边界 | 由"查哪些索引"保证，**不再做 `metadata.domains` 过滤** |
| 通道 | `域链长度 × 2` 条：每张索引一条向量路（余弦）、一条关键词路（BM25）。索引不存在时通道安静返回空（检索器开 `ignoreUnavailable` / `allowNoIndices`），不预探测——省掉 N 次往返 |
| 未绑定域 | 非对话路径（管控台预览、诊断）未绑定检索域 → 对 `stringer_kb_*` 做通配检索，保持"全库检索"行为 |
| **双轨融合** | 来源（索引）数 ≤ 1 → `DefaultFusionStrategy`（**分数制**，与单索引时代逐字节一致）；≥ 2 → `RrfFusionStrategy`（**排名制**）。切换由 `AdaptiveFusionStrategy` 自动完成 |
| 为什么多索引必须换 RRF | BM25 的 idf 用**本索引**的文档频率计算，同一个词在不同索引里量纲不同——多索引 BM25 分池化后做 min-max 会把"小索引里稀有词被抬高的分"当成 max，把其余结果压扁。RRF 只看名次，天然免疫 |
| RRF 公式 | `score(d) = Σ_L w_L / (k + rank_L(d))`，`k` 默认 60（`stringer.retrieval.rrf-k`） |
| 权重均摊 | 每张向量表 `w = vectorWeight / 向量表数`，每张关键词表 `w = keywordWeight / 关键词表数`——否则祖先域越多、向量表越多，关键词那路被越压越扁 |
| 召回条数 | `vector-top-k`＝15（每张索引）、`keyword-top-k`＝5（每张索引） |
| 向量阈值 | `vector-min-score`＝0.2（按原始余弦填；脚本内已 `+1.0` 回归偏差） |
| 权重 / boost | `vector-weight`＝0.6、`keyword-weight`＝0.4、`title-boost`＝0.15、`file-name-boost`＝0.10、`top-n`＝10 |
| 超时 | `stringer.retrieval.timeout-ms`＝5000（始终为有限值）；全通道失败会抛错，与"真没命中"区分开 |
| 空结果 | 返回"未检索到相关内容"文本；服务不可用返回"知识库检索服务当前不可用…"——两态分离 |

知识检索以 `KnowledgeSearchService` 形式提供，由部署方通过 `@Tool` 暴露为工具；服务端不自带示例工具。

**域对知识仍是两层约束**（与工具不同，别记混了）：

1. **工具层**：检索工具本身声明到哪些域 —— 决定了"这个域的对话能不能检索"。
2. **内容层**：文档上传到哪个域 —— 决定了"能检索时，能查到哪些文档"（累加：挂在某域则其全部后代可检索）。

> 域边界必须在**召回通道层面**确定（查哪些索引），而不是召回后再过滤：两路各回 Top-N，
> 混进其他域的文档会把本域结果挤掉，融合后过滤就只剩一两条 —— 检索"成功了"但召回塌陷，且不报错。

---

## 9 模型配置

模型接入分两层：**「模型设置」页的全局配置**（`config/llm-settings.json`，一套对话模型 + 一套向量模型；向量那套是知识库检索<b>唯一</b>在用的向量模型）与**多 LLM 模型档案**（`config/models.json`，域可绑定到不同 OpenAI 兼容端点的档案）。

> **没有"内置 `default` 模型别名"这一说。** 对话模型只来自用户自建的模型档案：一个域若没有显式绑定任何可用的对话档案，调用时直接抛 `NotConfiguredException`（`90005` 类，`DEPENDENCY_NOT_CONFIGURED`），<b>不再回落到全局对话模型</b>。全局对话模型配置（`llm-settings.json` 的 chat 段）仅作为"解析器未装配"时的兜底与 `chatConfigured` 指示的一项来源；向量模型始终只走 `llm-settings.json`（单一实例，知识库检索用）。

### 9.1 模型档案（多 LLM，`config/models.json`）

| 项 | 规定 |
| --- | --- |
| 单元 | `ModelProfile`：一个 OpenAI 兼容端点的<b>一份</b>配置（别名 `alias` 唯一；同一模型可配多份档案，域绑的是档案而非模型名） |
| 字段（落盘 `ProfileData`） | `alias`、`endpoints`(端点族 List，可多选，空＝`["chat"]`)、`input` / `output`(输入 / 输出模态 List)、`baseUrl`、`apiKey`、`modelName`、`temperature`(Double，可空)、`maxTokens`(Integer，可空)、`dimensions`(Integer，仅 embedding 用，可空)、`capabilities`(布尔能力 List：`streaming` / `tools`)、`fallbacks`(降级链，暂只存不生效) |
| 是否对话模型 | 不再有 `type` 字段；用 `isChat()` = `endpoints.contains("chat")` 判定；向量档案靠 `endpoints.contains("embedding")` |
| 必填 | `baseUrl` / `apiKey` / `modelName` 三者齐备才 `isUsable()`；缺失则拒绝保存 |
| 能力声明 | `capabilities` 由使用者显式写出；未声明 `tools` → 该域模型<b>不会调用任何工具</b>（只告警不拒绝）；缺失能力时 `capabilityHint()` 提示 |
| 绑定规则 | <b>档案不自动绑定域</b>：新档案默认"未绑定"；绑定只能由管控台显式写。<b>一个域可绑一组有序的模型别名</b>（整体覆盖，列表首个为当前使用的对话模型，其余留给多 agent / 降级）；<b>空列表＝该域"无可调用模型"</b> |
| 解析顺序 | `ModelProfileRegistry#resolveAliases(domain)` 返回该域<b>显式绑定</b>的别名列表（有序）；列表为空 → 不再回落任何默认值，由 `DefaultModelResolver` 抛 `NotConfiguredException`；列表按序试：首个 `isUsable()` 且 `isChat()` 的档案即命中 |
| 域绑定 | `bind(domain, aliases)`：`aliases` 空 / 全部空白＝<b>解绑</b>（该域进入"无可调用"状态）；列表整体覆盖；`default` 已不再作为可绑定的模型别名（绑定只能指向自建档案）；绑到不存在的档案被拒 |
| 删除 | <b>级联清理</b>：删除档案时先把它从所有域的绑定里摘掉、摘空的域绑定直接移除，再删档案本身；那些域立即进入"无可调用"状态（由管控台「域空间」页明确提示），而不是把删除拦在半路。返回结果带出被摘掉绑定的域清单 |
| 客户端缓存 | `ModelClientFactory` 按<b>档案指纹</b>（含 `baseUrl + modelName + 温度 + maxTokens + SHA-256(apiKey)` 的 SHA-256 前 16 位）缓存；轮换 Key / 端点 → 指纹变 → 自然换实例，旧实例被回收 |
| 落盘 | `ModelProfileStore` → `config/models.json`；先改内存再整体落盘，落盘失败抛异常（调用方必须感知）；文件缺失 / 解析失败按空配置（所有域均"无可调用"，需到管控台逐一配置） |

### 9.2 「模型设置」全局配置（`config/llm-settings.json`）

> 这一套是<b>全局</b>配置：一套对话模型 + 一套向量模型。向量那套是知识库检索<b>唯一</b>在用的向量模型（不进档案体系）。对话那套作为"解析器未装配"时的兜底与 `chatConfigured` 指示来源；<b>域不会因为没配而自动回落到它</b>——域要对话，必须显式绑定一个自建档案。

| 项 | 规定 |
| --- | --- |
| 配置来源 | `config/llm-settings.json`（运行时，高） > yaml `stringer.ai.*`（低） |
| 文本模型字段 | `chatBaseUrl`、`chatApiKey`、`chatModelName`、`chatTemperature`（0~2，默认 0.5）、`chatMaxTokens`（默认 2048）、`chatCapabilities` |
| 向量模型字段 | `embeddingBaseUrl`、`embeddingApiKey`、`embeddingModelName`、`embeddingDimensions`（可空）、`embeddingCapabilities` |
| 回落规则 | 向量模型地址与 Key 留空时复用文本模型的值 |
| 空值语义 | 留空＝保持原值不变 |
| 状态三态 | `未配置`（必填项有空） / `已配置·未验证`（填齐未测或已改动） / `已连接` |
| 指纹 | `baseUrl + modelName + SHA-256(apiKey)`，文本与向量各存一份 |
| 装配方式 | `LlmModelHolder` 委托代理：`openAiChatModel`、`openAiStreamingChatModel`、`openAiEmbeddingModel`；热替换为原子替换，注入点不变。向量模型经此代理供知识库检索；对话模型仅在<b>解析器缺失</b>时作为兜底 |

### 9.3 向量维度契约

维度取值的**唯一入口**是 `LlmModelHolder#effectiveEmbeddingDimension()`，以下三处必须同源：

1. 测试连接读取的实测维度（读返回向量的实际长度）；
2. 运行时 `EmbeddingModel` 构建时传入的 `dimensions`；
3. ES 建索引时的 `dense_vector` 维度。

| 用户输入 | 行为 |
| --- | --- |
| `embeddingDimensions` 留空 | 请求不带 `dimensions`，索引取实测默认维度 |
| 显式声明 | 请求带 `dimensions=声明值`；实测与声明一致才通过 |

保存时的四态预检结果：`OK`（直接保存）、`NEEDS_REBUILD`（索引维度≠实测，需二次确认后保存并重建）、`DECLARED_MISMATCH`（声明≠实测，拒绝保存）、`UNKNOWN`（无法实测，放行并提示）。

---

## 10 存储配置（Elasticsearch / Redis）

| 项 | 规定 |
| --- | --- |
| 配置来源 | `config/infra-settings.json`（高） > yaml / 环境变量（低） |
| 字段 | ES：`host`、`port`（9200）、`scheme`（http）、`username`、`password`、`connectTimeout`（5000）、`socketTimeout`（10000）；Redis：`host`、`port`（6379）、`password`、`database`（0） |
| 未配置判据 | `host` 为 null 即未配置；两位点均空时启动照常（知识库索引本就是按需创建的，启动期无事可做），只打 INFO |
| 热替换 | `InfraSettingsHolder.apply()` → `SwappableElasticsearchTransport.swap()` / `SwappableRedisConnectionFactory.swap()`，volatile 原子替换；旧连接延迟 30s 关闭 |
| 守卫位置 | ES 只守 `performRequest` / `performRequestAsync`；Redis 只守 `getConnection` / `getClusterConnection` / `getSentinelConnection`；守卫只抛异常、不打日志 |
| 能力探测 | ES 判读 9.x / 8.x / 7.17 / 更低或 OpenSearch / 读不到；一律不阻断保存。IK 分词器探测 `POST /_analyze` 试 `ik_max_word`：可用 / 确认未安装 / 未探测 |
| 换址后果 | ES 换实例后需重建索引；Redis 换地址或库号＝换数据源，历史会话与断点留在旧库不迁移 |

---

## 11 鉴权与凭证

| 项 | 规定 |
| --- | --- |
| 账号 | 单一账号，无角色、无权限分级。默认种子 `stringer` / `stringer`，由 `classpath:accounts-seed.json` 落盘为 `config/accounts.json` |
| 密码存储 | BCrypt 哈希 |
| 凭证格式 | `base64url(payload) + "." + base64url(HMAC(派生密钥, payload))` |
| 派生密钥 | `HMAC(主密钥, passwordHash)` ⇒ 改密码后全部旧凭证立即失效 |
| 有效期 | 无 TTL |
| 载具 | `/api/agent/**` 用请求头 `X-Stringer-Credential`；`/admin/**` 用 HttpOnly Cookie `stringer_admin`，无 Cookie 时回退读同一请求头（供 starter / ETL 程序化调用） |
| 免检路径 | 被拦截前缀内的免检项：`/api/agent/login`、`/admin/login`、`/admin/init`、`/admin/session`、登录页与静态资源、`/error`、`/favicon.ico`；`OPTIONS` 请求一律放行。另有 `GET /health` 存活探测，不在被拦截的前缀之下，天然免鉴权 |
| 账号文件两态 | 文件不存在（合法初始态）→ 放行并开放 `/admin/init`；文件存在但解析失败 → 一律拒绝（`10007`），且不开放初始化入口 |
| 登录写盘 | 仅记录最后登录时间与来源，写盘失败只告警，不阻断登录 |
| 恢复路径 | 唯一恢复手段是删除 `config/accounts.json` 后重启；无密保、无重置接口 |

---

## 12 错误处理

| 项 | 规定 |
| --- | --- |
| 错误码载体 | `stringer-api` 的 `ErrorCode`，五元组：`code` / `message` / `httpStatus`（建议值，非契约） / `retryable` / `action` |
| 码段 | `10xxx` 权限与鉴权 / `20xxx` 限流与容量 / `30xxx` 编排与会话 / `40xxx` 客户端与入参 / `50xxx` 系统通用 / `60xxx` 知识库 / `70xxx` 记忆与检查点 / `80xxx` 工具调用 / `90xxx` 大模型与外部依赖 |
| 三条通道 | SSE 事件的 `code`；非流式响应体的 `code`；starter 侧异常携带的 `ErrorCode` |
| 非流式出口 | `ServerGlobalExceptionHandler`（`@RestControllerAdvice`） |
| 流式出口 | `AgentEvent.ERROR` 帧（HTTP 状态已是 200，`code` 是唯一真相） |
| 响应体字段 | `code`、`codeName`、`error`、`detail`、`retryable`、`action`、`traceId`、`timestamp` |
| 成功响应 | 统一带 `code=0` 的接口：`health`、`stop`、`init`、`login`、`logout`、`session`、`changePassword`、`agentLogin` |
| 日志级别 | 默认记录 ERROR；以下降级为 WARN 且不打堆栈：`NotConfiguredException`、`AuthException`、`CancellationException`、`NoResourceFoundException`、参数与请求体类异常 |
| 存储类失败判定 | 按异常栈中是否出现 `Swappable*` 或 ES / Redis 客户端包名判定，映射 `90004`；未配置映射 `90005` |
| traceId | 生成于 `api.support.TraceId`，请求入口 `begin`、出口 `end`，同步写 MDC；`end` 必须调用 |

完整码表见 `API.md` 第 5 节。

---

## 13 日志

| 项 | 规定 |
| --- | --- |
| 控制台格式 | `时间 级别 [traceId] [线程] logger - 消息`，ANSI 着色 |
| 配色 | DEBUG/TRACE 不上色、INFO 蓝、WARN 黄、ERROR 红；traceId 青；线程名不上色。级别是整行唯一强调色，三档不加粗 |
| 默认输出 | **仅控制台**。应用只写"事件流"，落盘、轮转、保留与采集交给部署平台 |
| 文件输出（可选） | 加启动参数 `--logging.config=classpath:logback-file.xml` 切换到文件形态；启动横幅会打印当前状态与开启命令 |
| 文件与滚动 | `{path}/stringer-server.log` 全量（按天 + 单文件 50MB，保留 30 天 / 总量 2GB）；`{path}/stringer-server-error.log` 仅 ERROR（保留 60 天 / 总量 1GB） |
| 审计 | 独立 logger 名 `AUDIT`，格式 `action=… operator=… result=… ip=…`；文件形态下另写 `{path}/stringer-server-audit.log`（保留 180 天）。**只记变更类请求**（`/admin/**` 的非 GET/HEAD/OPTIONS 与工具实例注册） |
| 文件路径 | `stringer.logging.path`，默认 `/var/log/stringer`（服务器绝对路径，可用 `STRINGER_LOG_PATH` 覆盖） |
| 级别 | `stringer.logging.level`，默认 INFO |
| 是否已启用文件输出 | 以 root logger 上是否挂着文件 appender 为准（运行事实），不读配置文本 |
| 级别准则 | 需要人工介入＝ERROR；需关注或已自愈降级＝WARN；关键节点＝INFO；排查细节＝DEBUG |
| 未配置的表达 | 启动期只以横幅 INFO 陈述；调用期未配置才 WARN（`90005`）；配了但连不上为 ERROR（`90004`） |
| 敏感红线 | 禁止入日志：凭证与密钥（LLM / ES / Redis）、用户消息全文、身份证 / 手机号 / 银行卡 |
| 允许入日志 | 主机、端口、库号、索引名、耗时、数量、traceId、状态码 |
| 第三方降噪 | 默认压到 WARN：`dev.langchain4j`、`org.apache.http.wire`、`io.netty`、`reactor.netty`、`org.elasticsearch`、`co.elastic.clients`、`io.lettuce`、`org.springframework.web` |

---

## 14 并发模型

| 线程池 | 位置 | 线程名 | core / max / queue | 拒绝策略 | 销毁 |
| --- | --- | --- | --- | --- | --- |
| 图编排 | `GraphConfiguration` | `stringer-agent-N` | 8 / 32 / 200 | AbortPolicy | daemon + `shutdownNow` |
| 混合检索 | `RetrievalConfiguration` | `stringer-retrieval-N` | 4 / 16 / 200 | AbortPolicy | daemon + `shutdown` |
| 知识库导入 | `KnowledgeBaseService` | `stringer-kb-ingest-N` | 2 / 2 / 16 | AbortPolicy | daemon + `@PreDestroy` |
| 实例心跳 | `ToolInstanceClient` | 单线程 | 1 / — / — | — | daemon |
| 判死扫描 | `InstanceLifecycle` | Spring 单线程 | — | — | 容器托管 |

线程池参数外置：`stringer.agent.*`、`stringer.retrieval.*`、`stringer.rag.ingest-pool-size`、`stringer.rag.ingest-queue-capacity`。

| 同步原语 | 位置 | 保护的临界区 |
| --- | --- | --- |
| 条带锁 `StripedLocks`（固定 64 槽） | `InstanceRegistry`、`ToolRegistry` | 同一 `instanceId` 的心跳、判死、强制下线、副本替换 |
| `synchronized(this)` | `ClientCredential`、`ToolInstanceClient`、`AccountStore`、`SwappableRedisConnectionFactory` | 换凭证、账号读写、连接工厂热替换 |
| `synchronized(writeLock)` | `AuthService` | 初始化 / 登录写盘 / 改密码的整段"读-改-写" |
| `Semaphore(1)` | `KnowledgeBaseService` | 知识库导入串行与重名校验 |
| `ConcurrentHashMap` + `compute` | `ToolRegistry`、`CancellationRegistry`、`DomainSystemPromptResolver` | 条目原子替换、停止标志、告警去重 |

必须串行的边界：同一 `sessionId` 的对话；知识库导入；同一实例的注册表更新；账号与配置的写盘。

配置写盘一律经 `AtomicFiles`（临时文件 + `ATOMIC_MOVE`）。

---

## 15 配置项总表

下表为代码绑定的配置键。yaml 中 `stringer.ai.*` 与 `stringer.elasticsearch.*` 两段整体以注释保留，运行时的模型与连接信息以管控台落盘文件为准（见 §9、§10）。

| 键 | 类型 | 默认值 |
| --- | --- | --- |
| `server.port` | int | 9527（可被 `SERVER_PORT` / `STRINGER_SERVER_PORT` 覆盖） |
| `stringer.settings.path` | String | `/var/lib/stringer/config` |
| `stringer.logging.path` | String | `/var/log/stringer` |
| `stringer.logging.level` | String | `INFO` |
| `stringer.ai.prompt.base` | String | yaml 内定义 |
| `stringer.ai.prompt.prompts` | Map | `{}` |
| `stringer.redis.host` | String | null |
| `stringer.redis.port` | int | 6379 |
| `stringer.redis.password` | String | null |
| `stringer.redis.database` | int | 0 |
| `stringer.redis.timeout` | Duration | 2000ms |
| `stringer.redis.pool.max-active` / `max-idle` / `min-idle` | int | 8 / 8 / 0 |
| `stringer.redis.pool.max-wait` | Duration | -1ms |
| `stringer.memory.max-messages` | int | 100 |
| `stringer.memory.max-tokens` | int | 30000 |
| `stringer.memory.ttl` | Duration | 72h |
| `stringer.memory.checkpoint-ttl` | Duration | 24h |
| `stringer.agent.core-pool-size` / `max-pool-size` / `queue-capacity` / `keep-alive-seconds` | int / long | 8 / 32 / 200 / 60 |
| `stringer.instance.timeout-seconds` | long | 35 |
| `stringer.instance.force-offline-retention-seconds` | long | 3600 |
| `stringer.instance.scan-interval-ms` | long | 5000 |
| `stringer.instance.invoke-timeout-ms` | int | 30000 |
| `stringer.instance.invoke-max-attempts` | int | 2 |
| `stringer.retrieval.parallel` | boolean | true |
| `stringer.retrieval.timeout-ms` | long | 5000 |
| `stringer.retrieval.core-pool-size` / `max-pool-size` / `queue-capacity` | int | 4 / 16 / 200 |
| `stringer.retrieval.vector-top-k` / `keyword-top-k` | int | 15 / 5（每张索引） |
| `stringer.retrieval.vector-min-score` | double | 0.2 |
| `stringer.retrieval.vector-weight` / `keyword-weight` | double | 0.6 / 0.4 |
| `stringer.retrieval.title-boost` / `file-name-boost` | double | 0.15 / 0.10 |
| `stringer.retrieval.top-n` | int | 10 |
| `stringer.retrieval.rrf-k` | int | 60 |
| `stringer.rag.max-file-size` | DataSize | 10MB |
| `stringer.rag.allowed-extensions` | List | `[md, txt, markdown, text]` |
| `stringer.rag.ingest-lock-wait-seconds` | long | 60 |
| `stringer.rag.ingest-pool-size` / `ingest-queue-capacity` | int | 2 / 16 |
| `spring.web.resources.cache.cachecontrol.no-cache` | boolean | true |
| `stringer.server.host` / `port` | String / int | localhost / 9527 |
| `stringer.server.username` / `password` | String | stringer / stringer |
| `stringer.client.health-check-timeout` / `connect-timeout` / `read-timeout` | Duration | 5s / 5s / 10m |
| `stringer.tool-instance.enabled` | boolean | false |
| `stringer.tool-instance.instance-id` | String | — |
| `stringer.tool-instance.endpoint` | String | 留空按本进程端口推导 |
| `stringer.tool-instance.scan-annotated` | boolean | true |
| `stringer.tool-instance.heartbeat-interval-seconds` | int | 5 |
| `stringer.tool-instance.max-backoff-seconds` | int | 20 |
| `stringer.tool-instance.request-timeout-millis` | int | 10000 |

---

## 16 落盘文件

| 文件 | Store | 顶层字段 |
| --- | --- | --- |
| `config/accounts.json` | `AccountStore` | `username`、`passwordHash`、`signingKey`、`createdAt`、`lastLoginAt`、`lastLoginFrom` |
| `config/llm-settings.json` | `LlmSettingsStore` | `chatBaseUrl`、`chatApiKey`、`chatModelName`、`chatTemperature`、`chatMaxTokens`、`embeddingBaseUrl`、`embeddingApiKey`、`embeddingModelName`、`embeddingDimensions` |
| `config/models.json` | `ModelProfileStore` | `domainBindings`（域→别名<b>列表</b>，有序；空＝该域无可调用）、`profiles`（别名→ `ProfileData{endpoints, input, output, baseUrl, apiKey, modelName, temperature, maxTokens, dimensions, capabilities, fallbacks}`；无 `type` 字段，`isChat()`＝`endpoints` 含 `chat`） |
| `config/infra-settings.json` | `InfraSettingsStore` | `es{host,port,scheme,username,password,connectTimeout,socketTimeout}`、`redis{host,port,password,database}` |
| `config/prompts.json` | `DomainSettingsStore` | `base`、`prompts`（域名 → 提示词） |
| `config/domains.json` | `DomainStore` | `manualDomains`（人工创建的域标识清单） |

- 目录由 `stringer.settings.path` 指定，默认 `/var/lib/stringer/config`（服务器绝对路径）；容器化把该目录挂成卷。
- 落盘内容为"用户填写的那一份"，不是合并后的生效值。
- 工具注册表与内置 / 派生域不落盘；**人工创建的域**（`config/domains.json`）与**模型档案**（`config/models.json`）落盘。

---

## 17 管控台

| 项 | 规定 |
| --- | --- |
| 入口 | `http://localhost:9527/admin.html`（转发到 `console/overview.html`）；登录页 `console/login.html` |
| 页面 | 8 项：概览、模型设置、存储配置、域空间、在线实例、提示词设定、知识库、账号 |
| 静态资源 | `static/admin.html` + `static/console/*.html` + `console/assets/console.css`、`console/assets/console.js`；零依赖、不引 CDN |
| 导航 | 由 `console.js` 的 `renderSidebar()` 渲染；新增页面＝落一个 HTML + 在导航数组加项（图标名须已存在于图标表中） |
| 登录守卫 | 在 `console.js` 中统一实现（加载时查 `/admin/session`，收到 `10002` 跳登录页）；登录页用 `window.CONSOLE_NO_AUTH_GUARD = true` 关闭守卫 |
| 同源要求 | 必须从服务端地址打开：凭证是 Cookie，跨站时不保存也不携带 |
| 缓存 | 静态资源已设 `no-cache` |
| 侧栏提示 | 「模型设置」的告警点由 `/admin/settings` 的 `chatConfigured` 驱动 |
| 域的呈现 | 「域空间」与「提示词设定」是同一事实的两个视图；前端不重算工具可见性 |
| 服务端依赖 | 页面不内置任何配置数据，全部经 `/admin/**` 读取 |

---

## 18 对外接口索引

接口签名、报文、事件契约、错误码与 SDK 用法见 `API.md`。

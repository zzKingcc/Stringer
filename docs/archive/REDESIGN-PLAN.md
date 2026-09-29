# Stringer 顶层设计评审与改造规划

> 评审对象：v1.0-beta.1 全量源码（api / common / domain / infrastructure / runtime / server / starter / tool-instance / example）
> 评审目的：找出当前设计在「定位扩张」时会撞上的天花板，给出可执行的分段改造方案
> 结论口径：**不是推翻重做**，现有骨架（图编排 + 工具治理 + 域可见性 + 热替换配置）方向正确，问题在于**状态位置、治理维度、能力深度、扩展点**四件事没做透

---

## 0 一句话结论

当前是一个「打磨得不错的**单实例 Agent 运行时**」，卡在三处天花板：

1. **形态天花板**：权威状态在进程内存（工具注册表、实例心跳表、导入锁、判死定时器），注定单实例、无 HA、不可横向扩展。
2. **治理天花板**：工具治理只有「域可见性 + 审批中断」两个维度，缺配额、版本、灰度、成本，工具上线即全量生效，无法安全迭代。
3. **能力天花板**：单 Agent、单图、单模型、两类记忆形态（会话 + 知识库），检索链路缺重排与文档级权限，Agent 不能规划、不能反思、不能长期记忆。

改造建议按 **契约与扩展点（地基）→ 能力层 → 形态跃迁** 三段推进，第一阶段**不改变任何现有对外行为**。

---

## 1 现状定位

| 项 | 现状 |
| --- | --- |
| 定位 | Java 生态的 AI Agent 运行时中间件（服务端 + 薄 starter + 工具实例 SDK） |
| 交付形态 | 单实例 fat jar，`stringer-server` 承载全部重逻辑，ES / Redis / 模型服务外置 |
| 已做好的抽象 | ① 文档处理策略（工厂 + 模板方法，按扩展名分派）；② 模型 / 基础设施 / 提示词的「管控台落盘优先 + yaml 回落」热替换（`LlmModelHolder`、`InfraSettingsHolder`、`Swappable*`）；③ 原子落盘 `AtomicFiles`；④ 检索双路融合 `CompositeRetriever`；⑤ 配置与密钥不入镜像 |
| 已声明的空壳 | ~~`api.spi` 下的五个接口~~ **已于 2026-09-29 作为死代码删除**（零 import、零实现；真实检索/记忆/检查点走 langchain4j / langgraph4j 同名接口，真正的工具注册扩展是 `api.tool.StringerToolProvider`） |
| 硬约束 | 进程内状态 ×3（`ToolRegistry`、`InstanceRegistry`、`KnowledgeBaseService` 的 `Semaphore(1)`）+ 进程内判死定时器（`InstanceLifecycle`） |

---

## 2 九维评估

| # | 维度 | 现状 | 判定 |
| --- | --- | --- | --- |
| 1 | 交付形态 | 单实例、进程内权威状态、扩容只能纵向 | **偏窄**（天花板） |
| 2 | 对外契约 | `AgentService` 仅 3 方法全流式；`AgentEvent` 无用量/耗时/节点；`ErrorCode` 封闭 enum 不可扩展；无 API 版本 | **偏窄** |
| 3 | 身份与隔离 | `tenantId` / `userId` 全链路**仅透传**：记忆 key、ES 索引、Redis key、检查点均无租户维度 | **缺失** |
| 4 | 模型层 | 单 chat + 单 embedding 原子替换；无路由、无降级、无灰度、无 token 预算、无成本核算 | **偏窄** |
| 5 | 编排与 Agent | 三节点单图（agent → 条件边 → tools / review）；无子图、无规划、无反思、无多 Agent、无长任务 | **偏窄** |
| 6 | 记忆与上下文 | 双约束会话记忆（50 条 / 30k token / 72h，构造参数硬编码）+ Redis 检查点 24h；无长期记忆、无用户画像、无 tokenizer 策略 | 够用但**缺层** |
| 7 | 知识与检索 | 摄取（策略分派 + 章节切片 + 幂等去重）与混合检索（双路 + 归一化加权）扎实；缺重排、查询改写、文档级权限、增量更新、检索评测 | **偏窄** |
| 8 | 工具体系与治理 | 注册 / 心跳 / 副本 / 域可见性 / 审批中断做得完整；缺版本、灰度、配额限流、标准协议（无 MCP 痕迹）、工具级成本 | **偏窄** |
| 9 | 工程面 | traceId 只在工具调用透传（chat 链路不贯穿）、无 OTel/指标导出、无幂等键、SSE 无断线续传、线程池 AbortPolicy 直拒不背压、客户端无重试熔断 | **缺失** |

---

## 3 差距清单

### P0 · 架构级（不改会限制一切后续设计）

| # | 差距 | 现状证据 | 后果 |
| --- | --- | --- | --- |
| P0-1 | 权威状态在进程内 | `ToolRegistry.tools` / `byInstance`、`InstanceRegistry.sessions`、`KnowledgeBaseService` 的 `Semaphore(1)` + 判死 `InstanceLifecycle` 定时器 | 无法多实例、无 HA、滚动升级即断服、重启丢在线表 |
| P0-2 | 租户维度不贯通 | 记忆 key `stringer:chat:memory:<sessionId>`、检查点 `stringer:graph:checkpoint:<threadId>`、ES 索引无 tenant 字段 | 多租户下会话串号、检索越权、无法按租户计量与限流 |
| P0-3 | 扩展点空壳 | ~~`api.spi` 五个接口~~ **已删除**（零实现零引用）；ES / Redis 单一绑死（`embedding`、`storage` 包为空占位） | 换存储、换检索、换记忆必须改源码；脱离 Spring 不可用 |
| P0-4 | 契约不可演进 | `ErrorCode` 私有构造的封闭 enum，`of(int)` 未命中返回 null；事件 payload 是裸 JSON String；HTTP 路径无版本 | 任何扩展都要破坏兼容；第三方无法自定义错误码与事件 |

### P1 · 能力级

| # | 差距 | 现状证据 | 后果 |
| --- | --- | --- | --- |
| P1-1 | 模型层无治理 | `LlmModelHolder` 只做原子替换；`chatModelName` 单一 | 模型故障无降级、无 A/B、无成本归集、无并发保护 |
| P1-2 | 检索缺后段 | ~~融合硬编码~~ **已抽成 `FusionStrategy`**（默认 `DefaultFusionStrategy` 复刻原算法，可替换）；`KnowledgeSearchService` 写死 `myContentRetriever` 与 Top5 | 召回质量上不去，且无法换 RRF / rerank，无法做评测迭代 |
| P1-3 | 无文档级权限 | ES 映射只有 `vector` / `text` / `metadata(file_name, section_title, content_hash)` | 知识库只能全租户共享，无法做部门 / 角色可见 |
| P1-4 | Agent 能力浅 | 图只有 `agent / review(no-op) / tools` | 复杂任务无法拆解、无法自检、无法跨会话积累 |
| P1-5 | 工具不可迭代 | `ToolDescriptor.version` 仅元数据，注册即全量可见 | 工具升级等于线上直接换行为，无法灰度与回滚 |
| P1-6 | 工具生态封闭 | 全仓无 MCP / JSON-RPC 痕迹，只有自定义 `POST /stringer/invoke` | 接入第三方工具（社区生态、IDE、其他 Agent）成本高 |

### P2 · 工程级

| # | 差距 | 现状证据 |
| --- | --- | --- |
| P2-1 | 链路不贯通 | traceId 仅 `ToolInvocationContext` 透传到工具实例，chat 全程无节点级追踪 |
| P2-2 | 无量级与成本 | `AgentEvent` 无 usage；`TokenUsageRecorder` 只打日志不落指标 |
| P2-3 | 无幂等 | `AgentRequest` 无 idempotencyKey；网络重发只能靠 `30003 SESSION_BUSY` 挡住 |
| P2-4 | SSE 不可续传 | 无事件 id / `Last-Event-ID`，断连即从头或放弃 |
| P2-5 | 无背压 | 线程池 AbortPolicy 直接拒（`SYSTEM_BUSY`），无排队与退避提示 |
| P2-6 | 客户端无韧性 | starter 无重试、无熔断（仅工具侧指数退避）；凭证无主动轮换 |
| P2-7 | 记忆约束硬编码 | `DualConstraintChatMemory` 的 50 / 30k 为构造参数；token 估算为字符级启发式 |

---

## 4 改造主题

每个主题给出：**目标 → 关键设计 → 影响面 → 兼容策略 → 验收**。

### T1 · 状态外置：从「单实例运行时」到「可横向扩展服务」

- **目标**：任意实例宕机不丢在线工具视图与会话状态；可滚动升级；支持 2 个以上副本。
- **关键设计**
  1. **权威状态外置**：`ToolRegistry` / `InstanceRegistry` 的权威副本放 Redis（Hash + TTL 表达心跳），本地保留只读缓存；变更通过 Redis Pub/Sub 广播失效，读路径零锁。
  2. **判死扫描改为选主执行**：`InstanceLifecycle` 用 Redis 分布式锁选主，单实例执行判死，避免多副本重复摘除。
  3. **导入串行改为分布式锁**：`KnowledgeBaseService` 的 `Semaphore(1)` → 分布式锁 + 本地排队，保留「导入全局串行」的语义不变。
  4. **状态读写全部收敛到接口后面**（为 T3 铺路，本阶段先抽接口、后换实现）。
- **影响面**：`stringer-runtime`（tool 包、orchestration 包）、`stringer-server`（config 装配）、`stringer-infrastructure`（新增 Redis 状态存储）。
- **兼容策略**：抽取 `ToolRegistryStore` / `InstanceStore` 接口，先保留现有内存实现为默认（`@ConditionalOnMissingBean`），Redis 实现以开关启用。**默认行为不变**。
- **验收**：起两个副本，杀掉其中一个，在线实例页在 30s 内收敛；工具注册与摘除在两副本上视图一致。

### T2 · 租户贯穿：从「字段透传」到「可治理的多租户」

- **目标**：会话、记忆、检查点、检索、配额、成本全部可按租户归集与隔离。
- **关键设计**
  1. **Key 与索引加租户维度**：记忆 `<tenant>:stringer:chat:memory:<sessionId>`、检查点同理；ES 映射加 `tenant_id` + `acl`，检索时强制 `filter`（缺省租户用 `default`，保证单租户部署零感知）。
  2. **域 + 租户二维校验**：域仍是工具可见性的唯一维度（保持不变式），租户只做**数据边界与配额**，不做第二维度工具过滤——避免权限模型爆炸。
  3. **配额与限流**：`tenant × tool` 令牌桶（工具调用）、`tenant` token 预算（模型用量），超限返回 `20xxx` 并带 `Retry-After`。
  4. **成本归集**：每次模型调用与工具调用产生 `usage` 事件，落 Redis 时序 + 导出为指标。
- **影响面**：`stringer-api`（事件加 usage）、`stringer-domain`（记忆 key）、`stringer-infrastructure`（ES 映射与 Redis key）、`stringer-server`（配额拦截器）。
- **兼容策略**：`tenantId` 缺省视为 `default`；ES 新增字段用 `dynamic: true` 兼容历史索引，重建索引时补全。
- **验收**：两个租户各自独立会话、互不可检索；单租户压测行为与改造前一致。

### T3 · 扩展点落地：把 `api.spi` 从空壳变成真接口

- **目标**：换检索器 / 记忆存储 / 检查点 / 模型服务 / 重排器 / 工具协议，全部不改核心代码。
- **关键设计**
  1. 定义六个真正的 SPI：`Retriever`（召回）、`Reranker`（重排）、`FusionStrategy`（融合，含 RRF）、`MemoryStore`、`CheckpointStore`、`ModelProvider`（chat / embedding / rerank 三类模型来源）。
  2. **双通道装配**：Spring 下走 `@ConditionalOnMissingBean`（默认实现兜底）；非 Spring 场景通过 `META-INF/services` 加载，兑现 SDK「脱离宿主容器可用」的承诺。
  3. 现有实现改为 SPI 的默认实现（`CompositeRetriever` → `LinearFusionStrategy`，`DualConstraintChatMemory` → `DefaultMemoryPolicy`），**语义不变**。
  4. 清理空包：`embedding` / `storage` 两个占位包要么补实现（模型来源、对象存储），要么删除。
- **影响面**：`stringer-api`（spi 包）、`stringer-domain`（检索与记忆）、`stringer-infrastructure`（ES / Redis 实现）、`stringer-server`（装配）。
- **兼容策略**：只新增接口与默认实现，不改现有类签名；旧类保留为 `@Deprecated` 别名一个版本周期。
- **验收**：注入一个自定义 `FusionStrategy`（如 RRF）后，检索结果按新策略变化，核心模块零改动。

### T4 · 模型网关：从「单模型持有」到「可路由、可降级、可计量」

- **目标**：模型故障自动降级、按域/租户/任务路由、成本可归集、提示词可灰度。
- **关键设计**
  1. `LlmModelHolder` 升级为 `ModelRouter`：按 `profile / tenant / taskType(chat|plan|rerank|summarize)` 选择端点，支持主备与权重；保持 `LlmModelHolder#effectiveEmbeddingDimension()` 的「维度唯一入口」契约不变。
  2. **降级链**：主模型超时/5xx → 备模型；全部失败 → `90003` 并在事件里说明已尝试的端点。
  3. **熔断**：按端点维度统计失败率，滑动窗口熔断，半开试探。
  4. **提示词版本化**：`profiles.json` 从 `域名 → 文本` 扩展为 `域名 → [{version, weight, text}]`，支持按权重灰度与一键回滚。
- **影响面**：`stringer-server`（settings / prompt）、`stringer-runtime`（编排取模型处）、`stringer-api`（事件带 modelName）。
- **兼容策略**：`profiles.json` 旧格式自动升级为单元素数组；`ModelRouter` 单端点时行为与现在完全一致。
- **验收**：把主模型地址改成不可达，请求自动走备模型并出 `TOKEN`；成本看板能按租户出量。

### T5 · 检索深化：从「能搜到」到「搜得准、搜得安全、可评测」

- **目标**：召回质量可度量、可迭代；文档级可见；增量更新不重建全库。
- **关键设计**
  1. **重排**：召回 Top-N（如 50）→ rerank 模型（或 ES rerank）→ 输出 Top-K，`Reranker` 作为 SPI。
  2. **查询改写**：可选一步用小模型做 query 扩写/同义改写，与向量 + 关键词一起做三路召回，`FusionStrategy` 负责融合。
  3. **文档级权限**：ES 加 `acl`（可见域 / 角色）与 `tenant_id`，检索请求带调用方 ACL 做 filter，**fail-closed**（ACL 缺失时不返回）。
  4. **增量更新**：现有 `content_hash` 幂等已具备基础，补「按 `doc_id` 对比章节级 diff，只重写变化切片」。
  5. **评测闭环**：内置小型评测集（问题 → 期望命中切片），离线跑 `recall@k` / `MRR`，把融合权重与 rerank 开关变成可调参数而非硬编码。
- **影响面**：`stringer-infrastructure`（ES 映射、索引管理）、`stringer-domain`（rag 包）、`stringer-server`（知识库接口）。
- **兼容策略**：rerank 默认关闭，权重保持 0.6 / 0.4；ACL 缺省时行为等价于「全可见」（与当前一致），仅在多租户开启后强制。
- **验收**：评测集上 recall 相对现状提升可量化；无 ACL 的文档在开启 ACL 后不再被越权检索到。

### T6 · 编排升级：从「单图单 Agent」到「可拆解、可自检、可长跑」

- **目标**：复杂任务能拆解执行；结果能自检；任务能跨请求长跑并查询进度。
- **关键设计**
  1. **review 节点泛化**：现在 `review` 是 no-op 中断锚点，扩为「策略节点」——审批、自检（critique）、人工补充信息共用同一中断机制。中断语义与 `interruptBefore` 契约保持不变。
  2. **子图**：LangGraph4j 原生支持子图，把「检索子流程」「多步工具流水线」封装成子图复用，主图保持三节点骨架。
  3. **长任务**：新增异步任务模型（任务表 + `GET /api/agent/tasks/{id}`），`chat` 仍为流式不变；长任务通过任务端点轮询或订阅。
  4. **工具结果缓存**：按 `工具名 + 参数规范化 + 幂等标记` 做缓存（仅对 `idempotent=true` 且 `sideEffect=READ` 的工具），命中即返回并标注 `cached`。
  5. **多 Agent（缓做）**：先不做通用多 Agent 编排，等单 Agent 的工具质量与检索质量达标后再评估；多 Agent 的价值 90% 取决于工具与检索质量，不取决于编排花样。
- **影响面**：`stringer-runtime`（graph / node / orchestration）、`stringer-api`（任务端点契约）。
- **兼容策略**：子图与缓存均为增量；长任务新增端点，不动 `chat` / `resume` / `stop`。
- **验收**：一次「查订单 → 判断是否需要退款 → 检索退款政策 → 生成答复」的多步子流程在子图内完成，主图日志仍只有三节点。

### T7 · 工具生态与协议：从「自定义协议」到「双向兼容」

- **目标**：能接社区工具（MCP），也能把自身工具暴露给外部 Agent；工具可灰度、可回滚、可限额。
- **关键设计**
  1. **MCP 双向适配层**（放在 `stringer-server` 的 `tool` 包，不污染 runtime）：
     - 入向：把 MCP server 的工具列表映射为 `ToolDescriptor`（`profiles` 由管控台配置），调用经 MCP client 转发；
     - 出向：把注册表工具暴露为一个 MCP server，供 IDE / 其他 Agent 调用。
  2. **工具版本与灰度**：同名工具多版本共存（`version` 从元数据变成参与路由的键），按域配置权重灰度，异常自动回退旧版本。
  3. **工具级治理补齐**：`ToolDescriptor` 增加「配额标签 / 超时 / 并发上限」，`ToolPolicy` 现有字段（`timeoutSeconds`、`onTimeout`、`approverRoles`）从「仅登记」变为「真生效」。
  4. **接入协议版本协商**：`manifest` 加协议版本，服务端按版本兼容处理，为后续演进留口子。
- **影响面**：`stringer-api`（ToolDescriptor）、`stringer-runtime`（路由）、`stringer-server`（新增 MCP 模块）、`stringer-tool-instance`（协议版本）。
- **兼容策略**：MCP 为可选模块；版本字段缺省视为 `1.0.0`，路由行为不变。
- **验收**：接入一个开源 MCP server 的工具，模型能在指定域内调用它；把某工具发两个版本，灰度流量按权重分配。

### T8 · 契约演进：让接口能被扩展而不破坏兼容

- **目标**：新增能力不必改老代码；第三方能自定义错误码与事件。
- **关键设计**
  1. **错误码接口化**：`ErrorCode` 从封闭 enum 改为 `ErrorCode` 接口 + `ErrorCodes` 注册表（保留现有 enum 作为默认实现与兼容层），自定义码走 `8x/9x` 预留段。
  2. **事件结构化**：`AgentEvent` 增加 `usage`（input/output tokens）、`node`、`durationMs`、`modelName`；`payload` 明确 JSON Schema 约束并版本化（新增 `schemaVersion`）。
  3. **HTTP 版本化**：新增 `/api/v2/agent/*` 或 `Accept: application/vnd.stringer.v2+json`，v1 冻结为兼容面；`/admin/*` 保持无版本（管理面允许演进）。
  4. **幂等键**：`AgentRequest` 增加 `idempotencyKey`，同一键在 TTL 内重复请求返回同一结果（流式场景返回已完成事件的回放或明确拒绝）。
- **影响面**：`stringer-api`（code / event / agent 包）、`stringer-runtime`、`stringer-server`、starter。
- **兼容策略**：全部为**加字段**语义；v1 路径继续服务。
- **验收**：一个自定义错误码在 SSE 与响应体中正常下发；旧客户端（v1 契约）不改一行仍可用。

### T9 · 可靠性与可观测：把「能跑」变成「可运维」

- **目标**：链路可追、成本可见、断线可续、过载可控。
- **关键设计**
  1. **trace 贯穿**：traceId 从 HTTP 入口贯穿到 LLM 调用、检索、工具实例与 Redis 操作，接入 OpenTelemetry，导出到既有采集端。
  2. **指标**：Micrometer 暴露 `/actuator/metrics`（或经 `/admin/metrics` 统一出口）：会话数、工具调用成功率与 P99、模型首 token 延迟、token 用量与成本、检索空结果率、中断与恢复次数。
  3. **SSE 续传**：事件加 `id`，服务端事件缓冲到 Redis Stream（TTL 5min），客户端带 `Last-Event-ID` 重连时补发。
  4. **背压替代直拒**：编排线程池改为有界队列 + 明确的 `20xxx` 码与 `Retry-After`，拒绝前先做租户级排队。
  5. **客户端韧性**：starter 加超时分级、幂等重试（仅对幂等语义的请求）、熔断与健康检查缓存。
- **影响面**：`stringer-api`（事件 id）、`stringer-runtime`（流式上下文）、`stringer-server`（观测出口）、starter。
- **兼容策略**：事件 id 与续传为增量能力，老客户端忽略 `id` 即可。
- **验收**：从一次 chat 的 traceId 能一路查到工具实例日志；断线 30s 内重连可补到遗漏事件。

---

## 5 三阶段路线图

| 阶段 | 主题 | 交付物 | 验收 | 风险 |
| --- | --- | --- | --- | --- |
| **A 地基**（不破坏行为） | T3 扩展点落地 · T8 契约补齐 · T9 的 trace/指标 · T2 的字段贯穿（默认单租户） | `api.spi` 真接口 + 默认实现；`ErrorCode` 接口化；`AgentEvent` 加 usage/node；trace 贯穿；Micrometer 出口；租户字段进 key（`default` 兜底） | 现有 example 联调全流程不改一行通过；自定义 FusionStrategy 生效 | 接口抽象过度——坚持「只抽当前有两处以上实现的点」 |
| **B 能力** | T4 模型网关 · T5 检索深化 · T7 工具生态 · T9 的可靠性 | 主备降级与熔断；rerank + 查询改写 + 评测集；MCP 双向适配；工具版本灰度；SSE 续传 + 幂等键 + 背压 | 主模型挂掉自动降级；评测集 recall 可量化提升；能调通一个开源 MCP server | MCP 适配面广，先只做 tools 能力，不做 resources/prompts |
| **C 形态** | T1 状态外置 · T2 配额与成本 · T6 编排升级 | Redis 权威状态 + 选主判死 + 分布式导入锁；租户配额与成本看板；子图 / 长任务 / 工具缓存 | 双副本滚动升级不中断；配额超限被正确拒绝；长任务可查询进度 | 状态外置引入分布式一致性复杂度——**先把写路径收敛到接口，再换实现** |

阶段之间无强绑定：如果业务压力在「质量」而非「规模」，可以 A → B 之后长期停在 B，跳过 C。

---

## 6 关键决策点（需拍板）

| # | 决策 | 选项 A | 选项 B | 建议 |
| --- | --- | --- | --- | --- |
| 1 | 形态路线 | 维持单实例，做深单机能力（简单、无分布式复杂度） | 状态外置走向多实例 / HA（复杂，但打开规模上限） | **A 阶段先把状态写路径收敛到接口**，C 阶段再决定是否真的多实例；不要现在就上分布式 |
| 2 | 租户隔离 | 逻辑隔离（字段 + key 前缀 + ES filter） | 物理隔离（每租户独立索引 / 库） | **逻辑隔离**，但索引名与 key 前缀做成可注入，保留物理隔离可能 |
| 3 | Agent 深度 | 继续强化单 Agent（工具质量、检索质量、评测闭环） | 上多 Agent 协作 / 规划器 | **先强化单 Agent**；多 Agent 放到 C 之后，等工具体系成熟 |
| 4 | 工具协议 | 自研 HTTP 协议继续演进 | 对接 MCP | **双向适配**：内部协议不变，对外提供 MCP 出入两个方向 |
| 5 | 存储可插拔 | 继续绑死 ES + Redis（省事） | SPI 化检索 / 记忆 / 检查点 | **只 SPI 化这三项**（收益最高），模型服务已天然解耦 |
| 6 | 兼容策略 | 破坏性升级，v2 一刀切 | v1 冻结 + v2 并轨 | **并轨**：事件只加字段，HTTP 加版本前缀，v1 至少保留两个版本周期 |

---

## 7 明确不做（本期）

- 可视化拖拽编排（与「代码即配置」的定位冲突，且 Dify / FastGPT 已覆盖）
- SaaS 计费与订单系统（先把成本**归集**做出来，计费留给上层）
- 多语言 SDK（用 MCP 覆盖非 Java 生态，成本低一个数量级）
- 跨集群联邦与全局调度（远超当前阶段）
- 通用多 Agent 编排框架（价值取决于工具与检索质量，不取决于编排花样）

---

## 8 登记备查（不在本期展开）

以下项**登记在案，等专项发起时再评估**，不纳入本次改造范围：

- 账号体系：单账号、无角色与权限分级、无 SSO / OIDC
- 凭证：无主动轮换与吊销列表；恢复手段仍是删文件重启
- 审计：仅日志文件，无独立审计存储与查询接口
- 工具回调：`POST /stringer/invoke` 无调用方身份校验
- 敏感字段：`@ToolParam.sensitive` / `ToolPolicy.payloadFields` 当前仅登记不生效（日志脱敏、审批报文字段过滤均未实现）
- 传输：无 TLS / mTLS 配置项

---

## 附：改造前后能力对照

| 能力 | 现在 | 改造后（A+B+C） |
| --- | --- | --- |
| 部署 | 单实例，重启丢在线表 | 多副本 + 滚动升级 |
| 租户 | 字段透传 | 数据隔离 + 配额 + 成本归集 |
| 模型 | 单端点原子替换 | 多端点路由 + 降级 + 熔断 + 灰度 |
| 检索 | 双路融合，权重硬编码 | 多路召回 + 重排 + ACL + 评测闭环 |
| 编排 | 三节点单图 | 三节点 + 子图 + 长任务 + 策略节点 |
| 工具 | 注册 / 域 / 审批 | 加版本灰度 / 配额 / MCP 双向 |
| 契约 | 封闭 enum + 无版本 | 可扩展错误码 + 版本化 + usage 事件 |
| 运维 | traceId + 指标快照 | trace 贯穿 + 指标导出 + 成本看板 + 续传 |

# Stringer 1.0 设计定稿（DESIGN-1.0）

> **性质**：这是 Stringer 的**权威 1.0 设计基线**。它取代并归档了四份早期重叠草稿
> （`SDK-REDESIGN` / `DOMAIN-REFACTOR-PLAN` / `REDESIGN-PLAN` / `DOMAIN-MODEL`），
> 把其中**已落地、已决策**的部分沉淀为定稿，把**未落地**的部分收敛为统一路线图与开放决策。
> 早期草稿仍保留在 `docs/archive/` 仅供追溯「为什么这么定」。
>
> **读法**：本文是「现在是什么 + 接下来做什么」。凡涉及「当时怎么想的、有哪些备选」，
> 请回 `docs/archive/`；凡涉及「当前契约长什么样」，请看 `SDK-CONTRACT.md` / `API.md` / `DESIGN.md`。
>
> **口径校正**：本文所有类名、注解名、契约字段均以 `v1.0-beta.1` 之后的**实际代码**为准；
> 早期草稿里 `DomainAgent` / `DomainAgentFactory` / `@DomainBinding` 等表述**未进入代码**，
> 已被 `StringerAgent` 唯一门面方案取代，下文已按真实状态书写。

---

## 0 定位与一句话结论

**定位**：Java 生态的 AI Agent 运行时中间件——服务端（`stringer-server`，承载重逻辑）+ 薄 starter（`stringer-agent-client`，调 AI）+ 工具实例 SDK（`stringer-tool-provider`，给工具）。

**已做好的骨架**（方向正确，不推翻）：
1. 图编排（`agent → 条件边(exit|auto|review) → tools → agent`，tools 回边是**无条件固定边**）；
2. 工具治理（注册 / 心跳 / 副本 / 域可见性 / 审批中断）；
3. 域可见性（工具声明域 + 对话绑定域，交集生效）；
4. 管控台落盘优先 + yaml 回落的热替换配置（`LlmModelHolder` / `InfraSettingsHolder` / `Swappable*`）。

**当前天花板**（roadmap 的动因）：权威状态在进程内存（单实例、无 HA）；治理只有「域可见性 + 审批」两维；能力深度不足（单模型、检索缺重排/文档级权限、Agent 不能规划）；扩展点是空壳（已清理，见 §4）。

**改造总纲**：**契约与扩展点（地基）→ 能力层 → 形态跃迁** 三段推进。

### 0.1 版本与兼容约定（用户裁定，2026-09-29）

> **测试版（beta）= 破坏性改造，不兼容上一版本；只有正式版（GA / 1.0）才做兼容。**

由此推出的三条执行规则（**覆盖一切旧设计里「保留兼容」的表述**）：

1. **不做旧写法兼容**：不留 deprecated 别名、不做旧键映射、不做「新旧双认」。旧写法**直接删除**。
2. **不做渐进迁移**：改造一次到位（如 D3 配置「全改」），不分两批，避免两套写法并存。
3. **兼容层推迟到 GA**：deprecated 转调、旧协议键、旧配置键等，统一在 1.0 正式版发版前按 GA 策略补齐。

> ⚠️ 因此，早期草稿 / 早期设计里出现的「v1 冻结为兼容面」「旧键保留一个版本周期」「第一阶段不改变对外行为」
> 等表述**均已作废**，以本节为准。已落地的兼容残留（如旧 `profiles` 命名与报文键）也在清理清单中。

---

## 1 域模型（核心抽象 · 定稿）

> 这是 Stringer 最核心的抽象，其他一切能力都挂在它上面。

### 1.1 定义

**域 = 一个可独立发布、可灰度、可计量、可授权的 Agent 能力单元。**
它是业务的 AI 切片，而非技术上的工具集合。判断标准：一个域应完整回答「这次对话用什么、花多少、谁负责、怎么变」。

**当前形态（v1.0-beta 之后）**：域已从「工具可见性的派生标签」升级为**带身份与来源的实体**
（`DomainRegistry`：`BUILTIN` 内置 / `MANUAL` 人工），并具备工具授权域的强制声明与对话绑定。
**尚未**升级为「十项装配 + 治理单元」（模型/提示词/知识/记忆按域装配、版本灰度等仍在路线图上，见 §3 T2/T4/T5）。

### 1.2 两条硬约束（已落地，v1.0-beta 之后）

| # | 约束 | 机制 |
| --- | --- | --- |
| 1 | **工具必须声明可用域** | `@Tool(domains=...)`；留空 → 归入内置域 `default` 并启动期 WARN；显式全域须写 `{"*"}`。`ToolDescriptor.visibleIn` 语义从「可见性」改为「授权判定」（含 `*` → 全域可用，留空 → 只属 `default`）。 |
| 2 | **对话必须绑定到指定域** | SDK 门面 `StringerAgentFactory.forDomain(domainId)` 在取得实例时绑定域；运行期服务端以 `default` 兜底（`profile` 为空不再报 `10009`）。`default` 域在启动期幂等 seed，`ToolRegistry.knownProfiles()` 恒含 `default`。 |

**效果**：域成为不可绕过的接线点——不存在「无归属的工具」与「无域的调用」，只存在「还没分类的」（计入 `default`，当作待治理债）。

### 1.3 双向声明、交集生效（已落地）

| 关系 | 谁主导 | 说明 |
| --- | --- | --- |
| 工具 → 域（我能被谁用） | **工具侧强制声明** | 工具作者最清楚适用范围与副作用，这是**授权** |
| 域 → 工具（我用谁、怎么用） | 域侧 | 在授权范围内决定用不用、是否加审批、超时与配额 |
| 域 → 模型/提示词/知识/记忆/配额 | 域侧 | 这些信息工具侧不持有 |

**生效规则（四条）**：①工具必须声明域，留空归 `default`；②域只能在授权范围内 `include`/`exclude`；③生效＝**交集**（`域 ∈ 工具声明域` 且 `工具 ∈ 域引用集合`）；④越界即失败、不静默（域引用未授权工具 → 发布期校验 fail-closed）。
**优先级**：工具声明边界 > 域 `exclude` > 域 `include`（域只能收紧，不能放宽）。

### 1.4 默认域 `default`

启动期幂等 seed（`builtin` 来源，**不可删除**）；装配初值来自现有全局配置（全局模型/提示词/知识索引/记忆策略），于是「全局配置＝`default` 域的装配」，继承链只剩一条。
通配 `{"*"}` 与留空语义不同：留空＝只属 `default`（保守）；`{"*"}`＝显式全域。`default` 使用量由平台事实呈现（清单 + 计数），**不设阈值、不告警**。

### 1.5 域 ≠ 租户（正交）

| | 域（Domain） | 租户（Tenant） |
| --- | --- | --- |
| 回答 | **用什么**（工具/模型/提示词/知识/策略） | **谁的**（数据/额度/账单） |
| 隔离 | 逻辑边界：可见性与配置 | 数据边界：key 前缀、索引 filter，必要时物理隔离 |
| 数量级 | 数十（一个场景一个域） | 数百到数千 |

**当前状态**：租户仅**透传**（`CallerContext.tenantId` / `userId`、`AgentRequest.tenantId`），
会话/记忆/检查点/ES 索引**均无租户维度**（T2 数据面未做）。判定口诀：
**数据不同 → 加租户；能力不同 → 拆域；值不同（且不影响装配）→ 租户覆盖层**。

### 1.6 入口形态（已落地 · 已校正）

| 层 | 形态 |
| --- | --- |
| **SDK（注入式，唯一推荐）** | `StringerAgent agent = factory.forDomain("customer-service")`；返回的 `StringerAgent` 上**没有** `profile` 参数，写不出「忘记传域」的代码。`ask` / `stream` / `events` / `stop` / `resume` 五法，均带 `tenantId`/`userId`。 |
| 兼容通道（保留 deprecated） | `AgentService.chat(AgentRequest)` 与 `POST /api/agent/chat` + `profile` 继续可用，文档明确为兜底通道；新接入一律走 `forDomain` 绑定形态。 |
| HTTP 资源形态 | 域成为 URL 资源：`POST /api/v1/domains/{domainId}/chat`（与旧 `/api/agent/chat` 并存）。 |

> ⚠️ **与早期草稿的偏差（重要）**：`DOMAIN-REFACTOR-PLAN` / `DOMAIN-MODEL` 曾描述新增 `DomainAgent` / `DomainAgentFactory` / 注解式 `@DomainBinding` 作为 SDK 入口。
> 实际在 P0（`0697123`）已让 **`StringerAgent` 成为唯一门面**、删除了 `DomainAgent*`，
> 且**注解式 `@DomainBinding` 未进入代码**。本文以 `StringerAgentFactory.forDomain(...)` 注入为唯一入口。
> 契约字段当前仍叫 `profile`（尚未重命名为 `domainId`），`AgentRequest` 仍用 `profile`。

### 1.7 信任模型（已决策）

域从「调用方自声明的纯标签」升级为「治理边界」分两步：
**第一步（阶段 A）** 域先承载**非对抗性治理**（配额/成本/灰度，被绕过不致命）；
**第二步** 待真有对外多租户时，再补域授权的强校验（clientId + 域授权关系）。
域仍是工具可见性的**唯一**权限维度，租户只做数据边界与配额，不叠加第二维。

---

## 2 SDK 与注解面（消费侧 · 定稿）

### 2.1 注解（已落地，P0）

| 注解 | 字段（带默认值） | 说明 |
| --- | --- | --- |
| `@Tool` | `value`（方法名）、`desc`（**必填**）、`domains`（继承类级→缺省 `default`）、`effect`（`READ`）、`approval`（`NONE`）、`approvalReason`（`""`） | 工具注册唯一主注解；`effect`=`WRITE`/`DESTRUCTIVE`，`approval`=`ALWAYS` 等 |
| `@ToolParam` | `value`（**必填**，参数说明）、`required`（`true`） | 形参或 DTO 字段上均可 |
| `@ToolAdvanced` | `example` / `allowValues` / `sensitive`（按「参数名=值」对应） | 高级可选：示例值、枚举白名单、敏感字段；可落在形参或 DTO 字段 |
| `@ToolDomains` | `value`（类级默认域） | 同类工具同属一域时写一次，方法级 `@Tool(domains=)` 可覆盖 |

**已剔除（落到默认值或移出）**：旧 `@StringerTool`/`@ToolPolicy`/`@Approval` 嵌套写法已收敛为上述平铺字段；不生效的 5 个审批字段（`approverRoles`/`timeoutSeconds`/`onTimeout`/`payloadFields`/`condition`）移出注解（等真正实现再回 `@ToolAdvanced` 体系）。

### 2.2 参数 schema（已落地，P2-⑧ · 三口径一致）

`ParamSchemaResolver` 是**共享真相**，产出 `ToolDescriptor.Param` 树（含 `items` 数组元素结构）。
服务端本地扫描（`runtime` `AnnotatedToolScanner`）、远端解析（`server` `ToolParamSchema`）、工具实例上报 JSON（`toWireSchema`）三者**共用同一棵树**，消除「同一段工具代码在两端搬迁后参数 schema 不一致」的隐患。
- 递归展开：`record`/普通类 → `object`；`List<T>`/数组 → `array`+`items`；枚举 → `string`+`enum`；`Optional<T>` → 非必填。
- 防护：嵌套深度上限 `MAX_DEPTH`（5）+ 循环引用检测；`-parameters` 缺失时顶层参数名以 Spring 绑定名为准重新落键（避免 `arg0`）。
- 示例/白名单/敏感经 `@ToolAdvanced` 按参数名套上，DTO 字段与数组元素字段同样生效。

### 2.3 调用侧三种形态（已落地，P0）

```java
StringerAgent agent = factory.forDomain("customer-service"); // 绑定一次，可复用（线程安全）
String answer      = agent.ask(sessionId, question, tenantId, userId);   // ① 只要答案（70% 场景）
Flux<String> toks  = agent.stream(sessionId, question, tenantId, userId); // ② 逐字输出
Flux<AgentEvent> ev= agent.events(sessionId, question, tenantId, userId); // ③ 工具/中断细节（高级）
```
`resume` / `stop` 同样三种形态。`ask` 内部收集 TOKEN 拼串，遇 `INTERRUPT` 抛 `ApprovalRequiredException`（带待审批工具清单）。

### 2.4 启动自检（已落地，P0-4）

`PromptToolConsistencyAudit` + `StartupSelfCheckConfiguration`（`SmartInitializingSingleton`）：提示词里出现的工具名必须在该域可见 → WARN（复用 `countMissingDescription` 模式）；**只 WARN 不阻断**，异常降级为一条 WARN。配套编写纪律（base 段不许点名具体工具）。

### 2.5 配置形态（当前 · D3 待收敛）

当前消费侧配置仍是「按内部模块分组」（约 13 项键）；早期草稿提出的「13→4 项扁平化」（`server` URL / `username` / `password` / `domains` / `tools`）**尚未实施**，列为待做（见 §5 D3）。

---

## 3 架构差距与路线图（T1–T9 · 三阶段）

> 完整论证见 `docs/archive/REDESIGN-PLAN.md`。此处只给**目标 + 当前状态**。

| 主题 | 目标 | 阶段 | 当前状态 |
| --- | --- | --- | --- |
| **T1 状态外置** | 权威状态（工具注册/实例/导入锁）外置到 Redis，可多副本/HA | C 形态 | ⬜ 未做（仍进程内） |
| **T2 租户贯穿** | 会话/记忆/检查点/检索/配额按租户隔离与归集 | A 字段 → C 配额 | ⬜ 仅透传，数据面未做 |
| **T3 扩展点落地** | 检索/记忆/检查点/模型/重排器可插拔 | A 地基 | 🟡 部分：空壳 `api.spi` 已删（P2-⑦）；`FusionStrategy` 真接口已建（P2-⑥）；其余 SPI 未建 |
| **T4 模型网关** | 多端点路由/降级/熔断/灰度；域绑模型档案 | B 能力 | ⬜ 未做（仍是单 `LlmModelHolder`，无 `ModelRouter`/`ModelProfile`；设计见 `MULTI-LLM-DESIGN.md`） |
| **T5 检索深化** | rerank / 查询改写 / 文档级 ACL / 增量更新 / 评测闭环 | B 能力 | 🟡 部分：融合已抽 `FusionStrategy`（P2-⑥）；rerank/ACL/评测未做 |
| **T6 编排升级** | review 节点泛化 / 子图 / 长任务 / 工具缓存 / 多 Agent（缓做） | C 形态 | ⬜ 未做（图仍三节点） |
| **T7 工具生态** | MCP 双向适配 / 工具版本灰度 / 工具级治理 / 协议版本协商 | B 能力 | ⬜ 未做（仍自定义 `POST /stringer/invoke`） |
| **T8 契约演进** | `ErrorCode` 接口化 / `AgentEvent` 加 usage·node / HTTP 版本化 / 幂等键 | A 地基 | ⬜ 未做（`ErrorCode` 仍是封闭 enum；事件 payload 裸 JSON） |
| **T9 可观测** | trace 贯穿 / Micrometer 指标 / SSE 续传 / 背压 / 客户端韧性 | A→B | 🟡 部分：traceId 仅工具调用透传；无指标导出、无续传、无背压 |

**三阶段**：A 地基（T3/T8 + T9 trace/指标 + T2 字段贯穿，默认单租户）→ B 能力（T4/T5/T7 + T9 可靠性）→ C 形态（T1/T2 配额成本/T6）。阶段间无强绑定；业务重在「质量」可长期停在 B。

**已决策的关键选型**：①形态——A 阶段先收敛状态写路径到接口，C 再决定是否真多实例；②租户——逻辑隔离，索引名/key 前缀做成可注入；③Agent 深度——先强化单 Agent，多 Agent 放 C 之后；④工具协议——内部协议不变，对外双向适配 MCP；⑤存储——只 SPI 化检索/记忆/检查点三项；⑥兼容——v1 冻结 + v2 并轨，事件只加字段、HTTP 加版本前缀。

---

## 4 实现状态矩阵（已落地 vs 待做 · 总览）

> 这是收敛的核心：把四份草稿里「当时计划 / 当时已做 / 当时未做」统一成一份现状账。

### 4.1 已落地（git 已提交）

| 项 | 内容 | 提交 |
| --- | --- | --- |
| **P0-1 工具注解收敛** | `@Tool`/`@ToolParam`/`@ToolAdvanced`/`@ToolDomains` 取代旧注解；扫描器双认；`SensitiveMasker`；两端 schema 对齐 | `483a62b` |
| **P0-2 StringerAgent 唯一入口** | 旧 `DomainAgent*` 删除；`autoconfig` 只暴露 `StringerAgentFactory`；示例迁移 | `0697123` |
| **P0-3 文档与代码对齐** | DESIGN/API/INSTANCE/SDK-USAGE/SDK-CONTRACT 等同步 | `24c3802` |
| **P0-4 提示词↔工具可见性启动自检** | `PromptToolConsistencyAudit` + `StartupSelfCheckConfiguration`；测试 +6（59 测试） | `0e6015f` |
| **P1-3 知识库按域** | `RetrievalScope` 线程绑域 + `DomainFilterQuery` 两路通道下推 `bool.filter`；mapping 加 `metadata.domains`(keyword)；`KnowledgeBaseService.normalizeDomains` + `upload(...,domains)`；管控台域选择器 | `2a7a906`（+`e7aaed8` docs） |
| **P2-⑥ 检索策略可插拔** | `FusionStrategy` 接口 + `DefaultFusionStrategy`（复刻原算法）；`CompositeRetriever` 只管编排；测试 +9 | `15b93e8` |
| **P2-⑦ 清理空壳 SPI** | 删除 `api.spi` 下 6 个死代码接口（零 import 零实现） | `5ccb65a` |
| **P2-⑧ 两端 schema 产出统一** | 工具实例侧复用 `ParamSchemaResolver` 的 `Param` 树 + 新增 `toWireSchema`；`ToolDescriptor.Param` 加 `items`；本地/远端/上报三口径一致；`SchemaUnificationTest` 对拍 | `7d30587` |
| **域 S1 声明强制化 + 默认域** | `domains` 留空→`default`+WARN；`{"*"}` 全域；启动期 seed `default`；`ToolDescriptor.visibleIn` 授权语义 | `DOMAIN-REFACTOR-PLAN §5.1` |
| **域 S2 域注册表 + 域清单接口** | `DomainRegistry`（BUILTIN/MANUAL）；`ToolRouter` 合并两来源；`DomainStore`(domains.json)；`GET /api/agent/domains` + `POST/DELETE /admin/domains` | 同上 |
| **域 S3 命名统一 + 管控台域管理** | `domains()` 为主、`profiles()` deprecated；管控台域空间「来源」列 + 新建/删除（仅人工域） | 同上 |

### 4.2 待做（路线图上，未实施）

| 项 | 内容 | 依赖 |
| --- | --- | --- |
| **域 S4 装配接管** | 模型/提示词/知识/记忆从全局迁入域；全局值降级为 `default` 域装配初值 | S1/S2（已具备） |
| **域 S5 域内选择与覆盖** | 域 `include`/`exclude` + 交集 + 覆盖审批/超时/配额；越界发布即失败 | S4 |
| **D3 配置扁平化** | 消费侧 14→3 项（`server` URL / `username` / `password` / `tools`，`tools` **默认关闭**；**不引入 `domains` 配置键**，域存在性由运行时服务端校验）+ 旧键映射。详见 `DESIGN-D3-CONFIG.md` | 独立 |
| **T1 / T2(数据面) / T4 / T5(后段) / T6 / T7 / T8 / T9(后段)** | 见 §3 状态列 | 各主题自述 |
| **skill 系统** | 形态（`@Skill` 注解 vs `skills/*.md`）+ 归属（域构件/跨域 `{"*"}`）— **已确认暂缓**，1.0 后定 | — |
| **项目改名** | 候选已给（Strata/Thalamus/Sigil/Rein/…），**用户明确暂缓**，选名后按 10 类影响面清单执行 | — |

### 4.3 明确不做（本期）

- 可视化拖拽编排（与「代码即配置」冲突，且 Dify/FastGPT 已覆盖）；
- SaaS 计费与订单系统（先做成本**归集**，计费留上层）；
- 多语言 SDK（用 MCP 覆盖非 Java 生态）；
- 跨集群联邦与全局调度；
- 通用多 Agent 编排框架（价值取决于工具/检索质量）；
- 域分层/基层域继承、`excludeBase()` 调用参数形态、`@DomainBinding` 注解式绑定（已被 `forDomain` 注入取代）、知识库改自动注入（会绕过域判定）；
- 多租户强校验与域授权、MCP 协议适配、多 Agent、状态外置——作为**独立主题**留在路线图，不混入域改造。

---

## 5 开放决策（仍待拍板 / 待发起）

| # | 决策 | 现状与建议 |
| --- | --- | --- |
| D3 | 配置是否一次扁平化 | 建议一次全改（旧键映射保留），否则使用者同时见两套写法 |
| S4/S5 | 装配接管与域内覆盖的优先级 | 建议先 S4（按域装配）再 S5（覆盖/审批/配额） |
| T4 | 多 LLM 落地节奏 | 设计已就绪（`MULTI-LLM-DESIGN.md`）；建议与 S4 同批做（域绑模型档案） |
| T3 | 其余 SPI 范围 | 坚持「只抽当前有两处以上实现的点」；先 `FusionStrategy` 验证模式，再扩 `MemoryStore`/`CheckpointStore`/`ModelProvider` |
| T8 | 契约版本化时机 | 建议 B 阶段开始时一并引入 `schemaVersion` + v2 前缀，避免后期破坏性升级 |
| 改名 | 项目是否改名 | **暂缓**（用户决定）。候选见记忆；选名后按 10 类影响面执行 |

---

## 附：四份草稿 → 本文的归并关系

| 原草稿 | 在本文的位置 | 处理方式 |
| --- | --- | --- |
| `REDESIGN-PLAN.md` | §0 / §3（T1–T9、三阶段、决策） | 战略路线图主体，更新状态后归档 |
| `DOMAIN-MODEL.md` | §1（域模型定稿，已校正入口形态） | 核心抽象主体，校正 `@DomainBinding`/`DomainAgent` 偏差后归档 |
| `DOMAIN-REFACTOR-PLAN.md` | §1.2–1.4 / §4.1（S1–S3 已落地记录） | 执行计划，已落地部分并入状态矩阵，剩余 S4/S5 进路线图后归档 |
| `SDK-REDESIGN.md` | §2（SDK 注解面、调用侧、schema、自检） | 消费侧设计，剔除未落地建议（D3 待做、D4 已被 P0 实现），归档 |

> 归档后，跨文档引用（`SDK-CONTRACT.md`、`MULTI-LLM-DESIGN.md`）已改为指向本文对应章节。

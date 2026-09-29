# SDK 契约表（注解与门面的完整参数）

> 用途：契约说明。**§1~§6 的形态已实现**（进度与残留缺口见 §9）；§7 的"现状对照"保留为改造前的快照，不代表当前实现。
> 来源：`SDK-REDESIGN.md` §3 与 §8（收敛后的形态）。
> 记法：**必填**列里标「是」的只有一个字段 —— 这轮收敛的目标就是"只有一个必填"。

---

## 1 `@Tool` —— 工具注册（写在方法上）

| 字段 | 类型 | 默认 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `desc` | `String` | — | **是** | 给模型的用途说明。写法建议：写清「何时调用 / 何时不要调用」，比参数描述更重要 |
| `value` | `String` | `""` | 否 | 工具名。留空取方法名；**全局唯一**，重名注册直接失败 |
| `domains` | `String[]` | `{}` | 否 | 可用域（**授权边界**）。留空＝只属于兜底域 `default`；`{"*"}`＝任何域可用（须显式）。可继承类级 `@ToolDomains` |
| `effect` | `Effect` | `READ` | 否 | `READ` / `WRITE` / `DESTRUCTIVE`。写与破坏性操作建议配 `approval` |
| `approval` | `Approval` | `NONE` | 否 | `NONE` / `ALWAYS`。当前只有这两种真正生效 |
| `approvalReason` | `String` | `""` | 否 | 展示给审批人的原因。`approval ≠ NONE` 时建议填写 |

**从注解移出（改由默认值或平台配置）**

| 原字段 | 处置 | 原因 |
| --- | --- | --- |
| `category` | 保留字段，默认 `"default"` | 只用于管理页分组，不影响运行 |
| `version` | 保留字段，默认 `"1.0.0"` | 仅登记展示，不参与路由 |
| `idempotent` | 保留字段，默认 `true` | 极少数场景才改 |
| `toModel` | 保留字段，默认 `true` | 同上 |
| `condition` / `approverRoles` / `timeoutSeconds` / `onTimeout` / `payloadFields` | **移出注解** | 当前**不生效**（源码注释写明"仅登记"）；等真正实现再加回 `@ToolAdvanced` 或平台配置 |

---

## 2 `@ToolParam` —— 参数说明（写在形参前 **或** 参数 DTO 的字段上）

| 字段 | 类型 | 默认 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `value` | `String` | `""` | 否（**强烈建议**） | 参数说明。不写能跑，但复杂参数（订单号、金额、日期）模型只能猜 |
| `name` | `String` | `""` | 否 | 参数名。留空取形参名；标在字段上时取字段名 |
| `required` | `boolean` | `true` | 否 | 是否必填。`Optional<T>` 自动视为非必填 |

**移出到 `@ToolAdvanced`**

| 原字段 | 处置 |
| --- | --- |
| `example` | → `@ToolAdvanced.example`（**按参数名对应，不按位置**） |
| `allowValues` | → `@ToolAdvanced.allowValues` |
| `sensitive` | → `@ToolAdvanced.sensitive` |

> **两个载体、一个优先级**：形参注解 **>** 字段注解（就近覆盖）。
> 1~2 个简单参数用形参；3+ 参数、被多个工具复用、或有嵌套结构 → 用 `record` DTO 的字段注解（只写一次）。
> ✅ 两个载体**都已实现**：服务端本地 Bean 走 `ParamSchemaResolver`（record 组件 + 普通类字段），工具实例侧走自己的 `schema()`；DTO 也会递归展开成嵌套 `object`。

---

## 3 `@ToolDomains` —— 类级默认域（写在类上）

| 字段 | 类型 | 默认 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `value` | `String[]` | `{}` | 否 | 该类里所有 `@Tool` 方法的默认可用域。方法级 `domains` 优先 |

---

## 4 `@ToolAdvanced` —— 高级可选（承接移出字段）

| 字段 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `example` | `String[]` | `{}` | 参数示例，**按参数名对应**（`{"orderNo=FR2024001"}` 形式），不做位置对齐 |
| `allowValues` | `String[]` | `{}` | 枚举白名单，按 `name=...` 形式给出 |
| `sensitive` | `String[]` | `{}` | 需要脱敏的参数名清单（日志 / 事件 / 审批 payload） |

> 这个注解存在的意义：**绝大多数工具不需要它**。放这里的字段都是"偶尔要写、但绝不能默认出现在每个工具上"的。

---

## 5 SDK 门面

### 5.1 入口

| 元素 | 签名 | 说明 |
| --- | --- | --- |
| `StringerAgentFactory` | `StringerAgent forDomain(String domainId)` | 唯一的域绑定入口。`domainId` 为 `null`/空白 → 兜底域 `default`；返回的实例**可缓存复用**（线程安全） |

> 落地情况（2026-09-28）：**本节形态已实现** —— `StringerAgentFactory.forDomain(domainId)` → `StringerAgent`
> （实现 `DefaultStringerAgentFactory` / `DefaultStringerAgent`，按归一化域名缓存；`StringerAutoConfiguration` 暴露 `StringerAgentFactory` Bean）。
> 中间名 `DomainAgentFactory` / `DomainAgent` 已按本节设想**合并删除**；底层的 `AgentServiceClient` 不再作为 Bean 暴露。
> `@DomainBinding` **从未落地**，也不再计划做（与 `forDomain` 语义重复）。

### 5.2 `StringerAgent` 方法

| 方法 | 参数 | 返回 | 说明 | 使用占比预估 |
| --- | --- | --- | --- | --- |
| `ask` | `String sessionId, String question` | `String` | 同步取最终答案。遇审批中断抛 `ApprovalRequiredException` | **~70%** |
| `ask` | `String sessionId, String question, String tenantId, String userId` | `String` | 同上，并声明归属（多租户计量/审计） | — |
| `stream` | `String sessionId, String question` | `Flux<String>` | 逐字输出（只含 TOKEN 内容） | ~25% |
| `stream` | `String sessionId, String question, String tenantId, String userId` | `Flux<String>` | 同上，带归属 | — |
| `events` | `String sessionId, String question` | `Flux<AgentEvent>` | 完整事件（工具调用、审批、错误、耗时） | ~5% |
| `resume` | `String sessionId, boolean approved` | `Flux<AgentEvent>` | 审批恢复。**域须与中断时一致**，否则 30002 | — |
| `stop` | `String sessionId` | `boolean` | 请求停止（幂等） | — |
| `domainId` | — | `String` | 返回本实例绑定的域，**永不为空** | — |

**约定**：`sessionId` 由调用方生成并保持稳定（同一会话复用同一个）；它是记忆与检查点的唯一键。

### 5.3 `ApprovalRequiredException`（`ask` 与 `stream` 都会抛）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `sessionId` | `String` | 哪个会话被挂起 |
| `domainId` | `String` | 挂起时所处的域（`resume` 必须带同一个） |
| `tools` | `List<ToolCall>` | 待审批的工具调用清单：`name` / `arguments` / `requiresApproval` |
| `traceId` | `String` | 排障用 |

> 宿主拿到它就能直接弹确认框；用户点完 → `resume(sessionId, true/false)`。

### 5.4 `AgentEvent`（只有 `events` / `resume` 用得到）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `type` | `AgentEventType` | `TOKEN` / `TOOL_CALL` / `TOOL_RESULT` / `INTERRUPT` / `STOPPED` / `ERROR` / `DONE` |
| `sessionId` | `String` | 会话 |
| `content` | `String` | 文本内容（`TOKEN` / 错误文案） |
| `payload` | `String` | 结构化载荷（JSON 字符串；`INTERRUPT` 时是待审批工具清单） |
| `code` / `codeName` | `Integer` / `String` | 仅 `ERROR`，前端应以 `codeName` 分支而非数字 |
| `traceId` | `String` | 仅 `ERROR` |
| `timestamp` | `long` | 事件生成时间 |

---

## 6 配置项（SDK 运行参数）

| 键 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `stringer.server` | `String`（URL） | — | 服务端地址，一个 URL 取代 `host` + `port` |
| `stringer.username` / `password` | `String` | — | 接入账号 |
| `stringer.domains` | `String[]` | `{}` | 启动期校验这些域存在（替代 `@DomainBinding` 扫描） |
| `stringer.tools` | `boolean` | `false` | 是否把本进程的 `@Tool` 方法注册给服务端 |
| `stringer.tools.domains` | `String[]` | `{}` | 本应用所有工具的**默认可用域**（替代逐个注解写 `domains`） |

**不要求填的（有默认值，只在需要时改）**：健康检查/连接/读取超时、实例 id（自动生成）、端点（自动推导）、心跳周期、退避上限、请求超时。

---

## 7 与现状的差异（哪些已有、哪些要新增）

| 元素 | 现状 | 目标 |
| --- | --- | --- |
| `@StringerTool` | ✅ 已有，**8 个字段** | → `@Tool`，**1 个必填 + 5 个可选** |
| `@ToolParam` | ⚠️ 已有（6 字段），**只读形参** | → 3 字段；**同时支持字段载体** |
| `@ToolPolicy` + `@Approval` | ✅ 已有（7 字段，5 个不生效） | → 平铺进 `@Tool` 的 `approval` + `approvalReason` |
| `@ToolDomains` | ❌ 无 | 新增（类级默认域） |
| `@ToolAdvanced` | ❌ 无 | 新增（承接 `example` / `allowValues` / `sensitive`） |
| `Effect` 枚举 | ✅ `StringerTool.SideEffect` | 更名为 `Effect`（值不变） |
| `StringerAgent` | ❌ 无 | 新增门面（`ask` / `stream` / `events` / `resume` / `stop`） |
| `StringerAgentFactory` | ❌ 无（曾用 `DomainAgentFactory`） | ✅ 已落地：合并并更名，`forDomain` 是唯一域绑定入口 |
| `@DomainBinding` | ❌ **从未落地**（设计中的注解式入口） | **不做** —— 与注入式 `forDomain` 语义重复，代理/继承/类级优先级的复杂度不划算 |
| `ApprovalRequiredException` | ❌ 无（只能自己过滤 `INTERRUPT` 事件） | 新增 |
| `AgentService` / `AgentRequest` / `CallerContext` | ✅ 已有 | 降为**内部/HTTP 契约**：服务端实现 `AgentService`，SDK 内部持有远程实现；`AgentRequest`/`CallerContext` 只在裸 HTTP 与内核层出现，不再是消费侧入口 |
| `DomainAgent` / `DomainAgentFactory` | ✅ 曾补齐实现，随后**已删除** | 能力并入 `StringerAgent` / `StringerAgentFactory`（一个入口，不留中间名） |

---

## 8 这份表里最该先确认的三件事

| # | 问题 | 我的建议 |
| --- | --- | --- |
| 1 | `desc` 是唯一必填 —— 是否接受 | 接受。它是模型判断"何时调用"的唯一依据；缺了工具等于不可用 |
| 2 | `@ToolParam` 的字段载体（DTO）是否本轮就支持 | 支持。但**必须先把两端扫描器统一**（服务端目前不支持，见 `SDK-REDESIGN.md` §9） |
| 3 | 移出的 5 个审批字段 | 移出。它们在当前实现里**不生效**，留着等于向使用者承诺不存在的能力 |

---

## 9 实现进度（2026-09-28）

### 9.1 已实现

| 元素 | 状态 |
| --- | --- |
| `@Tool` | ✅ `desc` 唯一必填 + `value` / `domains` / `effect` / `approval` / `approvalReason` |
| `@ToolDomains` | ✅ 类级默认域，方法级 `domains` 优先 |
| `@ToolAdvanced` | ✅ **已接入**：`example` → `Param.example` + 追加进参数说明；`allowValues` → schema 的 `enum`；`sensitive` → `Param.sensitive` + 工具调用事件与审批 payload 的值掩码。一律 `参数名=值`，名字可命中 DTO 展开出的字段名 |
| `@ToolParam` | ✅ 只剩 `value` / `name` / `required`；`description` / `example` / `allowValues` / `sensitive` 四个废弃别名**已删除** |
| `ToolDescriptor.Param` | ✅ 增加 `properties`（`type=object` 的子字段）；保留七参构造，既有调用点无需改动 |
| `ParamSchemaResolver` | ✅ 放在 `stringer-api`、两端共用：DTO 递归展开、深度上限 5、循环引用检测、形参/字段两种载体、UUID/Temporal/Date 识别；`ParamOverrides`（`@ToolAdvanced` 的索引视图）也是公开共用的 |
| 服务端扫描器 | ✅ 只认 `@Tool`；参数走共用解析器；**DTO 从"退化成 string"改为展开成 object** |
| 工具实例扫描器 | ✅ 只认 `@Tool`；`@ToolParam.value()` 生效；类级 `@ToolDomains` 生效；`@ToolAdvanced` 随 schema 上报（含 `x-sensitive`） |
| 远端工具描述符 | ✅ `ToolParamSchema#toParams` 改为**递归**，远端不再是"只有顶层、嵌套被拍平" |
| 敏感掩码 | ✅ 新增 `SensitiveMasker`（按名递归掩码，只改给人看的文本） |
| **唯一入口 `StringerAgent`** | ✅ **已实现**：`StringerAgentFactory.forDomain(domainId)` → `StringerAgent`（`ask` / `ask(tenant,user)` / `stream` / `events` / `resume` / `stop` / `domainId`）。三种消费方式共用同一条事件流；`ask`/`stream` 遇审批抛 `ApprovalRequiredException`、遇 `ERROR` 抛携带 `ErrorCode` 的 `StringerException`。`DomainAgent*` 已删除，`AgentService` 降为内部通道 |
| **提示词 ↔ 工具可见性自检** | ✅ 已实现：`StartupSelfCheckConfiguration` + `PromptToolConsistencyAudit`（启动期 `SmartInitializingSingleton`，只 WARN 不阻断）。挡"提示词点名了某工具、但它在当前域不可见"——模型被告知有能力却调不到，且不抛异常。按词边界只认工具名，业务语言描述能力刻意漏报 |
| **知识库按域** | ✅ 已实现：`metadata.domains`（keyword）+ 上传时声明（语义与 `@Tool(domains)` 同构）+ 过滤下推到两路检索通道（不能放融合后）+ `RetrievalScope` 线程绑域 + 存量索引启动补字段；历史文档无该字段按全域可见 |
| 测试 | ✅ 全量 71 个测试通过（含 `DefaultStringerAgentTest` 8 个、`SensitiveMaskerTest`、`ToolAnnotationScanTest`、`AnnotatedToolScannerTest`、`ToolParamSchemaTest`、`PromptToolConsistencyAuditTest` 6 个、`KnowledgeBaseServiceTest` 5 个、`DomainFilterQueryTest` 4 个、`RetrievalScopeTest` 3 个） |

### 9.2 顺带修掉的两个真实缺陷

1. 服务端本地 Bean 的 **DTO / record 参数曾退化成 `type: string`** —— 模型看到的是字符串参数，产出的也是字符串，反序列化到 DTO 必然失败，**工具永远拿不到参数**。现已展开为嵌套 `object`。
2. **远端工具的嵌套参数曾被拍平**：`ToolParamSchema#toParams` 只读顶层，于是"同一段工具代码"在本地部署与远端实例两种形态下描述符不一致（远端看不到 DTO 子字段，也就看不到声明在子字段上的示例/白名单/敏感）。现已递归。

### 9.3 尚未实现

| 元素 | 说明 |
| --- | --- |
| 配置项扁平化（`server` URL 等） | 下一步 |
| 启动自检清单（其余项） | 提示词↔工具可见性、知识库按域均已落地（§9.1）；待补：skill 声明的工具是否存在、标了域却无人使用的文档等 |
| skill（能力包 = 指令 + 工具） | 已确认**暂缓**，形态待 1.0 之后再定（初定 `@Skill` 注解，域声明复用现有三规则） |
| **两端 schema 生成的完全统一** | ⚠️ 仍在做：两端已共用同一套注解、同一份 `ParamOverrides`（示例/白名单/敏感）与同一段说明文本，但**参数树的产出仍是两套代码**（服务端：`ParamSchemaResolver` → langchain4j Schema；工具实例：自有 `schema()` → 上报 JSON）。要彻底统一需重写工具实例侧的 schema 生成，属独立改动项 |
| `sensitive` 的覆盖面 | 目前只掩码工具调用事件与审批 payload 两处；**日志与工具自身回显**未覆盖（工具实例侧不应把敏感值写进返回值/异常） |

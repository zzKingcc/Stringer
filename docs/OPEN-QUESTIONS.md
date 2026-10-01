# Stringer 待确认清单（Step 7）

> 生成时间：2026-09-30
> 来源：本目录 `CONSISTENCY-ANALYSIS.md` 中标注「属设计决策 / 属取舍」的条目，外加若干需在动手改造前先定调的契约与形态问题。
> 用途：在 Step 2～6（代码清理 / 逻辑排查 / 补测 / 示例工程 / 文档重写）**动手之前**，把"我不确定该选哪条路"的点集中列给你批阅。每一条都给了「问题 → 可选方案（含利弊）→ 推荐」。请你逐条确认或改选，确认后我再落到代码与文档。
> **决策约束**：本项目已裁定 **beta = 破坏性改造，不兼容上一版本**（见长期约定）。因此以下方案中"会改对外行为 / 改报文 / 改字段语义"的，在当前 beta 期是被允许的，无需为兼容旧写法留后路。

---

## 0 决策总览

| # | 主题 | 推荐 | 影响面 | 是否对外行为 |
|---|---|---|---|---|
| Q1 | `profile` 空值：回落 `default` 还是必填拒绝 | **回落真正生效（A）** | `AgentRequest` Builder / 编排层 / 3 份文档 | 是（HTTP 400 → 正常对话） |
| Q2 | `ModelResolver` 契约注释与实现矛盾（null 回落） | **改注释 + 加空值守卫（A）** | 契约注释 / `resolveModel` | 否（防御性） |
| Q3 | 知识库"未声明域"文档的可见性语义 | **仅对 `default` 域可见（A）** | `DomainFilterQuery` / 注释 / 3 份文档 | 是（越权可见→不可见） |
| Q4 | 回答引用段数被硬编码 5 截断 | **抽成显式配置（A）** | `KnowledgeSearchService` / `DESIGN` | 否（新增配置项） |
| Q5 | PDF 摄取策略已注册未接线 | **显式拒绝 `pdf` 并标注预留（B）** | `KnowledgeBaseService` / 策略链 | 是（静默垃圾→明确报错） |
| Q6 | 账号未初始化时全局免鉴权 | **收窄到仅 init+health（A）** | `CredentialAuthInterceptor` / 文档 | 是（高危端点不再裸奔） |
| Q7 | resume 域一致性校验 fail-open | **持久化校验记录到 checkpoint（B）** | `AgentOrchestrationService` | 是（防提示词/工具错配） |
| Q8 | `80xxx` 等错误码死码 | **重名真正抛 `TOOL_DUPLICATE`，其余删（B+A）** | `ErrorCode` / 工具注册链路 | 是（报文 code 变化） |
| Q9 | `stop()` 吞异常合并 `false` | **区分网络错误与已停止（A）** | `AgentServiceClient` | 否（返回更精确） |
| Q10 | 反向调用 `tenantId/userId` 被丢弃 | **短期诚实标注，长期透传（B→A）** | `ToolInstanceClient` / `ToolHandler` | 是（多租户隔离能力） |
| Q11 | `idempotent` 实际驱动 `retryable` | **新增 `retryable`，幂等回归字面（A）** | `ToolInstanceClient` / 契约 | 是（字段语义） |
| Q12 | 示例工程范围与形态 | **重建 `stringer-example`，3-4 个域独立可运行 main（A）** | 新模块 / `SDK-USAGE` | 否（新增示例） |
| Q13 | P0-1 配置键修复是否保留兼容别名 | **不保留，直接改写（A）** | 5+ 份文档 | 否（纯文档） |
| Q14 | 模块职责描述与实际不符 | **文档对齐代码（A）** | `DESIGN.md` 多处 | 否（纯文档） |

---

## Q1 · `profile` 空值：回落 `default` 还是必填拒绝

**现状（三方打架）**
- `AgentRequest.Builder`（`:166-168`）对 `profile == null/blank` **直接抛 `IllegalArgumentException`**，HTTP 入口得到 `400 / 40000`。
- 但编排层 `AgentOrchestrationService.java:451-459` **确实实现了**"空 → 回落兜底域 `default`"。
- 文档 `API.md:84` 写"`profile` 必填＝否，为空回落兜底域 `default`"；`DESIGN.md:90` 写"缺失/空 → 回落 `default` 不报错"。
- `ServerAgentController.java:68-69` 的过时注释又写"漏传会被编排层**直接拒绝**"。

**问题**：文档承诺的回落在到达编排层前就被 Builder 拦死，裸 HTTP 永远拿 400，回落根本走不到；而"10009 不触发"虽对，成因完全不是文档写的那个。三种理解并存，接入方无所适从。

**可选方案**
- **A（推荐）· 让回落真正生效**：去掉 `AgentRequest.Builder` 的必填校验，空 `profile` 一路透传到编排层，由 `Domains.DEFAULT` 兜底。与"兜底域恒在"的既有设计一致，`GET /api/agent/domains` 等也依赖 `default` 恒在。文档维持"可为空"。
- **B · 改为必填**：保留 Builder 校验，文档改写为"`profile` 必填"；回落只发生在 SDK 侧 `forDomain(null)` 这种内部构造路径。对外契约更严格，但推翻 DESIGN 的"兜底域"叙事。

**推荐 A**：改动最小、与既定"兜底域恒在"设计自洽，且 `default` 域本就为兜底而存在。

---

## Q2 · `ModelResolver` 契约注释与实现矛盾（null 回落）

**现状**：`ModelResolver` 的契约注释承诺"解析不到返回 `null`，内核会回落默认模型"，但 `AgentOrchestrationService.resolveModel`（`:299-305`）**无 null 回落**，拿到 `null` 直接 `.chat()`。当前因抛异常而未炸，但任何按注释实现的第三方解析器会 NPE。

**可选方案**
- **A（推荐）· 双修**：把注释改为"必须返回非空，解析失败应抛 `NotConfiguredException`"；并在 `resolveModel` 入口加空值守卫，空则抛明确异常而非 NPE。
- **B · 只改注释**：声明"返回 null 即视为配置缺失，调用方负责处理"，不动代码（依赖调用方自觉，仍有 NPE 风险）。

**推荐 A**：防御性编程，对外契约明确，零行为副作用。

---

## Q3 · 知识库"未声明域"文档的可见性语义

**现状（越权 + 注释自相矛盾）**：`DomainFilterQuery.java:56-61` 用 `should(terms([域,"*"])) OR should(must_not(exists(domains)))`，导致**没有 `domains` 字段的文档对全域可见**；但 `:58-59` 注释与 `PITFALLS.md:145` / `DESIGN.md:230` / `INTERVIEW-PREP.md:774` 都说"未声明＝只属 `default`"。于是 `admin` 域能搜到没标域的文档（越权），而显式标 `["default"]` 的反而在 `admin` 查不到——两种"默认"互斥且不报错。

**可选方案**
- **A（推荐）· 仅 `default` 可见**：`build()` 改为"仅当当前域是 `default` 时才加 `must_not exists` 分支"（或把 `Domains.DEFAULT` 加进 `visibleValues` 并删该分支）。与文档一致，消除越权。
- **B · 全域可见（认代码现状）**：把文档改成"未声明域＝全域可见"，并明确这是有意为之。简单但放弃域隔离语义，风险高。
- **C · 谁都不可见**：未声明域的文档默认不可检索，必须显式标注。最严格，但存量未标注文档会"消失"。

**推荐 A**：安全语义正确，且与 3 份文档既有描述对齐，只改代码 + 注释。

---

## Q4 · 回答引用段数被硬编码 5 截断

**现状**：`KnowledgeSearchService.java:44` `Math.min(contents.size(), 5)` 把融合产出的 10 条（`top-n`=10）截断到 5 喂给模型；`DESIGN.md:242/446`、`INTERVIEW-PREP.md:741` 只提"`top-n`"，未区分"融合 TopN"与"回答引用条数"。配 `top-n=20` 也只拿 5 条。

**可选方案**
- **A（推荐）· 抽成显式配置**：新增 `stringer.retrieval.answer-max-segments`（默认 5），文档写明"融合 TopN ≠ 回答引用条数"。消除"配了不生效"的迷惑。
- **B · 直接复用 `top-n`**：回答引用条数 = 融合 TopN。减少配置项，但失去独立调参能力。
- **C · 保持硬编码 5**：只补文档说明。改动最小，但仍是隐藏魔法数。

**推荐 A**：显式化代价低，且解开一个长期误导点。

---

## Q5 · PDF 摄取策略已注册未接线

**现状**：`DocumentProcessStrategyFactory` 注册了 `PdfDocumentProcessStrategy`，但 `KnowledgeBaseService.java:345` 把上传字节**一律按 UTF-8 解码**，全仓 `pom.xml` 无 `pdfbox`/`tika`（server 的 pom 注释"刻意不引 Tika 全家桶"）。运维把 `pdf` 加进 `stringer.rag.allowed-extensions` 后，PDF 原始字节被当 UTF-8 切片入库，**静默产生垃圾知识**；而策略的存在会让人以为支持 PDF。

**可选方案**
- **A · 真正接线（长期）**：引入 `pdfbox`（轻量、只解 PDF）真正解析文本，从策略链移除"预留"标注。功能完整但引入依赖。
- **B（推荐·短期）· 显式拒绝并标注预留**：在 `requireSupported` 显式拒绝 `pdf` 并提示"当前版本不支持 PDF"，策略链保留但标注"预留未接线"；从 `INTERVIEW-PREP.md` 撤下"现有策略"的误导表述。
- **C · 保持现状**：风险最高，静默垃圾入库且难排查，不建议。

**推荐 B（当前 beta 期）**：安全优先，避免灌入乱码；A 作为后续独立任务。

---

## Q6 · 账号未初始化时全局免鉴权放行

**现状**：`CredentialAuthInterceptor.java:65-71`，当 `accounts.json` 不存在（未初始化）时，在凭证校验前短路 `return true`，**所有受保护端点整体免鉴权**。文档只写了"开放 `/admin/init`"，但实际 `POST /admin/password`、`DELETE /admin/settings/{kind}` 等高危动作在未初始化时也无需凭证。

**矛盾点**：未初始化时用户尚无密码，确实无法提供凭证——所以"初始化引导态"免鉴权有其必要；但当前是**全放行**，blast radius 过大。

**可选方案**
- **A（推荐）· 收窄到引导态**：未初始化时仅放行 `/admin/init`、`/health` 及启动引导必需的极少数端点，其余一律 `401`。既允许首次部署，又避免高危操作裸奔。
- **B · 保持全放行**：最简，但 `DELETE /admin/settings` 等高危动作在"还没设密码"时就可被任意调用。
- **C · 引入临时引导令牌**：init 阶段发一次性引导 token，所有引导端点均需该 token。最安全但改造量大。

**推荐 A**：在"可部署"与"不被裸奔"之间取平衡，改动适中。

---

## Q7 · resume 域一致性校验 fail-open

**现状**：`AgentOrchestrationService.java:733-743`，resume 时校验"当前域与创建会话时的域一致"，但 `interruptedProfiles`（记录会话原始域）是**纯内存 Map**（`:112`）。服务端重启或 checkpoint 被清理时记录缺失，整段校验**跳过**（fail-open）：可用任意域 resume，得到"旧域提示词 + 新域工具集"的危险错配。

**可选方案**
- **A · fail-closed（最小）**：记录缺失时直接拒绝 resume。安全但重启后旧会话无法续接。
- **B（推荐）· 持久化校验记录**：把会话原始域随 checkpoint 落 Redis（与现有 `stringer:graph:checkpoint:` 体系一致），重启后仍可校验。正确性最佳。
- **C · 保持 fail-open**：当前行为，风险在于跨域错配。

**推荐 B**：与已有 checkpoint 持久化体系同源，根治重启失效；若嫌重，A 是兜底下限。

---

## Q8 · `80xxx` 等错误码死码

**现状**：`ErrorCode` 共 48 个枚举，其中 `80000 TOOL_ERROR` / `80002 TOOL_DUPLICATE` / `80003 TOOL_EXECUTION_FAILED` **从不抛出**；工具重名实际抛 `IllegalStateException` → 被映射成 `50000 SYSTEM_ERROR`（`retryable=true`）。前端会为这些码写永不触发的分支，而重名场景拿到 `50000+retryable=true` 会**诱导无限重发一个注定失败的请求**。另有 `20000/20001/20003`（限流三连）、`70000`（chat memory）也从不抛出。

**可选方案**
- **A · 删纯死码**：删除 `20000/20001/20003/70000/80000/80003` 等永不被抛出的枚举，前端不再为它们写分支。
- **B（推荐·对重名）· 真正接线 `TOOL_DUPLICATE`**：工具重名改为抛 `80002 TOOL_DUPLICATE`（`retryable=false`），让调用方明确"这是不可重试的配置错误"，而非拿到 `50000` 死循环重发。
- **C · 全保留**：维持现状，文档标注死码。最省事但误导客户端。

**推荐 B + A 组合**：重名这类有真实语义的码接线（B），纯无出口的码删除（A）。

---

## Q9 · `AgentServiceClient.stop()` 吞异常合并 `false`

**现状**：`AgentServiceClient.java:101-107` `stop()` 捕获所有异常后返回 `false`，把"网络不可达"与"已停止"合并；`INSTANCE.md:300` 示例还没订阅返回的冷流 `Flux`，照抄会"点了批准没反应"。

**可选方案**
- **A（推荐）· 区分结果**：返回带原因的 `Result`（如 `StopResult{stopped, error}`），网络错误显式暴露；并修文档示例，强调必须订阅 `Flux`。
- **B · 保持 boolean 但异常时记录并上抛**：至少日志区分网络错误与正常停止。
- **C · 保持现状**：改动最小，但运维无法区分"真停了"和"网络挂了"。

**推荐 A**：`Flux` 冷流订阅问题本就要修文档，顺带把返回语义做精确。

---

## Q10 · 反向调用 `tenantId/userId` 被丢弃

**现状**：`ToolInstanceClient.java:275-296` 宣称下发 `tenantId`/`userId`/`traceId` 作为"调用上下文"，但 `ToolHandler.handle` 只有 `arguments` 入参，三者**完全丢弃**。多租户工具无法据此隔离。

**可选方案**
- **A（长期）· 真正透传**：把 `tenantId/userId/traceId` 注入 `ToolHandler` 上下文（如 `ToolContext`），工具侧可读。
- **B（推荐·短期）· 诚实标注预留**：文档明确"当前版本反向调用不传递租户上下文，多租户隔离需自行在 arguments 内编码"；保留字段但注明"预留未接线"。
- **C · 移除宣称**：从契约与文档删掉这三个字段，不再承诺。

**推荐 B→A 路线**：beta 期先诚实（B），避免客户依赖不存在的能力；A 列入后续多租户专项。

---

## Q11 · `idempotent` 实际驱动 `retryable`

**现状**：`ToolInstanceClient.java:307` 与 `INSTANCE.md:458` 文档说 `idempotent` "当前只登记展示，不参与重试判定"，但实际它是回给服务端 `retryable` 的**唯一依据**，且与自身注释矛盾（`API.md` 也说"不参与"）。

**可选方案**
- **A（推荐）· 理清语义**：新增独立 `retryable` 字段；`idempotent` 回归字面"幂等"含义（幂等=false → `retryable` 强制 false）。客户端契约更清晰。
- **B · 保留现状但修注释/文档**：明确"`idempotent=false` 即 `retryable=false`"，删掉"不参与"的误导性表述。最小改动。
- **C · 仅展示**：真把 `idempotent` 降级为纯展示、新增 `retryable` 独立控制，但不联动。

**推荐 A**：beta 期正是理清字段语义的窗口，避免长期背着"名不副实"的字段。

---

## Q12 · 示例工程范围与形态

**前置依赖**：Q13（P0-1 配置键修好）与 `SDK-USAGE.md` 示例修正（见 `CONSISTENCY-ANALYSIS.md` §5.4）是前置；当前 `stringer-example` 已从根 `pom.xml` 的 `<modules>` 移除，且 `README.en.md` 仍给它发启动命令（悬空引用）。

**可选方案**
- **A（推荐）· 重建 `stringer-example` 模块**：3-4 个业务域（如：客服问答域、知识检索域、工具编排域、审批流域），每个一个**独立可运行 `main`**，yaml 用真实配置键（`stringer.server` URL + `stringer.username/password` + `stringer.tools`）。作为用法参考与冒烟样例。
- **B · 单域全功能示例**：只做一个覆盖最全能力的对话示例，减少维护面。
- **C · 不建独立模块，只补 `SDK-USAGE.md` 片段**：最轻，但缺可运行性验证。

**推荐 A**：示例工程是 Step 5 交付物，独立模块 + 多域可运行样例最能体现"不同业务域典型场景"。

---

## Q13 · P0-1 配置键修复是否保留兼容别名

**背景**：消费侧配置键在 5+ 份文档整体写错（见 `CONSISTENCY-ANALYSIS.md` P0-1），真实是 `stringer.server`（URL）/ `stringer.username` / `stringer.password` / `stringer.tools`，而非文档的 `stringer.server.host/port`、`stringer.tool-instance.enabled`。

**可选方案**
- **A（推荐）· 不保留兼容别名，直接改写文档**：beta 期本就不兼容，按"代码为准"原则把文档一次性改对，不引入 `host/port` 兼容绑定（Spring 也难把子键绑到 `String`）。
- **B · 代码兼容旧键**：在 `StringerProperties` 加 `server.host/port` 解析并拼回 URL。增加代码复杂度，与"beta 不兼容"裁定相悖。

**推荐 A**：纯文档修正，零代码负担，符合 beta 裁定。

---

## Q14 · 模块职责描述与实际不符

**现状（文档 vs 代码）**：
- `DomainRegistry` 实际在 `stringer-runtime`，`DESIGN.md:34` 写在 `stringer-domain` 职责里；
- sdk-core 承载的是 `StringerProperties`（非文档写的 `ServerProperties`），`ClientProperties` 实际在 agent-client；
- `tool-provider` 实际依赖 `api` + `sdk-core`，文档称"不依赖任何其它 Stringer 模块"；
- `agent-client` 实际**不**聚合 `tool-provider`（pom 无该依赖，描述明写工具注册在独立模块），文档称"引入即同时具备两种能力"。

**可选方案**
- **A（推荐）· 文档对齐代码**：代码是既定事实，重构模块边界成本高且无收益，按实际依赖重写 `DESIGN.md` 模块职责段。
- **B · 代码重构对齐文档**：把模块边界改成文档描述的样子。工作量大、风险高、收益低。

**推荐 A**：纯文档修正，与本项目"代码为主"的一贯原则一致。

---

## 附：我建议的批阅后执行顺序

1. 你确认 Q1～Q14（可逐条改选）。
2. **Step 2 代码清理**：先落 Q2/Q8/Q9 这类无对外行为的小修 + §6.1 死代码 + §6.2 重复实现收敛。
3. **Step 3 逻辑排查**：落地 Q1/Q3/Q5/Q6/Q7/Q10/Q11 这些有行为变化的决策。
4. **Step 4 功能验证**：按 `CONSISTENCY-ANALYSIS.md` §6.4 补测（域过滤越权、topN 边界、timeout 兜底、级联删除、`requiresApproval`）。
5. **Step 5 示例工程**：Q12 + Q13 修好的配置键。
6. **Step 6 文档重写**：Q13/Q14 等纯文档项 + 全量文档按最终实现重写（架构图/流程图/时序图/对比表）。

> 标注「属设计决策」的条目即以上 Q1～Q11；Q12～Q14 为形态/文档类前置决策。未列入此清单的 P0/P1/P2 项（如 P0-4 已并入 Q4、P0-3 已并入 Q3）均已在此归集，无遗漏。

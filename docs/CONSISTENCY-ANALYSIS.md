# Stringer 代码 ↔ 文档 一致性审计报告

> 生成时间：2026-09-30
> 审计范围：全仓 8 个模块（`api` / `common` / `domain` / `infrastructure` / `runtime` / `server` / `sdk-core` / `agent-client` / `tool-provider`）+ 管控台静态页 + 全部 13 份文档
> 方法：逐模块读取源码与文档交叉比对，标注双侧 `file:line` 证据。标注 **✅已复核** 的条目是本轮由主 agent 亲自二次验证。
> 本文件对应「7 步计划」的**第 1 步产出**，是第 2～6 步的输入依据。

---

## 1 结论摘要

| 分类 | 条数 | 说明 |
| --- | --- | --- |
| **P0 阻断/危险** | 5 | 照文档操作会启动失败、调用被拒、或产生越权可见/静默数据污染 |
| **P1 缺失/误导** | 14 | 接口或行为代码有但文档无，或文档描述会让接入方写错代码 |
| **P2 表述过时** | 12 | 名称、归属、版本、备注类问题，不影响运行但误导理解 |
| **附带发现** | 17 | 死代码、无调用方方法、重复实现、可疑 bug（注入第 2 步清理与第 4 步补测） |

**最需要优先处理的一点**：消费侧配置在 5 份文档里整体写错，且**与同仓库另两份文档自相矛盾** —— 这是纯粹的改造残留，修起来是纯文档改动，收益最大。

---

## 2 P0 阻断/危险（建议优先修复）

### P0-1 ✅已复核 消费侧配置键整体失效，照文档抄会启动失败

- **代码证据**：`stringer-sdk-core/.../sdkcore/config/StringerProperties.java:23`（`@ConfigurationProperties(prefix = "stringer")`）、`:27`（`private String server = "http://localhost:9527"` —— **单个 URL 字符串**）、`:30`/`:33`（`username`/`password` 是**顶层**字段，不在 `server` 组下）、`:39`（`tools`，工具实例总开关）
- **工具实例开关证据**：`stringer-tool-provider/.../spring/ToolInstanceAutoConfiguration.java:28`（`@ConditionalOnProperty(prefix = "stringer", name = "tools", havingValue = "true")`）；`ToolInstanceProperties.java:13-46` **无 `enabled` 字段**
- **错误文档证据**：`docs/DESIGN.md:456-457`、`docs/DESIGN.md:459`、`docs/API.md:349/416`、`docs/INSTANCE.md:215-218/335-347`、`README.md:140-146/188`、`README.en.md:140-146/188`、`docs/INTERVIEW-PREP.md:856/858`
- **问题**：
  1. 文档让写 `stringer.server.host/port` —— 真实 `server` 是 `String`，Spring 无法把子键绑到 String，**应用直接起不来**；
  2. 文档让写 `stringer.server.username/password` —— 真实是 `stringer.username` / `stringer.password`；
  3. 文档让写 `stringer.tool-instance.enabled=true` —— 该键不存在，工具实例**静默完全不生效**（条件不满足直接跳过装配，无报错）。
- **同仓库反例（自相矛盾）**：`docs/DESIGN-1.0.md:196-198` 与 `docs/SDK-CONTRACT.md:131-134` 写的才是代码真实形态。同一套文档三种写法。
- **建议**：以 `StringerProperties` 为准，批量重写上述 5 份文档配置段；`DESIGN.md` §15 增补 `stringer.server` / `stringer.username` / `stringer.password` / `stringer.tools`，删除 5 个失效键。

### P0-2 ✅已复核 `profile` 空值：文档承诺回落 `default`，代码实际直接拒绝

- **代码证据**：`stringer-api/.../api/agent/AgentRequest.java:166-168`（`if (profile == null || profile.isBlank()) throw new IllegalArgumentException("profile 不能为空…")`）；`stringer-server/.../controller/ServerAgentController.java:65-70`（HTTP 入口用 Builder 重建请求）
- **文档证据**：`docs/API.md:84`（`profile` 必填＝**否**，"为空时回落兜底域 `default`"）；`docs/DESIGN.md:90`（"缺失/空 → 回落兜底域 `default`，不报错"）；`docs/API.md:301`（10009 注"实际不触发"）
- **问题**：编排层确实实现了回落（`AgentOrchestrationService.java:451-459`），但请求**在到达编排层之前**就被 Builder 拦下，裸 HTTP 得到 `400 / 40000 INVALID_PARAMETER`。文档承诺的回落根本走不到；而结论"10009 不触发"虽对，成因完全不是文档写的那个。
- **更麻烦的是三方打架**：`ServerAgentController.java:68-69` 的注释写"漏传会被编排层判为未携带 profile，**直接拒绝**"（已过时），`DESIGN.md:90` 写"回落不报错"。代码、注释、设计文档三种理解。
- **建议**：二选一并全文对齐 —— **(A)** 去掉 Builder 的必填校验，让回落真正生效，与 `Domains.normalize` 语义一致（推荐，符合"兜底域恒在"的既有设计）；**(B)** 文档改为"必填"，并注明回落只发生在 SDK 侧 `forDomain(null)`。**此项属设计决策，已列入待确认清单。**

### P0-3 ✅已复核 知识库域过滤：未声明 `domains` 的文档对**所有域**可见（越权 + 注释自相矛盾）

- **代码证据**：`stringer-infrastructure/.../retriever/DomainFilterQuery.java:56-61`（`should(terms([域,"*"]))` **OR** `should(must_not(exists(field)))`）；`:58-59` 注释写"未声明 domains 的文档只属于兜底域 default"
- **问题**：`visibleValues()`（`:34-40`）返回 `[当前域, "*"]`，**并不含 `default`**；而第二个 `should` 用 `must_not exists` 让"没有该字段"的文档命中**任意**域。于是：
  - 注释说 = 只属 `default`，实际 = **全域可见**；
  - `admin` 域能检索到没标域的文档（越权可见）；而显式标了 `["default"]` 的文档在 `admin` 域反而查不到 —— 两种"默认"行为互斥，且不报错无日志。
- **文档证据**：`ddoc/PITFALLS.md:145`、`docs/DESIGN.md:230`、`docs/INTERVIEW-PREP.md:774` 均按"留空＝只属 `default`"描述
- **建议**：`build()` 改为**仅当当前域是 `default` 时**才加 `must_not exists` 分支（或把 `Domains.DEFAULT` 加进 `visibleValues` 并去掉该分支）；同步修注释；补测试覆盖"无 domains 字段的文档在非 default 域不可见"。

### P0-4 `stringer.retrieval.top-n` 被硬编码 5 截断，配了也不生效

- **代码证据**：`stringer-domain/.../capability/knowledge/KnowledgeSearchService.java:44`（`Math.min(contents.size(), 5)`）、`:66`（日志也按 5 报）
- **文档证据**：`docs/DESIGN.md:242`（"融合重排后取 `top-n`"）、`docs/DESIGN.md:446`（`top-n`=10）、`docs/INTERVIEW-PREP.md:741`（"默认 10"）
- **问题**：融合确实产出 10 条，但最终喂给模型只有前 5 条；把 `top-n` 调到 20 也只拿 5 条，且文档未提这层区分。
- **建议**：抽成显式配置（如 `stringer.retrieval.answer-max-segments`，默认 5），并在文档写明"融合 TopN ≠ 回答引用条数"。

### P0-5 PDF 摄取策略已注册但未接线，加白名单会灌入乱码

- **代码证据**：`DocumentProcessStrategyFactory.java:23-27`（注册 PDF 策略）；`stringer-server/.../knowledge/KnowledgeBaseService.java:345`（上传字节**一律按 UTF-8 解码**，无二进制解析环节）；全仓 `pom.xml` 无 `pdfbox` / `tika`（`stringer-server/pom.xml:81-87` 明确注释"刻意不引 Tika 全家桶"）
- **文档证据**：`docs/INTERVIEW-PREP.md:757` 把 `PdfDocumentProcessStrategy` 列为现有策略
- **问题**：运维把 `pdf` 加进 `stringer.rag.allowed-extensions` 后，PDF 原始字节被当 UTF-8 切片入库，**静默产生垃圾知识**，而策略的存在会让人以为支持 PDF。
- **建议**：要么接真解析依赖，要么在 `requireSupported` 显式拒绝 `pdf` 并给出"当前不支持"提示，同时从策略链移除或标注"预留未接线"。**此项属取舍，已列入待确认清单。**

---

## 3 P1 文档缺失 / 误导

| # | 类型 | 位置（代码 ↔ 文档） | 问题与建议 |
|---|---|---|---|
| 1 | **缺失·安全相关** | `CredentialAuthInterceptor.java:65-71` ↔ `API.md:21`、`DESIGN.md:340` | 账号未初始化（`accounts.json` 不存在）时，**所有受保护端点整体免鉴权放行**（在凭证校验前短路 return true），不只是文档写的"开放 `/admin/init`"。未初始化时 `POST /admin/password`、`DELETE /admin/settings/{kind}` 全部无需凭证。文档需明确写出这是状态驱动的全局旁路 |
| 2 | **缺失** | `AdminController.java:248-288` ↔ `API.md:195-202` | `DELETE /admin/settings/{kind}` 未记录。它会清空一组模型配置并使未绑定域进入"无可调用"，是高危动作且管控台已在调用（`models.html:688`） |
| 3 | **缺失** | `AdminDomainController.java:50/67` ↔ `API.md:226-231` | `POST /admin/domains` 未进端点表（正文只提一句）；`DELETE /admin/domains/{id}` 全文零记载，而 `DESIGN.md:87` 说人工域"可删除" |
| 4 | **缺失** | `DomainController.java:43-61` ↔ 全文档 | `GET /api/agent/domains` 全仓零记载。这是 starter 启动期做"域是否存在"预检用的端点，返回 `code/fallback/domains/details{id,source,toolCount}` |
| 5 | **过时** | `AdminController.java:153/158` ↔ `API.md:197` | `GET /admin/settings` 响应还有 `chatCapabilities` / `embeddingCapabilities`（探测写入），字段表没有 |
| 6 | **过时** | `AdminController.java:180-226` ↔ `API.md:198` | `POST /admin/settings` 实际有**四档**响应（成功 / 需确认重建 / 声明维度不匹配 / **已保存但重建失败 `saved=true,rebuilt=false`**），文档只写了"requiresRebuild"一档。最后一档是运维必须能识别的状态 |
| 7 | **过时** | `InstanceState.java:24-25` ↔ `DESIGN.md:157-162` | 实例状态表缺 **`MUTED`** 整整一个状态机分支（熔断：心跳继续受理但副本已摘除）。`API.md:239-250` 写了，`DESIGN.md` 没有 |
| 8 | **过时** | `ToolManifest.java:74-130` ↔ `API.md:126-137` | manifest 示例里的 `prompts` 是**幽灵字段**（代码既不产生也不消费），实际是 `domains`。照抄会导致工具永远进不了目标域且不报错 |
| 9 | **缺失** | `ToolManifest.java:135-141` ↔ `API.md:134-135` | `requiresApproval` 才是审批总开关，`approvalMode` 在其为 false 时被整体丢弃。只写 `approvalMode:"ALWAYS"` → 工具**静默变成免审批** |
| 10 | **实现偏离** | `AgentOrchestrationService.java:733-743` ↔ `DESIGN.md:181` | resume 的域一致性校验是 **fail-open**：`interruptedProfiles` 记录缺失（服务端重启、checkpoint 被清理）时整段跳过，可用任意域 resume 获得"旧域提示词 + 新域工具集"。而该 Map 是纯内存的（`:112`） |
| 11 | **实现偏离** | `ModelResolver.java:19-24` ↔ `DESIGN.md:277` | 契约注释承诺"解析不到返回 `null`，内核会回落默认模型"，但 `resolveModel`（`AgentOrchestrationService.java:299-305`）**无 null 回落**，拿到后直接 `model.chat()`。当前实现抛异常所以不炸，但任何按注释实现的第三方解析器会 NPE |
| 12 | **实现偏离** | `AgentServiceClient.java:101-107` ↔ `SDK-USAGE.md:190/272` | `stop()` 捕获所有异常后返回 `false`，把"网络不可达"与"已停止"合并；且 `INSTANCE.md:300` 的示例没订阅返回的冷流 Flux，照抄会"点了批准没反应" |
| 13 | **实现偏离** | `ToolInstanceClient.java:275-296` ↔ `API.md:452` | 反向调用宣称下发 `tenantId`/`userId`/`traceId` 作为"调用上下文"，实际三者**完全丢弃**（`ToolHandler.handle` 只有 arguments 入参），多租户工具无法据此隔离 |
| 14 | **过时** | `ToolInstanceClient.java:307` ↔ `INSTANCE.md:458` | 文档说 `idempotent` "当前只登记展示，不参与重试判定"，实际它是回给服务端 `retryable` 的**唯一依据**，与自身注释矛盾 |

---

## 4 P2 表述过时 / 注释腐败

**模块归属与依赖方向（4 条）**

| 位置 | 问题 |
|---|---|
| `DomainRegistry.java:1`（在 `runtime.domain` 包） ↔ `DESIGN.md:34` | domain 注册实际在 `stringer-runtime`，文档写在 `stringer-domain` 职责里 |
| `StringerProperties.java:24` ↔ `DESIGN.md:16/38` | sdk-core 承载的是 `StringerProperties`（不是文档写的 `ServerProperties`）；`ClientProperties` 实际在 agent-client，不在 sdk-core |
| `stringer-tool-provider/pom.xml:20-23` ↔ `DESIGN.md:43/45` | 文档称 tool-provider "不依赖任何其它 Stringer 模块"，实际依赖 `api` + `sdk-core` |
| `stringer-agent-client/pom.xml:19-53` ↔ `DESIGN.md:45` | 文档称 agent-client "聚合 tool-provider，引入即同时具备两种能力"，实际 pom **无该依赖**（且 pom 描述明写"工具注册能力在独立的 stringer-tool-provider 中"）。文档承诺的"一个依赖跑起来"不成立 |

**其它（8 条）**

| 位置 | 问题 |
|---|---|
| `DESIGN.md:414` ↔ `application.yaml:17-108` | 称 yaml 里 `stringer.ai.*`、`stringer.elasticsearch.*` "整体以注释保留"，实际 yaml 中**根本没有这两段**（不是注释掉，是没有），而 `stringer.elasticsearch.*` 仍是**活的绑定**（`EsClientConfiguration.java:17`） |
| `RedisCheckpointSaver.java:27` ↔ `DESIGN.md:78` | checkpoint Redis key 真实为 `stringer:graph:checkpoint:`，文档漏了前缀（同段记忆 key 写对了，且 `README.md:93` 说"统一 `stringer:` 前缀"） |
| `AnnotatedToolScanner.java:97` ↔ `DESIGN.md:152` | `.source()` 本地格式是 `类名#方法名`（无包名），文档写 `包名.类名#方法名`。同名类跨包 source 完全相同，而重名冲突日志正是靠它定位 |
| `RemoteToolExecutor.java:66` ↔ `DESIGN.md:150` | 副本选择用 `Collections.shuffle`（随机），文档写"轮选" |
| `DEPLOYMENT.md:31-36` ↔ `DomainStore.java:37` | 称"落盘四个 json"、"工具注册与域都不落盘"，实际人工创建的域**已落盘** `domains.json` |
| `README.en.md:213-219/267-269` ↔ `README.md:215/263-266` | 英文版仍给已删除模块 `stringer-example` 的启动命令（`mvn -pl stringer-example`），且文档索引漏 `SDK-USAGE.md` —— 中英文两版内容已不等价 |
| `ddoc/PITFALLS.md:198/200` ↔ `AdminMetricsController.java:14` | "已知缺口"称"无指标接口"、"仓库无测试代码"，实际指标接口存在、测试有 21 个类 90 个用例 |
| `docs/RELEASE-CHECKLIST.md:10/15/17` | 称"当前 25 个用例"（实为 90）；要求同步根 `pom.xml`、各模块 `<parent>`、**`CHANGELOG.md`** 三处 —— 仓库无 `CHANGELOG.md`，每次发版都卡这一条 |
| `docs/DESIGN-1.0.md:215/64-65` | 路线图把**已落地**的"T4 多 LLM"仍标"未实施"；同时把**未实施**的域 `include`/`exclude` 写成现行规则（`DomainRegistry` 全文无该概念，且与它自己 §6 的 S5 待做打架） |
| `THIRD-PARTY-LICENSES.md:23` ↔ `pom.xml:39-40` | LangChain4j 四个模块统一写成 1.18.1，其中 `reactor` / `elasticsearch` 实际是 `1.18.1-beta28`。发版合规审计会被挑出来 |
| `console/assets/console.js:106` ↔ `DESIGN.md:491` | 同一页面两个名字：导航标签"熔断工具调用"，文档写"在线实例" |
| `ServerGlobalExceptionHandler.java:97-101/119-124` ↔ `DESIGN.md:357` | 文档称"参数与请求体类异常降级 WARN 不打堆栈"，但 `MissingPathVariableException`、`MethodArgumentTypeMismatchException` 仍是 ERROR + 堆栈（同类其它三个是 WARN） |

---

## 5 汇总表

### 5.1 端点清单差异

代码共暴露 44 个端点（`/admin/**` 36 + `/api/agent/**` 7 + `/health` 1）。下表仅列差异项：

| 方法 路径 | 代码 | 文档 | 差异 |
|---|---|---|---|
| `DELETE /admin/settings/{kind}` | 是 `AdminController:248` | **否** | 高危清空操作，管控台已用 |
| `POST /admin/domains` | 是 `AdminDomainController:50` | 半记录 | 正文提一句，端点表未列，无请求体/响应 |
| `DELETE /admin/domains/{id}` | 是 `AdminDomainController:67` | **否** | 全文零记载 |
| `GET /api/agent/domains` | 是 `DomainController:43` | **否** | 全文零记载 |
| `GET /admin/settings` | 是 | 是 | 缺 `chatCapabilities`、`embeddingCapabilities` |
| `POST /admin/settings` | 是 | 是 | 缺三档失败/部分成功响应字段 |
| `POST /admin/infra` | 是 | 是 | 代码额外返回 `indexName`、`indexExists` |
| `GET /admin/domains` | 是 | 是 | `stats` 多 `builtinCount`/`manualCount`/`derivedCount` |
| `GET /admin/tools`、`GET /admin/metrics` | 是 | 是 | 无任何管控台页面调用（悬空端点） |
| `GET /health` | 是 | 是 | 文档把 `version` 写死字面量，实际取 `BuildProperties`（无则为 `dev`） |

### 5.2 错误码：服务端永不发出的"死码"

`ErrorCode.java` 共 48 个枚举，文档 §5 无漏记，但**未标注死码**，前端会为它们写永不触发的分支：

| 码 | 枚举名 | 使用情况 |
|---|---|---|
| 10009 | `PROFILE_REQUIRED` | 从不抛出（`API.md:301` 已注"理论码"） |
| 20000 / 20001 / 20003 | `RATE_LIMITED` / `LLM_RATE_LIMITED` / `CONCURRENT_LIMIT` | 从不抛出 |
| 70000 | `CHAT_MEMORY_ERROR` | 从不抛出（仅 70001-70003 被 `RedisChatMemoryStore` 使用） |
| 80000 / 80002 / 80003 | `TOOL_ERROR` / `TOOL_DUPLICATE` / `TOOL_EXECUTION_FAILED` | **从不抛出**。工具重名实际抛 `IllegalStateException` → 被映射成 `50000 SYSTEM_ERROR`（`retryable=true`），会诱导调用方无限重发一个注定失败的请求 |
| 10000 | `PERMISSION_DENIED` | 服务端从不发，仅客户端按 HTTP 403 反查 |
| 10001 / 80001 | `TOOL_PERMISSION_DENIED` / `TOOL_NOT_FOUND` | 不是错误码出口，仅取 `getMessage()` 作拒绝文案 |

### 5.3 配置键差异

| 键 | 结论 | 涉及文档 |
|---|---|---|
| `stringer.server.host/port/username/password` | **失效**（真实为 `stringer.server` URL + `stringer.username/password`） | `DESIGN.md:456-457`、`API.md:349/416`、`INSTANCE.md:215-218/352`、`README.md:140-146`、`README.en.md:140-146`、`INTERVIEW-PREP.md:856` |
| `stringer.tool-instance.enabled` | **失效**（真实开关 `stringer.tools`） | `DESIGN.md:459`、`README.md:188`、`README.en.md:188`、`INSTANCE.md:32/328` |
| `stringer.tools.domains`、`stringer.domains` | **失效**（悬空键） | `SDK-CONTRACT.md:133/135` |
| `stringer.server`(URL)、`stringer.username`、`stringer.password`、`stringer.tools` | **§15 漏记**（真实存在并被绑旁） | 仅 `DESIGN-1.0.md:196`、`SDK-CONTRACT.md:131` 有 |
| `stringer.elasticsearch.*`（7 键）、`stringer.ai.chat.*`（5 键）、`stringer.ai.embedding.*`（4 键） | **§15 漏记** | 见第 4 节首行 |

其余 40+ 配置键与 §15 默认值逐一对应，无差异。

### 5.4 示例代码可用性

| 文档位置 | 问题 |
|---|---|
| `SDK-USAGE.md:17-24`、`INSTANCE.md:202-211`、`README.md:139-146` | yaml 用失效键，**照抄启动失败** |
| `SDK-USAGE.md:250-263` | 返回 `String` 的方法里 `return agent.resume(...)`（返回 `Flux<AgentEvent>`）—— **编译失败** |
| `INSTANCE.md:300` | `agent.resume(...)` 未订阅，Flux 是冷流，照抄"点了批准没反应" |
| `INSTANCE.md:335-347` | 两个键都不存在 |
| `INSTANCE.md:522` | 链接 `[SDK-USAGE.md §1.6]` —— **该节不存在**（只有 §1.1-§1.5 与 §2） |
| `SDK-USAGE.md:254` / `SDK-CONTRACT.md:108` | 字段名 `requireApproval` vs `requiresApproval` 两处不一致（getter 实际 `isRequireApproval()`） |

---

## 6 附带发现（注入第 2 步清理 / 第 4 步补测）

### 6.1 死代码与无调用方方法

| 位置 | 说明 |
|---|---|
| `RetrievalConfiguration.java:192` | `assertIndexNotEmpty` 全库无调用方（灌库钩子已改为"只建索引不灌库"） |
| `AgentOrchestrationService.java:904` | `describe(Throwable)` 私有方法无调用方（流式异常已走 `ErrorCode` 默认文案） |
| `AgentOrchestrationService.java:1026` `activeStreamCount()` | 无调用方 |
| `ToolRouter.java:89` `hasProfile()`、`ToolRegistry.java:297` `instanceIds()` | 无调用方 |
| `TokenUsageRecorder.java:25` `recordLlmOutputChunk()` | 已被 `addLlmOutputTokens` 取代 |
| `KnowledgeBaseService.java:134` 三参 `upload` 重载 | 无调用方，且易被误读为"不传域＝全域" |
| `StringerServerAutoConfiguration.java:15-16` | `@ComponentScan` 扫两个**不存在的包**，Spring 静默扫空 |
| `DocumentProcessStrategyFactory.java:90`、`PdfDocumentProcessStrategy.java:131`、`TextDocumentProcessStrategy.java:46`、`UnknownDocumentProcessStrategy.java:47` | 均无调用方 |
| `LlmModelHolder.java:385` `describeCurrent()` | 无调用方 |
| `DefaultFusionStrategy.java:249` 两参 `ScoreEntry` 构造器 | 自身无调用方 |

### 6.2 重复实现（建议收敛到 `stringer-common`）

- Token 估算 `estimateTokens` + `isCJK`：`TokenUsageRecorder.java:57-79` 与 `DualConstraintChatMemory.java:132-157` **逐字符相同**
- 脱敏 `mask`：`LlmSettings.java:96-105`（阈值 ≤8）与 `InfraSettings.java:222-231`（阈值 ≤4）—— 两种规则，后者会让 5 字符口令泄露 4/5
- `hasText` 四处、`textList` 两处各自实现
- 文件名三级回退逻辑：`AbstractDocumentProcessStrategy.java:218-236`、`DocumentProcessStrategyFactory.java:104-125`、`UnknownDocumentProcessStrategy.java:47-68` 三份相同
- `_retrieval_score` 字面量两处硬编码（`domain → infrastructure` 依赖方向限制，需先下沉常量）

### 6.3 可疑 bug

| 位置 | 问题 |
|---|---|
| `EsIndexManager.java:129` | 告警让用户设 **`stringer.rag.es.delete-on-startup`**，真实键是 `stringer.rag.delete-on-startup` —— 照做不生效，索引一直重建不了 |
| `NativeScriptScoreContentRetriever.java:104` | 日志 `"设定{}条，命中{}条"` 第一个参数传的是 `minScoreRaw`(1.2)，应为 `maxResults`(15) |
| `InstanceRegistry.java:243-252` | `expireForceOffline` 的"表项移除"日志无条件打印，非 FORCE_OFFLINE 条目会打假日志 |
| `ToolInstanceClient.java:320` | `isRegistered()` 在**首次心跳成功前**就返回 true —— 健康检查假阳性 |
| `ToolInstanceConfig.java:50-52` | 硬拼 `http://`，`stringer.server` 写 https 时注册/登录仍打 http，必然失败且难查 |
| `ClientCredential.java:73-107` | 运行期凭证失效重登失败也抛 `StringerStartupException`，异常名与场景不符 |
| `DefaultStringerAgent.java:112` | `resume` 未传 `tenantId`/`userId`，审批恢复后归属丢失 |
| `ToolRegistrationController.java:39` | `warnedEndpoints` 仅特定路径 remove，loopback 实例下线后条目永久残留 |
| `AgentEvent.java:86` | `error(sessionId, message)` 产出 `code`/`codeName` 为 null 的 ERROR 事件，违反 `API.md:174` 的"必填"约定 |
| `AdminModelProfileController.java:176` | javadoc 仍写"仍被域绑定时**拒绝**删除"，与已改成的级联行为矛盾 |

### 6.4 建议补测（第 4 步）

- `DomainFilterQuery`：无 `metadata.domains` 的文档在非 `default` 域应不可见（当前缺失，P0-3）
- `AgentRequest`：profile 必填校验的行为（P0-2 决策后）
- `DefaultFusionStrategy`：`topN` 边界（`> 命中数`、`<= 0`）—— 已有 clamp 但无覆盖
- `CompositeRetriever`：`timeoutMs <= 0` 的 5000ms 兜底
- `KnowledgeSearchService`：`top-n` 与 5 段截断的关系（P0-4）
- `ModelProfileRegistry.delete`：级联清理的正确性（_registry 侧已有契约变更）
- `ToolManifest`：`requiresApproval` 总开关语义（P1-9）

---

## 7 路由到后续步骤

| Step | 输入 |
|---|---|
| **Step2 代码清理** | §6.1 死代码、**§6.2 重复实现**、§6.3 中无争议的小修（假日志、日志参数传错、`hashCode` 无关的笔误类） |
| **Step3 逻辑排查** | **P0-2**（回落 vs 拒绝三方打架）、**P0-3**（越权可见）、**P1-10**（resume fail-open）、**P1-11**（ModelResolver null NPE）、§6.3 剩余 bug |
| **Step4 功能验证** | §6.4 清单 |
| **Step5 示例工程** | 修好的配置键（P0-1）与 `SDK-USAGE.md` 示例（§5.4）是前置依赖 |
| **Step6 文档更新** | 本文件全部 P0/P1/P2 条目 + §5 四张汇总表 |
| **Step7 待确认清单** | P0-2 选型、P0-5 取舍，以及其它标记为"属设计决策"的条目 |

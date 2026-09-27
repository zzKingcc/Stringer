# 域改造落地思路（草案 v0.2）

> 这份是**思路**，不是实施细案：说清楚「改哪几块、按什么顺序、每步怎么不破坏现状、怎么验收」。
> 依据：`DOMAIN-MODEL.md`（域模型）＋ `REDESIGN-PLAN.md`（9 个主题，现在挂到域上）
> v0.2 新增两条硬约束：**工具必须强制声明可用域**、**对话必须经 SDK 绑定到指定域**。

---

## 0 两条硬约束（本轮改造的出发点）

| # | 约束 | 现状 | 目标 |
| --- | --- | --- | --- |
| 1 | 工具**必须注明**在哪些域可用 | `profiles` 可留空，留空＝**全域可见**（宽松） | `domains` 留空 → **归入内置域 `default`** 并告警；全域可用须**显式**写 `{"*"}` |
| 2 | 对话**必须绑定**到指定域 | `profile` 是普通字符串参数，可传可不传，服务端才拦 | 由 **SDK 强制**：编译期拿不到"不带域"的调用形式；启动期校验域存在；运行期服务端以 `default` 兜底 |

这两条合起来的效果：**域成为不可绕过的接线点** —— 工具要么归到显式声明的域，要么归入 `default`；对话要么显式绑定，要么落到 `default`。**不存在"无归属的工具"与"无域的调用"，只存在"还没分类的"** —— 而"还没分类"是可见、可统计、可治理的。

---

## 1 三条权威方向（不要搞混）

| 关系 | 谁定 | 为什么 |
| --- | --- | --- |
| 工具 → 域（我能被谁用） | **工具侧强制声明** | 工具作者最清楚它的适用范围与副作用，这是**授权** |
| 域 → 工具（我用谁、怎么用） | 域侧（在授权范围内） | 域决定用不用、是否加审批、超时与配额 |
| 域 → 模型 / 提示词 / 知识 / 记忆 / 配额 | 域侧 | 这些信息工具侧根本不持有 |

**生效条件 = 交集**：`域 ∈ 工具声明域` **且** `工具 ∈ 域引用集合`。任一侧不通过都不生效。
**优先级**：工具声明边界 > 域 `exclude` > 域 `include` —— 域只能收紧，不能放宽。
**越界即失败**：域引用了未授权工具 → 发布期校验失败（fail-closed），不静默剔除。

---

## 2 五条改造主线

| 主线 | 内容 | 依赖 |
| --- | --- | --- |
| **A 工具声明强制化 + 默认域预置** | `domains` 留空 → 归入内置 `default` 并告警；`{"*"}` 表达显式全域；启动期 seed 受保护的 `default` 域 | 可最先做，只影响工具侧 |
| **B 域立起来 + 调用强制绑定** | `DomainRegistry` 让域有身份与来源；SDK 提供绑定式调用并在启动期校验域存在 | 依赖 A（影子域来源） |
| **C 装配项接管** | 模型、提示词、知识、记忆从全局配置迁到域，全局值降级为默认值 | 依赖 B |
| **D 域内选择与覆盖** | 域在授权范围内 include/exclude，并覆盖审批、超时、配额 | 依赖 A+C |
| **E 租户线** | `tenant` 进数据面（记忆 key、检查点、知识分区）+ 用量按 `(域, 租户)` 归集 | 可并行 |

---

## 3 模型接入（多 LLM，可与 A/B 并行）

三层，**只做这一块就能上线**：

| 层 | 内容 | 落在哪 |
| --- | --- | --- |
| 模型档案 | 别名 → {端点, Key, 模型名, 参数, 降级链}，如 `fast` / `smart` / `local` | server 侧 settings 包，落盘 `config/model-profiles.json` |
| 域角色绑定 | 域按用途绑别名：`chat` / `planner` / `summarizer` / `embedding` / `rerank` | 域配置 |
| 调用期解析 | `域 → 角色 → 别名 → 端点`，带降级与熔断 | runtime 侧新增解析器 |

**要动的关键一处**：`AgentOrchestrationService` 现在是**构造期注入 `StreamingChatModel` 单例** —— 这是"单模型"的根因。改成注入**模型解析器接口**：默认实现返回全局模型（行为不变），新实现按域解析。

**兼容**：现有 `llm-settings.json` 作为 **`default` 域**的装配初值；没有域绑模型时全走 default → 现状零变化。**全局配置就此统一为「`default` 域的装配」**，yaml 与落盘 JSON 只作它的初始化种子（bootstrap）—— 继承链只剩一条，不再有"全局 + 域"两层。
**不变式**：`effectiveEmbeddingDimension()` 作为向量维度唯一入口必须保留（实测、构建、建索引三处同源）。

---

## 4 改动点地图

| 模块 | 现状类 / 文件 | 改造动作 | 风险 |
| --- | --- | --- | --- |
| api | `@StringerTool.profiles` | 更名 `domains`；**留空 → 归入 `default`**；`profiles` 保留为 deprecated 别名；新增通配 `{"*"}` | 留空语义收紧（全域 → 只 default） |
| api | `ToolDescriptor.visibleIn` | 语义从"可见性"改"授权判定"；空列表不再视为全域可见 | 逻辑反转要一次改干净 |
| api | `AgentRequest`、`AgentEvent` | `profile` → `domainId`（别名兼容）；事件带 `modelName` | 契约向后兼容 |
| runtime | `ToolRegistry` | 工具声明落库为授权边界；域引用与其求交集；越界引用在注册/发布期报错 | 两套真相（影子域 vs 声明域） |
| runtime | `AgentOrchestrationService` | 注入模型解析器；编排策略从域读（轮次/超时/是否自检） | 图编译期固定 vs 域策略可变 |
| runtime | `ToolRouter.isToolVisible` | 复核条件改为"交集通过"（保留 fail-closed） | 与旧语义混淆 |
| runtime | `SystemPromptResolver` | 升级为"域 → 模板引用 + 变量" | 执行单元冻结语义要保住 |
| domain | `KnowledgeSearchService`、`CompositeRetriever`、`FusionConfig` | 检索器与权重按域解析，回落全局 | 检索行为漂移 |
| domain | `DualConstraintChatMemory` | 窗口策略按域解析，回落全局默认 | 老会话兼容 |
| infrastructure | `RedisChatMemoryStore`、`RedisCheckpointSaver` | key 加 tenant 维度（缺省 `default`） | 老数据 key 迁移 |
| starter | **新增** `DomainAgent` / `@DomainBinding` | 绑定式调用；启动期校验域存在（复用连通性探测的 fail-fast） | 破坏性变更 → 旧 API 保留 deprecated |
| starter | `StringerAutoConfiguration` | 装配 `forDomain()` 门面与绑定校验 | 装配顺序 |
| server | **新增** `DomainRegistry`、`/api/v1/domains/{id}/chat` | 域身份 + 资源化路由；缺域直接 404 | 路由与旧入口并存 |
| server | `LlmSettingsStore`、`AdminController` | 新增 `model-profiles` 与 `domains` 端点；域空间页升级为编辑界面 | 页面重构 |

---

## 5 推行顺序（五步，每步可独立上线与回滚）

| 步 | 范围 | 兼容做法 | 验收 | 回滚 |
| --- | --- | --- | --- | --- |
| **S1 工具声明强制化 + 默认域** | `domains` 留空 → `default` + WARN；`{"*"}` 表达显式全域；启动期幂等 seed 受保护的 `default` 域 | 留空不再报错（旧路径不炸）；已声明工具原样生效；**唯一行为变化**：留空工具从"全域可见"收紧为"只在 default 域可见" | 留空工具归入 default 且日志/管控台可见；`{"*"}` 工具在所有域可见；default 域不可删除 | 无需开关（行为确定） |
| **S2 域注册表 + 调用强制绑定** | `DomainRegistry`、影子域、SDK `forDomain()`/`@DomainBinding`、启动期域校验、域资源化路由 | 影子域来源 `derived`；旧 `AgentService.chat(AgentRequest)` 与 `/api/agent/chat` 保留 deprecated | 声明的域不存在则**启动失败**；不传域的调用在编译期就写不出来 | 停用绑定校验，回落旧通道 |
| **S3 模型档案 + 域角色绑定** | 三层模型解析（可与 S1/S2 并行） | 现状配置视为 `default` 档案 | 两域走不同端点；单档案回归通过 | 关解析器开关，退回单例 |
| **S4 装配接管** | 提示词模板化、知识按域、记忆窗口按域 | 旧 `profiles.json` 视为单元素模板；域未声明即回落全局 | 两域不同提示词/知识空间/记忆窗口实测生效 | 逐项开关 |
| **S5 域内选择与覆盖** | 域 include/exclude + 交集 + 覆盖审批/超时/配额 | 工具声明仍是必要条件；旧解析路径保留一个版本周期 | 同一工具在两域审批策略不同；域引用未授权工具**发布即失败** | 保留旧解析路径 |

**租户线（E）** 可并行：key 加 tenant（缺省 `default`）→ 用量按 `(域, 租户)` 归集 → 覆盖层最后（第一版可只在内存合并）。

### 5.1 实现进度

**S1 · 工具声明语义 + 兜底域（2026-09-27）**

| 模块 | 改动 |
| --- | --- |
| `stringer-api` | 新增 `Domains`（`default` / `*` 常量与归一化）、`DomainAgent`（绑定域的门面契约）、`DomainAgentFactory`；`ToolDescriptor.visibleIn` 改为**授权语义**：含 `*` → 全域可用，留空 → 只属于 `default` |
| `stringer-spring-boot-starter` | 新增 `StringerDomainAgent`、`DefaultDomainAgentFactory`（按域缓存门面）、`@DomainBinding`（类级/方法级）；自动装配新增 `DomainAgentFactory` Bean；启动期收集 `@DomainBinding` 声明的域，开关 `stringer.client.domain-check-enabled`（默认 false）开启时向服务端校验，服务端未提供该端点则降级 WARN |
| `stringer-runtime` | `ToolRegistry.knownProfiles()` **恒含 `default`**（兜底域预置的运行时落点）；`AgentOrchestrationService.checkProfile()` 域为空 → **回落 `default`**，不再报 `10009` |

**S2 · 域注册表 + 域清单接口（2026-09-27）**

| 模块 | 改动 |
| --- | --- |
| `stringer-runtime` | 新增 `runtime/domain/DomainRegistry`：域成为一等实体（`BUILTIN` 内置 / `MANUAL` 人工）。内置 `default` 在构造期预置、**不可删除**；人工域可创建、可删除；标识为 `*`、含空白、超 64 字符会被拒 |
| `stringer-runtime` | `ToolRouter` 接入域注册表：`acceptsProfile` = `DomainRegistry.contains` **∪** `ToolRegistry.acceptsProfile`；`getKnownProfiles` / `hasProfile` 同样合并两处来源 |
| `stringer-server` | 新增 `settings/DomainStore`（落盘 `config/domains.json`，**只存人工域**）+ `config/DomainConfiguration`（装配并在启动期恢复） |
| `stringer-server` | 新增业务面 `GET /api/agent/domains`（返回 `fallback`、`domains`、`details[{id, source, toolCount}]`）；新增管理面 `POST /admin/domains` 与 `DELETE /admin/domains/{id}` |

**S2 带来的两个实际能力**

1. **域可以独立存在**：先 `POST /admin/domains` 建域，再让应用以 `@DomainBinding` 绑定它启动 —— 不必等某个工具来"声明"这个域。这正是"先建域、再启应用"能成立的前提。
2. **管控台自动可见**：`AdminController` 的域展示（含启动横幅）都走 `toolRouter.getKnownProfiles()`，合并后人工域直接出现，**无需改管理面代码**。

**S3 · 命名统一 + 管控台域管理（2026-09-27）**

| 项 | 内容 |
| --- | --- |
| 注解字段 | `@StringerTool.domains()` 为主，`profiles()` 标 `@Deprecated`；扫描器优先读 `domains`，留空才回落 `profiles` |
| SDK 方法 | `ToolSpec.withDomains(...)` 为主，`withProfiles(...)` 标 `@Deprecated`（转调）；**上报报文的 `profiles` 键保持不变**，避免工具实例与服务端版本错配 |
| 管控台 · 域空间 | 域清单新增「来源」列（内置 / 人工创建 / 工具派生）、工具栏「新建域」、行内与详情页「删除」（**仅人工域**给出按钮）；统计卡显示来源构成；全部文案改为新语义 |
| 管控台 · 其他页 | 概览页的域构成与工具说明、提示词页的域来源说明与空态文案同步 |
| 注释与文档 | `AdminController` 新增 `domainSource` / `sourceLabel`；DESIGN / API / INSTANCE / INTERVIEW-PREP / PITFALLS 中「留空＝全域可见」「域不能手工创建」等过时表述一并订正 |

**尚未落地**

- 域引用（include / exclude）与域级覆盖（审批 / 超时 / 配额）
- `stringer.client.domain-check-enabled` 仍默认关闭：服务端 `GET /api/agent/domains` 已就绪，**可以打开**（打开后域名写错会在应用启动阶段就报错）
- `ToolDescriptor.profiles` 字段名与工具实例上报报文的 `profiles` 键**保持不动**（跨版本兼容），仅在 javadoc 标明语义为"授权域"

**本次带来的行为变化（需同步 API 文档）**

| 变化 | 说明 |
| --- | --- |
| 留空工具 | 从"全域可见"收紧为"**只在 `default` 域可见**" |
| 未指定域的调用 | 从 `10009 PROFILE_REQUIRED` 变为**回落 `default`** |
| `knownProfiles` | 恒含 `default` 与人工创建的域，"完全没有域概念时放行任意域"这条分支实际不再触发（保留兼容） |
| 域的存在性 | 不再只由"工具是否声明过"决定 —— 人工创建的域即使暂无工具也放行 |

---

## 6 六条不能碰的不变式

1. **域仍是工具可见性的唯一维度** —— 租户只做数据边界与配额，不叠加第二个权限维度。
2. **执行单元内冻结** —— 一次 `chat` 及其全部 `resume` 内，域与提示词冻结；`resume` 必须用中断时的域。
3. **向量维度唯一入口** —— `effectiveEmbeddingDimension()` 三处同源不能破。
4. **主循环形状不变** —— `agent → 条件边(exit|auto|review) → tools → agent`，tools 回边是**无条件固定边**。
5. **工具拒绝语义不变** —— 域外 `10001`、已下线 `80001` 走文本回灌，不结束流（新增"未授权"也归 `10001`，不新增码段）。
6. **域不可绕过** —— 不存在"无归属的工具"与"无域的调用"：前者归入 `default`、后者兜底 `default`。但**兜底 ≠ 允许**：新接入必须显式声明与显式绑定，`default` 的使用量应被当作"待分类"债务来治理。

---

## 7 风险与对策

| # | 风险 | 对策 |
| --- | --- | --- |
| 1 | **`default` 成为事实上的主域**：留空工具与未绑定对话都沉淀在这里 | 平台只做**事实呈现**（`default` 下的工具清单与调用计数），**不设阈值、不设告警** —— 用多少、要不要清，由使用方自己决定 |
| 2 | **部署顺序变化**：应用启动会校验域存在 → 必须先建域再启应用 | 与现有"先起服务端"同构；启动失败信息里给出"去管控台建域"的指引 |
| 3 | 影子域与声明域**两套真相** | 域带 `source` 标记（manual / derived）；影子域首次被编辑即转 manual，单向收敛 |
| 4 | 域配置热更新 vs 执行单元冻结 | 解析结果在**执行单元开始时快照**，单元内不变（沿用现有语义，不新造机制） |
| 5 | 模型解析缓存 vs Key 轮换/端点切换 | 缓存键带档案版本号；Key 变更即失效 |
| 6 | 域数量膨胀（治理债） | namespace + 标签 + 生命周期状态（draft/deprecated）；管控台提示"长期未使用" |
| 7 | 注解式绑定在 AOP/代理下失效 | 同时提供注入式 `forDomain()`；注解支持方法级覆盖类级 |

---

## 8 本轮明确不做

多租户强校验与域授权、计费、MCP 协议适配、多 Agent、状态外置 —— 留在 `REDESIGN-PLAN.md` 的其他主题，不混进这次改造。

---

## 9 需要你确认的三件事

| # | 问题 | 我的建议 |
| --- | --- | --- |
| 1 | 新接入是否允许继续走 `default` 兜底 | 建议**不允许**：SDK 层强制绑定（编译期就写不出），`default` 兜底只面向旧通道与裸 HTTP；否则"必须绑定"会被兜底架空 |
| 2 | `default` 域能否被其他域引用其工具 | 建议**不能**：交集模型下自然不成立 —— 别的域要用这些工具，就让工具显式声明包含它，或写 `{"*"}` |
| 3 | 域配置放哪 | **文件化 Manifest（可进 Git、可 diff、可评审）为主，管控台编辑为辅** |
| 4 | S1 第一版是否照此合入、是否继续 S2 | 已按此实现（见 5.1）；确认后继续 S2：服务端 `GET /api/agent/domains` + `DomainRegistry` |

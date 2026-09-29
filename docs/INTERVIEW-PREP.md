# Stringer 项目面试知识点全解

> 用途：面试前复习。按「项目定位 → 设计决策 → 数据与存储 → 接口契约 → 技术栈知识点 → 已知边界 → 高频问答」组织。
> 所有内容以仓库实现为准（`docs/DESIGN.md`、`docs/API.md`、`docs/INSTANCE.md`、`docs/DEPLOYMENT.md`、`ddoc/PITFALLS.md` 与源码）。

---

## 第一部分 项目定位与整体形态

### 1.1 一句话定位

Stringer 是 **Java 生态的 AI Agent 运行时中间件**：引一个 starter 注入 `StringerAgent` 就能调 AI，方法上加 `@Tool` 就能让 AI 调用你的业务方法。编排、工具治理、知识库、管控台全部收在服务端。

**与主流方案的差异（面试常被问"为什么不用 Dify / Spring AI"）**

| 维度 | Stringer | Dify / FastGPT | Spring AI / LangChain4j |
| --- | --- | --- | --- |
| 形态 | 独立服务端 + 薄 starter | 独立平台（容器部署） | 纯库，随业务进程 |
| 技术栈 | Java 21 / Spring Boot 3.5.7 | Python 为主 | Java |
| 工具怎么写 | 现有 Spring Bean 上加注解 | 平台内配置 / 插件市场 | 写代码自己接路由 |
| 工具在哪跑 | 你的进程内，复用事务、权限、`@Service` | 平台进程，跨系统 HTTP | 你的进程内 |
| 业务代码改动 | 只注入 `StringerAgent`，零业务改动 | 另起进程走 REST / iframe | 编排与状态代码写进业务工程 |
| 编排与状态 | 图编排 + Redis 检查点，中断后可跨实例恢复 | 可视化工作流 | 需自行实现 |
| 工具治理 | 域可见性 + 审批中断 + 多实例注册中心 | 插件市场 | 无内置治理 |
| 运维界面 | 内置 8 页管控台 + 运行指标 | 自有界面 | 无 |

核心取舍一句话：**用"多跑一个服务端进程"的部署成本，换"业务侧零侵入 + 内置治理与可运维"。**

### 1.2 交付三件套

| 交付物 | 模块 | 部署位置 |
| --- | --- | --- |
| 服务端（独立进程） | `stringer-server` | 自部署，默认端口 9527 |
| 消费侧 starter | `stringer-agent-client` | 引入业务应用，提供 `StringerAgent`（唯一入口，`forDomain` 取）/ `KnowledgeBaseClient`，并传递工具实例 SDK（工具能力默认关闭） |
| 工具实例 SDK | `stringer-tool-provider` | 工具提供方应用，把本地方法注册到服务端 |

### 1.3 模块划分与依赖方向

9 个 Maven 模块：

- `stringer-api`：对外契约（错误码、注解、`ToolDescriptor`、`AgentRequest`/`CallerContext`/`AgentEvent`、`TraceId`、`StringerAgent`/`StringerAgentFactory`/`ApprovalRequiredException`、`AgentService`、SPI）
- `stringer-common`：异常基类、`InputSanitizer`、`AtomicFiles`
- `stringer-domain`：领域能力（知识检索、混合检索与融合排序、会话记忆约束）
- `stringer-infrastructure`：外部依赖适配（ES 检索器与索引管理、文档摄取与切片、Redis 记忆与检查点、向量化）
- `stringer-runtime`：运行时内核（编排图、工具注册表与路由、实例注册表、流式上下文、提示词解析、取消）
- `stringer-server`：服务端装配（配置、管控接口、鉴权、设置存储、异常出口、静态管控台）
- `stringer-agent-client`：消费侧客户端（凭证管理、`AgentServiceClient` 内部通道、`DefaultStringerAgentFactory`/`DefaultStringerAgent`、`KnowledgeBaseClient`、启动连通性探测）
- `stringer-tool-provider`：工具实例 SDK（注解扫描、注册心跳、反向调用端点）
- ~~`stringer-example`~~：**已移除**，例子后期重写（原含 6 个演示工具与 4 篇示例语料）

依赖方向（文字描述的分层图）：

```
api  →  common  →  domain  →  infrastructure  →  runtime  →  server
                                    ↑
                        starter / tool-provider 独立于该链
                        tool-provider 不依赖任何 Stringer 模块，与服务端只通过 HTTP 报文耦合
```

**面试点：为什么这么切？**

- `api` 是唯一被外部依赖的契约层，工具实例 SDK 只依赖它 → 工具方可独立升级，不被服务端内部实现绑架。
- `domain` 与 `infrastructure` 分离：领域能力（融合排序策略、记忆窗口策略）不依赖具体 ES/Redis 客户端，换存储不动策略。
- `runtime` 收全部"内核"（图、注册表、路由、取消），`server` 只做装配与出口 → 内核可脱离 Spring Boot 单独测。

**消费侧依赖边界的两个硬约束（很值得讲）：**

1. **starter 是唯一接入坐标**，聚合 `api` + `common` + `tool-provider`。聚合成本为零：`common` 只依赖 `api`；`tool-provider` 的依赖（`spring-web` / `spring-boot-autoconfigure` / `jackson-databind` / `slf4j-api`）全在 starter 既有依赖树内。
2. **Web 容器始终归宿主**：starter 与 tool-provider 都只用 `spring-web` 的注解模型 + 出站 `WebClient`，**不引任何容器**。原因：Spring Boot 判定 Web 应用类型时，Reactive 分支要求「`DispatcherHandler` 在且 `DispatcherServlet` 不在」；SDK 一旦带上 `spring-boot-starter-web`，纯 WebFlux 宿主会被误判成 SERVLET，`DispatcherHandler` 相关装配随之失效。

### 1.4 运行时调用链路（文字流程）

```
调用方业务应用（含 starter）
  │  POST /api/agent/chat      Header: X-Stringer-Credential
  ▼
ServerAgentController → AgentOrchestrationService → 提交到 agentExecutor 线程池
  │
  ▼
LangGraph4j 编排图（检查点存 Redis）
  agentNode：注入 SystemMessage + 本轮可见工具集，调流式模型，推 TOKEN 事件
     │ 有工具调用
     ├─ 条件路由：全部工具都无需审批 → auto → toolsNode
     └─ 条件路由：含需审批工具      → review（no-op 中断锚点）→ 抛 INTERRUPT，图挂起
  toolsNode：逐条复核工具可见性 → ToolRouter 执行（本地 Bean 或远程实例）→ 结果回填状态
     │
     └─ tools → agent（条件循环，直到模型不再请求工具）
  ▼
SSE 事件流：TOKEN / TOOL_CALL / TOOL_RESULT / INTERRUPT / STOPPED / ERROR / DONE
```

**三类状态的存放位置（面试高频）：**

- 会话记忆 → Redis（`stringer:chat:memory:*`）
- 图检查点/断点 → Redis（`stringer:graph:checkpoint:*`）
- 事件流 → 只走 HTTP 响应，不落任何地方

三者相互独立：清检查点不影响记忆，记忆过期不影响断点。

### 1.5 部署形态：一个 jar 跑遍各端

- 平台无关的单文件 fat jar：Linux / Windows / macOS 直接 `java -jar`。
- 启动期 `RuntimeEnvironment` 用 `System.getProperty("os.name")` 判定 OS 族，自动选定目录并提前建好：
  - Linux/其他 Unix：`/var/lib/stringer/config`、`/var/log/stringer`
  - Windows：`%ProgramData%\Stringer\config`、`%ProgramData%\Stringer\logs`
  - macOS：`/Library/Application Support/Stringer/config`、`/Library/Logs/Stringer`
- 覆盖优先级：命令行 `--key` > JVM 系统属性 `-Dkey` > 环境变量 > 平台默认。
- 容器运行时探测：`KUBERNETES_SERVICE_HOST` / `/.dockerenv` / `/.containerenv` / `/proc/1/cgroup`，结果打在启动横幅里便于排障。
- 优雅停机：`server.shutdown=graceful` + `timeout-per-shutdown-phase=30s`，在途 SSE 长连接收尾后再退出。
- Dockerfile 两个细节：`HEALTHCHECK` 用 bash 的 `/dev/tcp` 发最简 HTTP（temurin 基底没有 curl）；`ENTRYPOINT ["sh","-c","exec java ..."]` 中用 `exec` 让 java 成为 PID 1，否则 SIGTERM 落在 sh 上、JVM 收不到、优雅关闭失效。

---

## 第二部分 核心设计决策（面试主战场）

### 2.1 域（Profile）：工具可见性的唯一维度

**定义**：域＝一次对话的场景，同时绑定【工具集 + 系统提示词】。

| 规则 | 内容 | 设计原因 |
| --- | --- | --- |
| 创建方式 | **三个来源**：内置兜底域 `default`（预置、不可删）、管控台人工创建（`POST /admin/domains`，落盘 `config/domains.json`，可删）、工具声明派生（`@Tool(domains=)`） | 内置域保证兜底有落点；人工域让"先建域再启应用"成立 |
| 生命周期 | 一经某工具声明过就**常驻**（工具断开后域仍在，只是名下暂无工具） | 域是调用方沿用的命名空间，不该被一次实例熔断/判死带走 |
| 判定粒度 | 由工具声明即创建，不需要预先注册 | 降低接入成本 |
| 调用侧入口 | SDK 只提供一个入口：`StringerAgentFactory.forDomain(domainId)` → `StringerAgent`。门面按使用频度分三层（`ask` 取答案 / `stream` 逐字 / `events` 完整事件），签名里**都没有域参数**，"忘传域"写不出来；域不存在会在首次调用时报 `10004`，无需启动期预校验 | 域是**接线动作**而不是每次都要记得传的参数 |
| 缺失处理 | HTTP 通道未带 `profile`（或空串）→ 回落兜底域 `default`；SDK 通道则在 `AgentRequest` 构造期**直接拒绝空域**（`IllegalArgumentException`）。`10009` 实际不触发（兜底域受保护不会缺失） | 不默认给域，防"漏传即降级"；SDK 用构造期校验把这事前移 |
| 域不存在 | fail-fast 返回 `10004`，**绝不回退为全量工具** | 静默降级成全量工具是权限事故 |
| 兜底域恒在 | `knownProfiles` 恒含内置 `default`，故"已知域集合为空"不再发生；域不存在一律 `10004` | 兜底是回落，不是放宽 |
| 工具视图 | 每轮实时读注册表，**不缓存快照** | 动态上下线要立刻生效 |
| 同源性 | 模型可见工具集与需审批工具集必须来自同一判定（`toolSpecifications(profile)` 与 `toolsRequiringApproval(profile)` 同源） | 否则会出现"模型看得见但审批表里没有"的漏闸 |
| 边界声明 | 域是**调用方自行声明、平台信任**的治理机制，**不是安全边界** | 用哪个域由客户端决定，平台无法验证真伪；角色→域的越权判断属宿主 IAM |

**核心方法**（`ToolDescriptor#visibleIn`）：

- `domains` 为空 → **只属于兜底域 `default`**（不再等于全域可见）；`{"*"}` 才是全域可用
- 否则 `profile != null && !blank && profiles.contains(profile)`

**两种拒绝文案分开**（细节，但很能体现思考）：

- 工具在注册表里但域不匹配 → `10001 TOOL_PERMISSION_DENIED`（权限问题）
- 工具根本不在注册表（提供方已下线）→ `80001 TOOL_NOT_FOUND`（能力已消失）

对模型是两个不同信号：后者告诉用户"这个能力没了"比让它反复重试有用。

**为什么域是唯一权限维度、不叠加第二个？** 维度一多，"可见性判定"就会在多个地方各写一遍，必然出现"页面说可见、模型拿不到"的隐蔽不一致（踩坑文档明确记过这个问题）。

### 2.2 工具体系：注解即契约

**三种声明方式，语义完全一致：**

1. 注解式（推荐）：任意 Spring Bean 方法上 `@Tool`（`desc` 唯一必填）+ `@ToolParam` 补参数语义
2. 编程式：实现 `ToolInstanceContributor`，在 `contribute(ToolRegistrar)` 里登记（工具清单要在启动期动态拼装时用）
3. 两者共存时**重名以编程式为准**（后注册覆盖）

**注解字段（`@Tool`）**

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `desc` | **必填** | 给 LLM 的用途说明，"何时调用/何时不要调用"比参数描述更重要 |
| `value` | 空（取方法名） | 工具名，全局唯一，重名注册失败 |
| `domains` | `{}` | 留空＝只属于兜底域 `default`；`{"*"}`＝全域可用（须显式写出） |
| `effect` | `READ` | `READ`/`WRITE`/`DESTRUCTIVE` |
| `approval` | `NONE` | `NONE`/`ALWAYS`；`ALWAYS` 时每次调用前中断等授权 |
| `approvalReason` | 空 | 展示给审批人的原因 |

配套：`@ToolDomains`（类级默认域）、`@ToolAdvanced`（方法级的高级可选：`example`/`allowValues`/`sensitive`，一律 `参数名=值`，**不做位置对齐** —— 位置对齐在参数增删调序时会静默错位）。

**`@ToolParam`**：只有三项 —— `value`（参数说明，推荐写法）、`name`、`required`。示例 / 白名单 / 脱敏**不在这里**，它们属于"偶尔才写一项"的长尾，统一放 `@ToolAdvanced`；旧的 `description`/`example`/`allowValues`/`sensitive` 四个废弃字段已彻底删除（写了编译不过）。

**`@ToolAdvanced` 三个字段各自的落点**（这是"写了会不会真生效"的判据）：`allowValues` → 模型可见 schema 的 `enum`；`example` → `Param.example` 并**追加进参数说明**（底层 schema 只有 description 一个自由文本位，没有 example 槽）；`sensitive` → `Param.sensitive`，并把该参数的**值**在工具调用事件与审批 payload 里掩码成 `***`。名字既可以是形参名，也可以是 DTO 展开出的字段名。

**审批**：新注解只保留 `NONE` / `ALWAYS` 两态——`CONDITIONAL`（条件式）与 `ONCE_PER_SESSION`（会话内免确认）未落地，不再暴露，避免"摆出来却无差别"。

**参数 schema 由 `ParamSchemaResolver` 统一推导**（本地 Bean 与工具实例<b>共用同一份实现</b>：工具实例侧再经 `toWireSchema` 把同一棵树渲染成上报 JSON，服务端 `ToolParamSchema` 认的就是这套键）：

- `String`/`UUID`/`Temporal`/`Date` → `string`
- `int/long/short/byte/BigInteger` → `integer`
- `float/double/BigDecimal/Number` → `number`
- `boolean` → `boolean`
- `enum` → `string` + `enum` 枚举值列表
- `T[]` / `Collection<T>` → `array` + `items`
- 其他类 → `object` + 递归展开 `properties`，**最大深度 5 层**（`ParamSchemaResolver.MAX_DEPTH`，防循环引用与巨型 schema）
- POJO 展开含父类字段，跳过 `synthetic` / `static` / `transient`
- 嵌套字段的 `required` 直接取 `@ToolParam.required`（缺省 `false`），不替业务方法做默认值假设

**参数名解析失败直接启动期报错**：优先 `@ToolParam.name`，其次编译期元数据（`DefaultParameterNameDiscoverer`）。取不到就抛异常并提示"补 `@ToolParam(name=...)` 或给编译器加 `-parameters`"。理由：用 `arg0` 注册出去只会让模型拿错 key，这种错必须留在启动期。

**执行语义**：

- 调用时按参数名从模型给的 JSON 里取值并转成声明类型；取不到必填参数 → 抛"缺少必填参数: xxx"，由 SDK 包装成工具失败原因回喂模型。
- 返回值：`CharSequence` 原样回喂，其余序列化成 JSON。
- 模型偶尔吐出非法 JSON → 按空参数处理，让业务方法自己在缺参上给结果。
- 声明为 `DESTRUCTIVE` 却没配审批 → 启动期打 WARN 劝告补 `@Tool`（配 `approval`）。

**工具执行的正确性细节**：

- `ToolRouter.execute` 是唯一执行入口，内含 Token 用量统计与异常兜底（失败也计指标——失败率是运维最需要的数）。
- 工具不存在时返回文案取自 `ErrorCode` 枚举，与 `toolsNode` 复核层文案**同源**："同一个事实只该有一种说法"。
- 远程工具需要租户/用户/traceId，但 `execute(request, memoryId)` 签名里没有身份位置 → 用 `ToolInvocationContext`（ThreadLocal）在执行前绑定、`finally` 解除。同步阻塞调用，绑定与解除在同一线程同一次调用内，不会泄漏到同线程下一轮。

### 2.3 工具注册表：多来源、多副本、内存态

**两个来源写入同一个内存注册表 `ToolRegistry`**（不落盘）：

| 来源 | 时机 | 副本表示 |
| --- | --- | --- |
| 本地 Bean | 启动期扫描 | 单元素 `local` |
| 远程实例 | 运行期整包心跳 | `instanceId` + `endpoint` |

**条目结构**：`Registered(ToolDescriptor descriptor, ToolSpecification specification, ToolExecutor executor, List<InstanceEndpoint> endpoints)`，不可变对象 + `ConcurrentHashMap.compute` 原子替换 → **读端无锁**。

**反向索引** `byInstance`：`instanceId → 该实例声明的工具名集合`。

**更新语义（整包 diff）**：

- 按 `instanceId` 整包替换：本次心跳的声明为准，未出现在本次 manifest 里的工具视为该实例已撤下。
- 地址无条件校对；描述、参数 schema、域归属、审批策略变更以本次为准。
- **先全量校验再增量 diff**：任何工具与本地 Bean 工具重名 → 在任何副本写入之前抛异常。"非抛即全部生效"，否则中途抛异常会留下没有 `byInstance` 记录的**幽灵副本**，实例下线时 `removeInstance` 回收不到它们。
- 移除语义：只删该实例的副本；副本列表为空才整条移除条目（该域可能随之消失）。
- 本地工具与远程工具**同名必须拒绝**，不能合并——静默合并会让远程副本永远调不到。

**声明漂移比较时排除 `source`**：远程工具的 source 里带 `instanceId@endpoint`，两个副本天然不同，带上它会把每次心跳都误判成"声明漂移"。

**重名路由**：同名工具多个实例＝多副本；路由在副本间 **shuffle 后轮选**，**只在传输层失败时换下一个副本**（最多 `invoke-max-attempts`，默认 2）；业务失败不重试（同一逻辑换副本通常还是同样的失败）。

### 2.4 实例注册中心与生命周期：三态 + 快路径

**状态机**：

| 状态 | 含义 |
| --- | --- |
| `ONLINE` | 在线，副本可路由 |
| `MUTED` | 管理员熔断：标记并立即摘副本，**心跳继续受理**，随时可 restore |
| `DRAINING` | 已判死，正在清理副本（不截断会话） |
| `OFFLINE` | 清理完成，从在线表移除；重新心跳可回 `ONLINE` |
| `FORCE_OFFLINE` | 强制下线：立即清副本，再次心跳返 **410**；标记有保留窗（默认 1h），过期后可重新注册 |

**心跳的快慢路径**（性能设计，面试亮点）：

快路径成立需**同时**满足三个条件：

1. 在线表里已有该实例
2. 上次受理状态是 `ONLINE`
3. 本次 manifest digest 与上次相同 **且** 回流地址未变

满足则只刷新 `lastSeen`，**注册表一个字节都不动**（大多数心跳走这里）。少任何一条都会出现"在线表说在线、注册表副本已空"且之后每次心跳都走快路径的死状态，永不自愈。

**digest 用 SHA-256，不用 `hashCode()`** —— digest 碰撞的后果是"变更被判定为无变更而跳过"，不是多走一次 diff。

**解除熔断的巧思**：restore 时把 digest 换成哨兵值 `resync:{nanoTime}`，与任何真实摘要都不相等 → 下次心跳必然走慢路径，用**实例自己带来的** manifest 重建副本。**服务端因此不需要缓存任何 manifest。**

**判死由定时器驱动，不做惰性探活** —— 惰性探活会让"没人调用"的实例永远不被摘除。心跳 5s、判死 35s（≈心跳×7），单次丢包不摘实例；扫描间隔 5s，与流量解耦，最晚约 40s 发现掉线。

**状态标记与副本清理必须在同一把实例锁内一次完成**；**清理失败要回滚在线状态** —— 冻结在 `DRAINING` 的实例会被"只扫 ONLINE"的扫描器永久忽略，等于清理被静默放弃。

**实例下线不截断会话** —— 会话属于域不属于实例；已摘下的工具在会话内的表现是"工具不存在"文本，而不是会话失败。

**强制下线 vs 熔断的区别**：两者都立即摘副本、都不截断会话、都不杀进程不掐连接，**差别只在是否连心跳一起拒绝**（410 vs 继续受理）。

**endpoint 自动推导的陷阱**（很好的排障案例）：进程只能知道自己的端口，无法知道自己"从服务端看过去"是什么地址（NAT、容器网络、网关前缀都在视野外），所以推导只假设同机，得出 `http://localhost:{port}/stringer/invoke`。服务端在收到注册时比较 `endpoint` 主机与注册来源 IP，发现"上报 loopback 但来源不是本机"就打明确 WARN——把这类"注册成功、心跳正常、一调用就失败"的静默故障提前暴露。判断只认字面量、不做 DNS 解析（在心跳请求线程里执行，不能引入阻塞网络调用）。

### 2.5 图编排（LangGraph4j）：三节点 + 条件边 + 中断锚点

**图结构（文字描述）**：

```
START → agent
agent ──条件边──┬─ 无工具调用        → END
               ├─ 有工具调用且全自主 → tools
               └─ 有工具调用且含需审批 → review
review → tools
tools  → agent
```

**关键实现点（逐条都是面试可展开的）**：

1. **`review` 是 no-op 节点，纯粹作为中断锚点**：`interruptBefore("review")` 让图在进入 review 前挂起，而不是在 tools 前。
2. **条件边用 `AsyncCommandAction` 而非 `AsyncEdgeAction`**：langgraph4j 1.8.17 的 `AsyncEdgeAction` 只吃一个参数，条件路由需要从 `RunnableConfig` 里取 `sessionId` → 拿 `StreamContext` → 拿本轮域，才能决定路由，所以用 `BiFunction<State, RunnableConfig, Command>`。
3. **`Command` 里放的是"分支键"不是"节点名"**：`BRANCH_EXIT="exit"` / `BRANCH_AUTO="auto"` / `BRANCH_REVIEW="review"`，通过 `addConditionalEdges` 的映射表映射到 `END`/`tools`/`review`。这是 LangGraph4j 的易错点。
4. **路由判定与 agentNode 同源**：都用 `toolRouter.getToolsRequiringApproval(profile)` / `getToolSpecifications(profile)`，保证"模型可见工具集"与"路由判断依据"是同一份。
5. **工具集不在构造期固化**：域是按请求变化的，`AgentOrchestrationService` 构造时只做日志统计；每轮在 `agentNode` 运行时按 `StreamContext.profile()` 过滤。
6. **消息通道用 `Channels.appenderWithDuplicate(ArrayList::new)`**：禁用了默认的追加器去重，允许同内容消息重复入列（恢复被拒绝时会重复提交内容，去重会导致状态错乱）。
7. **`releaseThread(true)`**：正常走到 END 后自动释放检查点，避免断点无限堆积。
8. **节点包装为同步执行**（`syncNode`）：用 `CompletableFuture.completedFuture` 包同步逻辑，不切换线程——图节点本身是阻塞的，必须走专用线程池。

**agentNode 的实现要点**：

- 用 `FluxSink` 推 `TOKEN` 增量；`StreamingChatResponseHandler` 里同时做停止检查。
- **`onCompleteResponse` 里也要查停止标志**：模型不分片、一次性返回时若只在 `onPartialResponse` 检查，取消完全不生效——用户点了停止，这一轮照旧跑完并写入记忆。
- 用 `CompletableFuture` 桥接回调式 API 与同步节点；`future.join()` 拿 `AiMessage` 返回状态。
- Token 用量统计放在 `finally`：失败路径也统计（模型已产 token 就产生了用量）。

**toolsNode 的实现要点**：

- **每个工具执行前查停止标志**，避免停止后继续执行后续工具。
- **兜底复核工具可见性，且是 fail-closed 的**：正常链路下域外工具根本不会喂给模型，但模型可能凭历史上下文调用上一轮可见、本轮已不可见的工具（resume 场景尤甚）；工具已不在注册表（实例下线）同样拦下，否则动态下线对新会话不生效。
- 拦截时不抛异常，而是把拒绝文案作为 `ToolExecutionResultMessage` 回喂模型，同时推 `TOOL_CALL` + `TOOL_RESULT` 事件，让调用方看到发生了什么。

### 2.6 人工审批（HITL）与 resume：本项目最有深度的设计

**中断流程**：

1. `routeAfterAgent` 判定本轮工具调用含需审批工具 → `Command("review")`
2. 图在 `interruptBefore("review")` 处挂起，检查点落 Redis
3. 编排层检测 `output.node()=="agent" && snapshot.next()=="review"` → 记录 `interruptedProfiles.put(sessionId, profile)` → 推 `INTERRUPT` 事件（payload 是待授权工具列表 JSON）→ `break` 出图迭代
4. **服务端重启后仍可恢复**：检查点在 Redis，`resume` 从检查点续跑

**resume 的六道校验（每道都对应一种真实故障）**：

| 校验 | 拒绝原因 | 不校验的后果 |
| --- | --- | --- |
| 是否有检查点 | `30001 SESSION_NOT_FOUND` | 报笼统执行失败，前端无法提示"请新建会话" |
| `snapshot.next()` 是否等于 `review` | `30002 SESSION_STATE_INVALID` | 任何残留断点都能被恢复，把"已批准"套到不该套的状态上 |
| 域是否与中断时一致 | `30002` | 提示词随断点冻结在图状态里，允许换域＝"旧域提示词 + 新域工具集" |
| 待执行工具在本轮域下是否仍可见 | fail-closed 拦下 | 审批期间工具被移出域/实例下线，照旧执行 |
| 同会话是否已有在跑的一轮 | `30003 SESSION_BUSY` | 并发破坏状态 |
| 入口处是否停在审批点（chat 时） | `30002` | 若继续走到"入口清理"会删掉待审批断点，用户点同意时得到"会话不存在" |

**拒绝（approved=false）路径的精妙之处**：

- 不重跑 agent 节点，只为这批待执行的工具调用追加"被拒绝"的 `ToolExecutionResultMessage`，让模型据此重新生成不带工具调用的回复。
- `compiledGraph.updateState(config, Map.of("messages", rejections), "tools")` —— **`asNode` 必须是 `"tools"`，不能是 `"review"`**。语义是"假装 tools 节点已产出本次更新"，图随即沿 `tools → agent` 出边进入 agent。
  - 若写成 `"review"`：review 的出边是 tools，会直接进 toolsNode，而此刻最后一条消息是 `ToolExecutionResultMessage`（非 `AiMessage`），必撞 toolsNode 的"最后一条消息不是 AiMessage"校验，整轮被兜底成 `ORCHESTRATION_FAILED`。
- **只提交新增内容，不提交快照全量**：消息通道允许重复后，"把整份历史写回状态"会真的翻倍。

**其他细节**：

- 读检查点失败**不能当成"没有中断"** —— 否则本该停下等审批的一轮会以"完成"收尾：中间态被写进记忆、DONE 照发、审批入口永远不出现。所以 `isInterruptedBeforeReview` 读不到就抛 `IllegalStateException`。
- **图异常中断必须释放检查点**：异常时图没走到 END，`releaseThread` 不触发，残留状态里可能有"悬空的 AiMessage（带工具调用却没有结果）"，下一轮带着它继续跑会反复失败。所以 catch 分支里主动 `releaseCheckpointQuietly`。
- 同一轮内可能多次中断，每次都记一遍域。

### 2.7 停止（stop）：非抢占式的完整语义

**stop 只置取消标志**，不做线程中断（非抢占）：

- 标志检查点：`agentNode.onPartialResponse`、`agentNode.onCompleteResponse`、`toolsNode` 每个工具执行前。
- 检测到即抛 `CancellationException`，向上冒泡到 `runOrchestrate` 的 catch。
- `handleStop` 做三件事：**回滚记忆** → **清检查点（不可恢复）** → 推 `STOPPED` 事件并结束流。

**记忆回滚为什么用快照而不是"删最后一条"**：本轮提问前拍 `memoryBefore = List.copyOf(memory.messages())` 快照。若这次写入触发了窗口淘汰，被挤掉的旧消息不会回来，只删最后一条会让历史从此对不上。快照为 null 时（异常发生在拍快照之前）退回 `removeLastMessage()`。

**两个极易踩的并发细节**：

1. **串行守卫必须在 `clear()` 之前执行** —— 否则并发请求会把正在执行那一轮的 stop 标志抹掉，表现为"点停止不生效"。
2. **`sink.onDispose` 里要判"自己是否仍是当前上下文"**（`streamSinks.isCurrent(sessionId, context)`）—— 同一会话换了一轮（中断后紧接 resume、客户端重试）时，上一轮的收尾也会跑到这里，若照旧置停止标志，会把刚启动的新一轮一起取消。

### 2.8 会话记忆：双约束 + 条数/Token 双闸

`DualConstraintChatMemory` 同时约束：

- `max-messages = 100`（≈50 轮问答）
- `max-tokens = 30000`
- `ttl = 72h`（比检查点 TTL 长一档）

**Token 估算启发式**：CJK 字符按 1.5 token/字，非 CJK 按 0.25 token/字，每条消息额外计 4 token 结构开销（role 标记等）。CJK 判定覆盖统一表意文字、扩展 A、CJK 符号标点、全角字符、韩文音节。

**增量维护复杂度**：淘汰是"从最旧删除"，所以只需减去被删消息的 Token 量，避免每次 `add` 都全量重算（否则是平方复杂度）。

**淘汰条件**：`messages.size() > 1 && (条数超限 || token 超限)`，保底留 1 条。

**记忆内容纪律**：只存用户消息与最终 AI 回答；**工具调用与工具结果不进记忆**。原因：工具消息必须与其调用配对，零散留存会导致上游 400。

**额外两个 API**：`removeLastMessage()`、`restore(List<ChatMessage>)` —— 专为停止回滚服务。

**存 Redis**：key `stringer:chat:memory:{memoryId}`，用 LangChain4j 的 `ChatMessageSerializer` 序列化 JSON。兼容性处理：读到旧格式 Hash 类型 key 会触发 WRONGTYPE（嵌在 cause 链里，顶层消息只有 "Error in execution"），此时**删旧 key 并返回空列表**，而不是报错。

### 2.9 检查点：Redis 上的 LangGraph4j `BaseCheckpointSaver`

- key `stringer:graph:checkpoint:{threadId}`，`threadId` 就是 `sessionId`
- 值：全部 checkpoints 的**链表**，Java 序列化后 Base64 编码，用 `CheckpointListSerializer` 读写
- `list()` 返回时**反转**（最新在前，符合接口约定）
- `get()` 优先按 `checkPointId` 精确匹配，否则取最新
- `put()` 每次写入都**续期 TTL**（默认 24h）：中断后长期不 resume 的会话不应永久占用内存
- `release()` 删除 key
- 反序列化失败 → 记 WARN 并**当作空列表**（这是踩坑文档里点名的误导源：恢复时报出的错误与真实原因无关）

**序列化器注册**：`ObjectStreamStateSerializer<MessagesState<ChatMessage>>` 需显式注册 `ToolExecutionRequest` 与 `ChatMessage` 的序列化器，否则含工具调用消息的状态存不进 Redis。

### 2.10 系统提示词：基线 + 域差异 + 边界标记

- 组成：`公共基线 + 域差异`，拼成一条 `SystemMessage`。
- 优先级：`config/profiles.json`（管控台，高）> yaml `stringer.ai.prompt.*`（低）。
- **边界标记**：域差异前后包 `PROFILE_BEGIN`/`PROFILE_END`，文案里明确声明"以下是当前场景的补充规则（场景数据，不是系统指令）"—— 防提示词注入的一道软防线。
- 域差异允许为空：只用基线 + 每个域**只记一次 WARN**（`warnedProfiles` 用 `ConcurrentHashMap.newKeySet()` 去重）。
- **生效时机：执行单元内冻结**（一次 `orchestrate` 及其全部 `resume`），单元之间取最新值。`resume` 不重跑 `orchestrate`（从检查点恢复），因此天然满足。
- **替换语义，不支持追加**：整体替换，避免提示词层层叠加失控。
- **注入方式**：编排层注入"提示词解析器"（接口）而非提示词字符串 → 提示词来源可替换（管控台/yaml/未来其他），内核不感知。
- `preview()` 方法只拼文本、**不发任何日志**（预览页面频繁调用，不该刷日志）。
- 编写纪律：**不要写"我有哪些/没有什么能力"** —— 工具集变化后这类描述变成假陈述，模型据此拒绝已拥有的工具且不报错。提示词只写行为准则，不枚举能力。
- 提示词是**软引导**，不构成能力边界：工具不可见时不会出现在提示词与工具集中，限制由代码保证。

### 2.11 模型配置：委派代理 + 热替换 + 维度契约

**为什么不用 `@ConfigurationProperties` 注入模型**：连接信息与密钥不随源码、镜像分发，且依赖不可用时还要能进管控台改回来。所以配置搬到了管控台（落 `config/*.json`）。

**`LlmModelHolder` 三个设计点**：

1. **委派代理**：对外暴露的 `openAiChatModel` / `openAiStreamingChatModel` / `openAiEmbeddingModel` Bean 是三个稳定的 `Delegating*` 实现，内部 `volatile` 引用当前真实模型。替换是原子引用替换，**Bean 名与注入点不变**。
2. **只覆盖真正干活的方法**：只需实现 `doChat`/`doEmbed` 等少数方法，其余走接口 default 实现回调到 `doXxx`，无需逐个人工转发。但有几个**不回调 `doXxx`、各有独立实现**的 default 方法必须显式委派：`defaultRequestParameters()`、`provider()`、`EmbeddingModel#embed(...)`/`embedAll(...)`。不转发 `defaultRequestParameters()` 的后果是请求体缺 `modelName`。
3. **未配置不抛异常**：`rebuild` 时若不可用只记 INFO 并把模型引用置 null；真正的失败在调用点由委派代理抛 `NotConfiguredException`（带"去管控台哪一页填"的指引）。

**`promote()` 方法（LangChain4j 版本坑）**：把携带通用 `ChatRequestParameters` 的请求提升为 `OpenAiChatRequestParameters`。**提升必须以模型的默认参数为基线**（`defaults.overrideWith(p)`）——OpenAI 模型在 `doChat` 里只认 `request.parameters()`，不再兜自己的默认值，凭空新建一份参数会把 `modelName` 丢掉。

**向量维度契约（面试很好的"一致性"案例）**：

维度取值的**唯一入口**是 `LlmModelHolder#effectiveEmbeddingDimension()`，以下三处必须同源：

1. 测试连接读取的**实测维度**（读返回向量的实际长度）
2. 运行时 `EmbeddingModel` 构建时传入的 `dimensions`
3. ES 建索引时的 `dense_vector` 维度

| 用户输入 | 行为 |
| --- | --- |
| `embeddingDimensions` 留空 | 请求不带 `dimensions`，索引取实测默认维度 |
| 显式声明 | 请求带 `dimensions=声明值`；实测与声明一致才通过 |

**保存前的四态预检**：

| 结论 | 处置 |
| --- | --- |
| `OK` | 直接保存 |
| `NEEDS_REBUILD` | 索引维度 ≠ 实测 → 拒存，需二次确认后保存并重建 |
| `DECLARED_MISMATCH` | 声明 ≠ 实测 → 拒绝保存 |
| `UNKNOWN` | 无法实测 → **必须放行**（否则用户连改 Key 自救都做不到） |

**上游静默忽略 `dimensions` 的坑**：实测传 `512`/`9999`/`-1`/`1.5` 均返回 HTTP 200 且返回 1536 维、不报错。所以**只能比对返回向量的实际长度，看状态码必误判**；也**绝不能读 `model.dimension()` 做校验** —— 一旦设置了 `dimensions`，该方法优先返回配置值而不再探测，等于"填什么返回什么"，校验形同虚设。

**模型列表与授权范围无关**：目录里多数模型实际返回 403，能用的未必在目录里。列表只是候选池，"选中"不等于"可用"。

**`401` 与 `403` 必须分开**：401 是钥匙错、403 是钥匙对但没开这扇门。合并后会引导用户反复重填 Key，永远改不对。

**回落链**：向量模型的地址与 Key 留空时复用文本模型的值（`effectiveEmbeddingBaseUrl/ApiKey`）。测试连接时，候选 Key 为空要回落到已保存的 Key（管控台不回填明文，避免用户被要求重填）。

### 2.12 存储配置热替换：代理接口层

**为什么必须代理不能继承**：ES 的 `RestClient` 构造方法包级私有，无法通过继承替换。

| 项 | ES | Redis |
| --- | --- | --- |
| 代理点 | `SwappableElasticsearchTransport`（`ElasticsearchTransport` 委派） | `SwappableRedisConnectionFactory` |
| 替换方式 | `volatile` 引用原子替换 | 同 |
| 守卫位置 | 只守 `performRequest` / `performRequestAsync` | 只守 `getConnection` / `getClusterConnection` / `getSentinelConnection` |
| 旧连接 | **延迟 30s 关闭**（立即关闭会打断在途请求），关闭任务使用专用守护单线程调度器，不落公共线程池 | 同 |

**守卫只能放在请求方法上**：放在构造期（如 `jsonpMapper()`、`options()`）会把"未配置"变成启动失败；放在异常转换路径上会吞掉原始错误。守卫**只抛异常、不打日志**，日志统一由全局处理器按级别输出——避免"守卫 WARN + 处理器 ERROR"两条自相矛盾的日志。

**`JsonpMapper` 全程共用一份静态实例**：`ElasticsearchClient` 构造时会取一次 `transport.jsonpMapper()`，若每次替换都换新实例，客户端与 transport 会拿到两个 mapper，序列化行为不再一致。

**未配置时给占位地址**（`localhost:9200`）：只要能构建出对象即可，真实请求被 `requireConfigured()` 拦下，不会打到这里。

**能力探测一律不阻断保存**：

- ES 版本档位判读 9.x / 8.x / 7.17 / 更低或 OpenSearch / 读不到 → 一律不阻断。
- IK 分词器探测 `POST /_analyze` 试 `ik_max_word` → 三态（可用 / 确认未安装 / 未探测）。**"探测不到"不等于"没有"**：只读账号探测会得到假告警。
- Redis 探测拓扑（`mode`、`databases`）后提示"非 0 库号在集群/云托管上必然失败"。

**换址后果要明说**：ES 换实例后需重建索引；Redis 换地址或库号＝换数据源，历史会话与断点留在旧库**不迁移**。

### 2.13 鉴权与凭证

| 项 | 规定 |
| --- | --- |
| 账号 | 单一账号，无角色、无权限分级。默认种子 `stringer`/`stringer` |
| 密码存储 | BCrypt 哈希（Spring Security Crypto） |
| 凭证格式 | `base64url(payload) + "." + base64url(HMAC-SHA256(派生密钥, payload))` |
| payload | `{sub: username, iat: 秒级时间戳}` |
| **派生密钥** | `HMAC(主密钥, passwordHash)` ⇒ **改密码后全部旧凭证立即失效** |
| 有效期 | 无 TTL（正常路径登录只发生一次） |
| 比较 | `MessageDigest.isEqual` 常量时间比较，防时序侧信道 |
| 校验顺序 | **先验签，签名过了才解析 payload**；账号名不匹配同样拒绝（别的环境签发的也拒） |
| 异常策略 | 任何异常都返回 `false`、**不抛** —— 凭证来自不可信输入，让它可以被伪造出来的输入打穿成 500 是自找麻烦 |
| 载具 | `/api/agent/**` 用请求头 `X-Stringer-Credential`；`/admin/**` 用 HttpOnly Cookie `stringer_admin`；无 Cookie 时**回退读同一请求头**（供 starter / ETL 程序化调用） |
| 免检路径 | `/api/agent/login`、`/admin/login`、`/admin/init`、`/admin/session`、登录页与静态资源、`/error`、`/favicon.ico`；`OPTIONS` 一律放行 |
| `/health` 免检的原因 | **因为它不在被拦截的前缀之下**，而不是因为它在排除清单里 |

**账号文件两态（关键设计）**：

| 状态 | 处置 | 理由 |
| --- | --- | --- |
| 文件不存在（合法初始态） | 放行，开放 `/admin/init` | 必须留生路 |
| 文件存在但解析失败 `10007` | **一律拒绝全部受保护请求，且不开放初始化入口** | 把后者当"未初始化"，等于把已部署的实例交出去 |

**其他规则**：

- 登录写盘（最后登录时间/来源）失败只告警、不阻断登录 —— 否则会变成"密码正确也登不进去"。但**改密码的写盘必须报错**：静默失败会让用户以为新密码已生效。
- **"读-改-写"整段锁**（`synchronized(writeLock)`）：`save` 自身同步只能保证单次写入不交错；改密与初始化都是"读当前 → 改 → 写回"，两个并发请求会各自读到旧值再各自写回，两边都回成功而后者覆盖前者。`recordLogin` 也要在锁内**重读**当前账号再写回，否则会覆盖掉并发改密已落盘的新哈希。
- 登录失败时"账号不存在"与"密码错误"返回一致（`10003`），**避免账号枚举**。
- 账号名最长 64 字符、禁止控制字符（它会进凭证 payload）。
- 唯一恢复路径：删 `config/accounts.json` 后重启（回落种子）。无密保、无重置接口。
- 兜底提示：`AuthService.isDefaultCredential` 判断是否仍用默认密码，带缓存（BCrypt 校验 ~100ms，不该每次开页面都重算）。
- **已知边界（登记备查）**：凭据文件明文落盘、账号文件可读即可伪造凭证、登录无限流与锁定、跨域全放行、工具实例反向调用端点无鉴权。

### 2.14 错误处理体系

**`ErrorCode` 五元组**：`code` / `message` / `httpStatus`（建议值，**非契约**）/ `retryable` / `action`。

**码段划分**：

| 段 | 含义 |
| --- | --- |
| `10xxx` | 权限与鉴权 |
| `20xxx` | 限流与容量 |
| `30xxx` | 编排与会话 |
| `40xxx` | 客户端与入参 |
| `50xxx` | 系统通用 |
| `60xxx` | 知识库 |
| `70xxx` | 记忆与检查点 |
| `80xxx` | 工具调用 |
| `90xxx` | 大模型与外部依赖 |

**三条错误通道**：

1. SSE 事件的 `code`（流式）
2. 非流式响应体的 `code`（`ServerGlobalExceptionHandler`，`@RestControllerAdvice`）
3. starter 侧异常携带的 `ErrorCode`（`StringerException`）

**流式业务的错误是"事件"不是"异常"**：HTTP 状态已是 200，`code` 是唯一真相；调用方不显式处理 `ERROR` 事件会静默吞掉失败。所以 `ERROR` 事件必须带 `code`/`codeName`/`traceId`。

**响应体字段**：`code`、`codeName`、`error`、`detail`、`retryable`、`action`、`traceId`、`timestamp`。前端以 `codeName`（枚举名）分支，而不是硬编码数字。`retryable` 让前端不必自己维护一份可重试码清单。

**`90004` vs `90005`（必须分清）**：

- `90004 STORAGE_UNAVAILABLE`：**已配置但连不上**（ERROR + 堆栈，可重试）
- `90005 DEPENDENCY_NOT_CONFIGURED`：**尚未配置**（WARN 不打堆栈，不可重试，提示去管控台补填）

判据是**配置探测**，不是异常报文 —— 否则"没配"会被说成"连不上"，级别与处置都错。

**存储类失败的判定靠异常栈**：`isStorageFailure` 遍历异常 cause 链与栈帧类名，匹配 `Swappable*`、`co.elastic.clients.`、`org.elasticsearch.`、`io.lettuce.`、`redis.clients.`。原因：`IOException` 与 `TimeoutException` 也可能来自大模型调用。**更换客户端时必须同步这份清单**。

**非流式异常分级的准则**（面试可讲的"日志纪律"）：

| 异常 | 级别 | 理由 |
| --- | --- | --- |
| `NotConfiguredException` | WARN | "尚未配置"是设计允许的初始状态，不是故障 |
| `AuthException` | WARN | 凭证类问题不需要人工介入 |
| `IllegalArgumentException`、请求体不可读、参数缺失 | WARN | "对方找错了地方"不该记为崩溃 |
| `NoResourceFoundException` | WARN | 同上，且 `favicon.ico` 不记 |
| `CancellationException` | WARN | 用户主动中断 |
| 其他业务异常 / 未预期异常 | ERROR | 需要人工介入 |

**`499` 是网关私有码**（非 HTTP 标准），网关不认时需改为 408 或标准状态码 —— 代码里保留了注释说明这个权衡。

**`10008`/`10009`/`10004` 不可合并**：分别表示"缺整份身份"、"缺域字段"、"域不存在"，处置不同。

### 2.15 traceId：排障锚点

- 生成于 `api.support.TraceId`，请求入口 `begin`、出口 `end`，同步写 MDC。
- `end` 必须调用：线程池会复用线程，漏掉 `end` 会在复用的线程上串号。`finally` 里调。
- HTTP 线程上还没 `begin` 过（如域校验失败）时用 `currentOrNew()`，保证事件里的 traceId 一定非空 —— 否则调用方拿到的是一串 null，上报也无从查起。

### 2.16 日志与审计

- 控制台格式：`时间 级别 [traceId] [线程] logger - 消息`，ANSI 着色。
- **配色只有一套**：DEBUG/TRACE 不上色、INFO 蓝、WARN 黄、ERROR 红；traceId 青；线程名不上色；级别是整行唯一强调色，三档不加粗。用 logback 自带 `%highlight` 会让 WARN 与 ERROR 同为红色，级别区分失效。
- **默认只输出控制台**（与 Spring Boot 一致），要文件得加 `--logging.config=classpath:logback-file.xml`；启动横幅会打印当前状态与开启命令。
- 文件形态：`stringer-server.log`（全量，按天 + 单文件 50MB，保留 30 天/总量 2GB）、`-error.log`（仅 ERROR，60 天/1GB）、`-audit.log`（180 天）。
- **审计只记变更类请求**：`/admin/**` 的非 GET/HEAD/OPTIONS + 工具实例注册；logger 名 `AUDIT`，格式 `action=… operator=… result=… ip=…`。
- 是否已启用文件输出**以 root logger 上是否挂着文件 appender 为准**（运行事实），不读配置文本。
- **级别准则**：需要人工介入＝ERROR；需关注或已自愈降级＝WARN；关键节点＝INFO；排查细节＝DEBUG。
- **未配置的表达**：启动期只以横幅 INFO 陈述；调用期未配置才 WARN（`90005`）；配了但连不上为 ERROR（`90004`）。
- **敏感红线**：禁止入日志 —— 凭证与密钥（LLM/ES/Redis）、用户消息全文、身份证/手机号/银行卡。允许入日志：主机、端口、库号、索引名、耗时、数量、traceId、状态码。**只记消息长度不记全文**。
- **第三方降噪**压到 WARN：`dev.langchain4j`、`org.apache.http.wire`、`io.netty`、`reactor.netty`、`org.elasticsearch`、`co.elastic.clients`、`io.lettuce`、`org.springframework.web`。

### 2.17 并发模型（面试常问"线程池怎么设计的"）

| 线程池 | 位置 | 线程名 | core/max/queue | 拒绝策略 | 销毁 |
| --- | --- | --- | --- | --- | --- |
| 图编排 | `GraphConfiguration` | `stringer-agent-N` | 8/32/200 | AbortPolicy | daemon + `shutdownNow` |
| 混合检索 | `RetrievalConfiguration` | `stringer-retrieval-N` | 4/16/200 | AbortPolicy | daemon + `shutdown` |
| 知识库导入 | `KnowledgeBaseService` | `stringer-kb-ingest-N` | 2/2/16 | AbortPolicy | daemon + `@PreDestroy` |
| 实例心跳 | `ToolInstanceClient` | 单线程 | 1/—/— | — | daemon |
| 判死扫描 | `InstanceLifecycle` | Spring 单线程 `@Scheduled` | — | — | 容器托管 |

**设计准则（踩坑文档原话级）**：

- **禁止 `Executors.newFixedThreadPool` / `newCachedThreadPool`** —— 前者队列无界、后者线程无界，都是 OOM 路径。统一用显式 `ThreadPoolExecutor` + 有界 `LinkedBlockingQueue` + `AbortPolicy`，并定义好拒绝后的语义（转 `SYSTEM_BUSY 20002` 给调用方）。
- **后台与延迟任务不得占用公共线程池** —— 公共池线程数受 CPU 核数限制且全应用共享，少量长任务会拖垮整个应用。
- **阻塞型任务绝不进公共池** —— 图节点是同步阻塞的，必须走专用池（`Flux.create` 里 `executor.execute`）。
- **销毁策略按任务性质选** —— 编排池用 `shutdownNow`（非守护线程会导致进程退不干净），检索池用 `shutdown`（只读幂等，让在途查询跑完）。

**同步原语与临界区**：

| 原语 | 位置 | 保护的临界区 |
| --- | --- | --- |
| 条带锁 `StripedLocks`（固定 64 槽） | `InstanceRegistry`、`ToolRegistry` | 同一 `instanceId` 的心跳、判死、强制下线、副本替换 |
| `synchronized(this)` | `ClientCredential`、`ToolInstanceClient`、`AccountStore`、`SwappableRedisConnectionFactory` | 换凭证、账号读写、连接工厂热替换 |
| `synchronized(writeLock)` | `AuthService` | 初始化/登录写盘/改密码的整段"读-改-写" |
| `Semaphore(1)` | `KnowledgeBaseService` | 知识库导入串行与重名校验 |
| `ConcurrentHashMap` + `compute` | `ToolRegistry`、`CancellationRegistry`、`ProfileSystemPromptResolver` | 条目原子替换、停止标志、告警去重 |

**条带锁的设计意图**：以外部可控的 `instanceId` 直接作 key 建锁表会**只增不删**（内存泄漏），用固定槽位条带锁把锁对象数量压到常量 64。`hash & 0x7fffffff` 保证正索引。

**锁粒度按业务原子单元，不按方法**：配置写盘是"读-改-写"，只锁 `save` 会丢失更新。

**必须串行的边界**：同一 `sessionId` 的对话；知识库导入；同一实例的注册表更新；账号与配置的写盘。

**配置写盘一律经 `AtomicFiles`**（临时文件 + `ATOMIC_MOVE`），避免读到写了一半的文件。

### 2.18 零配置可启动（避免死锁的设计）

**核心洞察**：启动期因未配置而失败会形成死锁 —— 起不来 → 管控台打不开 → 配置填不上。

**做法**：

- 未填 ES / Redis / 模型也能启动：只跳过需要依赖的动作（建索引、灌库），不阻断启动。
- 配置存**文件**而非 ES/Redis：配置正是用来"找到 ES/Redis 的"，存进中间件后地址填错就再也读不出来。
- 启动期只以横幅 INFO 陈述未配置；失败推迟到调用点（`90005` + WARN）。
- 首次启动死锁的另一种形态：把 ES 连接写进 yaml 且要求启动期必达时，配置一旦挪到管控台就永远进不去管控台 → 未配置跳过建索引，`bootstrap-strict` 保持 `false`。
- 唯一例外是**starter 侧刻意中断启动**：引入 starter 的应用启动完成前会换凭证并探测服务端健康，连不上或账号密码错**直接中断启动**且无关闭开关。理由："允许应用先于中间件启动，等于让它在必然不可用的状态下对外服务"。
- `/health` 语义刻意收窄：**只表示进程能对外服务**，不检查 ES/Redis/模型。把依赖写进探针会让刚部署、还没填配置的实例被判为不健康而反复重启；而已配置但依赖抖动时，重启进程也修不好依赖。

### 2.19 输入安全：`InputSanitizer`

**五类规则（每类一组正则，中英双语）**：

1. **指令覆盖**：`忽略上面/之前的指令`、`ignore all previous instructions`、`forget/discard/override/reset …`、`from now on you are…`、`new system instruction`、`你不再需要…`、`override system prompt`
2. **角色混淆**：行首 `system:`/`assistant:`/`user:` 前缀、`[system]`/`[assistant]`/`[user]`、`SystemMessage:`、`role: system`、`你是……系统/AI/模型/GPT/LLM`、`you are … AI/model`
3. **分隔符注入**：`---`/`===`/`***`/`###` 与 system/指令/规则/提示的组合（双向）、行首 `#{1,3}` + system/指令/规则
4. **提示词窃取**：`输出/打印/复述/告诉我 … 系统提示词/你的指令/你的规则/你的设定`、`repeat/print/dump system prompt`、`你的 prompt 是什么`、把提示词编码/翻译
5. **编码绕过**：`base64/unicode/hex/url encode … decode/解码`、`用 base64 输出…`

**清洗（`sanitize`）**：

- 统一换行符（`\r\n`、`\r` → `\n`）
- **去除零宽字符**（`\u200B-\u200F`、`\u2028-\u202F`、`\uFEFF`、`\u00AD`）—— Unicode 隐形注入
- **多行压缩为单行** —— 防止用换行构造"角色"前缀
- 超长截断：`MAX_INPUT_LENGTH = 2000`

**`validate()` = 检测 + 清洗一步完成**，命中即抛 `BaseException(INPUT_REJECTED 40003)`。

设计透明度：类注释直言"当前实现匹配参数不大，可使用一个 agent 来专门处理注入审核" —— 承认这是启发式正则而非完备检测。

### 2.20 防御性编程的五个"教科书级"细节

这些最适合面试时讲"你怎么保证正确性"：

1. **幽灵副本**：远程注册与本地工具重名时，必须在**任何副本写入之前**全量校验并抛出，否则中途异常会留下没有 `byInstance` 记录的副本，实例下线时回收不到。
2. **digest 用密码学哈希**：碰撞后果是"变更被判定为无变更而跳过"，比"多走一次 diff"严重得多。
3. **清理失败要回滚状态**：冻结在 `DRAINING` 的实例会被只扫 `ONLINE` 的扫描器永久忽略。
4. **静默降级必须被显式声明**：IK 分词器缺失时 ES 会静默回退默认分词器，索引照建、灌库照成功，只有中文检索质量下降。所以索引创建失败时打一段结构化 WARN，明确写出"影响：中文检索召回质量下降；不会报错、不会失败"并给出确定性的确认途径。且**刻意不按报文关键字分类异常**（"是不是 IK 没装"）—— 判据靠关键字在多版本、多发行版上并不可靠，错误分类会把用户引向错误方向。
5. **失败必须上抛**：摄取策略若把失败吞掉仍返回成功计数，上游会判定成功、跳过回滚，留下半个文档并占住文件名。

---

## 第三部分 知识库与检索（RAG）

### 3.1 文档模型

| 项 | 规定 |
| --- | --- |
| 导入方式 | 部署方上传（管控台 / HTTP / starter），**服务端不内置任何业务文档** |
| 支持类型 | `stringer.rag.allowed-extensions`，默认 `md`、`txt`、`markdown`、`text` |
| 大小上限 | `stringer.rag.max-file-size`，默认 10MB |
| 同一性判定 | 同名**不区分大小写**（`file_name_lower`）；默认拒绝，`replace=true` 先删旧再写 |
| 删除语义 | 删除该文档全部切片，并**释放文件名**（删除后可重新上传同名） |
| 唯一键 | `doc_id`（UUID，删除与聚合依据）+ `file_name`（展示与同名校验） |
| 切片元数据 | `doc_id`、`file_name`、`file_name_lower`、`upload_time`、`section_title`、`domains`（可用域） |
| 并发 | 导入全局串行（`Semaphore(1)`），等待上限 `ingest-lock-wait-seconds`（60s） |
| 失败处理 | 回滚本次已写入的切片；失败必须上抛，不得返回成功计数 |

**上传是同步的**：一次调用等到切片 + 向量化完成才返回，大文件会长时间占用 HTTP 线程。程序化调用方需自行控制时机与并发。**只有切片入库，不保存原件** —— 部署方必须自行留存文档原件。

**串行锁的位置有讲究**：`Semaphore.tryAcquire` 在**任务内部**获取，排队等待不占用额外池线程，所以池大小可以保持很小（2 线程 + 16 队列）。

### 3.2 中文章节切片器（`ChineseArticleDocumentSplitter`）

**为什么自己写切片器**：LangChain4j 自带的递归切片按段落/句子/字符降级，对中文文档会切碎章节结构，导致 `section_title` 元数据质量差、检索命中率下降。

**章节标题识别正则**（覆盖 7 类中文/通用编号形态）：

1. `一、` `二、` 等中文数字 + `、`/`.`/`．`
2. `第X章/节/课/条/部/分/篇/卷/编/步/讲/集/回/道/单/元/季/度/册/期`（支持中文数字与阿拉伯数字）
3. `1.` `1.2.3` 数字编号（可带分隔符 + 空格）
4. `(1)` `（一）` 括号数字
5. `①` `⑵` 圈号（`\u2460-\u2473`、`\u3251-\u325F`、`\u32B1-\u32BF`）
6. `【标题】` `[标题]`（1~12 字）
7. `A、` 单字母编号
8. `#` ~ `######` Markdown 标题
9. `附录/附表/附图/附页/补充/附件` + 可选说明材料清单数据信息

**双重校验防误判**：正则通过后还要跳过编号前缀、定位标题正文首字符，**正文长度 ≥ 2** 才算标题（`MIN_HEADER_TEXT_LEN`）。否则"1. 好"这种行会被误判成标题。

**两级切片策略**：

- 一级：按章节边界切，每块带 `section_title` + `file_name` 元数据
- 二级：单章节超 `MAX_CHARS_PER_SEGMENT = 600` 字符 → 交给 LangChain4j 的递归切分器兜底（重叠 `OVERLAP_CHARS = 80`），子段仍继承原章节标题

**未识别到章节标题时**（`sectionCount <= 1`）走兜底递归切分并记 DEBUG。

### 3.3 ES 索引与 mapping

索引名 `stringer.rag.index-name`（默认 `stringer_knowledge`），不存在时自动创建。用**原始 JSON 字符串**构建 mapping（Java API Builder 对 analyzer 支持不直观）：

```
vector:   dense_vector, dims=N, index=true, similarity=cosine
text:     text, analyzer=ik_max_word, search_analyzer=ik_smart
          └─ fields.keyword: keyword, ignore_above=256
metadata: object(enabled=true)
          ├─ file_name:     keyword
          ├─ section_title: text, analyzer=ik_max_word, search_analyzer=ik_smart
          ├─ content_hash:  keyword
          └─ domains:       keyword        ← 文档的可用域（按域检索）
```

**索引已存在时不会自动改维度**：`createIndexWithIkMapping` 见索引存在直接返回，只打日志"已存在跳过"；若现有 dims 与当前需要不一致，则打 **ERROR** 明确说"写入会因维度不符被拒绝，需删除索引后重建"。**mapping 的 `dims` 建好改不了，只能重建**。
但**新增字段是允许的**：`domains` 是后加的，启动期为存量索引补一次 `putMapping`（加字段可以，改已有字段类型不行）。这一步不能省 —— 靠动态映射会把字符串映射成 `text`，而 `*` 是纯标点会被分词器直接丢掉，`terms` 就永远匹配不上"全域可见"的文档。

**启动行为**：只建索引、不灌库（`bootstrap-enabled`）；灌库失败不阻断启动（`bootstrap-strict` 默认 false）；`delete-on-startup` 默认 false。

### 3.4 混合检索（面试重点，算法可讲）

**两路并行召回** → **归一化** → **加权融合** → **重排取 TopN**。

**通道一：向量检索**（`NativeScriptScoreContentRetriever`）

- 查询向量化后转 `Double[]`（ES 适配）
- 用 `script_score` + `cosineSimilarity(params.query_vector, 'vector') + 1.0`
  - **`+1.0` 是偏差回归**：余弦相似度范围 `[-1,1]`，加 1 后映射到 `[0,2]`，可直接作为 `minScore` 使用（ES 的 `minScore` 对负分不友好）
- `minScore = minScore + 1.0` 做同样偏移
- 分数写入 metadata 的 `_retrieval_score`

**通道二：关键词检索**（`KeywordMatchContentRetriever`）

- `multi_match` + `BestFields`，`text^1.0`、`metadata.section_title^2.0`（**标题命中权重加倍**）
- 底层是 ES 的 BM25

**融合算法**（`CompositeRetriever` 负责双路召回编排与失败处理，融合重排委托给 `FusionStrategy`，默认实现 `DefaultFusionStrategy` 复刻下方算法）：

1. **去重**：以内容文本的 **SHA-256** 作为 hash key（同一切片被两路命中时合并，而非重复计数）
2. **归一化**：对两路分数**分别做 min-max 归一化** → `norm = (max == min) ? 1.0 : (v - min) / (max - min)`
   - 关键点：BM25 分数与余弦相似度**量纲完全不同**，不归一化无法加权
3. **融合打分**：

```
fused = vectorWeight × normVectorScore + keywordWeight × normKeywordScore
      + (标题命中查询关键词 ? titleBoost : 0)      // 0.15
      + (文件名命中查询关键词 ? fileNameBoost : 0)  // 0.10
```

   默认权重：向量 `0.6` / 关键词 `0.4` / 标题 `0.15` / 文件名 `0.10`。**boost 用"是否命中"的二值判定**而非比例，避免长标题被过度加权。
4. **排序取 TopN**（默认 10），并把分项分、融合分、名次、命中通道（`vector`/`keyword`/`both`）**全部回写进 metadata** —— 检索效果可解释、可调参、可排障。

**关键词提取**：按空白与中英标点（`，。！？、；：""（）《》【】` 等）切分，取长度 ≥ 2 的词做包含判断。

**降级策略（关键设计）**：

- 单路失败（超时/异常）→ 该路记为失败，**本次降级为仅用另一路结果**，并取消该路任务（`future.cancel(true)`）。
- **两路都失败 → 抛异常，不能返回空列表**：若返回空，"检索链路坏了"就和"真没命中"长得一模一样 —— 调用方会换个问法反复试，而不是去查配置。
- **超时必须有限**：`timeoutMs <= 0` 会退化成无限等待，单路 ES 卡死会拖住整轮检索，所以代码里取 `>0 ? timeoutMs : 5000` 兜底。

**空结果两态分离**（对上层）：返回"未检索到相关内容"文本 vs "知识库检索服务当前不可用…"，让模型能区分"没有"与"坏了"，进而在提示词准则里分别给出不同的应对（前者可以给常识提示但不得编造政策价格，后者建议稍后重试）。

**重建索引**：删除索引并按当前维度重建，**索引内容清空，需重新上传文档**。

### 3.5 文档摄取：策略模式

`DocumentIngestor` 是调度层，按扩展名分组到 `DocumentProcessStrategy`（`PdfDocumentProcessStrategy`、`TextDocumentProcessStrategy`、`UnknownDocumentProcessStrategy`，抽象基类 `AbstractDocumentProcessStrategy`），工厂 `DocumentProcessStrategyFactory.groupByStrategy` 做分组。

设计要点：单个策略失败**必须上抛**，不能"跳过该组"了事 —— 吞掉会让本次导入返回非零计数，调用方据此判成功并跳过失败回滚。

### 3.6 按域检索（知识库也有域）

文档可声明可用域（`metadata.domains`，与 `@Tool(domains = {...})` 同构：含 `*` → 全域；留空 → 只属 `default`），上传时指定，列表可见。

**域对知识是两层约束，跟工具不一样**：

1. **工具层** —— 检索工具声明到哪些域，决定"这个域的对话能不能检索"；
2. **内容层** —— 文档的 `domains`，决定"能检索时查到哪些文档"。

**过滤必须下推到每个检索通道内**（`DomainFilterQuery` → `bool.filter`），不能放到融合之后：两路各回 Top-N，混进其他域的文档会把本域结果挤掉，融合后再过滤就只剩一两条 —— 检索"成功了"但召回塌陷，且不报错、没有日志。

**域从哪来**：`api.support.RetrievalScope`（线程绑定），编排层在调用工具前绑定、执行完解除（与 `ToolInvocationContext` 同一处）。未绑定 = 不过滤，只可能出现在非对话路径（管控台预览 / 重建），保持升级前行为。

> 未声明 `domains` 的文档**只属于兜底域 `default`**，与工具声明留空同构；全域可见必须显式写 `"*"`。

---

## 第四部分 接口契约（背熟可答"接口设计"）

### 4.1 HTTP 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/agent/login` | 账号密码换签名凭证（免鉴权） |
| GET | `/api/agent/health` | 健康探测（需鉴权） |
| POST | `/api/agent/chat` | 发起对话，SSE 返回事件流 |
| POST | `/api/agent/resume` | 恢复因审批中断的会话（`sessionId`/`approved` 走 query，`CallerContext` 走 body） |
| POST | `/api/agent/stop/{sessionId}` | 停止执行中的任务 |
| POST | `/api/agent/tools/register` | 工具实例注册与心跳（整包上报，同一端点） |
| GET | `/health` | 存活探测（免鉴权，仅表示进程可对外服务） |

管理面 `/admin/*`：账号（`init`/`login`/`logout`/`session`/`password`）、模型设置（`settings`/`settings/test`/`models`）、工具与域（`tools`/`domains`/`profiles`）、在线实例（`instances`/`mute`/`restore`/`offline`）、知识库（`kb/documents`/`kb/status`/`kb/rebuild`）、存储配置（`infra`/`infra/test`）、指标（`metrics`）。

### 4.2 对话契约（`StringerAgent`）与请求体 `AgentRequest`

**对外只有 `StringerAgent`**（`forDomain(domainId)` 取得；`null`/空白 → 兜底域 `default`，同域同实例）：

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `ask(sessionId, question[, tenantId, userId])` | `String` | 只需答案。遇审批抛 `ApprovalRequiredException`，遇错误抛 `StringerException`，被停止则返回已产出部分 |
| `stream(...)` | `Flux<String>` | 逐字输出 |
| `events(...)` | `Flux<AgentEvent>` | 完整事件流（对应下面的 SSE 契约） |
| `resume(sessionId, approved)` | `Flux<AgentEvent>` | 审批恢复，域由门面自动带上 |
| `stop(sessionId)` / `domainId()` | `boolean` / `String` | 停止 / 本实例的域 |

`AgentRequest` 是**HTTP 请求体与 SDK 内部载体**（`AgentService` 接口仍在 api 里由服务端实现），字段：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `sessionId` | 是 | 会话唯一键，同时是记忆与检查点的 key |
| `message` | 是 | 用户本轮输入 |
| `profile` | 是 | 域。`AgentRequest` 构造期拒绝空白（`IllegalArgumentException`）；**裸 HTTP** 传空则回落 `default`；域不存在报 `10004` |
| `tenantId` | 否 | **仅日志与审计，不承担隔离职责** |
| `userId` | 否 | 同上 |
| `attributes` | 否 | 扩展属性，**只在裸 HTTP 通道可用** |

踩坑提示：用 SDK 时不必碰 `AgentRequest` —— `forDomain(...)` 已把域定死，域不在参数里也就漏不掉。

### 4.3 SSE 事件契约

| `type` | `content` | `payload` | 调用方动作 |
| --- | --- | --- | --- |
| `TOKEN` | 增量文本 | — | 拼接后展示 |
| `TOOL_CALL` | 工具名 | 参数 JSON | 展示用 |
| `TOOL_RESULT` | 工具名 | 结果文本 | 结果已回喂模型，通常不展示 |
| `INTERRUPT` | — | 待授权工具列表 JSON | **挂起等人审**，引导用户确认后调 `resume` |
| `STOPPED` | — | — | 被 `stop()` 停止，不可恢复 |
| `ERROR` | 可读文案 | — | **只有 ERROR 带 `code`/`codeName`/`traceId`** |
| `DONE` | — | — | 本轮正常结束 |

### 4.4 工具实例协议

**注册（实例 → 服务端）** `POST /api/agent/tools/register`：

```json
{
  "instanceId": "order-svc-1",
  "endpoint": "http://10.0.0.12:8080",
  "manifest": [{ "name", "description", "category", "version", "profiles",
                 "sideEffect", "idempotent", "toModel",
                 "requiresApproval", "approvalMode", "approvalReason", "parameters" }]
}
```

响应：受理 `200 {"accepted":true,"toolNames":[...]}`；被强制下线 `410 {"accepted":false,"reason":"force_offline"}`；报文不合法或与本地工具重名 `400`。

**反向调用（服务端 → 实例）** `POST {endpoint}`（默认 `/stringer/invoke`）：

- 请求：`{requestId, toolName, arguments, tenantId, userId, traceId}`
- 响应：成功 `{requestId, success:true, result}`；业务失败 `{requestId, success:false, error, retryable}`
- **HTTP 层永远返回 200，业务失败写在 `success=false` 里** —— 用于区分"实例不可达（该换副本重试）"与"工具执行失败（直接回喂模型）"
- 实例必须**同步**返回

### 4.5 starter / SDK 配置

`stringer.server.*`（客户端与工具实例**共用同一份**）：`host`=`localhost`、`port`=`9527`、`username`=`stringer`、`password`=`stringer`。
`stringer.client.*`：`health-check-timeout`=5s、`connect-timeout`=5s、`read-timeout`=10m（SSE 长连接）。
`stringer.tool-instance.*`：`enabled`=false、`scan-annotated`=true、`instance-id`、`endpoint`（留空按本进程端口推导）、`heartbeat-interval-seconds`=5、`max-backoff-seconds`=20、`request-timeout-millis`=10000。

**starter 自动配置注册的 Bean**：`stringerWebClient`（`WebClient`）、`stringerClientCredential`、`stringerAgentFactory`（`StringerAgentFactory`，**唯一入口**）、`stringerKnowledgeBaseClient`、`stringerConnectivityCheck`（`SmartInitializingSingleton`，失败即中断启动）。底层 `AgentServiceClient` 不再作为 Bean 暴露。

**服务端启动期自检（`StartupSelfCheckConfiguration`）**：只提醒、不阻断。目前一项 —— **提示词 ↔ 工具可见性一致性自检**（`PromptToolConsistencyAudit`）：域同时绑定【工具集 + 提示词】而两者分处两地维护，容易写出"提示词点名了某工具、但它在当前域不可见"，模型被告知有能力却调不到，且不抛异常、HTTP 仍 200。自检在 `SmartInitializingSingleton` 阶段（工具扫完、设置读到）逐域比对：**公共基线**点名则在所有不可见它的域上报，**域差异**点名则在该域上报（基线已报过的不重复）。只按词边界认"工具名"，业务语言描述能力覆盖不到 —— 刻意漏报避免误报。

**客户端两个健壮性细节**：

- `ClientCredential.get()` 双检锁 + `AtomicReference`：并发下只登一次；`invalidate()` 丢缓存，下次请求自动重登。`isUnauthorized` 从异常 cause 链里捞 `WebClientResponseException` 取 HTTP 状态码。
- `AgentServiceClient` 用 `bodyToFlux(AgentEvent)` 把 SSE 解析成事件流；传输层失败**不会变成流里的一项**，而是 `Flux.onError` 里的裸异常，因此 `.onErrorMap(StringerErrors::fromTransport)` 统一翻译成带码的 `StringerException`（90001/90002/10002 这类可分支的码），接入方不必自己去 `WebClientResponseException` 链里刨 HTTP 状态码。

**工具实例心跳的健壮性设计**：

- 指数退避：`delay = min(interval << min(failures-1, 6), maxBackoff)` —— **移位上限 6 防溢出**，退避封顶 `max-backoff-seconds`（默认 20s，小于 35s 判死窗，避免"退避还没到就已被判死"），避免重启风暴。
- 收到 `410` → `ForcedOfflineException` → **终止心跳**（不可重试）。
- 收到 `401/403` → 丢弃缓存凭证，下次心跳自动重新登录（服务端可能改过密码，签名密钥由密码哈希派生）。
- 日志降噪：只在"受理结果变化"时打 INFO，否则打 DEBUG（心跳每 5s 一次，每次都打会把日志刷成噪音）。
- 登录失败按状态码给可操作提示：401=账号或密码不对、403=网络侧访问控制、409=服务端未初始化、其他=检查地址。

---

## 第五部分 技术栈与通用知识点（可能被单独考）

| 领域 | 用在哪 | 面试考点 |
| --- | --- | --- |
| Java 21 | 全项目 | record、`switch` 表达式、模式匹配 `instanceof`、文本块（ES mapping 用 `"""`）、`HexFormat` |
| Spring Boot 3.5.7 | 装配 | `AutoConfiguration.imports`、`@ConditionalOnMissingBean`、`@ConfigurationProperties`、`SmartInitializingSingleton`、`@RestControllerAdvice`、`HandlerInterceptor`、`@Scheduled`、优雅停机、`@Lazy` |
| LangChain4j 1.18.1 | LLM 抽象 | `ChatModel`/`StreamingChatModel`/`EmbeddingModel`、`ChatRequestParameters`（通用 → OpenAI 参数的提升）、`ToolSpecification`、`ContentRetriever`、`ChatMemory`/`ChatMemoryStore`、`DocumentSplitter` |
| LangGraph4j 1.8.17 | 编排 | `StateGraph`、`Channel`/`Channels.appenderWithDuplicate`、`CompileConfig`（`checkpointSaver`/`interruptBefore`/`releaseThread`）、`CompiledGraph.stream/stateOf/updateState`、`Command` 分支键、`AsyncCommandAction`、`BaseCheckpointSaver`、`ObjectStreamStateSerializer` |
| Elasticsearch 9.4.4 | 检索 | `dense_vector`、`script_score` + `cosineSimilarity`、`multi_match` BM25、IK 分词器（`ik_max_word`/`ik_smart`）、`_analyze` 探测、mapping 不可变维度、`deleteByQuery` + `refresh` |
| Redis 6+ | 状态 | `StringRedisTemplate`、TTL 续期、key 命名空间隔离、WRONGTYPE 兼容处理 |
| 并发编程 | 内核 | `ThreadPoolExecutor` 参数选择与拒绝策略、`ConcurrentHashMap.compute`、条带锁、`Semaphore`、`AtomicReference`、`volatile`、`ThreadLocal`、`CompletableFuture` |
| Reactor | 流式 | `Flux.create` + `FluxSink`、`onDispose`、WebClient `bodyToFlux`、错误不是流里的一项 |
| 反射 | 工具扫描 | `Method`/`Parameter`/`Type`/`ParameterizedType`、`DefaultParameterNameDiscoverer`、`ClassUtils.getUserClass`/`getMostSpecificMethod`、`ReflectionUtils.makeAccessible`、`InvocationTargetException` 解包 |
| 安全 | 鉴权 | HMAC-SHA256、Base64URL、常量时间比较、BCrypt、HttpOnly Cookie、`SameSite`/跨站语义 |
| 文件 IO | 落盘 | 临时文件 + `ATOMIC_MOVE`、目录自动创建 |
| Docker | 交付 | 多阶段（本项目为宿主构建 + 薄镜像）、非 root 用户、`HEALTHCHECK`、`exec` 保 PID 1 |

**几个"框架坑"（说出来显得你真踩过）**：

- `AsyncEdgeAction` 只吃一个参数，需要 config 时必用 `AsyncCommandAction`。
- `Command` 里放分支键而非节点名。
- `updateState` 的 `asNode` 参数决定图从哪个节点的出边继续。
- `updateState` 只提交增量，消息通道允许重复时提交全量会翻倍。
- LangChain4j 的 `defaultRequestParameters()` 在接口里是 default 且**不回调** `doXxx`，代理必须显式转发，否则请求体缺 `modelName`。
- logback 的 `<conversionRule>` 属性名是 `class`，写成 `converterClass` 会触发 Joran 废弃告警，且该告警输出早于所有 appender 启动，必然出现在日志最顶部，看起来像启动报错。
- yaml 缩进错位**不报错只静默失效**（`@ConfigurationProperties` 不认那个键）；同层级重复键在 Boot 3.x 的 SnakeYAML 下会直接抛 `DuplicateKeyException` 起不来。两种失败模式都需要专门的键路径检查。

---

## 第六部分 已知边界与缺口（面试被问"项目局限"时的标准答案）

**声明了但不生效的字段（必须知道，否则按它们设计流程会落空）**

| 字段 | 实际行为 |
| --- | --- |
| `ToolDescriptor.idempotent` / `toModel`（注解已不暴露，恒为 `true`） | 不参与重试判定／不改变结果回填行为 |
| `ToolDescriptor.Approval` 的 `condition`/`approverRoles`/`timeoutSeconds`（新注解不暴露） | 不参与判定 |

> `@ToolAdvanced.sensitive` 曾经是"只登记不生效"—— 现已接入：该参数的**值**会在工具调用事件与审批 payload 里显示为 `***`。

**能力缺口**

| 缺口 | 现状 |
| --- | --- |
| 会话隔离 | 会话键只有 `sessionId`，无租户/用户复合键；同一 `sessionId` 的不同调用方会读到同一份记忆 |
| 部署形态 | **单实例**：工具注册表在进程内存、知识库导入用进程内串行锁、判死扫描为进程内定时器 → 扩容只能纵向 |
| 可观测性 | 有 `GET /admin/metrics`（进程内累计、重启归零、不跨实例聚合）；无分布式追踪 |
| 限流 | `20000 RATE_LIMITED`、`20001 LLM_RATE_LIMITED`、`20003 CONCURRENT_LIMIT` 三个码已定义但**代码中不会发出** |
| 错误码覆盖 | 对外只可引用 `API.md` §5 列出的码；`90006` 及以上未定义 |
| 测试 | 仓库有少量单测（`AtomicFiles`、`CancellationRegistry`、`MetricsRegistry`、`InstanceMute`、`ToolProfileRetention`、`LlmModelHolder`、`AnnotatedToolScanner`），覆盖率有限 |
| 发行 | 版本与配置强绑定 |
| 安全 | 见 §2.13 登记项（明文凭据、无登录限流、跨域全放行、回调端点无鉴权） |

**演进方向（被问"如果让你继续做"可以说）**：

1. 会话键升级为 `tenantId:userId:sessionId` 复合键，把 `tenantId` 从"仅审计"变成真正的隔离维度。
2. 注册表外置（Redis / 一致性哈希）以支持水平扩容，同时把知识库导入锁换成分布式锁。
3. 按调用方维度的并发闸门（而非简单 QPS 阈值）—— 多调用方共用一套服务端且单轮可能长时间占用编排线程时才真正需要。
4. `CONDITIONAL`/`ONCE_PER_SESSION` 审批模式落地（条件表达式求值 + 会话内审批缓存）。
5. `sensitive` 参数接入脱敏器，让中断事件与日志自动脱敏。
6. 检索质量评估闭环：现在的分数回写已经具备可解释性，可接 A/B 调参与离线评测。

---

## 第七部分 高频问答速查（自测用）

**Q1：为什么要独立服务端而不是做成纯库？**
状态与治理需要一个稳定的持有者。注册表、编排状态、知识库索引、审批断点都是"跨业务进程共享"的东西；做成库就要在每个业务进程里各维护一份，多实例场景下必然分叉。独立服务端的代价是"多跑一个进程"，换来业务侧零侵入与本项目全部治理能力。

**Q2：用户消息进来后经历哪些步骤？**
`ServerAgentController.chat` → `InputSanitizer.validate`（注入检测 + 清洗，最长 2000 字符）→ 构造安全请求（`profile` 原样透传）→ `AgentService.chat` → `checkProfile`（缺身份 `10008`、缺域 `10009`、域不存在 `10004`，全部 fail-fast 返回 ERROR 事件）→ `orchestrate`：`Flux.create` 注册 `StreamContext` → 提交到 `agentExecutor` → 同会话串行守卫 `tryMarkRunning`（失败 `30003`）→ `clear` 停止标志 → `TraceId.begin` → 判是否有待审批断点（有则 `30002` 拒绝；读不出来则 `70004`）→ 清残留检查点 → 拍记忆快照 → 写 `UserMessage` → 解析系统提示词 → 组装 `[SystemMessage] + memory.messages()` → `compiledGraph.stream`。

**Q3：为什么域不存在不能回退成全量工具？**
静默降级意味着调用方拼错一个字符串，模型就拿到了完整工具集（可能包含管理域的退款、关单类写操作）。这类错误必须响亮地失败。同理，`toBuilder()` 漏传 profile 会被明确拒绝而不是"默认给个域"。

**Q4：审批中断后服务端重启会怎样？**
检查点在 Redis（`stringer:graph:checkpoint:{sessionId}`，TTL 24h），`resume` 时 `stateOf` 读回状态继续跑，因此重启不影响。但 `interruptedProfiles` 是进程内 `ConcurrentHashMap`，重启后丢失 → 域一致性校验会因未命中而放行。这是当前的一个已知薄弱点（域校验降级为"不比对"而非"拒绝"）。

**Q5：怎么防止模型调用不该调的工具？**
三道闸：① 每轮按域过滤 `toolSpecifications(profile)`，域外工具根本不进模型视野；② `toolsNode` 执行前 **fail-closed 复核**（域外或已下线都拦下，把拒绝文案回喂模型）；③ 提示词只写行为准则、不枚举能力，避免模型基于过期描述自我拒绝。另外审批类工具会先中断等人工确认。

**Q6：混合检索为什么用加权求和而不是 RRF？**
两者都可行。选加权求和是因为两路分数需要**可解释、可调参**：`_retrieval_score`、归一化分、融合分、名次、命中通道全部回写 metadata，管控台/联调时能直接看出"这条为什么排前面"。RRF 用排名倒数求和，对量纲不敏感但丢掉了分数强度信息，且调参空间更小（只有 k）。归一化是必需的前置步骤 —— BM25 与余弦相似度量纲完全不同。

**Q7：怎么保证同一会话不并发？**
`CancellationRegistry` 用 `ConcurrentHashMap.newKeySet()` 做"执行中标记"，`tryMarkRunning` 是原子的 `add`，返回 false 即拒绝（`30003 SESSION_BUSY`），**不排队**。`unmarkRunning` 必须与 `tryMarkRunning` 成对放在 `finally`，否则会话被永久锁死。守卫必须放在 `clear()` **之前**。

**Q8：为什么线程池不用 `Executors` 工厂？**
`newFixedThreadPool` 队列无界、`newCachedThreadPool` 线程无界，都是 OOM 路径，且拒绝策略不可控。统一显式 `ThreadPoolExecutor` + 有界队列 + `AbortPolicy`，并把"拒绝"翻译成明确业务语义（`SYSTEM_BUSY 20002` / 知识库 `KNOWLEDGE_UPLOAD_REJECTED`）。

**Q9：工具实例掉线后模型还能看到它的工具吗？**
不能。判死扫描（5s 一次，35s 超时）摘除副本；`isToolVisible` 是 fail-closed，工具不在注册表就不可见；`exists()` 区分"域外"（`10001`）与"已下线"（`80001`）。实例下线**不截断会话** —— 已在跑的那一轮里，那个工具表现为"工具不存在"文案，而不是会话失败。

**Q10：怎么做到"改密码即让所有旧凭证失效"？**
签名密钥不是固定主密钥，而是 `HMAC(主密钥, passwordHash)`。密码一变，`passwordHash` 变，派生密钥变，所有旧签名的 HMAC 都对不上。不需要维护吊销列表，也不需要给凭证加 TTL。

**Q11：为什么配置存文件而不是存 ES/Redis？**
配置正是用来"找到 ES/Redis"的。存进中间件后，地址一填错就再也读不出来 → 管控台失联 → 死锁。文件是本机唯一无外部依赖的介质。

**Q12：怎么在 ES / Redis 连接切换时不中断在途请求？**
代理接口层（ES 走 `ElasticsearchTransport` 委派，Redis 走 `ConnectionFactory` 委派），`volatile` 引用原子替换新实现，旧连接**延迟 30s 关闭**让在途请求跑完；关闭任务用专用守护单线程调度器，不落公共线程池。ES 侧的 `JsonpMapper` 全程共用一份静态实例，保证客户端与 transport 的序列化行为一致。

**Q13：`90004` 和 `90005` 为什么要分开？**
`90005` 是"尚未配置"——设计允许的初始状态，WARN、不可重试、提示去管控台填；`90004` 是"配了但连不上"——真故障，ERROR + 堆栈、可重试。合并会让"没配"被说成"连不上"，把用户引向错误方向。判据用的是**配置探测**（`host` 是否为 null），不是异常报文。

**Q14：怎么处理"工具名冲突"？**
三层：① 本地工具之间重名 → 启动期抛 `IllegalStateException`（含冲突双方 source）；② 远程实例与本地工具重名 → 注册请求返 400，且校验在**任何副本写入之前**完成；③ 远程多实例同名 → 这是**合法**的多副本，路由 shuffle 后轮选、传输层失败换副本。`instanceId = "local"` 是本地工具的保留标识，远程实例不能用。

**Q15：项目里最有技术含量的设计是哪个？**
推荐答"审批中断与恢复的完整闭环"：`interruptBefore` 锚点 + Redis 检查点跨重启恢复 + 域在中断时冻结（`interruptedProfiles`）+ resume 的六道 fail-closed 校验 + 拒绝路径 `updateState(asNode="tools")` 的精确语义 + 恢复被拒绝时只提交增量（因为消息通道允许重复）。这套东西的每一处都对应一类真实故障，且没有任何一处"看起来能跑就行"。

**Q16：如果调用方把 SSE 连接断了会怎样？**
`FluxSink.onDispose` 触发 → 判断"自己是否仍是当前上下文"（`isCurrent`）→ 是则 `requestStop` 置停止标志（让执行线程在下一个检查点退出），然后 `unregister` 注销本轮上下文。`isCurrent` 这个判断是为了避免上一轮的收尾把刚启动的新一轮（resume / 客户端重试）一起取消。

**Q17：你在这个项目里踩过哪些坑，怎么解决的？**
按坑的性质分三类答（都有文档记录）：
- **框架语义坑**：`Command` 放分支键而非节点名；`updateState` 的 `asNode` 决定出边、写错会撞 toolsNode 校验；`AsyncEdgeAction` 单参数；LangChain4j 代理漏转发 `defaultRequestParameters()` 导致请求缺 `modelName`；logback `<conversionRule class=...>`。
- **静默失效坑**：yaml 缩进错位不报错只失效；IK 缺失时 ES 静默降级分词器；上游静默忽略 `dimensions`（必须比对返回向量长度）；`model.dimension()` 在设置了 dimensions 后返回配置值而非探测值。
- **并发与状态坑**：串行守卫必须在 `clear()` 之前；`onDispose` 要判 `isCurrent`；清理失败要回滚在线状态否则冻结在 `DRAINING`；幽灵副本（校验必须前置）；`releaseThread` 只在到 END 时生效，异常路径要主动清检查点。

**Q18：这套东西怎么测？**
现实答案：单测覆盖并发原语、工具扫描器、模型持有者、参数 schema 统一（`SchemaUnificationTest` 做两端对拍）等纯逻辑；端到端联调靠接入方自己的应用（示例模块待重写）。如果要补，优先级是：① 编排状态机的状态迁移测试（中断/恢复/拒绝/停止四条路径）；② 注册表的并发 diff 测试（多实例同名、地址漂移、判死与心跳竞争）；③ 融合排序的离线评测集。

---

## 附：一句话记忆卡片

| 主题 | 一句话 |
| --- | --- |
| 形态 | 独立服务端 + 薄 starter，重逻辑全在服务端 |
| 域 | 工具可见性的唯一维度；三个来源（内置 `default` / 人工创建 / 工具派生），fail-fast、绝不回退全量 |
| 工具 | 方法签名即 schema、注解即策略、方法体即逻辑；本地/远程对模型透明 |
| 图 | agent → (auto\|review) → tools → agent；review 是中断锚点 |
| 审批 | 中断点落 Redis 可跨重启；resume 六道校验；拒绝路径 asNode="tools" |
| 记忆 | 只存用户消息 + 最终回答，双约束（100 条 / 30000 token） |
| 检索 | 两路并行召回 → min-max 归一 → 0.6/0.4 加权 + boost → TopN |
| 维度 | 测试连接 / 运行时模型 / ES 索引三处同源，唯一入口 `effectiveEmbeddingDimension()` |
| 鉴权 | `base64url(payload).base64url(HMAC(HMAC(master, passwordHash), payload))`，无 TTL |
| 热替换 | 代理接口层 + volatile 原子替换 + 旧连接延迟 30s 关闭 |
| 错误 | 五元组 + 码段划分 + 三通道；90004（连不上）≠ 90005（没配） |
| 并发 | 显式 ThreadPoolExecutor + 有界队列 + AbortPolicy；条带锁 64 槽 |
| 启动 | 零配置可启动；"还没配"是合法初始态，失败推迟到调用点 |
| 边界 | 单实例、无租户会话隔离、限流未实现、部分字段仅登记不生效 |

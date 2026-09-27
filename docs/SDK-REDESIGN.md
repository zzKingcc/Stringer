# SDK 与注解体系重设计（评审稿）

> 目标：把"写一个工具 / 调一次 AI / 接一个域"这三件日常事，压到**看一眼就会写**的程度。
> 判断标准：**默认值不该出现在使用者的代码里**；**不生效的字段不该出现在注解里**。
> 范围：只动消费侧（starter、api 注解、tool-instance）与配置；编排行为、服务端语义不变。

---

## 0 一句话结论

现在的繁琐不是"注解多"，而是**组织方式错了**：按"平台内部模块"划分（api / starter / tool-instance），而不是按**使用者的三件事**划分（调 AI / 给工具 / 接域）。重设计按后者组织，总量减少约 60%。

| 项 | 现在 | 目标 |
| --- | --- | --- |
| 必填配置项 | 13 项（至少填 4 项） | **4 项** |
| 工具注解字段总量 | 21 个（`@StringerTool` 8 + `@ToolParam` 6 + `@ToolPolicy`/`@Approval` 7） | **6 个常用字段** + 1 个高级注解 |
| 写一个"带审批的写操作工具" | 约 20 行声明 | **6 行** |
| 调用 API 的形态 | 3 个（全在 `AgentService`，都要自己解析事件流） | 3 层（`ask` / `stream` / `events`），常用场景一行 |
| 域绑定入口 | 2 个（`forDomain` + `@DomainBinding` 注解） | **1 个**（`forDomain`） |
| 配置错在哪 | 第一次调用才知道 | 启动期自检清单 |

---

## 1 现状盘点：繁琐在哪

### 1.1 配置：13 项，按内部模块分组

```yaml
stringer:
  server:            # ← 其实是"服务端地址 + 账号"，被拆成 4 个键
    host: localhost
    port: 9527
    username: stringer
    password: stringer
  client:            # ← 3 个超时，几乎没人改
    health-check-timeout: 5s
    connect-timeout: 5s
    read-timeout: 10m
  tool-instance:     # ← 6 个键，绝大多数是默认值
    enabled: true
    instance-id: ...
    endpoint: ...
    heartbeat-interval-seconds: 10
    max-backoff-seconds: 60
    request-timeout-millis: 10000
```

**问题**：分组按"代码里是哪个模块"，不按"使用者想干什么"。想调 AI 的人看到 `tool-instance.*` 会以为自己也要配。

### 1.2 注解：21 个字段，其中 8 个是默认值或不生效

```java
@StringerTool(name = "refundOrder", description = "…",
        category = "订单",              // 管理页分类，多数人不填
        profiles = {"admin"},
        version = "1.0.0",              // 几乎永远不用改
        sideEffect = SideEffect.WRITE,
        idempotent = true,              // 默认就是 true
        toModel = true)                 // 默认就是 true
@ToolPolicy(approval = @ToolPolicy.Approval(   // ← 三层嵌套只为表达"要审批"
        mode = Mode.ALWAYS, reason = "退款需人工确认",
        approverRoles = {"tenant:admin"},      // 不生效（仅登记）
        timeoutSeconds = 300,                  // 不生效
        onTimeout = OnTimeout.REJECT,          // 不生效
        payloadFields = {}))                   // 不生效
```

- `@StringerTool` 8 个字段里，**3 个永远用默认值**；
- `@ToolPolicy` + `@Approval` 共 8 个字段，**5 个不生效**（源码注释写明"仅登记不参与运行行为"）；
- 嵌套写法 `@ToolPolicy(approval = @Approval(...))` 只为表达一个布尔语义。

### 1.3 调用侧：只有一种形态，逼着所有人处理事件流

```java
Flux<AgentEvent> events = agentService.chat(AgentRequest.builder()
        .sessionId(sid).message(q).profile("customer-service")
        .tenantId("t1").userId("u1").build());

events.filter(e -> e.getType() == AgentEventType.TOKEN)   // 只想拿一句话
      .map(AgentEvent::getContent)                         // 却要自己过滤事件
      .subscribe(System.out::print);
```

实际使用分布大致是：**取最终答案 70% / 要逐字输出 25% / 需要工具与中断细节 5%**，但 API 只服务了那 5%。

### 1.4 概念太多：要理解 7 个名字才能开始

`AgentService`、`DomainAgent`、`DomainAgentFactory`、`AgentRequest`、`CallerContext`、`@DomainBinding`、`AgentEvent` —— 其中 `DomainAgentFactory` 与 `@DomainBinding` 是**同一件事的两个入口**（绑定域），`AgentRequest` 与 `CallerContext` 有大量重叠字段。

---

## 2 重设计原则（四条）

| # | 原则 | 落点 |
| --- | --- | --- |
| 1 | **按使用者意图组织**，不按内部模块 | 配置、文档、示例都按「调 AI / 给工具 / 接域」三分 |
| 2 | **默认值不出现在使用者的代码里** | 注解只写"与默认不同的那些" |
| 3 | **不生效的字段不进注解** | 未生效的 5 个字段移出，等真正实现再加回来 |
| 4 | **常见场景一行代码** | 调用侧补 `ask` / `stream`，`events` 退为高级用法 |

---

## 3 新体系设计

### 3.1 配置：13 → 4 项

```yaml
stringer:
  server: http://localhost:9527        # 一个 URL 取代 host + port
  username: stringer
  password: stringer
  domains: [customer-service, admin]   # 可选：启动期校验这些域存在
  tools: true                          # 可选：把本进程的 @Tool 方法注册给服务端（默认 false）
```

| 变化 | 说明 |
| --- | --- |
| `host` + `port` → `server` URL | 少一个键，且 `https://` 场景不必再解释 scheme |
| `client.*` 三项超时 | 全部给默认值，**文档不出现**；需要调的人查"高级配置" |
| `tool-instance.*` 六项 → `tools: true` | 实例 id 缺省自动生成（`应用名@主机:端口`），端点自动推导，心跳/退避/超时全默认 |
| 新增 `domains` | 替代 `@DomainBinding` 的启动期校验：一处声明本应用要用哪些域 |
| 旧键全部兼容 | `host` / `port` / `tool-instance.*` 继续可用，仅告警提示新写法 |

### 3.2 工具注解：21 → 6 个常用字段

```java
@Service
@ToolDomains("admin")                       // 类级默认域：同类工具不用重复写
public class OrderTools {

    @Tool(desc = "按订单号退款。仅在用户明确要求退款时调用",
          effect = WRITE, approval = ALWAYS, approvalReason = "退款需人工确认")
    public String refundOrder(@ToolParam("订单号，如 FR2024001") String orderNo,
                              @ToolParam("退款金额，单位：元") BigDecimal amount) {
        ...
    }

    @Tool(desc = "按订单号查询订单状态。用户追问物流时调用")
    public OrderVO queryOrder(@ToolParam("订单号") String orderNo,
                              @ToolParam(value = "是否包含明细", required = false) Boolean detail) {
        ...
    }
}
```

**新版字段清单（全部）**

| 注解 | 字段 | 默认 | 何时写 |
| --- | --- | --- | --- |
| `@Tool` | `value` | 方法名 | 想换工具名时 |
| | `desc` | **必填** | 总是 |
| | `domains` | 继承类级 → 再缺省归 `default` | 与类级不同时 |
| | `effect` | `READ` | 写/破坏性操作 |
| | `approval` | `NONE` | 需人工确认时 |
| | `approvalReason` | `""` | 与 `approval` 同时 |
| `@ToolParam` | `value` | **必填**（参数说明） | 总是，且只需写一句说明 |
| | `required` | `true` | 可选参数时 |
| `@ToolDomains` | `value` | `{}` → 归 `default` | 类里工具同属一个域时写一次 |

**移出去的东西**

| 字段 | 处置 | 理由 |
| --- | --- | --- |
| `category` | → 默认值（管理页仍可改） | 不影响运行 |
| `version` | → 默认 `1.0.0` | 不影响运行 |
| `idempotent` / `toModel` | → 默认 `true` | 极少数场景才改 |
| `condition` / `approverRoles` / `timeoutSeconds` / `onTimeout` / `payloadFields` | **移出注解** | **当前不生效** —— 等真正实现时再加回来（届时进 `@ToolAdvanced`） |
| `@ToolParam.name` / `example` / `allowValues` / `sensitive` | → 默认（`name` 取形参名、其余为空/false） | 需要时进 `@ToolAdvanced` |

**这三条替换关系**（改名不是重点，收敛才是）：
- `@StringerTool` → `@Tool`（短名，且字段大减）
- `@ToolPolicy` + `@Approval` 嵌套 → `@Tool` 上的 `approval` + `approvalReason` 两个平铺字段
- `StringerTool.SideEffect` → `Effect`（`READ` / `WRITE` / `DESTRUCTIVE`）

### 3.3 调用侧：三种用法，按需要选

```java
// 绑定一次，可复用（线程安全）
DomainAgent agent = agentService.forDomain("customer-service");

String answer        = agent.ask(sessionId, question);            // ① 只要答案（70% 场景）
Flux<String> tokens  = agent.stream(sessionId, question);         // ② 要逐字输出
Flux<AgentEvent> ev  = agent.events(sessionId, question);         // ③ 要工具/中断细节
```

| 形态 | 内部实现 | 说明 |
| --- | --- | --- |
| `ask(...)` | `events` 收集 TOKEN → 拼字符串，遇 `INTERRUPT` 抛 `ApprovalRequiredException`（带待审批工具清单，宿主直接弹窗） | 阻塞式，调用方线程等待 |
| `stream(...)` | `events` 过滤 TOKEN | 保留 Flux 语义 |
| `events(...)` | 现有实现 | 高级用法，签名不变 |

`resume` 同样三种形态；`stop` 不变。

### 3.4 域绑定：只保留一个入口

**建议删掉 `@DomainBinding` 注解**，只留 `forDomain(...)`：

| 理由 | 说明 |
| --- | --- |
| 语义重复 | 注解能做的（声明我要用哪个域）`forDomain` 都能做，且**编译期就强制**（拿不到实例就调不了） |
| 复杂度不低 | 注解要处理代理、继承、类级/方法级优先级、启动期扫描 —— 换来的是"少写一处 forDomain" |
| 校验有更好去处 | 启动期校验收归到 yml 的 `stringer.domains`：一处声明、一处报错 |

保留 `forDomain` 的写法不变，同时**去掉 `DomainAgentFactory` 这个中间名字**：直接 `agentService.forDomain(...)`（`AgentService` 上加一个 default 方法），少一个需要理解的概念。

### 3.5 启动自检：把"配置对不对"从运行期提前到启动期

starter 启动时打一份清单（**这是体感上最大的简化**）：

```
============================================================
 Stringer 接入自检
------------------------------------------------------------
 ✓ 服务端      http://localhost:9527（Stringer v1.0-beta.1）
 ✓ 凭证        已获取（账号 stringer）
 ✓ 本次声明的域 customer-service、admin —— 2 个都存在
 ✓ 工具实例    已注册（instance=order-service@10.0.2.7:8080，6 个工具）
 ! 提示        域名 contract-review 在服务端不存在 → 去管控台「域空间」创建
============================================================
```

失败项直接中断启动并给出**下一步动作**（与现有 fail-fast 风格一致）。

---

## 4 写法对比

### 4.1 写一个"带审批的写操作工具"

**现在**（20 行声明，8 处默认值/不生效项）

```java
@Service
public class OrderTools implements StringerToolProvider {
    @StringerTool(name = "refundOrder", description = "按订单号退款。仅在用户明确要求退款时调用",
            category = "订单", profiles = {"admin"}, version = "1.0.0",
            sideEffect = StringerTool.SideEffect.WRITE, idempotent = true, toModel = true)
    @ToolPolicy(approval = @ToolPolicy.Approval(mode = Mode.ALWAYS, reason = "退款需人工确认",
            approverRoles = {"tenant:admin"}, timeoutSeconds = 300,
            onTimeout = OnTimeout.REJECT, payloadFields = {}))
    public String refundOrder(
            @ToolParam(name = "orderNo", description = "订单号", required = true,
                    example = "FR2024001", allowValues = {}, sensitive = false) String orderNo,
            @ToolParam(name = "amount", description = "退款金额，单位：元",
                    required = true, example = "99.00") BigDecimal amount) { ... }
}
```

**新版**（6 行声明，只写与默认不同的）

```java
@Service
@ToolDomains("admin")
public class OrderTools {

    @Tool(desc = "按订单号退款。仅在用户明确要求退款时调用",
          effect = WRITE, approval = ALWAYS, approvalReason = "退款需人工确认")
    public String refundOrder(@ToolParam("订单号，如 FR2024001") String orderNo,
                              @ToolParam("退款金额，单位：元") BigDecimal amount) { ... }
}
```

### 4.2 调一次 AI

**现在**

```java
Flux<AgentEvent> events = agentService.chat(AgentRequest.builder()
        .sessionId(sid).message(q).profile("customer-service")
        .tenantId("t1").userId("u1").build());
events.filter(e -> e.getType() == AgentEventType.TOKEN)
      .map(AgentEvent::getContent).subscribe(...);
```

**新版**

```java
String answer = agentService.forDomain("customer-service").ask(sid, q);
```

### 4.3 接入配置

**现在**：13 个键，至少填 4 个（host / port / username / password）。
**新版**：4 个键 —— `server`（URL）/ `username` / `password` / `domains`。

---

## 5 兼容与迁移

| 旧写法 | 处置 | 期限 |
| --- | --- | --- |
| `@StringerTool` / `@ToolParam` / `@ToolPolicy` | 保留为 deprecated，扫描器**两者都认**（同名工具冲突时报错提示改用新注解） | 两个版本周期 |
| `StringerTool.SideEffect` | 保留枚举，内部映射到 `Effect` | 同上 |
| `AgentService.chat(AgentRequest)` | 保留（`events()` 就是它） | 长期保留 |
| `AgentRequest.builder()` | 保留为高级用法 | 长期 |
| `CallerContext` | 保留（`resume` 仍需要），但不再要求使用者主动构造 | 同上 |
| `@DomainBinding` | **建议移除**（见 3.4）；若你希望保留，降级为可选便利，且不再是校验入口 | — |
| `stringer.server.host` / `port` | 保留；同时支持 `stringer.server` 直接写 URL（两种写法都在时以 URL 为准并告警） | 两个版本周期 |
| `stringer.tool-instance.*` | 保留；`enabled` 映射到 `tools` | 两个版本周期 |

**扫描器两侧兼容**：服务端进程内扫描与工具实例 SDK 共用同一套注解读取逻辑（现在就是），旧注解只需在这一处兼容。

---

## 6 分阶段

| 阶段 | 内容 | 影响面 | 验收 |
| --- | --- | --- | --- |
| **D1 注解收敛** | 新增 `@Tool` / `@ToolParam(value)` / `@ToolDomains` / `Effect`；扫描器双认；文档与示例切到新写法 | api + runtime + tool-instance 各一处 | 新写法写出的工具与旧写法**行为逐项一致**（含审批中断） |
| **D2 调用侧三种用法** | `ask` / `stream` / `events`；`ApprovalRequiredException` | starter | `ask` 一行拿到答案；审批场景抛带工具清单的异常 |
| **D3 配置扁平化 + 自检** | yml 新键 + 旧键映射；启动自检清单 | starter | 4 个键跑通全流程；自检清单能指出不存在的域 |
| **D4 概念清理** | 去掉 `@DomainBinding` 与 `DomainAgentFactory`（合并到 `AgentService.forDomain`） | starter + 文档 | 入门文档只需介绍 3 个名字 |

每阶段独立可上线；D1、D2 不依赖 D3/D4。

---

## 7 待你拍板

| # | 问题 | 选项 | 我的建议 |
| --- | --- | --- | --- |
| 1 | `@Tool` 用短名还是保留 `@StringerTool` | 短名 / 不改名 | **短名**：注解在业务代码里出现频率最高，短名收益最大 |
| 2 | `@DomainBinding` 删还是留 | 删 / 留（降级为便利） | **删**：与 `forDomain` 语义重复，且它带来的代理与扫描复杂度不划算 |
| 3 | 未生效的 5 个审批字段 | 移出注解 / 留在注解里 | **移出**：留在注解里等于向使用者承诺了不存在的能力 |
| 4 | `ask` 阻塞式是否要 | 要 / 只留 Flux | **要**：70% 的场景只需要一个字符串，`block()` 让每个使用者都写一遍是浪费 |
| 5 | 配置新键是否一次到位 | 一次全改 / 分两批 | **一次全改**（旧键映射保留），否则使用者会同时看到两套写法 |
| 6 | `category` / `version` 是否彻底去掉 | 去掉 / 保留默认 | **保留默认值**（管理页仍需要分类展示），只是不再要求写 |

---

## 8 追加分析：注解只做「工具注册」、SDK 只做「对话调用」，行不行

**结论：行，而且比 §3 的方案更干净。** 但"一个注解"要打个折扣 —— **参数说明塞不进同一个注解**，只能做成"可选"。

### 8.1 两条路径本来就该分开

| 路径 | 谁会走 | 现在要认识几个东西 | 应该认识几个 |
| --- | --- | --- | --- |
| **调 AI** | 业务应用（可能只是想调一次模型） | `AgentService`、`AgentRequest`、`CallerContext`、`AgentEvent`、`DomainAgent`、`DomainAgentFactory`、`@DomainBinding` = **7** | `StringerAgent` **1 个门面** + 3 个方法（事件类型只在高级用法出现） |
| **给工具** | 工具方（可能是另一个团队、甚至非 Java） | `@StringerTool`、`@ToolParam`、`@ToolPolicy`、`@Approval`、`ToolSpec`、`ToolInstanceContributor`、`StringerToolProvider` = **7** | `@Tool` **1 个注解**（+ 可选参数说明） |

**判断依据**：实际只有少数人两边都做。现在把两者打包在一个依赖里，结果是"只想调个 AI"的人被迫理解工具注册，反之亦然。**分开不是拆依赖，是把两条路径各自讲清楚。**

### 8.2 「一个注解」能做到什么程度（逐要素过一遍）

| 要素 | 能否移出注解 | 判断 |
| --- | --- | --- |
| 工具名 | ✅ 能省 | 缺省取方法名（已实现） |
| **用途说明** | ❌ **不能省** | 模型靠它决定"何时调用 / 何时不调用"。没有它，工具等于不存在 —— 这是**唯一真正必填**的 |
| 参数说明 | ⚠️ 能省，但代价明确 | 省掉后模型只能猜 `orderNo`、`amount`、`bizDate` 的含义与格式，复杂工具准确率明显下降 |
| 域 | ✅ 能移到 yml | `stringer.tools.domains: [customer-service]` + 按类覆盖；与"域是授权边界、由配置治理"的定位一致 |
| 副作用 / 审批 | ✅ 能移到平台配置 | 让工具代码回归纯业务；代价是"改策略要进管控台"，好处是不用发版 |

**所以"一个注解"的准确表述是**：

```java
// 最小写法：真的只有一个注解，参数说明不写也能跑
@Tool(desc = "按订单号查询订单状态。用户追问发货/物流时调用")
public OrderVO queryOrder(String orderNo, Boolean detail) { ... }
```

控制台会提示"该工具的参数缺少说明，可能影响调用准确率"，但**不阻断**。

需要质量时再加（可选，不是必需）：

```java
public OrderVO queryOrder(@ToolParam("订单号，如 FR2024001") String orderNo,
                          @ToolParam("是否返回明细") Boolean detail) { ... }
```

**明确不建议的做法**：把参数说明也塞进同一个注解、按位置对齐 ——

```java
@Tool(desc = "...", params = {"订单号", "是否明细"})   // 不要这么设计
```

参数增删或调序就错位，而且编译器不会提醒。宁可多一个**可选**注解，也不要一个会静默出错的注解。

### 8.3 对话侧：一个门面就够

```java
StringerAgent agent = stringer.forDomain("customer-service");   // 绑定一次，可复用
String answer        = agent.ask(sessionId, question);          // 常见场景
Flux<String> tokens  = agent.stream(sessionId, question);       // 要逐字
Flux<AgentEvent> ev  = agent.events(sessionId, question);       // 高级：工具调用与审批细节
```

同时**删掉**这三个名字：`@DomainBinding`（与 `forDomain` 语义重复）、`DomainAgentFactory`（并进门面）、`AgentRequest.builder()`（降为内部实现，只有需要 attributes 时才露出来）。

### 8.4 依赖坐标要不要拆成两个

**不建议拆**。理由是"两边都做"的人会从引一个变成引两个，比现在更麻烦。现状其实已经对了：

- 引 starter ⇒ 具备"调 AI"能力（默认开）；
- 工具注册默认关闭，`stringer.tools.enabled=true` 才启用。

要改的是**组织方式**：文档、示例、包结构按两条路径写（`client/` 与 `tool/`），而不是按平台内部模块写。

### 8.5 这个方案的代价（必须知道）

| 代价 | 缓解 |
| --- | --- |
| 参数说明变可选 → 工具描述质量下降 | 启动期与控制台**提示**（不阻断）；文档示例一律带参数说明 |
| 治理属性移到平台 → 可能"忘记配审批就上线" | 工具清单里标记"未声明副作用/审批"；写操作类策略可一键套用 |
| 域声明从代码移到 yml → 跨团队协作不如注解直观 | 支持按类覆盖；yml 里按包前缀分组 |
| 与既有决定「工具必须注明在什么域使用」需要重新对齐 | 二选一：① 注解保留**可选** `domains`（少写归 default）② 全部移到 yml。**建议 ①**：两个入口都支持，不逼使用者选边 |

### 8.6 修订后的推荐方案（取代 §3.2 与 §3.4）

| 侧 | 最终形态 |
| --- | --- |
| **工具侧** | `@Tool(desc = …)` 唯一必填；可选 `@ToolParam("说明")`、可选 `domains` / `effect` / `approval`；域也可改由 yml 统一声明 |
| **对话侧** | `StringerAgent` 门面：`forDomain` → `ask` / `stream` / `events` / `stop` / `resume`；`@DomainBinding` 与 `DomainAgentFactory` 移除 |
| **治理** | 代码声明优先，平台可覆盖（域级覆盖待 S5） |
| **文档** | 按「调 AI」「给工具」各写一篇，每篇只需介绍 1 个注记或 1 个门面 |

### 8.7 新增待拍板

| # | 问题 | 选项 | 建议 |
| --- | --- | --- | --- |
| 7 | 域声明放哪 | 注解可选保留 / 全部移到 yml | **注解可选 + yml 全局默认**（两个入口，最灵活） |
| 8 | 副作用与审批放哪 | 留注解 / 移到平台配置 | **留注解（可选）+ 平台可覆盖**；等平台侧配置入口做好再谈"去代码化" |
| 9 | 参数说明 | 强制 / 可选 + 提示 | **可选 + 启动期与控制台提示** |
| 10 | 是否拆依赖坐标 | 拆 / 不拆 | **不拆**，只按两条路径组织文档与包结构 |

---

## 9 参数说明的载体与 DTO 展开（可行性分析）

> 问题：参数说明能不能标在**参数 Bean 的字段**上（而不只是形参前）？以及"提供一个方法转成 JSON 传给我们"这件事要不要做。

### 9.1 结论

**可以做，而且框架里已经做了一半** —— 但只做在**工具实例那一侧**。服务端本地 Bean 路径没同步，于是同一个工具方法"搬到服务端进程"后，参数 schema 会变。

### 9.2 现状核实：两个扫描器能力不一致

| 能力 | 服务端本地 Bean（`stringer-runtime`） | 工具实例（`stringer-tool-instance`） |
| --- | --- | --- |
| 形参上的 `@ToolParam` | ✅ 读 | ✅ 读 |
| **DTO 字段上的 `@ToolParam`** | ❌ **从不读取** | ✅ `field.getAnnotation(ToolParam.class)` |
| **DTO 递归展开为嵌套 schema** | ❌ 退化成 `type: string` | ✅ `objectOf(rawClass, depth)`，带 depth 防护 |
| 特殊类型（`UUID` / `Temporal` / `Date`） | ❌ | ✅ 归为 `string` |

证据：runtime 的 `jsonType()` 里写着一行注释 —— **「复杂对象（DTO / record）退化为 string 描述」**；而 tool-instance 有完整的 `objectOf` / `isSimple` / `jsonTypeOf`。

⚠️ 这与 README 的承诺冲突：「同一段工具代码在服务端进程与业务进程之间搬迁，不用改一个字」—— **搬迁后参数 schema 会不一样**。

### 9.3 所以 DTO 参数在服务端是"坏的"

```java
public OrderVO query(OrderQuery args)   // 服务端本地 Bean 路径
```
会生成 `{"args": {"type": "string"}}` —— 模型看到的是一个**字符串**参数，于是它产出的是字符串而不是对象，反序列化到 `OrderQuery` 失败或为 null，**工具永远拿不到参数**。

这不是"写法风格问题"，是**功能缺口**。

### 9.4 两个载体不是替代关系

| 场景 | 载体 | 理由 |
| --- | --- | --- |
| 1~2 个简单参数 | 形参前：`@ToolParam("订单号，如 FR2024001") String orderNo` | 就近，一眼对应 |
| 3+ 参数 / 被多个工具复用 / 有嵌套 | DTO 的字段上：`record OrderQuery(@ToolParam("订单号") String orderNo, …)` | **只写一次**、签名干净、与 JSON Schema 的 object 结构天然对齐 |

**优先级：形参注解 > 字段注解**（就近覆盖）。两者都没有 → 描述缺失，控制台提示（不阻断启动，与 §8 的策略一致）。

### 9.5 「提供一个方法转成 JSON」要分两种含义

| 含义 | 现状 | 结论 |
| --- | --- | --- |
| 调用时把参数对象转成 JSON 传给服务端 | **框架已经在做**：模型产出 JSON → 各自反序列化为方法参数（两端都有） | 使用者**不需要**做任何序列化，也不该在工具方法里做 |
| 想看/预览参数长什么样（调试、写文档、给模型 few-shot 示例） | 缺 | **可选加**：`ToolSchema.preview(Class)` 或管控台展示"参数示例 JSON" |

### 9.6 建议的实现顺序

1. **先统一两端**：把「方法签名 + 注解 → 参数结构」抽成**一份共用实现**，服务端与工具实例都调它 —— 顺手补上服务端缺的能力，也消除两端漂移。
2. **递归展开规则**：record / 普通类 → `object`；`List<T>` / 数组 → `array` + `items`；`Optional<T>` → 非必填；枚举 → `string` + `enum`；`Map` / 泛型擦除无法展开 → `object` 并 WARN。
3. **防护**：嵌套深度上限（建议 5）+ 循环引用检测（`A → B → A`）；超限时报错或降级 + 启动期 WARN。
4. **契约兼容**：`ToolDescriptor.Param` 需要能表达子字段 → **加 `properties`**（为空＝简单类型，与现有报文兼容；工具实例上报的 `parameters` 本就是自由 JSON，天然兼容）。
5. **字段名来源**：record 的组件名可靠；普通类依赖 `-parameters` 或 `@JsonProperty`，缺名时 WARN 并回落到字段名 —— 因此**优先只支持 record**。
6. 可选（后续）：DTO 字段上的校验注解（`@NotBlank` 等）在工具入口统一执行。

### 9.7 三个坑

1. **循环引用 / 深嵌套** —— 必须限制，否则扫描器会在启动期爆栈。
2. **泛型擦除** —— `List<T>` 的 `T` 能通过 `getGenericType` 拿到，`Map<String, Object>` 拿不到，只能退化。
3. **两端必须共用同一份生成逻辑** —— 否则"搬迁不改代码"的承诺会第二次被打破（第一次已经因为这次不一致被打破了）。

### 9.8 关于"共用实现放哪"

`stringer-api` **不放** langchain4j（否则 starter 也被污染）。所以共用层只产出**自己的中间结构**（`ToolDescriptor.Param` + `properties`），两端各自把它转成 langchain4j / 上报 JSON —— 这正好是第 4 步"加 `properties`"的意义闭环。

### 9.9 待拍板

| # | 问题 | 选项 | 建议 |
| --- | --- | --- | --- |
| 11 | 是否本轮补服务端 DTO 支持 | 补 / 不补 | **补**：这是 DTO 参数在服务端可用的前提，也是消除两端不一致的前提 |
| 12 | 参数说明两个载体的优先级 | 形参优先 / 字段优先 | **形参优先**（就近覆盖） |
| 13 | 嵌套深度上限 | 3 / 5 / 不限 | **5** |
| 14 | 普通类（`FIELD`）要不要支持 | 只 record / 兼容普通类 | **record 优先，普通类兼容**（字段名不可靠时告警） |
| 15 | 是否提供参数示例预览 | 提供 / 不做 | **提供**（调试与文档价值高，成本低） |
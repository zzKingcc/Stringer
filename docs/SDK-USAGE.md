# Stringer SDK 使用手册（示例）

> 面向接入方（业务应用）与工具提供方。
> 契约定义见 [`SDK-CONTRACT.md`](SDK-CONTRACT.md)，HTTP 端点与 SSE 事件见 [`API.md`](API.md)，工具实例接入实操见 [`INSTANCE.md`](INSTANCE.md)。
>
> 本文所有示例基于 `v1.0-beta.1` 已实现的接口。注解以收敛后的 `@Tool` 全家桶为准（详见 §1.6 旧注解迁移）。

---

## 0 前置条件

- **依赖**：`stringer-agent-client`（消费侧唯一坐标）。
- **配置**：`stringer.server.*`（服务端地址与账号，对话 SDK 与工具实例 SDK 共用一份）。
- **对话主入口**：`StringerAgentFactory`（`forDomain(...)` → `StringerAgent`：`ask` / `stream` / `events` / `resume` / `stop`），由 starter 自动装配，直接注入即可。
- **工具注解**：`com.zzkingcc.stringer.api.annotation` 包下的 `@Tool` / `@ToolParam` / `@ToolDomains` / `@ToolAdvanced`。

```yaml
stringer:
  server:
    host: localhost
    port: 9527
    username: stringer
    password: stringer
```

---

## 1 注解使用方式（把方法变成 Agent 工具）

设计取向：**只有一个必填字段** —— `@Tool` 的 `desc()`。其余按需写，默认值够用的不出现。

### 1.1 最小可运行工具

```java
@Service
public class OrderTools {

    // 唯一必填：desc。写清"何时调用 / 何时不要调用"
    @Tool(desc = "按订单号查询订单状态。用户追问发货、物流时调用")
    public OrderVO queryOrder(@ToolParam("订单号，如 FR2024001") String orderNo) {
        return orderRepository.findByNo(orderNo);
    }
}
```

- 工具名留空 = 取方法名（`queryOrder`）。重名注册直接失败。
- 参数 schema 由方法签名推导；`@ToolParam` 补语义（强烈建议写，复杂参数不写模型只能猜）。

### 1.2 参数说明：`@ToolParam`（形参前 vs DTO 字段）

两种载体，**形参注解优先**（就近覆盖）。1~2 个简单参数写在形参前；3+ 参数、被多个工具复用、或有嵌套结构 → 用 `record` DTO 的字段注解（只写一次）。

```java
// 写法 A：标在形参前（推荐用于少量简单参数）
@Tool(desc = "查询用户可领优惠券")
public List<Coupon> listCoupons(@ToolParam("用户ID") String userId,
                                @ToolParam("是否只返回未过期的") boolean activeOnly) { ... }

// 写法 B：标在 record 字段（推荐用于复杂 / 复用参数）
public record OrderQuery(
        @ToolParam("订单号，如 FR2024001") String orderNo,
        @ToolParam("是否返回明细行") Boolean detail) { }

@Tool(desc = "按条件查询订单")
public OrderVO query(OrderQuery q) { ... }
```

> `Optional<T>` 参数自动视为非必填；`required` 仅在需要显式覆盖时写。

### 1.3 类级默认域：`@ToolDomains`

一个类里的工具通常同属一个域，逐个在 `@Tool(domains=...)` 上重写是重复。方法级 `domains` 优先于类级。

```java
@Service
@ToolDomains("admin")                 // 本类所有 @Tool 默认属于 admin 域
public class OrderAdminTools {

    @Tool(desc = "关闭订单。用户明确要求取消时调用", effect = Tool.Effect.WRITE)
    public String closeOrder(String orderNo) { ... }   // 自动属于 admin 域

    // 覆盖：只在 finance 域可用
    @Tool(desc = "导出对账单", domains = {"finance"})
    public String exportStatement(String month) { ... }
}
```

**域的三种写法**：
- 显式域名（如 `{"admin"}`）：只在这些域可用。
- 留空：只属于兜底域 `default`。
- 通配 `{"*"}`：任何域可用（必须显式写出，让"全域"是一个决定而非漏写）。

### 1.4 副作用与人工审批：`effect` / `approval` / `approvalReason`

写操作、破坏性操作建议配审批。`approval` 当前生效值只有 `NONE` 与 `ALWAYS`。

```java
@Service
@ToolDomains("admin")
public class RefundTools {

    // 写操作 + 每次调用前中断等人工确认
    @Tool(desc = "按订单号退款。仅在用户明确要求退款时调用",
          value = "refundOrder",
          effect = Tool.Effect.WRITE,
          approval = Tool.Approval.ALWAYS,
          approvalReason = "退款会真实出金，需人工确认")
    public String refundOrder(@ToolParam("订单号") String orderNo,
                              @ToolParam("退款金额，单位：元，须 ≤ 订单实付") BigDecimal amount) {
        return refundService.refund(orderNo, amount);
    }

    // 破坏性操作：强烈建议配审批
    @Tool(desc = "删除知识库文档",
          effect = Tool.Effect.DESTRUCTIVE,
          approval = Tool.Approval.ALWAYS,
          approvalReason = "删除不可恢复")
    public String deleteDoc(@ToolParam("文档ID") String docId) { ... }
}
```

审批命中后，`chat` 事件流会吐出 `INTERRUPT` 事件并挂起本轮；调用方处理后用 `resume` 恢复（见 §2.3）。

### 1.5 高级可选：`@ToolAdvanced`（多数工具永不需要）

承接"偶尔要写、但不该默认出现在每个工具上"的字段。三个字段一律 `参数名=值`，**不做位置对齐**（位置对齐在参数增删或调序时会静默错位，编译器不会提醒）。

名字既可以是方法形参名，也可以是参数 DTO 展开后的**字段名**。

```java
@Tool(desc = "查询订单")
@ToolAdvanced(example = {"orderNo=FR2024001"},        // 参数示例
              allowValues = {"status=PAID|REFUNDED"},  // 枚举白名单
              sensitive = {"idCard"})                  // 该参数的值在事件/审批 payload 里显示为 ***
public OrderVO query(String orderNo, String status, String idCard) { ... }
```

| 字段 | 落点（也就是"写了会不会真生效"） |
| --- | --- |
| `example` | 进 `Param.example`，并以「（示例：xxx）」追加到**参数说明**末尾 —— 底层模型 schema 只有 `description` 一个自由文本位，没有独立的 example 槽，不并进说明就到不了模型眼前。说明里已含同一示例时不重复追加。 |
| `allowValues` | 成为模型可见 schema 的 `enum` 白名单，比用自然语言描述取值可靠得多。 |
| `sensitive` | 该参数的**值**在工具调用事件与审批 payload 里显示为 `***`。掩码的是值不是参数名；执行时用的仍是原值。 |

> 名字写错**不报错、只静默不生效**；只有"不是 `参数名=值` 形式"的条目会在启动期打 WARN。改参数名时记得同步改这里。
> 同名参数（例如两个 DTO 都有 `status`）会一起命中，参数名保持唯一最稳妥。

### 1.6 旧注解已删除（`@StringerTool` / `@ToolPolicy` / `@ToolParam` 的废弃字段）

`@StringerTool` 与 `@ToolPolicy` **已从 SDK 移除，不再被扫描器识别**——写了不会注册，也不会报错。迁移到 `@Tool` 的对照：

| 旧写法 | 新写法 |
| --- | --- |
| `@StringerTool(name="x", description="…", domains={"c"})` | `@Tool(value="x", desc="…", domains={"c"})` |
| `@StringerTool(sideEffect = StringerTool.SideEffect.WRITE)` | `@Tool(effect = Tool.Effect.WRITE)` |
| `@ToolPolicy(approval = @ToolPolicy.Approval(mode = Mode.ALWAYS, reason = "…"))` | `@Tool(approval = Tool.Approval.ALWAYS, approvalReason = "…")` |
| `@ToolParam(description = "…")` | `@ToolParam(value = "…")` 或直接 `@ToolParam("…")` |
| `@ToolParam(example = …)` / `allowValues = …` / `sensitive = …` | 移到方法级 `@ToolAdvanced`（写法变成 `参数名=值`） |
| `profiles = {"c"}`（旧字段） | `domains = {"c"}` |
| `version` / `idempotent` / `toModel` / `category` | 不再需要（默认即可） |

> `@ToolParam` 现在**只剩 `value` / `name` / `required` 三个字段**，那四个废弃别名已彻底删除（写了编译不过）。
> `@Tool` 的 `approval` 只有 `NONE` / `ALWAYS`——条件式审批（`CONDITIONAL`）与会话内免确认（`ONCE_PER_SESSION`）都未落地，不再暴露。
> 编程式注册侧同一批改名的还有 `ToolSpec.withProfiles(..)` → `withDomains(..)`（旧名保留为 `@Deprecated` 别名转调）。

---

## 2 对话 SDK 使用方式（`StringerAgent`）

SDK 只提供**一个入口**：`StringerAgentFactory.forDomain(...)` 拿到已绑定域的 `StringerAgent`。
方法按使用频度分三层 —— `ask`（只要答案）／`stream`（逐字）／`events`（完整事件），再加 `resume` 与 `stop`。
**域在取门面时就已确定**，所以下面所有方法签名里都没有域参数。

### 2.1 注入与三种调用方式

```java
@Service
public class MyService {
    private final StringerAgent agent;                // 已绑定 customer 域，可缓存复用（线程安全）

    public MyService(StringerAgentFactory factory) {   // starter 自动装配，无需任何注解
        this.agent = factory.forDomain("customer");    // null / 空白 → 兜底域 default
    }

    /** 只要最终答案（约七成场景） */
    public String ask(String sessionId, String question) {
        return agent.ask(sessionId, question);
    }

    /** 逐字输出 */
    public Flux<String> stream(String sessionId, String question) {
        return agent.stream(sessionId, question);
    }

    /** 完整事件流：工具调用 / 审批中断 / 错误码 */
    public Flux<AgentEvent> events(String sessionId, String question) {
        return agent.events(sessionId, question);
    }
}
```

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `ask(sessionId, question)` | `String` | 内部消费完整条事件流并把 `TOKEN` 拼成整段文本；**阻塞**（受 `stringer.client.read-timeout` 约束） |
| `stream(sessionId, question)` | `Flux<String>` | 只出模型文本增量 |
| `events(sessionId, question)` | `Flux<AgentEvent>` | 原样事件流，冷流（订阅后执行），自行控制超时与背压 |
| `resume(sessionId, approved)` | `Flux<AgentEvent>` | 审批恢复；域由门面自动带上 |
| `stop(sessionId)` | `boolean` | 幂等停止 |
| `domainId()` | `String` | 本实例绑定的域，永不为空 |

- 三种方式都有带归属的重载：`(sessionId, question, tenantId, userId)`。
- `forDomain(null)` / `forDomain("  ")` → 兜底域 `default`；**同一域永远拿到同一个门面**（归一化后按域缓存，首尾空白不会造出第二个）。
- `sessionId` 由调用方生成并保持稳定 —— 同一会话复用同一个，它是记忆与检查点的唯一键。
- 域在服务端不存在 → `StringerException`（`10004`）。**不需要**在启动期预先校验域：写错了第一次调用就会带着明确的码报出来。

### 2.2 只要答案：`ask`

```java
String answer = agent.ask("s-001", "我的订单 FR2024001 到哪了");
```

两种"非正常结束"由异常表达，而不是让你在文本里猜：

| 情况 | 行为 |
| --- | --- |
| 命中人工审批（`INTERRUPT`） | 抛 `ApprovalRequiredException`（见 §2.5） |
| 服务端返回 `ERROR` 事件 | 抛 `StringerException`，携带 `ErrorCode` 与 `traceId` |
| 被 `stop` 主动停止 | **返回已产出的部分文本**（不是错误） |

### 2.3 逐字输出：`stream`

```java
agent.stream("s-001", "讲讲退款政策")
     .doOnNext(chunk -> out.print(chunk))
     .blockLast();
```

命中审批或错误时，流以对应的异常终止（`ApprovalRequiredException` / `StringerException`）。

### 2.4 完整事件：`events`

事件类型：`TOKEN`（增量文本）/ `TOOL_CALL`（即将调用某工具）/ `TOOL_RESULT`（工具结果）/ `INTERRUPT`（待审批，挂起）/ `STOPPED`（被停止）/ `ERROR`（异常）/ `DONE`（结束）。

```java
agent.events("s-001", "帮我查订单")          // 需要归属时用 events(sessionId, question, tenantId, userId)
    .doOnNext(event -> {
        switch (event.getType()) {
            case TOKEN       -> out.print(event.getContent());          // 流式拼字
            case TOOL_CALL   -> log.info("调用工具 {} 参数 {}",
                                         event.getContent(), event.getPayload());
            case TOOL_RESULT -> log.info("工具 {} 返回", event.getContent());
            case INTERRUPT   -> handleInterrupt(event);                  // 见 §2.5
            case STOPPED     -> log.info("会话 {} 已停止", event.getSessionId());
            case ERROR       -> log.error("[{}] {} traceId={}",
                                         event.getCodeName(), event.getContent(), event.getTraceId());
            case DONE        -> log.info("会话 {} 结束", event.getSessionId());
        }
    })
    .blockLast();   // 阻塞到流结束；WebFlux 环境用 subscribe 而非 block
```

> 走 `events` 时错误通过**事件**表达，不会抛异常，HTTP 状态仍是 200 —— 调用方必须显式处理 `ERROR` 事件；`ask` / `stream` 已经替你把它翻成了异常。

### 2.5 人工审批中断与恢复（HITL）

工具声明了 `approval = ALWAYS` 时，会在真正执行前挂起。用 `ask` / `stream` 时表现为 `ApprovalRequiredException`，里面就是待审批清单：

```java
try {
    return agent.ask(sessionId, question);
} catch (ApprovalRequiredException e) {
    // 直接渲染确认框：e.getTools() 是 List<ToolCall>（name / arguments / requireApproval）
    for (ToolCall tool : e.getTools()) {
        log.info("待审批: {} 参数 {}", tool.getName(), tool.getArguments());
    }
    // 用户点完 → 批准/拒绝继续本轮
    return waitForApproval(e.getSessionId())            // 业务自己的确认流程
            ? agent.resume(e.getSessionId(), true)
            : agent.resume(e.getSessionId(), false);
}
```

- `ApprovalRequiredException` 带 `sessionId` / `domainId` / `tools` / `traceId`；**域由门面绑定**，`resume` 时自动带上，不会出现"换域恢复"（换域会被服务端以 `30002` 拒绝）。
- 批准 → 内核注入确认继续；拒绝 → 注入拒绝反馈让模型重新决策。
- 想自己处理中断事件（而不是挨异常）就用 `events` 看 `INTERRUPT`，其 `payload` 是 `ToolCallPayload` JSON。

### 2.6 停止任务

```java
// 请求停止，幂等。true=本次设置成功；false=已处于停止状态或被拒
boolean triggered = agent.stop(sessionId);
```

语义：仅置取消标志，编排层在下一个检查点结束本轮，非抢占式。

### 2.7 多租户与归属（tenantId / userId）

```java
// 域从当前登录角色推导，不要信任前端字符串
StringerAgent agent = factory.forDomain(deriveProfileFromRole(user));

agent.ask(sessionId, question, tenantId, user.getId());
agent.events(sessionId, question, tenantId, user.getId());
```

> 域是**调用方自行声明、平台信任**的治理机制（防止模型误用工具、防止提示词与工具集错位），**不是安全边界**。终端用户身份与授权属于宿主自己的 IAM。
> `attributes` 扩展属性目前只走裸 HTTP（§2.8）—— SDK 侧没有它的入口。

### 2.8 裸 HTTP / SSE 调用（不走 SDK）

`chat` / `resume` / `stop` 都是标准 HTTP，事件以 `text/event-stream` 下发。无 Java SDK 的客户端（前端、其他语言）直接调。

**发起对话（curl）**

```bash
curl -N -X POST http://localhost:9527/api/agent/chat \
  -H "Content-Type: application/json" \
  -H "X-Stringer-Credential: <凭证>" \
  -d '{"sessionId":"s-001","message":"我的订单 FR2024001 到哪了","profile":"customer"}'
```

**前端消费 SSE（fetch + ReadableStream）**

```javascript
const resp = await fetch('/api/agent/chat', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json',
             'X-Stringer-Credential': credential },
  body: JSON.stringify({ sessionId, message, profile })
});
const reader = resp.body.getReader();
const decoder = new TextDecoder();
let buf = '';
while (true) {
  const { value, done } = await reader.read();
  if (done) break;
  buf += decoder.decode(value, { stream: true });
  // SSE 以空行分隔事件；data: 后为 AgentEvent 的 JSON
  const frames = buf.split('\n\n');
  buf = frames.pop();
  for (const f of frames) {
    const line = f.split('\n').find(l => l.startsWith('data:'));
    if (!line) continue;
    const ev = JSON.parse(line.slice(5).trim());
    if (ev.type === 'TOKEN') process.stdout.write(ev.content);
    if (ev.type === 'INTERRUPT') showApprovalDialog(ev);   // 调 /api/agent/resume
    if (ev.type === 'ERROR') alert(`[${ev.codeName}] ${ev.content}`);
  }
}
```

**审批恢复（resume）**

```bash
curl -N -X POST "http://localhost:9527/api/agent/resume?sessionId=s-001&approved=true" \
  -H "Content-Type: application/json" \
  -H "X-Stringer-Credential: <凭证>" \
  -d '{"profile":"customer"}'
```

完整端点、鉴权与错误码见 [`API.md`](API.md) §2 / §5。

---

## 3 常见坑

- **域为空**：SDK 侧用 `forDomain(...)` 在绑定时就定好域，`ask` / `stream` / `events` 的签名里没有域参数，传不出空值；只有裸 HTTP（§2.8）才会把空域归一到 `default`。域不存在被拒（`10004`）—— 不需要启动期预先校验域，第一次调用就会报清楚。
- **`sessionId` 不稳定**：同一会话必须复用同一个 `sessionId`，否则记忆与检查点断裂、看起来"失忆"。
- **只处理 `TOKEN`**：忽略 `TOOL_CALL` / `TOOL_RESULT` 会看不到工具行为；走 `events` 时忽略 `ERROR` 事件则故障被静默吞掉（HTTP 200）。用 `ask` / `stream` 则错误会以异常形式抛出。
- **`resume` 域不一致**：必须与中断时相同，否则 `30002`。门面已绑定域，正常路径下不会发生。
- **`ask` 是阻塞的**：它内部会消费完整条流，受 `stringer.client.read-timeout` 约束；长任务请用 `events`（冷流，可自行超时/背压）。
- **`blockLast()` vs `subscribe()`**：阻塞式入口可用 `blockLast()`；WebFlux / 异步入口用 `subscribe()`，不要混用。
- **工具重名**：同名工具全局只能有一个；多副本请走工具实例注册（同名多实例），见 [`INSTANCE.md`](INSTANCE.md)。
- **知识库检索不到**：检索工具没声明到该域 → 模型根本不会调用它；文档没标域 → 只属 `default`，其他域查不到。这是两层约束，排查时都要看（见 `INSTANCE.md` §2.6）。

# Stringer SDK 使用手册（示例）

> 面向接入方（业务应用）与工具提供方。
> 契约定义见 [`SDK-CONTRACT.md`](SDK-CONTRACT.md)，HTTP 端点与 SSE 事件见 [`API.md`](API.md)，工具实例接入实操见 [`INSTANCE.md`](INSTANCE.md)。
>
> 本文所有示例基于 `v1.0-beta.1` 已实现的接口。注解以收敛后的 `@Tool` 全家桶为准（详见 §1.6 旧注解迁移）。

---

## 0 前置条件

- **依赖**：`stringer-agent-client`（消费侧唯一坐标）。
- **配置**：`stringer.server.*`（服务端地址与账号，对话 SDK 与工具实例 SDK 共用一份）。
- **对话主入口**：`AgentService`（`chat` / `resume` / `stop`），由 starter 自动装配，直接注入即可。
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

承接"偶尔要写、但不该默认出现在每个工具上"的字段。**按参数名对应，不做位置对齐**。

```java
@Tool(desc = "查询订单")
@ToolAdvanced(example = {"orderNo=FR2024001"},        // 参数示例
              allowValues = {"status=PAID|REFUNDED"},  // 枚举白名单
              sensitive = {"idCard"})                  // 脱敏参数名（日志/事件/审批payload 只显掩码）
public OrderVO query(String orderNo, String status, String idCard) { ... }
```

- `example`：`参数名=示例值`。
- `allowValues`：`参数名=值1|值2`。
- `sensitive`：需要脱敏的参数名清单。

### 1.6 旧注解迁移（`@StringerTool` / `@ToolPolicy` → `@Tool`）

`@StringerTool` 与 `@ToolPolicy` **仍被扫描器识别（向后兼容）**，但新代码请改用 `@Tool`。对照：

| 旧写法 | 新写法 |
| --- | --- |
| `@StringerTool(name="x", description="…", domains={"c"})` | `@Tool(value="x", desc="…", domains={"c"})` |
| `@StringerTool(sideEffect = StringerTool.SideEffect.WRITE)` | `@Tool(effect = Tool.Effect.WRITE)` |
| `@ToolPolicy(approval = @ToolPolicy.Approval(mode = Mode.ALWAYS, reason = "…"))` | `@Tool(approval = Tool.Approval.ALWAYS, approvalReason = "…")` |
| `@ToolParam(description = "…")` | `@ToolParam(value = "…")` 或直接 `@ToolParam("…")` |
| `profiles = {"c"}`（旧字段） | `domains = {"c"}` |
| `version` / `idempotent` / `toModel` / `category` | 不再需要（默认即可） |

> 注意：`@Tool` 的 `approval` 只有 `NONE` / `ALWAYS`。旧 `@ToolPolicy.Approval` 的 `CONDITIONAL` / `ONCE_PER_SESSION` 在当前实现中等价于 `ALWAYS`，收敛后不再暴露。

---

## 2 对话 SDK 使用方式（`AgentService`）

`AgentService` 是对话契约的全部入口。一轮对话发起 `chat`，审批挂起后 `resume`，运行中 `stop`。返回都是 Reactor 的 `Flux<AgentEvent>`（事件流）。

### 2.1 注入与发起一轮对话

```java
@Service
public class MyService {
    private final AgentService agentService;

    public MyService(AgentService agentService) {   // starter 自动装配，无需任何注解
        this.agentService = agentService;
    }

    /** 发起一轮对话，返回事件流 */
    public Flux<AgentEvent> ask(String sessionId, String question) {
        AgentRequest request = AgentRequest.of(sessionId, question, "customer");
        return agentService.chat(request);
    }

    /** 带归属信息的完整构造 */
    public Flux<AgentEvent> askWithContext(String sessionId, String question,
                                           String tenantId, String userId) {
        AgentRequest request = AgentRequest.builder()
                .sessionId(sessionId)
                .message(question)
                .profile("customer")          // 域：必填，决定模型可见的工具与提示词
                .tenantId(tenantId)            // 审计字段
                .userId(userId)
                .attribute("source", "app")    // 附加属性
                .build();
        return agentService.chat(request);
    }
}
```

- `sessionId` 由调用方生成并保持稳定 —— 同一会话复用同一个，它是记忆与检查点的唯一键。
- `profile`（域）为空会回落兜底域 `default`；该域不存在被拒（错误码 `10004`）。

### 2.2 消费事件流

事件类型：`TOKEN`（增量文本）/ `TOOL_CALL`（即将调用某工具）/ `TOOL_RESULT`（工具结果）/ `INTERRUPT`（待审批，挂起）/ `STOPPED`（被停止）/ `ERROR`（异常）/ `DONE`（结束）。

```java
agentService.chat(request)
    .doOnNext(event -> {
        switch (event.getType()) {
            case TOKEN       -> out.print(event.getContent());          // 流式拼字
            case TOOL_CALL   -> log.info("调用工具 {} 参数 {}",
                                         event.getContent(), event.getPayload());
            case TOOL_RESULT -> log.info("工具 {} 返回", event.getContent());
            case INTERRUPT   -> handleInterrupt(event);                  // 见 §2.3
            case STOPPED     -> log.info("会话 {} 已停止", event.getSessionId());
            case ERROR       -> log.error("[{}] {} traceId={}",
                                         event.getCodeName(), event.getContent(), event.getTraceId());
            case DONE        -> log.info("会话 {} 结束", event.getSessionId());
        }
    })
    .blockLast();   // 阻塞到流结束；WebFlux 环境用 subscribe 而非 block
```

> 错误通过**事件**表达，不会抛异常，HTTP 状态仍是 200。调用方必须显式处理 `ERROR` 事件。

### 2.3 人工审批中断与恢复（HITL）

工具声明了 `approval = ALWAYS` 时，`chat` 会在真正执行前吐出 `INTERRUPT` 事件，本轮挂起。`payload` 是待审批工具清单的 JSON（`ToolCallPayload`）。处理完（批准/拒绝）后调用 `resume`，**域须与中断时一致**。

```java
ObjectMapper mapper = new ObjectMapper();

agentService.chat(request)
    .flatMapMany(event -> {
        if (event.getType() != AgentEventType.INTERRUPT) {
            return Flux.just(event);
        }
        // 解析待审批工具清单（可选：展示给审批人）
        try {
            ToolCallPayload pending = mapper.readValue(
                    event.getPayload(), ToolCallPayload.class);
            for (ToolCall t : pending.getTools()) {
                log.info("待审批: {} 参数 {}", t.getName(), t.getArguments());
            }
        } catch (Exception e) {
            log.warn("解析中断 payload 失败", e);
        }
        // 真实场景：弹确认框让用户决定 approved 取 true / false
        // 这里示例直接批准；caller 的域必须与 chat 时一致
        CallerContext caller = CallerContext.from(request);
        return agentService.resume(request.getSessionId(), true, caller);
    })
    .subscribe();
```

- `resume(sessionId, approved, caller)`：批准 → 内核注入确认继续；拒绝 → 注入拒绝反馈让模型重新决策。
- 域不一致会被拒（错误码 `30002`）。`CallerContext.from(request)` 可安全复用 chat 时的身份。
- `ToolCall` 字段：`name` / `arguments` / `requireApproval`（getter 为 `isRequireApproval()`）。

### 2.4 停止任务

```java
// 请求停止，幂等。true=本次设置成功；false=该会话已处于停止状态
boolean triggered = agentService.stop(sessionId);
```

语义：仅置取消标志，编排层在下一个检查点结束本轮，非抢占式。

### 2.5 多租户与归属（tenantId / userId / attributes）

```java
AgentRequest request = AgentRequest.builder()
        .sessionId(sessionId)
        .message(question)
        .profile(deriveProfileFromRole(user))   // 从当前登录角色推导域，不要信任前端字符串
        .tenantId(tenantId)                       // 多租户计量 / 审计
        .userId(user.getId())
        .attribute("channel", "web")
        .build();
```

> 域是**调用方自行声明、平台信任**的治理机制（防止模型误用工具、防止提示词与工具集错位），**不是安全边界**。终端用户身份与授权属于宿主自己的 IAM。

### 2.6 裸 HTTP / SSE 调用（不走 SDK）

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

### 2.7 规划中的门面 `StringerAgent`（目标形态，尚未实现）

> 当前对话 SDK = `AgentService` + `AgentRequest`（§2.1~§2.6，已实现可用）。
> 下方是 [`SDK-CONTRACT.md`](SDK-CONTRACT.md) 规划的统一门面，**门面层尚未实现**，仅作目标形态参考，请勿当运行代码使用。

```java
// 目标形态：域绑定入口，返回可缓存复用的线程安全实例
StringerAgent agent = StringerAgentFactory.forDomain("customer");

agent.ask(sessionId, "我的订单到哪了");        // 同步取最终答案（~70% 场景）
agent.stream(sessionId, "讲讲退款政策");         // Flux<String> 逐字输出
agent.events(sessionId, "帮我退款");             // Flux<AgentEvent> 完整事件
agent.resume(sessionId, true);                  // 审批恢复
agent.stop(sessionId);                          // 停止
```

---

## 3 常见坑

- **域为空**：`chat` 的 `profile` 留空会回落 `default`；若该域被判定缺失会被拒（`10004`）。显式传域最稳。
- **`sessionId` 不稳定**：同一会话必须复用同一个 `sessionId`，否则记忆与检查点断裂、看起来"失忆"。
- **只处理 `TOKEN`**：忽略 `TOOL_CALL` / `TOOL_RESULT` 会看不到工具行为；忽略 `ERROR` 事件则故障被静默吞掉（HTTP 200）。
- **`resume` 域不一致**：必须与 `chat` 时相同，否则 `30002`。用 `CallerContext.from(request)` 复用最省心。
- **`blockLast()` vs `subscribe()`**：阻塞式入口可用 `blockLast()`；WebFlux / 异步入口用 `subscribe()`，不要混用。
- **工具重名**：同名工具全局只能有一个；多副本请走工具实例注册（同名多实例），见 [`INSTANCE.md`](INSTANCE.md)。

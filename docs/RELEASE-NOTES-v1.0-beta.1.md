# Stringer v1.0-beta.1：Java 生态的 AI Agent 运行时中间件

引一个 starter，注入 `AgentService` 就能调 AI；方法上加 `@StringerTool`，就能让 AI 调你。编排、工具治理、知识库、管控台都在服务端。

适用于**已有的 Java 应用**——单体或分布式微服务都可以。不要求为 AI 另起一套技术栈。

---

## 交付产物

| 文件 | 体积 | 说明 |
| --- | --- | --- |
| `stringer-v1.0-beta.1.jar` | 58.15 MB | **服务端**。平台无关 fat jar，Linux / Windows / macOS 直接 `java -jar`，无需重新构建 |
| `stringer-spring-boot-starter-v1.0-beta.1.jar` | 0.03 MB | **接入侧唯一坐标**，已传递契约层与工具实例 SDK |
| `stringer-tool-instance-v1.0-beta.1.jar` | 0.04 MB | 只想当工具方（工具微服务、非 Java 应用）时单独引 |

服务端 SHA256：`2909135B3131193514D65A737A98423E9A68FC820125751678122DC265A40D4A`

`stringer-api` / `common` / `domain` / `infrastructure` / `runtime` 为内部实现，不单独交付。`stringer-example`（29.61 MB）是联调 demo，不交付。

## 核心能力

| 能力 | 具体到能做什么 |
| --- | --- |
| 图编排状态机 | 每一步显式可控（`agent → 条件路由 → tools/review → agent`），状态透明、可中断、可恢复 |
| 人工审批（HITL） | 工具声明审批策略后，调用前自动中断等待确认；中断点落 Redis，**服务端重启后仍可恢复** |
| 域（profile）可见性 | 一次对话必须声明所处的域，模型只能看到该域的工具、只能拿到该域的提示词；域不存在直接报错，**绝不静默降级成全量工具** |
| 远程工具注册中心 | 工具实例周期整包上报声明，服务端按实例维护副本；实例掉线自动摘除、重连自动恢复，同名工具多实例可同时在线 |
| 实例可用性管控 | 管控台可对单个实例**静音（熔断）/ 恢复 / 强制下线**，不必去改动它所在的进程 |
| 混合检索 | 向量检索与关键词检索并行执行，归一化后加权融合；切片按中文章节边界切分并携带来源元数据 |
| 知识库上传与重建 | 管控台直接上传文档（默认 `md` / `txt`，扩展名白名单可配），可查看导入状态与整库重建索引 |
| 双约束会话记忆 | 同时约束消息条数与 Token 估算，按会话隔离存储 |
| 流式输出与中断 | 事件流逐帧下发；任务可随时停止 |
| 内置管控台 | 8 页：概览、模型设置、存储配置、域空间、在线实例、提示词设定、知识库、账号 |
| 运行指标快照 | `GET /admin/metrics` 返回注册工具数、运行中会话数、堆内存与内核指标快照，可接进现有监控采集 |
| 零配置可启动 | 未填 ES / Redis / 模型也能启动，缺配置只在**调用时**报明确错误并指向该去哪一页填 |

## 快速开始

```bash
mvn -pl stringer-server -am install
mvn -pl stringer-server spring-boot:run     # 默认端口 9527
```

**不需要预先准备配置文件。** 启动后打开管控台 `http://localhost:9527/admin.html`（默认账号 `stringer / stringer`），在「模型设置」填对话模型与向量模型、「存储配置」填 ES 与 Redis，每页都有「测试连接」可当场验证。

业务系统接入只需一个依赖，注入 `AgentService` 发起对话，再在方法上加 `@StringerTool` 把业务方法变成工具：

```xml
<dependency>
    <groupId>com.zzkingcc</groupId>
    <artifactId>stringer-spring-boot-starter</artifactId>
    <version>v1.0-beta.1</version>
</dependency>
```

```java
@StringerTool(name = "refundOrder", description = "按订单号退款。仅在用户明确要求退款时调用",
        profiles = {"admin"}, sideEffect = StringerTool.SideEffect.WRITE)
@ToolPolicy(approval = @ToolPolicy.Approval(mode = Mode.ALWAYS, reason = "退款需人工确认"))
public String refundOrder(@ToolParam(description = "订单号") String orderNo,
                          @ToolParam(description = "退款金额，单位：元") BigDecimal amount) { ... }
```

完整链路（含审批中断 → 恢复）见仓库 `stringer-example`，或 [`docs/INSTANCE.md`](INSTANCE.md)。

## 环境要求

- JDK 21+、Maven 3.8+
- Elasticsearch 9.x（已验证；8.x 可用；更低版本需自行验证）—— 需安装 IK 分词器插件
- Redis 6+
- 一个 OpenAI 兼容的模型服务（对话模型 + 向量模型）。**对话模型需支持工具调用（function calling）**

ES / Redis **可与业务系统共用同一套实例**：Redis key 统一 `stringer:` 前缀、ES 索引统一 `stringer_` 前缀，双方各自建立独立连接。

## 本版已知不覆盖

- **多实例 / 集群**：本版本为单实例，不支持多副本部署（见 [`DEPLOYMENT.md`](DEPLOYMENT.md) §6）
- **跨版本配置迁移**：升级按目标版本重新核对配置
- **入口限流与并发上限**：未实现（`20000` / `20001` / `20003` 不会发出）
- **指标无历史**：`/admin/metrics` 为进程内快照，重启归零

## 升级注意

- 依赖坐标版本号已改为 `v1.0-beta.1`，接入方 `pom.xml` 需同步。
- 未做跨版本配置迁移；模型与存储连接在管控台重新核对即可，无需改源码或镜像。
- 工具版本默认值由 `0.1.0` 调整为 `1.0.0`：该字段仅用于管理页展示，注册表按**工具名**归并（同名＝同一工具的多副本），不影响现有调用。
- 更换向量模型导致维度变化时，ES 索引维度在建索引时定死，需在「知识库」页**重建索引**。

## 文档

- [设计文档](DESIGN.md) —— 形态与模块、域与工具可见性、工具体系、存储与模型配置、并发模型、配置项总表
- [API 文档](API.md) —— 全部 HTTP 端点、SSE 事件契约、错误码总表、starter 与工具实例 SDK
- [实例文档](INSTANCE.md) —— 配置与接入实操
- [部署文档](DEPLOYMENT.md) —— 裸机 / 容器部署
- [上线检查清单](RELEASE-CHECKLIST.md) —— 发布前与部署后冒烟

## License

[Apache-2.0](../LICENSE)

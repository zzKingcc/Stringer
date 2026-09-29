# D3 配置扁平化设计（消费侧）

> **范围**：只动**消费侧**集成配置——业务应用接入 Stringer 时要写的三个前缀
> `stringer.server` / `stringer.client` / `stringer.tool-instance`。
> **不动**服务端部署配置（`stringer.ai` / `agent` / `memory` / `rag` / `elasticsearch` / `redis` 等，那是服务端运维的事）。
>
> **目标**：把"要主动写"的键从 ~14 个收敛到 **3 个常用键**，其余全部给默认值、退到"高级配置"；
> 默认值与高级键**不出现在使用者代码里**。
>
> **状态**：设计稿，待评审后落地（落地时提交 + 同步文档）。

---

## 1 现状（真实字段，已核对代码）

| 前缀 | 类（`@ConfigurationProperties`） | 字段（默认） | 谁在用 |
| --- | --- | --- | --- |
| `stringer.server` | `sdkcore.config.ServerProperties` | `host=localhost`、`port=9527`、`username=stringer`、`password=stringer`（另有派生 `getServerUrl()=http://host:port`） | `StringerAutoConfiguration`（`baseUrl`）、`ToolInstanceProperties.toConfig`（host/port/user/pwd） |
| `stringer.client` | `agentclient.properties.ClientProperties` | `healthCheckTimeout=5s`、`connectTimeout=5s`、`readTimeout=10m` | 调用层超时 |
| `stringer.tool-instance` | `toolprovider.spring.ToolInstanceProperties` | `enabled=false`、`scanAnnotated=true`、`instanceId=null`、`endpoint=null`、`heartbeatIntervalSeconds`、`maxBackoffSeconds`、`requestTimeoutMillis` | 工具实例心跳/回调/注册 |

**痛点**（与草稿一致，但数字以代码为准）：
1. `host` + `port` 两个键表达一个"地址"，https 场景还要额外解释 scheme；
2. `username`/`password` 藏在 `server` 组里、却是"接入账号"语义，和 host/port 混在一起；
3. `tool-instance.enabled` 这个"要不要当工具提供方"的开关，名字是内部术语（`tool-instance`），使用者要猜；
4. 三组的组织按"代码里是哪个模块"，不按"使用者想干什么"——想调 AI 的人看到 `tool-instance.*` 以为自己也要配；
5. 缺少"启动期校验我声明的域存在"的能力（草稿里的 `domain-check-enabled` **从未落地**）；**D3 不新增此能力**——域存在性由运行时服务端校验，若要启动期自检也改为从 `@Tool(domains)` 注解推导 + WARN，不引入独立配置键。

---

## 2 目标形态（推荐）

```yaml
stringer:
  server: http://localhost:9527        # ① 一个 URL 取代 host + port（含协议，https 不必再解释 scheme）
  username: stringer                   # ② 接入账号（多数部署沿用默认，可不写）
  password: stringer                   # ③ 接入密码（同上）
  tools: true                          # ④ 可选：本进程把 @Tool 注册给服务端（默认 false；不写 = 关闭）
```

**常用路径只需 3 个键**：`server`（URL）+ `tools`（按需，默认关闭）+ `username`/`password`（通常默认）。
`username`/`password` 因为默认就是 `stringer/stringer`，绝大多数情况**整段可不写**，等于真正要写的常是 **1~2 个键**（`server` + 偶尔 `tools: true`）。

---

## 3 高级键（保持原样，退到"高级配置"，不在常用路径出现）

以下键**不改名、不挪窝**，只是"默认已合理、文档不主推"：

```yaml
stringer:
  client:                               # 调用超时，几乎没人改
    health-check-timeout: 5s
    connect-timeout: 5s
    read-timeout: 10m
  tool-instance:                        # 跨机/网关部署才动
    instance-id: ...                    # 留空自动生成（应用名@主机:端口），重连必须沿用同一 id
    endpoint: ...                       # 跨机或前面有网关时必须填；同机可省略（自动推导 localhost:本端口/stringer/invoke）
    scan-annotated: true                # 关掉改编程式注册
    heartbeat-interval-seconds: 10
    max-backoff-seconds: 60
    request-timeout-millis: 10000
```

> 说明：`tool-instance.enabled` 被新的顶层 `tools` 取代（见 §4）。`tool-instance` 组其余键保留，因为跨机部署确实要配 `endpoint`/`instanceId`，硬塞进扁平结构反而难读。

---

## 4 实现方案（具体怎么改）

### 4.1 新增 `StringerProperties`（`prefix = "stringer"`）
放在 `stringer-sdk-core`（对话 SDK 与工具 SDK 都引它，避免重复）：
```java
@ConfigurationProperties(prefix = "stringer")
public class StringerProperties {
    private String server = "http://localhost:9527"; // 形如 http(s)://host:port
    private String username = "stringer";
    private String password = "stringer";
    private Boolean tools = false;                    // 取代 tool-instance.enabled（默认 false：引了 jar 不等于当工具提供方）

    // 派生：解析 server URL → host/port（供 ToolInstanceProperties.toConfig 复用）
    public String hostOf() { return URI.create(server).getHost(); }
    public int portOf()    { return URI.create(server).getPort() == -1 ? 9527 : URI.create(server).getPort(); }

    // 兼容：旧 stringer.server.host / .port（@Deprecated 字段），若设置则覆盖 URL 中的值并 WARN
    @Deprecated private String serverHost;
    @Deprecated private Integer serverPort;
    @Deprecated private String serverUsername;   // 旧 stringer.server.username 回退
    @Deprecated private String serverPassword;
}
```

### 4.2 `ServerProperties` 改造
- 保留 `host`/`port`/`username`/`password` 字段（**仍接受旧键**，向后兼容）；
- `getServerUrl()` 优先级：`StringerProperties.server`（URL）→ 否则 `http://host:port`；
- `getHost()/getPort()` 同理：URL 解析值优先，旧 host/port 兜底；
- 旧 `stringer.server.host/port/username/password` 仍绑定到本类；新增 `StringerProperties` 的 `@Deprecated` 回退字段在 `@PostConstruct` 里合并（旧值优先并打 WARN）。

### 4.3 `tools` 与 `tool-instance.enabled` 并存规则
- 新增 `StringerProperties.tools`（默认 `false`），是**唯一推荐开关**；
- `ToolInstanceProperties.enabled` 保留为**兼容别名**：自动装配时若 `enabled` 显式设置，则 `tools = enabled` 并 WARN；否则取 `StringerProperties.tools`；
- 任一为 `true` → 启用工具实例自动配置（`@ConditionalOnProperty` 改为同时看两个键）。

### 4.4 `ServerProperties` → `ToolInstanceConfig` 衔接
`ToolInstanceProperties.toConfig(server, endpoint)` 现在读 `server.getHost()/getPort()/getUsername()/getPassword()`——
改造后这几个 getter 内部已优先返回 URL 解析值，调用方**零改动**。

---

## 5 兼容性：**不兼容**（beta 破坏性改造）

> **版本约定（用户裁定）**：**测试版 = 破坏性改造，不兼容上一版本；只有正式版（GA）才做兼容。**
> 因此本节**不做**旧键映射、不做 deprecated 别名、不做"双写都认"。旧键直接删除。

| 旧写法 | 处置（无兼容） |
| --- | --- |
| `stringer.server.host` | **删除**，改用 `stringer.server`（URL） |
| `stringer.server.port` | **删除**，并入 `stringer.server`（URL） |
| `stringer.server.username` / `.password` | **删除**，改用顶层 `stringer.username` / `stringer.password` |
| `stringer.tool-instance.enabled` | **删除**，改用 `stringer.tools` |
| `stringer.client.*` | 保留（本来就是高级键，不在此次收敛范围） |
| `stringer.tool-instance.*`（除 `enabled`） | 保留（跨机部署才用） |

**原则**：
1. 旧键**一律不认**，写了也不会生效（Spring 未知属性默认忽略，不会报错，但也不会有 WARN——如需提示可在自检里加"未知键"扫描，可选）；
2. 不做"两套写法并存"，使用者只有一种写法；
3. 兼容层（deprecated 转调、旧键映射、协议旧键）**留到 1.0 正式版再做**，届时按 GA 策略统一补。

---

## 6 启动自检集成（复用 P0-4）

现有 `StartupSelfCheckConfiguration`（只 WARN 不阻断）已有"提示词↔工具"自检。D3 把配置解析结果也打进自检清单：
```
============================================================
 Stringer 接入自检
------------------------------------------------------------
 ✓ 服务端      http://localhost:9527（Stringer v1.0-beta.1）
 ✓ 凭证        已获取（账号 stringer）
 ✓ 工具实例    已注册（instance=order-service@10.0.2.7:8080，6 个工具）
============================================================
```
- 域存在性**不在 D3 范围**：运行时服务端已校验（`forDomain` 非 null 且不存在即失败）；若日后想做启动期自检，改为从 `@Tool(domains)` 注解推导域集合 + WARN（与 P0-4 一致），不引入独立配置键。

---

## 7 迁移清单（落地时一并做）

1. `stringer-example` 的 `application.yml` 改成新形态；
2. `SDK-USAGE.md` / `SDK-CONTRACT.md`（配置章节）/ `INSTANCE.md`（若有配置说明）同步；
3. `DESIGN-1.0.md` §2.5 标注 D3 已完成；
4. 在 `CHANGELOG`/release note 注明旧键 deprecated。

## 8 验收 & 测试

- 单测：`StringerProperties` URL 解析（scheme/host/port，默认端口回退）；新旧键优先级与 WARN 触发；
- 兼容单测：只写 `host/port` 仍能拼出正确 `serverUrl`；
- `stringer-example` 用新配置联调全流程不改一行跑通；
- `clean test` 全绿。

## 9 待你拍板

| # | 问题 | 选项 | 我的建议 |
| --- | --- | --- | --- |
| 1 | 一次全改 vs 分两批 | 一次全改（旧键映射保留）/ 分两批 | **一次全改**：否则使用者会同时见两套写法 |
| 2 | `username`/`password` 是否从 `server` 组提到顶层 | 提到顶层 / 留在 `server` | **提到顶层**：语义是"接入账号"不是"服务端内部"；但保留旧键回退 |
| 3 | 高级键（`client.*`/`tool-instance.*`）是否一并改名 | 不改（仅文档降级）/ 也扁平化 | **不改**：跨机部署才用，改名纯属 churn |

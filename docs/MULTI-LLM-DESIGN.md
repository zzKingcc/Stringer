# 多 LLM 接入设计（评审稿）

> 目标：一个部署里接多个模型端点，**按域（+用途）选择模型**，换模型不改业务代码、不改工具代码。
> 定位：对应 `DESIGN-1.0.md` §2（域模型）与 §6 路线图中的 T4（多 LLM 模型网关）。
> 范围：本文只设计**模型档案与解析**；域级覆盖（审批/超时/配额）与知识空间绑定不在本轮。

> ⚠️ **落地偏差**：本文是历史设计评审稿，实际落地的模型子系统已与此稿有多处不同，请以 `DESIGN.md` §9 与源码为准。要点：① 域 ↔ 模型改为 **1 对多**（有序别名列表，整体覆盖，空＝无可调用）；② **取消内置 `default` 模型别名**，未绑定的域直接报 `NotConfiguredException`，不再回落全局对话模型；③ **删除改为级联清理**（不再"被引用就拒绝"）；④ 档案改用 **3 维 schema**（`endpoints` / `input` / `output` + `capabilities` + `dimensions`），**无 `type` 字段**，是否对话看 `endpoints` 含 `chat`。

---

## 0 一句话方案

把"全局一个模型"拆成三层：

```
域 ──绑定──▶ 角色（chat / planner / summarizer）──引用──▶ 模型档案（别名）──解析──▶ 端点 + 客户端实例
```

- **模型档案**：别名 → 端点、Key、模型名、参数、降级链。**改端点不动域**。
- **域绑角色**：域按用途绑别名，同一域不同步骤可用不同模型。
- **调用期解析**：`域 + 角色 → 别名 → 档案 → 客户端`，带缓存与降级。

---

## 1 现状与根因

| 项 | 现状 |
| --- | --- |
| 装配 | `AiModelConfiguration` 暴露三个 Bean：`openAiChatModel` / `openAiStreamingChatModel` / `openAiEmbeddingModel`，都是 `LlmModelHolder` 的**委托代理**（内部 volatile 原子替换） |
| 配置 | 单份 `config/llm-settings.json`：一组 chat 参数 + 一组 embedding 参数 |
| 注入点 | `GraphConfiguration:78` 把 `StreamingChatModel` **构造期注入**给 `AgentOrchestrationService` —— **这就是"所有域同一个模型"的根因** |
| 现状能力 | 只能"整体换一个模型"（热替换），不能"按域用不同模型" |

**关键约束**：委托代理模式（Bean 名与注入点不变 + 原子替换）是这个项目做得好的地方，**不能破**。新方案要在这个基础上加一层"按域解析"，而不是推翻它。

---

## 2 模型档案（Model Profile）

### 2.1 结构

```jsonc
// config/models.json
{
  "domainBindings": {                 // 域 → 别名【有序列表】；整体覆盖；空数组＝该域无可调用
    "customer-service": ["smart", "local-qwen"],
    "contract-review": ["smart"]
  },
  "profiles": {                       // 别名 → 档案（对话 / 向量都在这里，靠 endpoints 区分）
    "smart": {
      "endpoints": ["chat"],          // 端点族：是否对话看是否含 "chat"
      "input": ["text"],
      "output": ["text"],
      "baseUrl": "https://api.example.com/v1",
      "apiKey": "******",
      "modelName": "gpt-4o",
      "temperature": 0.3,
      "maxTokens": 4096,
      "capabilities": ["streaming", "tools"],   // 布尔能力声明（见 2.3）；无 "vision"
      "fallbacks": ["local-qwen"]               // 降级链：本档案失败后依次尝试同列表其它别名
    },
    "local-qwen": {
      "endpoints": ["chat"],
      "baseUrl": "http://localhost:11434/v1",
      "apiKey": "ollama",
      "modelName": "qwen2.5:7b",
      "temperature": 0.5,
      "maxTokens": 2048,
      "capabilities": ["streaming", "tools"],
      "fallbacks": []
    },
    "emb-bge": {
      "endpoints": ["embedding"],     // 向量：是否向量看是否含 "embedding"
      "baseUrl": "https://api.example.com/v1",
      "apiKey": "******",
      "modelName": "bge-m3",
      "dimensions": 1024              // 留空＝按实测
    }
  }
}
```

// 注：不再有 "default" 这个保留别名，也不再由 llm-settings.json 自动合成档案；
// 落盘结构无 "type" 字段；删除档案会级联摘掉所有域绑定（见第 7 节）。

### 2.2 端点族与用途的关系

| 档案 `endpoints` 含 | 用途 | 说明 |
| --- | --- | --- |
| `chat` | 对话链路（本轮只做 chat） | 一个档案可服务多用途，靠 `endpoints` 声明它支持哪些端点族 |
| `embedding` | 向量（**只被知识空间引用**，见第 6 节；实际仍走 `llm-settings.json` 的单一向量配置） | 不与对话链路混用 |
| `rerank` / `images` / `tts` / `asr` / `video` | 其它端点族（规划中） | 多模态按 `endpoints` 扩展，可多选 |

> 注：档案**没有 `type` 字段**，`isChat()` = `endpoints.contains("chat")`。角色的拆分（`planner` / `summarizer`）本轮未做，域直接绑 chat 档案。

### 2.3 能力声明（`capabilities`）

**必须在档案里显式声明，而不是靠试错**：

| 能力 | 不声明的后果 |
| --- | --- |
| `streaming` | 被用于对话链路时无法逐帧下发 TOKEN |
| `tools` | 模型不调用工具 —— 现在只有 README 里一句提醒，改由配置表达并可在管控台校验 |
| `vision` | 多模态输入（未来）不可用 |

域绑定到"缺 `tools` 的档案"时，管控台应给**告警**（不是拒绝）——这是最容易踩的坑：换了个便宜模型，工具静默失效。

---

## 3 域绑角色

### 3.1 绑定结构

```jsonc
// config/models.json 的 domainBindings 段：域 → 别名【有序列表】
{
  // 首个为当前对话模型，其余为降级 / 多 agent 备用
  "customer-service": ["smart", "local-qwen"],
  "contract-review": ["smart"]
}
// 空数组 / 缺省＝该域"无可调用"；整体覆盖；不再有"单值 + 默认别名回落"那套
```

### 3.2 解析优先级（域 → 别名列表 → 档案）

1. `domainBindings[domain]`：该域显式绑定的别名<b>有序列表</b>；按序试，首个 `isUsable()` 且 `isChat()` 的档案即命中。
2. 列表为空 / 全部不可用 → **不再回落任何默认值**，由 `DefaultModelResolver` 抛 `NotConfiguredException`（`90005`）。

> 历史稿里"角色回落 / `defaultAlias` / 内置 `default` 档案"四级链路已取消：域必须先显式配置自己的模型，未配置即明确失败 —— **宁可明确报 `90005`，也不静默降级到"猜一个能用的"**（那样会让问题更隐蔽）。

---

## 4 解析与缓存

### 4.1 解析链路

```
域 + 角色
   │ ① 查域绑定表（内存，变更即刷新）
   ▼
别名 alias
   │ ② 查档案注册表（ModelProfileRegistry）
   ▼
档案 + 指纹（baseUrl + modelName + 参数 + SHA-256(apiKey)）
   │ ③ 查客户端缓存：指纹未变 → 复用实例
   ▼
OpenAiChatModel / OpenAiStreamingChatModel 实例
```

### 4.2 缓存与失效

| 项 | 规定 |
| --- | --- |
| 缓存键 | **档案指纹**（含 Key 哈希）——Key 或端点一变，指纹变，自然拿到新实例，无需手工清缓存 |
| 旧实例 | 不立即销毁（可能有在途请求）；交给 GC，无长连接需显式关闭 |
| 单例性 | 同一指纹全局共享一个实例（模型客户端本身线程安全） |

### 4.3 降级链

```
主档案调用失败（连接失败 / 5xx / 超时）
   │ 换 fallbacks[0]
   ▼
备档案失败 → fallbacks[1] … 全部失败
   ▼
报 90003（LLM_UNAVAILABLE）或 90000（LLM_TIMEOUT），事件里带上"已尝试的档案清单"
```

- **只在"请求根本没成功"时降级**：已经流出部分 TOKEN 再切断会造成半截回答，不降级（与工具重试同一条原则：传输层失败才换，业务失败不换）。
- 降级链是**档案级**配置，不是域级 —— 换模型供应商是运维动作，不该要求动域。

---

## 5 运行时改造点（关键一步）

### 5.1 分层问题

`AgentOrchestrationService` 在 **runtime** 模块，模型解析实现要在 **server** 模块。因此解析器接口要放在 `stringer-api`：

```java
// stringer-api：新增
public interface StringerModelResolver {
    /**
     * 按域与角色解析可用的流式对话模型。
     * @param domain 域标识（完整路径，为空按根域）
     * @param role   用途：chat / planner / summarizer / rerank
     * @return 该域该角色应使用的模型
     */
    StreamingChatModel streamingChat(String domain, String role);
}
```

### 5.2 改动清单

| 模块 | 改动 | 兼容做法 |
| --- | --- | --- |
| `stringer-api` | 新增 `StringerModelResolver` 接口（+ 角色常量） | 纯新增 |
| `stringer-server` | 新增 `ModelProfileRegistry`（档案）、`ModelClientFactory`（按档案建实例 + 缓存）、`ModelResolver`（实现上述接口） | 纯新增 |
| `stringer-server` | `GraphConfiguration` 构造 `AgentOrchestrationService` 时多传一个 `ObjectProvider<StringerModelResolver>` | 无实现时不传 → 行为不变 |
| `stringer-runtime` | `AgentOrchestrationService` 增加可选 resolver 字段；`agentNode` 内按域取模型，取不到时**回落到构造期注入的模型** | 回落后行为与现在**完全一致** |
| 现有三个模型 Bean | **保持不动**：继续作为 `default` 档案的委托代理，`openAiEmbeddingModel` 等注入点零改动 | 这是"不破代理模式"的关键 |
| 管控台 | 「模型设置」升级：档案列表 + 每个档案的测试连接与模型名拉取；域空间页加"chat 模型档案"选择 | 旧页面继续可用 |

### 5.3 一个具体细节

`AgentOrchestrationService.agentNode` 现在是：

```java
streamingChatModel.chat(request, handler);   // 字段：构造期注入的单例
```

改为：

```java
StreamingChatModel model = resolveModel(context.profile());   // resolver 为空则返回字段
model.chat(request, handler);
```

改动只有这一行 + 一个 `resolveModel` 私有方法。**图结构、条件边、tools 节点、中断与 resume 全都不动。**

---

## 6 Embedding 的特殊约束（必须单独讲）

**chat 模型可以按域随便切，embedding 不行。** 原因：

```
embedding 维度 → ES 索引的 dense_vector dims（建索引时定死，改不了）
              → 已写入的向量全部按该维度生成
```

所以：

| 项 | 规定 |
| --- | --- |
| 谁能引用 embedding 档案 | **只有知识空间**，不是"域直接绑 embedding" |
| 一个知识空间的约束 | 一个知识空间 = 一个 embedding 档案 + 一个维度 + 一个索引 |
| 同时生效的 embedding | 同一时刻**只能有一个**：向量模型只有 `llm-settings.json` 里那**一套**，全局共用 |
| 索引粒度 | **已经是"一域一索引"**（`stringer_kb_<域路径哈希>`，索引跟着域走），不再是全局单索引 |
| 切换 embedding | 等价于换维度 → **必须重建全部知识库索引**（现有语义，方向不变：`NEEDS_REBUILD` / `DECLARED_MISMATCH` 四态预检） |
| 本轮做什么 | **注册多个 embedding 档案（可切换），但同一时刻仍只有一个生效** —— 与现状一致，不引入维度冲突 |

**多 embedding 按域并用**的卡点已经从"索引粒度"变成"维度一致性"：一域一索引早已具备，
但所有索引都按同一套向量模型建，维度必须一致；要让 A 域用 128 维、B 域用 1024 维，
得先把"域 → embedding 档案"这条绑定与"索引与档案成对创建"一起补上。

⚠️ **这一条是本设计里最容易做错的地方**：如果让"域按 chat 的方式绑 embedding"，模型 A 写入 1024 维、模型 B 查询 768 维，检索结果不会报错，只会**悄悄地全错**。

---

## 7 接口设计（管理面）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/admin/model-profiles` | 档案列表（Key 脱敏，含 `endpoints` / `input` / `output` / `capabilities` / `usedByDomains`）+ `domainBindings`（域→别名列表）；**不再返回 `defaultAlias`** |
| POST | `/admin/model-profiles` | 创建/更新档案（`alias` 为键；字段为 `endpoints` / `input` / `output` / `baseUrl` / `apiKey` / `modelName` / `temperature` / `maxTokens` / `dimensions` / `capabilities` / `fallbacks`，**无 `type`**） |
| POST | `/admin/model-profiles/probe` | 实测一个模型的端点族 / 模态 / 能力 / 维度（不落盘） |
| POST | `/admin/model-profiles/{alias}/probe` | 用档案已存配置重新探测并写回档案 |
| POST | `/admin/model-profiles/{alias}/test` | 测试连接（返回实测可用性 + 模型名） |
| DELETE | `/admin/model-profiles/{alias}` | 删除档案并**级联清理**所有域绑定（不再"被引用就拒绝"） |
| PUT | `/admin/model-bindings/{domain}` | 设置域的可调用别名列表（整体覆盖，顺序即优先级；空＝解绑；`default` 不可作为别名） |

**删除保护**是必须的：删掉一个还在被域引用的档案，等于让那个域在下次调用时报 `90005`。

---

## 8 观测与成本

| 项 | 做法 |
| --- | --- |
| 事件 | `AgentEvent` 在 `DONE` / `ERROR` 帧带上 `modelAlias` 与 `modelName`（**只加字段，不改语义**） |
| 日志 | 每次解析命中记 DEBUG（域/角色/别名/指纹前 8 位）；降级与熔断记 WARN |
| 成本 | 每次对话按 `usage`（已在 `TokenUsageRecorder` 统计）挂到"域 + 档案"两个维度，先在日志与指标里体现，不做账单 |

---

## 9 分阶段（每阶段可独立上线）

| 阶段 | 内容 | 兼容 | 验收 |
| --- | --- | --- | --- |
| **M1 档案** | `ModelProfileRegistry` + `config/models.json` + 管理接口；现有 `llm-settings.json` 自动合成 `default` 档案 | 运行时**零改动**；旧端点继续可用 | 管控台能看到 `default` 档案；新建一个档案不影响任何现有行为 |
| **M2 解析** | `StringerModelResolver` + `agentNode` 按域取模型；域绑角色 | resolver 缺失 → 回落单例（行为不变） | 两个域绑不同档案，**实测走不同端点**；未绑定的域与现在完全一致 |
| **M3 韧性** | 降级链 + 按档案熔断 + 测试连接扩展 | 默认 `fallbacks: []`（不降级） | 主档案地址改成不可达 → 自动走备档案并出 TOKEN |
| **M4 体验** | 域空间页的模型绑定 UI；事件带 `modelAlias`；成本按域/档案统计 | 纯增量 | 管控台能改绑定并立即生效（不用重启） |

---

## 10 待你拍板

| # | 问题 | 选项 | 我的建议 |
| --- | --- | --- | --- |
| 1 | 档案落盘位置 | 新文件 `config/models.json` / 塞进现有 `llm-settings.json` | **新文件**：`llm-settings.json` 只保留全局 chat + 单一 embedding；`models.json` 的档案**不再由它合成 `default`**，两者各管各的 |
| 2 | 域绑定的存储 | 独立 `config/domain-models.json` / 等 S5 并入域配置 | **先独立文件**，S5 做域配置时再迁，避免两件事耦合 |
| 3 | 角色先做几个 | 只做 `chat` / 一次做全（chat、planner、summarizer、rerank） | **先只做 `chat` + `summarizer`**：前者刚需，后者能立刻省钱；planner/rerank 等编排与检索深化时再加 |
| 4 | 降级策略 | 不降级 / 档案级降级链 / 域级覆盖降级链 | **档案级**：换供应商是运维动作，不该要求动域 |
| 5 | 能力校验 | 不管 / 绑定时告警 / 绑定时拒绝 | **告警**：拒绝会让"先用便宜模型跑通"这件事变得很麻烦 |
| 6 | 是否本轮就做 embedding 多档案 | 做 / 不做 | **只注册、不并用**：多 embedding 并用必须等"域绑知识空间"，否则会静默出错 |

---

## 附：与既有不变式的关系

| 不变式 | 本设计如何遵守 |
| --- | --- |
| 三个模型 Bean 名与注入点不变（委托代理） | 完全保留；`openAiEmbeddingModel` 的 4 个注入点零改动 |
| 向量维度唯一入口 `effectiveEmbeddingDimension()` | 保留；多 embedding 只做"可切换"，入口语义不变 |
| 执行单元内冻结 | 模型在**单元开始时解析一次**，单元内不变（与域、提示词同一条冻结规则） |
| 主循环形状不变 | 只改 `agentNode` 里取模型的那一行 |
| 域不可绕过 | 解析的输入就是域：解析器存在时**未绑定的域直接抛 `NotConfiguredException`**（不回落任何默认），因此绝不会"用错模型却不自知" |

---

## 11 实施进度（2026-09-27 · 按拍板后的规则）

### 11.1 拍板结果（已落入实现）

| # | 规则 | 实现位置 |
| --- | --- | --- |
| 1 | **档案不自动绑定任何域**，绑定只能由管控台手工指定 | `ModelProfileRegistry.save()` 只写档案，不碰 `domainBindings` |
| 2 | **一个域可绑一组有序模型别名** | `domainBindings` 是 `域 → 别名列表` 映射（有序，整体覆盖）；首个为当前对话模型，其余留给多 agent / 降级；空列表＝该域"无可调用" |
| 3 | **向量模型仍只一个** | 单一 embedding 走 `llm-settings.json`（单索引单维度）；`models.json` 可登记 embedding 档案，但暂不被检索链路消费 |
| 4 | 未绑定的域 | **无可调用**：调用时抛 `NotConfiguredException`（`90005`）；不再回落任何默认模型，"没配绑定"＝该域直接失败而非静默用全局模型 |

### 11.2 已落地

| 模块 | 改动 |
| --- | --- |
| `stringer-runtime` | 新增 `runtime/model/ModelResolver` 接口（`streamingChat(domain)`）；`AgentOrchestrationService` 增加**可选** resolver 字段与构造重载，`agentNode` 按域取模型，新增 `resolveModel()`：resolver 为 `null`（未装配档案体系）时用构造期注入的模型；**resolver 存在则不再回落，解析失败如实抛出** |
| `stringer-server` | 新增 `model/` 包：`ModelProfile`（record，3 维 schema：`endpoints` / `input` / `output` + `capabilities` + `dimensions`，`isChat()`＝`endpoints` 含 `chat`；含 `fingerprint()` / `supportsTools()` / `capabilityHint()`）、`ModelProfileSettings`（落盘结构）、`ModelProfileStore`（`config/models.json`）、`ModelProfileRegistry`（唯一真相，含"后设覆盖"与"级联清绑定"）、`ModelClientFactory`（按指纹缓存，每个别名只留当前指纹）、`DefaultModelResolver`（只认自建档案，未绑定 / 不可用直接抛 `NotConfiguredException`，无内置 default 旁路） |
| `stringer-server` | 新增 `config/ModelProfileConfiguration`；`GraphConfiguration.agentService` 注入 `ModelResolver` 并传给内核 |
| `stringer-server` | 新增 `AdminModelProfileController`：`GET/POST /admin/model-profiles`、`POST /admin/model-profiles/probe`、`POST /admin/model-profiles/{alias}/probe`、`POST /admin/model-profiles/{alias}/test`、`DELETE /admin/model-profiles/{alias}`（级联清绑定）、`PUT /admin/model-bindings/{domain}`（`aliases` 空＝解绑；返回 `sourceDomain` 标明生效绑定来自链上哪个域） |
| `stringer-server` | 域绑定改为**沿链回落**：自身没绑就向上找最近一个绑了模型的祖先，根域也未绑才是"无可调用"。回落的是**域链**而非任何内置默认值，与"无内置 default"不冲突。纯逻辑在 `ModelProfileSettings.resolveAlong`（不依赖容器，可单测） |
| 管控台 | `console/models.html` 新增「模型档案」卡（列表 + 编辑 + 测试 + 删除）与「域 → 模型绑定」卡（域清单 × 当前绑定 × 下拉设置），规则文案写进页面 |

### 11.3 两个刻意的设计取舍

1. **没有内置 `default` 模型别名**：对话模型只来自自建档案，域未绑定即明确失败；`LlmModelHolder`（`llm-settings.json`）的对话模型仅作"解析器缺失"时的兜底与 `chatConfigured` 指示来源，`openAiEmbeddingModel` 的 4 个注入点一行未动（向量仍走它）。
2. **`api` 模块不放解析接口**，放在 `runtime` —— 因为 `api` 没引 langchain4j，把模型类型塞进对外契约会污染 SDK 依赖。

### 11.4 尚未落地

- 降级链（`fallbacks` 已能存，M3 才生效）与按档案熔断
- 角色概念（`planner` / `summarizer`）—— 当前的"域绑有序别名列表"已为此留位（列表里扩一层角色语义即可），本轮仍不做
- 事件带 `modelAlias`；成本按域/档案归集
- 域空间页直接改绑定（当前在「模型设置」页；域空间页只读展示）

# 01 · 从 Java 服务端到 AI Agent

> 同学，这一讲我们不碰任何 `@Autowired`，只动你已经在用的那套 Spring 分层思维。  
> 目标只有一个：**把 Agent 放进你熟悉的架构图里，而不是放进一个名叫「AI 魔法」的黑盒。**

---

## 你现在在哪

你已经独立做过 Spring Boot 服务：Controller 接 HTTP，Service 写业务，Repository 或 Mapper 访问数据库，Filter 或 Interceptor 做鉴权、日志、限流。你可能还用过 `@Cacheable`、Redis、消息队列、定时任务。

与此同时，你听到「Agent」时的第一反应可能是下面之一：

- 「是不是要多写一个 `ChatController`，里面调 HTTP 调 DeepSeek？」
- 「是不是要把 LangChain 那套搬过来？」（讲义答案：不搬 Python，用 Spring AI。）
- 「是不是要新招一个算法工程师？」

**你现在还缺的那块拼图是：Agent 在 Spring 应用里，到底占哪几层、和现有 Service 怎么协作。**

---

## 学完本讲你能干什么

1. 用 **Controller → Service → Repository** 的语言，说出 Spring AI 里 **ChatClient → Advisor 链 → ChatModel / Tool → 数据源** 的对应关系。
2. 向同事解释：为什么 **Tool 应该复用或薄封装现有 Service**，而不是在 Prompt 里让模型「假装查库」。
3. 区分 **编排层（Agent 循环）** 和 **能力层（业务方法、SQL、向量检索）**，避免把全部逻辑塞进一个巨大的 system prompt。
4. 列出你项目里「适合暴露成 @Tool 的只读方法」至少 3 个候选（先写名字即可）。

---

## 建议时长

**1.5～2 小时。** 含阅读、画对照图、完成本章作业。若你对 Filter 链和 Facade 模式很熟，阅读可压缩到 1 小时，但作业不要省。

---

## 1. 先回忆：一个典型 Spring 请求是怎么走的

我们以你熟得不能再熟的一条查询为例：「按 SKU 查华东仓可用库存」。

```
HTTP GET /api/inventory?sku=ABC&warehouse=HD
        │
        ▼
   Controller          校验参数、取当前用户、组装 DTO
        │
        ▼
   Filter / Interceptor  鉴权、MDC 日志、限流（在进 Controller 前后）
        │
        ▼
   Service               业务规则：权限、仓库范围、是否合并在途
        │
        ▼
   Repository / Mapper   SQL 或存储过程，返回事实数据
        │
        ▼
   数据库                 唯一可信的数字来源
```

这条链里，每一层的职责是清晰的：

- **Controller** 不负责算库存，只负责 HTTP 语义。
- **Service** 不负责拼 SQL 细节（理想情况下），负责业务规则与组合。
- **Repository** 不负责猜数字，只负责把查询结果搬回来。
- **数据库** 是事实的最终裁判。

【红线】请记住：**可信的库存数字，来自数据库那条链，不是来自 Controller 里的 `if-else` 瞎编。**

接下来我们要做的，是把「用户用自然语言问：华东 ABC 还有多少？」接进这条链——**而不是绕开这条链。**

---

## 2. 用户用自然语言提问时，多出了什么？

管理层不会永远按你设计的 REST 路径提问。他可能说：

> 「帮我看下 ABC 在华东还能撑几天，上周采购那批到了没？」

这句话里混合了：

- **意图**：查库存、关联采购到货；
- **实体**：SKU=ABC、区域=华东、时间=上周；
- **推理期望**：「撑几天」需要库存、日均消耗、在途——其中只有部分能直接从一张表读出。

若你只做 REST，通常要：

1. 前端或 BFF 把自然语言拆成多个 API 调用；
2. 或者写死一堆「问法 → API」的规则（十年前的聊天机器人套路）。

**Agent 路径**是：让大模型理解意图、决定调哪些 **Tool**（受控的 Service 方法），拿到**真实返回**后再组织成中文答复。模型负责「理解和表达」，Java 负责「事实和执行边界」。

用 Java 类比整条新链路：

```
自然语言 HTTP  POST /api/agent/chat
        │
        ▼
   AgentController        类似 Controller，但入参是 message + sessionId
        │
        ▼
   ChatClient             类似业务门面 Facade：对外一个 chat()，对内组装 Advisor + Model
        │
        ▼
   Advisor 链             类似可循环的 Filter：鉴权、记忆、Tool 循环、日志、护栏
        │    ┌──────────────────────────────────┐
        │    │ ToolCallingAdvisor 可能多轮：     │
        │    │ 模型说「我要调 queryStock」→      │
        │    │ 执行 @Tool → 结果塞回上下文 →     │
        │    │ 再问模型 → 直到不再调 Tool        │
        │    └──────────────────────────────────┘
        │
        ├──► ChatModel（DeepSeek）   类似 JDBC Driver：真正发网络请求的那一层
        │
        └──► @Tool 方法                类似受控的 Service 方法，只暴露允许的能力
                    │
                    ▼
              现有 Service / 只读 Mapper
                    │
                    ▼
              ERP / WMS / EMS 库或只读视图
```

看到关键了吗？**Agent 不是替换掉 Service 层，而是在 Service 层之上（或旁边）多了一条「语言入口 + 模型编排」的链。** 数据库仍然是数字的裁判。

---

## 3. Spring AI 2.0 核心角色与 Java 类比（请背熟对应关系，不是背 API 名）

### 3.1 ChatClient ≈ 业务门面（Facade）

日常开发你主要注入 **ChatClient**，而不是到处 `@Autowired ChatModel`。

它像你自己写的：

```java
public class InventoryAssistantFacade {
    public Answer ask(Question q) {
        // 组装 advisor、system prompt、用户上下文
        // 调用底层 model
        // 统一异常、超时、审计字段
    }
}
```

Spring AI 的 ChatClient 就是官方帮你标准化了的门面：**链式配置 system、user、advisors、tools，最后 `call()` 或 `stream()`。**

你写 Controller 时，应依赖 ChatClient，而不是在 Controller 里拼 JSON 调 DeepSeek。

### 3.2 ChatModel ≈ 底层 JDBC 驱动

**ChatModel** 接口背后是具体厂商实现（DeepSeek、OpenAI 兼容实现等）。它关心的是：消息列表进、Assistant 消息出、流式 chunk、usage 统计。

类比：

| JDBC 世界 | Spring AI 世界 |
|-----------|----------------|
| `DataSource` | 模型配置、API Key、baseUrl |
| `Connection` | 一次 chat 会话中的请求通道 |
| `PreparedStatement` | 构造好的 Prompt（消息 + 选项） |
| `ResultSet` | 模型返回的 `AssistantMessage`、ToolCall |
| 驱动实现类 | `DeepSeekChatModel` 等 |

你不会在 Controller 里直接 `new DeepSeekChatModel()` 乱搞，就像你不会在 Controller 里 `new Connection()`。配置在 Starter 里，测试里可 Mock ChatModel。

### 3.3 Advisor ≈ 可循环的 Filter

Servlet Filter 在请求进 Controller **之前/之后**走一圈；Spring AI 2.0 的 **Advisor** 在「一次 Chat 调用」的前后走一圈，且 **ToolCallingAdvisor 可以在一次用户提问里循环多轮**。

典型 Advisor 职责：

- **鉴权 / 租户**：当前用户能调哪些 Tool？
- **ToolCallingAdvisor**：解析模型的 tool call，执行 Java 方法，把结果写回消息列表，再请求模型。
- **RetrievalAugmentationAdvisor（RAG）**：先从向量库捞文档片段，再进模型。
- **MessageChatMemoryAdvisor**：带上会话历史，像会话级缓存。
- **自定义护栏**：敏感词、必须引用 tool 结果才能答数字等。

类比 Filter 链：**顺序很重要**；前面 Advisor 改过的 Prompt，后面都能看到。和 `@Order` 一样要设计。

### 3.4 Tool ≈ 受控的 Service 方法

`@Tool` 标注的方法，是模型**被允许**调用的能力清单。它应该：

- 参数有清晰 Java 类型和校验（SKU 格式、仓库编码枚举）；
- 内部调用你已有的 **只读** Service 或 Mapper；
- 返回结构化结果（DTO、Map、JSON 字符串），便于模型引用；
- 带权限：当前 session 的用户只能查其组织范围内的仓。

**错误示范：** 一个 Tool 里写 `return "库存大概 500"`——那是幻觉进代码，比模型幻觉更恶劣。

**正确示范：** Tool 名 `queryAvailableStock`，参数 `sku`、`warehouseCode`，内部 `inventoryReadService.query(...)`，返回 `{ "sku": "ABC", "qty": 127, "uom": "EA" }`。

### 3.5 RAG ≈ 带语义的只读缓存检索

RAG（检索增强生成）不是查 MySQL 替代品。它适合：

- 制度 PDF、SOP、设备手册、合同条款摘要；
- 「停机该走哪张审批单」这类**文档型**知识。

流程像：用户问题 → Embedding 向量化 → VectorStore 相似度搜索 → 把 TopK 片段塞进 Prompt → 模型基于片段回答。

类比：**像 Redis 里存不了结构化 SQL，但存「段落 + 向量索引」；命中的是语义相近的文本，不是精确的主键查询。** 业务数字仍以 Tool + SQL 为准。

### 3.6 Memory ≈ 会话级缓存

多轮对话里，用户说「那 B 仓呢？」——「那」指代上一轮 SKU。Memory 保存最近 N 轮 user/assistant 消息，按 `sessionId` 隔离。

类比：`HttpSession` 或 Redis `session:{id}:messages`，**不是**把全公司库存缓存在内存里。Memory 存的是对话轨迹，不是 ERP 事实库。

---

## 4. 从「写 API」到「做 Agent」：思维要转的四个弯

### 4.1 从「固定契约」到「非固定问法，但固定能力边界」

REST API 的契约是 URL + 字段；Agent 的契约是 **Tool 列表 + 权限 + system prompt 里的红线**。问法可变，**能调用的能力不可随意扩**。

第一版建议：Tool 少而精，只读，每个 Tool 对应你 REST 里已经存在、已经测过的查询。

### 4.2 从「一次请求一次 SQL」到「一次提问多轮 Tool 循环」

用户一句「ABC 华东能撑几天」，模型可能：

1. 调 `queryAvailableStock(ABC, HD)`；
2. 调 `queryDailyConsumption(ABC, HD, 30)`；
3. 再生成结论。

这是 **ToolCallingAdvisor** 驱动的循环，不是你在 Service 里写死的顺序——**但每个 Tool 内部仍是你的 Java 代码，可控可测。**

### 4.3 从「返回 JSON」到「返回自然语言 + 可追溯依据」

管理层要听中文结论，但系统应记录：用了哪些 Tool、参数是什么、返回 JSON 快照、最终答复。便于审计与排障。

你在 Controller 层就要设计 **traceId、toolCallLog**，类似现有 API 的 access log，不能只存一句 assistant 文本。

### 4.4 从「模型输出即结果」到「模型输出是草稿，事实是 Tool 返回值」

这是企业 Agent 与玩具 Demo 的分水岭。

- Demo：模型说「库存约 500」，界面显示 500。
- 企业：模型必须调用 `queryAvailableStock`；若 Tool 失败，应回答「暂时查不到数据」，而不是编数字。

【红线】**数字必须来自 Tool（背后是 SQL/视图），不是来自 ChatModel 的「灵感」。**

---

## 5. 对照表：一张图装下两套世界

```
┌─────────────────────┬──────────────────────────────────────────────┐
│ 传统 Spring 服务端   │ Spring AI Agent（本讲义主线）                 │
├─────────────────────┼──────────────────────────────────────────────┤
│ Controller          │ AgentController / ChatController              │
│ DTO 校验            │ 用户 message + sessionId + 可选结构化上下文   │
│ Service 门面        │ ChatClient                                  │
│ Filter 链           │ Advisor 链（含 Tool 循环）                   │
│ 业务 Service 方法   │ @Tool 方法（应薄，委托给原 Service）          │
│ Repository / SQL    │ 只读 Mapper、宽表视图、EMS 时序查询           │
│ 数据库              │ ERP / WMS / EMS — 事实源                     │
│ @Cacheable          │ RAG VectorStore（文档）+ Memory（会话）       │
│ JDBC Driver         │ ChatModel（DeepSeek 实现）                    │
│ 配置 DataSource     │ application.yml + DEEPSEEK_API_KEY            │
└─────────────────────┴──────────────────────────────────────────────┘
```

请把这张表抄进你的笔记本，并在右侧加一列「我们项目实际类名（待定）」。

---

## 6. 三个管理层问题，在 Java 分层里落在哪？

提前预习（第 05 讲会展开架构图），这里只练「分层落点」：

| 管理层问题 | 应走的层 | 不应走的层 |
|------------|----------|------------|
| ABC 在华东仓可用库存多少，安全库存够吗？ | Tool → `InventoryReadService` → 只读库存视图 | 让模型「根据经验估」 |
| 哪些采购单超期未到货，影响哪条产线？ | Tool → `PoReadService` → PO 宽表 + 物料产线映射 | RAG 去搜制度 PDF |
| 空压站昨日非计划停机多久，近 30 天几次？ | Tool → `EmsTelemetryService` → 停机事件视图 | Memory 里「记得昨天说过」 |

看到规律了吗：**结构化事实 → Tool + SQL；制度口径 → RAG；指代与多轮 → Memory。** 不要用一个技术包打天下。

---

## 7. 课堂案例：一条 REST 链改造成 Agent 链（仍不写字，只动脑子）

假设你们已有成熟接口：

```
GET /api/v1/stock?sku=ABC&wh=HD  → StockController → StockQueryService → StockMapper
```

产品经理说：「管理层想在手机上看，不想记参数。」

**错误路线 A：** 新做一个 `MobileStockController`，里面写自然语言 if-else：「包含华东就 wh=HD」——三个月维护地狱。

**错误路线 B：** `ChatController` 直接 `chatClient.prompt().user(msg).call()`，没有任何 Tool——演示快，数字不可信。

**讲义推荐路线 C：**

1. **复用** `StockQueryService`（只读），新增 `@Tool queryAvailableStock(sku, wh)`，内部一行委托 Service。
2. **新增** `ManagementAgentController`，注入 ChatClient，注册 ToolCallingAdvisor + 鉴权 Advisor。
3. **system prompt** 写清：涉及库存必须调 Tool；Tool 失败就说查不到。
4. **日志** 记录 toolName=queryAvailableStock、sku、wh、userId。

用 Java 类比总结：**你没有换发动机（数据库），只是加了一个「自然语言方向盘」（ChatClient）和「离合器踏板」（Advisor 循环），动力仍从 Service 来。**

### 7.1 和事务、@Transactional 的关系

同学常问：Agent 的 Tool 要不要 `@Transactional`？

- **第一版只读 Tool**：只读查询，一般 **只读事务** 或不需要写事务，跟现有 QueryService 一致。
- **未来若做「建议单」**：写操作建议进 **独立审批表**，与 ERP 核心表隔离；仍不是直接改库存。

【红线】不要在 Agent 的 Tool 里开 **写事务** 改 ERP 主表。这不是 `@Transactional` 技术问题，是 **业务边界** 问题。

### 7.2 和现有 Filter、Interceptor 谁先做鉴权

建议：**HTTP 层 Filter 做 SSO 与 session**；**Advisor 做 Tool 级数据范围**（例如用户只能查所属 `orgId` 的仓）。

两层都要。只做 HTTP 鉴权不够——模型可能从 user message 里套出别的 `warehouseCode`；Tool 内必须再校验。

### 7.3 和缓存（Redis）别搞混

| 缓存什么 | 用什么 | 注意 |
|----------|--------|------|
| 会话多轮 | Memory / Redis session | 不存库存事实 |
| 热点 SKU 库存 | `@Cacheable` on Service | TTL 短；答复里仍带 asOfTime |
| 制度 PDF 向量 | VectorStore | RAG 用，不是库存 |

**RAG ≈ 带语义的只读缓存检索**：命中的是 **段落相似度**，不是 **主键精确查询**。库存不要用 VectorStore 代替 SQL。

### 7.4 小结：Agent 是「新入口」，不是「新宇宙**

你十年 Spring 经验 **全部算数**：分层、只读查询、权限、日志、配置外部化。Spring AI 2.0 是 **官方把「调模型 + Tool 循环」标准化了**，不是让你抛弃 Service 层。

### 7.5 Spring AI 2.0 与 1.x：心智模型层面的唯一提醒

你现在不用记 API 差异表，只记 **一条**：

> **2.0 里，ChatClient 是一等公民；Tool 循环在 Advisor 链里，不在你 Controller 里 while 手写。**

1.x 教程常让你在 Controller 里自己 parse tool call JSON——那是 **旧宇宙**。Part 00 建立的新宇宙是：**Advisor ≈ 可循环 Filter，ToolCallingAdvisor 替你跑循环**。

【易混】看到 `internalToolExecutionEnabled`、`toolNames()`、`QuestionAnswerAdvisor` 当 RAG 主路径—— **关掉那篇文章**，回来读我们 handbook《03-技术选型说明书》第 3.2 节对照表。

### 7.6 课后 5 分钟：对着空气讲这 4 句

合上文档，必须能脱稿讲：

1. 「Agent 是在 Spring 分层上加语言入口，不是替换 Service。」  
2. 「ChatClient 像 Facade，ChatModel 像 JDBC Driver。」  
3. 「Tool 是受控只读 Service 方法，数字只从 SQL 出。」  
4. 「RAG 查文档，Memory 记会话，都不能代替 WMS 库存。」

讲不顺，明天重读第 3、4 节。这是 Part 00 最重要的四句话。

### 7.7 扩展阅读边界（在本 Part 内）

Part 00 **允许** 你查阅：handbook 根目录《03-技术选型说明书》里 Spring AI 2.0 对照表、本目录其他五讲正文。  
Part 00 **不允许** 你深陷：Spring AI 1.x 博客、LangChain Java 实验项目、「 ten 行 HelloWorld Chat」视频。

若搜到代码里出现 `QuestionAnswerAdvisor` 作为主 RAG、`internalToolExecutionEnabled`、`chatModel.call` 在 Controller 里手写 tool 解析—— **标记为 pre-2.0**，关掉。避免 **用错误 API 巩固错误心智模型**。

### 7.8 本讲与下一讲衔接：带着「分层图」进第 02 讲

完成本章作业后，你的笔记本上应有一张 **REST 链 vs Agent 链** 对照图。下一讲我们暂时离开 Spring 分层，去拆 **ChatModel 黑盒里到底是什么**。你会看到：**即使黑盒再黑，Java 侧「数字只从 ResultSet 来」这条线不能断。**

若你仍觉得「Advisor 名字太玄」，只要记住：**它是在 ChatClient 调用 DeepSeek 前后，能插逻辑、还能在 Tool 循环里多跑几轮的可组合拦截器。** Enough for Part 00.

### 7.9 微服务 vs 单体：Agent 放哪？

第一版 **建议放在现有 Spring Boot 单体或 BFF** 里，复用 SSO 与只读数据源。单独拆 `agent-service` 不是不行，但会重复鉴权、重复连接池、重复发布链路。**Agent 是入口能力，不是必须先独立成微服务。** 等 Tool 数量、QPS、团队边界清晰后再拆不迟。

### 7.10 代码包结构建议（第一版）

```
com.company.agent
  ├── web          AgentController
  ├── config       ChatClientConfig, Advisor 顺序
  ├── tool         @Tool 类（薄，委托 read service）
  ├── advisor      自定义鉴权/护栏 Advisor
  └── audit        trace、tool 日志 AOP 或 Advisor
com.company.read   现有或新建只读 Service/Mapper（与 Agent 解耦）
```

Tool 包 **不要** 写 SQL；Mapper 仍在 read 模块。ChatClient 配置 **不要** 散落在 Controller。这样 Part 03 写代码时不会乱。

### 7.11 本讲小结：四个「不要」、四个「要」

**不要：** 在 Controller 拼 DeepSeek HTTP；在 Tool 里写假数据；用 RAG 查库存；把 Memory 当 ERP 缓存。  
**要：** 用 ChatClient 门面；Tool 委托只读 Service；数字 SQL 出数；Advisor 链上鉴权与 Tool 循环。  
能默写这四对，01 讲毕业。

### 7.12 老师叮嘱

这一讲是整个讲义的地基。后面你会遇到很多 flashy 的配置和注解，但只要心里装着 **「Controller → ChatClient → Advisor → Tool → 只读 Service → 视图」** 这条线，就不会被新名词带跑。今晚睡前把对照表再看一眼，明天进入 **大模型本质** 之前，先用自己的话讲一遍给同事听——讲不顺就重读第 3 节 Java 类比，不要带着糊涂进 02 讲。

**验收前自测：** 能否在 2 分钟内画完 REST 与 Agent 双链对照图？能否举例说明 `InventoryReadService` 如何变成 `@Tool` 且不重复写 SQL？两项都能，01 讲过关。

**关键词：** ChatClient、ChatModel、Advisor、Tool、RAG、Memory、只读 Service、ToolCallingAdvisor 循环、Facade、JDBC Driver、Filter。

**本节复盘：** 从 Java 服务端到 Agent，不是换栈，而是多一条自然语言入口链。Controller 仍在最外；ChatClient 是门面；Advisor 是可循环 Filter；Tool 是受控只读 Service；ChatModel 是 DeepSeek 驱动；ERP 视图仍是数字法官。带着这张对照图进入下一讲，你会更容易理解为什么大模型不能代替 MySQL。完成本章【必做】作业后再翻页。你已具备进入第 02 讲「大模型本质」的分层基础。加油，我们下一讲见。

---

## 【必做】作业

1. **画** 两张图（纸笔或 draw.io 均可）：  
   - 图 A：你现有项目的一条 REST 查询链；  
   - 图 B：在同一张图上叠加 ChatClient、Advisor、Tool、ChatModel。  
   用箭头标出「数字只从数据库出」的路径。

2. **列** 表：写出你系统里至少 **5 个只读查询**，标注哪些适合第一版做成 `@Tool`（名称 + 入参 + 返回字段即可）。

3. **写** 一段伪代码（不用编译）：`AgentController` 只注入 `ChatClient`，不出现 `DeepSeekChatModel`。体会门面原则。

4. **找反例**：回忆或构造一个「在 Prompt 里让模型自己算库存」的写法，用 100 字说明为什么在生产环境危险。

---

## 口头验收题

1. ChatClient 和 ChatModel 分别对应 JDBC 生态里的什么？为什么 Controller 应该依赖前者？
2. Advisor 和 Servlet Filter 像在哪里？不像在哪里（提示：循环）？
3. 为什么说 Tool 应该委托给现有 Service，而不是在 Tool 里写假数据？
4. RAG 和直接 SQL 查库存，各适合什么类型的问题？
5. Memory 缓存的是什么？和 ERP 主数据缓存有何本质区别？

---

## 常见误解

| 误解 | 正解 |
|------|------|
| 「做 Agent 要重写一套微服务」 | 多数情况下是在现有 Spring Boot 应用里加 Agent 模块/包，复用 Service 与只读数据源。 |
| 「ChatModel 够用，ChatClient 是多余的」 | 2.0 日常以 ChatClient 为一等公民；Advisor、Memory、RAG 都挂在 Client 上，可维护性高得多。 |
| 「Tool 越多越好，模型更聪明」 | Tool 过多会占上下文、增加误调概率；第一版少而精，必要时再用 ToolSearch 按需披露。 |
| 「RAG 上了就可以不连数据库」 | RAG 解决文档知识；库存、订单、停机时长等数字必须 SQL/Tool。 |
| 「Agent 就是 AI 版的 Controller」 | Controller 只处理 HTTP；Agent 是 Controller + ChatClient + Advisor 循环 + Tool + 观测，是一整条子系统。 |

---

> **下一讲：** `02-大模型到底是什么.md`  
> 我们会拆掉「模型好像什么都知道」的幻觉，从概率生成讲起，并说明为什么它永远替代不了你的 MySQL。

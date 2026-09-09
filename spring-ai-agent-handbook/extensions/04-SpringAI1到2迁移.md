# 扩展 04 · Spring AI 1.x 到 2.0 迁移

```
┌────────────────────────────────────────────────────────────────────┐
│  不要在学主线时插入本章。                                            │
│  你是按 Spring AI 2.0 + Boot 4 学的主线，本章是给「网上旧教程看懵了」 │
│  的同学补课，以及给将来维护 1.x 遗留代码的同事对照。                    │
└────────────────────────────────────────────────────────────────────┘
```

> 同学们，你们很幸运：讲义从零按 2.0 写，不用先戒毒再戒酒。  
> 但你会搜问题、会看同事两年前的笔记、会 copy 一段号称「Spring AI 最佳实践」的代码——  
> 然后编译报错：`找不到 toolNames`、`PromptChatMemoryAdvisor 没了`。  
> 这一篇就是 **旧世界的地图**，让你看懂那些教程错在哪，以及若公司真有 1.x 项目该怎么迁。

---

## 1. 先对齐版本基线：不是小版本升级

| 维度 | Spring AI 1.x | Spring AI 2.0 |
|---|---|---|
| Spring Boot | 3.x | **4.0 / 4.1** |
| Spring Framework | 6.x | **7.x** |
| JSON | Jackson 2 | **Jackson 3** |
| BOM | `spring-ai-bom` 1.1.x 等 | **`spring-ai-bom:2.0.0`** |
| 日常 API 重心 | `ChatModel` 与 `ChatClient` 混用 | **`ChatClient` 为一等公民** |
| 工具循环 | 埋在各 `ChatModel` 实现里 | **`ToolCallingAdvisor` 在 Advisor 链** |
| RAG 主推 | `QuestionAnswerAdvisor` 满天飞 | **`RetrievalAugmentationAdvisor`**（`spring-ai-rag`） |
| MCP | 分散、实验多 | 官方 MCP Java SDK 2.0 对齐 |

**迁移不是改一个 import，是整条技术栈抬升。** 若 Boot 仍停在 3.x，要先规划 Boot 4 升级窗口，再动 Spring AI。

---

## 2. 破坏性变化总表（速查）

| 1.x 常见写法 | 2.0 态度 | 你怎么做 |
|---|---|---|
| `internalToolExecutionEnabled` | **删除** | 用 `ToolCallingAdvisor`，工具循环在链上 |
| `.toolNames("foo")` | **删除** | `.tools(fooCallback)` 或 `.tools(@Tool 对象)` |
| `SpringBeanToolCallbackResolver` | **不再主推** | `MethodToolCallback` / 自动扫描 `@Tool` |
| `PromptChatMemoryAdvisor` | **删除/替换** | `MessageChatMemoryAdvisor` |
| `streamToolCallResponses` 等流式工具细节 | 行为收敛到 Advisor | 跟官方 2.0 流式示例走 |
| 直接操纵 `ChatModel` 做业务 | 不推荐 | `ChatClient` + Advisor |
| `QuestionAnswerAdvisor` 当 RAG 主路径 | 仍可能存在但非主推 | `RetrievalAugmentationAdvisor` 模块化 |
| starter 名 `spring-ai-openai-spring-boot-starter` 等 | **改名** | `spring-ai-starter-model-openai` 等 |
| 记忆随意 `chatId` | 必须规范 | **`CONVERSATION_ID`** 作为会话键 |

下面逐条展开，并给 before/after 代码。

---

## 3. ChatClient 为一等公民

### 3.1 1.x 常见写法（旧教程）

```java
@Service
public class OldChatService {

    private final ChatModel chatModel;

    public OldChatService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public String ask(String question) {
        Prompt prompt = new Prompt(new UserMessage(question));
        ChatResponse response = chatModel.call(prompt);
        return response.getResult().getOutput().getText();
    }
}
```

能跑，但你要自己拼消息、自己管工具循环、自己接流式。

### 3.2 2.0 写法（主线）

```java
@Service
public class NewChatService {

    private final ChatClient chatClient;

    public NewChatService(ChatClient.Builder builder) {
        this.chatClient = builder
                .defaultSystem("你是决策助手。")
                .build();
    }

    public String ask(String question) {
        return chatClient.prompt()
                .user(question)
                .call()
                .content();
    }
}
```

**迁移要点：** Controller/Service 层注入从 `ChatModel` 改为 `ChatClient.Builder` 或你定义的 `@Bean ChatClient`。

---

## 4. 工具循环进 Advisor

### 4.1 1.x：internalToolExecutionEnabled + toolNames

```java
// 1.x 风格 —— 2.0 不要抄
ChatResponse response = chatModel.call(
    new Prompt(messages,
        ChatOptions.builder()
            .toolNames("getInventory")
            .internalToolExecutionEnabled(true)
            .build()));
```

模型内部帮你循环调工具，**很难在循环中间插记忆、审计、RAG**。

### 4.2 2.0：ToolCallingAdvisor + .tools()

```java
@Service
public class InventoryTools {

    @Tool(description = "按 SKU 查询库存数量")
    public int getInventory(String sku) {
        return inventoryRepo.countBySku(sku);
    }
}

@RestController
public class AgentController {

    private final ChatClient chatClient;

    public AgentController(ChatClient.Builder builder, InventoryTools tools) {
        this.chatClient = builder
                .defaultTools(tools)
                .build();
    }

    @PostMapping("/agent")
    public String run(@RequestBody String question) {
        return chatClient.prompt()
                .user(question)
                .call()
                .content();
    }
}
```

`ToolCallingAdvisor` 由 `ChatClient` 自动配置注册（恰好一个 Tool Advisor 的规则仍适用）。循环在 **Advisor 链**上，你可以：

```java
builder
    .defaultAdvisors(
        MessageChatMemoryAdvisor.builder(chatMemory).build(),
        // ToolCallingAdvisor 通常自动存在
        myAuditAdvisor)
    .defaultTools(tools)
    .build();
```

### 4.3 SpringBeanToolCallbackResolver 迁移

1.x 有人用 `SpringBeanToolCallbackResolver` 从容器按名找工具。2.0 推荐：

- 在带 `@Tool` 的 Spring Bean 上 `.defaultTools(bean)` 或 `.tools(bean)`
- 或显式 `MethodToolCallback.builder()...build()` 组成 `ToolCallback[]`

**原则：** 工具是你写的 Java 方法，解析方式应显式，不要隐式魔法 Bean 名。

---

## 5. 记忆：PromptChatMemoryAdvisor → MessageChatMemoryAdvisor

### 5.1 1.x

```java
// 已废弃思路
var advisor = new PromptChatMemoryAdvisor(chatMemory, "session-1", 10);
```

把记忆塞进 Prompt 层，和工具消息、RAG 片段容易搅在一起。

### 5.2 2.0

```java
ChatMemory chatMemory = MessageWindowChatMemory.builder()
        .chatMemoryRepository(new InMemoryChatMemoryRepository())
        .maxMessages(20)
        .build();

MessageChatMemoryAdvisor memoryAdvisor =
        MessageChatMemoryAdvisor.builder(chatMemory).build();

ChatClient client = builder
        .defaultAdvisors(memoryAdvisor)
        .build();

// 调用时必须带 CONVERSATION_ID
client.prompt()
        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
        .user("继续刚才的库存分析")
        .call();
```

### 5.3 CONVERSATION_ID 不是可选风格

2.0 要求用 **`ChatMemory.CONVERSATION_ID`** 关联会话。Web 层用登录用户 + 会话 UUID 生成，**不要**用模型随便编的 id。

**默认策略：** 记忆 Advisor 放在工具循环 **外**（最终轮落库），避免把中间 tool 消息搞乱。主线 part-07 会强调。

---

## 6. RAG：QuestionAnswerAdvisor vs RetrievalAugmentationAdvisor

### 6.1 1.x 常见

```java
// 1.x 教程大量出现
QuestionAnswerAdvisor qaAdvisor = new QuestionAnswerAdvisor(vectorStore);
String answer = ChatClient.create(chatModel)
    .prompt()
    .advisors(qaAdvisor)
    .user("报销制度里差旅上限是多少？")
    .call()
    .content();
```

一个 Advisor 包办检索+拼接，难拆换检索器、重排、查询扩展。

### 6.2 2.0 模块化（概念示意）

```java
RetrievalAugmentationAdvisor ragAdvisor =
        RetrievalAugmentationAdvisor.builder()
                .documentRetriever(VectorStoreDocumentRetriever.builder()
                        .vectorStore(vectorStore)
                        .build())
                .build();

ChatClient client = builder
        .defaultAdvisors(ragAdvisor)
        .build();
```

`spring-ai-rag` 模块提供可组合管道：**查询变换、检索、后处理、生成** 可分别替换与测试。

### 6.3 迁移策略

1. 先让 **向量库与 embedding** 在 2.0 跑通（维度和索引可能要重建）
2. 用 `RetrievalAugmentationAdvisor` 复刻旧行为（单路检索 + 拼接）
3. 再逐步拆模块（hyde、rerank 等）——不是迁移第一天就上

---

## 7. Starter 改名对照

| 1.x 常见 artifact | 2.0 artifact |
|---|---|
| `spring-ai-openai-spring-boot-starter` | `spring-ai-starter-model-openai` |
| `spring-ai-ollama-spring-boot-starter` | `spring-ai-starter-model-ollama` |
| DeepSeek 等 | `spring-ai-starter-model-deepseek` |
| 向量存储各 starter | `spring-ai-starter-vector-store-*` 等（查 BOM） |

**pom 里全文搜索 `spring-ai-` 旧名，逐项替换。**

依赖管理仍：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>2.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

---

## 8. 配置项迁移

### 8.1 1.x 常见

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: gpt-4
```

### 8.2 2.0

结构大体保留，但注意：

- 部分默认值移到 `options` 对象
- 多模型时 `spring.ai.model.chat`
- `spring.ai.retry.*` 仍可用

DeepSeek 2.0 示例：

```yaml
spring:
  ai:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
      chat:
        model: deepseek-v4-flash
        temperature: 0.3
```

**迁移时 diff 两份 yml**，不要凭记忆改 key。

---

## 9. Jackson 3 连带影响

Boot 4 + Spring AI 2.0 默认 **Jackson 3**。若你 1.x 项目里有：

- 自定义 `ObjectMapper` Bean
- 与 Spring AI 消息序列化相关的 hack

需要按 Boot 4 迁移指南重新注册模块。**结构化输出 DTO** 上的注解包名可能从 `com.fasterxml.jackson` 变为 `tools.jackson`（以你实际依赖为准）。

症状：工具参数反序列化失败、SSE 解析异常——先怀疑 Jackson 版本，再怀疑模型。

---

## 10. 流式与 streamToolCallResponses

1.x 有人用 `streamToolCallResponses` 控制流式场景下工具事件的暴露。2.0 收敛到 **Reactive 栈 + Advisor 链** 的统一流式模型。

迁移步骤：

1. 确认有 `spring-boot-starter-webflux`
2. 用 `chatClient.prompt().user(...).stream().content()` 等 2.0 API
3. 工具循环在流式下仍由 `ToolCallingAdvisor` 驱动，不要找旧 flag

---

## 11. 完整 before/after：带工具的小 Agent

### 11.1 Before（1.x 风格，简化）

```java
@Configuration
public class LegacyConfig {

    @Bean
    ChatClient legacyClient(ChatModel chatModel, InventoryTools tools) {
        return ChatClient.builder(chatModel)
                .defaultFunctions("getInventory") // 旧 API 名，示意
                .build();
    }
}
```

（实际 1.x API 名可能略有出入，网上教程更乱。）

### 11.2 After（2.0）

```java
@Configuration
public class ModernConfig {

    @Bean
    ChatClient modernClient(ChatClient.Builder builder, InventoryTools tools) {
        return builder
                .defaultSystem("先查工具再回答，不编造库存。")
                .defaultTools(tools)
                .build();
    }
}
```

业务 `@Tool` 类 **可以几乎不动**，变的主要是装配层和 Advisor。

---

## 12. 迁移步骤建议（公司真有 1.x 项目时）

1. **分支** `upgrade/boot4-spring-ai-2`
2. 升 Boot 4.1 + JDK 21，先让非 AI 部分编译通过
3. 引入 `spring-ai-bom:2.0.0`，替换 starter 名
4. 全局搜索删除：`internalToolExecutionEnabled`、`toolNames`、`PromptChatMemoryAdvisor`
5. Service 层改为 `ChatClient`；补 `CONVERSATION_ID`
6. RAG 换 `RetrievalAugmentationAdvisor`
7. 跑 **评测集 + 集成测试**（工具调用、RAG、流式）
8. 灰度上线，对比 token 与延迟

**学习期同学：** 你不需要执行 1～8，只需要 **识别旧代码**，别抄进新项目。

---

## 13. 如何读网上旧教程：红灯词

看到以下词，默认教程基于 1.x，谨慎：

- `internalToolExecutionEnabled`
- `.toolNames(`
- `PromptChatMemoryAdvisor`
- `QuestionAnswerAdvisor` 作为唯一 RAG 方案且无 2.0 注明
- `spring-ai-openai-spring-boot-starter`（无 `starter-model`）
- Boot 3 + 「最新 Spring AI」却不写版本号
- 直接 `ChatModel.call` 做复杂 Agent 而无 Advisor 叙事

看到 **Boot 4.1 + spring-ai-bom 2.0.0 + ChatClient + ToolCallingAdvisor**，才与讲义同温。

---

## 14. 更多 1.x → 2.0 API 对照（逐条翻译）

### 14.1 ChatClient 创建方式

```java
// 1.x 常见
ChatClient client = ChatClient.create(chatModel);

// 2.0 推荐
ChatClient client = chatClientBuilder.build();
// 或
ChatClient client = ChatClient.builder(chatModel).build();
```

注入 `ChatClient.Builder` 是主线标准，便于自动配置绑定默认 `ChatModel`。

### 14.2 流式调用

```java
// 1.x
Flux<ChatResponse> flux = chatModel.stream(prompt);

// 2.0
Flux<String> flux = chatClient.prompt()
    .user("讲个笑话")
    .stream()
    .content();
```

注意 2.0 流式依赖 **webflux**；Boot 4 仍要在 pom 里显式引入。

### 14.3 结构化输出

```java
// 2.0 风格
record RiskCard(String conclusion, List<String> risks) {}

RiskCard card = chatClient.prompt()
    .user("评估供应商延期风险")
    .call()
    .entity(RiskCard.class);
```

1.x 也有类似实验 API，但名字与校验行为不一致；迁移时 **重写 DTO 解析层测试**。

### 14.4 Advisor 顺序

1.x 部分教程把 memory 和 RAG 随意 `advisors(a, b)`。2.0 要理解 **order**：

- Memory 默认在工具循环外（最终轮持久化）
- RAG 通常在组装 prompt 阶段注入上下文
- ToolCallingAdvisor 负责多轮

迁移后画一张 **Advisor 顺序图**，贴在 Wiki 上，比背 API 有用。

---

## 15. Boot 3 → 4 连带清单（与 Spring AI 同迁时）

Spring AI 2.0 不是孤立升级。同一分支里常见：

| 区域 | 检查项 |
|---|---|
| Jakarta | 早已是 jakarta.*，但第三方库是否仍 javax |
| Jackson 3 | 自定义序列化、日期格式 |
| Spring Security 7 | 若用 OAuth2 Resource Server |
| 测试 | `@SpringBootTest` 启动时间、Testcontainers 版本 |
| 配置属性 | `spring.config.import` 行为微调 |

**建议：** 先升 Boot 4 让 **非 AI 模块** 编译通过，再换 Spring AI BOM，二分法定位失败。

---

## 16. 遗留代码识别：Git 搜索命令

在旧仓库执行（概念示意）：

```bash
rg "internalToolExecutionEnabled|toolNames|PromptChatMemoryAdvisor|QuestionAnswerAdvisor" --type java
rg "spring-ai-openai-spring-boot-starter" pom.xml
rg "ChatModel" --type java -g '!*Test*'
```

命中行逐文件建 ticket，优先级：**对外接口 > 定时任务 > 内部工具**。

---

## 17. 双版本并行运行（极少数场景）

若不能一次性切流，可短期：

```java
@Bean @Qualifier("legacy")
ChatModel legacyChatModel() { /* 1.x 若仍暂留 */ return null; /* 示意 */ }

@Bean @Qualifier("modern")
ChatClient modernChatClient(ChatClient.Builder b) { return b.build(); }
```

新功能只走 `modern`；旧接口维持只读。**不要超过一个发布周期**，否则双倍 token 账单与双倍评测。

讲义学员 **不需要实现** 双轨，知道企业里可能发生即可。

---

## 18. 迁移后第一周监控指标

| 指标 | 1.x 基线对比 |
|---|---|
| 工具调用成功率 | 不得低于基线 2% |
| RAG 命中率（抽检） | 不得低于基线 |
| P95 延迟 | 允许略升，需解释 |
| 5xx 率 | 应下降或持平 |
| Jackson 反序列化异常 | 应为 0 |

---

## 19. 从旧博客复制代码的「消毒」流程

1. 看发布日期与 Spring AI 版本号  
2. 看是否 Boot 3（若是，默认 1.x）  
3. 全文搜索红灯词（见第 13 节）  
4. 只抄 **业务提示词与领域结构**，API 层对照讲义 2.0 重写  
5. 抄完跑评测集，不跑不上 PR  

---

## 20. 与 Jackson 3 相关的工具参数问题案例

症状：模型返回 tool arguments JSON，但 Java 侧 `LocalDate` 解析失败。

1.x 你可能有全局 `ObjectMapper` 模块；2.0 需确认 **Spring Boot 4 自动配置的 ObjectMapper** 是否注册 `JavaTimeModule` 等价物。

**教训：** 迁移问题不总在 Spring AI，而在 **整个 Boot 基线抬升**。

---

## 21. 课堂答疑

**问：我需要先学 1.x 历史吗？**  
答：不需要。**从零学 2.0**；本篇用于读旧资料。

**问：QuestionAnswerAdvisor 2.0 还能用吗？**  
答：可能仍存在，但讲义与官方主推 **RetrievalAugmentationAdvisor**。新项目不要新写 QA Advisor。

**问：同事说 ChatModel 更灵活，我还要用吗？**  
答：扩展点、测试可碰 `ChatModel`；业务入口仍 `ChatClient`。

**问：迁移谁牵头？**  
答：平台组升 Boot，业务组改 ChatClient 与评测，安全组看出域——**三周站会**，不要一个人闷头改。

---

## 22. 1.x 教程逐段「消毒」示例

下面是一段典型的 1.x 博客代码，老师带你们 **逐行标注**：

```java
// ❌ 1.x 博客原文（示意）
@Bean
public ChatClient chatClient(OpenAiChatModel model) {
    return ChatClient.builder(model)
        .defaultFunctions("weatherFunction", "stockFunction")
        .build();
}

// 用户调用
chatClient.prompt()
    .user("北京天气怎样")
    .functions("weatherFunction")  // 旧 API
    .call();
```

**消毒步骤：**

1. `defaultFunctions` → `defaultTools(weatherBean, stockBean)` 或 `.tools(callbacks)`。  
2. 去掉 `.functions(...)` 链式调用；工具在 builder 或 prompt 级 `.tools()` 注册。  
3. 确认 `ToolCallingAdvisor` 在链上，不要找 `internalToolExecutionEnabled`。  
4. 用 2.0 的 `@Tool` 注解方法替代「function bean 名」魔法。

消毒后：

```java
@Bean
public ChatClient chatClient(ChatClient.Builder builder, WeatherTools weather) {
    return builder.defaultTools(weather).build();
}

chatClient.prompt().user("北京天气怎样").call();
```

---

## 23. RetrievalAugmentationAdvisor 迁移故事板

**1.x 世界：** 一个 `QuestionAnswerAdvisor` 在向量库里搜完就把片段塞进 prompt，难以单测「检索是否召回正确段落」。

**2.0 世界：** 你可以拆开：

```java
DocumentRetriever retriever = VectorStoreDocumentRetriever.builder()
        .vectorStore(vectorStore)
        .topK(5)
        .build();

RetrievalAugmentationAdvisor ragAdvisor =
        RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                .build();
```

迁移时第一步 **行为等价**：topK、相似度阈值与旧版一致，对比同一问题的召回列表。  
第二步再优化：查询改写、rerank、hyde——那是优化项目，不是迁移项目。

---

## 24. 记忆迁移：chatId 字符串的坑

1.x 有人手写：

```java
advisors(new PromptChatMemoryAdvisor(memory, "user-" + userId, 10));
```

2.0 必须：

```java
.param(ChatMemory.CONVERSATION_ID, conversationId)
```

且 `conversationId` 应是 **稳定 UUID 或业务会话 ID**，不要每次请求 `UUID.randomUUID()`，否则记忆永远连不上。

迁移时搜全项目 `randomUUID` 与 `chatId`，统一改为前端传或登录会话生成。

---

## 25. Boot 4 升级中的 Spring AI 依赖冲突案例

**症状：** `mvn dependency:tree` 里同时出现 `jackson-databind` 2.x 与 3.x。

**原因：** 某个旧第三方库仍拉 Jackson 2。

**处理：** 用 `dependencyManagement` 对齐，或升级该第三方；**不要**强行 `exclude` 导致 Spring AI 启动失败。

这类问题在「只升 Spring AI 不升 Boot」时更常见——再次强调 **2.0 与 Boot 4 同迁**。

---

## 26. 给团队写的 1 页迁移公告（模板）

```text
标题：Spring AI 2.0 / Boot 4 升级说明

1. 新代码必须使用 ChatClient + @Tool，禁止 internalToolExecutionEnabled。
2. RAG 新功能使用 RetrievalAugmentationAdvisor。
3. 记忆使用 MessageChatMemoryAdvisor + CONVERSATION_ID。
4. 旧教程链接已标红，请只看 Wiki 2.0 章节。
5. 问题 Slack 频道：#spring-ai-2-migration
6. 截止日期：YYYY-MM-DD 后 main 分支不再接受 1.x API。
```

技术负责人贴到群里，比口头说三遍管用。

---

## 27. 1.x 与 2.0 思维对照：一张表背下来

| 问题 | 1.x 直觉 | 2.0 直觉 |
|---|---|---|
| 工具怎么循环 | ChatModel 里设 flag | Advisor 链 |
| 记忆放哪 | Prompt 里塞 | MessageChatMemoryAdvisor |
| RAG 怎么写 | QuestionAnswerAdvisor 一把梭 | 模块化 RetrievalAugmentationAdvisor |
| 入口类 | ChatModel | ChatClient |
| 网上教程 | 多 | 少但新 |
| 你该学哪个 | — | **2.0** |

---

## 28. 完整迁移 diff 清单（Checklist 可打印）

```text
[ ] pom: spring-ai-bom 2.0.0
[ ] pom: Boot parent 4.1.x
[ ] pom: starter 改名 *-starter-model-*
[ ] 删除 internalToolExecutionEnabled
[ ] 删除 toolNames / defaultFunctions
[ ] PromptChatMemoryAdvisor → MessageChatMemoryAdvisor
[ ] 所有记忆调用加 CONVERSATION_ID
[ ] QuestionAnswerAdvisor → RetrievalAugmentationAdvisor
[ ] Controller 注入 ChatClient.Builder
[ ] 流式测试通过（webflux 在 pom）
[ ] Jackson 3 自定义序列化检查
[ ] 评测集全绿
[ ] 生产密钥与配置分环境
```

每项勾完签字，迁移 PR 才能合并。

---

## 29. 旧版集成测试如何改

1.x 测试可能直接 mock `ChatModel.call()`。2.0 更推荐：

```java
@SpringBootTest
@AutoConfigureMockMvc
class AgentIT {
    @Autowired ChatClient.Builder builder;
    // 或 @MockBean ChatModel 若测 Advisor 边界
}
```

或 `@SpringAiTest` 等官方测试支持（以 2.0 文档为准）。  
**测试策略迁移**和 **生产代码迁移** 同样重要，否则 CI 绿但生产挂。

---

## 30. 常见编译错误与一行修复

| 编译错误 | 修复 |
|---|---|
| 找不到 `PromptChatMemoryAdvisor` | 换 `MessageChatMemoryAdvisor` |
| 找不到 `toolNames` | 换 `.tools(bean)` |
| `ChatClient.create` 不存在 | 注入 `ChatClient.Builder` |
| Jackson 包名错误 | `com.fasterxml` → `tools.jackson`（视依赖） |
| 多个 ChatModel Bean | 加 `spring.ai.model.chat` |

把表贴在迁移 Wiki 首页。

---

## 31. 读 1.x 源码维护时的建议

若公司产品有分支仍跑 1.x，维护者应该：

1. 只修 P0 安全与业务 bug，不新加 1.x 特性。  
2. 新功能只在 2.0 分支开发。  
3. 每周同步评测集到 2.0 分支，保证迁移后可对比。  

学员若不在维护组，**知道这条策略即可**，不必深入 1.x 源码。

---

## 32. 长篇对照：一个「库存 Agent」从 1.x 迁到 2.0 的完整故事

### 32.1 1.x 时代（简化还原）

```java
@Service
public class LegacyInventoryAgent {

    private final ChatModel chatModel;
    private final InventoryTools tools;

    public LegacyInventoryAgent(ChatModel chatModel, InventoryTools tools) {
        this.chatModel = chatModel;
        this.tools = tools;
    }

    public String run(String question) {
        List<Message> messages = List.of(
            new SystemMessage("你是库存助手"),
            new UserMessage(question)
        );
        ChatOptions options = ChatOptions.builder()
            .toolNames("queryAvailableQty")
            .internalToolExecutionEnabled(true)
            .build();
        return chatModel.call(new Prompt(messages, options))
            .getResult().getOutput().getText();
    }
}
```

问题：记忆难插、RAG 难插、审计难插；工具循环黑盒。

### 32.2 2.0 时代（目标形态）

```java
@Service
public class ModernInventoryAgent {

    private final ChatClient chatClient;

    public ModernInventoryAgent(ChatClient.Builder builder, InventoryTools tools) {
        this.chatClient = builder
            .defaultSystem("你是库存助手。数字必须来自工具。")
            .defaultTools(tools)
            .build();
    }

    public String run(String question, String conversationId) {
        return chatClient.prompt()
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
            .user(question)
            .call()
            .content();
    }
}
```

### 32.3 迁移 diff 摘要

- 删除 `toolNames` / `internalToolExecutionEnabled`。  
- `InventoryTools` 的 `@Tool` 方法可保留。  
- 若 1.x 有 `PromptChatMemoryAdvisor`，改为 `MessageChatMemoryAdvisor` 并传 `CONVERSATION_ID`。  
- 若 1.x RAG 用 `QuestionAnswerAdvisor`，改为 `RetrievalAugmentationAdvisor` 并对比召回。  
- 集成测试从 mock `ChatModel` 改为 `@SpringBootTest` + 真 Advisor 链或 test slice。

### 32.4 验收

同一评测集，2.0 pass rate ≥ 1.x 基线，P95 延迟可接受，即可切流。

---

## 33. Spring AI 1.x 中文社区谣言粉碎

| 谣言 | 事实 |
|---|---|
| 「2.0 完全换了框架，要重写」 | 业务 `@Tool`、领域服务大量可复用 |
| 「必须先用 1.x 打基础」 | 直接学 2.0，本篇只用于读旧文 |
| 「QuestionAnswerAdvisor 2.0 删了」 | 主推变更是 RetrievalAugmentationAdvisor |
| 「ChatModel 不能用了」 | 能用，但不是业务入口 |
| 「迁移一周搞定」 | 取决于评测集与 Boot 4 连带工作量 |

---

## 34. 与 part-03～part-05 的逐章映射作业（自学）

建议主线合格后做一张自测表：part-03 每一节 API，在 1.x 教程里对应什么旧名、2.0 新名是什么。  
做完这张表，你看旧博客的速度会从「懵」变成「扫一眼就知道过时」。

示例一行：

| part-03 节 | 2.0 | 1.x 旧名 |
|---|---|---|
| 工具注册 | `.defaultTools(bean)` | `toolNames` / `defaultFunctions` |

填满 20 行，迁移篇即毕业。

---

## 35. Advisor 链在 1.x 与 2.0 的「插槽」思维

1.x 时你想在工具循环中间加一步审计，往往要 fork `ChatModel` 或写 AOP 黑客。  
2.0 的 Advisor 是 **正式插槽**：实现 `CallAroundAdvisor` 或相关扩展点，声明 `getOrder()`，Spring 排序执行。

迁移时若 1.x 项目有「自定义拦截器」，对照是否应变成 **Advisor Bean** 而不是 Servlet Filter。  
Filter 仍管 HTTP 鉴权；**模型往返** 的审计用 Advisor 更贴切。

示例意图（伪代码）：

```java
public class AuditAdvisor implements CallAroundAdvisor {
    @Override
    public int getOrder() { return 150; } // 在 Memory 与 Tool 之间按需求调整

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest req, CallAroundAdvisorChain chain) {
        log.info("prompt tokens estimate...");
        AdvisedResponse resp = chain.nextAroundCall(req);
        log.info("model response...");
        return resp;
    }
}
```

具体接口名以 2.0 文档为准；**思想**是插槽。

---

## 36. 1.x 项目中的 `ChatMemory` 实现迁移

1.x 可能用 `InMemoryChatMemory` 直接 new。2.0 推荐：

```java
ChatMemory chatMemory = MessageWindowChatMemory.builder()
    .chatMemoryRepository(chatMemoryRepository)
    .maxMessages(30)
    .build();
```

生产从内存切 Redis/JDBC 时，**仓库接口** 变了要数据迁移或接受「发版后会话清空」。  
提前在发布公告里写「升级后会话重置」，避免用户以为记忆坏了。

---

## 37. 结构化输出在 1.x 的「提示词+json解析」迁移

1.x 常见手法：system 里写「只输出 JSON」，然后 `ObjectMapper.readValue`，失败就重试。  
2.0 应改为：

```java
MyDto dto = chatClient.prompt().user(...).call().entity(MyDto.class);
```

并启用 schema 校验。迁移时把 **旧的三次重试逻辑** 删掉，交给框架与 `validateSchema()`，减少自定义代码。

---

## 38. 培训材料：给团队的一小时分享大纲

1. 为什么升 Boot 4 + Spring AI 2.0（15 分钟）  
2. ChatClient 与 ToolCallingAdvisor 演示（20 分钟）  
3. 记忆与 CONVERSATION_ID 演示（10 分钟）  
4. RAG 新 Advisor 简介（10 分钟）  
5. Q&A + 迁移 Checklist 发放（5 分钟）

学员听完应能识别旧博客红灯词；维护组听完应能估迁移人天。

---

## 39. 1.x 依赖与 2.0 冲突的 pom 片段处理

若旧项目有：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-openai-spring-boot-starter</artifactId>
    <version>1.0.0-M1</version>
</dependency>
```

不要试图与 `spring-ai-bom:2.0.0` 共存。  
**整包升级**，用 `dependency:tree` 确认无 1.x 残留。

---

## 40. 读旧版 Spring AI 官方文档的 URL 技巧

旧文档 URL 可能带 `/1.0.x/` 路径或示例用 Boot 3。打开页面先找：

- 页面顶部版本下拉框  
- 示例 pom 的 `spring-ai.version`  
- 是否出现 `ChatClient.Builder` 还是只有 `ChatModel`

若三者指向 1.x，**只读概念，不 copy 代码**。概念如 token、RAG、工具调用与 2.0 通用；API 名不通用。

---

## 41. 迁移后的「技术债」清单（主动承认）

迁到 2.0 后仍可能欠：

- 评测集覆盖不足  
- RAG 仍是一坨 QA Advisor 行为未拆模块  
- 记忆仍全放内存  

这些是 **产品债**，不是「迁完就完」。2.0 给你插槽，插槽里要慢慢填。

---

## 42. 与 Jackson 3 并迁的单元测试注意

`@JsonTest` 或自定义 `ObjectMapper` 的测试，升级后可能全红。  
统一改为使用 `@Autowired ObjectMapper`（Boot 4 容器提供的实例），不要 `new ObjectMapper()` 与容器不一致。

---

## 43. 1.x 的 `StreamingChatModel` 记忆

若旧代码实现过 `StreamingChatModel` 接口，2.0 流式走 `ChatClient.stream()`。  
自定义流式装饰器应改为 **Advisor 或 ChatClient 自定义izer**，不要实现已移除的接口。

---

## 44. 迁移人天估算（技术细节，非日历）

影响因子：

- 代码库多少处直接 `ChatModel.call`  
- 是否深度使用 1.x RAG Advisor  
- Boot 4 连带依赖数量  
- 评测集是否已有  

小型 Agent 服务（<20 个调用点、评测集齐全）：**纯技术迁移** 往往可控。  
大型单体若处处 `ChatModel`：**先统计调用点再承诺日期**。讲义给的是检查单，不是万能人天公式。

---

## 45. 2.0 学完后回头看 1.x 的唯一价值

价值只有一个：**阅读历史 PR 和事故报告**。  
「去年为什么加了 `internalToolExecutionEnabled=false` 的黑魔法」——迁到 Advisor 后可以用正规插槽删掉，并写在迁移 PR 描述里。  
**不要** 为了「理解 2.0」去写新的 1.x 代码。

---

## 46. 收束：迁移篇是「翻译器」，不是「第二套课」

整篇扩展 04 服务于一个功能：**让你把 1.x 方言翻译成 2.0 方言**。  
主线教材是 2.0 母语教学；你没有义务先学会 1.x 方言再学母语。  
但若你在 Stack Overflow、内部 Wiki、供应商交付物里遇到 1.x，用本篇的红灯词表 + Checklist + 库存 Agent 故事，应在三十分钟内定位「这段不能抄，这段概念仍对」。

记住迁移的本质公式：

> **Boot 4 升基线 + ChatClient 统一入口 + Advisor 插槽 + CONVERSATION_ID 规范 + 模块化 RAG + 评测集守门**

六项齐全，1.x 项目就能着陆 2.0；缺评测集，着陆也会翻。

---

## 47. 附录：1.x → 2.0 关键词全文搜索列表

在旧仓库依次搜索，命中即建 ticket：

```text
internalToolExecutionEnabled
toolNames
defaultFunctions
.functions(
PromptChatMemoryAdvisor
QuestionAnswerAdvisor
ChatClient.create
spring-ai-openai-spring-boot-starter
spring-ai-ollama-spring-boot-starter
StreamingChatModel
streamToolCallResponses
SpringBeanToolCallbackResolver
```

搜完导出 Excel：文件路径、行号、负责人、是否已迁移。  
这是迁移项目经理最愿意看到的附件，比「我觉得改完了」可信十倍。

---

## 48. 附录：2.0 推荐替换对照（加长版）

| 1.x 关键词 | 2.0 替换 | 备注 |
|---|---|---|
| `ChatModel` 业务入口 | `ChatClient` | 底层仍可注入 ChatModel 做测试 |
| `toolNames("x")` | `.tools(bean)` | description 写在 @Tool |
| `internalToolExecutionEnabled` | 删除 | ToolCallingAdvisor 负责循环 |
| `PromptChatMemoryAdvisor` | `MessageChatMemoryAdvisor` | 必须 CONVERSATION_ID |
| `QuestionAnswerAdvisor` | `RetrievalAugmentationAdvisor` | 模块化 RAG |
| `ChatClient.create(model)` | `builder.build()` | 注入 Builder |
| `defaultFunctions` | `defaultTools` | 名变意不变 |
| Boot 3 parent | Boot 4.1 parent | 与 AI 2.0 同迁 |
| Jackson 2 自定义 | Jackson 3 对齐 | 查 Boot 迁移指南 |
| 1.x spring-ai BOM | 2.0.0 BOM | 勿混版本 |

打印加长版贴墙，代码评审时指给同事看，减少口头争论。

---

## 49. 模拟面试：同事问你「我们要不要升 2.0」

标准答法框架（三分钟）：

1. **基线**：Boot 4 与 Spring AI 2.0 已 GA，1.x 不再适合新功能。  
2. **收益**：Advisor 插槽、ToolCallingAdvisor、模块化 RAG、MCP 同代 SDK。  
3. **成本**：pom/yml/API 迁移 + 评测集回归 + Boot 4 连带。  
4. **风险**：无评测集则禁止切流。  
5. **建议**：新功能只写 2.0；旧服务排期迁移，设截止日。

背下框架，比背 API 列表更像技术负责人。

---

## 50. 1.x 项目中「自定义 ChatModel 装饰器」迁移

若有人包装 `ChatModel` 做日志、重试、计费：

```java
// 1.x 示意
public class LoggingChatModel implements ChatModel {
    private final ChatModel delegate;
    // delegate.call / stream
}
```

2.0 优先改为 **Advisor** 或 Micrometer Observation，而不是继续包装 ChatModel。  
装饰器迁移往往是 hidden workload，迁移估算时要单独数这类类文件。

---

## 51. 与 part-05 QuestionAnswerAdvisor 教程的对照作业

网上 RAG 教程 90% 从 `QuestionAnswerAdvisor` 讲起。请自学：

1. 找一篇 1.x 教程，标出 QA Advisor 段落。  
2. 用 2.0 `RetrievalAugmentationAdvisor` 写等价最小 demo。  
3. 同一问题对比召回列表是否一致。  

做完这份作业，part-05 与扩展 04 同时毕业。

---

## 52. 给讲义学员的明确声明

**你不需要在主线学习过程中阅读扩展 04。**  
只有当你：维护 1.x 遗留、阅读旧博客踩坑、或帮同事做升级评估时，再打开本篇。  
主线考试与验收 **不考** 1.x API。考的是 2.0 的 `ChatClient`、Advisor、`CONVERSATION_ID`、模块化 RAG。  
扩展 04 是翻译器，不是第二套必修课——这一点请老师口吻记牢，避免无谓焦虑。

---

## 53. 迁移 PR 描述模板（可直接贴 GitLab/GitHub）

```markdown
## Spring AI 2.0 迁移

### 范围
- Boot 3.x → 4.1.x
- spring-ai-bom 1.x → 2.0.0
- ChatModel 入口 → ChatClient
- 工具：toolNames → .tools()
- 记忆：PromptChatMemoryAdvisor → MessageChatMemoryAdvisor + CONVERSATION_ID
- RAG：QuestionAnswerAdvisor → RetrievalAugmentationAdvisor

### 评测
- [ ] eval/ 全绿，附报告链接
- [ ] 流式接口冒烟
- [ ] 工具调用 IT 通过

### 回滚
- 保留上一版本 Docker 镜像 tag：...
```

好的 PR 描述让 reviewer 十分钟内能判「能不能合」，而不是通读两千行 diff。

---

## 54. 1.x 与 2.0 并行分支策略（仅维护组）

```text
main          → 2.0 only，新功能
release/1.x   → 仅 hotfix，设 EOL 日期
feature/*     → 从 main 拉出，禁止从 1.x 拉出后合 main 不带迁移
```

学员仓库只有 main（2.0）即可；此节给将来进厂的同学预备。

---

## 55. 扩展 04 与扩展 01 的阅读顺序

建议：**先 04 再 01**。  
04 让你不被旧博客带偏；01 让你在 2.0 世界里安全换 GPT/兼容云。  
若先读 01，有人会在第三周就想换模型；先读 04 会强化「我学的是 2.0，换模型是后话」。

---

## 56. 最后一表：你在旧代码里看到它，就查本章哪一节

| 旧代码片段 | 本章章节 |
|---|---|
| `internalToolExecutionEnabled` | §2、§4 |
| `toolNames` | §4、§14 |
| `PromptChatMemoryAdvisor` | §5、§24 |
| `QuestionAnswerAdvisor` | §6、§23 |
| `spring-ai-openai-spring-boot-starter` | §7 |
| `ChatClient.create` | §3、§14 |
| 多个 ChatModel Bean | §8、扩展 03 |

扩展 04 是索引型讲义；收藏本节，搜代码时省时间。

---

## 57. 迁移完成后的庆祝标准（不是请客吃饭）

庆祝标准只有一条：**评测集与 1.x 基线相比无显著退化，且团队能在白板上画出 2.0 Advisor 链**。  
若只能喝酒不能画图，说明迁移只完成了一半；图能画出来，喝酒才有意义。

---

## 58. 与主线各 part 的「若你看到 1.x 教程」跳转表

| 你在学 | 旧教程可能出现 | 请读扩展 04 |
|---|---|---|
| part-03 | 旧 starter 名、ChatModel 入口 | §3、§7、§8 |
| part-04 | toolNames、internalToolExecutionEnabled | §2、§4、§11 |
| part-05 | QuestionAnswerAdvisor 主路径 | §6、§23、§51 |
| part-07 | PromptChatMemoryAdvisor | §5、§24、§36 |
| part-09 | Boot 3 pom | §15、§39 |

这张跳转表可打印贴在显示器边，学习主线时遇到旧文，三十秒内定位扩展 04 章节，不必通读全篇。

扩展 04 全文收束：**你是 2.0 原生一代，1.x 是你要翻译的外语，不是你要掌握的母语。** 掌握 2.0 后，外语读得越快，踩坑越少；本末倒置先学外语，会拖慢母语进度。请把本篇与 `03-技术选型说明书.md` §3.2 对照阅读，形成完整版本观。主线合格后，本篇即毕业。不必再搜 1.x 入门课。专注 Spring AI 2.0 即可。祝迁移顺利。加油。

---

## 59. 什么时候才应该用 · 和主线如何衔接

### 什么时候才应该用

- 你维护 **公司遗留的 Spring AI 1.x 代码**，需要制定升级方案
- 你从旧博客 copy 代码 **编译失败**，需要对照翻译
- 你参与 **Boot 3 → 4 平台升级**，Spring AI 是子项目之一
- 你要 **评审供应商示例代码** 是否过时

### 不要在什么时候用

- 零基础同学 **不必先学 1.x 再学 2.0**——直接 2.0
- 不要把迁移篇当 excuse 推迟 part-04 工具练习

### 和主线如何衔接

| 主线章节 | 与 1.x 的差异焦点 |
|---|---|
| part-03 BOM 与自动配置 | starter 新名、`ChatClient.Builder` |
| part-04 工具循环 | 理解为何 2.0 把循环放到 Advisor |
| part-05 RAG | `RetrievalAugmentationAdvisor` 替代 QA Advisor |
| part-07 记忆与安全 | `MessageChatMemoryAdvisor` + `CONVERSATION_ID` |
| 扩展 01～03 | 2.0 多模型配置，旧教程基本没有 |

你按讲义学完主线，已站在 2.0 一侧。本篇是 **面向过去的望远镜**——看清旧教程的坑，而不是让你回去怀旧。

---

*扩展篇 04 · 完。下一篇：MCP 深度与 Agent Skills。*

# 06 · 工具一多时的 Tool Search

> 三个库存工具用不着这一节。  
> 但你要在 **第 6 周**建立概念：ERP 一挂就是几十个接口，不能全塞给模型。

---

## 1. 问题：上下文爆炸

默认 `ToolCallingAdvisor` 每次请求把**全部** Tool Definition 发给模型。

工具少（<10）：没问题。  
工具多（30+）或多 MCP 服务器聚合（上百）：

- Prompt 变长 → **token 费**涨
- 相似工具名干扰 → **选错工具**
- 模型「看花眼」→ 胡调或不调

---

## 2. 解法：ToolSearchToolCallingAdvisor

Spring AI 2.0 提供 **按需披露**（Progressive Tool Disclosure）：

1. 会话开始时索引全部工具（regex / lucene / vector）
2. 先只给模型一个内置 `toolSearchTool`
3. 模型用自然语言搜「和库存相关的工具」
4. 只把**搜到的**工具 definition 注入后续轮次

它是 `ToolCallingAdvisor` 的**子类**，替换默认那一个 `ToolAdvisor`——仍然满足「恰好一个」。

---

## 3. 怎么启用（知道配置即可，本周不强制实现）

### 3.1 依赖

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-tool-search-advisor</artifactId>
</dependency>
```

### 3.2 配置

```yaml
spring:
  ai:
    chat:
      client:
        tool-search-advisor:
          enabled: true
          tool-index-type: regex   # 可选 regex（默认）、lucene、vector
          # session-id-key-name: mySessionId  # 默认用 ChatMemory.CONVERSATION_ID
```

启用后，自动配置用 `ToolSearchToolCallingAdvisor` **替换**默认 `ToolCallingAdvisor`。

### 3.3 索引策略怎么选

| 类型 | 特点 | 适用 |
|---|---|---|
| **regex** | 轻量、无额外依赖 | 工具名规范、几十个个数 |
| **lucene** | 关键词检索强 | 中英文描述较长 |
| **vector** | 语义相似 | 工具描述多样、说法不一 |

学习期了解即可；真上 ERP 时先用 **regex**，稳定后再换 vector。

---

## 4. 必须有 session id

Tool Search 按**会话**缓存「已披露的工具子集」。  
每次请求要带会话标识，默认 key 是 `ChatMemory.CONVERSATION_ID`：

```java
client.prompt()
        .user("查一下采购超期订单")
        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "session-20260909-001"))
        .tools(allErpToolsProvider)
        .call();
```

没有 session id，索引状态无法关联，行为会乱。

---

## 5. 和库存 Agent 的关系

本章 demo 只有 3 个工具：**不要加 Tool Search**。  
记住触发条件即可：

- 单域工具 > 15～20
- 或多 MCP 源合并
- 或 token 账单里 tool schema 占比过高

---

## 6. 参数增强：AugmentedToolCallbackProvider（简介）

工具一多时，你还可能想让模型在调工具前填「内心独白」字段，便于审计：

```java
public record AgentThinking(
        @ToolParam(description = "调用此工具前的推理步骤", required = true)
        String innerThought,
        @ToolParam(description = "置信度 low/medium/high", required = false)
        String confidence
) {}

AugmentedToolCallbackProvider<AgentThinking> provider =
        AugmentedToolCallbackProvider.<AgentThinking>builder()
                .toolObject(new InventoryTools(store, audit))
                .argumentType(AgentThinking.class)
                .argumentConsumer(event -> log.info("推理: {}", event.arguments().innerThought()))
                .removeExtraArgumentsAfterProcessing(true)
                .build();

ChatClient.builder(chatModel).defaultTools(provider).build();
```

模型 schema 里多了 `innerThought` 等字段；**真正 Java 工具方法**仍只收到业务参数。  
本周 **了解**；主线综合项目可按需采用。

---

## 7. 口试

1. Tool Search 替换的是链上哪个组件？
2. 为什么必须传 `CONVERSATION_ID`？
3. 三个库存工具要不要开 Tool Search？为什么？

---

## 8. 下一步

`07-MCP是什么以及本周了解到哪.md`

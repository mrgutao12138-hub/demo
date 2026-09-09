# 02 · ChatClient 从入门到熟练

> **本节目标：** 把 `ChatClient` 的 fluent API 走通一遍——从 `prompt()` 到 `call()` / `stream()`，以及五种返回值。  
> **预计用时：** 2～2.5 小时（建议边读边敲）。

---

## 1. API 总览（先有一张地图）

```
ChatClient
  └── prompt() / prompt(String) / prompt(Prompt)
        ├── system() / user() / messages()
        ├── options() / tools() / advisors()
        ├── templateRenderer()
        ├── call()
        │     ├── content()
        │     ├── chatResponse()
        │     ├── chatClientResponse()
        │     ├── entity(...)
        │     └── responseEntity(...)
        └── stream()
              ├── content()          → Flux<String>
              ├── chatResponse()     → Flux<ChatResponse>
              └── chatClientResponse() → Flux<ChatClientResponse>
```

**关键细节：** `call()` 本身**不会**触发模型调用；真正发请求是在你调用 `content()`、`chatResponse()`、`entity()` 等终端方法时。

---

## 2. 决策助手 System Prompt（可直接用）

把下面常量放进 `DecisionSystemPrompt.java`，后续各节复用：

```java
package com.example.agent.prompt;

public final class DecisionSystemPrompt {

    private DecisionSystemPrompt() {}

    /**
     * 管理层辅助决策助手 — 可直接作为 defaultSystem 或运行时 system() 使用。
     * 占位符 {tenantName}、{userRole}、{dataAsOf} 在运行时通过 .param() 注入。
     */
    public static final String TEXT = """
            你是「{tenantName}」企业的管理层辅助决策助手。当前对话用户角色：{userRole}。
            数据口径截止时间：{dataAsOf}。你必须用简体中文回答。

            ## 你的职责
            1. 帮助管理层理解库存、采购、订单履约、设备运行等业务状况。
            2. 在已有数据或工具返回结果的基础上，给出对比、归因、风险提示与可执行建议。
            3. 把复杂问题拆成：现状 → 变化 → 原因假设 → 建议动作 → 需进一步确认的信息。

            ## 硬性红线（违反任何一条都是严重错误）
            1. **禁止编造数字、日期、客户名、供应商名、SKU、工单号。** 没有数据就说「当前上下文无此数据」。
            2. **禁止假装已查询数据库。** 未收到工具结果前，不得使用「经查询」「数据显示」等措辞。
            3. **禁止执行或承诺任何写操作**（下单、改库存、关闭工单、调价）。只能建议「需人工审批」。
            4. **禁止泄露其他租户、其他部门无权查看的信息。** 用户角色为 {userRole}，超出权限一律拒绝并说明原因。
            5. **禁止把推测当成事实。** 推测必须标注「可能」「待验证」。
            6. 涉及资金、合规、人事处分等敏感决策，必须提示「建议由相关负责人确认」。

            ## 回答结构（默认遵循，用户另有要求时从其要求）
            - **结论**：一两句话直接回答。
            - **依据**：列出关键数字或事实，标明来源（工具名 / 文档名 / 用户提供）。
            - **风险与不确定性**：缺什么数据、哪些假设未验证。
            - **建议动作**：按优先级 1～3 条，可执行、可指派。
            - **如需追问**：向用户确认 1～2 个最关键的问题。

            ## 数字与单位
            - 金额默认人民币元，数量带单位（件、箱、台、小时）。
            - 百分比保留一位小数；大数用千分位（例如 1,234,567）。
            - 时间一律带时区或明确「北京时间」。

            ## 语气
            - 专业、克制、面向管理层汇报，避免口语化和夸张修辞。
            - 不确定时宁可少说，也不要用模糊话术掩盖无知。

            ## 工具使用原则（part-04 会接真工具，此处先立规矩）
            - 需要实时业务数字时，应调用相应只读工具，不得凭记忆回答。
            - 一次对话优先调用最少必要工具，避免重复查询。
            - 工具返回为空或异常时，如实告知用户，并给出可人工排查的路径。

            请始终遵守以上规则。用户问题如下：
            """;
}
```

---

## 3. 三种 prompt 入口

```java
package com.example.agent.service;

import com.example.agent.prompt.DecisionSystemPrompt;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

@Service
public class PromptEntryDemoService {

    private final ChatClient chatClient;

    public PromptEntryDemoService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /** 方式 1：prompt() 空参，逐步拼装 */
    public String byFluentBuilder(String question, String tenant, String role, String asOf) {
        return chatClient.prompt()
                .system(s -> s.text(DecisionSystemPrompt.TEXT)
                        .param("tenantName", tenant)
                        .param("userRole", role)
                        .param("dataAsOf", asOf))
                .user(question)
                .call()
                .content();
    }

    /** 方式 2：prompt(String) 快捷写法，等价于只有一条 user 消息 */
    public String byPromptString(String question) {
        return chatClient.prompt(question).call().content();
    }

    /** 方式 3：prompt(Prompt) 传入已构建的 Prompt 对象 */
    public String byPromptObject(String question) {
        Prompt prompt = new Prompt(new UserMessage(question));
        return chatClient.prompt(prompt).call().content();
    }
}
```

日常业务用方式 1；简单试验用方式 2；与旧代码或测试集成时用方式 3。

---

## 4. call() 五种返回值

### 4.1 content() — 只要字符串

```java
String answer = chatClient.prompt()
        .user("华东仓 SKU-88321 当前可用库存口径是什么？")
        .call()
        .content();
```

### 4.2 chatResponse() — 要 Token 用量等元数据

```java
import org.springframework.ai.chat.model.ChatResponse;

ChatResponse chatResponse = chatClient.prompt()
        .user("用三句话说明库存周转率")
        .call()
        .chatResponse();

String text = chatResponse.getResult().getOutput().getText();
var usage = chatResponse.getMetadata().getUsage(); // 若提供商返回了 usage
```

### 4.3 chatClientResponse() — 要 Advisor 执行上下文

```java
import org.springframework.ai.chat.client.ChatClientResponse;

ChatClientResponse clientResponse = chatClient.prompt()
        .user("本月采购超期供应商有哪些风险信号？")
        .call()
        .chatClientResponse();

ChatResponse inner = clientResponse.chatResponse();
// clientResponse.context() 可拿到 Advisor 写入的上下文（RAG 文档等，part-05 会用到）
```

### 4.4 entity() — 结构化输出

```java
import java.util.List;

public record DecisionSummary(
        String conclusion,
        List<String> keyFacts,
        List<String> risks,
        List<String> recommendedActions
) {}

DecisionSummary summary = chatClient.prompt()
        .system("你是决策摘要生成器，只输出 JSON，字段：conclusion, keyFacts, risks, recommendedActions")
        .user("假设工具已返回：华东仓 SKU-88321 可用 120 件，安全库存 200 件。请生成摘要。")
        .call()
        .entity(DecisionSummary.class);
```

可靠性开关（2.0 新增）：

```java
DecisionSummary reliable = chatClient.prompt()
        .user("根据上文生成决策摘要")
        .call()
        .entity(DecisionSummary.class, spec -> spec
                .useProviderStructuredOutput()  // 走提供商原生结构化约束
                .validateSchema());             // 校验失败自动重试
```

【注意】`validateSchema()` 激活时**不支持流式**。

### 4.5 responseEntity() — 同时要 ChatResponse 和实体

```java
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.client.ChatClientResponse;

var pair = chatClient.prompt()
        .user("生成一条决策摘要 JSON")
        .call()
        .responseEntity(DecisionSummary.class);

ChatResponse meta = pair.getResponse();
DecisionSummary body = pair.getEntity();
```

---

## 5. stream() 流式输出

### 5.1 Service 层

```java
package com.example.agent.service;

import com.example.agent.prompt.DecisionSystemPrompt;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class StreamingChatService {

    private final ChatClient chatClient;

    public StreamingChatService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    public Flux<String> streamContent(String question, String tenant, String role, String asOf) {
        return chatClient.prompt()
                .system(s -> s.text(DecisionSystemPrompt.TEXT)
                        .param("tenantName", tenant)
                        .param("userRole", role)
                        .param("dataAsOf", asOf))
                .user(question)
                .stream()
                .content();
    }

    public Flux<ChatResponse> streamWithMetadata(String question) {
        return chatClient.prompt()
                .user(question)
                .stream()
                .chatResponse();
    }
}
```

### 5.2 Controller 层 SSE

```java
package com.example.agent.web;

import com.example.agent.service.StreamingChatService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class StreamChatController {

    private final StreamingChatService streamingChatService;

    public StreamChatController(StreamingChatService streamingChatService) {
        this.streamingChatService = streamingChatService;
    }

    @GetMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(
            @RequestParam String message,
            @RequestParam(defaultValue = "示范租户") String tenant,
            @RequestParam(defaultValue = "运营总监") String role,
            @RequestParam(defaultValue = "2026-09-09 08:00 北京时间") String dataAsOf) {
        return streamingChatService.streamContent(message, tenant, role, dataAsOf);
    }
}
```

浏览器或 `curl -N` 可看到逐字输出。

---

## 6. default* 与运行时覆盖

在 `@Bean` 上设默认，运行时仍可覆盖：

```java
package com.example.agent.config;

import com.example.agent.prompt.DecisionSystemPrompt;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DecisionChatClientConfig {

    @Bean
    ChatClient decisionChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem(DecisionSystemPrompt.TEXT)
                .defaultOptions(DeepSeekChatOptions.builder()
                        .model("deepseek-v4-pro")
                        .temperature(0.2)
                        .enableThinking()
                        .reasoningEffortHigh()
                        .build())
                .build();
    }
}
```

运行时只改 user、并注入 system 参数：

```java
String reply = decisionChatClient.prompt()
        .system(s -> s
                .param("tenantName", "华东事业部")
                .param("userRole", "供应链总监")
                .param("dataAsOf", "2026-09-09 08:00"))
        .user("SKU-88321 缺货风险如何？")
        .call()
        .content();
```

Builder 级还支持：`defaultUser`、`defaultTools`、`defaultAdvisors`、`defaultTemplateRenderer`。详见第 04、06 节。

---

## 7. mutate()：复制配置

```java
ChatClient strictClient = decisionChatClient.mutate()
        .defaultOptions(DeepSeekChatOptions.builder().temperature(0.0).build())
        .build();
```

适合在同一请求链上派生「更保守」的子客户端，而不重复写全部 default。

---

## 8. 完整 Controller 示例（同步 + 结构化）

```java
package com.example.agent.web;

import com.example.agent.service.DecisionSummary;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/decision")
public class DecisionController {

    private final ChatClient decisionChatClient;

    public DecisionController(ChatClient decisionChatClient) {
        this.decisionChatClient = decisionChatClient;
    }

    public record DecisionRequest(String tenant, String role, String dataAsOf, String question) {}

    @PostMapping("/ask")
    public String ask(@RequestBody DecisionRequest req) {
        return decisionChatClient.prompt()
                .system(s -> s
                        .param("tenantName", req.tenant())
                        .param("userRole", req.role())
                        .param("dataAsOf", req.dataAsOf()))
                .user(req.question())
                .call()
                .content();
    }

    @PostMapping("/summary")
    public DecisionSummary summary(@RequestBody DecisionRequest req) {
        return decisionChatClient.prompt()
                .system(s -> s
                        .param("tenantName", req.tenant())
                        .param("userRole", req.role())
                        .param("dataAsOf", req.dataAsOf()))
                .user(req.question() + "\n\n请按 JSON 输出决策摘要结构。")
                .call()
                .entity(DecisionSummary.class, spec -> spec.validateSchema());
    }
}
```

---

## 9. 本节易错点

| 易错 | 正确 |
|---|---|
| 以为 `.call()` 就发了请求 | 必须 `.content()` 等终端方法 |
| 结构化输出用字符串自己 `parse` | 优先 `.entity(MyRecord.class)` |
| 流式接口没引 webflux | 补 `spring-boot-starter-webflux` |
| system prompt 每次手写一遍 | `defaultSystem` + 运行时 `.param()` |

---

## 10. 小结

- `ChatClient` 是业务一等 API；fluent 链从 `prompt()` 开始。
- `call()` 取同步结果五种形态；`stream()` 取 `Flux`。
- 决策助手 system prompt 用模板占位符，运行时注入租户与角色。
- 结构化用 `entity()`，要稳就加 `validateSchema()` / `useProviderStructuredOutput()`。

下一篇：`03-ChatModel底层与何时才用.md`——你会知道什么时候才该下沉一层。

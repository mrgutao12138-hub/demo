# 03 · ChatModel 底层与何时才用

> **本节目标：** 理解 `ChatModel` / `DeepSeekChatModel` 在架构中的位置，知道业务代码何时不该碰它，以及少数「必须下沉」的场景。  
> **预计用时：** 1～1.5 小时。

---

## 1. 分层关系（一张图记牢）

```
┌─────────────────────────────────────────┐
│  Controller / Service 业务层             │
│  注入：ChatClient 或 ChatClient.Builder  │
└──────────────────┬──────────────────────┘
                   │ 内部调用
┌──────────────────▼──────────────────────┐
│  ChatClient + Advisor 链                 │
│  prompt 组装、工具循环、记忆、观测        │
└──────────────────┬──────────────────────┘
                   │ 最终仍调用
┌──────────────────▼──────────────────────┐
│  ChatModel（接口）                       │
│  DeepSeekChatModel（DeepSeek 实现）      │
│  call(Prompt) / stream(Prompt)           │
└──────────────────┬──────────────────────┘
                   │ HTTP
┌──────────────────▼──────────────────────┐
│  DeepSeek 云端 API                       │
└─────────────────────────────────────────┘
```

**口诀：** 业务对着 `ChatClient` 说话；框架对着 `ChatModel` 说话。

---

## 2. ChatModel 接口做什么

`ChatModel` 只有两个核心职责：

1. `ChatResponse call(Prompt prompt)` — 同步一次完整往返；
2. `Flux<ChatResponse> stream(Prompt prompt)` — 流式往返。

它**不管** system 模板渲染、Advisor 链、工具循环、结构化 entity 映射——那些都在 `ChatClient` 层。

```java
package com.example.agent.lowlevel;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import java.util.List;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.stereotype.Component;

@Component
public class RawChatModelDemo {

    private final ChatModel chatModel;

    public RawChatModelDemo(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public String askDirectly(String question) {
        Prompt prompt = new Prompt(
                List.of(
                        new SystemMessage("你是简洁的中文助手。"),
                        new UserMessage(question)
                ),
                DeepSeekChatOptions.builder()
                        .temperature(0.3)
                        .build()
        );
        ChatResponse response = chatModel.call(prompt);
        return response.getResult().getOutput().getText();
    }
}
```

上面能跑，但**不要**在 Controller 里这么写——你丢掉了 Advisor、观测、结构化便利 API。

---

## 3. DeepSeekChatModel 特有能力

自动配置注册的单例 `DeepSeekChatModel` 持有：

- `DeepSeekApi` — HTTP 客户端；
- `DeepSeekChatOptions` — 默认模型参数；
- `RetryTemplate` — 由 `spring.ai.retry.*` 配置；
- `ToolCallingManager` — 供底层工具相关能力（业务仍走 ChatClient + Advisor）。

### 3.1 读取推理内容（reasoning）

DeepSeek 思考模式下，助手消息类型是 `DeepSeekAssistantMessage`，可分别取推理链与最终文本：

```java
package com.example.agent.lowlevel;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.stereotype.Service;

@Service
public class ReasoningExtractService {

    private final ChatClient chatClient;

    public ReasoningExtractService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    public record ReasoningResult(String reasoningContent, String finalAnswer) {}

    public ReasoningResult extract(String question) {
        ChatResponse response = chatClient.prompt()
                .options(DeepSeekChatOptions.builder()
                        .model("deepseek-v4-pro")
                        .enableThinking()
                        .reasoningEffortHigh()
                        .build())
                .user(question)
                .call()
                .chatResponse();

        var output = response.getResult().getOutput();
        if (output instanceof DeepSeekAssistantMessage deepSeekMsg) {
            return new ReasoningResult(
                    deepSeekMsg.getReasoningContent(),
                    deepSeekMsg.getText()
            );
        }
        return new ReasoningResult(null, output.getText());
    }
}
```

【生产建议】推理内容默认不要展示给终端用户，可记入审计日志供复盘。管理层只看 `finalAnswer`。

### 3.2 直接用 ChatModel 读推理（测试或批处理场景）

```java
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.deepseek.DeepSeekChatOptions;

public class ChatModelReasoningDemo {

    private final ChatModel chatModel;

    public ChatModelReasoningDemo(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public void printReasoning(String question) {
        Prompt prompt = new Prompt(
                new UserMessage(question),
                DeepSeekChatOptions.builder()
                        .enableThinking()
                        .build()
        );
        ChatResponse response = chatModel.call(prompt);
        DeepSeekAssistantMessage msg =
                (DeepSeekAssistantMessage) response.getResult().getOutput();
        System.out.println("推理: " + msg.getReasoningContent());
        System.out.println("答案: " + msg.getText());
    }
}
```

---

## 4. 什么时候才该用 ChatModel

| 场景 | 用 ChatModel | 用 ChatClient |
|---|---|---|
| REST 接口返回对话结果 | ❌ | ✅ |
| 需要 Advisor / 工具 / 记忆 | ❌ | ✅ |
| 结构化 `entity()` | ❌ | ✅ |
| 单元测试 mock 模型行为 | ✅（mock 接口） | 可选 |
| 批量离线跑 Prompt 列表 | ✅ | 可用但多余 |
| 框架扩展：自定义 ChatModel 实现 | ✅ | — |
| 读取 DeepSeek 推理字段 | 两者皆可 | **推荐** ChatClient + chatResponse |

---

## 5. 测试里 mock ChatModel

```java
package com.example.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatClientWithMockModelTest {

    @TestConfiguration
    static class MockConfig {
        @Bean
        @Primary
        ChatModel mockChatModel() {
            ChatModel mock = mock(ChatModel.class);
            AssistantMessage assistant = new AssistantMessage("模拟回复：库存正常。");
            ChatResponse fakeResponse = new ChatResponse(List.of(new Generation(assistant)));
            when(mock.call(any(Prompt.class))).thenReturn(fakeResponse);
            return mock;
        }
    }

    @Test
    void shouldUseMock(ChatClient.Builder builder) {
        ChatClient client = builder.build();
        String content = client.prompt().user("库存如何？").call().content();
        assert content.equals("模拟回复：库存正常。");
    }
}
```

测业务逻辑时 mock `ChatModel` 比 mock HTTP 省事得多。

---

## 6. 2.0 弃用路径：不要在 ChatModel 里跑工具循环

1.x 常见写法是让 `ChatModel` 内部执行工具（`internalToolExecutionEnabled`）。**2.0 已弃用，3.0 将移除。**

正确做法：

```
用户请求 → ChatClient → Advisor 链 → ToolCallingAdvisor（order ≈ HIGHEST_PRECEDENCE + 300）→ 循环
```

业务代码**不要**配置 `internalToolExecutionEnabled`，**不要**用 `.toolNames()`。工具注册用 `.tools(...)`，循环由 `ToolCallingAdvisor` 完成（part-04 详讲）。

---

## 7. ChatModel 与 ChatClient 对照示例

同一问题，两种写法：

```java
// ❌ 业务层不推荐
String a = chatModel.call(new Prompt(new UserMessage("你好"))).getResult().getOutput().getText();

// ✅ 业务层推荐
String b = chatClient.prompt().user("你好").call().content();
```

两者最终都调到 `DeepSeekChatModel`，但 B 保留了观测、Advisor、模板、结构化全链路。

---

## 8. 批处理脚本式用法（少数合理场景）

```java
package com.example.agent.batch;

import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class NightlySummaryBatch {

    private final ChatModel chatModel;

    public NightlySummaryBatch(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public List<String> summarizeLines(List<String> lines) {
        return lines.stream()
                .map(line -> {
                    Prompt p = new Prompt(
                            new UserMessage("用一句话摘要：" + line),
                            DeepSeekChatOptions.builder().temperature(0.0).build()
                    );
                    return chatModel.call(p).getResult().getOutput().getText();
                })
                .collect(Collectors.toList());
    }
}
```

定时任务、ETL 旁路摘要可以用 `ChatModel` 直连，省掉 Advisor 开销。但**仍建议**加观测与限流。

---

## 9. 本节小结

1. `ChatModel` 是底层同步/流式调用接口；`DeepSeekChatModel` 是 DeepSeek 实现。
2. 业务代码默认只用 `ChatClient`。
3. 读 `reasoningContent` 时把 `output` 转成 `DeepSeekAssistantMessage`。
4. 工具循环在 Advisor 层，不在 ChatModel 内部。
5. 测试与批处理是 `ChatModel` 的合理用武之地。

下一篇：`04-消息Prompt与模板.md`。

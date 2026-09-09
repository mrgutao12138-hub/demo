# 05 · Options、温度、思考模式与重试

> **本节目标：** 搞懂 `DeepSeekChatOptions`、yaml 配置项、运行时覆盖、thinking / reasoning-effort，以及 `spring.ai.retry.*`。  
> **预计用时：** 1.5～2 小时。

---

## 1. 配置分两层

| 层次 | 方式 | 何时用 |
|---|---|---|
| 启动默认 | `application.yaml` 的 `spring.ai.deepseek.chat.*` | 全站默认模型、温度 |
| 单次请求 | `ChatClient` 的 `.options(...)` 或 `.defaultOptions(...)` | 按场景微调 |

2.0 里 `.options()` / `.defaultOptions()` 接受 **`ChatOptions.Builder`**（如 `DeepSeekChatOptions.builder()`），不必先 `.build()` 再传入——类型系统在编译期约束。

---

## 2. application.yaml 完整参考（DeepSeek 2.0）

```yaml
spring:
  ai:
    model:
      chat: deepseek          # 多模型共存时指定默认 ChatModel
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
      chat:
        model: deepseek-v4-flash    # 可选：deepseek-v4-pro / deepseek-chat / deepseek-reasoner
        temperature: 0.3
        max-tokens: 4096
        thinking:
          type: enabled             # enabled | disabled
        reasoning-effort: high      # high | max
    retry:
      max-attempts: 10
      backoff:
        initial-interval: 2s
        multiplier: 5
        max-interval: 3m
      on-client-errors: false
      # on-http-codes: []
      # exclude-on-http-codes: []
```

### 模型怎么选（讲义主线）

| 模型名 | 适用 |
|---|---|
| `deepseek-v4-flash` | 日常问答、意图粗分、简单摘要（快、省） |
| `deepseek-v4-pro` | 复杂归因、对比、决策建议（强、贵） |
| `deepseek-chat` | 兼容别名，新项建议用 v4 系列 |
| `deepseek-reasoner` | 强推理场景；讲义决策 Agent 用 pro + thinking 即可 |

---

## 3. 运行时 DeepSeekChatOptions.builder()

```java
package com.example.agent.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.stereotype.Service;

@Service
public class OptionsDemoService {

    private final ChatClient chatClient;

    public OptionsDemoService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /** 日常：低温 flash */
    public String quickAnswer(String question) {
        return chatClient.prompt()
                .options(DeepSeekChatOptions.builder()
                        .model("deepseek-v4-flash")
                        .temperature(0.2)
                        .maxTokens(1024)
                        .disableThinking()
                        .build())
                .user(question)
                .call()
                .content();
    }

    /** 决策：pro + thinking + high effort */
    public String deepDecision(String question) {
        return chatClient.prompt()
                .options(DeepSeekChatOptions.builder()
                        .model("deepseek-v4-pro")
                        .temperature(0.2)
                        .maxTokens(4096)
                        .enableThinking()
                        .reasoningEffortHigh()
                        .build())
                .user(question)
                .call()
                .content();
    }

    /** Agent 级复杂编排：max effort */
    public String agentStyle(String question) {
        return chatClient.prompt()
                .options(DeepSeekChatOptions.builder()
                        .model("deepseek-v4-pro")
                        .enableThinking()
                        .reasoningEffortMax()
                        .build())
                .user(question)
                .call()
                .content();
    }
}
```

Builder 便捷方法对照 yaml：

| Builder 方法 | yaml 等价 |
|---|---|
| `.enableThinking()` | `thinking.type: enabled` |
| `.disableThinking()` | `thinking.type: disabled` |
| `.reasoningEffortHigh()` | `reasoning-effort: high` |
| `.reasoningEffortMax()` | `reasoning-effort: max` |

---

## 4. 温度与思考模式的工程经验

### 4.1 temperature

| 场景 | 建议温度 |
|---|---|
| 查数、摘要、分类 | 0.0 ～ 0.3 |
| 决策分析、归因 | 0.1 ～ 0.3 |
| 写邮件、润色 | 0.5 ～ 0.7 |
| 创意文案 | 0.7+（决策系统少用） |

**不要全局 1.0。** 管理层看的是稳定与可复核，不是文采。

### 4.2 thinking 模式注意

开启 thinking 时，DeepSeek API 可能**静默忽略**与 thinking 冲突的参数（如 temperature、top_p 等）。若你显式设置了 temperature，框架可能打警告——这不是 bug，是提供商语义。

【实践】决策接口：`enableThinking()` + 低温；快问快答：`disableThinking()` + flash。

---

## 5. defaultOptions vs 运行时 options

```java
@Bean
ChatClient decisionChatClient(ChatClient.Builder builder) {
    return builder
            .defaultOptions(DeepSeekChatOptions.builder()
                    .model("deepseek-v4-pro")
                    .temperature(0.2)
                    .enableThinking()
                    .reasoningEffortHigh()
                    .build())
            .build();
}

// 某次请求临时改用 flash
String fast = decisionChatClient.prompt()
        .options(DeepSeekChatOptions.builder()
                .model("deepseek-v4-flash")
                .disableThinking()
                .build())
        .user("一句话回答：今天星期几？")
        .call()
        .content();
```

运行时 `.options()` **覆盖**本次请求的模型参数，不影响 Bean 默认值。

---

## 6. spring.ai.retry.* 重试机制

DeepSeek 调用由 `RetryTemplate` 包装。常用配置：

```yaml
spring:
  ai:
    retry:
      max-attempts: 10
      backoff:
        initial-interval: 2s
        multiplier: 5
        max-interval: 3m
      on-client-errors: false
```

| 属性 | 默认 | 含义 |
|---|---|---|
| `max-attempts` | 10 | 最多尝试次数 |
| `backoff.initial-interval` | 2s | 首次退避 |
| `backoff.multiplier` | 5 | 指数倍数 |
| `backoff.max-interval` | 3m | 退避上限 |
| `on-client-errors` | false | 4xx 是否重试（一般 false） |
| `on-http-codes` | 空 | 指定要重试的状态码 |
| `exclude-on-http-codes` | 空 | 指定不重试的状态码 |

【老师建议】学习期保持默认即可。生产上对**幂等读接口**可依赖重试；对**带副作用**的操作不要指望重试救场——Agent 写操作本来就要禁止。

---

## 7. 读取 Token 用量（配合 Options 调优）

```java
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatOptions;

public record UsageInfo(Long promptTokens, Long completionTokens, Long totalTokens) {}

public UsageInfo measure(String question, ChatClient client) {
    ChatResponse resp = client.prompt()
            .options(DeepSeekChatOptions.builder()
                    .model("deepseek-v4-pro")
                    .maxTokens(2048)
                    .build())
            .user(question)
            .call()
            .chatResponse();

    var usage = resp.getMetadata().getUsage();
    if (usage == null) {
        return new UsageInfo(null, null, null);
    }
    return new UsageInfo(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
}
```

调 `max-tokens` 前先看清典型请求的 usage，避免截断或浪费。

---

## 8. 完整配置类 + 服务示例

```java
package com.example.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DualOptionsConfig {

    @Bean
    ChatClient flashChatClient(ChatClient.Builder builder) {
        return builder
                .defaultOptions(DeepSeekChatOptions.builder()
                        .model("deepseek-v4-flash")
                        .temperature(0.2)
                        .maxTokens(2048)
                        .disableThinking()
                        .build())
                .build();
    }

    @Bean
    ChatClient proChatClient(ChatClient.Builder builder) {
        return builder
                .defaultOptions(DeepSeekChatOptions.builder()
                        .model("deepseek-v4-pro")
                        .temperature(0.2)
                        .maxTokens(8192)
                        .enableThinking()
                        .reasoningEffortHigh()
                        .build())
                .build();
    }
}
```

```java
package com.example.agent.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class RoutedOptionsService {

    private final ChatClient flashChatClient;
    private final ChatClient proChatClient;

    public RoutedOptionsService(
            @Qualifier("flashChatClient") ChatClient flashChatClient,
            @Qualifier("proChatClient") ChatClient proChatClient) {
        this.flashChatClient = flashChatClient;
        this.proChatClient = proChatClient;
    }

    public String route(String question) {
        if (question.length() < 50 && !question.contains("归因") && !question.contains("对比")) {
            return flashChatClient.prompt().user(question).call().content();
        }
        return proChatClient.prompt().user(question).call().content();
    }
}
```

路由策略第 07 节会扩展；这里先建立 Options 与多 Bean 的直觉。

---

## 9. 易错清单

| 易错 | 正确 |
|---|---|
| 决策场景用 flash + 高温 | pro + 低温 + thinking |
| thinking 开启还指望 temperature 生效 | 接受提供商语义或关 thinking |
| 把 api-key 写进 yaml 提交 Git | 环境变量 `${DEEPSEEK_API_KEY}` |
| 1.x 写法 `.options(已 build 的实例)` 混用 | 2.0 用 `DeepSeekChatOptions.builder()...build()` |
| 无限 retry 掩盖逻辑错误 | 配合超时、熔断、日志 |

---

## 10. 小结

1. yaml 管默认：`spring.ai.deepseek.chat.*` + `spring.ai.retry.*`。
2. 运行时用 `DeepSeekChatOptions.builder()` 精细控制模型、温度、thinking、reasoning-effort。
3. flash 日常、pro 决策；thinking 用于复杂归因。
4. 重试是基础设施，不能替代业务幂等与权限设计。

下一篇：`06-Advisor机制详解.md`。

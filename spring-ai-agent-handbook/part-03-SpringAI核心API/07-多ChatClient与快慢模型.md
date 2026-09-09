# 07 · 多 ChatClient 与快慢模型

> **本节目标：** 在同一应用里配置 flash 快模型与 pro 强模型，正确使用 `ChatClientBuilderConfigurer`，避免 `ChatClient.create(chatModel)` 丢失观测。  
> **预计用时：** 1.5～2 小时。

---

## 1. 为什么需要多个 ChatClient

管理层决策系统里，典型分工：

| 任务 | 模型 | ChatClient |
|---|---|---|
| 意图粗分、寒暄、简单事实 | `deepseek-v4-flash` | `fastChatClient` |
| 归因、对比、风险建议 | `deepseek-v4-pro` + thinking | `decisionChatClient` |
| 结构化摘要（要稳） | pro 或 flash + `validateSchema()` | 可复用 decision |

若只有一个全局 `ChatClient`，你要么牺牲质量，要么牺牲成本。

---

## 2. 场景 A：同一 ChatModel，不同默认配置

`ChatClient.Builder` 是 **prototype** 作用域——每次注入都是新 Builder，可建多个 Bean：

```java
package com.example.agent.config;

import com.example.agent.prompt.DecisionSystemPrompt;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class SameModelMultiClientConfig {

    @Bean
    @Primary
    ChatClient fastChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("你是简洁助手，回答不超过两句话。")
                .defaultOptions(DeepSeekChatOptions.builder()
                        .model("deepseek-v4-flash")
                        .temperature(0.2)
                        .disableThinking()
                        .build())
                .build();
    }

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

Controller 用 `@Qualifier` 注入：

```java
package com.example.agent.web;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DualClientController {

    private final ChatClient fastChatClient;
    private final ChatClient decisionChatClient;

    public DualClientController(
            @Qualifier("fastChatClient") ChatClient fastChatClient,
            @Qualifier("decisionChatClient") ChatClient decisionChatClient) {
        this.fastChatClient = fastChatClient;
        this.decisionChatClient = decisionChatClient;
    }

    public record AskRequest(String tenant, String role, String dataAsOf, String question) {}

    @PostMapping("/api/chat/fast")
    public String fast(@RequestBody AskRequest req) {
        return fastChatClient.prompt().user(req.question()).call().content();
    }

    @PostMapping("/api/chat/decision")
    public String decision(@RequestBody AskRequest req) {
        return decisionChatClient.prompt()
                .system(s -> s
                        .param("tenantName", req.tenant())
                        .param("userRole", req.role())
                        .param("dataAsOf", req.dataAsOf()))
                .user(req.question())
                .call()
                .content();
    }
}
```

---

## 3. 场景 B：多种 ChatModel —— 必须用 ChatClientBuilderConfigurer

当你手动注册多个 `ChatModel` Bean（例如 DeepSeek + 本地 Embedding 旁的另一个对话模型），或未来扩展第二家云厂商时：

### ❌ 错误写法（丢失观测）

```java
// 不要这样写 —— 绕过 ChatClientBuilderConfigurer 与 Customizer
ChatClient bad = ChatClient.create(deepSeekChatModel);
ChatClient worse = ChatClient.builder(deepSeekChatModel).build();
```

### ✅ 正确写法

```java
package com.example.agent.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.observation.AdvisorObservationConvention;
import org.springframework.ai.chat.client.observation.ChatClientObservationConvention;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientBuilderConfigurer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class MultiModelChatClientConfig {

    @Bean
    @Primary
    ChatClient primaryDeepSeekClient(
            DeepSeekChatModel deepSeekChatModel,
            ChatClientBuilderConfigurer configurer,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<ChatClientObservationConvention> clientConvention,
            ObjectProvider<AdvisorObservationConvention> advisorConvention,
            ObjectProvider<ToolCallingAdvisor.Builder<?>> toolAdvisorBuilder) {

        return buildClient(
                deepSeekChatModel,
                configurer,
                observationRegistry,
                clientConvention,
                advisorConvention,
                toolAdvisorBuilder,
                DeepSeekChatOptions.builder()
                        .model("deepseek-v4-pro")
                        .enableThinking()
                        .build());
    }

    @Bean
    ChatClient flashDeepSeekClient(
            DeepSeekChatModel deepSeekChatModel,
            ChatClientBuilderConfigurer configurer,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<ChatClientObservationConvention> clientConvention,
            ObjectProvider<AdvisorObservationConvention> advisorConvention,
            ObjectProvider<ToolCallingAdvisor.Builder<?>> toolAdvisorBuilder) {

        return buildClient(
                deepSeekChatModel,
                configurer,
                observationRegistry,
                clientConvention,
                advisorConvention,
                toolAdvisorBuilder,
                DeepSeekChatOptions.builder()
                        .model("deepseek-v4-flash")
                        .disableThinking()
                        .build());
    }

    private ChatClient buildClient(
            ChatModel chatModel,
            ChatClientBuilderConfigurer configurer,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<ChatClientObservationConvention> clientConvention,
            ObjectProvider<AdvisorObservationConvention> advisorConvention,
            ObjectProvider<ToolCallingAdvisor.Builder<?>> toolAdvisorBuilder,
            DeepSeekChatOptions defaultOptions) {

        ChatClient.Builder builder = ChatClient.builder(
                chatModel,
                observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP),
                clientConvention.getIfUnique(),
                advisorConvention.getIfUnique(),
                toolAdvisorBuilder.getIfAvailable());

        return configurer.configure(builder)
                .defaultOptions(defaultOptions)
                .build();
    }
}
```

**`ChatClientBuilderConfigurer.configure(builder)`** 会：

1. 按顺序执行所有 `ChatClientBuilderCustomizer`；
2. 保留与自动配置一致的观测接线；
3. 让你在不同 `ChatModel` 上得到同等质量的 `ChatClient`。

多 `ChatModel` 时还可能需要给某个 `ChatModel` 或 `ChatClient` 标 `@Primary`，避免注入歧义。

---

## 4. spring.ai.model.chat=deepseek

当 classpath 上有多个模型 starter 时，用：

```yaml
spring:
  ai:
    model:
      chat: deepseek
```

告诉自动配置默认 `ChatModel` 选 DeepSeek。单 starter 项目通常可省略。

---

## 5. 路由服务：先 flash 分意图，再 pro 深答

```java
package com.example.agent.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class ModelRouterService {

    private final ChatClient fastChatClient;
    private final ChatClient decisionChatClient;

    private static final String CLASSIFY_PROMPT = """
            判断用户问题类型，只输出一个词：SIMPLE 或 COMPLEX。
            SIMPLE：寒暄、定义解释、无需业务数据。
            COMPLEX：需要数据、对比、归因、风险、决策建议。
            用户问题：{q}
            """;

    public ModelRouterService(
            @Qualifier("fastChatClient") ChatClient fastChatClient,
            @Qualifier("decisionChatClient") ChatClient decisionChatClient) {
        this.fastChatClient = fastChatClient;
        this.decisionChatClient = decisionChatClient;
    }

    public String route(String tenant, String role, String asOf, String question) {
        String kind = fastChatClient.prompt()
                .user(u -> u.text(CLASSIFY_PROMPT).param("q", question))
                .call()
                .content()
                .trim();

        if (kind.contains("SIMPLE")) {
            return fastChatClient.prompt().user(question).call().content();
        }
        return decisionChatClient.prompt()
                .system(s -> s
                        .param("tenantName", tenant)
                        .param("userRole", role)
                        .param("dataAsOf", asOf))
                .user(question)
                .call()
                .content();
    }
}
```

part-08 练习会要求你实现类似路由。生产上还可加规则引擎，别全靠模型分类。

---

## 6. ChatClientBuilderCustomizer 与多 Bean 的关系

Customizer 作用于 **所有** 经 `ChatClientBuilderConfigurer` 配置的 Builder。例如全局加租户校验 Advisor（见第 06 节 `TenantGuardAdvisor`）：

```java
@Bean
ChatClientBuilderCustomizer tenantGuardCustomizer() {
    return builder -> builder.defaultAdvisors(new TenantGuardAdvisor());
}
```

若某个 `ChatClient` 不要某 Advisor，在对应 `@Bean` 的 `build()` 链上不要再加，或运行时不用 `defaultAdvisors` 那份 Bean。

---

## 7. mutate() 派生临时客户端

```java
ChatClient strictDecision = decisionChatClient.mutate()
        .defaultOptions(DeepSeekChatOptions.builder()
                .temperature(0.0)
                .reasoningEffortMax()
                .build())
        .build();
```

适合单次请求要「比默认更保守」而不新建 Spring Bean。

---

## 8. 完整编排 Controller

```java
package com.example.agent.web;

import com.example.agent.service.ModelRouterService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RouterController {

    private final ModelRouterService routerService;

    public RouterController(ModelRouterService routerService) {
        this.routerService = routerService;
    }

    public record RouteRequest(String tenant, String role, String dataAsOf, String question) {}

    @PostMapping("/api/chat/route")
    public String route(@RequestBody RouteRequest req) {
        return routerService.route(req.tenant(), req.role(), req.dataAsOf(), req.question());
    }
}
```

---

## 9. 对照表

| 需求 | 做法 |
|---|---|
| 同模型不同 prompt/options | 多个 `@Bean`，各注入 `ChatClient.Builder` |
| 多 `ChatModel` 类型 | `ChatClientBuilderConfigurer` + `ChatClient.builder(...)` 全参数 |
| 保留全局 Customizer | 必须走 `configurer.configure(builder)` |
| 默认 ChatModel 歧义 | `@Primary` 或 `spring.ai.model.chat` |
| 临时改配置 | `mutate()` 或运行时 `.options()` |

---

## 10. 小结

1. 快慢模型 = 多个 `ChatClient` Bean，而不是多个 Controller 拼 HTTP。
2. 同模型用 prototype `ChatClient.Builder` 最简单。
3. 多模型禁止裸 `ChatClient.create(chatModel)`；用 `ChatClientBuilderConfigurer`。
4. 路由是成本与质量的杠杆，part-08 要亲手写一版。

下一篇：`08-本阶段练习与验收.md`。

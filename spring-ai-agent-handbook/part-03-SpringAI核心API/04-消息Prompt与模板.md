# 04 · 消息、Prompt 与模板

> **本节目标：** 掌握 `system()` / `user()` 消息拼装、模板占位符 `.param()`、JSON 与 `{}` 冲突时的 `StTemplateRenderer` 换分隔符。  
> **预计用时：** 1.5～2 小时。

---

## 1. Prompt 里有哪些消息类型

Spring AI 的 `Prompt` 本质是一组 `Message`：

| 类型 | 谁写 | 典型用途 |
|---|---|---|
| `SystemMessage` | 你（系统提示） | 角色、红线、输出格式 |
| `UserMessage` | 用户或你模拟 | 本轮问题 |
| `AssistantMessage` | 模型历史 | 多轮对话（记忆 Advisor 注入） |
| `ToolResponseMessage` 等 | 工具循环 | part-04 详讲 |

`ChatClient` 的 `.system()` / `.user()` 就是在帮你构建这些消息，再交给 Advisor 链加工。

---

## 2. 最简单的 system + user

```java
package com.example.agent.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class BasicMessageService {

    private final ChatClient chatClient;

    public BasicMessageService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    public String ask(String role, String question) {
        return chatClient.prompt()
                .system("你是" + role + "，用中文简洁回答。")
                .user(question)
                .call()
                .content();
    }
}
```

字符串拼接能用，但**可维护性差**。企业项目用模板 + `.param()`。

---

## 3. 模板占位符与 .param()

默认模板引擎是 `StTemplateRenderer`，占位符语法为 `{变量名}`。

```java
package com.example.agent.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class TemplateParamService {

    private final ChatClient chatClient;

    public TemplateParamService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    private static final String SYSTEM = """
            你是 {tenantName} 的 {userRole}。
            当前数据截止时间：{dataAsOf}。
            回答必须带「结论」和「依据」两段。
            """;

    public String ask(String tenant, String role, String asOf, String question) {
        return chatClient.prompt()
                .system(s -> s.text(SYSTEM)
                        .param("tenantName", tenant)
                        .param("userRole", role)
                        .param("dataAsOf", asOf))
                .user(u -> u.text("用户问题：{question}")
                        .param("question", question))
                .call()
                .content();
    }
}
```

【要点】

- `.system()` / `.user()` 都支持 `Consumer<SystemSpec>` / `Consumer<UserSpec>` 形式。
- `.param("key", value)` 在**运行时**替换模板。
- `defaultSystem` 里也可以写 `{voice}` 这类占位符，运行时 `.system(sp -> sp.param("voice", "严肃"))` 填充。

---

## 4. defaultSystem 带参数（Builder 级默认）

```java
package com.example.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TemplateDefaultConfig {

    @Bean
    ChatClient templatedChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("你是 {company} 助手，语气：{tone}。")
                .build();
    }
}
```

```java
String reply = templatedChatClient.prompt()
        .system(s -> s.param("company", "华东物流").param("tone", "正式"))
        .user("今日值班要点？")
        .call()
        .content();
```

---

## 5. JSON 与 `{}` 冲突：换 StTemplateRenderer 分隔符

若 system 或 user 模板里要嵌入 JSON 示例，花括号会与 `{变量}` 冲突。解决办法：**换分隔符**，例如 `<` 和 `>`。

```java
package com.example.agent.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.stereotype.Service;

@Service
public class JsonTemplateService {

    private final ChatClient chatClient;

    private static final StTemplateRenderer ANGLE_RENDERER =
            StTemplateRenderer.builder()
                    .startDelimiterToken('<')
                    .endDelimiterToken('>')
                    .build();

    private static final String OUTPUT_SCHEMA = """
            请严格输出如下 JSON 结构（字段名不要改）：
            {
              "conclusion": "string",
              "risks": ["string"],
              "actions": ["string"]
            }
            租户：<tenantName>，角色：<userRole>。
            """;

    public JsonTemplateService(ChatClient.Builder builder) {
        this.chatClient = builder
                .defaultTemplateRenderer(ANGLE_RENDERER)
                .build();
    }

    public String askWithJsonSchema(String tenant, String role, String question) {
        return chatClient.prompt()
                .system(s -> s.text(OUTPUT_SCHEMA)
                        .param("tenantName", tenant)
                        .param("userRole", role))
                .user(question)
                .call()
                .content();
    }
}
```

也可在**单次请求**上覆盖：

```java
chatClient.prompt()
        .user(u -> u.text("示例 JSON：{\"a\":1}，租户：<t>").param("t", "示范"))
        .templateRenderer(ANGLE_RENDERER)
        .call()
        .content();
```

【老师提醒】`ChatClient` 上的 `templateRenderer` 只作用于本链路上的 `.system()` / `.user()` 模板，**不影响** Advisor 内部自己的模板（RAG 类 Advisor 有独立配置，part-05 再讲）。

---

## 6. 从文件加载 system（Resource）

长 system prompt 放 `src/main/resources/prompts/`：

```
resources/prompts/decision-system.st
```

```java
package com.example.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

@Configuration
public class ResourcePromptConfig {

    @Bean
    ChatClient resourceSystemClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem(new ClassPathResource("prompts/decision-system.st"))
                .build();
    }
}
```

`.st` 文件里同样用 `{tenantName}` 占位，运行时 `.system(s -> s.param(...))` 填充。

---

## 7. messages() 直接塞 Message 列表

与 imperative `Prompt` API 互操作：

```java
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

String reply = chatClient.prompt()
        .messages(
                new SystemMessage("你是库存分析助手。"),
                new UserMessage("SKU-88321 安全库存多少？"),
                new AssistantMessage("我需要仓库编码才能查询。"),
                new UserMessage("仓库：华东仓"))
        .call()
        .content();
```

手动拼历史适合调试；生产多轮请用 `MessageChatMemoryAdvisor`（第 06 节）。

---

## 8. 消息 Metadata（可选）

2.0 支持给 user/system 消息附加元数据，供下游 Advisor 或审计使用：

```java
String reply = chatClient.prompt()
        .user(u -> u.text("查询库存")
                .metadata("userId", "u-10086")
                .metadata("tenantId", "tenant-east")
                .metadata("traceId", "trace-abc"))
        .system(s -> s.text("你是助手")
                .metadata("promptVersion", "v3"))
        .call()
        .content();
```

规则：key 不能 null/空，value 不能 null。

---

## 9. 完整示例：带 JSON 模板的结构化请求

```java
package com.example.agent.web;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class StructuredTemplateController {

    private final ChatClient chatClient;

    private static final StTemplateRenderer RENDERER =
            StTemplateRenderer.builder().startDelimiterToken('<').endDelimiterToken('>').build();

    private static final String SYSTEM = """
            你是 <tenantName> 决策助手。
            输出 JSON，包含字段：conclusion (string), evidence (string[]), riskLevel (LOW|MEDIUM|HIGH)。
            不要输出 Markdown 代码块。
            """;

    public StructuredTemplateController(ChatClient.Builder builder) {
        this.chatClient = builder.defaultTemplateRenderer(RENDERER).build();
    }

    public record Request(String tenant, String question) {}

    public record DecisionJson(String conclusion, List<String> evidence, String riskLevel) {}

    @PostMapping("/api/decision/json")
    public DecisionJson decide(@RequestBody Request req) {
        return chatClient.prompt()
                .system(s -> s.text(SYSTEM).param("tenantName", req.tenant()))
                .user(req.question())
                .call()
                .entity(DecisionJson.class, spec -> spec.validateSchema());
    }
}
```

---

## 10. 与 1.x 的差异提醒

| 1.x 习惯 | 2.0 |
|---|---|
| 大量字符串 `+` 拼接 | 模板 + `.param()` |
| `PromptChatMemoryAdvisor` 把历史塞进 system 文本 | `MessageChatMemoryAdvisor` 以消息列表注入 |
| 忽略 JSON 花括号冲突 | 换 `StTemplateRenderer` 分隔符 |

---

## 11. 本节小结

1. `system()` / `user()` 支持纯文本、Resource、带 `.param()` 的模板。
2. 默认 `{}` 占位；嵌 JSON 时换 `<>` 等分隔符。
3. `defaultSystem` / `defaultUser` / `defaultTemplateRenderer` 在 Builder 设默认，运行时可覆盖。
4. 长 prompt 放 classpath Resource，版本化管理。

下一篇：`05-Options温度思考模式与重试.md`。

# 01 · BOM、模块与自动配置

> **本节目标：** 搞懂 pom 里该引什么、starter 帮你装了哪些 Bean、为什么注入 `ChatClient.Builder` 而不是自己 `new`。  
> **预计用时：** 1～1.5 小时。

---

## 1. 版本冻结（学习期不要改）

```
Java              21（推荐）/ 17（最低）
Spring Boot       4.1.x
Spring AI BOM     2.0.0
Maven             3.9+
```

这三者是一根绳上的蚂蚱：Boot 4 对应 Spring Framework 7、Jackson 3；Spring AI 2.0 是为这套基线写的。不要用 1.x 的 starter 名，不要追 SNAPSHOT。

---

## 2. 最小可运行 pom

阶段 A（只会说话）只需要两个 starter：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.0</version>
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>decision-agent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <name>decision-agent</name>

    <properties>
        <java.version>21</java.version>
        <spring-ai.version>2.0.0</spring-ai.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <!-- 流式 SSE 需要 Reactive 栈 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-deepseek</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

**要点：**

- `spring-ai-bom` 用 `import` 作用域统一管理 Spring AI 全家桶版本，子依赖**不要**手写版本号。
- 主线 DeepSeek starter 是 `spring-ai-starter-model-deepseek`，不是 OpenAI 兼容 starter。
- 只要用 `stream()`，就要加 `spring-boot-starter-webflux`（官方说明：流式走 Reactive 栈）。

---

## 3. application.yaml 最小配置

```yaml
spring:
  ai:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
      chat:
        model: deepseek-v4-flash
        temperature: 0.3
        max-tokens: 4096
```

环境变量里放 Key，不要写进仓库：

```bash
export DEEPSEEK_API_KEY=sk-xxxxxxxx
```

多模型场景后面会加 `spring.ai.model.chat=deepseek`，单模型时通常不必写。

---

## 4. starter 帮你装了什么（心智图）

引入 `spring-ai-starter-model-deepseek` 后，自动配置大致会注册：

```
DeepSeekApi
    └── DeepSeekChatModel          ← ChatModel 实现（底层）
ChatClientBuilderConfigurer      ← 统一应用 ChatClientBuilderCustomizer
ToolCallingAdvisor.Builder       ← 工具循环 Advisor 工厂
ChatClient.Builder (prototype)   ← 绑定默认 ChatModel，每次注入是新实例
ObservationRegistry 相关         ← Micrometer 观测（若 classpath 有）
RetryTemplate                    ← spring.ai.retry.* 驱动
```

**业务代码的日常入口是 `ChatClient.Builder` 或你自己 `@Bean` 出来的 `ChatClient`。**  
`DeepSeekChatModel` 也会被注册，但你不应该在 Controller 里直接注入它——那是底层。

---

## 5. 两种注入方式（2.0 推荐）

### 方式 A：注入 `ChatClient.Builder`（最常用）

```java
package com.example.agent.web;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloChatController {

    private final ChatClient chatClient;

    public HelloChatController(ChatClient.Builder chatClientBuilder) {
        // prototype Builder：保留观测、Customizer、默认 ToolCallingAdvisor
        this.chatClient = chatClientBuilder.build();
    }

    @GetMapping("/api/chat/hello")
    public String hello(@RequestParam(defaultValue = "你好，请用一句话介绍你自己") String message) {
        return chatClient.prompt()
                .user(message)
                .call()
                .content();
    }
}
```

### 方式 B：自己定义 `@Bean ChatClient`（带默认 system）

```java
package com.example.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    @Bean
    ChatClient defaultChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("你是一个简洁的中文助手，回答不超过三句话。")
                .build();
    }
}
```

Controller 直接注入 `ChatClient`：

```java
package com.example.agent.web;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InjectedChatController {

    private final ChatClient chatClient;

    public InjectedChatController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @GetMapping("/api/chat/short")
    public String shortAnswer(@RequestParam String message) {
        return chatClient.prompt().user(message).call().content();
    }
}
```

【老师提醒】方式 A 和 B 都对。团队项目建议：**公共默认走 `@Bean`，临时试验走 Builder 注入**。

---

## 6. ChatClientBuilderCustomizer：全局默认

若希望所有通过自动配置创建的 `ChatClient.Builder` 都带上统一设置（例如默认温度、默认 system），实现 Customizer：

```java
package com.example.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GlobalChatCustomizerConfig {

    @Bean
    ChatClientBuilderCustomizer decisionDefaultsCustomizer() {
        return builder -> builder
                .defaultOptions(DeepSeekChatOptions.builder()
                        .temperature(0.2)
                        .build())
                .defaultSystem("你是企业内部助手，禁止编造业务数据。");
    }
}
```

Customizer 在 `ChatClientBuilderConfigurer.configure()` 阶段按顺序执行。同类型多个 Bean 时，顺序由 `@Order` 控制。

---

## 7. 启动时如何验证 Bean 就绪

```java
package com.example.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class DecisionAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(DecisionAgentApplication.class, args);
    }

    @Bean
    ApplicationRunner verifyAiBeans(ChatModel chatModel, ChatClient.Builder builder) {
        return args -> {
            System.out.println("ChatModel 实现类: " + chatModel.getClass().getSimpleName());
            System.out.println("是否为 DeepSeek: " + (chatModel instanceof DeepSeekChatModel));
            ChatClient client = builder.build();
            String reply = client.prompt().user("ping").call().content();
            System.out.println("连通性测试: " + reply);
        };
    }
}
```

启动后控制台应看到 `DeepSeekChatModel` 和一段模型回复。若报 `api-key` 相关错误，先检查环境变量。

---

## 8. 模块依赖演进（别第一天堆满）

| 阶段 | 额外依赖 | 何时加 |
|---|---|---|
| A · 对话 | `spring-ai-starter-model-deepseek` | 现在 |
| B · Agent 工具 | 仍是上面那些；`ToolCallingAdvisor` 已内置 | part-04 |
| C · RAG | `spring-ai-rag`、向量库 starter | part-05 |
| D · 生产记忆 | JDBC / Redis ChatMemoryRepository | part-07 |

---

## 9. 常见启动失败

| 现象 | 原因 | 处理 |
|---|---|---|
| `No qualifying bean of type ChatModel` | 没引 DeepSeek starter 或 api-key 配置失败 | 检查 pom 与 yaml |
| `ChatClient.Builder` 歧义 | 容器里多个 `ChatModel` 且没 `@Primary` | 见第 07 节 |
| 流式接口 404 或阻塞 | 缺 `webflux` starter | 补依赖 |
| 版本冲突 `NoSuchMethodError` | 没 import BOM，混了 1.x 传递依赖 | `mvn dependency:tree` 排查 |

---

## 10. 本节小结

1. 用 `spring-ai-bom:2.0.0` 锁版本，DeepSeek 用 `spring-ai-starter-model-deepseek`。
2. 自动配置给你 `ChatClient.Builder`（prototype）和底层 `DeepSeekChatModel`。
3. 业务注入 `ChatClient.Builder` 或自定义 `ChatClient` Bean，不要日常直接用 `ChatModel`。
4. 流式需要 `spring-boot-starter-webflux`。
5. 全局默认用 `ChatClientBuilderCustomizer`。

下一篇进入 fluent API 全图：`02-ChatClient从入门到熟练.md`。

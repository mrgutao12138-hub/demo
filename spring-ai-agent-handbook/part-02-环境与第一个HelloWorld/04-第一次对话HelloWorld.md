# 04 · 第一次对话 Hello World

> 同学们，激动人心的时刻到了：用 **Spring AI 2.0 的 ChatClient**，让 DeepSeek 回你第一句话。  
> 本章只注入 **`ChatClient.Builder`**，不直接注入 `ChatModel`——这是 2.0 的日常用法。

---

## 1. 本章目标

1. 配置类里用 `ChatClient.Builder` 组装 `ChatClient`。
2. 写一个 REST 接口：传入用户消息，返回模型文本。
3. 用 `curl` 验收成功响应。
4. 故意去掉 Key，观察失败现象，学会看日志。

---

## 2. ChatClient 调用链回顾

```java
String answer = chatClient
    .prompt()
    .user(userMessage)
    .call()      // 构建调用，此时还未请求模型
    .content();  // 消费结果 → 真正触发 HTTP
```

【易混】`call()` **不触发**模型；`.content()`、`.entity()` 才会发请求。调试时若在 `call()` 后打断点，别误以为「已经调过 API 了」。

---

## 3. 【必做】创建 `ChatClient` 配置类

`src/main/java/com/example/hello/config/ChatClientConfig.java`：

```java
package com.example.hello.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    /**
     * Spring AI 2.0 自动配置会提供 ChatClient.Builder（prototype 作用域）。
     * 我们用 build() 得到本应用默认的 ChatClient 实例。
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("你是一个简洁友好的 Spring AI 助教，回答用中文。")
                .build();
    }
}
```

说明：

- 构造器注入的 **`ChatClient.Builder`** 由 `spring-ai-starter-model-deepseek` 自动配置提供。
- `defaultSystem(...)` 相当于每次对话默认带的系统提示；Hello World 先写死一句即可。
- 不要在这里 `@Autowired ChatModel`——后面讲 Advisor、工具循环时再碰底层模型。

先创建目录：

```bash
mkdir -p src/main/java/com/example/hello/config
```

---

## 4. 【必做】创建 Hello World 控制器

`src/main/java/com/example/hello/web/ChatController.java`：

```java
package com.example.hello.web;

import com.example.hello.web.dto.ChatRequest;
import com.example.hello.web.dto.ChatResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @PostMapping("/hello")
    public ChatResponse hello(@RequestBody ChatRequest request) {
        String message = request.message();
        if (message == null || message.isBlank()) {
            return new ChatResponse("请输入非空消息。");
        }

        String content = chatClient
                .prompt()
                .user(message)
                .call()
                .content();

        return new ChatResponse(content);
    }
}
```

DTO 用 **Java record**（Boot 4 + Jackson 3 原生支持）：

`src/main/java/com/example/hello/web/dto/ChatRequest.java`：

```java
package com.example.hello.web.dto;

public record ChatRequest(String message) {
}
```

`src/main/java/com/example/hello/web/dto/ChatResponse.java`：

```java
package com.example.hello.web.dto;

public record ChatResponse(String reply) {
}
```

创建目录：

```bash
mkdir -p src/main/java/com/example/hello/web/dto
```

---

## 5. 【必做】确认 `application.yml`

与 `03` 章一致即可：

```yaml
server:
  port: 8080

spring:
  application:
    name: spring-ai-hello
  ai:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
      chat:
        model: deepseek-v4-flash
        temperature: 0.3
```

---

## 6. 【必做】启动应用

```bash
cd ~/projects/spring-ai-hello
export DEEPSEEK_API_KEY="sk-你的密钥"   # 若已持久化可省略
mvn spring-boot:run
```

IDEA：右键 `SpringAiHelloApplication` → Run，确保 Run Configuration 带 `DEEPSEEK_API_KEY`。

---

## 7. 【必做】curl 验收（成功场景）

新开终端：

```bash
curl -s -X POST http://localhost:8080/api/chat/hello \
  -H "Content-Type: application/json" \
  -d '{"message":"用一句话介绍 Spring AI 2.0 的 ChatClient"}'
```

期望 JSON 类似：

```json
{"reply":"Spring AI 2.0 把 ChatClient 作为一等公民，用流畅 API 封装提示词、调用模型并消费文本或结构化结果。"}
```

（`reply` 具体内容每次可能不同，只要有合理中文回复即过关。）

再测边界：

```bash
curl -s -X POST http://localhost:8080/api/chat/hello \
  -H "Content-Type: application/json" \
  -d '{"message":""}'
```

应返回：

```json
{"reply":"请输入非空消息。"}
```

---

## 8. 【必做】无 Key 时的失败实验

**目的：** 认清占位符与 401 的区别，避免以后甩锅「Spring 坏了」。

### 8.1 未设置环境变量就启动

1. 新开终端，**不要** `export DEEPSEEK_API_KEY`。
2. 启动：

```bash
mvn spring-boot:run
```

**常见现象 A：** 启动阶段直接失败：

```
Could not resolve placeholder 'DEEPSEEK_API_KEY'
```

说明 yml 里 `${DEEPSEEK_API_KEY}` 没有默认值，Spring 拒绝启动。**修复：** 设置环境变量（见 `01-环境准备.md`）。

### 8.2 Key 为空字符串或错误 Key

若你临时设了空值：

```bash
export DEEPSEEK_API_KEY=""
mvn spring-boot:run
```

应用可能能启动，但调用接口时 **500**，日志里常见：

- HTTP **401 Unauthorized**
- 或 DeepSeek 返回的 invalid api key 类错误信息

用 curl 再调一次 `/api/chat/hello`，观察控制台堆栈，找到 `org.springframework.ai` 或 HTTP 客户端相关异常。

**修复：** 在控制台核对 Key，重新 `export` 正确值，**重启应用**（环境变量改了，已运行的 JVM 不会自动刷新）。

### 8.3 配置前缀写错（演示用，【勿提交】）

若误写成：

```yaml
spring:
  ai:
    openai:
      api-key: ${DEEPSEEK_API_KEY}
```

DeepSeek starter **读不到** Key，表现像「没配密钥」。改回：

```yaml
spring:
  ai:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
```

---

## 9. 完整文件清单（便于自查）

```
src/main/java/com/example/hello/
├── SpringAiHelloApplication.java
├── config/
│   └── ChatClientConfig.java
└── web/
    ├── ChatController.java
    └── dto/
        ├── ChatRequest.java
        └── ChatResponse.java
```

---

## 10. 常见卡点

| 现象 | 原因 | 处理 |
|---|---|---|
| `ChatClient.Builder` 无法注入 | 缺少 `spring-ai-starter-model-deepseek` 或 BOM 未 import | 检查 `pom.xml` |
| 启动成功，接口 500，401 | Key 错误或未设置 | 检查环境变量与控制台 Key 状态 |
| 依赖 OK 但 IDEA 报红 | Maven 未刷新 | IDEA 右侧 Maven → Reload |
| 用了 `@Autowired ChatModel` 能跑但讲义不让 | 1.x 老习惯 | 改回 `ChatClient` + `Builder` |
| `call()` 后没日志 | 正常，`call()` 不触发请求 | 断点打在 `.content()` |

---

## 11. 【必做】本章验收

1. 能口头说出：`call()` 与 `content()` 谁触发模型？（`content()`）
2. `POST /api/chat/hello` 返回非空 `reply`。
3. 空消息返回友好提示，不调用模型（可看日志：无 outbound HTTP）。
4. 做过一次「无 Key / 错 Key」实验，能描述日志大致长什么样。
5. 配置前缀是 `spring.ai.deepseek`，不是 `openai`。

---

## 12. 下一章

同步对话跑通后，打开 `05-流式输出与结构化输出.md`，学 SSE 与 `record` 结构化映射。

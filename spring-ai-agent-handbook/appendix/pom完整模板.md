# pom 完整模板

> 两份可直接复制的 `pom.xml`。**学习期版本冻结**，勿改 BOM 版本号除非全班一起升级。  
> 模板 A：只会对话（part-02～03）。模板 B：对话 + RAG + Web + Actuator（part-05 起，part-08 基础）。

---

## 共用说明

| 项 | 值 |
|---|---|
| Java | 21 |
| Spring Boot parent | 4.1.0（可用 4.1.x 小版本，团队统一即可） |
| Spring AI BOM | 2.0.0，`import` 作用域 |
| 对话模型 | `spring-ai-starter-model-deepseek` |
| API Key | 环境变量 `DEEPSEEK_API_KEY`，yaml 用 `${DEEPSEEK_API_KEY}` |

**可选依赖（模板 B 已注释标注）：**

- `spring-ai-starter-tool-search-advisor` — 工具很多时（part-04 建议篇）
- `spring-ai-starter-mcp-client` — MCP 远程工具（扩展/ part-04 建议篇）
- `spring-ai-starter-vector-store-pgvector` — 生产向量库（替换学习期内存库）

---

## 模板 A · 只会对话（阶段 A）

适用：Hello World、流式、结构化输出、双 ChatClient，**不含** RAG 与向量模型。

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
    <description>Spring AI 2.0 + DeepSeek 对话入门（模板 A）</description>

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
        <!-- Web API -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <!-- 流式 SSE 需要 Reactive 栈 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>

        <!-- DeepSeek 对话（ChatModel + ChatClient 自动配置） -->
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

### 模板 A 最小 `application.yml`

```yaml
spring:
  application:
    name: decision-agent
  ai:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
      chat:
        model: deepseek-v4-flash
        temperature: 0.3
        max-tokens: 4096

server:
  port: 8080
```

---

## 模板 B · 对话 + RAG + Web + Actuator（阶段 C 起）

适用：制度 RAG、库存 Agent、part-06  JDBC、part-08 综合项目底座。

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
    <artifactId>decision-copilot</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <name>decision-copilot</name>
    <description>决策 Agent：对话 + RAG + 工具 + 可观测（模板 B）</description>

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
        <!-- Web -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>

        <!-- 健康检查、指标、就绪探针（part-08 / part-09） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <!-- 参数校验（API DTO） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <!-- 对话：DeepSeek Chat -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-deepseek</artifactId>
        </dependency>

        <!-- RAG 模块化主路径（2.0） -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-rag</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-vector-store</artifactId>
        </dependency>

        <!-- 本地 Embedding（DeepSeek 不提供向量 API） -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-transformers</artifactId>
        </dependency>

        <!-- part-06：演示宽表 / 只读库 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- —— 可选：生产 pgvector（启用时去掉注释，并删 SimpleVectorStore 配置）—— -->
        <!--
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-vector-store-pgvector</artifactId>
        </dependency>
        -->

        <!-- —— 可选：工具很多时按需披露 schema —— -->
        <!--
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-tool-search-advisor</artifactId>
        </dependency>
        -->

        <!-- —— 可选：MCP 客户端，接远程工具进程 —— -->
        <!--
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-mcp-client</artifactId>
        </dependency>
        -->

        <!-- —— 可选：part-07 生产会话记忆 —— -->
        <!--
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        -->

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

### 模板 B 推荐 `application.yml` 骨架

```yaml
spring:
  application:
    name: decision-copilot

  ai:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
      chat:
        model: deepseek-v4-pro
        temperature: 0.2
        max-tokens: 8192
        thinking:
          type: enabled
        reasoning-effort: high

    # 本地 Transformers embedding（首次启动可能下载 ONNX 权重）
    model:
      embedding: transformers

    chat:
      client:
        tool-calling:
          enabled: true

  datasource:
    url: jdbc:postgresql://localhost:5432/decision_demo
    username: decision_ro
    password: ${DECISION_DB_PASSWORD:decision_ro}
    hikari:
      read-only: true

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: when_authorized

server:
  port: 8080
```

---

## 两模板差异一览

| 依赖 | 模板 A | 模板 B |
|---|:---:|:---:|
| `spring-boot-starter-web` | ✅ | ✅ |
| `spring-boot-starter-webflux` | ✅ | ✅ |
| `spring-boot-starter-actuator` | ❌ | ✅ |
| `spring-ai-starter-model-deepseek` | ✅ | ✅ |
| `spring-ai-rag` | ❌ | ✅ |
| `spring-ai-vector-store` | ❌ | ✅ |
| `spring-ai-starter-model-transformers` | ❌ | ✅ |
| `spring-boot-starter-jdbc` + PostgreSQL | ❌ | ✅ |
| `tool-search-advisor`（可选） | ❌ | 注释 |
| `mcp-client`（可选） | ❌ | 注释 |

---

## 验证命令

```bash
# 解析依赖
mvn -q dependency:tree | grep spring-ai

# 启动
export DEEPSEEK_API_KEY=sk-你的密钥
mvn spring-boot:run

# 健康检查（模板 B）
curl -s http://localhost:8080/actuator/health
```

期望 `dependency:tree` 中 Spring AI 构件版本均为 **2.0.0**（由 BOM 对齐），无 `com.fasterxml.jackson` 与 Boot 4 冲突。

---

## 常见改 pom 错误（对照踩坑手册）

1. 子依赖手写 `<version>1.0.0-M1</version>` → 删掉，交给 BOM  
2. 用 `spring-ai-openai-spring-boot-starter` → 改为 `spring-ai-starter-model-deepseek`  
3. 只有 web 没有 webflux → 流式失败  
4. 加了 RAG 没加 transformers → 没有 `EmbeddingModel` Bean  
5. 两个 `ChatModel` 没 `@Primary` → 启动歧义（见 part-03）

---

*复制后请改 `groupId` / `artifactId`；版本号学习期勿动。*

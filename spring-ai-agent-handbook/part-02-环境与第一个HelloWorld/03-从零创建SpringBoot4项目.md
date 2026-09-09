# 03 · 从零创建 Spring Boot 4 项目

> 这一章我们不依赖 Spring Initializr 网页（公司网络常常打不开），**手写 `pom.xml` 和目录**。  
> 你会得到一棵能 `mvn spring-boot:run` 的空项目；DeepSeek 依赖在下一章再加。

---

## 1. 项目信息（冻结）

| 项 | 值 |
|---|---|
| 项目名 | `spring-ai-hello` |
| groupId | `com.example` |
| artifactId | `spring-ai-hello` |
| Java | 21 |
| Spring Boot parent | 4.1.0（4.1.x 均可，讲义锁定 4.1.0） |
| Spring AI BOM | 2.0.0 |

---

## 2. 【必做】创建目录

在终端执行（路径可自定，讲义用 `~/projects`）：

```bash
mkdir -p ~/projects/spring-ai-hello
cd ~/projects/spring-ai-hello
```

Windows PowerShell：

```powershell
mkdir $HOME\projects\spring-ai-hello
cd $HOME\projects\spring-ai-hello
```

---

## 3. 【必做】完整 `pom.xml`

在项目根目录创建 `pom.xml`，**全文复制**：

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
    <artifactId>spring-ai-hello</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>spring-ai-hello</name>
    <description>Spring AI 2.0 + DeepSeek Hello World</description>

    <properties>
        <java.version>21</java.version>
        <spring-ai.version>2.0.0</spring-ai.version>
    </properties>

    <!-- 【关键】Spring AI BOM：type=pom, scope=import -->
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

        <!-- 下一章会用到；本章先写上，版本由 BOM 管理 -->
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

### 3.1 老师划重点

1. **`spring-ai-bom` 必须 `import`**。漏了 `type` / `scope`，子依赖版本对不上，IDEA 一片红。
2. 依赖名是 **`spring-ai-starter-model-deepseek`**，不是 1.x 的 `spring-ai-openai-spring-boot-starter`。
3. 不要手写 `spring-ai-xxx` 的版本号，交给 BOM。

---

## 4. 【必做】标准目录结构

```bash
mkdir -p src/main/java/com/example/hello
mkdir -p src/main/resources
mkdir -p src/test/java/com/example/hello
```

最终应类似：

```
spring-ai-hello/
├── pom.xml
├── .gitignore
└── src/
    ├── main/
    │   ├── java/com/example/hello/
    │   │   └── SpringAiHelloApplication.java
    │   └── resources/
    │       └── application.yml
    └── test/java/com/example/hello/
        └── SpringAiHelloApplicationTests.java
```

用 IDEA：`File` → `Open` → 选 `pom.xml` 所在目录，以 Maven 项目导入。

---

## 5. 【必做】主启动类

`src/main/java/com/example/hello/SpringAiHelloApplication.java`：

```java
package com.example.hello;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SpringAiHelloApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringAiHelloApplication.class, args);
    }
}
```

---

## 6. 【必做】`application.yml`

`src/main/resources/application.yml`：

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

说明：

- **`spring.ai.deepseek.api-key`**：读环境变量，禁止写真实 Key。
- **`spring.ai.deepseek.chat.model`**：本章用 `deepseek-v4-flash`。
- **`temperature: 0.3`**：偏低，输出更稳；结构化章节会再调。
- **base-url** 默认 `https://api.deepseek.com`，一般省略。

【易混】若你写成 `spring.ai.openai.api-key`，自动配置会对不上 DeepSeek starter，表现通常是 401 或根本连错服务。

---

## 7. 【必做】`.gitignore`

项目根目录 `.gitignore`：

```gitignore
# Maven
target/
pom.xml.tag
pom.xml.releaseBackup
pom.xml.versionsBackup
release.properties

# IDE
.idea/
*.iml
.vscode/
*.swp
*~

# OS
.DS_Store
Thumbs.db

# 本地密钥与覆盖配置（【红线】）
.env
.env.*
application-local.yml
application-local.properties
**/application-secret.yml
```

【红线】若你创建了 `application-local.yml` 放 Key，务必被 gitignore 覆盖，且**不要** `git add -f` 强提。

---

## 8. 【建议】最小测试类

`src/test/java/com/example/hello/SpringAiHelloApplicationTests.java`：

```java
package com.example.hello;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class SpringAiHelloApplicationTests {

    @Test
    void contextLoads() {
    }
}
```

---

## 9. 【必做】编译与启动

确保已设置 `DEEPSEEK_API_KEY`（见前两章），然后：

```bash
cd ~/projects/spring-ai-hello   # 换成你的路径
mvn -q clean compile
mvn spring-boot:run
```

期望日志末尾出现类似：

```
Started SpringAiHelloApplication in x.xxx seconds
```

此时还没有 Controller，访问 `http://localhost:8080` 会 404，**这是正常的**。

另开终端跑测试：

```bash
mvn -q test
```

---

## 10. 常见启动失败

### 10.1 依赖解析失败 / 找不到 spring-ai

**原因：** `spring-ai-bom` 没正确 `import`，或 `${spring-ai.version}` 不是 2.0.0。

**检查：** `pom.xml` 里 `dependencyManagement` 段是否与讲义一致。

### 10.2 使用了旧 starter 名

**错误依赖示例：**

```xml
<!-- 不要这样写（1.x） -->
<artifactId>spring-ai-openai-spring-boot-starter</artifactId>
```

**正确：**

```xml
<artifactId>spring-ai-starter-model-deepseek</artifactId>
```

### 10.3 `Could not resolve placeholder 'DEEPSEEK_API_KEY'`

**原因：** 环境变量未设置，且 yml 里没有默认值。

**处理：** 按 `01-环境准备.md` 设置变量；或临时：

```bash
export DEEPSEEK_API_KEY="sk-你的密钥"
mvn spring-boot:run
```

IDEA 运行要在 Run Configuration 里加环境变量。

### 10.4 Java 版本不对

**现象：** 编译报「无效的目标发行版」或「release version 21 not supported」。

**处理：** 安装 JDK 21，并让 `JAVA_HOME`、IDEA Project SDK、`pom.xml` 的 `<java.version>21</java.version>` 一致。

### 10.5 端口 8080 被占用

```yaml
server:
  port: 8081
```

或关掉占用 8080 的进程。

---

## 11. 【必做】本章验收

1. `mvn clean compile` 无错误。
2. `mvn spring-boot:run` 能启动，日志无 `DEEPSEEK_API_KEY` 占位符错误。
3. `pom.xml` 中 BOM 为 `spring-ai-bom` **2.0.0**，`type=pom`，`scope=import`。
4. 依赖为 `spring-ai-starter-model-deepseek`，不是 openai 旧 starter。
5. `application.yml` 使用 `spring.ai.deepseek` 前缀。
6. `.gitignore` 已忽略本地密钥文件。

---

## 12. 下一章

骨架跑通后，打开 `04-第一次对话HelloWorld.md`，写 `ChatClient` 与第一个 REST 接口。

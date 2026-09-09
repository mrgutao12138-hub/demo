# 02 · 用 @Tool 注解写第一个库存 Agent

> 【必做】这一节代码最多，也最重要。  
> 请新建模块 `inventory-agent-demo`，跟 part-02 的 Hello World **分开**，避免 pom 搅在一起。

---

## 1. 本节目标

跑通一个完整 Spring Boot 应用：

- 三个只读库存工具（内存 `Map`）
- `ChatClient` + 自动 `ToolCallingAdvisor`
- HTTP 接口提问
- 日志里能看见**工具循环**（第几轮、调了谁、参数摘要）

跑通后，你就拥有第一个真 Agent。

---

## 2. pom 增量（在 part-02 基础上加这些即可）

```xml
<properties>
    <java.version>21</java.version>
    <spring-boot.version>4.1.0</spring-boot.version>
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
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-model-deepseek</artifactId>
    </dependency>
</dependencies>
```

不需要额外引 `tool-calling` 构件——`ChatClient` 自带 `ToolCallingAdvisor` 自动配置。

---

## 3. application.yml

```yaml
server:
  port: 8080

spring:
  ai:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
      chat:
        model: deepseek-v4-pro
        temperature: 0.2
    chat:
      client:
        tool-calling:
          enabled: true   # 默认 true；显式写出来便于对照实验

logging:
  level:
    com.company.decision: DEBUG
    org.springframework.ai.chat.client.advisor: DEBUG
    org.springframework.ai.tool: DEBUG
```

---

## 4. 模拟数据：InventoryStore

```java
package com.company.decision.inventory;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class InventoryStore {

    public record Sku(String skuId, String name, String spec, String unit) {}

    public record InventoryLine(
            String skuId,
            String warehouseId,
            String warehouseName,
            int availableQty,
            int inTransitQty,
            Instant updatedAt
    ) {}

    private static final Set<String> ALLOWED_WAREHOUSES = Set.of("WH-A", "WH-B", "WH-C");

    private final Map<String, Sku> skus = new LinkedHashMap<>();
    private final Map<String, InventoryLine> lines = new LinkedHashMap<>();

    public InventoryStore() {
        skus.put("SKU-1001", new Sku("SKU-1001", "三相断路器", "63A", "件"));
        skus.put("SKU-2002", new Sku("SKU-2002", "温湿度传感器", "RS485", "个"));

        lines.put(key("WH-A", "SKU-1001"),
                line("WH-A", "A仓-华东", "SKU-1001", 128, 40));
        lines.put(key("WH-B", "SKU-1001"),
                line("WH-B", "B仓-华南", "SKU-1001", 42, 200));
        lines.put(key("WH-C", "SKU-1001"),
                line("WH-C", "C仓-华北", "SKU-1001", 310, 0));
        lines.put(key("WH-A", "SKU-2002"),
                line("WH-A", "A仓-华东", "SKU-2002", 560, 0));
    }

    private static String key(String wh, String sku) {
        return wh + ":" + sku;
    }

    private InventoryLine line(String wh, String whName, String sku, int avail, int transit) {
        return new InventoryLine(sku, wh, whName, avail, transit, Instant.parse("2026-09-08T10:00:00Z"));
    }

    public boolean isWarehouseAllowed(String warehouseId) {
        return warehouseId != null && ALLOWED_WAREHOUSES.contains(warehouseId);
    }

    public Sku getSku(String skuId) {
        Sku sku = skus.get(skuId);
        if (sku == null) {
            throw new IllegalArgumentException("SKU 不存在: " + skuId);
        }
        return sku;
    }

    public InventoryLine query(String skuId, String warehouseId) {
        if (!isWarehouseAllowed(warehouseId)) {
            throw new IllegalArgumentException("仓位不在白名单: " + warehouseId);
        }
        getSku(skuId); // 校验 SKU 存在
        InventoryLine line = lines.get(key(warehouseId, skuId));
        if (line == null) {
            throw new IllegalArgumentException("该仓无此 SKU 库存记录");
        }
        return line;
    }

    public List<InventoryLine> listBySku(String skuId) {
        getSku(skuId);
        return lines.values().stream()
                .filter(l -> l.skuId().equals(skuId))
                .filter(l -> isWarehouseAllowed(l.warehouseId()))
                .toList();
    }
}
```

---

## 5. 工具类：InventoryTools

```java
package com.company.decision.inventory;

import com.company.decision.audit.ToolAuditLogger;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.stream.Collectors;

@Component
public class InventoryTools {

    private final InventoryStore store;
    private final ToolAuditLogger audit;

    public InventoryTools(InventoryStore store, ToolAuditLogger audit) {
        this.store = store;
        this.audit = audit;
    }

    @Tool(description = """
            根据 SKU 编号查询商品基础信息（名称、规格、单位）。
            适用于：用户只问「这是什么货」、或需要先确认 SKU 是否存在。
            不要用于查询库存数量——数量请用 queryInventory。
            本系统只读，不能修改任何数据。
            """)
    public String getSku(
            @ToolParam(description = "SKU 编号，如 SKU-1001") String skuId) {
        long start = System.currentTimeMillis();
        try {
            var sku = store.getSku(skuId);
            audit.record("getSku", "skuId=" + skuId, true, System.currentTimeMillis() - start);
            return String.format("SKU=%s，名称=%s，规格=%s，单位=%s",
                    sku.skuId(), sku.name(), sku.spec(), sku.unit());
        } catch (Exception e) {
            audit.record("getSku", "skuId=" + skuId, false, System.currentTimeMillis() - start);
            return "查询失败：" + e.getMessage() + "。请勿编造商品信息。";
        }
    }

    @Tool(description = """
            查询指定仓库、指定 SKU 的库存快照：可用量、在途量、数据更新时间。
            适用于：用户明确给了仓和 SKU，问「有多少库存」。
            warehouseId 必须是 WH-A、WH-B、WH-C 之一。
            只读；禁止用于修改库存。
            """)
    public String queryInventory(
            @ToolParam(description = "SKU 编号") String skuId,
            @ToolParam(description = "仓库编码：WH-A/A仓、WH-B/B仓、WH-C/C仓") String warehouseId) {
        long start = System.currentTimeMillis();
        String summary = "skuId=" + skuId + ",warehouseId=" + warehouseId;
        try {
            var line = store.query(skuId, warehouseId);
            audit.record("queryInventory", summary, true, System.currentTimeMillis() - start);
            return String.format(
                    "仓=%s(%s)，SKU=%s，可用=%d%s，在途=%d，截至=%s",
                    line.warehouseId(), line.warehouseName(), line.skuId(),
                    line.availableQty(), line.inTransitQty(), line.updatedAt());
        } catch (Exception e) {
            audit.record("queryInventory", summary, false, System.currentTimeMillis() - start);
            return "查询失败：" + e.getMessage();
        }
    }

    @Tool(description = """
            对比同一 SKU 在各仓的可用库存，给出哪个仓更紧缺、哪个更适合补货来源参考。
            适用于：用户问「哪个仓该补货」「A B 仓对比」且没有指定单仓。
            只读；不执行任何库存调整。
            """)
    public String compareWarehouses(
            @ToolParam(description = "要对比的 SKU 编号") String skuId) {
        long start = System.currentTimeMillis();
        try {
            var lines = store.listBySku(skuId).stream()
                    .sorted(Comparator.comparingInt(InventoryStore.InventoryLine::availableQty))
                    .toList();
            audit.record("compareWarehouses", "skuId=" + skuId, true, System.currentTimeMillis() - start);
            if (lines.isEmpty()) {
                return "无可用仓库存数据";
            }
            String table = lines.stream()
                    .map(l -> l.warehouseId() + "(" + l.warehouseName() + "):可用="
                            + l.availableQty() + ",在途=" + l.inTransitQty())
                    .collect(Collectors.joining("；"));
            var lowest = lines.getFirst();
            return "各仓快照：" + table + "。可用量最低的是 "
                    + lowest.warehouseId() + "，补货优先级通常更高（需结合在途与业务规则）。";
        } catch (Exception e) {
            audit.record("compareWarehouses", "skuId=" + skuId, false, System.currentTimeMillis() - start);
            return "对比失败：" + e.getMessage();
        }
    }
}
```

注意：**没有** `updateInventory`。模型若「幻想」出写工具，也调不到。

---

## 6. 审计：ToolAuditLogger

```java
package com.company.decision.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ToolAuditLogger {

    private static final Logger log = LoggerFactory.getLogger(ToolAuditLogger.class);

    public void record(String toolName, String paramSummary, boolean success, long costMs) {
        log.info("[TOOL] name={} params={} success={} costMs={}",
                toolName, paramSummary, success, costMs);
    }
}
```

【红线】只记参数**摘要**，不记完整身份证、密钥。

---

## 7. ChatClient 配置与 System Prompt

```java
package com.company.decision.config;

import com.company.decision.inventory.InventoryTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfig {

    public static final String INVENTORY_SYSTEM = """
            你是制造业企业的库存决策助手，服务对象是管理层。

            【输出结构】结论 → 关键数字 → 依据 → 风险 → 建议下一步

            【红线——必须遵守】
            1. 所有库存数字必须来自工具返回，禁止编造。
            2. 本系统只读，没有任何修改库存的工具。
            3. 若用户要求改库存、下单、调账，明确拒绝并说明需走正式流程。
            4. 工具查不到时，回答「暂无数据」，不要猜测。
            5. 涉及仓位时，只接受 WH-A、WH-B、WH-C；用户说「A仓」对应 WH-A。

            【工具使用提示】
            - 问单仓数量：queryInventory
            - 问商品是什么：getSku
            - 问多仓对比/补货：compareWarehouses 或多次 queryInventory
            """;

    @Bean
    ChatClient inventoryAgent(ChatClient.Builder builder, InventoryTools inventoryTools) {
        return builder
                .defaultSystem(INVENTORY_SYSTEM)
                .defaultTools(inventoryTools)
                .defaultAdvisors(new SimpleLoggerAdvisor())  // 观察每轮 prompt/response
                .build();
    }
}
```

`SimpleLoggerAdvisor` 会把每轮发给模型的消息打到 DEBUG 日志——**观察循环最省事的方式**。

---

## 8. Controller

```java
package com.company.decision.web;

import com.company.decision.config.AgentConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent")
public class InventoryAgentController {

    private final ChatClient chatClient;

    public InventoryAgentController(ChatClient inventoryAgent) {
        this.chatClient = inventoryAgent;
    }

    public record AskRequest(String question) {}

    public record AskResponse(String answer) {}

    @PostMapping("/inventory/ask")
    public AskResponse ask(@RequestBody AskRequest request) {
        String answer = chatClient.prompt()
                .user(request.question())
                .call()
                .content();
        return new AskResponse(answer);
    }
}
```

启动类：

```java
package com.company.decision;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class InventoryAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(InventoryAgentApplication.class, args);
    }
}
```

---

## 9. 怎么观察工具循环（老师手把手）

### 9.1 看 `[TOOL]` 审计行

```bash
export DEEPSEEK_API_KEY=你的密钥
mvn spring-boot:run
```

```bash
curl -s -X POST http://localhost:8080/api/agent/inventory/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"A 仓 SKU-1001 现有多少可用库存？"}'
```

期望日志类似：

```text
[TOOL] name=queryInventory params=skuId=SKU-1001,warehouseId=WH-A success=true costMs=...
```

若模型先 `getSku` 再 `queryInventory`，你会看到**两行** `[TOOL]`，中间还有一次模型调用——这就是循环。

### 9.2 看 SimpleLoggerAdvisor 的 DEBUG

`org.springframework.ai.chat.client.advisor` 打到 DEBUG 后，能看到：

1. 第一轮：user + tool definitions
2. 第二轮：带上 `ToolResponseMessage` 的完整历史
3. 最终 assistant 文本

### 9.3 理解「几次模型调用」

| 用户问题 | 常见工具轨迹 | 大约几次模型 API |
|---|---|---|
| A 仓 SKU-1001 多少库存？ | `queryInventory` 一次 | 2 次（先要工具，再总结） |
| 对比 A、B 仓 SKU-1001 | `compareWarehouses` 或两次 `queryInventory` | 2～3 次 |
| 改库存为 0 | **无工具** | 1 次（直接拒绝） |

---

## 10. 三个必测问题（现在就测）

```bash
# 1. 单仓
curl -s -X POST http://localhost:8080/api/agent/inventory/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"A 仓 SKU-1001 现有多少可用库存？"}'

# 2. 对比
curl -s -X POST http://localhost:8080/api/agent/inventory/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"对比 A 仓和 B 仓 SKU-1001，哪个更该补货？"}'

# 3. 拒写（不应出现任何写工具日志）
curl -s -X POST http://localhost:8080/api/agent/inventory/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"帮我把 A 仓 SKU-1001 库存改成 0"}'
```

第三次：回答必须拒绝；日志里**没有** `updateInventory`；`[TOOL]` 可以为空或只有查询类工具。

---

## 11. 常见卡点

| 现象 | 原因 | 处理 |
|---|---|---|
| 模型编造数字 | system 不够硬或没调工具 | 加强红线；检查 tools 是否传入 |
| 工具从不执行 | `tool-calling.enabled=false` | 改回 true |
| 报 ToolCallback 找不到 | 工具名不匹配或未 `.tools()` | 检查 `@Tool` 方法是否在同一 Bean 被注册 |
| 复制 1.x 的 `.toolNames()` | 2.0 已删 | 用 `defaultTools` |
| 启动报 API Key | 环境变量未设 | `export DEEPSEEK_API_KEY=...` |

---

## 12. 【红线】自检清单

- [ ] 只有 `getSku` / `queryInventory` / `compareWarehouses`
- [ ] `warehouseId` 白名单校验在 Java 里，不靠 prompt
- [ ] 工具异常返回中文，无堆栈给模型
- [ ] `[TOOL]` 日志含名称与参数摘要
- [ ] 拒写问题不调写工具

---

## 13. 下一步

工具能跑了，但要写得像企业：  
`03-描述参数失败超时与权限.md`

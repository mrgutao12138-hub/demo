# 07 · MCP 是什么，以及本周了解到哪

> MCP 很重要，但**不是你这两周的主路**。  
> 先把本地 `@Tool` 写稳，再谈「远程工具插座」。

---

## 1. 一句话定义

**MCP（Model Context Protocol）** 是一套标准协议：  
让 AI 应用像插 USB 一样，从**外部进程/服务**发现工具、资源、提示词。

对你而言：

- **客户端**：你的 Spring Boot Agent 去连别人的 MCP Server，用它的工具
- **服务端**：你把 Java 能力用 `@McpTool` 暴露出去，给 IDE 或其它 Agent 用

和本章 `@Tool` 的关系：**远端 MCP 工具最终也变成 `ToolCallback`**，进同一个 `ToolCallingAdvisor` 循环。

---

## 2. 本周只需建立的心智模型

```
┌─────────────────────┐         MCP 协议          ┌─────────────────────┐
│  你的 ChatClient     │ ◄──── Streamable HTTP ──► │  外部 MCP Server     │
│  ToolCallingAdvisor  │      （2.0 默认传输）       │  （文档、工单、搜索）  │
└──────────┬──────────┘                           └─────────────────────┘
           │
           ▼
   SyncMcpToolCallbackProvider
   （把远程工具包装成 ToolCallback）
```

**三个必记事实：**

1. 加 `spring-ai-starter-mcp-client` **不会**自动把 MCP 工具挂到 `ChatClient`
2. 要注入 `SyncMcpToolCallbackProvider`，再 `.tools(mcpTools)` 或 `defaultTools`
3. 默认传输是 **Streamable HTTP**（stdio 仍可用于本地子进程）

---

## 3. 最小客户端示例（概念验证，非本周作业）

### 3.1 依赖

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client</artifactId>
</dependency>
```

### 3.2 配置（示例：连本地 stdio 服务，仅演示）

```yaml
spring:
  ai:
    mcp:
      client:
        stdio:
          connections:
            demo-server:
              command: npx
              args: "-y,@modelcontextprotocol/server-everything"
```

生产更常见 **Streamable HTTP** 指向内网网关——part-09 扩展再展开。

### 3.3 显式挂到 ChatClient

```java
@Configuration
class McpAgentConfig {

    @Bean
    ChatClient inventoryPlusMcp(ChatClient.Builder builder,
                                InventoryTools localTools,
                                SyncMcpToolCallbackProvider mcpTools) {
        return builder
                .defaultSystem(AgentConfig.INVENTORY_SYSTEM)
                // 本地 + 远程可混用
                .defaultTools(localTools, mcpTools)
                .build();
    }
}
```

【红线】MCP 工具来自外部，表面不可控：

- 用 `McpToolFilter` 限制暴露哪些远程工具
- 避免与本地 `@Tool` **重名**
- 仍走你的审计与权限策略（能拦则拦，不能拦就别接）

---

## 4. 暴露自己的服务：@McpTool（知道即可）

若别人要连**你的**库存能力（不是本周任务）：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
</dependency>
```

```java
@Component
public class InventoryMcpTools {

    @McpTool(description = "查询指定仓 SKU 可用库存（只读）")
    public String queryInventory(
            @McpToolParam(description = "SKU 编号") String skuId,
            @McpToolParam(description = "仓库 WH-A/WH-B/WH-C") String warehouseId) {
        // 复用 InventoryStore，仍要白名单校验
        ...
    }
}
```

`@McpTool` 在 **Server 侧**；Client 侧是 `SyncMcpToolCallbackProvider`。别混成一个注解。

---

## 5. 本周了解到哪（边界清晰）

| 本周要做 | 本周不做 |
|---|---|
| 理解 MCP = 远程工具插座 | 把库存 Agent 主路径改成 MCP |
| 知道要 `SyncMcpToolCallbackProvider` + `.tools()` | 配生产级 MCP 网关与鉴权 |
| 知道 Streamable HTTP 为默认传输 | 写 MCP Server 给全公司用 |
| 知道本地 `@Tool` 与 MCP 可 `.tools(local, mcp)` 混用 | Tool Search + 多 MCP 联调 |

**主路径仍是：** `InventoryTools` + `@Tool` + `ToolCallingAdvisor`。

MCP 放到 `extensions/` 和 part-09 深水；等你 L3 过关再玩。

---

## 6. 和 2.0 其它变更的交叉

- 没有 `SpringBeanToolCallbackResolver`；MCP 也不是按名字自动解析
- `tool-calling.enabled=false` 时，MCP 工具同样不会自动执行
- 工具循环仍在 `ToolCallingAdvisor`，MCP 不改变循环位置

---

## 7. 口试

1. 加了 mcp-client starter 后，ChatClient 能直接用到远程工具吗？为什么？
2. `@Tool` 和 `@McpTool` 分别站在哪一侧？
3. 为什么本周不让 MCP 当主路径？

---

## 8. 下一步

`08-本阶段验收三个必测问题.md` —— 过关。

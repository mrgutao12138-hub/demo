# 扩展 05 · MCP 深度与 Agent Skills

```
┌────────────────────────────────────────────────────────────────────┐
│  不要在学主线时插入本章。                                            │
│  MCP 是工具互操作协议，应在 part-04 工具循环与 part-08 综合项目之后再学。 │
│  先能把 @Tool 写稳，再谈把工具拆成独立 MCP Server。                   │
└────────────────────────────────────────────────────────────────────┘
```

> 同学们，MCP 这三个字母在 2025～2026 年被炒得很热。  
> 有人以为 MCP 是「又一个比 GPT 更强的模型」——错得离谱。  
> 这一篇把它讲透：MCP 是什么、Spring 官方 SDK 2.0 怎么用、和主线 `@Tool` 什么关系、  
> Agent Skills 又是什么（点到为止），以及 **安全上绝不能把远程工具当内网免检**。

---

## 1. MCP 是什么：不是大模型，是互操作协议

**MCP（Model Context Protocol）** 是一套开放协议，用来让 **AI 应用（Client）** 与 **外部能力提供方（Server）** 标准化地交换：

| 能力类型 | 协议概念 | 类比 |
|---|---|---|
| 工具 | Tools | 你熟悉的 `@Tool`，但可能跑在另一个进程/团队/语言 |
| 资源 | Resources | 可读的文件、配置片段、数据库视图元数据 |
| 提示 | Prompts | 可复用的提示模板，由 Server 暴露 |

**MCP 不包含推理。** 大模型仍在 Client 侧（你的 Spring Boot + ChatClient）。MCP Server 只说：「我提供这些工具和资源，按协议调用我。」

### 1.1 为什么需要它

企业里常见痛点：

- WMS 查询写在 Java，算法组工具写在 Python，IDE 插件又要一套——**重复封装**
- 供应商交付「Agent 工具包」，你希望 **进程隔离**、独立扩缩容
- 安全审计要求 **工具边界清晰**，谁提供、谁鉴权、谁记日志

MCP 的目标是 **一次实现、多处消费**（IDE、Agent 编排器、别的 team's Client）。

### 1.2 和主线 `@Tool` 的关系

```
主线 part-04：
  ChatClient → ToolCallingAdvisor → 本 JVM 内 @Tool 方法

引入 MCP 后：
  ChatClient → ToolCallingAdvisor → SyncMcpToolCallbackProvider
                                           │
                                           ▼ HTTP / STDIO
                                    远程 MCP Server（如 WMS 查询服务）
```

**决策逻辑仍在你的 Java Agent；MCP 只是把「手」伸到更远的地方。**

---

## 2. Spring 生态：官方 MCP Java SDK 2.0

Spring 官方维护 **MCP Java SDK 2.0**，与 **Spring AI 2.0** 同代。规范方面，讲义对齐 **2025-11-25** 日前后的 MCP 规范演进（Streamable HTTP 成为默认传输方向之一，详见下文）。

你在工程中常见两类角色：

| 角色 | 依赖方向 | 你做什么 |
|---|---|---|
| MCP Server | `spring-ai-starter-mcp-server` 等 | 用注解暴露工具/资源/提示 |
| MCP Client | `spring-ai-starter-mcp-client` | 消费远程 Server，把工具接进 ChatClient |

**学习顺序：** 先在本进程写 `@Tool`（part-04）→ 再把同一个能力拆成 MCP Server（本章）→ Client 用 `SyncMcpToolCallbackProvider` 接入。

---

## 3. Server 端注解：@McpTool、@McpResource、@McpPrompt

### 3.1 @McpTool

与 `@Tool` 类似，但暴露给 MCP 协议而非直接给 Spring AI 本地扫描：

```java
@Component
public class WmsMcpTools {

    @McpTool(description = "按物料编码查询 WMS 可用库存")
    public int queryAvailableStock(
            @McpToolParam(description = "物料编码") String materialCode) {
        return wmsReadService.available(materialCode);
    }
}
```

Server 启动后，注册到 MCP 协议层，等待 Client 发现与调用。

### 3.2 @McpResource

暴露 **可读资源**，例如制度 PDF 的元数据、指标字典片段：

```java
@McpResource(
        uri = "metrics://inventory-turnover-definition",
        name = "库存周转定义",
        description = "公司口径下的库存周转计算公式")
public String inventoryTurnoverDefinition() {
    return metricDictionary.get("inventory_turnover");
}
```

Client 可在对话前 **拉取资源** 丰富上下文，类似「可寻址的知识片段」。

### 3.3 @McpPrompt

暴露可复用提示模板：

```java
@McpPrompt(
        name = "weekly-ops-summary",
        description = "生成本周运营摘要的提示骨架")
public String weeklyOpsSummaryPrompt(
        @McpPromptParam(description = "周次，如 2026-W10") String week) {
    return """
            请基于工具查询结果，生成本周运营摘要。
            周次：%s
            结构：结论 / 关键指标 / 风险 / 建议
            """.formatted(week);
}
```

**老师提醒：** 提示模板仍在 Server 侧版本化，Client 不要硬编码一长串 system prompt 复制粘贴。

---

## 4. Client 端：显式 .tools(SyncMcpToolCallbackProvider)

主线里 `.defaultTools(myBean)` 扫描本地 `@Tool`。接 MCP 时，必须 **显式** 把远程工具注册进 `ChatClient`：

```java
@Configuration
public class McpClientConfig {

    @Bean
    ChatClient mcpAwareChatClient(
            ChatClient.Builder builder,
            SyncMcpToolCallbackProvider mcpTools) {
        return builder
                .defaultSystem("你是决策助手。需要库存时调用 WMS 工具。")
                .defaultTools(mcpTools)
                .build();
    }
}
```

`SyncMcpToolCallbackProvider` 由 MCP Client starter 根据你配置的 Server 地址/传输方式装配。**不会**魔法般自动进所有 ChatClient——避免无意把生产 WMS 工具暴露给测试 Agent。

### 4.1 配置示意（Streamable HTTP）

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: true
        streamable-http:
          connections:
            wms-server:
              url: https://wms-mcp.internal.company.com
              # 鉴权头、超时等按 SDK 文档配置
```

具体 key 以你使用的 `spring-ai-starter-mcp-client` 版本文档为准；原则是 **连接名 → Server 端点 → 同步或异步工具回调**。

### 4.2 调用链（心智图）

```
用户问题
   ▼
ChatClient + ToolCallingAdvisor
   ▼
模型返回 tool_call: queryAvailableStock
   ▼
SyncMcpToolCallbackProvider
   ▼
MCP Client 协议层 → HTTP → WMS MCP Server
   ▼
工具结果字符串回到模型
   ▼
最终自然语言答案
```

与本地 `@Tool` 相比，多了一跳网络与序列化——**超时、重试、熔断**必须配置（part-07 横切能力）。

---

## 5. 传输方式：Streamable HTTP、SSE、STDIO

### 5.1 Streamable HTTP（默认方向）

新版 MCP 规范推动 **Streamable HTTP** 作为远程传输的现代化默认（相对传统纯 SSE 分裂端点）。Spring MCP SDK 2.0 跟进这一方向。

**直觉：** 一个 HTTP 对话通道，既可流式也可承载请求/响应，利于穿透企业网关、统一鉴权。

### 5.2 SSE（Server-Sent Events）

早期 MCP 远程部署常见 SSE。**规范演进上 SSE 呈弃用/弱化方向**——不是明天就不能用，但新项目应优先查 Streamable HTTP 支持情况，避免刚上线就背技术债。

### 5.3 STDIO（标准输入输出）

本地进程模式：Client 启动 Server 子进程，用 stdin/stdout 传 MCP 消息。

```
你的 IDE / 本地 Agent 进程
        │ STDIO
        ▼
   npx @some/mcp-server
   或 java -jar wms-mcp-server.jar
```

适合 **本机开发、CI 沙箱**；生产跨机房更常用 HTTP。

### 5.4 选型简表

| 传输 | 适用 | 注意 |
|---|---|---|
| Streamable HTTP | 企业内网远程 Server | 网关、TLS、mTLS |
| SSE | 旧 Server 兼容 | 规划迁移 |
| STDIO | 本地工具、IDE | 进程生命周期与资源隔离 |

---

## 6. 企业用法：WMS 查询做成独立 MCP Server

### 6.1 场景

辅助决策系统（Client）需要查 WMS 库存、在途、库龄。WMS 团队不愿把你的 Agent 依赖打进同一个胖 jar。

### 6.2 拆分

```
┌─────────────────────────┐         MCP          ┌─────────────────────────┐
│ 决策 Agent (Spring Boot) │ ◄──── HTTP ────────► │ WMS MCP Server          │
│  ChatClient             │   Tools / Resources   │  只读仓储 API 封装       │
│  RAG / 记忆 / 审计       │                       │  独立部署、独立扩缩容     │
└─────────────────────────┘                       └─────────────────────────┘
```

WMS Server：

- 只暴露 **只读** 工具
- 内部做 WMS API 鉴权、限流、字段脱敏
- 版本发布不影响 Agent 主应用（只要协议兼容）

决策 Client：

- 路由、提示、多模型、管理层 UI
- 通过 `SyncMcpToolCallbackProvider` 消费 WMS 工具
- **审计日志记录「调了哪个 MCP 工具、参数哈希、耗时」**

### 6.3 与宽表模式（part-06）如何共存

- 演示期：Agent 直查讲义 Postgres 宽表 + 本地 `@Tool` 即可，**不必先 MCP**
- 多系统期：ERP/WMS/EMS 各团队提供 MCP Server，Agent 做编排——**组织问题大于技术问题**

---

## 7. Agent Skills 概念（点到为止）

### 7.1 是什么

**Agent Skills** 可理解为 **可移植的技能包**：把「提示片段 + 工具声明 + 可选资源引用 + 使用说明」打成一份包，在不同 Agent 运行时加载。

与 MCP 的关系（简化）：

- MCP 管 **运行时如何调用远程能力**
- Skills 管 **能力组合与使用文档如何打包、分发、版本化**

### 7.2 spring-ai-agent-utils 社区

社区有 **spring-ai-agent-utils** 一类项目，探索 Skills 目录结构、加载器、与 Spring AI 集成。**讲义不把它当主线依赖**，原因：

- 生态仍在快速变化
- 企业第一期应先把 `@Tool` / MCP Server 边界打清

**你若好奇：** 主线合格后可以浏览其 README 级别文档，做一个「加载本地 SKILL.md + 调 ChatClient」的小实验即可，不要替代 part-08 综合项目。

### 7.3 老师的态度

Skills 是有趣的 **分发层**；MCP 是有趣的 **调用层**。决策系统成败仍取决于：**数据口径、权限、评测集**——不是技能包名字有多酷。

---

## 8. 安全：MCP 工具同样要鉴权

### 8.1 远程工具不是内网免检

常见事故幻想：

> 「MCP Server 在内网，所以不用鉴权。」

错。内网横向移动、误配置 ACL、供应链篡改 Server jar 都会发生。

**必须做：**

- Client → Server：**mTLS 或 Bearer Token**，密钥轮换
- Server → 下游 WMS/DB：**服务账号最小权限**，只读
- **参数校验**：Server 内重复校验，不信任 Client 传来的 SKU
- **审计**：谁、何时、调了什么工具、返回行数级别
- **速率限制**：防止 Agent 循环疯狂打 WMS

### 8.2 与主线安全原则一致

part-07 的红线对 MCP 加倍适用：

- 模型不能绕过工具直接「猜库存」
- 工具默认只读
- 敏感字段出 MCP 响应前脱敏

### 8.3 供应链

MCP Server 依赖第三方包时，走公司制品库扫描。不要用不明来源的「一键 MCP 神器」连生产。

---

## 9. 观测与测试

### 9.1 测试金字塔

1. **Server 单测**：`@McpTool` 方法对假仓储
2. **协议集成测**：Testcontainers 或 mock MCP 通道
3. **Agent 端到端**：评测集含「必须走 WMS 工具」的问法

### 9.2 指标

- `mcp.tool.call.duration`
- `mcp.tool.call.errors`
- 与 `chat.client.token.usage` 联合看成本

---

## 10. 从 @Tool 迁移到 MCP Server 的最小路径

1. 把现有 `InventoryTools` 类原样复制到 `wms-mcp-server` 工程
2. `@Tool` 改 `@McpTool`（或保留业务逻辑，外层多一层适配）
3. Server 独立启动，Health 检查通过
4. Agent 工程删本地 `@Tool` 库存方法，改 `SyncMcpToolCallbackProvider`
5. **同一评测集**跑回归——行为应与迁移前等价

若评测通过率下降，先查 **工具描述字符串** 是否一致（模型选工具靠描述）。

---

## 11. 和 Spring AI 2.0 Advisor 链的配合

- `ToolCallingAdvisor` **不区分** 本地与 MCP 工具，都是 `ToolCallback`
- 可在 Advisor 链加 **仅 MCP 场景的审计 Advisor**
- `MessageChatMemoryAdvisor` 仍建议放循环外；MCP 多轮调用别污染记忆
- 大量 MCP 工具时，考虑 **Tool Search Advisor**（part-04 提过）按需披露

---

## 12. 常见误区答疑

| 误区 | 正解 |
|---|---|
| 上了 MCP 就不用写 Java 工具了 | Server 里仍是 Java（或其它语言）业务代码 |
| MCP 替代 RAG | 不替代；Resources 可补充，向量检索仍可能是主路径 |
| 每个 `@Tool` 都要立刻拆 MCP | 否；先单体，边界清晰再拆 |
| Client 会自动发现全网 MCP | 否；显式配置连接与 `SyncMcpToolCallbackProvider` |
| SSE 是长期默认 | 关注 Streamable HTTP，规划迁移 |

---

## 13. MCP Server 最小可运行工程骨架（Java）

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

```yaml
spring:
  ai:
    mcp:
      server:
        enabled: true
        name: wms-readonly-server
        version: 1.0.0
```

```java
@SpringBootApplication
public class WmsMcpServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(WmsMcpServerApplication.class, args);
    }
}
```

业务工具类仍是你熟悉的 Service + `@McpTool`。**先在本机 STDIO 或 HTTP 用官方 MCP Inspector 类工具测通**，再让 Agent Client 连接。

---

## 14. Client 与 Server 同仓库 vs 分仓库

| 方式 | 优点 | 缺点 |
|---|---|---|
| 同仓库多模块 | 初学快 | 发布边界糊 |
| Server 独立仓库 | 权责清晰 | 需契约测试 |
| 多 Server 一 Client | 领域拆分 | 连接配置多 |

part-08 之后 PoC 可用单仓库；生产 **WMS、EMS 各一 Server 仓库** 更常见。

---

## 15. Streamable HTTP 与 SSE：迁移检查单

若你维护的 MCP Server 仍只支持 SSE：

1. 查 Spring MCP SDK  release note 是否已支持 Streamable HTTP Server 端  
2. 在测试环境并排暴露两种传输（若允许）  
3. Client 先切 Streamable HTTP，保留 SSE 回滚一周  
4. 防火墙规则：SSE 长连接超时可能与网关默认 60s 冲突——Streamable HTTP 往往更好过企业网关  

**讲义立场：** 新立项默认 Streamable HTTP；SSE 仅兼容旧 Client。

---

## 16. STDIO 本地开发流程（与 IDE 生态）

1. `java -jar wms-mcp-server.jar --spring.ai.mcp.server.stdio.enabled=true`（选项名以文档为准）  
2. Cursor / 其它 IDE 的 MCP 配置里填 command + args  
3. 开发者在 IDE 里调试 WMS 工具，**与 Spring Agent 无关**  
4. Agent 集成时改为 HTTP 指向部署在测试环境的同一 Server  

STDIO **不适合** -kubernetes 默认部署，适合 **工程师本机**。

---

## 17. MCP Resource 与 RAG 的分工

| 手段 | 适合 | 不适合 |
|---|---|---|
| RAG 向量检索 | 大量 PDF、制度全文 | 实时库存数字 |
| MCP Resource | 小体量、权威、低频更新的定义 | 百万行日志 |
| `@Tool` 查库 | 实时、权限强 | 给 IDE 复用 |

决策系统：**数字走 Tool（或 MCP Tool）**，制度走 RAG，指标定义可走 MCP Resource。

---

## 18. Agent Skills 目录结构（概念示意，非标准最终稿）

社区探索中的 Skills 包可能长这样：

```text
skills/
  weekly-ops-summary/
    SKILL.md          # 人类可读：何时用、输入输出
    prompts/
      system.txt
    tools.json        # 引用的 MCP 工具名
```

加载器读取 `SKILL.md`，注入 ChatClient 的 system 片段，并注册对应 MCP 工具子集。  
**spring-ai-agent-utils** 等社区项目在做标准化——讲义要求你 **知道概念即可**，第一期交付不依赖 Skills 框架。

---

## 19. 安全纵深：零信任清单

- [ ] MCP Server 不监听 `0.0.0.0` 除非前面有网关  
- [ ] Client 凭证轮换 ≤ 90 天  
- [ ] Server 到 WMS 用只读账号  
- [ ] 工具参数白名单（SKU 格式、日期范围）  
- [ ] 响应行数上限，防「一次拉全表」  
- [ ] 全链路 traceId 从 HTTP 传入 MCP 元数据  
- [ ] 渗透测试包含 **伪造 MCP Server** 场景  

**远程工具不是内网免检**——这句话 worth 重复第三遍。

---

## 20. 故障排查

| 现象 | 排查 |
|---|---|
| Client 启动无工具 | `SyncMcpToolCallbackProvider` 是否 `.defaultTools()`；连接配置是否 enabled |
| 工具超时 | Server 下游 WMS 慢；调大 timeout；检查 Agent 循环次数 |
| 工具名对不上 | Server 与 Client 版本不一致；重新 discover |
| STDIO 进程僵死 | 健康检查杀进程重启；限制并发 |
| HTTP 403 | 鉴权头、mTLS 证书过期 |

---

## 21. MCP 与 part-04 学习对照表

| part-04 概念 | MCP 对应 |
|---|---|
| `@Tool` | `@McpTool` on Server |
| `ToolCallingAdvisor` | Client 侧不变 |
| `ToolCallback` | `SyncMcpToolCallbackProvider` 提供 |
| 工具 description | 仍决定模型是否选用 |
| returnDirect | Client 侧 Advisor 行为仍适用 |

学完 part-04 再读本节，应有「只是手变长了」的感觉，而不是全新世界观。

---

## 22. 课堂答疑

**问：MCP 会取代 Spring AI 吗？**  
答：不会。MCP 是协议；Spring AI 是应用框架。

**问：每个 @Tool 都要拆 Server 吗？**  
答：否。边界按 **团队与变更频率** 切，不是按注解个数。

**问：能和 Kafka 事件集成吗？**  
答：可以。MCP Tool 内部发事件或查事件溯源库——协议不限制实现。

**问：Skills 要不要写进简历？**  
答：写「熟悉 MCP 工具互操作」比「会用某 Skills 文件夹」更稳。

---

## 23. 企业级 WMS MCP Server 分层设计（详解）

把 part-06 的只读宽表访问，拆成 MCP Server 时，老师建议 **四层**：

```
┌─────────────────────────────────────┐
│ 协议层  MCP SDK @McpTool 暴露        │
├─────────────────────────────────────┤
│ 应用层  WmsQueryApplicationService   │  ← 参数校验、组合查询
├─────────────────────────────────────┤
│ 领域层  库存聚合、库龄计算（纯 Java）  │
├─────────────────────────────────────┤
│ 基础设施  只读仓储 / 宽表 JDBC        │
└─────────────────────────────────────┘
```

**协议层** 不要写 SQL。  
**应用层** 做 SKU 白名单、日期范围检查。  
**领域层** 与 Agent 无关，单元测试覆盖。  
**基础设施** 连接池、超时与 part-06 一致。

决策系统 Client 只连协议层 HTTP；WMS 团队发版 Server，Agent 团队不必重发 jar（只要 MCP 契约版本兼容）。

---

## 24. SyncMcpToolCallbackProvider 与异步变体

讲义强调 **Sync** 是因为管理层接口多为 Servlet 同步线程。若你用 WebFlux 全链路异步，查 SDK 是否提供 **异步 ToolCallback** 实现，避免在 event loop 上 block HTTP 调 MCP。

无论同步异步，**超时** 必须设：

```java
// 概念示意：在 MCP Client 配置或 RestClient 层
// connectTimeout / readTimeout 略小于 ChatClient 总超时
```

否则 Agent 循环会卡死在远程 WMS 上。

---

## 25. MCP Prompt 与系统提示的分工

- **系统提示（Client 侧 `defaultSystem`）**：角色、红线、输出格式，跟用户会话绑在一起。  
- **MCP Prompt（Server 侧 `@McpPrompt`）**：可版本化的任务模板，如「周报」「月度安全巡检」。

Client 在跑特定任务前，可先 **fetch MCP Prompt** 再拼进 user 消息。  
这样 WMS 团队更新周报模板，只发 Server 版本，不用改 Agent 的 Git。

---

## 26. 规范日期 2025-11-25：学员需要记住什么

不需要背规范全文。记住三点即可：

1. **Streamable HTTP** 是远程 MCP 的现代化方向。  
2. **SSE** 老项目可能还在用，规划迁移。  
3. **STDIO** 仍是本地/IDE 场景主力。

Spring 官方 MCP Java SDK 2.0 与 Spring AI 2.0 同代升级；锁 `spring-ai-bom:2.0.0` 时，MCP starter 版本跟着 BOM，不要手写杂版本。

---

## 27. Agent Skills 与 MCP 组合的未来形态（展望，不纳入考试）

可以想象的路径：

1. Skills 包声明「需要哪些 MCP Server 连接」。  
2. Agent 启动时加载 Skills，自动注册对应 `SyncMcpToolCallbackProvider` 子集。  
3. 人类可读 `SKILL.md` 给运维与审计看「这个 Agent 会被允许做什么」。

**今天** 你们用 Java 配置 + 评测集 + 鉴权就能上线；Skills 标准化成熟后再迁移不迟。  
**spring-ai-agent-utils** 社区值得观望，不是主线依赖。

---

## 28. 红队视角：MCP 攻击面

| 攻击 | 后果 | 防护 |
|---|---|---|
| 伪造 Server DNS | 工具结果投毒 | TLS + 固定证书 pin |
| 重放 tool 调用 | 爬取库存 | 请求签名 + 短时效 nonce |
| 超大 tool 响应 | JVM OOM | 响应 size cap |
| 提示注入经 Server 回传 | 误导模型 | Server 输出消毒 |
| 越权参数 | 查他人 SKU | 应用层鉴权 userId |

**远程工具不是内网免检**——安全章节请打印贴在工位。

---

## 29. part-08 答辩加分项：如何讲 MCP 不显得堆砌

答辩话术：

> 我们把 WMS 只读查询拆成独立 MCP Server，决策 Agent 通过标准协议调用。  
> 这样仓储域可以独立发版，Agent 侧仍是 Spring AI 2.0 的 ChatClient 与 ToolCallingAdvisor。  
> 我们跑了与本地 @Tool 等价的评测集，pass rate 持平，P95 延迟增加 X ms，在可接受范围内。

评委听的是 **边界、评测、延迟**，不是协议缩写。

---

## 30. MCP Client 配置多 Server 示意

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: true
        streamable-http:
          connections:
            wms:
              url: https://wms-mcp.internal.example.com
            ems:
              url: https://ems-mcp.internal.example.com
```

Java 侧可注入多个 provider 或统一 facade 聚合工具名前缀，如 `wms_queryStock`、`ems_queryDowntime`，避免工具名冲突。  
**命名前缀**应在 Server 设计阶段约定，不是联调最后一天拍脑袋。

---

## 31. @McpTool 与 @Tool 并存时的团队分工

| 团队 | 职责 |
|---|---|
| Agent 团队 | Client、路由、RAG、UI、评测 |
| WMS 团队 | WMS MCP Server、下游 API、Server 端鉴权 |
| 平台团队 | 网关、mTLS、证书、日志采集 |

Agent 项目里 **可以短期并存** 本地 `@Tool`（宽表）与 MCP Tool（WMS），迁移期用 feature flag 切换；评测集两条路径都要绿才能删本地 `@Tool`。

---

## 32. 本地 @Tool 与 MCP 的性能对比记录

| 路径 | P50 | P95 | 说明 |
|---|---|---|---|
| 本地 @Tool JDBC | 40ms | 120ms | 同 JVM |
| MCP HTTP 同机房 | 55ms | 180ms | 多一跳序列化 |
| MCP HTTP 跨机房 | 200ms | 800ms | 谨慎 |

延迟增加通常可接受；**不可用** 才是红线。跨机房 MCP 要考虑 **同区域部署** Client 与 Server。

---

## 33. Skills 学习路径（可选课外）

1. 读完本篇 MCP 章节。  
2. 浏览 spring-ai-agent-utils 的示例目录结构（若有）。  
3. 手写一个 `SKILL.md` + 三个 `@McpTool`，不引入加载框架。  
4. 对比「纯 Java 配置」与「Skills 目录」优劣，写一页笔记。  

**不纳入讲义验收**；好奇再做。

---

## 34. MCP 协议三层能力再辨析（考试级理解）

老师最后把 Tools / Resources / Prompts 做成一张 **考场级** 对照：

**Tools（工具）**  
- 有副作用或查实时数据：库存、在途、设备状态。  
- 模型通过 tool call 触发，Java/MCP Server 执行后返回字符串。  
- 决策系统 80% 的「手」在这里。

**Resources（资源）**  
- 相对静态、可 URI 定位：指标定义、版本化的 SOP 摘要、配置片段。  
- Client 可先 read resource 再对话，不一定每次向量检索。  
- 适合 **小而权威** 的上下文，不适合整库 PDF。

**Prompts（提示模板）**  
- 可复用任务骨架：周报、月报、巡检话术。  
- 由 Server 版本化，Client 按名拉取。  
- 减少在 Git 里复制粘贴大段 system prompt。

三者都不是大模型；大模型仍在 Client 的 `ChatClient` 里。**MCP 是供模型使用的「外设协议」。**

---

## 35. 从 IDE MCP 到企业 Agent：同一协议的两端受众

你在 Cursor 等 IDE 里配置的 MCP Server，与企业在数据中心部署的 WMS MCP Server，**协议层同一套**，受众不同：

| 受众 | 典型 Server | Client |
|---|---|---|
| 研发 | 代码搜索、文档 | IDE |
| 业务 Agent | WMS/EMS 只读 | Spring Boot |
| 运维 | 日志、监控 | 内部运维 Agent |

学会 Spring MCP Client 后，你会看懂 IDE 的 mcp.json 配置——**扩展视野，不是学习主线要求**。

---

## 36. Streamable HTTP 与防火墙：给网络组的一段话

「我们需要应用服务器访问 `https://wms-mcp.internal:443/mcp`（路径以实际为准），协议为 MCP Streamable HTTP，长连接行为与 SSE 不同，请按厂商白皮书放通健康检查与 TLS 拦截例外。」

提前准备这段话，比联调当天抓瞎强。

---

## 37. MCP Server 鉴权实现 sketch（Bearer）

```java
@Component
public class McpAuthFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String auth = req.getHeader("Authorization");
        if (!isValidBearer(auth)) {
            res.setStatus(401);
            return;
        }
        chain.doFilter(req, res);
    }
}
```

Client 侧在 MCP HTTP 配置里加相同 Bearer。**不要用基本认证明文传生产密码。**

---

## 38. 工具描述同步：@Tool 与 @McpTool 保持一致

迁移到 MCP 时，把 1.x/2.0 本地 `@Tool` 的 `description` **原样复制**到 `@McpTool`，减少模型行为漂移。  
若描述改了，视为 **模型发布事件**，重跑评测集。

---

## 39. part-04 结业标准与 MCP 的关系

part-04 结业要求你能讲清 **ToolCallingAdvisor 一轮循环**。  
MCP 结业要求你能讲清 **Client 如何把远程 tool 注册进同一 Advisor**。  
两者都过关，才建议在 part-08 项目里加 MCP；否则答辩会被问「为什么不用本地 @Tool」而答不出边界。

---

## 40. Agent Skills 与 MCP 的「谁包装谁」

现阶段务实理解：

- **MCP** 解决「工具在哪台机器、什么协议」。  
- **Skills** 解决「人类如何打包、分发一套能力说明与依赖」。  

Skills 可以声明「本技能需要连接 wms-mcp」；MCP 不提供技能叙事。  
等社区标准稳定前，企业用 **内部 Wiki + 评测集 + 架构图** 达到同样治理效果。

---

## 41. STDIO vs HTTP：选型决策一分钟

- 本机 IDE、开发者个人实验 → STDIO  
- 数据中心、K8s、跨团队 → HTTP（Streamable HTTP 优先）  
- 车间边缘无 HTTP 服务 → 视网闸方案，可能 HTTP over 隧道， rarely STDIO over SSH

---

## 42. MCP 与 Kafka 事件驱动（扩展阅读）

某 WMS 变更可发 Kafka 事件，MCP Tool 只查 **读模型快照**，不直接订阅 Kafka——边界清晰。  
若 Tool 内发 Kafka 命令，必须 **禁止写操作** 除非经过审批服务。  
Agent 决策系统默认 Tool 只读；MCP 不能成为绕过审批的后门。

---

## 43. 多 Server 工具名冲突案例

WMS Server 暴露 `queryStock`，EMS Server 也暴露 `queryStock`，Client 合并工具时会冲突。  
约定：`wms_queryStock`、`ems_queryStock`，Server 注册时加前缀。  
联调第一天就要对齐，不要等模型乱调工具再改名。

---

## 44. spring-ai-agent-utils 学习边界（再次点到为止）

社区 utils 可能提供 Skills 加载、MCP 发现辅助。  
**讲义不要求安装**。若你贡献开源，欢迎；若你做交付，以 **评测集绿 + 鉴权齐** 为准。  
Skills 标准化成熟后，讲义 extensions 会增补一节——当前保持警觉关注即可。

---

## 45. 规范 2025-11-25：学员行动项

1. 新项目 MCP 远程传输优先 Streamable HTTP。  
2. 维护旧 SSE Server 的团队排期迁移。  
3. 购买/自建 MCP 网关前确认协议版本与 Spring MCP SDK 2.0 兼容矩阵。  

三条做完，本章「规范日期」就不只是背书，而是 **立项检查项**。

---

## 46. 收束：MCP 与 Skills 在决策系统里的优先级

对管理层辅助决策系统，优先级建议写死在架构原则里：

1. **Java 只读工具 + 宽表**（part-06）——默认  
2. **RAG 制度口径**（part-05）——默认  
3. **本地 @Tool**（part-04）——默认可维护  
4. **MCP 拆域工具**——多团队、多语言、需进程隔离时  
5. **Agent Skills 打包**——生态成熟后再评估  

MCP 不是跳过 1～3 的捷径；Skills 不是跳过 4 的魔法。  
你把 WMS 查询做成 MCP Server，是因为 **仓储域要独立发布**，不是因为「MCP 更先进」。先进与否看评测集与边界，不看协议缩写。

---

## 47. 什么时候才应该用 · 和主线如何衔接

### 什么时候才应该用

- part-04 **工具循环**已熟练，评测集稳定
- 存在 **跨团队、跨语言、跨进程** 的工具提供方
- 需要 **独立扩缩容与发布** 的工具域（WMS、算法、文档服务）
- IDE/多 Agent 要 **共享同一套企业工具**

### 不要在什么时候用

- 还没写过 `@Tool` 就想「直接企业级 MCP」
- 只有一个单体、一个团队、三个工具——**本地 `@Tool` 更简单**
- 没有 Server 端鉴权方案就联调生产 WMS

### 和主线如何衔接

| 主线 | 扩展 05 承接 |
|---|---|
| part-04 `@Tool` + `ToolCallingAdvisor` | 同款循环，工具回调换 MCP Provider |
| part-06 企业数据 | WMS MCP Server 是宽表/API 的对外包装 |
| part-07 安全·观测 | MCP 鉴权、审计、超时 |
| part-08 综合项目 | 可在答辩版加「一个 MCP Server」加分项 |
| part-09 工程化 | Server 独立 CI/CD、契约测试 |
| 扩展 03 多模型 | Client 侧编排不变，工具来源可混合本地与 MCP |

---

## 14. 收束：一句话带走

> **MCP 是把「工具」从 JVM 里搬到协议线上；  
> 模型仍在 Client；安全仍在每一跳；  
> 评测集仍是上线法官。**

先把主线 Agent 做成「可信的数 + 可解释的判断」，再用 MCP 把企业的手伸得更远、拆得更清。那时你谈的才是架构，不是追热点。

---

*扩展篇 05 · 完。extensions 系列至此与主线衔接完毕。*

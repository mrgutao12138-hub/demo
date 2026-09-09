# 扩展 01 · GPT 与 OpenAI 生态

```
┌────────────────────────────────────────────────────────────────────┐
│  不要在学主线时插入本章。                                            │
│  主线 part-02～part-09 请坚持 DeepSeek + spring-ai-starter-model-   │
│  deepseek。本章是「模型可替换」的补课，不是第三周就该换栈的理由。      │
└────────────────────────────────────────────────────────────────────┘
```

> 同学们好。主线里你们一直在用 DeepSeek，这是对的——学习期要减少变量。  
> 但走出教室以后，老板会问：「能不能换成 GPT？」「能不能走公司买的国产云网关？」  
> 这一篇就是回答这些问题。记住：**换模型是配置和评测问题，不是重写业务。**

---

## 1. 先建立一张地图：OpenAI 兼容协议是什么

行业里有一件事，Java 工程师特别容易低估它的价值：**大量云厂商把自家大模型，伪装成「长得像 OpenAI 的 HTTP API」**。

所谓 OpenAI 兼容，指的是请求与响应的形态，与 OpenAI 官方的 Chat Completions 接口一致或高度相似。你最常碰到的三个要素是：

| 要素 | 含义 | 你日常要改什么 |
|---|---|---|
| `base-url` | API 根地址 | 换厂商、换网关、换内网代理时改这里 |
| `api-key` | 鉴权密钥 | 每家账号独立，绝不写进 Git |
| `/v1/chat/completions` | 对话补全路径 | 多数兼容网关沿用这条路径，少数要查文档 |

用你熟悉的 Spring MVC 类比：

- `base-url` 像 `server.servlet.context-path` 之上的「服务根」
- `api-key` 像 `Authorization` 头里的令牌
- `chat/completions` 像固定的 REST 资源路径

Spring AI 的 OpenAI starter 底层就是按这套协议发 HTTP。所以当你把 `base-url` 指到某国产云的「OpenAI 兼容入口」时，**Java 代码往往一行不用动**，变的只是 yml 和回归评测。

### 1.1 一次请求的骨架（建立直觉）

你不用手写 HTTP，但心里要有这张图：

```
POST {base-url}/v1/chat/completions
Headers:
  Authorization: Bearer {api-key}
  Content-Type: application/json
Body:
  model: "gpt-4o" 或厂商自己的模型名
  messages: [ { role: "system", ... }, { role: "user", ... } ]
  temperature: 0.3
  tools: [...]        // 若启用工具调用
  response_format: ... // 若启用结构化输出
```

响应里会有 `choices[0].message`，可能是纯文本，也可能带 `tool_calls`。Spring AI 把这些翻译成内部的 `ChatResponse`、`AssistantMessage`、`ToolCall`——**你在业务里面对的是 `ChatClient`，不是 JSON**。

### 1.2 兼容不等于一模一样

老师必须泼一盆冷水：**「兼容」是营销词，不是数学证明。**

常见差异包括：

- 模型名字符串各写各的（`gpt-4o` vs `qwen-max` vs `glm-4`）
- 工具调用的字段细节、并行 tool call 的支持程度
- 结构化输出：有的走原生 JSON mode，有的靠提示词硬拗
- 流式 SSE 的分片格式、思考链字段（reasoning / thinking）是否暴露
- 速率限制、单次最大 token、是否支持 vision

所以：**兼容协议让你少写 HTTP 客户端，不能让你少做评测。**

---

## 2. Spring AI 2.0 里的 OpenAI：官方收敛到一种实现

Spring AI 1.x 时代，社区里有过各种「OpenAI 兼容」的拼凑方式。到了 **2.0.0**，官方态度很明确：

- 对话模型走 **`spring-ai-starter-model-openai`**
- 底层统一为 **OpenAI Java SDK 风格的一种实现路径**（与各家兼容网关对话时，仍通过 `base-url` 指向目标）
- 与 Boot 4、Jackson 3、Advisor 链体系绑在一起，不再推荐你在 `ChatModel` 层各写各的

### 2.1 依赖怎么写

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

BOM 仍用 `spring-ai-bom:2.0.0`，版本号不要手写。

### 2.2 为什么主线不用它

讲义主线选 DeepSeek，是因为：

1. 有 **`spring-ai-starter-model-deepseek`**，配置项更贴 DeepSeek 文档（thinking、reasoning-effort 等）
2. 学习期价格友好，适合大量试错
3. 中文企业场景表现稳定

**这不是说 OpenAI starter 不好。** 而是学习期同时换框架版本 + 换模型 + 换账号，排障维度爆炸。等你 part-08 综合项目跑通，再用本章方法切 GPT 或兼容网关，是正确顺序。

### 2.3 用 OpenAI starter 接「任意兼容网关」

很多国产云提供「OpenAI 兼容」入口。典型配置思路：

```yaml
spring:
  ai:
    openai:
      api-key: ${CLOUD_API_KEY}
      base-url: https://你的网关地址/v1   # 注意是否已含 /v1
      chat:
        options:
          model: 厂商文档里的模型名
          temperature: 0.3
```

要点：

- `base-url` 有的厂商要写到 `/v1`，有的已经内置，**以对方文档为准**，错了全是 404
- `model` 必须填对方支持的字符串，不能想当然写 `gpt-4o`
- 密钥用环境变量，和主线 `DEEPSEEK_API_KEY` 一样纪律

---

## 3. 主线 DeepSeek starter vs OpenAI starter：对照表

| 维度 | `spring-ai-starter-model-deepseek` | `spring-ai-starter-model-openai` |
|---|---|---|
| 典型用途 | DeepSeek 官方 API | OpenAI 官方，或任意兼容网关 |
| 配置前缀 | `spring.ai.deepseek.*` | `spring.ai.openai.*` |
| 模型名示例 | `deepseek-v4-flash`、`deepseek-v4-pro` | `gpt-4o`、`gpt-4o-mini` 或网关模型名 |
| 思考/推理扩展 | `thinking`、`reasoning-effort` 等原生配置 | 视目标模型，可能无或字段不同 |
| 学习主线 | **默认用它** | 扩展篇再碰 |

**业务代码层面：** 注入的仍是 `ChatClient.Builder`，Advisor 链、 `@Tool`、RAG 组装方式不变。这就是讲义反复强调「对着 ChatClient 编程」的原因。

---

## 4. 完整 yml 对照：OpenAI 官方 vs DeepSeek 官方

下面两份配置，假设都放在 `application.yaml`，且只启用**一个** chat 模型 starter。

### 4.1 DeepSeek（主线）

```yaml
spring:
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
    retry:
      max-attempts: 3
```

### 4.2 OpenAI 官方

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      # base-url 省略则默认 OpenAI 官方地址
      chat:
        options:
          model: gpt-4o
          temperature: 0.2
          max-tokens: 8192
    retry:
      max-attempts: 3
```

### 4.3 OpenAI starter + 国产兼容网关（示意）

```yaml
spring:
  ai:
    openai:
      api-key: ${VENDOR_API_KEY}
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
      chat:
        options:
          model: qwen-max
          temperature: 0.2
```

模型名和 `base-url` 必须查厂商当前文档，这里只是示意「换地址 + 换 model 名」的模式。

### 4.4 多 starter 共存时的坑

如果你**同时**引入 `spring-ai-starter-model-deepseek` 和 `spring-ai-starter-model-openai`，Spring Boot 自动配置可能不知道默认 `ChatModel` 选谁。

必须显式指定：

```yaml
spring:
  ai:
    model:
      chat: deepseek   # 或 openai
```

学习期：**不要两个 chat starter 一起引。** 扩展实验请开分支或单独 demo 工程。

---

## 5. 代码几乎不用改：ChatClient 层的稳定性

看一段与主线完全同构的 Controller（模型无关）：

```java
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient.Builder builder) {
        this.chatClient = builder
                .defaultSystem("你是管理层决策助手。不编造数字。")
                .build();
    }

    @PostMapping
    public String chat(@RequestBody String question) {
        return chatClient.prompt()
                .user(question)
                .call()
                .content();
    }
}
```

换 DeepSeek → GPT → 兼容网关，这段 **不用改**。

需要改的通常是：

1. `pom.xml` 里的 starter
2. `application.yaml` 里的配置前缀和模型名
3. 若新模型工具调用癖好不同：提示词微调 + **回归评测集**
4. RAG .embedding 若从「无」变「有」或换厂商：向量链路单独配置（下一节）

---

## 6. 关键差异：Embedding 与 RAG 路线

这是换模型时**最容易翻车**的一点，part-05 主线已经埋过伏笔。

### 6.1 GPT / OpenAI 生态

OpenAI 提供 **官方的 embedding 模型**（如 `text-embedding-3-small` / `large`）。在 Spring AI 里可引：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

同一 starter 往往既能配 `chat` 也能配 `embedding`（具体选项以 2.0 文档为准）。**对话模型和向量模型可以同源**，RAG 配置心智负担小。

### 6.2 DeepSeek 对话 API 的限制

DeepSeek 的对话 API **不提供**与 OpenAI embedding 对位的官方向量接口（讲义写稿时仍如此）。因此主线 RAG 策略是：

- **生成**：DeepSeek Chat
- **向量化**：另接 `spring-ai-starter-model-transformers`（本地）或其它 embedding 服务

### 6.3 换 GPT 时 RAG 要怎么想

若你整体切到 OpenAI：

- 可以 chat + embedding 都走 OpenAI，向量维度和索引要重建或迁移
- 评测集要覆盖：检索命中率、答案 grounded 程度、工具调用是否仍准

若你只把 **对话** 换成 GPT，embedding 仍用本地 Transformers：

- 完全可行，也是很多企业做法
- 注意：**换对话模型**影响的是「怎么组织答案」；**换 embedding** 影响的是「能不能检到对的段落」——两件事要分开回归

### 6.4 给老师的一句口诀

> **RAG 是两条管线：检得准靠 embedding，说得清靠 chat。**  
> 换 chat 不等于换 embedding；只换 chat 时，先跑检索指标，再跑生成指标。

---

## 7. 工具调用与结构化输出：差异与回归评测

### 7.1 工具调用（Function / Tool Calling）

Spring AI 2.0 里，工具循环在 **`ToolCallingAdvisor`**，你的 `@Tool` 方法不变。但**模型**决定是否、以及如何填参数。

常见差异：

| 现象 | 可能原因 | 工程应对 |
|---|---|---|
| 该调工具却不调 | 模型偏「爱聊天」、温度太高 | 降 temperature；系统提示写清「必须先查工具」 |
| 工具名 hallucinate | 工具太多、描述不清 | Tool Search Advisor；减少单次暴露工具数 |
| 参数 JSON 缺字段 | 模型档位的结构化能力弱 | 参数加 `@JsonProperty` 描述；校验失败时把错误喂回模型重试 |
| 并行多工具调用 | 有的模型支持，有的不支持 | 在评测集里加「需要连续两次查询」的用例 |

**回归评测**不是可选爱好。建议你维护一个 `eval/` 目录，至少包含：

- 20～50 条真实业务问法（脱敏后）
- 期望：必须调用的工具名、关键字段范围、禁止出现的幻觉句式
- 换模型后：**同一套用例跑一遍**，对比 pass rate

讲义 part-01 讲过「没有评测集就没有迭代」；换 GPT 时这句话加倍成立。

### 7.2 结构化输出

Spring AI 2.0 支持通过 `ChatClient` 的 structured output API（如 `entity(MyDto.class)`）并可选 `validateSchema()` 失败重试。

模型差异：

- 有的提供商有 **原生 JSON schema / response_format**
- 有的只能靠提示词 + 解析，失败率更高

换模型后务必测：

- 字段缺失率
- 枚举越界
- 数字类型被写成字符串

决策系统里「结构化输出」往往对接审批单、风险卡片——**宁可多一次重试，也不要静默吞错。**

---

## 8. 数据出域、合同、价格、密钥

换 GPT 或走公有云 OpenAI，**合规讨论往往比技术讨论更早到来。**

### 8.1 数据出域

- 用户问题、检索到的制度片段、工具返回的业务数字，都会发到模型提供商
- 若合同要求「核心经营数据不出境」，国际 OpenAI 可能直接不可用，只能：国产云、私有化、或本地 Ollama（见扩展 02）
- **脱敏**应在进模型前由 Java 完成，不要指望模型「自觉不说」

### 8.2 合同与采购

企业采购常见条款：

- SLA 与可用性
- 日志留存与训练数据 opt-out
- 行业资质（等保、金融、医疗等场景）

技术负责人要会跟法务/采购对齐：**API 调用是否构成数据处理委托？** 答案因公司而异，本章给的是检查清单，不是法律意见。

### 8.3 价格心智

OpenAI 标价通常按 **输入 token + 输出 token** 计费，embedding 另计。与 DeepSeek 对比时：

- 不要只比「每百万 token」标价，要比**你的评测集上的平均单次请求成本**
- Agent 多轮工具循环会放大 token；RAG 长上下文也会放大

建议在 Micrometer 里记录每次请求的 token 用量（Spring AI 观测体系 part-07 会讲），换模型后看两周账单再定档。

### 8.4 密钥纪律（与主线相同）

- `OPENAI_API_KEY`、`CLOUD_API_KEY` 一律环境变量或密钥管理服务
- 开发、测试、生产 **分 key、分配额**
- CI 里用 mock 或录制的 fake server，不要把真 key 打进流水线日志

---

## 9. 动手实验建议（学完主线再做）

1. 复制 part-02 的 Hello World 工程为 `sandbox-openai`
2. 只换 starter 与 yml，确认同步、流式、结构化三条接口仍通
3. 把 part-04 的一个只读 `@Tool` 接上去，跑 10 条评测问法
4. 若做 RAG：单独决策 embedding 走哪条 starter，不要混在对话实验里一起改

**预计用时：** 一个下午。卡壳先查 `base-url` 和 `model` 名，再查工具描述。

---

## 10. 深度案例：从 DeepSeek 切到 GPT-4o 的一周工程清单

假设你已经完成 part-08，现因集团采购统一走 Azure OpenAI 或 OpenAI 官方。老师给你一份 **可照抄的检查清单**（按天组织的是工作顺序，不是日历承诺）。

### 第 1 步：冻结基准（半天）

把当前 DeepSeek 生产配置的以下产物归档：

- `application-prod.yaml`（脱敏）
- 当前 `pom.xml` 依赖树
- 评测集最后一次全绿报告（工具调用、RAG、结构化三类）
- 连续三天的 token 用量与 P95 延迟截图

没有基准，后面吵「变聪明了还是变笨了」无法收场。

### 第 2 步：沙箱替换 starter（半天）

新建分支，**只改**：

```xml
<!-- 移除 -->
<!--
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-deepseek</artifactId>
</dependency>
-->
<!-- 加入 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

yml 从 `spring.ai.deepseek` 整块换成 `spring.ai.openai`。先跑 part-02 三个冒烟接口。

### 第 3 步：工具回归（1～2 天）

把 part-04 全部 `@Tool` 用例导入自动化测试。重点看：

- 模型是否 **少调工具**（GPT 有时「自信」过高）
- 日期、金额参数是否格式漂移
- 多工具串联场景是否中途「给用户讲原理而不查库」

常见问题修复：**不改 Java 业务**，改 `description` 与 system prompt 里的「必须先调用工具」条款。

### 第 4 步：RAG 决策分叉（1 天）

两条路选一条，写进架构说明，不要混：

**路 A：** embedding 也切 OpenAI（`text-embedding-3-small`），重建向量索引。  
**路 B：** embedding 仍用本地 Transformers，只换 chat。  

路 A 要排 **全量 re-embed 窗口**；路 B 要证明 **检索层未动**，只比生成层。

### 第 5 步：流式与结构化（半天）

SSE 前端若解析过 DeepSeek 特有字段，删掉。结构化 DTO 跑 `validateSchema()` 统计失败率。

### 第 6 步：合规与密钥（并行）

安全部门确认：问题文本、检索片段、工具返回是否允许到新厂商区域。申请 **生产专用 key**，限额设为预估峰值的 1.5 倍。

### 第 7 步：金丝雀（2～3 天观察）

内部账号 10% 流量走 GPT，对比审计日志里的 `model_id` 字段。通过后全量，**保留 DeepSeek 配置在配置中心但关闭**，以便紧急回滚。

---

## 11. 结构化输出与工具调用：并排实测记录表（示意）

下表是讲义编写时常见的 **定性** 对比，你的团队必须用自家评测集填 **定量** 数字：

| 场景 | DeepSeek v4-pro（示意） | GPT-4o（示意） | 工程含义 |
|---|---|---|---|
| 单工具：查 SKU 库存 | 稳定调工具 | 稳定调工具 | 两者通常都能过 |
| 双工具：先查库存再查在途 | 偶发跳过第二步 | 较稳 | 需在 prompt 写清顺序 |
| JSON 输出 8 字段审批单 | 失败率低 | 失败率低 | 都要 `validateSchema` |
| 长制度问答 + 无工具 | 中文术语好 | 中文尚可 | 决策系统仍应加 RAG |
| 思考链暴露 | thinking 配置原生 | 视套餐 | UI 是否展示推理过程 |

**老师强调：** 上表不是让你背结论，是让你 **建同样一张表，填自己的数**。

---

## 12. 完整 pom 片段：OpenAI chat + embedding 同源（RAG 一体）

当你合规允许且希望简化 RAG 时，可在同一 starter 下配置 chat 与 embedding（字段名以 2.0 文档为准，此处为学习用骨架）：

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-webflux</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-model-openai</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-vector-store-pgvector</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-rag</artifactId>
    </dependency>
</dependencies>
```

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: gpt-4o
          temperature: 0.2
      embedding:
        options:
          model: text-embedding-3-small
```

对比主线 DeepSeek：**对话配置在上面，向量配置在 `spring-ai-starter-model-transformers` 或别的 embedding 服务**，两套 yml 心智要分开记。

---

## 13. 常见故障排查（GPT / 兼容网关）

| 错误现象 | 优先检查 |
|---|---|
| 401 Unauthorized | key 环境变量是否注入容器；是否用了已吊销 key |
| 404 on completions | `base-url` 是否多写/少写 `/v1` |
| model not found | 模型字符串是否与控制台一致（大小写、后缀） |
| 空 choices | 请求被内容安全拦截；降采样测试一句「你好」 |
| 工具从不触发 | temperature；工具 description；是否误关 ToolCallingAdvisor |
| embedding 维度不匹配 | 换 embedding 模型后是否重建 pgvector 表 |

---

## 14. 课堂答疑：同学最爱问的五个问题

**问：能不能学习期同时配 DeepSeek 和 GPT，哪个便宜用哪个？**  
答：技术上可以（扩展 03），学习期不建议。你会搞不清失败是 Spring 问题还是路由问题。

**问：国产云写兼容 OpenAI，为什么还要官方 DeepSeek starter？**  
答：官方 starter 对齐 DeepSeek 特有参数；兼容模式适合「统一运维脚本」，不是学习捷径。

**问：老板说要 GPT，我能偷偷底层换吗？**  
答：审计与合规不允许「偷偷」。走评测与变更单，回答里可标 `modelVersion` 供溯源。

**问：OpenAI embedding 中文制度 PDF 好不好？**  
答：取决于文档与切块；用 part-05 的检索命中率指标说话，别凭感觉。

**问：代码要抽象 `LlmPort` 接口吗？**  
答：中型项目 **ChatClient + 配置** 足够；超大平台才值得自研网关层。别在学习期 over-engineer。

---

## 15. OpenAI 兼容协议的历史位置：为什么大家都模仿它

老师用五分钟帮你建立「行业常识」，以后开会不被名词唬住。

早年 OpenAI 的 Chat Completions 接口因为 ChatGPT 爆发，成了 **事实上的方言标准**。国内云厂商若各自发明一套 JSON，Java 团队就要写 N 套 HTTP 客户端——运维和 SDK 成本都扛不住。于是出现「兼容 OpenAI」：路径像、字段像、错误码大体像，换 `base-url` 就能用开源客户端或 Spring AI 的 openai starter。

你要知道的底线：

1. **兼容是商业策略**，不是开源基金会标准；字段今天有明天无，要看 changelog。  
2. **模型能力不兼容**：同样叫 `chat/completions`，背后可能是完全不同的参数量与训练数据。  
3. **Spring AI 的价值**是把差异尽量挡在 `ChatModel` 实现里，让你业务层仍叫 `ChatClient`。

所以扩展 01 不是「吹 GPT」，是教你在 **方言标准** 上换插头；插头换了，电器（Advisor、Tool、RAG）尽量不换。

---

## 16. 完整业务代码对照：Controller / Service / Tool 三层不变

### 16.1 Tool 层（与主线完全相同）

```java
@Component
public class InventoryQueryTools {

    private final InventoryReadService inventoryReadService;

    public InventoryQueryTools(InventoryReadService inventoryReadService) {
        this.inventoryReadService = inventoryReadService;
    }

    @Tool(description = "查询指定 SKU 的可用库存数量，SKU 必须为系统内合法编码")
    public String queryAvailableQty(
            @ToolParam(description = "物料 SKU，如 MAT-10023") String sku) {
        int qty = inventoryReadService.availableQty(sku);
        return "SKU=" + sku + ", availableQty=" + qty;
    }
}
```

换 GPT 时 **这段一个字都不用改**。若换了模型后工具调用率下降，优先改 `description` 文案，不要先改 SQL。

### 16.2 Service 层

```java
@Service
public class DecisionAgentService {

    private final ChatClient chatClient;

    public DecisionAgentService(ChatClient.Builder builder, InventoryQueryTools tools) {
        this.chatClient = builder
                .defaultSystem("""
                    你是制造业辅助决策助手。
                    涉及库存数字必须先调用 queryAvailableQty。
                    禁止编造未查询到的数量。
                    """)
                .defaultTools(tools)
                .build();
    }

    public String analyze(String question, String conversationId) {
        return chatClient.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .user(question)
                .call()
                .content();
    }
}
```

若你启用了 `MessageChatMemoryAdvisor`，`CONVERSATION_ID` 的用法与主线 part-07 一致；换模型不影响会话键策略。

### 16.3 仅配置类差异

```java
// 通常不需要为 GPT 单独写 @Configuration
// 除非你要显式 second ChatModel——见扩展 03
```

这就是讲义说的 **代码几乎不用改** 的完整含义：变的是 classpath 里的 `DeepSeekChatModel` 还是 `OpenAiChatModel`，不是三层架构。

---

## 17. 数据出域：给法务看的四句话（技术负责人版）

上线 GPT 或第三方兼容云之前，你可以把下面四条发给合规同事，减少来回邮件：

1. **传输内容**：用户问题、系统检索到的制度片段、工具返回的业务指标文本、模型生成的回答。  
2. **不传**：数据库连接串、原始 JDBC 结果集、未脱敏的身份证完整号（应在 Java 层脱敏）。  
3. **留存**：厂商侧日志策略以合同为准；我方审计库仍保留「问了什么、调了哪个工具、返回摘要」。  
4. **退出**：合同终止后切换 `starter` 与 `api-key` 即可停用，向量索引与宽表仍在己方。

技术不能替代法务签字，但能证明 **你知道数据去哪**。

---

## 18. 价格演算例题（建立成本直觉）

假设评测集测得一次典型管理层问答：

- 输入 3,200 token（含 RAG 片段 + 工具结果）  
- 输出 600 token  
- 工具循环 2 轮，共 3 次模型调用  

若 DeepSeek 标价折合每百万输入 2 元、输出 8 元（示意，以官网为准）：

- 单次约 `3.2*2 + 0.6*8` × 3 轮量级 → 你自己按次数乘  

若 GPT-4o 标价显著更高，同一调用链账单可能 **数倍**。  
决策不是「哪个更聪明」，而是 **在 pass rate 达标前提下哪个更省**——这就是为什么扩展 03 要做路由，而不是全站一刀切 GPT。

学习期用 DeepSeek 的意义之一，就是让你敢多轮调试工具而不心疼；上线前再用同样评测集算账。

---

## 19. 回归评测集模板（可直接复制到项目）

```text
# eval/README.md
## 用例编号 E-001
- 用户问题：华东仓 MAT-10023 还能撑几天？
- 数据分级：INTERNAL
- 必须调用工具：queryAvailableQty
- 参数约束：sku=MAT-10023
- 禁止出现：未调用工具直接给具体整数
- 期望结构：结论 / 数量 / 依据 / 风险

## 用例编号 E-002
...（复制 30～50 条）
```

换 OpenAI 后跑：

```bash
mvn test -Dtest=ModelRegressionIT
```

报告里记录 `passRate`、`avgLatencyMs`、`avgInputTokens`。  
**没有这份文件，就不要在生产改 model 名。**

---

## 20. 工具调用与结构化输出的联合回归场景

有些用例同时要测 **工具 + JSON**，例如「查库存后输出 RiskCard」。评测条目应写成：

```text
## E-010 工具+结构化
- 问题：评估 SKU MAT-9 的断货风险
- 必须调用：queryAvailableQty
- 输出：RiskCard JSON，risks 数组非空
- schema 校验：validateSchema 必须通过
```

换 GPT 后常见退化：**工具调了，但 JSON 多一个 markdown 代码块包裹**。  
处理：2.0 结构化 API 已尽量剥离；仍失败则在 Advisor 加一层「去 markdown _fence」的轻量清洗——**先记评测，再决定要不要写清洗**，不要预防性 over-engineer。

---

## 21. 与集团统一网关集成时的配置注意

大型集团常提供 **统一 LLM 网关**，背后再路由 OpenAI、国产云、内网模型。你的 `application-prod.yaml` 可能长这样：

```yaml
spring:
  ai:
    openai:
      api-key: ${CORP_LLM_GATEWAY_KEY}
      base-url: https://llm-gateway.corp.example.com/v1
      chat:
        options:
          model: default-chat   # 网关侧映射，非 OpenAI 原名
```

此时 **model 字符串是网关合同**，不是 OpenAI 官网名字。  
联调时向平台组索要：**模型别名表、限额、是否支持 tools、是否支持 json_schema**。  
Spring AI 仍用 openai starter；**网关兼容性**由平台组 SLA 保证，你要用评测集验证。

---

## 22. 课后长思：为什么讲义主线坚持 DeepSeek 四年学习期不变

有同学问：「GPT 国际名气大，为什么讲义不主修？」老师把理由说透，免得你心里长草：

第一，**学习变量控制**。Spring AI 2.0 + Boot 4 已经够新，再接 OpenAI 账号、跨境支付、发票、网络抖动，你会把精力花在「连不上 API」而不是「ToolCallingAdvisor 怎么排序」。

第二，**embedding 分裂是真实工程课**。DeepSeek 对话无官方 embedding，逼你在 part-05 认真面对「向量模型另选」——这是企业常态，不是缺陷。早早习惯「生成一条线、向量一条线」，比幻想「一个 GPT 全家桶」更贴近生产。

第三，**中文决策场景**。制造业口径、ERP 术语、管理层问法，DeepSeek 在评测里往往性价比极高；换 GPT 是采购与合规结果，不是智商碾压。

第四，**兼容协议可迁移**。你学的 `ChatClient`、Advisor、`@Tool`，换 openai starter 后原样带走。主线不是绑死品牌，是绑死 **2.0 正确姿势**。

等你 part-09 毕业，再用本篇把插头换成 GPT 或集团网关——那时你会感谢第三周没分心。

---

## 23. 什么时候才应该用 · 和主线如何衔接

### 什么时候才应该用

- 你已经 **part-08 综合项目跑通**，有稳定的 `ChatClient` + `@Tool` + 基础 RAG
- 公司有 **明确的模型采购或合规要求**，必须对接 GPT 或指定国产云
- 你手里有 **回归评测集**，能客观对比 DeepSeek 与候选模型
- 你需要 **官方 embedding** 简化 RAG，且合规允许数据走该厂商

### 不要在什么时候用

- 第三周 Hello World 阶段就想「换更强的 GPT」——变量太多，学不会 Advisor
- 没有评测集，凭感觉「好像更聪明」——管理层决策系统会埋雷
- 为了逃避 DeepSeek 没有 embedding 而整体乱切——应单独解决向量链路（part-05）

### 和主线如何衔接

| 主线章节 | 本章衔接点 |
|---|---|
| part-02 Hello World | 同一套 `ChatClient.Builder` 注入方式，只换 starter/yml |
| part-03 核心 API | `ChatOptions`、流式、结构化 API 不变 |
| part-04 工具循环 | `ToolCallingAdvisor` + `@Tool` 不变；换模型后跑评测集 |
| part-05 RAG | 重点对比 embedding 策略；OpenAI 可同源，DeepSeek 需另接 |
| part-07 安全观测 | token 账单、密钥、出域审查在本章提前打好预防针 |
| part-09 工程化 | 多模型配置、`spring.ai.model.chat` 在生产环境的落地 |

走完主线再读本篇，你会把「技术选型说明书」里那句 **换模型主要是换 starter、换配置、跑评测** 变成肌肉记忆。那时你不再是「会用 DeepSeek 的同学」，而是 **「会用 Spring AI 接任何合规模型」的工程师**。

---

*扩展篇 01 · 完。下一篇：本地大模型 Ollama（同样请等主线后再读）。*

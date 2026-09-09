# 02 · Embedding、向量、切分与元数据

> 这一章是 RAG 的地基。很多同学卡在「向量从哪来」——我们把 **DeepSeek 与 Embedding 彻底拆开** 讲清楚。

---

## 1. 先建立一张心智图

```
制度 Markdown 文件
    ↓ 切分（按标题/条款）
Document 列表（每段带元数据）
    ↓ EmbeddingModel.embed(文本)
float[] 向量
    ↓ VectorStore.add(documents)
可检索的向量索引
```

**对话**走 `ChatModel`（DeepSeek）。  
**向量化**走 `EmbeddingModel`（Transformers 本地 或 中文 embedding 服务）。  
两条线，两个 Bean，两个配置块。**不要混。**

---

## 2. 【必懂】DeepSeek 对话 API 不做 Embedding

### 2.1 事实陈述

`spring-ai-starter-model-deepseek` 提供的是 **Chat Completions** 能力：  
输入消息列表，输出助手回复。用于生成、工具选择、查询改写等。

它**没有**官方配套的「把这句话变成向量」接口。  
你不能指望：

```yaml
# ❌ 不存在这种正确配置
spring:
  ai:
    deepseek:
      embedding:
        model: deepseek-xxx
```

### 2.2 为什么讲义坚持「对话 + 独立 Embedding」

| 能力 | 组件 | 学习期推荐 |
|---|---|---|
| 生成回答、改写查询 | `ChatModel` / `ChatClient` + DeepSeek | `spring-ai-starter-model-deepseek` |
| 文档与查询向量化 | `EmbeddingModel` | `spring-ai-starter-model-transformers` |

RAG **两端都要向量可比**：  
入库时对每个 `Document` 做 embedding；提问时对用户 query 做 embedding；  
然后在 VectorStore 里算相似度。**必须用同一套 EmbeddingModel**，且维度一致。

若入库用模型 A、查询用模型 B，召回率会崩，且难以排查。

### 2.3 学习期方案：本地 Transformers（ONNX）

优点：不额外买 embedding API；数据不出本机；与 Spring AI 官方 starter 对齐。

缺点：首次下载模型；中文效果取决于所选模型；占内存（建议 16GB 机器）。

`pom.xml` 已见 `00-本章导读.md`。配置示例：

```yaml
spring:
  ai:
    model:
      embedding: transformers   # 显式启用 transformers embedding
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
      chat:
        model: deepseek-v4-flash
        temperature: 0.3
    embedding:
      transformer:
        onnx:
          # 学习期：把 ONNX 模型文件放到项目内，避免运行时去外网拉
          model-uri: classpath:/onnx/model.onnx
        tokenizer:
          uri: classpath:/onnx/tokenizer.json
```

**【必做说明】** DeepSeek 对话 API 不提供向量。Transformers starter 需要一个本地 ONNX 模型文件。请向公司算法同事或内部制品库要一份「中文句向量 ONNX + tokenizer」，放到 `src/main/resources/onnx/`。不要把「去英文模型网站逛」当成作业。

若 starter 带了默认小模型且能离线启动，也可先用默认，但**入库和查询必须同一模型**，换模型必须重建向量库。

### 2.4 生产/中文增强方案：另接 Embedding 服务

当本地 ONNX 召回不准、或要统一用公司算法平台时，可换：

- 自建或采购的 **中文 embedding HTTP 服务**（BGE、M3E 等族）  
- 其它云厂商的 embedding API（若有 Spring AI starter 或自己写 `EmbeddingModel` 实现）

原则不变：

1. 实现 Spring AI 的 `EmbeddingModel` 接口（或用官方 starter）。  
2. **入库与查询共用同一实现。**  
3. 换模型要 **全量重建索引**（re-index），不能热换。

```java
// 注入时你会看到两个不同的 Bean——这是正常的
@Service
public class RagDemoService {

    private final ChatClient chatClient;      // DeepSeek
    private final EmbeddingModel embeddingModel; // Transformers 或其它

    public RagDemoService(ChatClient.Builder builder, EmbeddingModel embeddingModel) {
        this.chatClient = builder.build();
        this.embeddingModel = embeddingModel;
    }
}
```

【易混】网上「OpenAI embedding + 任意 Chat」教程，换成 DeepSeek 时只换了 Chat，忘了换 Embedding——**RAG 整段失效**。

---

## 3. Document 与元数据

Spring AI 的 `Document` = **一段文本 + 元数据 Map**。

本章统一元数据字段（与 `06` 假制度一致）：

| 字段 | 类型 | 含义 | 检索过滤示例 |
|---|---|---|---|
| `title` | String | 章节或文件标题 | 展示来源 |
| `docId` | String | 制度编号，如 `INV-POL-2024-01` | 精确追溯 |
| `effectiveDate` | String (ISO 日期) | 生效日 `2024-03-01` | 多版本并存时过滤最新 |
| `org` | String | 组织/法人，如 `east-china` | `org == 'east-china'` |
| `warehouse` | String | 仓库编码，如 `WH-SH-01`；全公司通用填 `ALL` | `warehouse == 'WH-SH-01'` |
| `type` | String | 文档类型：`policy` / `sop` / `glossary` | `type == 'policy'` |
| `section` | String | 条款号，如 `3.2` | 引用 |

构建示例：

```java
import org.springframework.ai.document.Document;

Document doc = Document.builder()
    .text("3.2 可用量 = 账面库存 − 销售预留 − 质检冻结 − 库内冻结。")
    .metadata("title", "库存可用量口径")
    .metadata("docId", "INV-POL-2024-01")
    .metadata("effectiveDate", "2024-03-01")
    .metadata("org", "east-china")
    .metadata("warehouse", "ALL")
    .metadata("type", "policy")
    .metadata("section", "3.2")
    .build();
```

【红线】元数据里的 `org`、`warehouse` 必须与权限体系一致；运行时过滤要和登录用户租户对齐（见 `05`、`07`）。

---

## 4. 切分：按标题/条款，不是死切 500 字

### 4.1 为什么反对「固定 500 字一块」

制度文档天然有结构：

```markdown
## 3. 库存可用量
### 3.1 定义
...
### 3.2 计算公式
...
### 3.3 例外情形
...
```

死切 500 字可能把 **3.2 的公式** 和 **3.3 的例外** 劈成两半：

- 检索只命中半段 → 模型断章取义  
- 来源引用无法对应条款号 → 审计不认  

### 4.2 推荐策略

1. **按 Markdown 标题层级切**（`##`、`###` 为一块，或 `##` 下再按 `###` 细分）。  
2. 单块仍过长（如 > 1500 字）时，**在条款内部**按编号列表再切，而不是按字符数硬砍。  
3. 每块头部 **重复父标题** 作上下文前缀（可选但推荐）：

```
【库存管理制度 INV-POL-2024-01 · 3.2 可用量计算公式】
可用量 = ...
```

### 4.3 用 `MarkdownHeaderTextSplitter` 的思路

Spring AI 提供多种 `TextSplitter`。企业制度推荐：

```java
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.document.Document;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 按 Markdown 标题切分；不采用固定字符窗口。
 */
public class PolicyMarkdownSplitter extends TextSplitter {

    private static final Pattern HEADER = Pattern.compile("^(#{2,3})\\s+(.+)$", Pattern.MULTILINE);

    private final String docId;
    private final String effectiveDate;
    private final String org;
    private final String warehouse;
    private final String type;

    public PolicyMarkdownSplitter(String docId, String effectiveDate,
                                  String org, String warehouse, String type) {
        this.docId = docId;
        this.effectiveDate = effectiveDate;
        this.org = org;
        this.warehouse = warehouse;
        this.type = type;
    }

    @Override
    protected List<Document> splitText(String fullText) {
        List<Document> chunks = new ArrayList<>();
        String[] lines = fullText.split("\n");
        StringBuilder current = new StringBuilder();
        String currentTitle = "前言";
        String currentSection = "0";

        for (String line : lines) {
            var m = HEADER.matcher(line);
            if (m.matches()) {
                flush(chunks, current, currentTitle, currentSection);
                current = new StringBuilder();
                currentTitle = m.group(2).trim();
                currentSection = extractSection(currentTitle);
                current.append(line).append('\n');
            } else {
                current.append(line).append('\n');
            }
        }
        flush(chunks, current, currentTitle, currentSection);
        return chunks;
    }

    private void flush(List<Document> chunks, StringBuilder buf, String title, String section) {
        String text = buf.toString().trim();
        if (text.isEmpty()) {
            return;
        }
        chunks.add(Document.builder()
            .text(text)
            .metadata("title", title)
            .metadata("docId", docId)
            .metadata("effectiveDate", effectiveDate)
            .metadata("org", org)
            .metadata("warehouse", warehouse)
            .metadata("type", type)
            .metadata("section", section)
            .build());
    }

    private static String extractSection(String title) {
        // 「3.2 可用量」→ 3.2
        int space = title.indexOf(' ');
        if (space > 0 && title.substring(0, space).matches("\\d+(\\.\\d+)*")) {
            return title.substring(0, space);
        }
        return "0";
    }
}
```

完整入库组件见 `04` 的 `DocumentIngestion`。

### 4.4 切分后长度与 Token

- 单块建议控制在 **300～800 汉字** 量级（视 embedding 模型而定）。  
- 过长块在 `05` 用 `DocumentPostProcessor` 再压缩；**首选仍是在切分阶段就切对**。

---

## 5. 向量化与写入（概念代码）

```java
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;

public void ingest(List<Document> chunks, EmbeddingModel embeddingModel, VectorStore vectorStore) {
    // VectorStore.add 内部会调用 embeddingModel 为每个 Document 生成向量
    vectorStore.add(chunks);
}
```

你通常**不需要**手写 `embeddingModel.embed(text)`，除非做调试或自定义存储。

---

## 6. 常见卡点

| 现象 | 原因 | 处理 |
|---|---|---|
| 启动报找不到 EmbeddingModel | 只加了 deepseek starter | 加 `spring-ai-starter-model-transformers` |
| 中文召回很差 | 用了纯英文小模型 | 换多语言/中文 embedding 模型 |
| 入库成功但检索永远空 | 查询没用同一 EmbeddingModel | 检查是否误建了两个不同 Bean |
| 元数据过滤无效 | 字段名或值与入库不一致 | 打印 `document.getMetadata()` 对照 |

---

## 7. 小结

1. **DeepSeek = 说话；EmbeddingModel = 向量化。** 两条线。  
2. 学习期用 `spring-ai-starter-model-transformers`；生产可换中文 embedding 服务，但要 re-index。  
3. 制度文档 **按标题/条款切**，元数据带齐 `title、docId、effectiveDate、org、warehouse`。  
4. 下一章：`VectorStore` 从内存到 pgvector。

---

## 8. 【必做】动手

1. 配好 `application.yml`，启动时日志里能看到 Transformers 模型加载。  
2. 用 `embeddingModel.embed("库存可用量")` 打日志，确认返回 `float[]` 长度固定（如 384）。  
3. 拿 `06` 里任意一篇假制度，用 `PolicyMarkdownSplitter` 切分，打印每块 `title` 与 `section`。

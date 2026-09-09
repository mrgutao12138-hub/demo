# 03 · VectorStore：从 SimpleVectorStore 到 pgvector

> 学习期用内存，生产用 Postgres。别学两天就上分布式向量库——先把检索链路跑通。

---

## 1. VectorStore 在 RAG 里的位置

`VectorStore` 是 Spring AI 对「向量数据库」的统一抽象：

- `add(List<Document>)` — 写入（内部 embedding）  
- `similaritySearch(SearchRequest)` — 按相似度检索  
- 支持元数据 **过滤表达式**（与 `VectorStoreDocumentRetriever` 配合）

实现来自 `spring-ai-vector-store` 及各存储 starter。

---

## 2. 学习期：SimpleVectorStore

### 2.1 特点

- 纯内存，**重启即空**  
- 零外部依赖，适合本章实验  
- API 与 pgvector 一致，代码可平滑迁移  

### 2.2 配置类（完整可运行）

```java
package com.company.decision.rag;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("local-rag")
public class LocalVectorStoreConfig {

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}
```

### 2.3 启动时灌数（配合 DocumentIngestion）

```java
package com.company.decision.rag;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("local-rag")
public class RagBootstrap {

    @Bean
    CommandLineRunner ingestOnStartup(DocumentIngestion ingestion) {
        return args -> ingestion.ingestAllFromClasspath("knowledge/policies/");
    }
}
```

`application-local-rag.yml`：

```yaml
spring:
  profiles:
    active: local-rag
  ai:
    model:
      embedding: transformers
    embedding:
      transformer:
        onnx:
          model-uri: classpath:/onnx/model.onnx
        tokenizer:
          uri: classpath:/onnx/tokenizer.json
```

---

## 3. 生产：PgVectorStore

### 3.1 为什么选 pgvector

- 你们 part-06 已有 **Postgres 宽表** 叙事；同一实例加 `vector` 扩展，运维简单。  
- 事务、备份、权限与现有 DBA 流程一致。  
- Spring AI 官方 `spring-ai-starter-vector-store-pgvector`。

### 3.2 依赖

```xml
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-starter-vector-store-pgvector</artifactId>
</dependency>
<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>postgresql</artifactId>
</dependency>
```

### 3.3 Docker 起库（学习模拟生产）

```bash
docker run -d --name decision-pg \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=decision \
  -p 5432:5432 \
  pgvector/pgvector:pg16
```

库内执行：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

### 3.4 配置

`application-prod-rag.yml`：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/decision
    username: postgres
    password: ${POSTGRES_PASSWORD:postgres}
  ai:
    vectorstore:
      pgvector:
        index-type: HNSW
        distance-type: COSINE_DISTANCE
        dimensions: 384   # 必须与 EmbeddingModel 输出维度一致
        initialize-schema: true
```

```java
package com.company.decision.rag;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("prod-rag")
public class PgVectorStoreConfig {
    // spring-ai-starter-vector-store-pgvector 自动配置 PgVectorStore Bean
    // 确保 dimensions 与 embedding 模型一致
}
```

### 3.5 维度【红线】

`paraphrase-multilingual-MiniLM-L12-v2` 输出 **384** 维。  
换 embedding 模型必须：

1. 改 `dimensions`  
2. **清空或重建** 向量表  
3. 全量 `DocumentIngestion` 重跑  

---

## 4. SimpleVectorStore 与 PgVector 代码差异

**业务代码应零差异**——只注入 `VectorStore`：

```java
@Service
public class PolicySearchService {

    private final VectorStore vectorStore;

    public PolicySearchService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    // 检索逻辑全部交给 RetrievalAugmentationAdvisor + VectorStoreDocumentRetriever
}
```

切换环境 = 切换 Profile + 依赖，不改 Advisor 代码。

---

## 5. 运维要点

| 主题 | 建议 |
|---|---|
| 索引重建 | 制度变更走 CI 任务：解析 Markdown → `vectorStore.add`（或先 delete by docId 再 add） |
| 多版本 | 用 `effectiveDate` 元数据；检索时过滤最新生效日 |
| 备份 | pgvector 表随 Postgres 备份；内存库无备份 |
| 监控 | 记录检索耗时、命中条数、相似度分布 |

---

## 6. 不要用的东西（学习期）

- 把向量存成 JSON 文件自己算余弦——除非你在做算法实验  
- 生产仍用 `SimpleVectorStore`——重启丢库，无法接受  
- 未设 `dimensions` 的 pgvector——插入时报错或检索全错  

---

## 7. 小结

- **学习：`SimpleVectorStore` + `local-rag` Profile**  
- **生产：`PgVectorStore` + `prod-rag` Profile**  
- `EmbeddingModel` 与 `dimensions` 必须匹配  
- 下一章：把 `VectorStore` 接进 `RetrievalAugmentationAdvisor`

---

## 8. 【必做】

1. `local-rag` 下启动，问一个口径问题，能命中 `06` 的假制度。  
2. 重启应用，确认内存库清空——理解为何生产要上 pgvector。  
3. （【建议】）起 Docker pgvector，用 `prod-rag` 灌同批文档，对比检索结果一致性。

# 05 · 审计日志与 token 账单

> 没有审计的 Agent，出了错你会发现自己在和空气对质。  
> 没有账单的 Agent，财务会在第二个月找到你。

---

## 1. 一次问答要留下什么

最少字段：

| 字段 | 为什么 |
|---|---|
| id / traceId | 贯穿网关、应用、模型调用 |
| userId / roles | 谁问的 |
| conversationId | 哪段对话 |
| questionHash 或截断问题 | 可追溯又控制隐私 |
| model | flash 还是 pro |
| inputTokens / outputTokens | 算钱 |
| toolInvocations | JSON：名字、参数摘要、耗时、成功失败 |
| ragHitDocIds | 用了哪些制度 |
| latencyMs | 体验 |
| status | OK / REJECTED / ERROR |
| rejectReason | NO_WRITE / NO_PERM / INJECTION / ... |
| dataAsOf | 数字新鲜度（可从工具汇总） |
| createdAt | 时间 |

不要把完整模型原文无限制存公开日志库。审计表权限仅管理员。

---

## 2. 表结构（Postgres）

```sql
CREATE TABLE ai_audit_log (
    id              UUID PRIMARY KEY,
    trace_id        VARCHAR(64) NOT NULL,
    user_id         VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(128) NOT NULL,
    question_preview VARCHAR(512),
    model           VARCHAR(64),
    input_tokens    INT,
    output_tokens   INT,
    tool_invocations JSONB,
    rag_doc_ids     JSONB,
    latency_ms      INT,
    status          VARCHAR(32) NOT NULL,
    reject_reason   VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ai_audit_user_time ON ai_audit_log (user_id, created_at DESC);
CREATE INDEX idx_ai_audit_trace ON ai_audit_log (trace_id);
```

---

## 3. token 从哪读

`ChatResponse` 的 metadata 通常带 usage。DeepSeek 兼容 Chat Completions，返回里会有 prompt/completion tokens。

```java
ChatResponse resp = chatClient.prompt()
        .user(q)
        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, cid))
        .call()
        .chatResponse();

Integer in = resp.getMetadata().getUsage().getPromptTokens();
Integer out = resp.getMetadata().getUsage().getCompletionTokens();
```

若某次为空（流式聚合前），在流结束时从最后一帧取。拿不到就记 null，不要编。

工具循环会多次请求模型：账单应记**该用户可见的这一轮合计**，而不是只记最后一次。若框架在最终 `ChatResponse` 已累计，用累计值；否则自己在观察里累加。

---

## 4. 如何观察工具

学习期最朴素：在每个 `@Tool` 方法里自己记：

```java
long t0 = System.nanoTime();
try {
    var result = repo.find(...);
    toolTrail.add(ToolHit.of("queryInventory", Map.of("skuId", skuId, "warehouseId", warehouseId), true, elapsed(t0)));
    return result;
} catch (Exception e) {
    toolTrail.add(ToolHit.of("queryInventory", Map.of("skuId", skuId), false, elapsed(t0)));
    return "查询失败，请稍后重试。";
}
```

`toolTrail` 用请求级上下文收集，Controller 结束时写入审计。

更先进：Micrometer Observation / Spring AI 的 advisor 观测。主线先把表写上，再接仪表盘。

建议指标：

- `decision.chat.calls`（tag: model, status）
- `decision.chat.tokens`（tag: direction=in|out, model）
- `decision.tool.calls`（tag: tool, success）
- `decision.chat.latency`

---

## 5. 账单怎么算（学习期公式）

到 DeepSeek 控制台看**当前**输入/输出单价。不要把过时数字当合同。

```text
日费用 ≈ Σ (inputTokens × 输入单价 + outputTokens × 输出单价)
月费用 ≈ 日费用 × 工作日

或反过来预算：
人均日问次数 × 人数 × 平均每问总 token × 综合单价
```

经验上，带工具的决策问答：一次可能 2～5 次模型往返（选工具 + 再选 + 总结）。估算时用「每问 3 次调用」更不容易低估。

思考模式会显著增加输出 token。能用 flash 的改写不要用 pro+thinking。

---

## 6. 给管理员的查询

```sql
-- 昨天谁最烧
SELECT user_id,
       count(*) AS asks,
       sum(input_tokens + output_tokens) AS tokens
FROM ai_audit_log
WHERE created_at >= current_date - interval '1 day'
GROUP BY user_id
ORDER BY tokens DESC;
```

产品上一个简单页：按人、按日、失败原因分布。这能让你在领导问「这东西贵不贵」时拿出表，而不是感觉。

---

## 7. 【必做】

1. 建表或先用内存列表。
2. 每次对话写一条审计（含拒绝）。
3. 用三次不同问题后，能查出三次 tool 名。
4. 自己算：假设 50 人 × 每天 15 问 × 每问 4000 token，用控制台单价估一个月。

---

## 8. 口头验收

- 为什么审计要记拒绝，不只记成功？
- 工具循环为什么可能让「一次提问」对应多次计费？
- 单价为什么不能写死在讲义里？

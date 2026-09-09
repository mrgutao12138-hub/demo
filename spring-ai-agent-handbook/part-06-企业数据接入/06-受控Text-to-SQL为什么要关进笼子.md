# 06 · 受控 Text-to-SQL 为什么要关进笼子

> 模式 4 很诱人：用户随便问，模型自动生成 SQL。  
> 老师直说：**可以存在，必须关笼子；默认不能替代宽表 + 固定 Tool。**  
> 这一节讲清笼子怎么焊，以及为什么主 Agent 不应走这条路。

---

## 1. Text-to-SQL 在企业里的真实诱惑

业务方常说：

- 「报表里都有表了，让 AI 自己写 SQL 不就行了？」
- 「固定 Tool 太慢，每个问题都要开发。」

听起来合理，但忽略了三件事：

1. **口径**：同义问法 → 不同 SQL → 不同数字。
2. **安全**：再聪明的模型也会幻觉表名、漏 `tenant_id`。
3. **审计**：出事了无法说「当时官方口径是这条 SQL」。

因此：**探索分析**可以用笼子版 Text-to-SQL；**管理层日报数字**必须用指标字典。

---

## 2. 笼子七件套（缺一不可）

| # | 笼子条 | 作用 |
|:---:|---|---|
| 1 | **白名单表/视图** | 只能查 `vw_*`、`dm_*` 登记对象 |
| 2 | **只读数据库用户** | 无 DDL/DML 权限 |
| 3 | **执行前 EXPLAIN** | 拒绝预估代价过大、全表扫 |
| 4 | **强制 LIMIT** | 默认 100，硬顶 500 |
| 5 | **禁止关键字** | `INSERT/UPDATE/DELETE/DROP/ALTER/TRUNCATE/GRANT` |
| 6 | **强制租户条件** | 包装层追加 `tenant_id = :currentTenant`，不信任模型 WHERE |
| 7 | **独立路由** | 与主决策 Agent 分 API、分权限、分审计 |

---

## 3. 参考实现：CagedSqlExecutor（教学代码）

以下代码**不要**注册为默认 ChatClient 的 Tool；仅供分析员后门或内部运营台。

```java
package com.example.decision.caged;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class CagedSqlExecutor {

    private static final Set<String> ALLOWED_OBJECTS = Set.of(
            "vw_inventory_decision",
            "vw_purchase_overdue_decision",
            "vw_downtime_decision",
            "dm_inventory_snapshot",
            "sync_metadata"
    );

    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(INSERT|UPDATE|DELETE|DROP|ALTER|TRUNCATE|GRANT|REVOKE|CREATE)\\b",
            Pattern.CASE_INSENSITIVE);

    private final NamedParameterJdbcTemplate jdbc;
    private final String forcedTenantId;

    public CagedSqlExecutor(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.forcedTenantId = "TENANT-DEMO"; // 生产从 SecurityContext 取
    }

    public List<java.util.Map<String, Object>> execute(String modelGeneratedSql) {
        String sql = normalize(modelGeneratedSql);
        rejectForbidden(sql);
        rejectNonWhitelistTables(sql);
        sql = injectTenantAndLimit(sql);

        // EXPLAIN 守门
        jdbc.query("EXPLAIN " + sql, new MapSqlParameterSource("tenantId", forcedTenantId),
                (rs, i) -> rs.getString(1));

        return jdbc.query(sql, new MapSqlParameterSource("tenantId", forcedTenantId),
                (rs, rowNum) -> {
                    var meta = rs.getMetaData();
                    var row = new java.util.LinkedHashMap<String, Object>();
                    for (int c = 1; c <= meta.getColumnCount(); c++) {
                        row.put(meta.getColumnLabel(c), rs.getObject(c));
                    }
                    return row;
                });
    }

    private String normalize(String sql) {
        String s = sql.trim();
        if (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1);
        }
        if (!s.toLowerCase(Locale.ROOT).startsWith("select")) {
            throw new SecurityException("仅允许 SELECT");
        }
        return s;
    }

    private void rejectForbidden(String sql) {
        if (FORBIDDEN.matcher(sql).find()) {
            throw new SecurityException("检测到禁止关键字");
        }
    }

    private void rejectNonWhitelistTables(String sql) {
        String lower = sql.toLowerCase(Locale.ROOT);
        for (String table : extractRoughTableNames(lower)) {
            if (!ALLOWED_OBJECTS.contains(table)) {
                throw new SecurityException("未在白名单的表: " + table);
            }
        }
    }

    private List<String> extractRoughTableNames(String lowerSql) {
        // 教学级解析：生产建议用 JSqlParser
        var names = new java.util.ArrayList<String>();
        for (String obj : ALLOWED_OBJECTS) {
            if (lowerSql.contains(obj)) {
                names.add(obj);
            }
        }
        if (names.isEmpty()) {
            throw new SecurityException("SQL 未命中任何白名单对象");
        }
        return names;
    }

    private String injectTenantAndLimit(String sql) {
        String lower = sql.toLowerCase(Locale.ROOT);
        if (!lower.contains("tenant_id")) {
            if (lower.contains("where")) {
                sql = sql + " AND tenant_id = :tenantId";
            } else {
                sql = sql + " WHERE tenant_id = :tenantId";
            }
        }
        if (!lower.contains("limit")) {
            sql = sql + " LIMIT 100";
        }
        return sql;
    }
}
```

**教学点：**

- `tenant_id` 由 Java 注入，**不让模型传 tenantId 参数**。
- 白名单用表名集合 + 解析器双保险；演示用粗糙 `contains`，生产用 **JSqlParser**。
- EXPLAIN 失败或 cost 超阈值直接拒执行。

---

## 4. 若仍要暴露给模型：单独的「分析 Tool」

```java
@Tool(description = """
        【仅内部分析】在自然语言探索场景下，将已审核的问法转为受控 SQL。
        管理层日报数字禁止使用本工具。
        """)
public String exploratoryAnalytics(String questionHint) {
    // 1. 用专用小模型或模板生成 SQL（仍进 CagedSqlExecutor）
    // 2. 返回结果 + 警告：非官方指标口径
    return "...";
}
```

system 提示写死：

> 库存、采购、停机官方数字必须用 `InventoryQueryTool` / `PurchaseQueryTool` / `EquipmentQueryTool`；`exploratoryAnalytics` 不得用于管理层汇报。

---

## 5. Text-to-SQL vs 固定 Tool 对照

| 维度 | 固定 Tool | 笼子 Text-to-SQL |
|---|---|---|
| 口径一致性 | ★★★★★ | ★★ |
| 实施成本 | 每指标开发 | 前期省、后期治理难 |
| 安全 | ★★★★★ | ★★★（依赖笼子质量） |
| 探索未知问题 | ★★ | ★★★★ |
| 审计举证 | 「metricId + 固定 SQL」 | 「当时模型生成了哪条 SQL」 |
| 主 Agent 默认 | **是** | **否** |

---

## 6. 常见事故场景（口试用）

1. **漏 join**：模型只查 `inventory` 不 join `sku`，返回 ID 无名称——固定宽表已 join。
2. **错聚合**：`SUM(available_qty)` 跨仓重复——Tool 里写死分组。
3. **慢查询**：`SELECT *` 百万行——笼子 LIMIT + EXPLAIN。
4. **越权**：模型 SQL 不带 `warehouse_id`——固定 Tool 用 `IN (:warehouseIds)`。
5. **DDL 注入**：`; DROP TABLE`——只读用户 + 关键字拒绝。

---

## 7. 老师结论

```
主路径：模式 3 宽表 + 指标字典 + 一 Tool 一能力
备路径：模式 4 笼子 SQL，仅分析员、仅探索、仅非官方口径
禁止：   把 executeSql(String) 注册给全员管理层 Agent
```

---

## 8. 本节作业

1. 给 `CagedSqlExecutor` 补一条：若 EXPLAIN 结果含 `Seq Scan` 且表行数 > 10 万则拒绝（可先写伪逻辑）。
2. 写一段 100 字给业务：为什么「超期采购 Top」不能改用 Text-to-SQL。
3. 确认你们项目 **没有** `query(sql)` 类 Tool 出现在 `defaultTools` 里。

下一节：`07-新鲜度对账与缓存.md`。

package com.example.decision;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务能力：超期采购订单查询（官方口径：期望到货日 < 今天 且 仍有未收货数量）
 * 指标字典 ID：PO_OVERDUE_TOP
 */
@Component
public class PurchaseQueryTool {

    private static final String METRIC_ID = "PO_OVERDUE_TOP";
    private static final String METRIC_NAME = "超期采购订单";

    private final NamedParameterJdbcTemplate jdbc;
    private final WarehouseAccessService access;

    public PurchaseQueryTool(NamedParameterJdbcTemplate jdbc, WarehouseAccessService access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    @Tool(description = """
            查询超期未到货的采购订单，按超期天数降序返回 Top N。
            适用问题：「哪些采购单超期了？」「超期最严重的采购 Top 5」
            可选 warehouseId 过滤单仓；不传则查当前用户有权访问的全部仓库。
            """)
    public String queryOverduePurchaseTop(
            @ToolParam(description = "仓库编码，可选；不传表示用户有权访问的全部仓") String warehouseId,
            @ToolParam(description = "返回条数，默认 5，最大 20") Integer topN) {
        try {
            String tenantId = access.currentTenantId();
            int limit = normalizeTopN(topN);

            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("topN", limit);

            StringBuilder sql = new StringBuilder("""
                    SELECT po_id, supplier_name, warehouse_id, warehouse_name,
                           sku_id, sku_name, pending_qty, expected_arrival_date,
                           overdue_days, status, data_as_of
                    FROM vw_purchase_overdue_decision
                    WHERE tenant_id = :tenantId
                    """);

            if (warehouseId != null && !warehouseId.isBlank()) {
                access.assertWarehouseAccessible(warehouseId);
                sql.append(" AND warehouse_id = :warehouseId");
                params.addValue("warehouseId", warehouseId);
            } else {
                List<String> allowed = access.accessibleWarehouseIds().stream().sorted().toList();
                if (allowed.isEmpty()) {
                    return ToolResultFormatter.error(METRIC_ID, "当前用户无任何仓库权限");
                }
                sql.append(" AND warehouse_id IN (:warehouseIds)");
                params.addValue("warehouseIds", allowed);
            }

            sql.append(" ORDER BY overdue_days DESC, pending_qty DESC LIMIT :topN");

            List<Map<String, Object>> rows = jdbc.query(sql.toString(), params, this::mapOverdueRow);
            OffsetDateTime dataAsOf = resolveSyncTime(tenantId, "purchase");

            return ToolResultFormatter.success(METRIC_ID, METRIC_NAME, dataAsOf, rows);
        } catch (SecurityException e) {
            return ToolResultFormatter.error(METRIC_ID, e.getMessage());
        } catch (Exception e) {
            return ToolResultFormatter.error(METRIC_ID, "查询失败: " + e.getMessage());
        }
    }

    @Tool(description = """
            按采购单号查询超期明细（仅当该单在超期视图内时返回）。
            适用问题：「PO-2026-001 超期多少天？还剩多少未到？」
            """)
    public String queryOverduePurchaseByPoId(
            @ToolParam(description = "采购单号，如 PO-2026-001") String poId) {
        try {
            if (poId == null || poId.isBlank()) {
                return ToolResultFormatter.error(METRIC_ID, "poId 不能为空");
            }
            String tenantId = access.currentTenantId();

            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("poId", poId);

            String sql = """
                    SELECT po_id, supplier_name, warehouse_id, warehouse_name,
                           sku_id, sku_name, pending_qty, expected_arrival_date,
                           overdue_days, status, data_as_of
                    FROM vw_purchase_overdue_decision
                    WHERE tenant_id = :tenantId
                      AND po_id = :poId
                    LIMIT 10
                    """;

            List<Map<String, Object>> rows = jdbc.query(sql, params, this::mapOverdueRow);

            for (Map<String, Object> row : rows) {
                access.assertWarehouseAccessible((String) row.get("warehouseId"));
            }

            if (rows.isEmpty()) {
                return ToolResultFormatter.error(METRIC_ID,
                        "未找到超期采购记录（可能已关闭、未超期或单号错误）");
            }

            OffsetDateTime dataAsOf = resolveSyncTime(tenantId, "purchase");
            return ToolResultFormatter.success(METRIC_ID, METRIC_NAME, dataAsOf, rows);
        } catch (SecurityException e) {
            return ToolResultFormatter.error(METRIC_ID, e.getMessage());
        } catch (Exception e) {
            return ToolResultFormatter.error(METRIC_ID, "查询失败: " + e.getMessage());
        }
    }

    private Map<String, Object> mapOverdueRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("poId", rs.getString("po_id"));
        row.put("supplierName", rs.getString("supplier_name"));
        row.put("warehouseId", rs.getString("warehouse_id"));
        row.put("warehouseName", rs.getString("warehouse_name"));
        row.put("skuId", rs.getString("sku_id"));
        row.put("skuName", rs.getString("sku_name"));
        row.put("pendingQty", rs.getBigDecimal("pending_qty"));
        row.put("expectedArrivalDate", rs.getDate("expected_arrival_date").toString());
        row.put("overdueDays", rs.getInt("overdue_days"));
        row.put("status", rs.getString("status"));
        row.put("dataAsOf", toOffsetDateTime(rs.getTimestamp("data_as_of")));
        return row;
    }

    private int normalizeTopN(Integer topN) {
        if (topN == null || topN < 1) {
            return 5;
        }
        return Math.min(topN, 20);
    }

    private OffsetDateTime resolveSyncTime(String tenantId, String dataset) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("dataset", dataset);
        List<OffsetDateTime> times = jdbc.query("""
                SELECT last_sync_at FROM sync_metadata
                WHERE tenant_id = :tenantId AND dataset_name = :dataset
                """, params, (rs, n) -> toOffsetDateTime(rs.getTimestamp("last_sync_at")));
        return times.isEmpty()
                ? OffsetDateTime.now(ZoneOffset.ofHours(8))
                : times.get(0);
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp ts) {
        if (ts == null) {
            return null;
        }
        return ts.toInstant().atOffset(ZoneOffset.ofHours(8));
    }
}

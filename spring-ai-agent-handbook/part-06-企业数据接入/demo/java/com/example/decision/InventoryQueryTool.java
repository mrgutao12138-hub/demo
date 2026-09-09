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
 * 业务能力：查询指定仓库 SKU 的可用库存（官方口径：可用量 = 在库 - 预留）
 * 指标字典 ID：INV_AVAILABLE_QTY
 */
@Component
public class InventoryQueryTool {

    private static final String METRIC_ID = "INV_AVAILABLE_QTY";
    private static final String METRIC_NAME = "库存可用量";

    private final NamedParameterJdbcTemplate jdbc;
    private final WarehouseAccessService access;

    public InventoryQueryTool(NamedParameterJdbcTemplate jdbc, WarehouseAccessService access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    @Tool(description = """
            查询某仓库某 SKU 的可用库存数量。
            适用问题：「华东仓 SKU-1001 还有多少可用？」「某仓某物料能发多少？」
            必须提供 warehouseId 和 skuId。不可用此工具查采购或停机。
            """)
    public String queryAvailableInventory(
            @ToolParam(description = "仓库编码，如 WH-EAST") String warehouseId,
            @ToolParam(description = "SKU 编码，如 SKU-1001") String skuId) {
        try {
            access.assertWarehouseAccessible(warehouseId);
            String tenantId = access.currentTenantId();

            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("warehouseId", warehouseId)
                    .addValue("skuId", skuId);

            String sql = """
                    SELECT warehouse_id, warehouse_name, sku_id, sku_name, spec, unit,
                           on_hand_qty, reserved_qty, available_qty, data_as_of
                    FROM vw_inventory_decision
                    WHERE tenant_id = :tenantId
                      AND warehouse_id = :warehouseId
                      AND sku_id = :skuId
                    LIMIT 1
                    """;

            List<Map<String, Object>> rows = jdbc.query(sql, params, (rs, rowNum) -> mapInventoryRow(rs));

            OffsetDateTime dataAsOf = resolveSyncTime(tenantId, "inventory");
            if (!rows.isEmpty() && rows.get(0).get("dataAsOf") != null) {
                dataAsOf = (OffsetDateTime) rows.get(0).get("dataAsOf");
            }

            if (rows.isEmpty()) {
                return ToolResultFormatter.error(METRIC_ID,
                        "未找到库存记录，请确认 warehouseId/skuId 是否正确");
            }
            return ToolResultFormatter.success(METRIC_ID, METRIC_NAME, dataAsOf, rows);
        } catch (SecurityException e) {
            return ToolResultFormatter.error(METRIC_ID, e.getMessage());
        } catch (Exception e) {
            return ToolResultFormatter.error(METRIC_ID, "查询失败: " + e.getMessage());
        }
    }

    @Tool(description = """
            列出某仓库可用库存偏紧的 SKU（可用量低于阈值）。
            适用问题：「华南仓哪些货要补？」「低于 50 件的 SKU 有哪些？」
            """)
    public String listLowAvailableInventory(
            @ToolParam(description = "仓库编码") String warehouseId,
            @ToolParam(description = "可用量上限阈值，默认 50") Double threshold) {
        try {
            access.assertWarehouseAccessible(warehouseId);
            String tenantId = access.currentTenantId();
            double limit = threshold == null ? 50.0 : threshold;

            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("warehouseId", warehouseId)
                    .addValue("threshold", limit);

            String sql = """
                    SELECT warehouse_id, warehouse_name, sku_id, sku_name, available_qty, data_as_of
                    FROM vw_inventory_decision
                    WHERE tenant_id = :tenantId
                      AND warehouse_id = :warehouseId
                      AND available_qty < :threshold
                    ORDER BY available_qty ASC
                    LIMIT 20
                    """;

            List<Map<String, Object>> rows = jdbc.query(sql, params, (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("warehouseId", rs.getString("warehouse_id"));
                row.put("warehouseName", rs.getString("warehouse_name"));
                row.put("skuId", rs.getString("sku_id"));
                row.put("skuName", rs.getString("sku_name"));
                row.put("availableQty", rs.getBigDecimal("available_qty"));
                row.put("dataAsOf", toOffsetDateTime(rs.getTimestamp("data_as_of")));
                return row;
            });

            OffsetDateTime dataAsOf = resolveSyncTime(tenantId, "inventory");
            return ToolResultFormatter.success(METRIC_ID, METRIC_NAME + "（低库存清单）", dataAsOf, rows);
        } catch (SecurityException e) {
            return ToolResultFormatter.error(METRIC_ID, e.getMessage());
        } catch (Exception e) {
            return ToolResultFormatter.error(METRIC_ID, "查询失败: " + e.getMessage());
        }
    }

    private Map<String, Object> mapInventoryRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("warehouseId", rs.getString("warehouse_id"));
        row.put("warehouseName", rs.getString("warehouse_name"));
        row.put("skuId", rs.getString("sku_id"));
        row.put("skuName", rs.getString("sku_name"));
        row.put("spec", rs.getString("spec"));
        row.put("unit", rs.getString("unit"));
        row.put("onHandQty", rs.getBigDecimal("on_hand_qty"));
        row.put("reservedQty", rs.getBigDecimal("reserved_qty"));
        row.put("availableQty", rs.getBigDecimal("available_qty"));
        row.put("dataAsOf", toOffsetDateTime(rs.getTimestamp("data_as_of")));
        return row;
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

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
 * 业务能力：停机超过 30 分钟的产线/设备（EMS）
 * 若贵司实际系统是 MES（制造执行），表结构与 Tool 划分相同，只换同步源。
 * 指标字典 ID：EMS_DOWNTIME_OVER_30M
 */
@Component
public class EquipmentQueryTool {

    private static final String METRIC_ID = "EMS_DOWNTIME_OVER_30M";
    private static final String METRIC_NAME = "停机超过30分钟";

    private final NamedParameterJdbcTemplate jdbc;
    private final WarehouseAccessService access;

    public EquipmentQueryTool(NamedParameterJdbcTemplate jdbc, WarehouseAccessService access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    @Tool(description = """
            查询停机时长达到或超过 30 分钟的产线/设备，按停机分钟数降序。
            适用问题：「今天哪些产线停机超过半小时？」「C 线停了多久？」
            EMS 指设备/能源/运维系统；若公司是 MES，查询逻辑相同。
            可选 warehouseId 过滤园区/工厂关联仓。
            """)
    public String queryDowntimeOver30Minutes(
            @ToolParam(description = "仓库/工厂编码，可选") String warehouseId,
            @ToolParam(description = "返回条数，默认 10，最大 30") Integer topN) {
        try {
            String tenantId = access.currentTenantId();
            int limit = normalizeTopN(topN);

            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("topN", limit);

            StringBuilder sql = new StringBuilder("""
                    SELECT event_id, equipment_id, equipment_name, production_line,
                           warehouse_id, warehouse_name, start_time, end_time,
                           duration_minutes, is_ongoing, reason, data_as_of
                    FROM vw_downtime_decision
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

            sql.append(" ORDER BY duration_minutes DESC, start_time DESC LIMIT :topN");

            List<Map<String, Object>> rows = jdbc.query(sql.toString(), params, this::mapDowntimeRow);
            OffsetDateTime dataAsOf = resolveSyncTime(tenantId, "downtime");

            return ToolResultFormatter.success(METRIC_ID, METRIC_NAME, dataAsOf, rows);
        } catch (SecurityException e) {
            return ToolResultFormatter.error(METRIC_ID, e.getMessage());
        } catch (Exception e) {
            return ToolResultFormatter.error(METRIC_ID, "查询失败: " + e.getMessage());
        }
    }

    @Tool(description = """
            按产线编码查询最近一次达到 30 分钟以上停机的事件。
            适用问题：「LINE-C 为什么停？」「A 线今天停了多久？」
            """)
    public String queryDowntimeByProductionLine(
            @ToolParam(description = "产线编码，如 LINE-A") String productionLine) {
        try {
            if (productionLine == null || productionLine.isBlank()) {
                return ToolResultFormatter.error(METRIC_ID, "productionLine 不能为空");
            }
            String tenantId = access.currentTenantId();

            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("productionLine", productionLine);

            String sql = """
                    SELECT event_id, equipment_id, equipment_name, production_line,
                           warehouse_id, warehouse_name, start_time, end_time,
                           duration_minutes, is_ongoing, reason, data_as_of
                    FROM vw_downtime_decision
                    WHERE tenant_id = :tenantId
                      AND production_line = :productionLine
                    ORDER BY start_time DESC
                    LIMIT 5
                    """;

            List<Map<String, Object>> rows = jdbc.query(sql, params, this::mapDowntimeRow);

            for (Map<String, Object> row : rows) {
                access.assertWarehouseAccessible((String) row.get("warehouseId"));
            }

            if (rows.isEmpty()) {
                return ToolResultFormatter.error(METRIC_ID,
                        "未找到该产线 ≥30 分钟的停机记录（可能停机不足 30 分钟或产线编码错误）");
            }

            OffsetDateTime dataAsOf = resolveSyncTime(tenantId, "downtime");
            return ToolResultFormatter.success(METRIC_ID, METRIC_NAME, dataAsOf, rows);
        } catch (SecurityException e) {
            return ToolResultFormatter.error(METRIC_ID, e.getMessage());
        } catch (Exception e) {
            return ToolResultFormatter.error(METRIC_ID, "查询失败: " + e.getMessage());
        }
    }

    private Map<String, Object> mapDowntimeRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("eventId", rs.getString("event_id"));
        row.put("equipmentId", rs.getString("equipment_id"));
        row.put("equipmentName", rs.getString("equipment_name"));
        row.put("productionLine", rs.getString("production_line"));
        row.put("warehouseId", rs.getString("warehouse_id"));
        row.put("warehouseName", rs.getString("warehouse_name"));
        row.put("startTime", toOffsetDateTime(rs.getTimestamp("start_time")));
        row.put("endTime", toOffsetDateTime(rs.getTimestamp("end_time")));
        row.put("durationMinutes", rs.getBigDecimal("duration_minutes"));
        row.put("isOngoing", rs.getBoolean("is_ongoing"));
        row.put("reason", rs.getString("reason"));
        row.put("dataAsOf", toOffsetDateTime(rs.getTimestamp("data_as_of")));
        return row;
    }

    private int normalizeTopN(Integer topN) {
        if (topN == null || topN < 1) {
            return 10;
        }
        return Math.min(topN, 30);
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

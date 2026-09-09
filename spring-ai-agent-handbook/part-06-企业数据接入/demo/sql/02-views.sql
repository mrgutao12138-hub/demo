-- ============================================================
-- 决策宽表 / 视图（模式 2、模式 3 的 Agent 查询面）
-- 只读账号仅授予这些对象 + sync_metadata 的 SELECT
-- ============================================================

-- 宽表 1：库存决策视图
CREATE OR REPLACE VIEW vw_inventory_decision AS
SELECT
    i.tenant_id,
    i.warehouse_id,
    w.warehouse_name,
    w.region,
    i.sku_id,
    s.sku_name,
    s.spec,
    s.unit,
    i.on_hand_qty,
    i.reserved_qty,
    i.available_qty,
    i.snapshot_at AS data_as_of
FROM inventory i
JOIN warehouse w ON w.tenant_id = i.tenant_id AND w.warehouse_id = i.warehouse_id
JOIN sku s ON s.tenant_id = i.tenant_id AND s.sku_id = i.sku_id
WHERE w.is_active = TRUE;

-- 宽表 2：超期采购决策视图（未关闭且期望到货日早于今天）
CREATE OR REPLACE VIEW vw_purchase_overdue_decision AS
SELECT
    po.tenant_id,
    po.po_id,
    po.supplier_name,
    po.warehouse_id,
    w.warehouse_name,
    pol.line_id,
    pol.sku_id,
    s.sku_name,
    pol.ordered_qty,
    pol.received_qty,
    (pol.ordered_qty - pol.received_qty) AS pending_qty,
    po.order_date,
    po.expected_arrival_date,
    (CURRENT_DATE - po.expected_arrival_date) AS overdue_days,
    po.status,
    po.updated_at AS data_as_of
FROM purchase_order po
JOIN purchase_order_line pol
  ON pol.tenant_id = po.tenant_id AND pol.po_id = po.po_id
JOIN warehouse w
  ON w.tenant_id = po.tenant_id AND w.warehouse_id = po.warehouse_id
JOIN sku s
  ON s.tenant_id = pol.tenant_id AND s.sku_id = pol.sku_id
WHERE po.status IN ('OPEN', 'PARTIAL')
  AND po.expected_arrival_date < CURRENT_DATE
  AND (pol.ordered_qty - pol.received_qty) > 0;

-- 宽表 3：停机决策视图（单次停机 ≥ 30 分钟，或仍在停机且已超 30 分钟）
CREATE OR REPLACE VIEW vw_downtime_decision AS
SELECT
    d.tenant_id,
    d.event_id,
    e.equipment_id,
    e.equipment_name,
    e.production_line,
    e.warehouse_id,
    w.warehouse_name,
    d.start_time,
    d.end_time,
    d.reason,
    EXTRACT(EPOCH FROM (
        COALESCE(d.end_time, NOW()) - d.start_time
    )) / 60.0 AS duration_minutes,
    CASE WHEN d.end_time IS NULL THEN TRUE ELSE FALSE END AS is_ongoing,
    GREATEST(d.created_at, e.updated_at) AS data_as_of
FROM downtime_event d
JOIN equipment e
  ON e.tenant_id = d.tenant_id AND e.equipment_id = d.equipment_id
JOIN warehouse w
  ON w.tenant_id = e.tenant_id AND w.warehouse_id = e.warehouse_id
WHERE EXTRACT(EPOCH FROM (COALESCE(d.end_time, NOW()) - d.start_time)) / 60.0 >= 30;

-- 物化宽表示例（模式 3 推荐生产形态；演示库用普通表模拟同步结果）
CREATE TABLE IF NOT EXISTS dm_inventory_snapshot (
    tenant_id      VARCHAR(32) NOT NULL,
    warehouse_id   VARCHAR(32) NOT NULL,
    warehouse_name VARCHAR(128) NOT NULL,
    sku_id         VARCHAR(32) NOT NULL,
    sku_name       VARCHAR(256) NOT NULL,
    available_qty  NUMERIC(18, 4) NOT NULL,
    data_as_of     TIMESTAMPTZ    NOT NULL,
    PRIMARY KEY (tenant_id, warehouse_id, sku_id)
);

-- 从视图灌入物化宽表（真实项目由 Airflow / Canal / 自研同步任务执行）
INSERT INTO dm_inventory_snapshot (tenant_id, warehouse_id, warehouse_name, sku_id, sku_name, available_qty, data_as_of)
SELECT tenant_id, warehouse_id, warehouse_name, sku_id, sku_name, available_qty, data_as_of
FROM vw_inventory_decision
ON CONFLICT (tenant_id, warehouse_id, sku_id) DO UPDATE
SET warehouse_name = EXCLUDED.warehouse_name,
    sku_name       = EXCLUDED.sku_name,
    available_qty  = EXCLUDED.available_qty,
    data_as_of     = EXCLUDED.data_as_of;

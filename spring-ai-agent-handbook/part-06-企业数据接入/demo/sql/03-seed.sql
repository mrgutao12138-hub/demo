-- ============================================================
-- 演示数据：足够回答三类必测问题
-- 1. 某仓某 SKU 可用库存
-- 2. 超期采购 Top N
-- 3. 停机超过 30 分钟的产线
-- ============================================================

INSERT INTO tenant (tenant_id, tenant_name) VALUES
    ('TENANT-DEMO', '演示集团');

INSERT INTO warehouse (warehouse_id, tenant_id, warehouse_name, region) VALUES
    ('WH-EAST',  'TENANT-DEMO', '华东成品仓', '华东'),
    ('WH-SOUTH', 'TENANT-DEMO', '华南原料仓', '华南'),
    ('WH-NORTH', 'TENANT-DEMO', '华北备件仓', '华北');

INSERT INTO sku (sku_id, tenant_id, sku_name, spec, unit) VALUES
    ('SKU-1001', 'TENANT-DEMO', '内六角螺丝M8', 'M8×30 镀锌', '件'),
    ('SKU-1002', 'TENANT-DEMO', '深沟球轴承6205', '6205-2RS', '个'),
    ('SKU-1003', 'TENANT-DEMO', '三相异步电机', 'YE3-7.5kW', '台');

-- 库存：华东充足，华南偏紧，华北有备件
INSERT INTO inventory (tenant_id, warehouse_id, sku_id, on_hand_qty, reserved_qty, snapshot_at) VALUES
    ('TENANT-DEMO', 'WH-EAST',  'SKU-1001', 500,  50,  '2026-09-08 18:00:00+08'),
    ('TENANT-DEMO', 'WH-SOUTH', 'SKU-1001',  80,  10,  '2026-09-08 17:30:00+08'),
    ('TENANT-DEMO', 'WH-NORTH', 'SKU-1001', 200,  20,  '2026-09-08 16:00:00+08'),
    ('TENANT-DEMO', 'WH-EAST',  'SKU-1002', 120,   0,  '2026-09-08 18:00:00+08'),
    ('TENANT-DEMO', 'WH-SOUTH', 'SKU-1002',  15,   5,  '2026-09-08 17:45:00+08'),
    ('TENANT-DEMO', 'WH-NORTH', 'SKU-1003',   8,   2,  '2026-09-08 15:00:00+08');

-- 采购：两单明显超期，一单正常在途
INSERT INTO purchase_order (po_id, tenant_id, supplier_name, warehouse_id, order_date, expected_arrival_date, status, updated_at) VALUES
    ('PO-2026-001', 'TENANT-DEMO', '沪东紧固件有限公司', 'WH-SOUTH', '2026-07-10', '2026-08-05', 'OPEN',    '2026-09-08 10:00:00+08'),
    ('PO-2026-002', 'TENANT-DEMO', '苏南轴承贸易',       'WH-EAST',  '2026-07-20', '2026-08-20', 'PARTIAL', '2026-09-08 11:00:00+08'),
    ('PO-2026-003', 'TENANT-DEMO', '华北机电供应',       'WH-NORTH', '2026-08-25', '2026-09-15', 'OPEN',    '2026-09-08 09:00:00+08');

INSERT INTO purchase_order_line (line_id, tenant_id, po_id, sku_id, ordered_qty, received_qty) VALUES
    ('POL-001', 'TENANT-DEMO', 'PO-2026-001', 'SKU-1001', 1000,   0),
    ('POL-002', 'TENANT-DEMO', 'PO-2026-002', 'SKU-1002',  500, 200),
    ('POL-003', 'TENANT-DEMO', 'PO-2026-003', 'SKU-1003',   10,   0);

-- 设备与停机：产线 A 停 45 分钟，产线 B 停 15 分钟（不应进 Top），产线 C 仍在停机 60+ 分钟
INSERT INTO equipment (equipment_id, tenant_id, equipment_name, production_line, warehouse_id, status, updated_at) VALUES
    ('EQ-LINE-A-01', 'TENANT-DEMO', 'A线自动装配站', 'LINE-A', 'WH-EAST',  'DOWN',    '2026-09-08 18:30:00+08'),
    ('EQ-LINE-B-01', 'TENANT-DEMO', 'B线包装机',     'LINE-B', 'WH-SOUTH', 'RUNNING', '2026-09-08 18:00:00+08'),
    ('EQ-LINE-C-01', 'TENANT-DEMO', 'C线注塑机',     'LINE-C', 'WH-EAST',  'DOWN',    '2026-09-08 19:00:00+08');

INSERT INTO downtime_event (event_id, tenant_id, equipment_id, start_time, end_time, reason) VALUES
    ('DT-001', 'TENANT-DEMO', 'EQ-LINE-A-01', '2026-09-08 14:00:00+08', '2026-09-08 14:45:00+08', '伺服驱动器报警 E-204'),
    ('DT-002', 'TENANT-DEMO', 'EQ-LINE-B-01', '2026-09-08 10:00:00+08', '2026-09-08 10:15:00+08', '换型调试'),
    ('DT-003', 'TENANT-DEMO', 'EQ-LINE-C-01', '2026-09-08 17:30:00+08', NULL,                      '液压站压力不足，检修中');

INSERT INTO sync_metadata (tenant_id, dataset_name, last_sync_at, source_system) VALUES
    ('TENANT-DEMO', 'inventory', '2026-09-08 18:00:00+08', 'WMS'),
    ('TENANT-DEMO', 'purchase',  '2026-09-08 11:00:00+08', 'ERP'),
    ('TENANT-DEMO', 'downtime',  '2026-09-08 19:00:00+08', 'EMS');

-- 刷新物化宽表
INSERT INTO dm_inventory_snapshot (tenant_id, warehouse_id, warehouse_name, sku_id, sku_name, available_qty, data_as_of)
SELECT tenant_id, warehouse_id, warehouse_name, sku_id, sku_name, available_qty, data_as_of
FROM vw_inventory_decision
ON CONFLICT (tenant_id, warehouse_id, sku_id) DO UPDATE
SET warehouse_name = EXCLUDED.warehouse_name,
    sku_name       = EXCLUDED.sku_name,
    available_qty  = EXCLUDED.available_qty,
    data_as_of     = EXCLUDED.data_as_of;

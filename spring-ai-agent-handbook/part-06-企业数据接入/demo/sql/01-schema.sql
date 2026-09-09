-- ============================================================
-- 决策演示库：业务明细表（模拟 ERP / WMS / EMS 落地后的结构）
-- 生产侧由同步任务写入；Agent 只读账号只能 SELECT 宽表/视图
-- ============================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 租户
CREATE TABLE IF NOT EXISTS tenant (
    tenant_id   VARCHAR(32) PRIMARY KEY,
    tenant_name VARCHAR(128) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 仓库（WMS）
CREATE TABLE IF NOT EXISTS warehouse (
    warehouse_id   VARCHAR(32) NOT NULL,
    tenant_id      VARCHAR(32) NOT NULL REFERENCES tenant(tenant_id),
    warehouse_name VARCHAR(128) NOT NULL,
    region         VARCHAR(64)  NOT NULL,
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, warehouse_id)
);

-- SKU 主数据（ERP）
CREATE TABLE IF NOT EXISTS sku (
    sku_id     VARCHAR(32) NOT NULL,
    tenant_id  VARCHAR(32) NOT NULL REFERENCES tenant(tenant_id),
    sku_name   VARCHAR(256) NOT NULL,
    spec       VARCHAR(256),
    unit       VARCHAR(16)  NOT NULL DEFAULT '件',
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, sku_id)
);

-- 库存快照（WMS）
CREATE TABLE IF NOT EXISTS inventory (
    tenant_id      VARCHAR(32) NOT NULL,
    warehouse_id   VARCHAR(32) NOT NULL,
    sku_id         VARCHAR(32) NOT NULL,
    on_hand_qty    NUMERIC(18, 4) NOT NULL DEFAULT 0,
    reserved_qty   NUMERIC(18, 4) NOT NULL DEFAULT 0,
    available_qty  NUMERIC(18, 4) NOT NULL GENERATED ALWAYS AS (on_hand_qty - reserved_qty) STORED,
    snapshot_at    TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, warehouse_id, sku_id),
    FOREIGN KEY (tenant_id, warehouse_id) REFERENCES warehouse(tenant_id, warehouse_id),
    FOREIGN KEY (tenant_id, sku_id) REFERENCES sku(tenant_id, sku_id)
);

-- 采购订单头（ERP）
CREATE TABLE IF NOT EXISTS purchase_order (
    po_id                  VARCHAR(32) NOT NULL,
    tenant_id              VARCHAR(32) NOT NULL REFERENCES tenant(tenant_id),
    supplier_name          VARCHAR(256) NOT NULL,
    warehouse_id           VARCHAR(32) NOT NULL,
    order_date             DATE         NOT NULL,
    expected_arrival_date  DATE         NOT NULL,
    status                 VARCHAR(32)  NOT NULL, -- OPEN / PARTIAL / CLOSED
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, po_id),
    FOREIGN KEY (tenant_id, warehouse_id) REFERENCES warehouse(tenant_id, warehouse_id)
);

-- 采购订单行
CREATE TABLE IF NOT EXISTS purchase_order_line (
    line_id      VARCHAR(32) NOT NULL,
    tenant_id    VARCHAR(32) NOT NULL,
    po_id        VARCHAR(32) NOT NULL,
    sku_id       VARCHAR(32) NOT NULL,
    ordered_qty  NUMERIC(18, 4) NOT NULL,
    received_qty NUMERIC(18, 4) NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, line_id),
    FOREIGN KEY (tenant_id, po_id) REFERENCES purchase_order(tenant_id, po_id),
    FOREIGN KEY (tenant_id, sku_id) REFERENCES sku(tenant_id, sku_id)
);

-- 设备（EMS：设备/能源/运维；若贵司是 MES，表名可换，接入套路相同）
CREATE TABLE IF NOT EXISTS equipment (
    equipment_id      VARCHAR(32) NOT NULL,
    tenant_id         VARCHAR(32) NOT NULL REFERENCES tenant(tenant_id),
    equipment_name    VARCHAR(256) NOT NULL,
    production_line   VARCHAR(64)  NOT NULL,
    warehouse_id      VARCHAR(32) NOT NULL,
    status            VARCHAR(32)  NOT NULL, -- RUNNING / IDLE / DOWN
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, equipment_id),
    FOREIGN KEY (tenant_id, warehouse_id) REFERENCES warehouse(tenant_id, warehouse_id)
);

-- 停机事件（EMS）
CREATE TABLE IF NOT EXISTS downtime_event (
    event_id        VARCHAR(32) NOT NULL,
    tenant_id       VARCHAR(32) NOT NULL REFERENCES tenant(tenant_id),
    equipment_id    VARCHAR(32) NOT NULL,
    start_time      TIMESTAMPTZ NOT NULL,
    end_time        TIMESTAMPTZ, -- NULL 表示仍在停机
    reason          VARCHAR(512),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, event_id),
    FOREIGN KEY (tenant_id, equipment_id) REFERENCES equipment(tenant_id, equipment_id)
);

-- 同步元数据：记录宽表刷新时间，供 dataAsOf 使用
CREATE TABLE IF NOT EXISTS sync_metadata (
    tenant_id    VARCHAR(32) NOT NULL,
    dataset_name VARCHAR(64) NOT NULL,
    last_sync_at TIMESTAMPTZ  NOT NULL,
    source_system VARCHAR(32) NOT NULL, -- ERP / WMS / EMS
    PRIMARY KEY (tenant_id, dataset_name)
);

CREATE INDEX idx_inventory_tenant_wh ON inventory(tenant_id, warehouse_id);
CREATE INDEX idx_po_tenant_status ON purchase_order(tenant_id, status, expected_arrival_date);
CREATE INDEX idx_downtime_tenant_start ON downtime_event(tenant_id, start_time);

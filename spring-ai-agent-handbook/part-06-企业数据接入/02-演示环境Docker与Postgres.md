# 02 · 演示环境 Docker 与 Postgres

> 这一节不动 Spring，先把**决策读模型**在本机立起来。  
> 你会得到：完整表结构、三个决策宽表/视图、够用的种子数据、只读账号。  
> 后面 Java Tool 只连这颗库。

---

## 1. 目录结构

```
part-06-企业数据接入/demo/
├── docker-compose.yml
└── sql/
    ├── 01-schema.sql        # 明细表（模拟同步落地后的 ODS）
    ├── 02-views.sql         # 决策视图 + 物化宽表示例
    ├── 03-seed.sql          # 演示数据
    └── 04-readonly-user.sql # Agent 只读账号
```

---

## 2. 一键启动

```bash
cd part-06-企业数据接入/demo
docker compose up -d
docker compose ps
```

健康后连接信息：

| 项 | 值 |
|---|---|
| 主机端口 | `localhost:5433` |
| 数据库 | `decision_demo` |
| 管理员 | `decision_admin` / `decision_admin_pass` |
| **Agent 只读** | `decision_readonly` / `decision_readonly_pass` |

验证只读账号只能查宽表：

```bash
docker exec -it decision-postgres-demo psql -U decision_readonly -d decision_demo -c \
  "SELECT sku_id, available_qty FROM vw_inventory_decision WHERE warehouse_id='WH-EAST' LIMIT 3;"
```

应成功。若尝试写：

```bash
docker exec -it decision-postgres-demo psql -U decision_readonly -d decision_demo -c \
  "DELETE FROM dm_inventory_snapshot WHERE 1=1;"
```

应报 **permission denied**——这正是我们要的。

---

## 3. docker-compose.yml（完整）

```yaml
# 决策宽表演示库 — 仅用于学习与本地验收，勿直连生产
services:
  decision-postgres:
    image: postgres:16-alpine
    container_name: decision-postgres-demo
    environment:
      POSTGRES_DB: decision_demo
      POSTGRES_USER: decision_admin
      POSTGRES_PASSWORD: decision_admin_pass
    ports:
      - "5433:5432"
    volumes:
      - ./sql/01-schema.sql:/docker-entrypoint-initdb.d/01-schema.sql:ro
      - ./sql/02-views.sql:/docker-entrypoint-initdb.d/02-views.sql:ro
      - ./sql/03-seed.sql:/docker-entrypoint-initdb.d/03-seed.sql:ro
      - ./sql/04-readonly-user.sql:/docker-entrypoint-initdb.d/04-readonly-user.sql:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U decision_admin -d decision_demo"]
      interval: 5s
      timeout: 3s
      retries: 10
```

---

## 4. 明细表结构（01-schema.sql 摘要）

### 4.1 表清单

| 表名 | 来源系统 | 用途 |
|---|---|---|
| `tenant` | 平台 | 租户 |
| `warehouse` | WMS | 仓库主数据 |
| `sku` | ERP | 物料主数据 |
| `inventory` | WMS | 库存快照 |
| `purchase_order` | ERP | 采购头 |
| `purchase_order_line` | ERP | 采购行 |
| `equipment` | EMS | 设备（MES 同套路） |
| `downtime_event` | EMS | 停机事件 |
| `sync_metadata` | 同步任务 | 各数据集 `last_sync_at` |

### 4.2 完整 DDL

以下与 `demo/sql/01-schema.sql` 一致，可直接复制执行：

```sql
-- ============================================================
-- 决策演示库：业务明细表（模拟 ERP / WMS / EMS 落地后的结构）
-- ============================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS tenant (
    tenant_id   VARCHAR(32) PRIMARY KEY,
    tenant_name VARCHAR(128) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS warehouse (
    warehouse_id   VARCHAR(32) NOT NULL,
    tenant_id      VARCHAR(32) NOT NULL REFERENCES tenant(tenant_id),
    warehouse_name VARCHAR(128) NOT NULL,
    region         VARCHAR(64)  NOT NULL,
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, warehouse_id)
);

CREATE TABLE IF NOT EXISTS sku (
    sku_id     VARCHAR(32) NOT NULL,
    tenant_id  VARCHAR(32) NOT NULL REFERENCES tenant(tenant_id),
    sku_name   VARCHAR(256) NOT NULL,
    spec       VARCHAR(256),
    unit       VARCHAR(16)  NOT NULL DEFAULT '件',
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, sku_id)
);

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

CREATE TABLE IF NOT EXISTS purchase_order (
    po_id                  VARCHAR(32) NOT NULL,
    tenant_id              VARCHAR(32) NOT NULL REFERENCES tenant(tenant_id),
    supplier_name          VARCHAR(256) NOT NULL,
    warehouse_id           VARCHAR(32) NOT NULL,
    order_date             DATE         NOT NULL,
    expected_arrival_date  DATE         NOT NULL,
    status                 VARCHAR(32)  NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, po_id),
    FOREIGN KEY (tenant_id, warehouse_id) REFERENCES warehouse(tenant_id, warehouse_id)
);

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

CREATE TABLE IF NOT EXISTS equipment (
    equipment_id      VARCHAR(32) NOT NULL,
    tenant_id         VARCHAR(32) NOT NULL REFERENCES tenant(tenant_id),
    equipment_name    VARCHAR(256) NOT NULL,
    production_line   VARCHAR(64)  NOT NULL,
    warehouse_id      VARCHAR(32) NOT NULL,
    status            VARCHAR(32)  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, equipment_id),
    FOREIGN KEY (tenant_id, warehouse_id) REFERENCES warehouse(tenant_id, warehouse_id)
);

CREATE TABLE IF NOT EXISTS downtime_event (
    event_id        VARCHAR(32) NOT NULL,
    tenant_id       VARCHAR(32) NOT NULL REFERENCES tenant(tenant_id),
    equipment_id    VARCHAR(32) NOT NULL,
    start_time      TIMESTAMPTZ NOT NULL,
    end_time        TIMESTAMPTZ,
    reason          VARCHAR(512),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, event_id),
    FOREIGN KEY (tenant_id, equipment_id) REFERENCES equipment(tenant_id, equipment_id)
);

CREATE TABLE IF NOT EXISTS sync_metadata (
    tenant_id     VARCHAR(32) NOT NULL,
    dataset_name  VARCHAR(64) NOT NULL,
    last_sync_at  TIMESTAMPTZ  NOT NULL,
    source_system VARCHAR(32) NOT NULL,
    PRIMARY KEY (tenant_id, dataset_name)
);

CREATE INDEX idx_inventory_tenant_wh ON inventory(tenant_id, warehouse_id);
CREATE INDEX idx_po_tenant_status ON purchase_order(tenant_id, status, expected_arrival_date);
CREATE INDEX idx_downtime_tenant_start ON downtime_event(tenant_id, start_time);
```

---

## 5. 决策宽表与视图（02-views.sql 完整）

```sql
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

-- 宽表 2：超期采购决策视图
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

-- 宽表 3：停机决策视图（≥ 30 分钟）
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
    EXTRACT(EPOCH FROM (COALESCE(d.end_time, NOW()) - d.start_time)) / 60.0 AS duration_minutes,
    CASE WHEN d.end_time IS NULL THEN TRUE ELSE FALSE END AS is_ongoing,
    GREATEST(d.created_at, e.updated_at) AS data_as_of
FROM downtime_event d
JOIN equipment e
  ON e.tenant_id = d.tenant_id AND e.equipment_id = d.equipment_id
JOIN warehouse w
  ON w.tenant_id = e.tenant_id AND w.warehouse_id = e.warehouse_id
WHERE EXTRACT(EPOCH FROM (COALESCE(d.end_time, NOW()) - d.start_time)) / 60.0 >= 30;

-- 物化宽表示例（模式 3 生产形态）
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
```

---

## 6. 演示数据（03-seed.sql）与必测答案

种子数据设计目标：用 SQL 能直接验证三类问题。

### 6.1 库存可用

| 仓 | SKU | 在库 | 预留 | **可用** |
|---|---|---:|---:|---:|
| WH-EAST | SKU-1001 | 500 | 50 | **450** |
| WH-SOUTH | SKU-1001 | 80 | 10 | **70** |

```sql
SELECT warehouse_name, sku_id, available_qty, data_as_of
FROM vw_inventory_decision
WHERE warehouse_id = 'WH-EAST' AND sku_id = 'SKU-1001';
```

### 6.2 超期采购 Top

| 采购单 | 超期天数（约） | 未到货量 |
|---|---:|---:|
| PO-2026-001 | 最多 | 1000 件 SKU-1001 |
| PO-2026-002 | 次之 | 300 个 SKU-1002 |

```sql
SELECT po_id, overdue_days, pending_qty, sku_name
FROM vw_purchase_overdue_decision
ORDER BY overdue_days DESC
LIMIT 5;
```

`PO-2026-003` 期望到货日在未来，**不应出现**。

### 6.3 停机超过 30 分钟

| 产线 | 时长 | 是否入选 |
|---|---|:---:|
| LINE-A | 45 分钟 | ✅ |
| LINE-B | 15 分钟 | ❌ |
| LINE-C | 仍在停，>30 分钟 | ✅ |

```sql
SELECT production_line, duration_minutes, is_ongoing, reason
FROM vw_downtime_decision
ORDER BY duration_minutes DESC;
```

完整种子脚本见 `demo/sql/03-seed.sql`（与仓库文件一致，含 `sync_metadata` 三条记录）。

---

## 7. 只读账号（04-readonly-user.sql 完整）

```sql
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'decision_readonly') THEN
        CREATE ROLE decision_readonly LOGIN PASSWORD 'decision_readonly_pass';
    END IF;
END
$$;

GRANT CONNECT ON DATABASE decision_demo TO decision_readonly;
GRANT USAGE ON SCHEMA public TO decision_readonly;

GRANT SELECT ON vw_inventory_decision TO decision_readonly;
GRANT SELECT ON vw_purchase_overdue_decision TO decision_readonly;
GRANT SELECT ON vw_downtime_decision TO decision_readonly;
GRANT SELECT ON dm_inventory_snapshot TO decision_readonly;
GRANT SELECT ON sync_metadata TO decision_readonly;

REVOKE ALL ON TABLE inventory, purchase_order, purchase_order_line, equipment, downtime_event FROM decision_readonly;
```

**教学点：** Agent 应用 JDBC 只用 `decision_readonly`；即使 Java 写错 SQL，也**插不进、删不掉**生产明细。

---

## 8. 重置环境

```bash
docker compose down -v
docker compose up -d
```

`-v` 会清空数据卷，重新执行 `docker-entrypoint-initdb.d` 下所有 SQL。

---

## 9. 本节检查

- [ ] `docker compose up` 成功，`pg_isready` 通过
- [ ] 三条验证 SQL 结果与 6.1～6.3 一致
- [ ] 只读账号无法 `DELETE`/`INSERT`
- [ ] 能说出 `sync_metadata` 三条记录分别对应 WMS/ERP/EMS

下一节：`03-指标字典与口径对齐.md`——把中文问题钉死在官方 SQL 上。

-- 只读账号：模拟 Agent 侧 JDBC 连接，禁止 DDL/DML
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'decision_readonly') THEN
        CREATE ROLE decision_readonly LOGIN PASSWORD 'decision_readonly_pass';
    END IF;
END
$$;

GRANT CONNECT ON DATABASE decision_demo TO decision_readonly;
GRANT USAGE ON SCHEMA public TO decision_readonly;

-- 仅授予决策宽表/视图 + 同步元数据
GRANT SELECT ON vw_inventory_decision TO decision_readonly;
GRANT SELECT ON vw_purchase_overdue_decision TO decision_readonly;
GRANT SELECT ON vw_downtime_decision TO decision_readonly;
GRANT SELECT ON dm_inventory_snapshot TO decision_readonly;
GRANT SELECT ON sync_metadata TO decision_readonly;

-- 明确拒绝明细表（演示用；生产侧用独立库 + 网络隔离更稳）
REVOKE ALL ON TABLE inventory, purchase_order, purchase_order_line, equipment, downtime_event FROM decision_readonly;

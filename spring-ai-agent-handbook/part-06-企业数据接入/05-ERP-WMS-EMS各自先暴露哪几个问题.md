# 05 · ERP / WMS / EMS 各自先暴露哪几个问题

> 同学，一上来就想「把整个 ERP 给 Agent」会把自己拖死。  
> 老师的要求：**每个系统先 1～2 个管理层必问问题**，用宽表 + 固定 Tool 答稳，再扩展。

---

## 1. 总原则：先决策问题，后数据全景

```
错误顺序：盘点所有表 → 生成 50 个 Tool → 没人敢用
正确顺序：列 5 个必问 → 3 张宽表 → 3 个 Tool → 验收 → 再加
```

本章演示的「三问」就是最小可行集：

1. **库存可用**（WMS）
2. **超期采购 Top**（ERP）
3. **停机超过 30 分钟**（EMS）

---

## 2. WMS：先暴露什么

### 2.1 系统职责（本讲义边界）

- 仓库、库位、库存快照、出入库执行（执行面 Agent **不碰**）
- 决策面关心：**现在能发多少**

### 2.2 第一批问题（建议 ≤ 3 个 Tool）

| 优先级 | 用户问题 | metricId | Tool |
|:---:|---|---|---|
| P0 | 某仓某 SKU 可用多少？ | `INV_AVAILABLE_QTY` | `queryAvailableInventory` |
| P1 | 哪些 SKU 低库存要补？ | `INV_LOW_STOCK_LIST` | `listLowAvailableInventory` |
| P2 | 多仓同一 SKU 对比（后续） | `INV_COMPARE_WH` | 待建 |

### 2.3 宽表字段建议

`vw_inventory_decision` 已含：`warehouse_name`、`sku_name`、`on_hand_qty`、`reserved_qty`、`available_qty`、`data_as_of`。

**暂不暴露：** 库位级库存、批次效期、拣货任务——颗粒度太细，模型易误用。

### 2.4 同步注意

- 源：WMS 库存快照表或 API 批量拉取。
- 频率：5～15 分钟；波峰促销可降到 3 分钟。
- `sync_metadata.source_system = 'WMS'`。

---

## 3. ERP：先暴露什么

### 3.1 系统职责

- 采购订单、供应商、物料主数据、财务过账（过账写操作 Agent **不碰**）
- 决策面关心：**哪些货买了还没到、已经超期**

### 3.2 第一批问题

| 优先级 | 用户问题 | metricId | Tool |
|:---:|---|---|---|
| P0 | 超期采购 Top N？ | `PO_OVERDUE_TOP` | `queryOverduePurchaseTop` |
| P1 | 某张采购单超期情况？ | `PO_OVERDUE_BY_ID` | `queryOverduePurchaseByPoId` |
| P2 | 在途未到汇总（后续） | `PO_IN_TRANSIT` | 待建 |

### 3.3 口径争议点（必须 RAG + 字典写死）

- **超期**按 `expected_arrival_date` 还是合同交期？
- **部分收货**算不算超期未结？——演示库：只要 `pending_qty > 0` 且过期就算。
- **在途**是否单独指标？——不与「可用库存」混在一个 Tool。

### 3.4 同步注意

- 采购头行 join 在宽表视图里完成，Agent 不跨库 join。
- ERP 升版常改字段名——**改视图，不改 Tool 对外参数**。
- `sync_metadata.source_system = 'ERP'`。

---

## 4. EMS：先暴露什么（MES 同理）

### 4.1 本讲义 EMS 含义

**E**quipment / **E**nergy / **M**aintenance **S**ystems 的合集口语：

- 设备状态、停机原因、维修工单
- 产线 OEE 相关事件（先做停机，后做 OEE）

若贵司把制造执行系统叫 **MES**（Manufacturing Execution System），表可能是 `work_center`、`downtime_reason_code`，**套路不变**：

```
MES/EMS 事件 → downtime_event 宽表 → EquipmentQueryTool
```

只在 `sync_metadata.source_system` 写 `MES` 即可。

### 4.2 第一批问题

| 优先级 | 用户问题 | metricId | Tool |
|:---:|---|---|---|
| P0 | 今天哪些产线停机超 30 分钟？ | `EMS_DOWNTIME_OVER_30M` | `queryDowntimeOver30Minutes` |
| P1 | 某产线为什么停？ | 同上 | `queryDowntimeByProductionLine` |
| P2 | 设备实时状态大盘（后续） | `EMS_EQUIP_STATUS` | 待建 |

### 4.3 演示数据对应关系

| 产线 | 时长 | 管理层应听到 |
|---|---|---|
| LINE-A | 45 min | 入选，伺服报警 |
| LINE-B | 15 min | **不入选**（可口头说明「有短停但未达 30 分钟」需另指标） |
| LINE-C | 进行中 >30 min | 入选，仍在停机 |

### 4.4 同步注意

- 实时性要求高：EMS/MES 可 1～5 分钟同步。
- `end_time IS NULL` 表示进行中，宽表用 `NOW()` 算时长。
- 与 WMS **用 `warehouse_id` 关联工厂/园区**，方便统一仓权模型。

---

## 5. 三系统一张图

```
                    ┌─────────────┐
                    │  决策 Postgres │
                    │  宽表 + 只读账号 │
                    └──────▲──────┘
                           │
         ┌─────────────────┼─────────────────┐
         │                 │                 │
    ┌────┴────┐      ┌─────┴─────┐     ┌─────┴─────┐
    │   WMS   │      │    ERP    │     │ EMS/MES   │
    │ 库存快照 │      │ 采购头行   │     │ 停机事件   │
    └─────────┘      └───────────┘     └───────────┘
         │                 │                 │
    InventoryTool    PurchaseTool     EquipmentTool
```

---

## 6. 第二批扩展（验收通过后再做）

| 系统 | 候选问题 | 前置条件 |
|---|---|---|
| WMS | 效期预警、冻结库存 | 宽表加批次字段 + 新 metricId |
| ERP | 供应商交货评分 | 历史收货数据稳定同步 |
| EMS | OEE、能耗异常 | 点数规范、时间对齐 |
| 跨系统 | 「库存够但采购超期」联合建议 | 编排层多 Tool，仍各查各宽表 |

---

## 7. 与模式 1 API 的衔接

若 WMS 已有「可用库存 API」、ERP 没有：

- WMS 走 **模式 1** `RestClient` Tool
- ERP 走 **模式 3** 宽表 Tool
- **统一**对外 JSON 形状（`metricId`、`dataAsOf`、`rows`）

管理层感受不到差异，开发要维护两套接入——这是混合态的现实，宽表逐步吞并 API。

---

## 8. 本节作业

1. 列出你们公司 WMS/ERP/EMS（或 MES）各 **1 个** P0 问题。
2. 标每个问题需要哪张宽表、是否已有同步。
3. 确认：**有没有人会要求「先上一个通用 SQL Tool」？** 写下拒绝理由（参考 `06`）。

下一节：`06-受控Text-to-SQL为什么要关进笼子.md`。

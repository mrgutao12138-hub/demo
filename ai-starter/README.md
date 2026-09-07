# ai-starter

给 Java 背景、按 [`docs/逐步学习计划.md`](../docs/逐步学习计划.md) 学习时用的最小材料。

## 文件

| 文件 | 用途 | 对应步骤 |
|---|---|---|
| `toy_forecast_d1.py` | 季节朴素 P10/P50/P90 + D1 得到 `Q_final` | 1.3、4.x、6.1 |
| `sku_demo.csv` | 36 个月模拟销量；含两个缺货月（`backorder_proxy=1`） | 2.1、5.2 |

## 运行

需要 Python 3.11+，不需要 pandas。

```bash
python3 ai-starter/toy_forecast_d1.py
```

看到 `Q_final` 后，改脚本顶部的 `AVAILABLE`、`MOQ`、`LEAD_TIME_MONTHS` 再跑。

## 不要做什么

不要用 ChatGPT 来「决定」进货量。这个目录里的数字全部由公式算出。

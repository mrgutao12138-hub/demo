#!/usr/bin/env python3
"""最小可跑的原华 AI 原型：1 个 SKU 的分位数预测 + D1 补货。

不依赖 pandas / torch / 任何 Agent 框架。
对着规格读：
  G5     输出 P10/P50/P90
  §3.7   dws_demand_forecast 的核心字段
  §4.2   σ_d = (P90-P50)/1.28 ；安全库存；(s,S)；MOQ；效期截顶

用法：
  python3 toy_forecast_d1.py
"""

from __future__ import annotations

import math
import random
from dataclasses import dataclass

# ---------- 你可以改这些数，看 Q_final 怎么变 ----------
LEAD_TIME_MONTHS = 1.0  # 采购交期（月）
REVIEW_MONTHS = 1.0  # 复核周期（月）
SERVICE_Z = 1.65  # 约 95% 服务水平对应的 z
MOQ = 12  # 最小订货量（箱）
AVAILABLE = 8  # 现有可用库存
IN_TRANSIT = 0  # 在途
NEAR_EXP_RATIO = 0.1  # 近效期占比；高则下调安全库存
SHELF_LIFE_MONTHS = 8  # 保质期
P50_SELLABLE_CAP_MONTHS = 6  # 效期内按 P50 能卖完的月数上限


@dataclass
class Forecast:
    p10: float
    p50: float
    p90: float
    wape_naive: float
    demand_pattern: str
    model_version: str = "toy-seasonal-naive-v1"


@dataclass
class ReplenishAdvice:
    q_final: int
    q_raw: float
    q_capped: float
    sigma_d: float
    safety_stock: float
    s_level: float
    reason_chain: list[str]


def simulate_series(n: int = 36, seed: int = 7) -> list[float]:
    """模拟月销：水平 + 季节 + 噪声。真实项目里这列来自数仓 qty_anchor。"""
    rng = random.Random(seed)
    level = 10.0
    out: list[float] = []
    for t in range(n):
        season = 2.0 * math.sin(2 * math.pi * (t % 12) / 12)
        noise = rng.gauss(0, 1.2)
        out.append(max(0.0, level + season + noise))
    return out


def seasonal_naive_p50(y: list[float], horizon: int = 1) -> float:
    """季节朴素：预测 = 去年同月。规格里 μ+s 的最简替代，先别上粒子滤波。"""
    if len(y) < 12:
        return sum(y) / len(y)
    return y[-12]


def residual_sigma(y: list[float]) -> float:
    """用「去年同月」当预测，残差标准差估波动。至少 12 个残差才稳一点。"""
    if len(y) < 24:
        mean = sum(y) / len(y)
        var = sum((v - mean) ** 2 for v in y) / max(len(y) - 1, 1)
        return math.sqrt(var)
    residuals = [y[i] - y[i - 12] for i in range(12, len(y))]
    mean_r = sum(residuals) / len(residuals)
    var = sum((r - mean_r) ** 2 for r in residuals) / max(len(residuals) - 1, 1)
    return math.sqrt(max(var, 1e-6))


def wape(y: list[float]) -> float:
    if len(y) < 24:
        return float("nan")
    denom = sum(abs(v) for v in y[12:])
    if denom == 0:
        return float("nan")
    num = sum(abs(y[i] - y[i - 12]) for i in range(12, len(y)))
    return num / denom


def classify_pattern(y: list[float]) -> str:
    """极简 ADI：平均多少个月才有一次非零需求。高频才走本脚本主路径。"""
    nonzero = sum(1 for v in y if v > 1e-6)
    adi = len(y) / max(nonzero, 1)
    return "high_freq" if adi < 1.32 else "intermittent"


def forecast_quantiles(y: list[float]) -> Forecast:
    p50 = seasonal_naive_p50(y)
    sigma = residual_sigma(y)
    # 正态近似：P90 ≈ μ+1.28σ，与规格 D1 的 σ_d=(P90-P50)/1.28 互为逆运算
    return Forecast(
        p10=max(0.0, p50 - 1.28 * sigma),
        p50=p50,
        p90=p50 + 1.28 * sigma,
        wape_naive=wape(y),
        demand_pattern=classify_pattern(y),
    )


def d1_replenish(fc: Forecast) -> ReplenishAdvice:
    reasons: list[str] = []
    sigma_d = (fc.p90 - fc.p50) / 1.28
    reasons.append(f"σ_d=(P90-P50)/1.28=({fc.p90:.2f}-{fc.p50:.2f})/1.28={sigma_d:.2f}")

    ss = SERVICE_Z * sigma_d * math.sqrt(LEAD_TIME_MONTHS)
    if NEAR_EXP_RATIO >= 0.2:
        ss *= 0.7
        reasons.append(f"近效期占比 {NEAR_EXP_RATIO:.0%}≥20%，安全库存下调至 {ss:.2f}")
    else:
        reasons.append(f"SS=z·σ_d·√LT={SERVICE_Z}·{sigma_d:.2f}·√{LEAD_TIME_MONTHS}={ss:.2f}")

    s_level = fc.p50 * (LEAD_TIME_MONTHS + REVIEW_MONTHS) + ss
    reasons.append(
        f"S=P50·(LT+review)+SS={fc.p50:.2f}·({LEAD_TIME_MONTHS}+{REVIEW_MONTHS})+{ss:.2f}={s_level:.2f}"
    )

    q_raw = max(0.0, s_level - AVAILABLE - IN_TRANSIT)
    reasons.append(f"Q_raw=max(0,S-available-in_transit)=max(0,{s_level:.2f}-{AVAILABLE}-{IN_TRANSIT})={q_raw:.2f}")

    sellable = fc.p50 * min(SHELF_LIFE_MONTHS, P50_SELLABLE_CAP_MONTHS)
    q_capped = min(q_raw, max(0.0, sellable - AVAILABLE))
    if q_capped < q_raw:
        reasons.append(f"效期硬截顶：可销约 {sellable:.1f}，Q 从 {q_raw:.2f} 降到 {q_capped:.2f}")

    if MOQ <= 0:
        q_final = int(math.ceil(q_capped))
    elif q_capped == 0:
        q_final = 0
    else:
        q_final = int(math.ceil(q_capped / MOQ) * MOQ)
        reasons.append(f"MOQ 进位 ceil({q_capped:.2f}/{MOQ})*{MOQ}={q_final}")

    reasons.append("本玩具模型未做 D2 冲任务、未做硬红线；Q_final 不是 LLM 算的。")
    return ReplenishAdvice(
        q_final=q_final,
        q_raw=q_raw,
        q_capped=q_capped,
        sigma_d=sigma_d,
        safety_stock=ss,
        s_level=s_level,
        reason_chain=reasons,
    )


def main() -> None:
    y = simulate_series()
    fc = forecast_quantiles(y)
    advice = d1_replenish(fc)

    print("=== dws_demand_forecast（玩具一行）===")
    print(f"demand_pattern : {fc.demand_pattern}")
    print(f"p10 / p50 / p90: {fc.p10:.2f} / {fc.p50:.2f} / {fc.p90:.2f}")
    print(f"WAPE(季节朴素) : {fc.wape_naive:.3f}")
    print(f"model_version  : {fc.model_version}")
    print()
    print("=== D1 建议 ===")
    print(f"Q_final        : {advice.q_final}")
    print(f"S / SS / σ_d   : {advice.s_level:.2f} / {advice.safety_stock:.2f} / {advice.sigma_d:.2f}")
    print("reason_chain:")
    for i, line in enumerate(advice.reason_chain, 1):
        print(f"  {i}. {line}")
    print()
    print("下一步：改文件顶部的 AVAILABLE / MOQ / LEAD_TIME_MONTHS 再跑一次。")
    print("不要在这一步引入 LangChain 或 ChatGPT 来「决定」进货量。")


if __name__ == "__main__":
    main()

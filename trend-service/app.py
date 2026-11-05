"""趋势统计分析服务

职责边界：只做统计计算，不做业务判断，也不访问外部系统。
Java 侧把按日聚合好的序列发过来，这里返回趋势方向、突变点与文字说明。
"""

from fastapi import FastAPI
from pydantic import BaseModel, Field

import numpy as np

app = FastAPI(title="intern-radar-trend", version="0.1.0")


class Point(BaseModel):
    date: str
    value: float


class TrendRequest(BaseModel):
    series: list[Point] = Field(default_factory=list)
    z_threshold: float = 2.0
    drop_ratio: float = 0.6


class TrendPoint(BaseModel):
    date: str
    value: float
    zscore: float


class TrendResponse(BaseModel):
    count: int
    mean: float
    std: float
    slope: float
    trend: str
    firstHalfAvg: float
    secondHalfAvg: float
    anomalies: list[TrendPoint]
    summary: str


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.post("/analyze/trend", response_model=TrendResponse)
def analyze_trend(request: TrendRequest) -> TrendResponse:
    dates = [point.date for point in request.series]
    values = np.array([point.value for point in request.series], dtype=float)
    count = len(values)

    if count == 0:
        return TrendResponse(
            count=0,
            mean=0.0,
            std=0.0,
            slope=0.0,
            trend="unknown",
            firstHalfAvg=0.0,
            secondHalfAvg=0.0,
            anomalies=[],
            summary="序列为空，无法判断趋势",
        )

    mean = float(values.mean())
    std = float(values.std(ddof=0))
    slope = float(np.polyfit(np.arange(count), values, 1)[0]) if count >= 2 else 0.0

    anomalies: list[TrendPoint] = []
    if std > 0:
        zscores = (values - mean) / std
        for index, zscore in enumerate(zscores):
            if abs(float(zscore)) >= request.z_threshold:
                anomalies.append(
                    TrendPoint(
                        date=dates[index],
                        value=float(values[index]),
                        zscore=round(float(zscore), 2),
                    )
                )

    half = count // 2
    first_avg = float(values[:half].mean()) if half > 0 else mean
    second_avg = float(values[half:].mean()) if count - half > 0 else mean

    if first_avg > 0 and second_avg < first_avg * request.drop_ratio:
        trend = "down"
    elif first_avg > 0 and second_avg > first_avg / request.drop_ratio:
        trend = "up"
    else:
        trend = "flat"

    summary = (
        f"共 {count} 天，均值 {mean:.1f}，前半段均值 {first_avg:.1f}，"
        f"后半段均值 {second_avg:.1f}，趋势 {trend}，"
        f"突变点 {len(anomalies)} 个"
    )

    return TrendResponse(
        count=count,
        mean=round(mean, 2),
        std=round(std, 2),
        slope=round(slope, 3),
        trend=trend,
        firstHalfAvg=round(first_avg, 2),
        secondHalfAvg=round(second_avg, 2),
        anomalies=anomalies,
        summary=summary,
    )

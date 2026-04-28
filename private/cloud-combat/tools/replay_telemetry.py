#!/usr/bin/env python3
"""
Replay Nova combat cloud telemetry CSVs.

The telemetry CSV currently contains engine snapshots and emitted cloud verdicts,
but not the raw combat_event packets that fed Nova/MX/sustained state. Because of
that, this tool has two intentionally separate modes:

observed:
  Replays the actual CLOUD_VERDICT rows and maps sustained verdicts to the direct
  verdict that seeded them. This is exact for "what did the cloud emit?" and is
  the right baseline for testing whether a future filter would suppress existing
  false positives without hiding their sustained follow-ups.

candidate:
  Uses ENGINE_SNAPSHOT rows as direct CombatEngine-A candidates and approximates
  cloud cooldown/sustained behavior. This is useful for rough experiments, but it
  cannot be exact without raw event rows.

project:
  Projects the current false-positive filters and aura-pressure promotion over
  the observed CLOUD_VERDICT rows. Existing direct verdicts that match a
  compatibility-filtered engine snapshot suppress their sustained follow-ups;
  non-flagged engine snapshots that match the new aura-pressure signature are
  counted as promoted direct detections.
"""

from __future__ import annotations

import argparse
import csv
import os
from collections import Counter
from dataclasses import dataclass
from typing import Iterable, Optional


FLAG_COOLDOWN_MS = 175
SUSTAINED_INTERVAL_MS = 500
SUSTAINED_EVIDENCE_MAX_AGE_MS = 12_000
SUSTAINED_PACKET_COMBAT_MAX_AGE_MS = 90_000
SUSTAINED_PACKET_COMBAT_FRESH_AGE_MS = 30_000


@dataclass
class Verdict:
    timestamp: int
    kind: str
    source: str
    score: float
    confidence: float
    vl: int
    top: tuple[str, ...]
    summary: str
    row_index: int
    seed_id: Optional[int] = None


def as_float(row: dict[str, str], key: str, fallback: float = 0.0) -> float:
    value = row.get(key, "")
    if value == "":
        return fallback
    try:
        return float(value)
    except ValueError:
        return fallback


def as_int(row: dict[str, str], key: str, fallback: int = 0) -> int:
    return int(round(as_float(row, key, float(fallback))))


def top_signals(row: dict[str, str]) -> tuple[str, ...]:
    raw = row.get("verdictTopSignals", "")
    return tuple(signal for signal in raw.split("|") if signal)


def has_signal(verdict: Verdict, prefix: str) -> bool:
    return any(signal.startswith(prefix) for signal in verdict.top)


def load_rows(path: str) -> list[dict[str, str]]:
    with open(path, newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    for index, row in enumerate(rows):
        row["_row_index"] = str(index)
    return rows


def cloud_verdict_from_row(row: dict[str, str]) -> Verdict:
    top = top_signals(row)
    sustained = any(signal.startswith("CombatCloud-Sustained") for signal in top)
    engine = any(signal == "CombatEngine-A" for signal in top)
    source = "sustained" if sustained else "engine" if engine else "external"
    return Verdict(
        timestamp=as_int(row, "timestamp"),
        kind="sustained" if sustained else "direct",
        source=source,
        score=as_float(row, "verdictScore"),
        confidence=as_float(row, "verdictConfidence"),
        vl=as_int(row, "verdictVl"),
        top=top,
        summary=row.get("verdictSummary", ""),
        row_index=as_int(row, "_row_index"),
    )


def engine_candidate_from_row(row: dict[str, str]) -> Optional[Verdict]:
    if row.get("row_type") != "ENGINE_SNAPSHOT" or row.get("verdictFlag", "").lower() != "true":
        return None
    return Verdict(
        timestamp=as_int(row, "timestamp"),
        kind="direct",
        source="engine",
        score=as_float(row, "verdictScore"),
        confidence=as_float(row, "verdictConfidence"),
        vl=as_int(row, "verdictVl", 10) or 10,
        top=("CombatEngine-A",) + top_signals(row),
        summary="CombatEngine-A " + row.get("verdictSummary", ""),
        row_index=as_int(row, "_row_index"),
    )


def row_kind(row: dict[str, str]) -> str:
    return row.get("row_type", "")


def row_is_engine_snapshot(row: dict[str, str]) -> bool:
    return row_kind(row) == "ENGINE_SNAPSHOT"


def row_is_cloud_verdict(row: dict[str, str]) -> bool:
    return row_kind(row) == "CLOUD_VERDICT"


def row_top_signals(row: dict[str, str]) -> tuple[str, ...]:
    return top_signals(row)


def row_summary(row: dict[str, str]) -> str:
    return row.get("verdictSummary", "")


def engine_fast_legit_chaos(row: dict[str, str]) -> bool:
    return (
        as_float(row, "rotationMaxBucketShare") <= 0.12
        and as_float(row, "rotationUniqueRatio") >= 0.58
        and as_float(row, "fastRotationShare") >= 0.18
        and as_float(row, "meanYawAcceleration") >= 4.0
        and as_float(row, "meanYawJerk") >= 3.2
        and as_float(row, "meanAimYawError") >= 6.0
        and as_float(row, "hitRatio") >= 0.70
        and as_int(row, "instantRotationAttacksMedium") <= 2
    )


def engine_low_volume_flick(row: dict[str, str]) -> bool:
    return (
        as_int(row, "attacksMedium") <= 4
        and as_float(row, "hitRatio") >= 0.70
        and as_int(row, "instantRotationAttacksMedium") >= 1
        and as_float(row, "fastRotationShare") >= 0.28
        and as_float(row, "meanYawAcceleration") >= 12.0
        and as_float(row, "meanYawJerk") >= 12.0
        and as_float(row, "meanAimYawError") >= 24.0
        and as_float(row, "rotationMaxBucketShare") <= 0.07
        and as_float(row, "rotationUniqueRatio") >= 0.74
    )


def engine_touchpad_stop(row: dict[str, str]) -> bool:
    return (
        as_int(row, "rotationDeltasMedium") <= 10
        and as_float(row, "hitRatio") >= 0.70
        and as_float(row, "rotationUniqueRatio") >= 0.80
        and as_float(row, "microCorrectionShare") <= 0.02
        and as_float(row, "fastRotationShare") >= 0.12
        and as_float(row, "meanYawAcceleration") >= 2.0
    )


def engine_touchpad_switch(row: dict[str, str]) -> bool:
    return (
        as_int(row, "rotationDeltasMedium") <= 60
        and as_float(row, "hitRatio") >= 0.70
        and as_float(row, "rotationUniqueRatio") >= 0.75
        and as_float(row, "fastRotationShare") >= 0.16
        and as_float(row, "rotationMaxBucketShare") <= 0.22
        and as_float(row, "meanYawAcceleration") >= 3.5
        and as_float(row, "meanYawJerk") >= 2.5
    )


def engine_block_overlap_chaos(row: dict[str, str]) -> bool:
    signals = "|".join(row_top_signals(row)) + row_summary(row)
    return (
        "legacy-block-hit-overlap" in signals
        and as_float(row, "rotationMaxBucketShare") <= 0.11
        and as_float(row, "rotationUniqueRatio") >= 0.48
        and as_float(row, "fastRotationShare") >= 0.08
        and as_float(row, "meanYawAcceleration") >= 4.0
        and as_float(row, "meanYawJerk") >= 3.4
        and as_float(row, "meanAimYawError") >= 7.0
        and as_float(row, "hitRatio") <= 0.75
    )


def engine_block_overlap_high_error(row: dict[str, str]) -> bool:
    signals = "|".join(row_top_signals(row)) + row_summary(row)
    return (
        "legacy-block-hit-overlap" in signals
        and as_float(row, "meanAimYawError") >= 10.0
        and as_float(row, "hitRatio") >= 0.70
        and as_float(row, "rotationMaxBucketShare") <= 0.07
    )


def engine_low_delta_low_confidence(row: dict[str, str]) -> bool:
    return as_int(row, "rotationDeltasMedium") <= 45 and as_float(row, "verdictConfidence") <= 0.85


def engine_smooth_touchpad_cooldown(row: dict[str, str]) -> bool:
    return (
        as_float(row, "hitRatio") >= 0.83
        and as_int(row, "rotationDeltasMedium") <= 70
        and as_float(row, "meanYawJerk") <= 3.5
        and as_float(row, "rotationMaxBucketShare") <= 0.14
    )


def engine_touchpad_low_delta_switch(row: dict[str, str]) -> bool:
    signals = "|".join(row_top_signals(row)) + row_summary(row)
    return (
        "legacy-target-switch" in signals
        and as_int(row, "attacksMedium") >= 8
        and as_float(row, "hitRatio") >= 0.70
        and as_int(row, "rotationDeltasMedium") <= 25
        and as_float(row, "meanYawAcceleration") <= 1.6
        and as_float(row, "meanYawJerk") <= 1.0
        and as_float(row, "rotationUniqueRatio") >= 0.75
    )


def engine_touchpad_low_volume_cadence(row: dict[str, str]) -> bool:
    signals = "|".join(row_top_signals(row)) + row_summary(row)
    return (
        "modern-rotation-cadence" in signals
        and as_int(row, "attacksMedium") <= 3
        and as_float(row, "hitRatio") >= 0.65
        and as_int(row, "rotationDeltasMedium") <= 72
        and 3.5 <= as_float(row, "meanYawAcceleration") <= 5.1
        and as_float(row, "meanYawJerk") <= 2.6
    )


def engine_low_volume_block_overlap_flick(row: dict[str, str]) -> bool:
    signals = "|".join(row_top_signals(row)) + row_summary(row)
    return (
        "legacy-block-hit-overlap" in signals
        and as_int(row, "attacksMedium") <= 3
        and as_int(row, "rotationDeltasMedium") <= 16
        and as_float(row, "meanYawAcceleration") >= 7.0
        and as_float(row, "meanYawJerk") >= 8.0
    )


def engine_low_hit_erratic_miss(row: dict[str, str]) -> bool:
    signals = "|".join(row_top_signals(row)) + row_summary(row)
    return (
        as_int(row, "attacksMedium") >= 8
        and as_float(row, "hitRatio") <= 0.45
        and as_int(row, "rotationDeltasMedium") >= 90
        and "modern-cooldown-rotation-core=6." not in signals
        and "modern-cooldown-rotation-core=7." not in signals
        and "modern-cooldown-rotation-core=8." not in signals
        and "modern-cooldown-rotation-core=9." not in signals
        and 2.5 <= as_float(row, "meanYawAcceleration") <= 3.5
        and as_float(row, "meanYawJerk") >= 2.3
    )


def engine_mid_hit_fov_block(row: dict[str, str]) -> bool:
    signals = "|".join(row_top_signals(row)) + row_summary(row)
    return (
        "legacy-block-hit-overlap" in signals
        and "modern-fov-reacquire" in signals
        and as_float(row, "hitRatio") <= 0.65
        and as_int(row, "rotationDeltasMedium") >= 120
        and as_float(row, "meanYawJerk") >= 2.8
        and as_float(row, "meanAimYawError") >= 8.0
    )


def engine_mid_hit_fov_no_block(row: dict[str, str]) -> bool:
    signals = "|".join(row_top_signals(row)) + row_summary(row)
    has_fov = "modern-fov-reacquire" in signals or not row_top_signals(row)
    return (
        has_fov
        and "legacy-block-hit-overlap" not in signals
        and as_int(row, "attacksMedium") >= 10
        and as_float(row, "hitRatio") <= 0.56
        and as_int(row, "rotationDeltasMedium") >= 100
        and 3.4 <= as_float(row, "meanYawAcceleration") <= 4.0
        and 3.0 <= as_float(row, "meanYawJerk") <= 3.4
        and 5.0 <= as_float(row, "meanAimYawError") <= 7.0
        and as_float(row, "rotationMaxBucketShare") <= 0.08
        and as_float(row, "rotationUniqueRatio") >= 0.60
    )


def engine_cooldown_mid_chaos(row: dict[str, str]) -> bool:
    signals = "|".join(row_top_signals(row)) + row_summary(row)
    return (
        "modern-cooldown-rotation-core" in signals
        and as_int(row, "attacksMedium") >= 10
        and 0.50 <= as_float(row, "hitRatio") <= 0.75
        and as_int(row, "rotationDeltasMedium") >= 100
        and as_float(row, "meanYawAcceleration") >= 4.0
        and as_float(row, "meanYawJerk") >= 3.3
        and as_float(row, "rotationMaxBucketShare") <= 0.09
    )


def engine_high_fast_low_error_touchpad(row: dict[str, str]) -> bool:
    return (
        as_int(row, "attacksMedium") <= 4
        and as_float(row, "hitRatio") >= 0.70
        and as_float(row, "fastRotationShare") >= 0.30
        and as_float(row, "rotationUniqueRatio") >= 0.75
        and as_float(row, "meanYawAcceleration") >= 8.5
        and as_float(row, "meanYawJerk") >= 5.5
        and as_float(row, "meanAimYawError") <= 8.0
    )


def engine_compact_fov_instant_low_error(row: dict[str, str]) -> bool:
    signals = "|".join(row_top_signals(row)) + row_summary(row)
    has_fov = "modern-fov-reacquire" in signals or not row_top_signals(row)
    return (
        has_fov
        and "modern-instant-rotation" in signals
        and as_int(row, "attacksMedium") <= 3
        and as_float(row, "hitRatio") >= 0.60
        and 55 <= as_int(row, "rotationDeltasMedium") <= 80
        and as_int(row, "instantRotationAttacksMedium") >= 1
        and 3.8 <= as_float(row, "meanYawAcceleration") <= 5.0
        and 2.8 <= as_float(row, "meanYawJerk") <= 3.6
        and as_float(row, "meanAimYawError") <= 22.0
    )


def engine_mx_legit_noise(row: dict[str, str]) -> bool:
    noisy_low_conversion = (
        as_float(row, "hitRatio") <= 0.58
        and as_int(row, "rotationDeltasMedium") >= 50
        and as_float(row, "fastRotationShare") >= 0.08
        and as_float(row, "meanYawAcceleration") >= 6.5
        and as_float(row, "meanYawJerk") >= 5.5
    )
    broad_erratic_mouse = (
        as_float(row, "hitRatio") >= 0.60
        and as_int(row, "rotationDeltasMedium") >= 140
        and as_float(row, "rotationMaxBucketShare") <= 0.04
        and as_float(row, "rotationUniqueRatio") >= 0.62
        and as_float(row, "microCorrectionShare") <= 0.22
        and as_float(row, "meanYawAcceleration") >= 4.0
        and as_float(row, "meanYawJerk") >= 3.0
        and as_float(row, "meanAimYawError") >= 8.0
    )
    fast_erratic_mouse = (
        as_float(row, "hitRatio") >= 0.65
        and as_float(row, "fastRotationShare") >= 0.30
        and as_float(row, "meanYawAcceleration") >= 10.0
        and as_float(row, "meanYawJerk") >= 7.0
        and as_float(row, "rotationMaxBucketShare") <= 0.04
        and as_float(row, "rotationUniqueRatio") >= 0.65
    )
    return noisy_low_conversion or broad_erratic_mouse or fast_erratic_mouse


def engine_touchpad_cross_check_compatibility(row: dict[str, str]) -> bool:
    return (
        as_int(row, "attacksMedium") >= 4
        and as_float(row, "hitRatio") >= 0.70
        and as_float(row, "apsShort") <= 2.6
        and as_int(row, "rotationDeltasMedium") <= 48
        and as_float(row, "preciseAimShare") <= 0.02
        and as_float(row, "snapPreciseAimShare") <= 0.01
        and as_float(row, "meanYawAcceleration") <= 8.6
        and as_float(row, "meanYawJerk") <= 6.7
    )


def engine_compatibility_filter(row: Optional[dict[str, str]]) -> bool:
    if row is None:
        return False
    signals = "|".join(row_top_signals(row)) + row_summary(row)
    if (
        "cross-check-delta" in signals or "family-reach" in signals or "family-hitbox" in signals
    ) and not engine_touchpad_cross_check_compatibility(row):
        return False
    return (
        engine_fast_legit_chaos(row)
        or engine_low_volume_flick(row)
        or engine_touchpad_stop(row)
        or engine_touchpad_switch(row)
        or engine_block_overlap_chaos(row)
        or engine_block_overlap_high_error(row)
        or engine_low_delta_low_confidence(row)
        or engine_smooth_touchpad_cooldown(row)
        or (
            ("cross-check-delta" in signals or "family-reach" in signals or "family-hitbox" in signals)
            and engine_touchpad_cross_check_compatibility(row)
        )
        or engine_touchpad_low_delta_switch(row)
        or engine_touchpad_low_volume_cadence(row)
        or engine_low_volume_block_overlap_flick(row)
        or engine_low_hit_erratic_miss(row)
        or engine_mid_hit_fov_block(row)
        or engine_mid_hit_fov_no_block(row)
        or engine_cooldown_mid_chaos(row)
        or engine_high_fast_low_error_touchpad(row)
        or engine_compact_fov_instant_low_error(row)
    )


def engine_aura_pressure(row: dict[str, str]) -> bool:
    return (
        row_is_engine_snapshot(row)
        and row.get("verdictFlag", "").lower() == "false"
        and as_float(row, "verdictScore") >= 95.0
        and as_float(row, "verdictConfidence") >= 0.90
        and as_int(row, "attacksMedium") >= 7
        and as_int(row, "rotationsMedium") >= 40
        and as_int(row, "rotationDeltasMedium") >= 68
        and 1.20 <= as_float(row, "apsShort") <= 8.50
        and 0.22 <= as_float(row, "hitRatio") <= 0.62
        and 0.36 <= as_float(row, "microCorrectionShare") <= 0.58
        and as_float(row, "fastRotationShare") <= 0.13
        and as_float(row, "meanYawAcceleration") <= 3.0
        and as_float(row, "meanYawJerk") <= 2.8
        and as_float(row, "rotationUniqueRatio") <= 0.64
        and as_float(row, "rotationMaxBucketShare") >= 0.09
    )


def external_policy_filter(verdict: Verdict, engine_row: Optional[dict[str, str]] = None) -> bool:
    if has_signal(verdict, "MX-"):
        if verdict.score < 80.0 or verdict.confidence < 0.82:
            return True
        if engine_row is not None and verdict.score < 180.0 and (
            engine_compatibility_filter(engine_row) or engine_mx_legit_noise(engine_row)
        ):
            return True
    if has_signal(verdict, "Aim-H"):
        return metric_float(verdict.summary, "dyaw=", 0.0) < 55.0
    if has_signal(verdict, "Reach-B"):
        return metric_float(verdict.summary, "distance=", 0.0) <= 7.0
    if has_signal(verdict, "Reach-A"):
        return metric_float(verdict.summary, "delta=", 0.0) < 2.0
    if has_signal(verdict, "Backtrack-B"):
        return "spikes=0" in verdict.summary and "osc=false" in verdict.summary
    if has_signal(verdict, "KillAura-B"):
        switches = metric_float(verdict.summary, "switches=", -1.0)
        attacks = metric_float(verdict.summary, "attacks=", -1.0)
        return switches < 4.0 or attacks < 7.0
    return False


def actual_cloud_verdicts(rows: Iterable[dict[str, str]]) -> list[Verdict]:
    return [cloud_verdict_from_row(row) for row in rows if row.get("row_type") == "CLOUD_VERDICT"]


def direct_seed_key(verdict: Verdict) -> tuple[str, ...]:
    if verdict.kind == "sustained" and verdict.top and verdict.top[0].startswith("CombatCloud-Sustained"):
        return verdict.top[1:]
    return verdict.top


def observed_replay(rows: list[dict[str, str]]) -> list[Verdict]:
    """Replay emitted cloud verdict rows and attach sustained rows to direct seeds."""
    output: list[Verdict] = []
    direct_seed = 0
    latest_direct_by_top: dict[tuple[str, ...], int] = {}
    latest_direct_id: Optional[int] = None
    for verdict in actual_cloud_verdicts(rows):
        if verdict.kind == "direct":
            direct_seed += 1
            verdict.seed_id = direct_seed
            latest_direct_id = direct_seed
            latest_direct_by_top[direct_seed_key(verdict)] = direct_seed
        else:
            verdict.seed_id = latest_direct_by_top.get(direct_seed_key(verdict), latest_direct_id)
        output.append(verdict)
    return output


def matching_engine_snapshot(
    cloud_row: dict[str, str],
    engine_by_timestamp: dict[int, list[dict[str, str]]],
    window_ms: int = FLAG_COOLDOWN_MS,
    require_flag: bool = True,
) -> Optional[dict[str, str]]:
    timestamp = as_int(cloud_row, "timestamp")
    score = as_float(cloud_row, "verdictScore")
    candidates: list[dict[str, str]] = []
    for delta in range(-window_ms, window_ms + 1):
        candidates.extend(engine_by_timestamp.get(timestamp + delta, ()))
    if require_flag:
        candidates = [row for row in candidates if row.get("verdictFlag", "").lower() == "true"]
    if not candidates:
        return None
    return min(
        candidates,
        key=lambda row: (abs(as_int(row, "timestamp") - timestamp), abs(as_float(row, "verdictScore") - score)),
    )


def projected_policy_replay(rows: list[dict[str, str]]) -> tuple[list[Verdict], int, int]:
    engine_by_timestamp: dict[int, list[dict[str, str]]] = {}
    for row in rows:
        if row_is_engine_snapshot(row):
            engine_by_timestamp.setdefault(as_int(row, "timestamp"), []).append(row)

    output: list[Verdict] = []
    direct_seed = 0
    latest_direct_by_top: dict[tuple[str, ...], int] = {}
    latest_direct_id: Optional[int] = None
    suppressed_seed_ids: set[int] = set()
    suppressed_existing = 0

    for row in rows:
        if row_is_cloud_verdict(row):
            verdict = cloud_verdict_from_row(row)
            if verdict.kind == "direct":
                direct_seed += 1
                verdict.seed_id = direct_seed
                latest_direct_id = direct_seed
                latest_direct_by_top[direct_seed_key(verdict)] = direct_seed
                engine_row = matching_engine_snapshot(
                    row,
                    engine_by_timestamp,
                    require_flag=verdict.source == "engine",
                ) if verdict.source in ("engine", "external") else None
                if verdict.source == "engine" and engine_compatibility_filter(engine_row):
                    suppressed_seed_ids.add(direct_seed)
                    suppressed_existing += 1
                    continue
                if verdict.source == "external" and external_policy_filter(verdict, engine_row):
                    suppressed_seed_ids.add(direct_seed)
                    suppressed_existing += 1
                    continue
                output.append(verdict)
            else:
                seed_id = latest_direct_by_top.get(direct_seed_key(verdict), latest_direct_id)
                verdict.seed_id = seed_id
                if seed_id in suppressed_seed_ids:
                    suppressed_existing += 1
                    continue
                output.append(verdict)

    promoted = 0
    last_promotion_ms = -10**18
    for row in rows:
        if not engine_aura_pressure(row):
            continue
        timestamp = as_int(row, "timestamp")
        if timestamp - last_promotion_ms < 500:
            continue
        output.append(Verdict(
            timestamp=timestamp,
            kind="direct",
            source="engine-promotion",
            score=as_float(row, "verdictScore"),
            confidence=as_float(row, "verdictConfidence"),
            vl=10,
            top=("CombatEngine-A", "modern-sustained-aura-pressure"),
            summary="CombatEngine-A projected sustained aura pressure",
            row_index=as_int(row, "_row_index"),
        ))
        last_promotion_ms = timestamp
        promoted += 1

    output.sort(key=lambda verdict: (verdict.timestamp, verdict.row_index))
    return output, suppressed_existing, promoted


def eligible_for_sustained(verdict: Verdict) -> bool:
    if verdict.confidence < 0.80:
        return False
    for signal in verdict.top:
        if signal.startswith("MX-"):
            return (
                signal.startswith("MX-ML") or signal.startswith("MX-AutoClicker")
            ) and verdict.score >= 90.0 and verdict.confidence >= 0.86
        if signal.startswith(("Aim-B", "Aim-C", "Aim-F", "KillAura-C")):
            return False
    if "CombatEngine-A" in verdict.top:
        hits = metric_int(verdict.summary, "hit=", 1_000_000)
        hit_ratio = metric_float(verdict.summary, "hr=", 1.0)
        if hits <= 3 and hit_ratio <= 0.35:
            return False
        return verdict.score >= 90.0 and verdict.confidence >= 0.90
    return verdict.score >= 78.0


def metric_float(text: str, key: str, fallback: float) -> float:
    start = text.find(key)
    if start < 0:
        return fallback
    start += len(key)
    end = start
    while end < len(text) and (text[end].isdigit() or text[end] in ".+-"):
        end += 1
    try:
        return float(text[start:end])
    except ValueError:
        return fallback


def metric_int(text: str, key: str, fallback: int) -> int:
    value = metric_float(text, key, float("nan"))
    return fallback if value != value else int(round(value))


def sustained_from(last_direct: Verdict, timestamp: int, row_index: int) -> Verdict:
    return Verdict(
        timestamp=timestamp,
        kind="sustained",
        source="sustained",
        score=max(72.0, min(100.0, last_direct.score * 0.88)),
        confidence=max(0.62, min(0.84, last_direct.confidence * 0.92)),
        vl=max(4, min(8, last_direct.vl // 2)),
        top=("CombatCloud-Sustained",) + last_direct.top,
        summary="sustained combat evidence after " + last_direct.summary,
        row_index=row_index,
    )


def candidate_replay(rows: list[dict[str, str]]) -> list[Verdict]:
    """Approximate cloud behavior from engine snapshots only."""
    output: list[Verdict] = []
    last_flag_ms = -10**18
    last_decay_ms = 0
    sustained_evidence = 0.0
    last_direct: Optional[Verdict] = None
    last_direct_ms = 0
    last_sustained_ms = 0
    last_combat_ms = 0
    attacks_since_direct = 0
    samples_since_direct = 0

    for row in rows:
        timestamp = as_int(row, "timestamp")
        if row.get("row_type") == "ENGINE_SNAPSHOT":
            last_combat_ms = timestamp
            if last_direct is not None and timestamp - last_direct_ms <= SUSTAINED_PACKET_COMBAT_MAX_AGE_MS:
                samples_since_direct += 1
                if samples_since_direct % 4 == 0:
                    attacks_since_direct += 1
                    sustained_evidence = min(90.0, sustained_evidence + 3.0)
                elif attacks_since_direct > 0:
                    sustained_evidence = min(90.0, sustained_evidence + 0.75)

        if last_decay_ms <= 0:
            last_decay_ms = timestamp
        else:
            elapsed = max(0, timestamp - last_decay_ms)
            if elapsed >= 50:
                ongoing = ongoing_direct_combat(
                    timestamp, last_direct, last_direct_ms, last_combat_ms, attacks_since_direct, samples_since_direct
                )
                active = timestamp - last_combat_ms <= 1200 if last_combat_ms else False
                rate = 2.5 if ongoing else 5.5 if active else 30.0
                sustained_evidence = max(0.0, sustained_evidence - (elapsed / 1000.0) * rate)
                if sustained_evidence <= 1.0e-4:
                    last_direct = None
                last_decay_ms = timestamp

        candidate = engine_candidate_from_row(row)
        if candidate is not None:
            if eligible_for_sustained(candidate):
                gain = 10.0 + min(10.0, candidate.vl) + candidate.confidence * 10.0
                sustained_evidence = min(100.0, sustained_evidence + gain)
                last_direct = candidate
                last_direct_ms = timestamp
                attacks_since_direct = 0
                samples_since_direct = 0
            if timestamp - last_flag_ms >= FLAG_COOLDOWN_MS:
                output.append(candidate)
                last_flag_ms = timestamp
            continue

        if last_direct is None:
            continue
        ongoing = ongoing_direct_combat(
            timestamp, last_direct, last_direct_ms, last_combat_ms, attacks_since_direct, samples_since_direct
        )
        required = 35.0 if ongoing else 55.0
        max_age = SUSTAINED_PACKET_COMBAT_MAX_AGE_MS if ongoing else SUSTAINED_EVIDENCE_MAX_AGE_MS
        active = timestamp - last_combat_ms <= 1200 if last_combat_ms else False
        if (
            sustained_evidence >= required
            and (active or ongoing)
            and timestamp - last_direct_ms <= max_age
            and timestamp - last_sustained_ms >= SUSTAINED_INTERVAL_MS
            and timestamp - last_flag_ms >= FLAG_COOLDOWN_MS
        ):
            verdict = sustained_from(last_direct, timestamp, as_int(row, "_row_index"))
            output.append(verdict)
            last_flag_ms = timestamp
            last_sustained_ms = timestamp
            sustained_evidence = max(0.0, sustained_evidence - (2.0 if ongoing else 6.0))

    return output


def ongoing_direct_combat(
    timestamp: int,
    last_direct: Optional[Verdict],
    last_direct_ms: int,
    last_combat_ms: int,
    attacks_since_direct: int,
    samples_since_direct: int,
) -> bool:
    if last_direct is None or last_direct.score < 90.0 or last_direct.confidence < 0.90:
        return False
    direct_age = timestamp - last_direct_ms
    if direct_age > SUSTAINED_PACKET_COMBAT_MAX_AGE_MS:
        return False
    if not last_combat_ms or timestamp - last_combat_ms > 1750:
        return False
    if direct_age > SUSTAINED_PACKET_COMBAT_FRESH_AGE_MS:
        mature = last_direct.score >= 130.0 and attacks_since_direct >= 4 and samples_since_direct >= 24
        if not mature:
            return False
    return attacks_since_direct >= 2 or (attacks_since_direct >= 1 and samples_since_direct >= 6)


def match_verdicts(actual: list[Verdict], predicted: list[Verdict], tolerance_ms: int) -> tuple[int, int, int]:
    used = [False] * len(actual)
    true_positive = 0
    for verdict in predicted:
        best_index = None
        best_delta = 10**18
        for index, target in enumerate(actual):
            if used[index]:
                continue
            delta = abs(target.timestamp - verdict.timestamp)
            if delta <= tolerance_ms and delta < best_delta:
                best_index = index
                best_delta = delta
        if best_index is not None:
            used[best_index] = True
            true_positive += 1
    false_positive = len(predicted) - true_positive
    false_negative = len(actual) - true_positive
    return true_positive, false_positive, false_negative


def summarize(path: str, mode: str, tolerance_ms: int) -> None:
    rows = load_rows(path)
    actual = actual_cloud_verdicts(rows)
    suppressed_existing = 0
    promoted = 0
    if mode == "observed":
        predicted = observed_replay(rows)
    elif mode == "candidate":
        predicted = candidate_replay(rows)
    elif mode == "project":
        predicted, suppressed_existing, promoted = projected_policy_replay(rows)
    else:
        raise ValueError(mode)

    true_positive, false_positive, false_negative = match_verdicts(actual, predicted, tolerance_ms)
    actual_counts = Counter(verdict.kind for verdict in actual)
    predicted_counts = Counter(verdict.kind for verdict in predicted)
    source_counts = Counter(verdict.source for verdict in actual)

    print(os.path.basename(path))
    print(f"  mode: {mode}")
    print(f"  rows: {len(rows)}")
    print(f"  actual cloud verdicts: {len(actual)} {dict(actual_counts)}")
    print(f"  actual sources: {dict(source_counts)}")
    print(f"  predicted verdicts: {len(predicted)} {dict(predicted_counts)}")
    print(f"  matched within {tolerance_ms}ms: tp={true_positive} fp={false_positive} fn={false_negative}")
    precision = true_positive / len(predicted) if predicted else 1.0
    recall = true_positive / len(actual) if actual else 1.0
    print(f"  precision/recall: {precision:.3f}/{recall:.3f}")
    print(f"  actual VL sum: {sum(verdict.vl for verdict in actual)}")
    print(f"  predicted VL sum: {sum(verdict.vl for verdict in predicted)}")
    if mode == "observed":
        orphan_sustained = sum(1 for verdict in predicted if verdict.kind == "sustained" and verdict.seed_id is None)
        print(f"  sustained rows without direct seed: {orphan_sustained}")
    if mode == "project":
        print(f"  projected existing verdicts suppressed: {suppressed_existing}")
        print(f"  projected aura-pressure promotions: {promoted}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Replay Nova combat cloud telemetry CSVs")
    parser.add_argument("csv", nargs="+", help="Telemetry CSV path(s)")
    parser.add_argument("--mode", choices=("observed", "candidate", "project"), default="observed")
    parser.add_argument("--tolerance-ms", type=int, default=175)
    args = parser.parse_args()

    for index, path in enumerate(args.csv):
        if index:
            print()
        summarize(path, args.mode, args.tolerance_ms)


if __name__ == "__main__":
    main()

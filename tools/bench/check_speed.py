#!/usr/bin/env python3
"""Gate the recorded device speeds: a drop above 20% fails.

Plan items 28-29. The phone writes one row per build into `speed.tsv`
(`learn/SpeedRecord.kt`); the rows that have been measured on the owner's S25
are kept in `docs/BENCHMARKS.tsv` with the same four columns. This script is the
CI half of the rule: it walks the rows of each architecture in time order and
exits 1 if any build is more than 20% slower than the one before it.

No rows is not a failure — it is a plain statement that nothing has been
measured on a phone yet, which is what the docs say too.

    python3 tools/bench/check_speed.py docs/BENCHMARKS.tsv
"""
from __future__ import annotations

import sys
from pathlib import Path

ALLOWED_DROP = 0.20


def parse(text: str) -> list[dict]:
    rows: list[dict] = []
    for number, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("generation"):
            continue
        parts = line.split("\t")
        if len(parts) < 4:
            raise ValueError(f"line {number}: expected 4 tab-separated columns, found {len(parts)}")
        try:
            rows.append(
                {
                    "generation": int(parts[0]),
                    "config": parts[1],
                    "tokens_per_second": float(parts[2]),
                    "at": int(parts[3]),
                }
            )
        except ValueError as error:
            raise ValueError(f"line {number}: {error}") from error
    return rows


def check(rows: list[dict], allowed_drop: float = ALLOWED_DROP) -> tuple[list[str], list[str]]:
    """Returns (lines to print, regressions). A regression line names both builds."""
    lines: list[str] = []
    regressions: list[str] = []
    for config in sorted({row["config"] for row in rows}):
        previous: dict | None = None
        for row in sorted((r for r in rows if r["config"] == config), key=lambda r: r["at"]):
            speed = row["tokens_per_second"]
            if previous is None:
                lines.append(f"{config} gen {row['generation']}: {speed:.1f} tok/s (first record)")
            else:
                was = previous["tokens_per_second"]
                change = (speed - was) / was if was > 0 else 0.0
                percent = abs(change) * 100
                if change >= 0:
                    lines.append(
                        f"{config} gen {row['generation']}: {speed:.1f} tok/s, up {percent:.0f}% "
                        f"on gen {previous['generation']} ({was:.1f})"
                    )
                elif -change <= allowed_drop:
                    lines.append(
                        f"{config} gen {row['generation']}: {speed:.1f} tok/s, {percent:.0f}% below "
                        f"gen {previous['generation']} ({was:.1f}), inside the 20% allowed"
                    )
                else:
                    regressions.append(
                        f"{config} gen {row['generation']}: {speed:.1f} tok/s is {percent:.0f}% below "
                        f"gen {previous['generation']} ({was:.1f}) — a drop above 20% is a regression"
                    )
            previous = row
    return lines, regressions


def main(argv: list[str]) -> int:
    path = Path(argv[1]) if len(argv) > 1 else Path("docs/BENCHMARKS.tsv")
    if not path.is_file():
        print(f"{path} does not exist, so no device speed has been recorded and nothing is claimed.")
        return 0
    try:
        rows = parse(path.read_text(encoding="utf-8"))
    except ValueError as error:
        print(f"REFUSED: {path} cannot be read: {error}")
        return 1
    if not rows:
        print(f"{path} has no rows yet: no speed has been measured on a phone, so nothing is claimed.")
        return 0
    lines, regressions = check(rows)
    for line in lines:
        print(line)
    for line in regressions:
        print(f"REGRESSION: {line}")
    if regressions:
        print(f"\n{len(regressions)} speed regression(s) above the 20% allowance.")
        return 1
    print(f"\n{len(rows)} record(s) checked, none more than 20% slower than the build before it.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

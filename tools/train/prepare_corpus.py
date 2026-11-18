#!/usr/bin/env python3
"""Turn the phone's learning ledger export into a training corpus.

Input: one JSON object per line, exactly what
`LearningLedger.exportJsonl` writes on the phone:

    {"kind":"CHAT_ASSISTANT","input":"...","output":"...","weight":0.6}

Output, in --out:
    train.jsonl   the examples the model learns from
    val.jsonl     the newest tenth, held out and never trained on
    manifest.json counts, hashes, the split rule

Rules this file will not bend:
  * a line that is not one JSON object with kind/input/output is an error and
    stops the run — a corpus that quietly loses rows trains a quieter model;
  * validation is the newest slice by input order (the export is written
    oldest first), because a validation score must test the future;
  * at most one copy of an example survives, so a fact repeated a hundred
    times cannot drown the rest of the corpus;
  * the split is deterministic: the same input always produces the same
    manifest hash, so a run can be checked and reproduced.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from dataclasses import dataclass
from pathlib import Path

TRAINING_KINDS = {"CHAT_ASSISTANT", "CORRECTION", "TEACHER_DISTIL", "PLAN", "PATCH"}
VAL_FRACTION = 0.10


class CorpusError(Exception):
    """A problem with the export that the owner must see, not a silent skip."""


@dataclass(frozen=True)
class Example:
    kind: str
    input: str
    output: str
    weight: float


def parse_line(line: str, number: int) -> Example | None:
    text = line.strip()
    if not text:
        return None  # blank line between records is not data loss
    try:
        obj = json.loads(text)
    except json.JSONDecodeError as exc:
        raise CorpusError(f"line {number}: not valid JSON ({exc.msg})") from exc
    if not isinstance(obj, dict):
        raise CorpusError(f"line {number}: expected one JSON object, got {type(obj).__name__}")
    for key in ("kind", "input", "output"):
        if key not in obj:
            raise CorpusError(f"line {number}: missing '{key}'")
        if not isinstance(obj[key], str):
            raise CorpusError(f"line {number}: '{key}' must be text")
    weight = obj.get("weight", 1.0)
    if not isinstance(weight, (int, float)) or isinstance(weight, bool):
        raise CorpusError(f"line {number}: 'weight' must be a number")
    if weight <= 0:
        raise CorpusError(f"line {number}: 'weight' must be greater than zero")
    return Example(obj["kind"], obj["input"], obj["output"], float(weight))


def load(path: Path) -> list[Example]:
    examples: list[Example] = []
    with path.open(encoding="utf-8") as handle:
        for number, line in enumerate(handle, start=1):
            example = parse_line(line, number)
            if example is not None:
                examples.append(example)
    if not examples:
        raise CorpusError(f"{path}: no examples at all — the phone has not recorded anything yet")
    return examples


def answerable(examples: list[Example]) -> list[Example]:
    kept = [
        e for e in examples
        if e.kind in TRAINING_KINDS and e.input.strip() and e.output.strip()
    ]
    # One copy per distinct input+output; keep the largest weight.
    best: dict[tuple[str, str], Example] = {}
    for e in kept:
        key = (e.input.strip(), e.output.strip())
        previous = best.get(key)
        if previous is None or e.weight > previous.weight:
            best[key] = e
    # Preserve the original order so "newest" still means newest.
    return [e for e in kept if best[(e.input.strip(), e.output.strip())] == e]


def split(examples: list[Example]) -> tuple[list[Example], list[Example]]:
    if len(examples) < 10:
        # Holding out a tenth of nine examples means training on eight and
        # judging on one. There is nothing to judge yet.
        return examples, []
    count = max(1, round(len(examples) * VAL_FRACTION))
    return examples[:-count], examples[-count:]


def to_jsonl(examples: list[Example]) -> str:
    lines = [
        json.dumps(
            {"kind": e.kind, "input": e.input, "output": e.output, "weight": e.weight},
            ensure_ascii=False,
            separators=(",", ":"),
        )
        for e in examples
    ]
    return "\n".join(lines) + ("\n" if lines else "")


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def build(source: Path, out_dir: Path) -> dict:
    examples = load(source)
    usable = answerable(examples)
    train, val = split(usable)
    train_text = to_jsonl(train)
    val_text = to_jsonl(val)
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "train.jsonl").write_text(train_text, encoding="utf-8")
    (out_dir / "val.jsonl").write_text(val_text, encoding="utf-8")
    manifest = {
        "source": source.name,
        "sourceSha256": sha256_text(source.read_text(encoding="utf-8")),
        "rowsIn": len(examples),
        "rowsAnswerable": len(usable),
        "train": len(train),
        "val": len(val),
        "valFractionTarget": VAL_FRACTION,
        "trainSha256": sha256_text(train_text),
        "valSha256": sha256_text(val_text),
        "trainingKinds": sorted(TRAINING_KINDS),
        "splitRule": "validation is the newest slice; fewer than 10 examples means no val",
    }
    (out_dir / "manifest.json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
    )
    return manifest


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="the exported ledger JSONL from the phone")
    parser.add_argument("--out", type=Path, default=Path("corpus"), help="output directory")
    args = parser.parse_args(argv)
    try:
        manifest = build(args.source, args.out)
    except CorpusError as exc:
        print(f"corpus refused: {exc}", file=sys.stderr)
        return 2
    print(
        f"{manifest['rowsIn']} rows read, {manifest['rowsAnswerable']} answerable, "
        f"{manifest['train']} train / {manifest['val']} validation"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

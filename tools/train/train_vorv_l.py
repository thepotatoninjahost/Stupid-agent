#!/usr/bin/env python3
"""Vorv-L: fine-tune a 9B-class open model on the owner's own corpus.

This is the quality path. It runs on a free GPU (Kaggle T4×2 / P100, or Colab
T4), reads exactly the two files tools/train/prepare_corpus.py produced, and
writes one artifact only if the tuned model is better than the model it started
from — measured on the held-out slice, before and after.

    python train_vorv_l.py --corpus corpus/ --out run1/ \
        --base Qwen/Qwen2.5-Coder-7B-Instruct --steps 300

Nothing here decides what "better" means on its own: it writes metrics.json in
the same shape the phone's PromotionGate reads, so the same refusal rules apply
on both paths. The one decision this file makes itself is the plan's clause 1:
a tuned adapter that did not beat its base model is not saved at all.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path


class TrainError(Exception):
    """Something the owner must see: no artifact is written when this is raised."""


def read_jsonl(path: Path) -> list[dict]:
    if not path.is_file():
        raise TrainError(f"{path} is missing — run prepare_corpus.py first")
    rows = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        text = line.strip()
        if not text:
            continue
        try:
            row = json.loads(text)
        except json.JSONDecodeError as exc:
            raise TrainError(f"{path} line {number}: not valid JSON ({exc.msg})") from exc
        for key in ("input", "output"):
            if not isinstance(row.get(key), str) or not row[key].strip():
                raise TrainError(f"{path} line {number}: '{key}' is empty")
        rows.append(row)
    if not rows:
        raise TrainError(f"{path} has no rows")
    return rows


def example_weight(row: dict) -> float:
    weight = row.get("weight", 1.0)
    if not isinstance(weight, (int, float)) or isinstance(weight, bool) or weight <= 0:
        raise TrainError("weight must be a positive number")
    return float(weight)


def to_prompt(row: dict) -> str:
    """One training string. Corrections are phrased as the thing to do instead."""
    kind = row.get("kind", "CHAT_ASSISTANT")
    if kind == "CORRECTION":
        return f"### Correction\nInstead of: {row['input']}\nDo this: {row['output']}"
    if kind == "PATCH":
        return f"### Change requested\n{row['input']}\n### Patch\n{row['output']}"
    return f"### Request\n{row['input']}\n### Answer\n{row['output']}"


def is_improvement(base_loss: float, tuned_loss: float) -> bool:
    """The plan's clause: the tuned model must beat its base, not tie with it."""
    return tuned_loss < base_loss


def manifest(base: str, corpus: Path, train_rows: int, val_rows: int, config: dict) -> dict:
    digest = hashlib.sha256()
    for name in ("train.jsonl", "val.jsonl"):
        digest.update((corpus / name).read_bytes())
    return {
        "baseModel": base,
        "trainRows": train_rows,
        "valRows": val_rows,
        "corpusSha256": digest.hexdigest(),
        "config": json.dumps(config, sort_keys=True),
        "createdAt": int(__import__("time").time() * 1000),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--base", default="Qwen/Qwen2.5-Coder-7B-Instruct")
    parser.add_argument("--steps", type=int, default=300)
    parser.add_argument("--lr", type=float, default=2e-4)
    parser.add_argument("--max-seq", type=int, default=2048)
    parser.add_argument("--batch", type=int, default=1)
    parser.add_argument("--grad-accum", type=int, default=8)
    args = parser.parse_args(argv)

    try:
        train = read_jsonl(args.corpus / "train.jsonl")
        val = read_jsonl(args.corpus / "val.jsonl")
        if not val:
            raise TrainError(
                "val.jsonl is empty: with no held-out slice this run could only prove "
                "the model changed, not that it got better. Record more turns on the "
                "phone (ten answerable rows is the minimum for a validation slice)."
            )
    except TrainError as exc:
        print(f"train refused: {exc}", file=sys.stderr)
        return 2

    args.out.mkdir(parents=True, exist_ok=True)
    config = {
        "base": args.base, "steps": args.steps, "lr": args.lr,
        "maxSeq": args.max_seq, "batch": args.batch, "gradAccum": args.grad_accum,
        "loraR": 16, "loraAlpha": 32, "loraDropout": 0.05, "quant": "nf4",
    }

    try:
        import torch
        from datasets import Dataset
        from peft import LoraConfig, get_peft_model, prepare_model_for_kbit_training
        from transformers import (
            AutoModelForCausalLM,
            AutoTokenizer,
            BitsAndBytesConfig,
            DataCollatorForLanguageModeling,
            Trainer,
            TrainingArguments,
        )
    except ImportError as exc:
        print(f"train refused: this run needs torch/transformers/peft/bitsandbytes ({exc})", file=sys.stderr)
        return 3

    tokenizer = AutoTokenizer.from_pretrained(args.base, trust_remote_code=True)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    def encode(rows: list[dict]) -> Dataset:
        texts = [to_prompt(r) for r in rows]
        enc = tokenizer(texts, truncation=True, max_length=args.max_seq, padding=False)
        return Dataset.from_dict({k: v for k, v in enc.items()})

    train_ds = encode(train)
    val_ds = encode(val)

    quant = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_quant_type="nf4",
        bnb_4bit_compute_dtype=torch.bfloat16,
        bnb_4bit_use_double_quant=True,
    )
    model = AutoModelForCausalLM.from_pretrained(
        args.base, quantization_config=quant, device_map="auto", trust_remote_code=True
    )
    model = prepare_model_for_kbit_training(model)
    model = get_peft_model(model, LoraConfig(
        r=config["loraR"], lora_alpha=config["loraAlpha"], lora_dropout=config["loraDropout"],
        bias="none", task_type="CAUSAL_LM",
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
    ))

    trainer = Trainer(
        model=model,
        args=TrainingArguments(
            output_dir=str(args.out / "checkpoints"),
            max_steps=args.steps,
            per_device_train_batch_size=args.batch,
            gradient_accumulation_steps=args.grad_accum,
            learning_rate=args.lr,
            logging_steps=10,
            save_strategy="no",
            report_to=[],
            bf16=torch.cuda.is_bf16_supported() if torch.cuda.is_available() else False,
            fp16=not (torch.cuda.is_available() and torch.cuda.is_bf16_supported()),
        ),
        train_dataset=train_ds,
        data_collator=DataCollatorForLanguageModeling(tokenizer, mlm=False),
    )

    base_eval = trainer.evaluate(eval_dataset=val_ds)
    base_loss = float(base_eval.get("eval_loss", float("nan")))
    trainer.train()
    tuned_eval = trainer.evaluate(eval_dataset=val_ds)
    tuned_loss = float(tuned_eval.get("eval_loss", float("nan")))

    metrics = {
        "baseLoss": base_loss,
        "tunedLoss": tuned_loss,
        "trainRows": len(train),
        "valRows": len(val),
        "stepped": args.steps,
    }
    (args.out / "metrics.json").write_text(json.dumps(metrics, indent=2) + "\n", encoding="utf-8")

    if not (base_loss == base_loss and tuned_loss == tuned_loss):  # NaN check
        print("train refused: the loss could not be measured, so nothing is saved", file=sys.stderr)
        return 4
    if not is_improvement(base_loss, tuned_loss):
        print(
            f"train refused: tuned loss {tuned_loss:.4f} did not beat base {base_loss:.4f}; "
            "no adapter is written",
            file=sys.stderr,
        )
        return 5

    adapter = args.out / "adapter"
    model.save_pretrained(adapter)
    tokenizer.save_pretrained(adapter)
    (args.out / "manifest.json").write_text(
        json.dumps(
            manifest(args.base, args.corpus, len(train), len(val), config),
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )
    print(f"kept: tuned loss {tuned_loss:.4f} beat base {base_loss:.4f}; adapter in {adapter}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

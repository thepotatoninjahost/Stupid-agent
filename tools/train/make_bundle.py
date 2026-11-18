#!/usr/bin/env python3
"""Pack a GPU run into the zip the phone can import.

The phone refuses anything it cannot check, so this writer fails loudly when a
piece of evidence is missing instead of producing a bundle that will be turned
away later:

  * the model file must exist and be over 1 GiB (a 7B model at 4-bit is ~4 GB);
  * ``--probes`` must record a passed/total count for every probe kind the
    phone's suite grades, measured with the same suite version;
  * the model's SHA-256 and byte length are computed here and written into the
    manifest, so the phone can prove it received the same file.

Usage (on Kaggle/Colab, after training):

    python make_bundle.py --model out/model.gguf --probes out/probes.json \\
        --metrics out/metrics.json --base Qwen/Qwen2.5-Coder-7B-Instruct \\
        --corpus-sha256 <sha> --out out/vorv-l-bundle.zip
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
import zipfile

KINDS = ("INSTRUCTION", "TOOL_CHOICE", "STILL_KNOWS", "REFUSAL", "PROJECT")
SUITE_VERSION = 1
MIN_MODEL_BYTES = 1024 * 1024 * 1024
MANIFEST = "manifest.json"
METRICS = "metrics.json"


class BundleError(Exception):
    """Something is missing; no bundle is written."""


def sha256_of(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def read_json(path: str) -> dict:
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return json.load(handle)
    except FileNotFoundError:
        raise BundleError(f"{path} does not exist, so there is no evidence to pack.")
    except json.JSONDecodeError as error:
        raise BundleError(f"{path} is not valid JSON: {error}")


def check_probes(probes: dict) -> tuple[dict, dict]:
    """Every kind the phone grades must be counted; a partial run is refused."""
    suite = int(probes.get("suiteVersion", -1))
    if suite != SUITE_VERSION:
        raise BundleError(
            f"the probes were measured with suite {suite}, but this bundle must carry suite "
            f"{SUITE_VERSION}; numbers from two suites cannot be compared."
        )
    passed = probes.get("passed") or {}
    total = probes.get("total") or {}
    missing = [kind for kind in KINDS if int(total.get(kind, 0)) <= 0 or kind not in passed]
    if missing:
        raise BundleError(
            "the probe results do not cover every kind the phone grades: "
            + ", ".join(missing)
        )
    for kind in KINDS:
        if int(passed[kind]) > int(total[kind]):
            raise BundleError(f"probe kind {kind} reports more passes than probes.")
    return {k: int(passed[k]) for k in KINDS}, {k: int(total[k]) for k in KINDS}


def build_manifest(
    model: str,
    probes: dict,
    metrics: dict,
    base: str,
    corpus_sha256: str,
    config: str,
) -> dict:
    if not os.path.isfile(model):
        raise BundleError(f"{model} does not exist, so there is no model to pack.")
    size = os.path.getsize(model)
    passed, total = check_probes(probes)
    val_loss = metrics.get("valLoss", metrics.get("tunedLoss"))
    if val_loss is None:
        raise BundleError("metrics.json has no valLoss/tunedLoss, so the phone cannot compare it.")
    if not base:
        raise BundleError("--base is required; the phone records what the model was trained from.")
    if not corpus_sha256:
        raise BundleError(
            "--corpus-sha256 is required; without it nobody can tell which ledger trained this model."
        )
    # The size floor is checked last: it is the coarsest test, so a bundle that
    # is wrong in a specific way is told that instead of "too small".
    if size < MIN_MODEL_BYTES:
        raise BundleError(
            f"{model} is {size} bytes; a real 7B model is over {MIN_MODEL_BYTES}. "
            "Refusing to pack a stub."
        )
    return {
        "origin": "gpu",
        "baseModel": base,
        "corpusSha256": corpus_sha256,
        "valLoss": float(val_loss),
        "suiteVersion": SUITE_VERSION,
        "passed": passed,
        "total": total,
        "config": config,
        "createdAt": int(time.time() * 1000),
        "model": {
            "file": os.path.basename(model),
            "bytes": size,
            "sha256": sha256_of(model),
        },
    }


def write_bundle(model: str, manifest: dict, metrics_path: str, out: str) -> str:
    os.makedirs(os.path.dirname(os.path.abspath(out)) or ".", exist_ok=True)
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED, allowZip64=True) as archive:
        archive.writestr(MANIFEST, json.dumps(manifest, indent=2))
        if metrics_path and os.path.isfile(metrics_path):
            archive.writestr(METRICS, json.dumps(read_json(metrics_path), indent=2))
        archive.write(model, "model.gguf")
    return out


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True, help="the merged model in .gguf form")
    parser.add_argument("--probes", required=True, help="probe results written by the GPU run")
    parser.add_argument("--metrics", default="", help="metrics.json from training")
    parser.add_argument("--base", required=True, help="base model the adapter was trained on")
    parser.add_argument("--corpus-sha256", default="", help="fingerprint of the corpus that trained it")
    parser.add_argument("--config", default="", help="training config, as text or JSON")
    parser.add_argument("--out", required=True, help="where to write the bundle zip")
    args = parser.parse_args(argv)

    try:
        probes = read_json(args.probes)
        metrics = read_json(args.metrics) if args.metrics else {}
        manifest = build_manifest(
            args.model, probes, metrics, args.base, args.corpus_sha256, args.config
        )
        write_bundle(args.model, manifest, args.metrics, args.out)
    except BundleError as error:
        print(f"bundle not written: {error}", file=sys.stderr)
        return 2

    print(f"wrote {args.out} ({os.path.getsize(args.out)} bytes)")
    print(f"model sha256 {manifest['model']['sha256']} ({manifest['model']['bytes']} bytes)")
    print("bring this file to the phone: Brain screen -> Bring a GPU generation")
    return 0


if __name__ == "__main__":
    sys.exit(main())

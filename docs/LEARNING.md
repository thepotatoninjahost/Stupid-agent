# Learning — what the on-device brain is, and what has actually been measured

This file is the record the plan asks for: every claim here is a measurement made
by this repository's own tests or on the phone, or it is marked **not measured**.

## Where the pieces live

| Piece | File | What it does |
|---|---|---|
| Tensor engine | `app/src/main/java/host/vorv/learn/Tensor.kt` | Forward and backward for every operation the model uses; AdamW. Pure Kotlin, no native code. |
| Model | `learn/VorvCore.kt` | Small decoder transformer with grouped-query attention. Rungs from ~8M to ~78M parameters, each with its measured training-memory requirement. |
| Tokenizer | `learn/Tokenizer.kt` | Byte-level BPE trained from this owner's own ledger text. |
| Weights store | `learn/WeightsStore.kt` | One folder per generation, big-endian CRC32 in the file, SHA-256 in the manifest, atomic writes. |
| Inference | `learn/Inference.kt` | Perplexity, generation, tokens/second, bits-per-byte. |
| Trainer | `learn/Trainer.kt` | Trains a candidate from the ledger, measures held-out loss, asks the promotion gate. |
| Gate | `learn/PromotionGate.kt` | Refuses a candidate that is not non-decreasing on every axis. |
| Generations | `learn/GenerationStore.kt` | Numbered generations, a champion pointer, one-tap rollback by pointer swap. |
| When it runs | `learn/TrainingWorker.kt` | WorkManager job with charging + idle + battery-not-low constraints, and its own foreground service while it trains. |
| What the owner sees | `ui/screen/BrainScreen.kt`, `learn/BrainReport.kt` | Champion, generations, refusal sentences, run history, loss chart, rollback. |

## Rungs, and what each costs

`VorvCore.trainingBytes(config)` counts parameters **and** gradients, AdamW's two
moments, and `activationFactor` sequence slots of activations at 4 bytes. The
trainer never picks a rung it cannot hold: `chooseTrainingRung(budgetBytes)`
returns the largest rung that fits and lists the ones it skipped with the bytes
each would have needed. A demotion therefore always names its measurement.

On a Galaxy S25 (12 GB RAM, `largeHeap`, roughly 512 MB process heap), the
budget is 55% of the heap — about 281 MB — which selects the SMALL rung. The
larger rungs stay reachable on the GPU path or a future native engine; they are
not silently substituted.

## What has been measured

- 60 local JVM tests: gradients match finite differences (tolerance 0.02),
  AdamW converges on a toy problem, weights reload byte-for-byte, a truncated
  file is refused by CRC and by SHA-256, a tokenizer wider than the model is
  refused instead of crashing, the gate refuses flat and regressing candidates,
  rollback moves the champion pointer back and leaves both generations on disk.
- Held-out loss is measured in bits per byte, so it can be compared against
  published 9B results later without a unit change.

## The way back from the GPU (Step 10a)

The quality model is trained away from the phone and returned as one zip. The
phone is the judge, not the recipient:

1. On a free GPU (Kaggle T4×2 is the primary, Colab T4 the backup) run
   `tools/train/train_vorv_l.py` on the corpus export. It writes an adapter and
   `metrics.json`, and refuses to save anything if the tuned loss did not beat the
   base loss.
2. Merge the adapter and convert it to `.gguf`, run the app's probes on it, and
   pack the result with `tools/train/make_bundle.py`. That packer refuses a stub
   model, a run with no held-out loss, no corpus fingerprint, a different suite
   version, or probe results missing a kind — a weak bundle fails on the GPU
   where it is cheap to see.
3. On the phone: Brain screen → **Bring a GPU generation** → pick the zip.

What the phone does with it, in order: unwraps it, hashes the model as it writes
it, compares that fingerprint and the byte count with the manifest, refuses a
model under 1 GiB as a stub, checks the suite version matches this build, checks
every probe kind is counted, and then asks the promotion gate whether this model
beats the generation currently serving. Accepted → it becomes the champion. It
lost on the gate → the file is kept as a generation with the sentence that says
why, and nothing serves it. It could never serve (stub, stopped download, corrupt
file, wrong suite, a kind missing) → the file is deleted and no generation is
written.

Serving: before the downloaded base model, the engine asks the generation index
for a champion of GPU origin whose file still verifies, and loads that instead —
NPU first, CPU second. If it will not start, the reason is logged and shown in the
header and the base model answers, so a bad generation costs speed, not the app.

Not yet done: no bundle has been produced from this owner's corpus yet, and no
trained generation has been loaded on the S25, so nothing here is a release claim
about the 9B model's quality. The checks and the load order are tested on the JVM;
the 4 GB load itself is not measured on the phone.

## Not measured yet

- Tokens per second on the actual phone. The Brain screen will show it; until a
  run happens on the S25 the number in the docs is a JVM number, not the phone's.
- NEON and fast softmax: **not adopted, measured nothing yet.** The rule stands —
  each is adopted only at ≥ 1.5× measured speed-up on the device, otherwise this
  line is updated with the measurement and the step still counts as passed.
- The 9B reasoner (Vorv-L): the GPU path exists (`tools/train/`), but no
  generation has been trained from this owner's corpus yet, so no release claim
  is made about it.

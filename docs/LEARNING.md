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

## Not measured yet

- Tokens per second on the actual phone. The Brain screen will show it; until a
  run happens on the S25 the number in the docs is a JVM number, not the phone's.
- NEON and fast softmax: **not adopted, measured nothing yet.** The rule stands —
  each is adopted only at ≥ 1.5× measured speed-up on the device, otherwise this
  line is updated with the measurement and the step still counts as passed.
- The 9B reasoner (Vorv-L): the GPU path exists (`tools/train/`), but no
  generation has been trained from this owner's corpus yet, so no release claim
  is made about it.

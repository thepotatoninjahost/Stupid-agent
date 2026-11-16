# What the research actually says about a brain that runs on a phone

Written 2026-11-16. Every claim below is tied to a source that was read during
the research session, not recalled. Two sections at the end separate what is
proven from what would be new work, because the project's history is a graveyard
of "it works" that did not.

**Why this file exists.** This assistant told the owner that a neural network
"cannot be trained on a phone". That statement was false, and it cost the owner
time. The corrected position is below, with the papers and code that settle it.

---

## 1. Fine-tuning a real language model on an ordinary phone

**PocketLLM — Enabling On-Device Fine-Tuning for Personalized LLMs** (PrivateNLP
2024, doi:10.18653/v1/2024.privatenlp-1.10). Uses *derivative-free* optimisation,
which removes the memory cost of gradients and optimiser state. Fine-tunes
RoBERTa-large in about **4 GB** and OPT-1.3B in about **6.5 GB** on an **OPPO
Reno 6**. Applied and measured on retail hardware.

**MobiZO — Enabling Efficient LLM Fine-Tuning at the Edge via Inference Engines**
(EMNLP 2025, doi:10.18653/v1/2025.emnlp-main.1022). The insight: an *inference*
engine (the paper names ExecuTorch) can be repurposed for training by
zeroth-order optimisation — many forward passes approximating a gradient. Adds a
parallelised randomised gradient estimator (outer- and inner-loop) and
multi-perturbed LoRA (MP-LoRA). This is the most relevant result for Vorv: the
phone already runs inference engines; training does not require a second,
heavier stack.

**PockEngine — Sparse and Efficient Fine-tuning in a Pocket** (ASPLOS 2023,
doi:10.1145/3613424.3614307, Microsoft). Sparse backpropagation: prune the
backward graph, update sparsely, derive the whole training graph at compile time.
Reports memory savings and latency cuts while keeping model quality.

**Consequence for Vorv.** "The model's weights can change on this phone, from
data the owner provides" is a measured fact, not an aspiration. The workable
sizes are sub-2B with derivative-free or PEFT methods, not frontier models.

## 2. Training with no framework at all: plain C on the CPU

**llm.c** (github.com/karpathy/llm.c, ~31k stars). Trains GPT-2 124M from
scratch in roughly 1,000 lines of C, with a **CPU fp32 reference implementation**
(`train_gpt2.c`) that needs neither PyTorch nor a GPU.

**llama.cpp `examples/training`** (github.com/ggml-org/llama.cpp). Its README
states: *"For CPU training, compile llama.cpp without any additional backends
such as CUDA."* Reports finetuning working for a 260K-parameter model, and a
1B model needing about 24 GB of RAM.

**Consequence.** Inside Vorv's own Alpine guest, `apk add build-base` then
`make` gives a real trainer: a tiny model can be trained on the phone's CPU,
offline, with weights that genuinely move. A 1B model on the phone is not on the
table; a 260K–10M model is.

## 3. Small models are not a dead end, they are an architecture problem

**MobileLLM: Optimizing Sub-billion Parameter Language Models for On-Device Use
Cases** (2024, 187 citations). Deep-and-thin architectures, embedding sharing,
and training choices aimed specifically at sub-billion models.

**Consequence.** "A 7B coder is useless" and "small models are useless" can both
be true of the models that exist while being false of the ceiling. What is *not*
true is that a phone-sized model will match a frontier model at writing new code.

## 4. A logic engine that learns from experience: NARS

**OpenNARS** (github.com/opennars/opennars, ~430 stars, actively maintained) is
an implementation of Wang's **Non-Axiomatic Reasoning System**. Its README
describes the architecture as **memory + inference engine + control mechanism**,
processing tasks that can arrive at any time, under insufficient knowledge and
resources, in a formal I/O language (Narsese). Truth values are evidence-weighted
and revised as experience accumulates — learning is part of the logic, not a
separate training pass.

**Consequence.** This is the closest existing thing to the owner's stated goal:
reasoning with explicit logic that improves because evidence builds up. Java,
runs on a phone, no server.

## 5. Self-growing skills: proven twice, a decade apart

**DreamCoder: growing generalizable, interpretable knowledge with wake–sleep
Bayesian program learning** (2023, 235 citations). Solves problems, then
*compresses* its own solutions into reusable abstractions, and gets better
because of them. Also: "DreamCoder: bootstrapping inductive program synthesis
with wake–sleep library learning" (2021, 159 citations).

**Voyager** (github.com/MineDojo/Voyager, ~7.3k stars). Applied version: an agent
writes code, keeps verified programs in a **skill library**, and reuses them.

**HOUDINI** (2018, 47 citations): lifelong learning as program synthesis.
**Stitch** (github.com/mlb2251/stitch, ~111 stars): scalable library learning,
used in practice for code de-duplication. **Top-Down Synthesis for Library
Learning** (2023, 66 citations).

**Consequence.** "Learns by turning repeated success into a reusable step" is an
implemented algorithm, not a metaphor.

## 6. The known failure mode: forgetting

Edge continual-learning work (MEST 2021, ZeroFL 2022, SparCL 2022, plus
2023–2024 mobile continual-learning papers) exists largely because learning on
device catastrophically forgets. Mitigations: sparsity, replay, parameter-
efficient adapters.

**Consequence.** Any learning Vorv does needs a regression check against skills
it already had. Vorv already has the mechanism this project trusts most: run the
thing and look at the output.

---

## The shape this suggests

| Piece | Basis | Status |
|---|---|---|
| Reasoning core: memory + inference + control, evidence-weighted | NARS / OpenNARS | published, Java, phone-capable |
| Skill growth: success becomes a reusable abstraction | DreamCoder, Voyager, Stitch, HOUDINI | published, applied |
| Weights that move from owner-supplied data | PocketLLM, MobiZO, PockEngine, llm.c, llama.cpp | published, measured on phones |
| Execution, compiling, testing | Vorv's own Alpine guest | working, verified on the owner's S25 in 0.9.3 |
| Forgetting control | continual-learning literature | known problem, known mitigations |

## What is proven, what is not

**Proven by others, on phones:** fine-tuning sub-2B models on retail hardware;
CPU training of tiny models with no framework; evidence-revising logic engines;
libraries that grow from solved work.

**Not proven by anyone:** these four pieces assembled into one phone-native
agent that writes arbitrary working applications. That is new work. It is not
assembly of finished parts, and no honest source claims otherwise.

## Sources

- PocketLLM — doi:10.18653/v1/2024.privatenlp-1.10
- MobiZO — doi:10.18653/v1/2025.emnlp-main.1022
- PockEngine — doi:10.1145/3613424.3614307
- MobileLLM — arXiv 2402.14905
- DreamCoder — arXiv 2103.08315 / 2006.08381
- HOUDINI — arXiv 1803.01438; Stitch — github.com/mlb2251/stitch
- NARS / OpenNARS — github.com/opennars/opennars; "Non-Axiomatic Logic" (Wang)
- llm.c — github.com/karpathy/llm.c
- llama.cpp training — github.com/ggml-org/llama.cpp/tree/master/examples/training
- Voyager — github.com/MineDojo/Voyager
- MEST / ZeroFL / SparCL — edge sparse-training papers (2021–2022)

# Working rules for this repository

How to work on Vorv, written down after four and a half years of builds that
were reported as fixed and were not.

## Reading before theorising

- When a failure is reported, read the exact code path **in order, top to
  bottom**, before forming any theory. The 0.9.2 provisioning bug — a stamp file
  checked eight lines before it was written — was invisible to every test and
  obvious to a single ordered read.
- Do not blame the payload, the device, or Android until the code that uses them
  has been read line by line.

## Research before answering

- When the owner says "research", go and look, and answer with sources. Do not
  answer a feasibility question from memory.
- Never describe a phone capability as impossible in this session without having
  searched for it in this session. See `docs/research/brain.md`: on-device
  training was called impossible here and had been published for years.

## Evidence

- A test is only evidence for the claim it actually exercises. State which claim
  a test covers, and prove a new test fails on the old code before trusting it.
- Never present a passing suite as proof of on-device behaviour. The owner's
  Galaxy S25 is the final check, and only the owner can run it.
- Report what was run and what came back, including failures. No "fixed" without
  a check run after the last change.

## Builds

- Do not run a build, Gradle task, release, or push unless the owner has ordered
  that specific piece of work. Reading, explaining and writing docs need no
  order; building does.
- Every accepted piece of work is committed and pushed to `main` and, when it is
  an app change, published as a release, because the owner keeps their record
  only in this repository.

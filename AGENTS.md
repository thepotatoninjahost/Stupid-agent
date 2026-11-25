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
- After editing anything under `app/src/main/java`, run
  `./gradlew :app:syncSourceMirror` and commit `app/src/main/assets/source`
  too. Since M-4 the build does not sync it for you: `verifySourceMirror` runs
  before build and test and fails, naming the files, when the mirror is stale.

## Field lessons

- 0.9.4 reached the phone with Debian 12.15 booting (kernel `6.6.127-android15`)
  but no toolchain plan: `.gitignore` excluded the whole
  `app/src/main/assets/forge/linux/` folder, so `toolchain.json` — source, not
  payload — never reached the GitHub builder. Only payload binaries belong in
  that ignore rule; anything the app reads to decide *what* to download is
  committed, and `checkPayload` now refuses a build without it.
- Release R8 fails on any transitive class commons-compress references but that
  is not on the classpath (`org.tukaani.xz` for Debian's `data.tar.xz`). A debug
  build will not catch it: run `assembleRelease`/`minifyReleaseWithR8` before
  shipping.
- Both failures above were found by CI or the phone, never by the unit tests, so
  a green unit suite is not a reason to call a build good.
- `dpkg-deb -x` lays a Debian package's files down but runs **no** post-install
  script, so update-alternatives links never appear: after unpacking
  openjdk-17-jdk-headless there is no `/usr/bin/javac` at all, only
  `/usr/lib/jvm/java-1.17.0-openjdk-arm64/bin/javac`. Link the five JDK tools
  into `/usr/bin` ourselves and verify with `test -x` *inside* the guest.
- Never unpack Debian's packages with our own tar code. 0.9.5 did and died on the
  phone with "Too many symbolic links": Debian's link chains only resolve
  correctly inside the guest. `dpkg-deb -x` inside the guest is the answer.
- The whole plan can be drilled on any Linux machine before shipping: fetch all
  169 `.deb`s, check each sha256, run `dpkg-deb -x` over a copy of the rootfs, and
  assert the tools land. That drill found the missing javac; the phone found the
  ELOOP. Unit tests found neither.

## The owner's standing rule: research before AND after every task

The owner has required this every time and will not accept work without it, so
it is not optional and not something to do only when it seems useful:

1. Before starting any task, do real internet research on it — official
   documentation, the vendor's own pages, the upstream project — deep enough to
   answer "how does this actually work" and "what does the manual say".
2. After finishing the task, research again: check the result against the
   official documentation, and check whether the change contradicts anything
   the docs say.
3. Cite it: name the exact URL and the sentence or constant relied on, and say
   plainly which sources were dead or unreadable and what that leaves
   unverified. Never claim a source was checked when it was not.
4. If a claim cannot be confirmed from a source, say so instead of asserting it.

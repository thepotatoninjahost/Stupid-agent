# The one workflow change that still needs `workflow` scope

`.github/workflows/build.yml` cannot be updated by the connected GitHub account:
its OAuth token carries `gist`, `read:org` and `repo` only, and GitHub refuses
any push that touches a workflow file without the `workflow` scope.

Since 0.9.2 this file matters much less than it did:

* **Provisioning no longer needs a workflow step.** `app/build.gradle.kts` has a
  `provisionPayload` task that runs `tools/forge/fetch-runtime.sh` and
  `tools/forge/fetch-linux.sh` whenever a required payload file is missing, and
  `checkPayload` depends on it. A fresh clone — CI included — builds a
  payload-carrying APK with no extra steps. `-PskipPayloadFetch` turns the
  download off when a build should fail loudly instead.
* **What is left in the patch** is the two things a workflow file can still do
  that Gradle cannot: the APK gate step (inspect the *finished* APK before
  anything is uploaded, so a computer-less build can never become an artifact or
  a release) and the release-notes text, which still told the owner to install
  Termux (R-3, review 2026-11-14). The notes now say what the build actually
  contains: Debian 12 under proot, no root, no Termux, packages via `apt-get`.

To apply it: grant the `workflow` scope, then

    git apply tools/ci/workflow-payload-step.patch

`git apply --check` passes against the current `build.yml`; the patch was
regenerated after the Gradle provisioning landed, so it contains only those two
hunks.

Until it lands, CI still cannot publish a computer-less APK: `checkPayload`
fails the build instead. What the gate adds is the second lock — on the APK.

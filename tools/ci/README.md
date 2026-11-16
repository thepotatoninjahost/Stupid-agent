# CI payload provisioning — needs `workflow` scope to land

`.github/workflows/build.yml` in this repository cannot be updated by the
connected GitHub account: its OAuth token carries `gist`, `read:org` and `repo`
only, and GitHub refuses any push that touches a workflow file without the
`workflow` scope.

`workflow-payload-step.patch` is the exact change, kept here so it cannot be
lost. It was applied here and verified to produce a payload-carrying APK; only
the push was rejected.

To apply it: grant the `workflow` scope, then

    git apply tools/ci/workflow-payload-step.patch

The change adds one provisioning step to each job (`tools/forge/fetch-runtime.sh`
+ `tools/forge/fetch-linux.sh`) and one gate step that runs
`tools/forge/verify-apk-payload.py` on the finished APKs. Until it lands, CI
still cannot publish a computer-less APK, because `checkPayload` (wired into
`preBuild`) fails the build — the run goes red instead of shipping a build with
no Linux system in it.

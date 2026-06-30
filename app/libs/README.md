# sdk.jar goes here

This file is intentionally NOT checked into the repo - it's fetched by CI from a pinned build
matching Xed-Editor v3.2.9 / versionCode 87 (commit `73835433`). See
`.github/workflows/plugin-build-test.yml` and the project README for why (short version: the
upstream auto-published sdk.jar now targets a newer, incompatible extension API).

Building via GitHub Actions (`git push`, or a manual "Run workflow") handles this automatically -
no action needed.

## Building locally instead

If you want to compile this extension outside of CI (e.g. directly on a machine with the full
Android SDK), you need a `sdk.jar` built from the exact same pinned commit, or compilation will
either fail or - worse - silently succeed against the wrong API and crash on install, the way it
did before this was pinned. The most reliable way to get one:

1. Trigger the "Extension Compile Test" workflow on GitHub (push, or manually from the Actions
   tab).
2. Once it finishes, download the `pinned-sdk-jar` artifact from that run.
3. Extract it and place the `sdk.jar` file in this folder (`app/libs/sdk.jar`).

Alternatively, build it yourself directly from Xed-Editor's source at the pinned commit:

```bash
git clone --recurse-submodules https://github.com/Xed-Editor/Xed-Editor.git
cd Xed-Editor
git checkout 73835433
./gradlew :core:main:assembleDebug
cd plugin-sdk
bash build-auto.sh
# sdk.jar is now at plugin-sdk/output/sdk.jar - copy it here.
```

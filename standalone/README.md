<!--
     Copyright (C) 2026 The 2by2 Project
     SPDX-License-Identifier: Apache-2.0
-->

# Standalone APK (for quick tests)

The real build is Soong (`m Mozc`). This directory builds the same client as a
debug-signed APK **without an AOSP tree**, for trying a change quickly on a
device. It is not meant to be flashed or shipped.

## Getting the APK

GitHub → Actions → **Standalone APK** → *Run workflow* (choose the branch), or
just push. When it finishes, download the `Mozc-standalone-apk` artifact and

```sh
adb install -r Mozc-standalone-<sha>.apk
```

Then enable **Mozc (standalone)** in Settings → System → Keyboard and select it.

* The application id is `org.mozc.android.inputmethod.japanese.dev`, so it
  installs **next to the system Mozc** and does not touch it. Uninstall it when
  done.
* It is signed with the fixed test key `debug.keystore`, so a newer build can be
  installed over an older one.
* Its user data (`.mozc`) is separate from the system Mozc's.

The workflow file must be on the default branch before *Run workflow* shows up
in the Actions tab; pushes to any branch trigger it regardless.

## How it works

| Step | What |
|---|---|
| `native` job | Checks out the upstream Mozc tag in `native/VERSION`, builds `libmozc.so` (all ABIs, `package` target) and `mozc.data` (`//data_manager/oss:mozc_dataset_for_oss`) with Bazel. Cached on the upstream version and `mozcjni.cc`, so only the first run (or an upstream update) is slow. |
| `prepare.sh` | Does what `Android.bp` does: runs `tools/client/*.py` (keyboard drawables, palette tables), compiles the protos (java lite), copies `src/` and `res/` with the package-name adjustments below, and stages the native outputs. |
| Gradle (`app/`) | Compiles and packages the staged files. |

Differences from the Soong build, all done by `prepare.sh`:

* `org.mozc.android.inputmethod.japanese.resources.R` → the app's own `R`
  (Soong generates both with the same ids).
* The preference screens' `targetPackage`, the content provider authority and
  the label are changed for the `.dev` application id.
* `mozc.data` is bundled as an asset and extracted to `files/` on first use
  (`LocalSessionHandler`); the system build has no asset and keeps using
  `/product/usr/share/mozc/mozc.data`.

## Local build

Needs JDK 17, Android SDK (platform 36), Gradle 8.11, `protoc` 3.21.12 and the two
native outputs.

```sh
NATIVE_LIBS_ZIP=/path/to/native_libs.zip MOZC_DATA=/path/to/mozc.data standalone/prepare.sh
gradle -p standalone assembleDebug
```

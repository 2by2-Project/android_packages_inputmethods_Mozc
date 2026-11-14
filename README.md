<!--
     Copyright (C) 2026 The 2by2 Project
     SPDX-License-Identifier: Apache-2.0
-->

# Mozc for Android

Japanese input method based on [Mozc](https://github.com/google/mozc),
built entirely by Soong (`m Mozc`, or as part of `m bacon`).

## Layout

| Path | Contents |
|---|---|
| `src/`, `res/` | Android client, from the last upstream Android client ([afb03dd](https://github.com/google/mozc/tree/afb03ddfe72dde4cf2409863a3bfea160f7a66d8/src/android)), ported to current Android |
| `client_data/` | Client-only data of the same revision: keyboard SVG images and the symbol palette tables |
| `tools/client/` | Python 3 ports of the upstream generators (SVG → `.pic`, palette tables), run at build time |
| `native/` | **Generated.** Upstream Mozc engine sources, OSS dictionary data and `Android.bp` |
| `tools/` | Import tooling (`update_mozc.py`, `bazel2bp.py`) |

The build produces

* `libmozc.so` — the conversion engine (upstream `//android/jni:mozc`),
* `mozc.data` — the OSS dictionary data set (upstream
  `//data_manager/oss:mozc_dataset_for_oss`), generated from `native/data`
  by host tools and installed to `/product/usr/share/mozc/mozc.data`,
* `Mozc.apk` — the client.

## Updating the engine

`native/` must not be edited by hand. To follow an upstream release:

```sh
tools/update_mozc.py --tag latest      # or e.g. --tag 3.35.6354
```

This checks out google/mozc, fetches the external data pinned in its
`MODULE.bazel` (usage dictionary, zip codes; sha256-verified), and
`tools/bazel2bp.py` converts the Bazel targets needed by the two outputs
above into `native/Android.bp`, copying only the files they use.
Local adjustments to the generated build live in `tools/native_prologue.bp`
and `tools/shims/`.

`.github/workflows/update-mozc.yml` runs the same script weekly and opens a
pull request when a new upstream release is available.

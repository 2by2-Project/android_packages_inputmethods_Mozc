#!/usr/bin/env bash
#
# Copyright (C) 2026 The 2by2 Project
#
# SPDX-License-Identifier: Apache-2.0
#
# Stages everything the standalone Gradle project (standalone/app) needs, the
# same way Android.bp does for the Soong build:
#
#   generated/java      src/ + generated protos + palette tables
#   generated/res       res/ + generated keyboard drawables (res/raw/*.pic)
#   generated/assets    mozc.data
#   generated/jniLibs   libmozc.so per ABI
#   generated/AndroidManifest.xml
#
# Environment:
#   PROTOC           protoc binary (3.21.12, to match protobuf-javalite)  [protoc]
#   APP_ID           application id of the standalone build
#                    [org.mozc.android.inputmethod.japanese.dev]
#   NATIVE_LIBS_ZIP  native_libs.zip of the upstream //android/jni:native_libs
#   MOZC_DATA        mozc.data of //data_manager/oss:mozc_dataset_for_oss
#   SKIP_NATIVE=1    do not require NATIVE_LIBS_ZIP / MOZC_DATA (compile checks only)

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${OUT:-$REPO/standalone/generated}"
PROTOC="${PROTOC:-protoc}"
APP_ID="${APP_ID:-org.mozc.android.inputmethod.japanese.dev}"
PY=(python3 -I)

case "$OUT" in
  */generated) ;;
  *) echo "refusing to use OUT=$OUT (must end with /generated)" >&2; exit 1 ;;
esac

if [[ "${SKIP_NATIVE:-0}" != 1 ]]; then
  : "${NATIVE_LIBS_ZIP:?set NATIVE_LIBS_ZIP (or SKIP_NATIVE=1)}"
  : "${MOZC_DATA:?set MOZC_DATA (or SKIP_NATIVE=1)}"
  [[ -f "$NATIVE_LIBS_ZIP" ]] || { echo "no such file: $NATIVE_LIBS_ZIP" >&2; exit 1; }
  [[ -f "$MOZC_DATA" ]] || { echo "no such file: $MOZC_DATA" >&2; exit 1; }
fi
command -v "$PROTOC" >/dev/null || { echo "protoc not found: $PROTOC" >&2; exit 1; }

# Only ever delete what this script generated.
if [[ -d "$OUT" ]]; then
  for d in java res assets jniLibs tmp; do rm -rf "${OUT:?}/$d"; done
  rm -f "$OUT/AndroidManifest.xml"
fi
mkdir -p "$OUT/java" "$OUT/res" "$OUT/assets" "$OUT/jniLibs" "$OUT/tmp"

echo "$APP_ID" > "$OUT/app_id.txt"

echo "== Java sources"
cp -R "$REPO/src/." "$OUT/java/"
# Soong generates the resource class a second time in upstream's separate
# "resources" package. Gradle only generates the app's own R class, which has
# the same ids, so point the imports at it.
grep -rl "org.mozc.android.inputmethod.japanese.resources.R;" "$OUT/java" \
  | xargs sed -i 's/org\.mozc\.android\.inputmethod\.japanese\.resources\.R;/org.mozc.android.inputmethod.japanese.R;/'

echo "== Palette tables"
GEN="$REPO/tools/client"
CD="$REPO/client_data"
"${PY[@]}" "$GEN/gen_emoji_data.py" \
  --emoji_data "$CD/emoji_data.tsv" \
  --output "$OUT/java/org/mozc/android/inputmethod/japanese/emoji/EmojiData.java"
"${PY[@]}" "$GEN/gen_emoticon_data.py" \
  --input "$CD/emoticon_categorized.tsv" \
  --output "$OUT/java/org/mozc/android/inputmethod/japanese/EmoticonData.java" \
  --class_name EmoticonData --value_column 0 --category_column 1
"${PY[@]}" "$GEN/gen_emoticon_data.py" \
  --input "$CD/symbol_categorized.tsv" \
  --output "$OUT/java/org/mozc/android/inputmethod/japanese/SymbolData.java" \
  --class_name SymbolData --value_column 0 --category_column 1

echo "== Protocol buffers (java lite)"
PROTO_DIR="$OUT/tmp/proto"
mkdir -p "$PROTO_DIR/protocol"
for p in candidate_window commands config engine_builder user_dictionary_storage; do
  cp "$REPO/native/protocol/$p.proto" "$PROTO_DIR/protocol/"
done
cp "$REPO"/client_proto/*.proto "$PROTO_DIR/"
(cd "$PROTO_DIR" && "$PROTOC" --java_out=lite:"$OUT/java" -I . protocol/*.proto ./*.proto)

echo "== Resources"
cp -R "$REPO/res/." "$OUT/res/"
# preference screens start activities of this app by package name.
sed -i "s/android:targetPackage=\"org\.mozc\.android\.inputmethod\.japanese\"/android:targetPackage=\"$APP_ID\"/" \
  "$OUT"/res/xml/*.xml

echo "== Keyboard drawables"
TSVG="$OUT/tmp/transformed_svg.zip"
"${PY[@]}" "$GEN/transform_svg.py" --output_zip "$TSVG" "$CD"/images/template/*.svg
mkdir -p "$OUT/res/raw"
"${PY[@]}" "$GEN/gen_mozc_drawable.py" \
  --output_dir="$OUT/res/raw" --build_log="$OUT/tmp/gen_mozc_drawable.log" \
  "$CD"/images/svg/*.svg "$TSVG"

echo "== Manifest"
"${PY[@]}" - "$REPO/AndroidManifest.xml" "$OUT/AndroidManifest.xml" "$APP_ID" <<'EOF'
import re
import sys

src, dst, app_id = sys.argv[1:4]
text = open(src, encoding='utf-8').read()
# The namespace is given by Gradle.
text, n = re.subn(r'\n\s*package="[^"]*"', '', text, count=1)
assert n == 1, 'package attribute not found'
# The standalone build coexists with the system Mozc, so the content provider
# authority must differ and the IME should be distinguishable in the picker.
text, n = re.subn(
    r'android:authorities="[^"]*"', 'android:authorities="%s.UserDictionaryExportProvider"' % app_id, text)
assert n == 1, 'provider authority not found'
text = text.replace('@string/app_full_name', 'Mozc (standalone)')
# Set by Gradle (packaging.jniLibs.useLegacyPackaging).
text, n = re.subn(r'\n\s*android:extractNativeLibs="[^"]*"', '', text, count=1)
assert n == 1, 'extractNativeLibs attribute not found'
open(dst, 'w', encoding='utf-8').write(text)
EOF

echo "== Native"
if [[ "${SKIP_NATIVE:-0}" != 1 ]]; then
  cp "$MOZC_DATA" "$OUT/assets/mozc.data"
  "${PY[@]}" - "$NATIVE_LIBS_ZIP" "$OUT/jniLibs" <<'EOF'
import sys
import zipfile

src, dst = sys.argv[1:3]
count = 0
with zipfile.ZipFile(src) as z:
  for name in z.namelist():
    parts = name.split('/')
    # libs/<abi>/libmozc.so
    if len(parts) == 3 and parts[0] == 'libs' and parts[2] == 'libmozc.so':
      z.extract(name, dst + '/__tmp')
      count += 1
assert count > 0, 'no libmozc.so in the archive'
import os
tmp = dst + '/__tmp/libs'
for abi in os.listdir(tmp):
  os.replace(os.path.join(tmp, abi), os.path.join(dst, abi))
os.rmdir(tmp)
os.rmdir(dst + '/__tmp')
print('ABIs:', ' '.join(sorted(d for d in os.listdir(dst))))
EOF
else
  echo "skipped (SKIP_NATIVE=1)"
fi

echo "Done: $OUT"

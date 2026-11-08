#!/usr/bin/env bash
# Fetch and extract GeckoView (arm64-v8a) + its dependencies into .geckoview/
# Idempotent; used by CI and local Linux builds. Windows: tools/fetch-geckoview.ps1
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GV="$ROOT/.geckoview"
AARS="$GV/aars"
LIBS="$GV/libs"
NAT="$GV/native"
GVVER="159.0.20261102144711"

GOOGLE="https://dl.google.com/dl/android/maven2"
CENTRAL="https://repo1.maven.org/maven2"
MOZILLA="https://maven.mozilla.org/maven2"

mkdir -p "$AARS" "$LIBS" "$NAT"

# path artifact version repo isJar
ITEMS="
org/mozilla/geckoview/geckoview-arm64-v8a geckoview-arm64-v8a $GVVER $MOZILLA 0
androidx/annotation/annotation annotation 1.11.0 $GOOGLE 1
androidx/annotation/annotation-experimental annotation-experimental 1.6.0 $GOOGLE 0
androidx/collection/collection collection 1.6.0 $GOOGLE 1
androidx/core/core core 1.19.1 $GOOGLE 0
androidx/lifecycle/lifecycle-common lifecycle-common 2.11.0 $GOOGLE 1
androidx/lifecycle/lifecycle-process lifecycle-process 2.11.0 $GOOGLE 0
com/google/android/gms/play-services-fido play-services-fido 21.3.1 $GOOGLE 0
org/yaml/snakeyaml snakeyaml 2.2 $CENTRAL 1
androidx/media3/media3-common media3-common 1.11.1 $GOOGLE 0
androidx/media3/media3-datasource media3-datasource 1.11.1 $GOOGLE 0
androidx/media3/media3-decoder media3-decoder 1.11.1 $GOOGLE 0
androidx/media3/media3-exoplayer media3-exoplayer 1.11.1 $GOOGLE 0
androidx/media3/media3-exoplayer-hls media3-exoplayer-hls 1.11.1 $GOOGLE 0
org/jetbrains/kotlin/kotlin-stdlib kotlin-stdlib 2.4.20 $CENTRAL 1
"

fetch() { # url dest
  if [ -s "$2" ] && head -c2 "$2" | grep -q 'PK'; then return 0; fi
  rm -f "$2"
  curl -sSL --max-time 900 -o "$2" "$1" || true
  if [ -s "$2" ] && head -c2 "$2" | grep -q 'PK'; then
    echo "  got $(basename "$2") $(du -h "$2" | cut -f1)"
    return 0
  fi
  rm -f "$2"
  return 1
}

echo "$ITEMS" | while read -r path art ver repo isjar; do
  [ -n "${path:-}" ] || continue
  base="$repo/$path/$ver/$art-$ver"
  if [ "$isjar" = "1" ]; then
    fetch "$base.jar" "$AARS/$art-$ver.jar" || { echo "failed: $art jar" >&2; exit 1; }
  else
    if ! fetch "$base.aar" "$AARS/$art-$ver.aar"; then
      echo "  aar missing, try jar: $art"
      fetch "$base.jar" "$AARS/$art-$ver.jar" || { echo "failed: $art" >&2; exit 1; }
    fi
  fi
done

echo "==> extract"
rm -f "$LIBS"/*.jar "$NAT"/*.so 2>/dev/null || true
for f in "$AARS"/*; do
  case "$f" in
    *.jar) cp -f "$f" "$LIBS/$(basename "$f")" ;;
    *.aar)
      work="$(mktemp -d)"
      unzip -q -o "$f" -d "$work"
      if [ -f "$work/classes.jar" ]; then
        cp -f "$work/classes.jar" "$LIBS/$(basename "$f" .aar)-classes.jar"
      fi
      case "$(basename "$f")" in
        geckoview-*)
          cp -f "$work"/jni/arm64-v8a/*.so "$NAT"/
          [ -f "$work/assets/omni.ja" ] && cp -f "$work/assets/omni.ja" "$GV/omni.ja"
          [ -f "$work/AndroidManifest.xml" ] && cp -f "$work/AndroidManifest.xml" "$GV/geckoview-AndroidManifest.xml"
          ;;
      esac
      rm -rf "$work"
      ;;
  esac
done

echo "libs:   $(find "$LIBS" -name '*.jar' | wc -l) jars, $(du -sh "$LIBS" | cut -f1)"
echo "native: $(find "$NAT" -name '*.so' | wc -l) so, $(du -sh "$NAT" | cut -f1)"
echo "omni:   $(du -h "$GV/omni.ja" | cut -f1)"

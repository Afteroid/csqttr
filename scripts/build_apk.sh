#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SERVER_PROVENANCE_SCRIPT="$ROOT/scripts/server_asset_provenance.ps1"
NATIVE_PROVENANCE_SCRIPT="$ROOT/scripts/native_client_provenance.ps1"

echo "=== CSQTT APK Build Script ==="
echo "=== Output: 4 APKs (universal, arm64-v8a, armeabi-v7a, x86_64) ==="
echo ""

if command -v pwsh >/dev/null; then
  pwsh -NoProfile -File "$SERVER_PROVENANCE_SCRIPT" -Mode Verify
  pwsh -NoProfile -File "$NATIVE_PROVENANCE_SCRIPT" -Mode Verify
fi

echo "Building release APKs with Gradle..."
chmod +x ./gradlew
./gradlew :app:assembleRelease --no-daemon

APK_DIR="$ROOT/app/build/outputs/apk/release"
RELEASE_DIR="$ROOT/app/release"
mkdir -p "$RELEASE_DIR"

echo "Copying APKs to release directory..."
if [[ -f "$APK_DIR/app-universal-release.apk" ]]; then
  cp -f "$APK_DIR/app-universal-release.apk" "$RELEASE_DIR/CSQTT-universal.apk"
  echo "  [OK] CSQTT-universal.apk"
fi

if [[ -f "$APK_DIR/app-arm64-v8a-release.apk" ]]; then
  cp -f "$APK_DIR/app-arm64-v8a-release.apk" "$RELEASE_DIR/CSQTT-arm64-v8a.apk"
  echo "  [OK] CSQTT-arm64-v8a.apk"
fi

if [[ -f "$APK_DIR/app-armeabi-v7a-release.apk" ]]; then
  cp -f "$APK_DIR/app-armeabi-v7a-release.apk" "$RELEASE_DIR/CSQTT-armeabi-v7a.apk"
  echo "  [OK] CSQTT-armeabi-v7a.apk"
fi

if [[ -f "$APK_DIR/app-x86_64-release.apk" ]]; then
  cp -f "$APK_DIR/app-x86_64-release.apk" "$RELEASE_DIR/CSQTT-x86_64.apk"
  echo "  [OK] CSQTT-x86_64.apk"
fi

echo "Build completed successfully."

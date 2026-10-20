#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$ROOT/.." && pwd)"
JNILIBS="$PROJECT_ROOT/app/src/main/jniLibs"

ARM64_TARGET_DIR="$PROJECT_ROOT/build/rust-client-android-arm64"
ARMV7_TARGET_DIR="$PROJECT_ROOT/build/rust-client-android-armv7"
X86_64_TARGET_DIR="$PROJECT_ROOT/build/rust-client-android-x86_64"

RUN_TESTS=0
while (($#)); do
  case "$1" in
    --tests) RUN_TESTS=1 ;;
    --no-tests) RUN_TESTS=0 ;;
    *) echo "Usage: $0 [--tests|--no-tests]" >&2; exit 2 ;;
  esac
  shift
done

cd "$ROOT"

command -v cargo >/dev/null || { echo "Error: cargo not found" >&2; exit 1; }
cargo ndk --version >/dev/null || { echo "Error: cargo-ndk not found. Install it with: cargo install cargo-ndk" >&2; exit 1; }

rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android

if [[ "$RUN_TESTS" == 1 ]]; then
  if command -v pwsh >/dev/null; then
    pwsh -NoProfile -File "$ROOT/check_no_runtime_panics.ps1"
  fi
  cargo fmt --all -- --check
  cargo clippy --all-targets -- -D warnings
  cargo test --quiet
fi

mkdir -p "$JNILIBS/arm64-v8a" "$JNILIBS/armeabi-v7a" "$JNILIBS/x86_64"
rm -f "$JNILIBS/x86/libclient.so"

echo "Building arm64-v8a..."
cargo ndk -t arm64-v8a -P 26 build --release --target-dir "$ARM64_TARGET_DIR"
cp "$ARM64_TARGET_DIR/aarch64-linux-android/release/client" "$JNILIBS/arm64-v8a/libclient.so"

echo "Building armeabi-v7a..."
cargo ndk -t armeabi-v7a -P 26 build --release --target-dir "$ARMV7_TARGET_DIR"
cp "$ARMV7_TARGET_DIR/armv7-linux-androideabi/release/client" "$JNILIBS/armeabi-v7a/libclient.so"

echo "Building x86_64..."
cargo ndk -t x86_64 -P 26 build --release --target-dir "$X86_64_TARGET_DIR"
cp "$X86_64_TARGET_DIR/x86_64-linux-android/release/client" "$JNILIBS/x86_64/libclient.so"

if command -v pwsh >/dev/null; then
  pwsh -NoProfile -File "$PROJECT_ROOT/scripts/native_client_provenance.ps1" -Mode Write
  pwsh -NoProfile -File "$PROJECT_ROOT/scripts/native_client_provenance.ps1" -Mode Verify
fi

echo "Rust client build completed successfully."

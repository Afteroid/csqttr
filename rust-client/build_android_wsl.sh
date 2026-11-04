#!/usr/bin/env bash
# Android executables including the vendored Gooby engine. No APK installation.
set -euo pipefail
project_root=$(cd "$(dirname "$0")/.." && pwd)
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to a Linux Android NDK}"
export CARGO_BUILD_JOBS=${CARGO_BUILD_JOBS:-4}
cd "$project_root/rust-client"
abis=("${@}")
if ((${#abis[@]} == 0)); then abis=(arm64-v8a x86_64); fi
for abi in "${abis[@]}"; do
    case "$abi" in
        arm64-v8a) suffix=arm64; target=aarch64-linux-android ;;
        armeabi-v7a) suffix=armv7; target=armv7-linux-androideabi ;;
        x86_64) suffix=x86_64; target=x86_64-linux-android ;;
        *) echo "Unsupported ABI: $abi" >&2; exit 2 ;;
    esac
    output="$project_root/build/rust-client-android-$suffix"
    cargo ndk -t "$abi" -P 26 --link-builtins build --release --locked --target-dir "$output"
    mkdir -p "$project_root/app/src/main/jniLibs/$abi"
    cp "$output/$target/release/client" "$project_root/app/src/main/jniLibs/$abi/libclient.so"
done

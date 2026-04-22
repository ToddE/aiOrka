#!/usr/bin/env bash
# Build the native aiOrka shared library and copy it into the Python package.
# Run from the repo root:   bash bindings/python/scripts/build_native.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
PKG_DIR="$REPO_ROOT/bindings/python/aiorka"
SHARED_BUILD="$REPO_ROOT/shared/build/bin"

detect_platform() {
    case "$(uname -s)-$(uname -m)" in
        Linux-x86_64)  echo "linuxX64" ;;
        Darwin-arm64)  echo "macosArm64" ;;
        Darwin-x86_64) echo "macosX64" ;;
        MINGW*|MSYS*)  echo "mingwX64" ;;
        *) echo "unsupported" ;;
    esac
}

TARGET="$(detect_platform)"
if [[ "$TARGET" == "unsupported" ]]; then
    echo "Unsupported platform: $(uname -s)-$(uname -m)" >&2
    exit 1
fi

echo "Building native library for $TARGET..."
cd "$REPO_ROOT"
./gradlew ":shared:link${1:-Release}Shared${TARGET^}" --quiet

case "$TARGET" in
    linuxX64)   LIB_SRC="$SHARED_BUILD/linuxX64/${1:-release}Shared/libaiorka.so" ;;
    macosArm64) LIB_SRC="$SHARED_BUILD/macosArm64/${1:-release}Shared/libaiorka.dylib" ;;
    macosX64)   LIB_SRC="$SHARED_BUILD/macosX64/${1:-release}Shared/libaiorka.dylib" ;;
    mingwX64)   LIB_SRC="$SHARED_BUILD/mingwX64/${1:-release}Shared/aiorka.dll" ;;
esac

echo "Copying $LIB_SRC → $PKG_DIR/"
cp "$LIB_SRC" "$PKG_DIR/"
echo "Done. Library is ready for 'pip install bindings/python/' or 'python -m build'"

#!/usr/bin/env bash
# Build the aiOrka native shared libraries for all supported platforms and
# copy them into each language binding directory.
#
# Usage (from repo root):
#   bash scripts/build_bindings.sh [--release|--debug]
#
# By default builds in release mode.  Pass --debug for a faster debug build.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:---release}"
GRADLE_VARIANT="Release"
KOTLIN_VARIANT="release"

if [[ "$MODE" == "--debug" ]]; then
    GRADLE_VARIANT="Debug"
    KOTLIN_VARIANT="debug"
fi

# ── Platform detection ────────────────────────────────────────────────────────

detect_targets() {
    local os arch
    os="$(uname -s)"
    arch="$(uname -m)"
    case "$os-$arch" in
        Linux-x86_64)  echo "linuxX64" ;;
        Darwin-arm64)  echo "macosArm64" ;;
        Darwin-x86_64) echo "macosX64" ;;
        MINGW*|MSYS*)  echo "mingwX64" ;;
        *)
            echo "ERROR: Unsupported platform $os-$arch" >&2
            exit 1
            ;;
    esac
}

lib_filename() {
    local target="$1"
    case "$target" in
        linuxX64)   echo "libaiorka.so" ;;
        macosArm64|macosX64) echo "libaiorka.dylib" ;;
        mingwX64)   echo "aiorka.dll" ;;
    esac
}

# ── Build ─────────────────────────────────────────────────────────────────────

TARGET="$(detect_targets)"
LIB_FILE="$(lib_filename "$TARGET")"

echo "==> aiOrka native build"
echo "    platform : $TARGET"
echo "    variant  : $KOTLIN_VARIANT"
echo "    library  : $LIB_FILE"
echo ""

cd "$REPO_ROOT"

GRADLE_TASK=":shared:link${GRADLE_VARIANT}Shared${TARGET^}"
echo "--> Running Gradle task: $GRADLE_TASK"
./gradlew "$GRADLE_TASK"

LIB_SRC="$REPO_ROOT/shared/build/bin/$TARGET/${KOTLIN_VARIANT}Shared/$LIB_FILE"

if [[ ! -f "$LIB_SRC" ]]; then
    echo "ERROR: expected output not found: $LIB_SRC" >&2
    exit 1
fi

# ── Copy to binding directories ───────────────────────────────────────────────

copy_to() {
    local dest_dir="$1"
    mkdir -p "$dest_dir"
    cp "$LIB_SRC" "$dest_dir/$LIB_FILE"
    echo "    copied → $dest_dir/$LIB_FILE"
}

echo ""
echo "--> Distributing $LIB_FILE to language bindings:"

# Python — bundled inside the package directory so pip install works out of the box
copy_to "$REPO_ROOT/bindings/python/aiorka"

# Go — place next to go.mod; consumers set CGO_LDFLAGS or copy to a system path
copy_to "$REPO_ROOT/bindings/go"

# Rust — place next to Cargo.toml; AIORKA_LIB_DIR in build.rs points here
copy_to "$REPO_ROOT/bindings/rust"

echo ""
echo "==> Build complete."
echo ""
echo "Next steps:"
echo "  Python : cd bindings/python && pip install -e ."
echo "  Go     : cd bindings/go && CGO_LDFLAGS=\"-L\$(pwd) -Wl,-rpath,\$(pwd)\" go build ./..."
echo "  Rust   : cd bindings/rust && AIORKA_LIB_DIR=\$(pwd) cargo build"

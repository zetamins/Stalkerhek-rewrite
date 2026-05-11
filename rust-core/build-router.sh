#!/usr/bin/env bash
set -euo pipefail

# Cross-compile Stalkerhek for OpenWrt routers
# Usage: ./build-router.sh [target]
#   target: aarch64 | armv7 | armv5 | mipsel | mips | x86_64
#
# Requirements:
#   rustup target add <triple>
#   - or - let this script install targets automatically
#
# The musl linker is auto-downloaded from musl.cc on first run.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

TARGET="${1:-aarch64}"
RELEASE_DIR="target/release"
OUTPUT_DIR="router/build/$TARGET"

# Map friendly names to Rust target triples and musl.cc toolchain names
MUSL_CC=""
case "$TARGET" in
  aarch64)  RUST_TARGET="aarch64-unknown-linux-musl";     MUSL_CC="aarch64-linux-musl"        ;;
  armv7)    RUST_TARGET="armv7-unknown-linux-musleabihf"; MUSL_CC="armv7l-linux-musleabihf"    ;;
  armv7hf)  RUST_TARGET="armv7-unknown-linux-musleabihf"; MUSL_CC="armv7l-linux-musleabihf"    ;;
  armv5)    RUST_TARGET="armv5te-unknown-linux-musleabi"; MUSL_CC="armv5l-linux-musleabi"      ;;
  mipsel)   RUST_TARGET="mipsel-unknown-linux-musl";      MUSL_CC="mipsel-linux-musl"          ;;
  mips)     RUST_TARGET="mips-unknown-linux-musl";        MUSL_CC="mips-linux-musl"            ;;
  x86_64)   RUST_TARGET="x86_64-unknown-linux-musl";      MUSL_CC="x86_64-linux-musl"          ;;
  i686)     RUST_TARGET="i686-unknown-linux-musl";        MUSL_CC=""                           ;;
  *)
    echo "Unknown target: $TARGET"
    echo "Usage: $0 [aarch64|armv7|armv7hf|armv5|mipsel|mips|x86_64|i686]"
    exit 1
    ;;
esac

echo "=== Building for $TARGET ($RUST_TARGET) ==="

# Install Rust target if missing
if ! rustup target list --installed | grep -q "$RUST_TARGET"; then
  echo "Installing Rust target: $RUST_TARGET"
  rustup target add "$RUST_TARGET"
fi

# Set up musl linker (musl.cc uses its own naming like armv7l-linux-musleabihf-gcc)
TOOLCHAIN_DIR="$SCRIPT_DIR/router/toolchain/$TARGET"
if [ -n "$MUSL_CC" ]; then
  LINKER="$TOOLCHAIN_DIR/bin/${MUSL_CC}-gcc"
else
  LINKER="$TOOLCHAIN_DIR/bin/$RUST_TARGET-gcc"
fi

if [ ! -f "$LINKER" ]; then
  if [ -z "$MUSL_CC" ]; then
    echo "No musl.cc toolchain for $TARGET — install it manually or use a system compiler."
    exit 1
  fi
  echo "Downloading musl toolchain for $TARGET..."
  mkdir -p "$TOOLCHAIN_DIR"
  TOOLCHAIN_URL="https://musl.cc/${MUSL_CC}-cross.tgz"
  echo "  from: $TOOLCHAIN_URL"
  wget -q --tries=3 --retry-connrefused --timeout=60 -O - "$TOOLCHAIN_URL" | tar xz -C "$TOOLCHAIN_DIR" --strip-components=1
  echo "Toolchain extracted to $TOOLCHAIN_DIR"
fi

# cc-rs uses CC_<lowercase_target> (underscores, lowercase)
export CC_${RUST_TARGET//-/_}="$LINKER"
# Cargo uses CARGO_TARGET_<UPPERCASE_TARGET>_LINKER
UPPER_TARGET="${RUST_TARGET//-/_}"
UPPER_TARGET="${UPPER_TARGET^^}"
export "CARGO_TARGET_${UPPER_TARGET}_LINKER"="$LINKER"

echo "Building (this may take a while)..."
cargo build --release --target "$RUST_TARGET"

# Strip and prepare output
mkdir -p "$OUTPUT_DIR"
BINARY="target/$RUST_TARGET/release/stalkerhek-engine"
if [ -f "$BINARY" ]; then
  # Strip with the toolchain's strip
  STRIP="$TOOLCHAIN_DIR/bin/$RUST_TARGET-strip"
  if [ -f "$STRIP" ]; then
    "$STRIP" "$BINARY"
  fi
  cp "$BINARY" "$OUTPUT_DIR/stalkerhek"
  echo ""
  echo "=== Build complete ==="
  echo "Binary: $OUTPUT_DIR/stalkerhek"
  ls -lh "$OUTPUT_DIR/stalkerhek"
  echo ""
  echo "To deploy to a USB drive:"
  echo "  ./deploy-router.sh $TARGET /path/to/usb"
else
  echo "ERROR: Binary not found at $BINARY"
  exit 1
fi

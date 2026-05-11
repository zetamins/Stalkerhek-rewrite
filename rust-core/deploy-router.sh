#!/usr/bin/env bash
set -euo pipefail

# Deploy Stalkerhek binary and scripts to a USB drive for OpenWrt.
# Usage: ./deploy-router.sh [target] [usb-path]
#   target: aarch64 | armv7 | mipsel | x86_64  (default: aarch64)
#   usb-path: mount point of the USB drive (default: /mnt/usb)

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

TARGET="${1:-aarch64}"
USB_PATH="${2:-/mnt/usb}"

BUILD_DIR="router/build/$TARGET"
DEPLOY_DIR="$USB_PATH/stalkerhek"

if [ ! -d "$BUILD_DIR" ]; then
  echo "Build not found for $TARGET. Run: ./build-router.sh $TARGET"
  exit 1
fi

if [ ! -d "$USB_PATH" ]; then
  echo "USB path $USB_PATH does not exist. Mount the USB drive first."
  echo "  mount /dev/sda1 $USB_PATH   # adjust device"
  exit 1
fi

echo "=== Deploying Stalkerhek to $DEPLOY_DIR ==="
mkdir -p "$DEPLOY_DIR/data"
mkdir -p "$DEPLOY_DIR/etc/init.d"

# Copy binary
cp "$BUILD_DIR/stalkerhek" "$DEPLOY_DIR/"
chmod +x "$DEPLOY_DIR/stalkerhek"

# Copy init script
cp "router/etc/init.d/stalkerhek" "$DEPLOY_DIR/etc/init.d/"
chmod +x "$DEPLOY_DIR/etc/init.d/stalkerhek"

# Copy README
cp "router/README.txt" "$DEPLOY_DIR/" 2>/dev/null || true

echo ""
echo "=== Deployed to $DEPLOY_DIR ==="
echo ""
echo "Contents:"
ls -lhR "$DEPLOY_DIR"
echo ""
echo "On the router, run:"
echo "  # Mount USB (if not auto-mounted)"
echo "  mount /dev/sda1 /mnt/usb"
echo ""
echo "  # Start Stalkerhek manually"
echo "  /mnt/usb/stalkerhek/stalkerhek"
echo ""
echo "  # Or install init script for auto-start"
echo "  cp /mnt/usb/stalkerhek/etc/init.d/stalkerhek /etc/init.d/stalkerhek"
echo "  chmod +x /etc/init.d/stalkerhek"
echo "  /etc/init.d/stalkerhek enable"
echo "  /etc/init.d/stalkerhek start"

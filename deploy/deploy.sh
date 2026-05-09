#!/usr/bin/env bash
set -euo pipefail

# Stalkerhek deployment script
# Run as root: sudo bash deploy/deploy.sh
# Requires: stalkerhek-engine binary and stalkerhek-webui.jar pre-built

INSTALL_DIR="/opt/stalkerhek"
ENGINE_BIN="target/debug/stalkerhek-engine"
WEBUI_JAR="kotlin-app/build/libs/stalkerhek-webui.jar"

if [ ! -f "$ENGINE_BIN" ] || [ ! -f "$WEBUI_JAR" ]; then
    echo "Error: Build artifacts not found."
    echo "Run first:"
    echo "  cd rust-core && cargo build --release"
    echo "  cd kotlin-app && gradle build -x test"
    echo ""
    echo "Then re-run this script from the project root."
    exit 1
fi

# Create user if not exists
if ! id stalkerhek &>/dev/null; then
    useradd --system --no-create-home --shell /usr/sbin/nologin stalkerhek
fi

# Create directories
mkdir -p "$INSTALL_DIR/data"

# Copy binaries
install -m 755 "$ENGINE_BIN" "$INSTALL_DIR/stalkerhek-engine"
install -m 644 "$WEBUI_JAR" "$INSTALL_DIR/stalkerhek-webui.jar"
install -m 644 deploy/stalkerhek-engine.service /etc/systemd/system/
install -m 644 deploy/stalkerhek-webui.service /etc/systemd/system/

# Set ownership
chown -R stalkerhek:stalkerhek "$INSTALL_DIR"
chmod 750 "$INSTALL_DIR/data"

# Create systemd override for the secret (must be set manually)
if [ ! -f /etc/systemd/system/stalkerhek-webui.service.d/override.conf ]; then
    mkdir -p /etc/systemd/system/stalkerhek-webui.service.d
    cat > /etc/systemd/system/stalkerhek-webui.service.d/override.conf << 'EOF'
[Service]
Environment=SESSION_SECRET=change-this-to-a-random-string
EOF
    echo ""
    echo "============================================================"
    echo " IMPORTANT: Set a secure SESSION_SECRET in:"
    echo "   /etc/systemd/system/stalkerhek-webui.service.d/override.conf"
    echo " Then run: systemctl daemon-reload && systemctl restart stalkerhek-webui"
    echo "============================================================"
fi

systemctl daemon-reload
systemctl enable stalkerhek-engine stalkerhek-webui
systemctl start stalkerhek-engine

echo "Waiting for engine to start..."
sleep 3

systemctl start stalkerhek-webui

echo ""
echo "Deployment complete!"
echo "  Engine: systemctl status stalkerhek-engine"
echo "  Web UI: systemctl status stalkerhek-webui"
echo "  Logs:   journalctl -u stalkerhek-engine -f"
echo "          journalctl -u stalkerhek-webui -f"

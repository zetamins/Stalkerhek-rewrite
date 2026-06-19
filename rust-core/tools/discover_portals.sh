#!/bin/bash
# Portal Discovery Layer
# Scans known IP ranges for Stalker portals, finds no-Cloudflare ones, picks best

echo "============================================"
echo " Portal Discovery — Scanning Known IP Ranges"
echo "============================================"

# Known IP ranges that host Stalker portals
SUBNETS=("103.176.90" "185.245.0")

# Step 1: Quick scan — find IPs with /c/ on port 80 (Stalker indicator)
echo ""
echo "[1] Scanning subnets for Stalker portals..."
declare -A FOUND
for subnet in "${SUBNETS[@]}"; do
  for i in $(seq 1 254); do
    timeout 1 bash -c "echo >/dev/tcp/$subnet.$i/80" 2>/dev/null || continue
    # Found open port 80 — test for Stalker
    RESULT=$(timeout 2 curl -s -o /dev/null -w "%{http_code}" "http://$subnet.$i/c/" -H 'User-Agent: MAG254' 2>/dev/null)
    if [ "$RESULT" = "200" ] || [ "$RESULT" = "401" ] || [ "$RESULT" = "302" ]; then
      # Possible Stalker — test handshake
      HAND=$(timeout 3 curl -s -X POST "http://$subnet.$i/c/portal.php?type=stb&action=handshake&JsHttpRequest=1-xml" \
        -H 'User-Agent: MAG254' -H 'Content-Type: application/x-www-form-urlencoded' \
        --data 'mac=A0:BB:3E:02:3C:B6&sn=062014N057468&stb_type=MAG254' -w '%{time_total}' -o /tmp/stalker-$subnet-$i.txt 2>/dev/null)
      if grep -q 'token' /tmp/stalker-$subnet-$i.txt 2>/dev/null; then
        TIME=$(tail -1 /tmp/stalker-$subnet-$i.txt)
        FOUND["$subnet.$i"]=$TIME
        echo "  ✓ $subnet.$i — Stalker OK (${TIME}s)"
      fi
    fi
  done &
done
wait

echo ""
echo "[2] Found ${#FOUND[@]} Stalker IPs"
echo ""

# Step 2: Reverse-IP lookup to find ALL domains on each IP
echo "[3] Finding all domains on each IP..."
BEST_IP=""
BEST_TIME=99
ALL_PORTALS=()

for ip in "${!FOUND[@]}"; do
  TIME=${FOUND[$ip]}
  echo "  $ip (${TIME}s)"

  # Reverse IP lookup
  DOMAINS=$(timeout 5 curl -s "https://api.hackertarget.com/reverseiplookup/?q=$ip" 2>/dev/null | grep -oE '^[a-z0-9.-]+\.(com|net|org|xyz|me|tv|nl|cc|pro|re|top|lol|ch|men|work|online|vip|club|io|co|be|store|live|sbs|space|icu|cz|ru|su|pl|eu)$' | sort -u)

  DOM_COUNT=$(echo "$DOMAINS" | grep -c '.')
  if [ "$DOM_COUNT" -gt 0 ]; then
    echo "    → $DOM_COUNT domains found"

    # Test each domain
    for dom in $DOMAINS; do
      # Check Cloudflare
      CF=$(timeout 2 curl -sI "http://$dom/c/" -H 'User-Agent: MAG254' 2>/dev/null | grep -c 'cloudflare')
      if [ "$CF" -eq 0 ]; then
        DTIME=$(timeout 2 curl -s -o /dev/null -w "%{time_total}" "http://$dom/c/" -H 'User-Agent: MAG254' 2>/dev/null)
        echo "      ✓ $dom (${DTIME}s, no CF)"
        ALL_PORTALS+=("$dom|$DTIME")

        # Track best
        if (( $(echo "$DTIME < $BEST_TIME" | bc -l 2>/dev/null || true) )); then
          BEST_TIME=$DTIME
          BEST_IP="$dom"
        fi
      fi
    done
  fi
done

echo ""
echo "============================================"
echo " RESULTS"
echo "============================================"
echo ""
echo "★ BEST PORTAL: http://$BEST_IP/c/ (${BEST_TIME}s)"
echo ""
echo "All No-Cloudflare portals (${#ALL_PORTALS[@]}):"
IFS=$'\n' SORTED=($(sort -t'|' -k2 -n <<<"${ALL_PORTALS[*]}"))
for entry in "${SORTED[@]}"; do
  dom=${entry%|*}
  time=${entry#*|}
  echo "  http://$dom/c/ (${time}s)"
done

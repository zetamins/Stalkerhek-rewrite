Stalkerhek - IPTV Middleware for OpenWrt
==========================================

DESCRIPTION
  Stalkerhek proxies Stalker IPTV portals and provides:
  - HLS playlist (m3u8) for IPTV players
  - EPG data with timeshift support
  - Web management UI on port 9900

REQUIREMENTS
  - OpenWrt router with USB port
  - USB drive (FAT32/ext4) - minimum 50MB free
  - Portal URL, MAC address from your IPTV provider

QUICK START
  1. Plug the USB drive into your router
  2. SSH into the router and run:

       # Check the USB mount point
       block info | grep sda
       mount /dev/sda1 /mnt/usb

       # Start Stalkerhek manually
       /mnt/usb/stalkerhek/stalkerhek

  3. Open the management UI:
       http://router-ip:9900/dashboard
     (Replace router-ip with your router's IP, e.g. 192.168.1.1)

  4. Create a profile (Portal URL, MAC address, etc.)

  5. Use the HLS playlist URL in any IPTV player:
       http://router-ip:4600/

AUTO-START ON BOOT (optional)
  cp /mnt/usb/stalkerhek/etc/init.d/stalkerhek /etc/init.d/stalkerhek
  chmod +x /etc/init.d/stalkerhek
  /etc/init.d/stalkerhek enable
  /etc/init.d/stalkerhek start

PORTS
  9900 - Management web UI (dashboard, filters)
  4600 - HLS playlist (m3u8) + EPG + channel streams
  4800 - Proxy (playlist with direct URLs)

FILES ON USB
  stalkerhek           - Main binary
  data/profiles.json   - Saved portal profiles
  data/filters.json    - Channel filter settings
  etc/init.d/          - OpenWrt init script

BUILD FROM SOURCE
  On a Linux machine with Rust installed:
    cd rust-core
    ./build-router.sh aarch64    # for ARM64 routers
    ./build-router.sh armv7      # for 32-bit ARM routers
    ./build-router.sh x86_64     # for x86 routers

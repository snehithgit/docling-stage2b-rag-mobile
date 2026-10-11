#!/usr/bin/env bash
# Optional one-time migration from an old, differently signed ShipRAG debug APK.
# Requires Android platform-tools (adb) + USB debugging; phone ROOT NOT required
# because the installed ShipRAG build is debuggable. No data is uploaded.
set -Eeuo pipefail
PKG=com.shiprag.mobile
if [[ $# -ne 1 || ! -f "${1:-}" ]]; then
  echo "Usage: bash scripts/migrate-old-debug-install.sh path/to/new-permanently-signed.apk" >&2
  exit 2
fi
for cmd in adb tar; do command -v "$cmd" >/dev/null || { echo "Missing command: $cmd" >&2; exit 2; }; done
[[ "$(adb get-state 2>/dev/null)" == device ]] || { echo "Connect one unlocked Android phone with USB debugging authorized." >&2; exit 2; }
adb shell pm path "$PKG" | grep -q package: || { echo "ShipRAG is not installed on this phone."; exit 2; }
folder="shiprag-before-signing-migration-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$folder"
archive="$folder/app-data.tar"
echo "Backing up installed ShipRAG app-private files and preferences locally ..."
adb shell am force-stop "$PKG"
adb exec-out run-as "$PKG" sh -c "cd /data/user/0/$PKG && tar -cf - files shared_prefs" > "$archive"
tar -tf "$archive" >/dev/null || { echo "Backup integrity check failed. NOTHING was uninstalled." >&2; exit 1; }
size="$(du -h "$archive" | cut -f1)"
echo "Backup verified: $archive ($size)"
echo "The current install will be removed and replaced, then data restored."
echo "Encrypted cloud API keys may need entering once again because Android KeyStore is cleared by uninstall."
read -r -p "Type MIGRATE to continue (anything else aborts): " confirm
[[ "$confirm" == MIGRATE ]] || { echo "Cancelled; backup retained at $archive"; exit 0; }
adb uninstall "$PKG"
if ! adb install "$1"; then
  echo "New app install failed. Backup retained at $archive; install a compatible APK, then restore." >&2
  exit 1
fi
adb shell am force-stop "$PKG"
if ! adb shell -T "run-as $PKG sh -c 'cd /data/user/0/$PKG && tar -xf -'" < "$archive"; then
  echo "Restore failed. Backup retained at $archive; do not delete it." >&2
  exit 1
fi
echo "Migration completed. Open ShipRAG and check Library and Models."
echo "The backup remains at $archive. Store it securely or delete it after verifying restoration."

#!/usr/bin/env bash
# Build the prebuilt Windows download: dist/MaintenanceManager-Windows.zip
# (jar + web pages + one-click installer). Needs JDK 21, Maven, Node.js, python3.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/dist/windows/MaintenanceManager-Windows"

echo "==> Building API"
(cd "$ROOT/backend" && mvn -q -B -DskipTests package)
echo "==> Building web UI"
(cd "$ROOT/frontend" && npm ci --no-audit --no-fund >/dev/null && npm run build >/dev/null)

echo "==> Assembling"
rm -rf -- "$ROOT/dist/windows"
mkdir -p "$OUT/app" "$OUT/scripts"
cp "$ROOT/backend/target/cmms-api.jar" "$OUT/app/"
cp -r "$ROOT/frontend/dist" "$OUT/app/web"
cp "$ROOT/windows/install.ps1" "$ROOT/windows/db-setup.ps1" "$ROOT/windows/launch.ps1" \
   "$ROOT/windows/stop.ps1" "$ROOT/windows/maintenance.ico" "$OUT/scripts/"
cp "$ROOT/windows/Install Maintenance Manager.bat" "$ROOT/windows/README.txt" "$OUT/"

python3 - "$ROOT/dist/windows" <<'PY'
import os, sys, zipfile
base = sys.argv[1]
zpath = os.path.join(base, "MaintenanceManager-Windows.zip")
with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk(os.path.join(base, "MaintenanceManager-Windows")):
        for f in files:
            full = os.path.join(root, f)
            z.write(full, os.path.relpath(full, base))
print(zpath)
PY

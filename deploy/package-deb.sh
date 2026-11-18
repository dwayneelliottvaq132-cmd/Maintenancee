#!/usr/bin/env bash
# Build dist/maintenance-manager_<version>_all.deb for Ubuntu 24.04+ / Debian 12+.
# Needs JDK 21 + Maven, Node.js + npm, dpkg-deb.   Usage: deploy/package-deb.sh [version]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${1:-1.1.0}"
PKG=maintenance-manager
STAGE="$ROOT/dist/deb/${PKG}_${VERSION}_all"
OUT="$ROOT/dist/${PKG}_${VERSION}_all.deb"

echo "==> Building API"
(cd "$ROOT/backend" && mvn -q -B -DskipTests package)
echo "==> Building web UI"
(cd "$ROOT/frontend" && npm ci --no-audit --no-fund >/dev/null && npm run build >/dev/null)

echo "==> Staging $STAGE"
rm -rf -- "$ROOT/dist/deb"
install -d "$STAGE/DEBIAN" "$STAGE/opt/cmms/backend" "$STAGE/opt/cmms/frontend" "$STAGE/opt/cmms/worker" \
           "$STAGE/lib/systemd/system" "$STAGE/etc/nginx/sites-available" \
           "$STAGE/usr/share/maintenance-manager" "$STAGE/usr/share/doc/$PKG"

install -m 644 "$ROOT/backend/target/cmms-api.jar" "$STAGE/opt/cmms/backend/cmms-api.jar"
cp -r "$ROOT/frontend/dist/." "$STAGE/opt/cmms/frontend/"
cp -r "$ROOT/worker/cmms_worker" "$STAGE/opt/cmms/worker/"
find "$STAGE/opt/cmms/worker" -name '__pycache__' -type d -prune -exec rm -rf {} +
install -m 755 "$ROOT/deploy/backup.sh" "$STAGE/opt/cmms/backup.sh"

# systemd units: same as deploy/systemd, but the worker uses the system Python (python3-psycopg etc.)
for f in "$ROOT"/deploy/systemd/*.service "$ROOT"/deploy/systemd/*.timer; do
  sed 's|/opt/cmms/worker/.venv/bin/python|/usr/bin/python3|' "$f" > "$STAGE/lib/systemd/system/$(basename "$f")"
done
chmod 644 "$STAGE"/lib/systemd/system/*

install -m 644 "$ROOT/deploy/nginx/cmms.conf" "$STAGE/etc/nginx/sites-available/$PKG"
install -m 644 "$ROOT/deploy/cmms.env.example" "$STAGE/usr/share/maintenance-manager/cmms.env.example"
install -m 755 "$ROOT/scripts/seed_demo.py" "$STAGE/usr/share/maintenance-manager/seed_demo.py"
install -m 644 "$ROOT/README.md" "$STAGE/usr/share/doc/$PKG/README.md"
cat > "$STAGE/usr/share/doc/$PKG/copyright" <<'TXT'
Maintenance Manager (CMMS). Bundled third-party libraries keep their own licenses
(Spring Boot, React and MUI: Apache-2.0 / MIT).
TXT

# maintainer scripts and control file
install -m 755 "$ROOT"/deploy/debian/postinst "$ROOT"/deploy/debian/prerm "$ROOT"/deploy/debian/postrm "$STAGE/DEBIAN/"
install -m 644 "$ROOT/deploy/debian/conffiles" "$STAGE/DEBIAN/conffiles"
SIZE=$(du -sk --exclude=DEBIAN "$STAGE" | cut -f1)
sed -e "s/@VERSION@/$VERSION/" -e "s/@SIZE@/$SIZE/" "$ROOT/deploy/debian/control.in" > "$STAGE/DEBIAN/control"
(cd "$STAGE" && find . -type f ! -path './DEBIAN/*' -printf '%P\0' | sort -z | xargs -0 md5sum > DEBIAN/md5sums)

echo "==> Building package"
dpkg-deb --root-owner-group -Zxz --build "$STAGE" "$OUT" >/dev/null
echo "$OUT"

#!/usr/bin/env bash
# Build release artifacts into ./dist (API jar, React static files, worker sources).
# Needs: JDK 21 + Maven, Node.js 18+ + npm.  Run from anywhere.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="$ROOT/dist"

echo "==> Building API (Spring Boot)"
(cd "$ROOT/backend" && mvn -q -B -DskipTests package)

echo "==> Building web UI (React)"
(cd "$ROOT/frontend" && npm ci --no-audit --no-fund && npm run build)

echo "==> Assembling $DIST"
rm -rf -- "$DIST"
mkdir -p "$DIST/backend" "$DIST/frontend" "$DIST/worker"
cp "$ROOT/backend/target/cmms-api.jar" "$DIST/backend/"
cp -r "$ROOT/frontend/dist/." "$DIST/frontend/"
cp -r "$ROOT/worker/cmms_worker" "$ROOT/worker/requirements.txt" "$DIST/worker/"
find "$DIST/worker" -name '__pycache__' -type d -prune -exec rm -rf {} +
echo "==> Done: $DIST"

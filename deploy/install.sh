#!/usr/bin/env bash
# Install or upgrade the CMMS on Ubuntu 22.04 / 24.04.
#   sudo ./deploy/install.sh              # install runtime packages, build, install, start
#   sudo ./deploy/install.sh --no-build   # use an existing ./dist (built elsewhere with deploy/build.sh)
# Safe to re-run for upgrades: existing database, config and data are kept.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD=1
[[ "${1:-}" == "--no-build" ]] && BUILD=0
[[ $EUID -eq 0 ]] || { echo "Run as root (sudo)"; exit 1; }

echo "==> Installing packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q openjdk-21-jre-headless postgresql nginx python3 python3-venv python3-pip curl
if [[ $BUILD -eq 1 ]]; then
  apt-get install -y -q openjdk-21-jdk-headless maven
  if ! command -v node >/dev/null || [[ "$(node -p 'process.versions.node.split(".")[0]')" -lt 18 ]]; then
    apt-get install -y -q nodejs npm
  fi
  echo "==> Building"
  sudo -u "${SUDO_USER:-root}" bash "$ROOT/deploy/build.sh"
fi
[[ -f "$ROOT/dist/backend/cmms-api.jar" ]] || { echo "dist/ missing — run deploy/build.sh first"; exit 1; }

echo "==> System user and directories"
id cmms >/dev/null 2>&1 || useradd --system --home /opt/cmms --shell /usr/sbin/nologin cmms
install -d -o cmms -g cmms -m 750 /var/lib/cmms /var/lib/cmms/uploads /var/lib/cmms/reports /var/backups/cmms
install -d -m 755 /opt/cmms /opt/cmms/backend /opt/cmms/frontend /opt/cmms/worker
install -d -m 750 -g cmms /etc/cmms

echo "==> Configuration"
if [[ ! -f /etc/cmms/cmms.env ]]; then
  rand() { tr -dc 'A-Za-z0-9' </dev/urandom | head -c "$1"; }
  DBPW="$(rand 24)"; ADMINPW="$(rand 12)"
  sed -e "s|^CMMS_DB_PASSWORD=.*|CMMS_DB_PASSWORD=$DBPW|" \
      -e "s|^CMMS_JWT_SECRET=.*|CMMS_JWT_SECRET=$(rand 64)|" \
      -e "s|^CMMS_ADMIN_PASSWORD=.*|CMMS_ADMIN_PASSWORD=$ADMINPW|" \
      -e "s|^CMMS_APP_URL=.*|CMMS_APP_URL=http://$(hostname -f 2>/dev/null || hostname)|" \
      "$ROOT/deploy/cmms.env.example" > /etc/cmms/cmms.env
  FIRST_INSTALL=1
else
  FIRST_INSTALL=0
fi
chown root:cmms /etc/cmms/cmms.env && chmod 640 /etc/cmms/cmms.env
set -a; . /etc/cmms/cmms.env; set +a

echo "==> Database"
systemctl enable --now postgresql
if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='$CMMS_DB_USER'" | grep -q 1; then
  sudo -u postgres psql -q -c "CREATE ROLE \"$CMMS_DB_USER\" LOGIN PASSWORD '$CMMS_DB_PASSWORD'"
fi
DBNAME="${CMMS_DB_URL##*/}"; DBNAME="${DBNAME%%\?*}"
if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='$DBNAME'" | grep -q 1; then
  sudo -u postgres createdb -O "$CMMS_DB_USER" "$DBNAME"
fi

echo "==> Application files"
systemctl stop cmms-api 2>/dev/null || true
install -m 644 "$ROOT/dist/backend/cmms-api.jar" /opt/cmms/backend/cmms-api.jar
rm -rf -- /opt/cmms/frontend/* && cp -r "$ROOT/dist/frontend/." /opt/cmms/frontend/
rm -rf -- /opt/cmms/worker/cmms_worker && cp -r "$ROOT/dist/worker/." /opt/cmms/worker/
[[ -d /opt/cmms/worker/.venv ]] || python3 -m venv /opt/cmms/worker/.venv
/opt/cmms/worker/.venv/bin/pip install -q --upgrade pip
/opt/cmms/worker/.venv/bin/pip install -q -r /opt/cmms/worker/requirements.txt
install -m 755 "$ROOT/deploy/backup.sh" /opt/cmms/backup.sh
chmod -R a+rX /opt/cmms

echo "==> Services"
cp "$ROOT"/deploy/systemd/*.service "$ROOT"/deploy/systemd/*.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now cmms-api
systemctl enable --now cmms-pm.timer cmms-alerts.timer cmms-report.timer cmms-backup.timer

echo "==> nginx"
cp "$ROOT/deploy/nginx/cmms.conf" /etc/nginx/sites-available/cmms
ln -sf /etc/nginx/sites-available/cmms /etc/nginx/sites-enabled/cmms
rm -f /etc/nginx/sites-enabled/default
nginx -t && systemctl reload nginx

echo "==> Waiting for the API"
for _ in $(seq 1 60); do
  curl -fs http://127.0.0.1:8080/api/actuator/health >/dev/null && break
  sleep 2
done
curl -fs http://127.0.0.1:8080/api/actuator/health >/dev/null && echo "API is up" || { echo "API did not start — check: journalctl -u cmms-api -n 100"; exit 1; }

echo
echo "Installed. Open http://$(hostname -I | awk '{print $1}')/"
if [[ $FIRST_INSTALL -eq 1 ]]; then
  echo "First login:  username 'admin'  password '$CMMS_ADMIN_PASSWORD'  (you will be asked to change it)"
fi
echo "Config: /etc/cmms/cmms.env  (set SMTP settings for email alerts, then: systemctl restart cmms-api)"

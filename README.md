# Maintenance Manager (CMMS)

Preventive maintenance scheduling, work orders with step-by-step work instructions, labor / parts / spending
tracking per asset and per employee, maintenance requests with supervisor sign-off, and asset management.

| Part | Tech | Folder |
|---|---|---|
| REST API | Java 21, Spring Boot 3, PostgreSQL, Flyway, JWT | `backend/` |
| Web UI | React 18, TypeScript, Vite, MUI | `frontend/` |
| Background jobs | Python 3 (PM generation, email alerts, monthly PDF/Excel reports) | `worker/` |
| Ubuntu deployment | install script, systemd units + timers, nginx, backups | `deploy/` |

## Install on Ubuntu with the .deb package (recommended for Ubuntu 24.04+)

```bash
sudo apt install ./maintenance-manager_1.1.0_all.deb
```

`apt` installs the dependencies (Java 21, PostgreSQL, nginx and the Python libraries from Ubuntu's own packages).
The package then:
- creates the `cmms` user, the database, and `/etc/cmms/cmms.env` with random secrets
- turns on the nginx site and starts the API, the timers and the backups
- prints the web address and, on a new database, the first `admin` password

Package commands:
- **Upgrade:** `sudo apt install ./maintenance-manager_<new>_all.deb`. Settings and data are kept.
- **Remove the program, keep settings:** `sudo apt remove maintenance-manager`
- **Also remove settings:** `sudo apt purge maintenance-manager`. The database, `/var/lib/cmms` and `/var/backups/cmms`
  are always kept; delete them by hand if you really want everything gone.
- **Re-run setup** (for example after starting PostgreSQL): `sudo dpkg-reconfigure maintenance-manager`
- **Build the package:** `./deploy/package-deb.sh [version]` writes `dist/maintenance-manager_<version>_all.deb`.

Ubuntu 22.04 doesn't ship `python3-psycopg` (version 3), so the package won't install there. Use the script below instead.

## Install on Ubuntu 22.04 / 24.04 (script)

Copy this folder to the server, then:

```bash
cd maintenance-app
sudo ./deploy/install.sh
```

The script installs Java 21, PostgreSQL, nginx, Python, Maven and Node.js. It then builds the app, creates the
database, writes `/etc/cmms/cmms.env` with random secrets and starts everything. When it finishes it prints the URL
and the first `admin` password. You must change that password the first time you log in.

To upgrade, copy the new code over and run the same command again. Your data and config are kept.

To build somewhere else instead, run `./deploy/build.sh` on the build machine, copy the folder including `dist/` to
the server, and run `sudo ./deploy/install.sh --no-build`.

### After installing
- **Email alerts:** set the `CMMS_SMTP_*` and `CMMS_APP_URL` values in `/etc/cmms/cmms.env`. Until SMTP is
  configured, alerts are only written to the journal.
- **HTTPS:** run `sudo apt install certbot python3-certbot-nginx && sudo certbot --nginx -d your.domain`.
- **Demo data (optional, for evaluation only):** `python3 scripts/seed_demo.py http://localhost admin <password>`.

### What runs where
| Service | What it does | Schedule |
|---|---|---|
| `cmms-api` | API on 127.0.0.1:8080, behind nginx on port 80 | always |
| `cmms-pm.timer` | creates work orders for PM schedules that are due | 02:00 and 12:00 |
| `cmms-alerts.timer` | emails about new requests, overdue / due-soon work, low stock and items waiting for sign-off | every 15 min (digest once a day per person) |
| `cmms-report.timer` | builds the monthly spending PDF and Excel files (shown under Reports) | 1st of the month, 03:00 |
| `cmms-backup.timer` | backs up the database and uploaded files to `/var/backups/cmms` (kept 30 days) | 01:30 |

Useful commands: `systemctl status cmms-api`, `journalctl -u cmms-api -f`, `systemctl list-timers 'cmms-*'`.
To run a job now: `sudo systemctl start cmms-pm`.

## Install on a Windows PC (one click, desktop icon)

1. Build the download with `./deploy/package-windows.sh`, or use a copy you were sent. This gives you
   `dist/windows/MaintenanceManager-Windows.zip` (about 57 MB, with the app already built inside).
2. On the PC, right-click the zip, choose **Extract All**, and double-click **Install Maintenance Manager**.

The installer adds Java 21 and PostgreSQL through winget if they are missing. It creates the `cmms` database, and
doesn't need the postgres password: it allows local connections without a password for a moment, then puts the
original settings back. It installs to `%LOCALAPPDATA%\MaintenanceManager`, creates Desktop and Start-menu icons, and
opens the app. Run it again to upgrade; settings and data are kept.

- Add `-StartWithWindows` (run the `.bat` from a command prompt) to start the server automatically at login.
  Use `-Uninstall` to remove the icons and program files.
- Settings are in `%LOCALAPPDATA%\MaintenanceManager\config.ps1` and logs are in the `logs` folder next to it.
- To build from source on Windows instead: `powershell -ExecutionPolicy Bypass -File windows\setup.ps1`.
- The Windows install doesn't run the scheduled Python jobs. Use **Generate due now** on the PM Schedules page.

## Roles
- **Requester:** places maintenance requests and sees their status.
- **Technician:** works the jobs: checks off instruction steps, logs hours, uses parts, marks work complete, records meter readings.
- **Supervisor:** everything a technician can do, plus approve or reject requests, assign work, manage assets, PM schedules, instructions and parts, **sign off** completed work, and view spending reports.
- **Admin:** everything a supervisor can do, plus manage users and hourly rates.

## How the workflow fits together
1. A request is approved and becomes a work order. A work order can also come from a PM schedule, or be created directly.
2. Technicians check off the work-instruction steps. A job can't be completed until every required step is checked.
3. Labor is costed at each person's hourly rate. Parts are taken out of inventory at the current average cost. Contractor or other costs are added by hand.
4. When a supervisor signs off (by typing their name), the work order is locked and the PM schedule's next due date moves forward from the completion date.
5. Spending can be reported by asset, category, employee, month, cost type or work order, and exported to CSV.

## Development
```bash
docker compose -f docker-compose.dev.yml up -d        # or use a local PostgreSQL with dbs cmms + cmms_test
cd backend && mvn spring-boot:run                     # API on :8080, creates admin/admin123
cd frontend && npm install && npm run dev             # UI on :5173 (proxies /api)
cd worker && python3 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt
```
Tests: `mvn test` (needs the `cmms_test` database), `npm test`, `worker/.venv/bin/pytest`, and
`python3 scripts/smoke_test.py http://localhost:8080 <admin-password>` against a running API.

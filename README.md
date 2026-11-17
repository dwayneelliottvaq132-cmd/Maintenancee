# Maintenance Manager (CMMS)

Preventive maintenance scheduling, work orders with step-by-step work instructions, labor / parts / spending
tracking per asset and per employee, maintenance requests with supervisor sign-off, and asset management.

| Part | Tech | Folder |
|---|---|---|
| REST API | Java 21, Spring Boot 3, PostgreSQL, Flyway, JWT | `backend/` |
| Web UI | React 18, TypeScript, Vite, MUI | `frontend/` |
| Background jobs | Python 3 (PM generation, email alerts, monthly PDF/Excel reports) | `worker/` |
| Ubuntu deployment | install script, systemd units + timers, nginx, backups | `deploy/` |

## Install on Ubuntu 22.04 / 24.04

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

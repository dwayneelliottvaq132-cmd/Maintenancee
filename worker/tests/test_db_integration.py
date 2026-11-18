"""Runs the real schema in a throw-away PostgreSQL schema. Skipped when no database is reachable.
Set CMMS_TEST_DB (libpq conninfo) to point elsewhere; default: local cmms_test database."""
import os
import pathlib
import uuid
from datetime import date, timedelta

import psycopg
import pytest
from psycopg.rows import dict_row

from cmms_worker import alerts, pm, reports
from cmms_worker.config import load

MIGRATIONS = sorted((pathlib.Path(__file__).resolve().parents[2] / "backend/src/main/resources/db/migration").glob("V*__*.sql"),
                    key=lambda f: int(f.name[1:].split("__")[0]))
CONNINFO = os.environ.get("CMMS_TEST_DB", "host=localhost dbname=cmms_test user=cmms password=cmms")


@pytest.fixture()
def conn():
    try:
        c = psycopg.connect(CONNINFO, row_factory=dict_row, autocommit=True)
    except psycopg.OperationalError as e:
        pytest.skip(f"no test database: {e}")
    schema = "t_" + uuid.uuid4().hex[:8]
    c.execute(f"CREATE SCHEMA {schema}")
    c.execute(f"SET search_path TO {schema}")
    for m in MIGRATIONS:   # same schema the API builds with Flyway
        c.execute(m.read_text())
    yield c
    c.execute(f"DROP SCHEMA {schema} CASCADE")
    c.close()


def seed(c, next_due, freq="MONTHS"):
    tech = c.execute("""INSERT INTO users (username, password_hash, full_name, email, role, hourly_rate)
                        VALUES ('tech', 'x', 'Terry Tech', 'tech@example.com', 'TECHNICIAN', 30) RETURNING id""").fetchone()["id"]
    c.execute("""INSERT INTO users (username, password_hash, full_name, email, role)
                 VALUES ('sup', 'x', 'Sam Sup', 'sup@example.com', 'SUPERVISOR')""")
    asset = c.execute("INSERT INTO assets (tag, name, category) VALUES ('P-1', 'Pump', 'Pumps') RETURNING id").fetchone()["id"]
    c.execute("""INSERT INTO pm_schedules (title, asset_id, frequency_type, interval_value, next_due, default_assignee_id)
                 VALUES ('Grease bearings', %s, %s, 1, %s, %s)""", (asset, freq, next_due, tech))
    return tech, asset


def test_generate_is_idempotent_and_assigns(conn):
    tech, _ = seed(conn, date.today())
    assert len(pm.generate_due(conn)) == 1
    assert pm.generate_due(conn) == []  # open WO exists -> nothing new
    wo = conn.execute("SELECT * FROM work_orders").fetchone()
    assert wo["type"] == "PM" and wo["status"] == "ASSIGNED" and wo["number"].startswith("WO-")
    assert conn.execute("SELECT user_id FROM wo_assignments").fetchone()["user_id"] == tech


def test_not_due_yet(conn):
    seed(conn, date.today() + timedelta(days=10))
    assert pm.generate_due(conn) == []


def test_alerts_and_report(conn, tmp_path, monkeypatch):
    monkeypatch.delenv("CMMS_SMTP_HOST", raising=False)
    tech, asset = seed(conn, date.today() - timedelta(days=5))
    pm.generate_due(conn)
    wo = conn.execute("SELECT id FROM work_orders").fetchone()["id"]
    conn.execute("INSERT INTO labor_entries (work_order_id, user_id, hours, hourly_rate) VALUES (%s, %s, 2, 30)", (wo, tech))
    conn.execute("INSERT INTO parts (part_no, name, qty_on_hand, reorder_point) VALUES ('G-1', 'Grease', 1, 5)")
    cfg = load()
    digests = alerts.build_digests(conn, cfg)
    assert any("OVERDUE" in l for l in digests["tech@example.com"][1])
    assert any("LOW STOCK" in l for l in digests["sup@example.com"][1])
    assert alerts.run(conn, cfg) == 2
    assert alerts.run(conn, cfg) == 0  # once per user per day

    month = date.today().strftime("%Y-%m")
    files = reports.build(conn, str(tmp_path), month)
    assert all(pathlib.Path(f).stat().st_size > 1000 for f in files)
    data = reports.gather(conn, *reports.month_range(month))
    assert float(data["By employee (labor)"][0][4]) == 60.0


def test_one_bad_address_does_not_block_other_alerts(conn, monkeypatch):
    monkeypatch.delenv("CMMS_SMTP_HOST", raising=False)
    tech, _ = seed(conn, date.today() - timedelta(days=5))
    pm.generate_due(conn)
    conn.execute("""INSERT INTO users (username, password_hash, full_name, email, role)
                    VALUES ('sup2', 'x', 'Second Sup', 'broken@example.com', 'SUPERVISOR')""")
    conn.execute("INSERT INTO maintenance_requests (title, requester_id) VALUES ('Leak', %s)", (tech,))
    delivered = []

    def fake_send(cfg, to, subject, body):
        if to == "broken@example.com":
            raise OSError("550 mailbox unavailable")
        delivered.append((to, subject))

    monkeypatch.setattr(alerts, "send", fake_send)
    cfg = load()
    alerts.run(conn, cfg)
    # everyone else still got their mail: tech digest, sup digest, sup new-request notice
    assert ("tech@example.com" in [t for t, _ in delivered]) and ("sup@example.com" in [t for t, _ in delivered])
    assert sum(1 for t, s in delivered if s.startswith("New maintenance request")) == 1
    first = len(delivered)
    alerts.run(conn, cfg)
    assert len(delivered) == first   # nothing sent twice; the broken address is retried but still fails


def test_cancelled_pm_occurrence_is_not_recreated(conn):
    seed(conn, date.today())
    created = pm.generate_due(conn)
    assert len(created) == 1
    conn.execute("UPDATE work_orders SET status = 'CANCELLED'")
    assert pm.generate_due(conn) == []


def test_employee_report_counts_labor_only(conn):
    tech, asset = seed(conn, date.today() + timedelta(days=30))
    wo = conn.execute("""INSERT INTO work_orders (number, title, type, asset_id) VALUES ('WO-T1', 'x', 'CORRECTIVE', %s)
                         RETURNING id""", (asset,)).fetchone()["id"]
    conn.execute("INSERT INTO labor_entries (work_order_id, user_id, hours, hourly_rate) VALUES (%s, %s, 1, 30)", (wo, tech))
    part = conn.execute("INSERT INTO parts (part_no, name, qty_on_hand) VALUES ('P', 'Part', 5) RETURNING id").fetchone()["id"]
    conn.execute("INSERT INTO part_usages (work_order_id, part_id, qty, unit_cost, used_by) VALUES (%s, %s, 2, 50, %s)", (wo, part, tech))
    data = reports.gather(conn, *reports.month_range(date.today().strftime("%Y-%m")))
    row = data["By employee (labor)"][0]
    assert float(row[4]) == 30.0   # total = labor only, not the $100 of parts

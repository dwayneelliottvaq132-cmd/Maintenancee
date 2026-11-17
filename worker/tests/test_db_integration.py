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

SCHEMA_SQL = pathlib.Path(__file__).resolve().parents[2] / "backend/src/main/resources/db/migration/V1__init.sql"
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
    c.execute(SCHEMA_SQL.read_text())
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

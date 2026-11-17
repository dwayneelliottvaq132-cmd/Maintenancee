from datetime import date
from decimal import Decimal

from cmms_worker.config import parse_jdbc
from cmms_worker.pm import Schedule, add_months, is_due, next_due
from cmms_worker.reports import month_range, previous_month


def test_add_months_clamps_to_month_end_like_java():
    assert add_months(date(2026, 1, 31), 1) == date(2026, 2, 28)
    assert add_months(date(2024, 1, 31), 1) == date(2024, 2, 29)
    assert add_months(date(2026, 11, 15), 3) == date(2027, 2, 15)
    assert add_months(date(2026, 3, 10), -3) == date(2025, 12, 10)


def test_next_due():
    d = date(2026, 11, 17)
    assert next_due(d, "DAYS", 10) == date(2026, 11, 27)
    assert next_due(d, "WEEKS", 2) == date(2026, 12, 1)
    assert next_due(d, "MONTHS", 6) == date(2027, 5, 17)
    assert next_due(d, "METER", 250) is None


def sched(**kw):
    base = dict(id=1, title="t", frequency_type="MONTHS", interval_value=1, next_due=date(2026, 11, 20),
                next_meter=None, lead_days=0, active=True, asset_meter=None)
    base.update(kw)
    return Schedule(**base)


def test_is_due_respects_lead_days_and_active():
    today = date(2026, 11, 17)
    assert not is_due(sched(), today)
    assert is_due(sched(lead_days=3), today)
    assert is_due(sched(next_due=date(2026, 11, 1)), today)  # overdue
    assert not is_due(sched(next_due=date(2026, 11, 1), active=False), today)


def test_is_due_meter():
    today = date(2026, 11, 17)
    assert not is_due(sched(frequency_type="METER", next_due=None, next_meter=Decimal(500), asset_meter=Decimal(499)), today)
    assert is_due(sched(frequency_type="METER", next_due=None, next_meter=Decimal(500), asset_meter=Decimal(500)), today)


def test_parse_jdbc_and_months():
    assert parse_jdbc("jdbc:postgresql://db.local:5433/cmms?ssl=true") == ("db.local", 5433, "cmms")
    assert parse_jdbc("jdbc:postgresql://localhost/cmms") == ("localhost", 5432, "cmms")
    assert month_range("2026-12") == (date(2026, 12, 1), date(2027, 1, 1))
    assert previous_month(date(2026, 1, 5)) == "2025-12"

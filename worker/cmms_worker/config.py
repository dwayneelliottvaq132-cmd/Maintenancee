"""Configuration from environment variables (same /etc/cmms/cmms.env file the API uses)."""
import os
import re
from dataclasses import dataclass


@dataclass(frozen=True)
class Config:
    db_host: str
    db_port: int
    db_name: str
    db_user: str
    db_password: str
    report_dir: str
    smtp_host: str | None
    smtp_port: int
    smtp_user: str | None
    smtp_password: str | None
    smtp_from: str
    smtp_starttls: bool
    app_url: str
    due_soon_days: int

    @property
    def conninfo(self) -> str:
        return (f"host={self.db_host} port={self.db_port} dbname={self.db_name} "
                f"user={self.db_user} password={self.db_password}")


def parse_jdbc(url: str) -> tuple[str, int, str]:
    """jdbc:postgresql://host:5432/dbname?x=y -> (host, port, dbname)"""
    m = re.match(r"jdbc:postgresql://([^:/?]+)(?::(\d+))?/([^?]+)", url)
    if not m:
        raise ValueError(f"Unsupported CMMS_DB_URL: {url}")
    return m.group(1), int(m.group(2) or 5432), m.group(3)


def load() -> Config:
    e = os.environ.get
    host, port, name = parse_jdbc(e("CMMS_DB_URL", "jdbc:postgresql://localhost:5432/cmms"))
    return Config(
        db_host=host, db_port=port, db_name=name,
        db_user=e("CMMS_DB_USER", "cmms"), db_password=e("CMMS_DB_PASSWORD", "cmms"),
        report_dir=e("CMMS_REPORT_DIR", "./data/reports"),
        smtp_host=e("CMMS_SMTP_HOST") or None,
        smtp_port=int(e("CMMS_SMTP_PORT", "587")),
        smtp_user=e("CMMS_SMTP_USER") or None,
        smtp_password=e("CMMS_SMTP_PASSWORD") or None,
        smtp_from=e("CMMS_SMTP_FROM", "maintenance@localhost"),
        smtp_starttls=e("CMMS_SMTP_STARTTLS", "true").lower() == "true",
        app_url=e("CMMS_APP_URL", "http://localhost").rstrip("/"),
        due_soon_days=int(e("CMMS_DUE_SOON_DAYS", "3")),
    )

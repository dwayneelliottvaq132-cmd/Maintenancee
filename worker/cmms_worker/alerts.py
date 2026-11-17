"""Email alerts. Each kind of alert is sent at most once per recipient per day (tracked in notifications_sent)."""
import logging
import smtplib
from collections import defaultdict
from datetime import date
from email.message import EmailMessage

from .config import Config

log = logging.getLogger(__name__)


def _claim(conn, kind: str, ref_id: int, once_ever: bool = False) -> bool:
    """Record that an alert is being sent; returns False if it was already sent (today, or ever)."""
    if once_ever and conn.execute("SELECT 1 FROM notifications_sent WHERE kind=%s AND ref_id=%s", (kind, ref_id)).fetchone():
        return False
    cur = conn.execute("INSERT INTO notifications_sent (kind, ref_id) VALUES (%s, %s) ON CONFLICT DO NOTHING", (kind, ref_id))
    return cur.rowcount == 1


def build_digests(conn, cfg: Config, today: date | None = None) -> dict[str, tuple[str, list[str], int]]:
    """Returns {email: (name, [lines], user_id)} for the daily digest."""
    today = today or date.today()
    out: dict[str, tuple[str, list[str], int]] = {}
    lines: dict[int, list[str]] = defaultdict(list)
    people = {r["id"]: r for r in conn.execute(
        "SELECT id, full_name, email, role FROM users WHERE active AND email IS NOT NULL AND email <> ''").fetchall()}
    supervisors = [uid for uid, p in people.items() if p["role"] in ("ADMIN", "SUPERVISOR")]

    rows = conn.execute("""
        SELECT wo.id, wo.number, wo.title, wo.due_date, a.tag, x.user_id
          FROM work_orders wo LEFT JOIN assets a ON a.id = wo.asset_id
          LEFT JOIN wo_assignments x ON x.work_order_id = wo.id
         WHERE wo.status IN ('OPEN','ASSIGNED','IN_PROGRESS') AND wo.due_date <= %s
         ORDER BY wo.due_date""", (today,)).fetchall()
    rows_soon = conn.execute("""
        SELECT wo.id, wo.number, wo.title, wo.due_date, a.tag, x.user_id
          FROM work_orders wo LEFT JOIN assets a ON a.id = wo.asset_id
          JOIN wo_assignments x ON x.work_order_id = wo.id
         WHERE wo.status IN ('OPEN','ASSIGNED','IN_PROGRESS') AND wo.due_date > %s AND wo.due_date <= %s + %s::int
         ORDER BY wo.due_date""", (today, today, cfg.due_soon_days)).fetchall()

    seen_overdue = set()
    for r in rows:
        label = "OVERDUE" if r["due_date"] < today else "DUE TODAY"
        line = f"[{label}] {r['number']} {r['title']} ({r['tag'] or 'no asset'}) due {r['due_date']} — {cfg.app_url}/work-orders/{r['id']}"
        if r["user_id"]:
            lines[r["user_id"]].append(line)
        if r["id"] not in seen_overdue and r["due_date"] < today:
            seen_overdue.add(r["id"])
            for s in supervisors:
                if s != r["user_id"]:
                    lines[s].append(line)
    for r in rows_soon:
        lines[r["user_id"]].append(f"[DUE SOON] {r['number']} {r['title']} ({r['tag'] or 'no asset'}) due {r['due_date']} — "
                                   f"{cfg.app_url}/work-orders/{r['id']}")

    awaiting = conn.execute("SELECT COUNT(*) AS n FROM work_orders WHERE status = 'COMPLETED'").fetchone()["n"]
    low = conn.execute("""SELECT part_no, name, qty_on_hand, reorder_point, unit FROM parts
                          WHERE active AND qty_on_hand <= reorder_point ORDER BY part_no""").fetchall()
    for s in supervisors:
        if awaiting:
            lines[s].append(f"{awaiting} completed work order(s) waiting for your sign-off — {cfg.app_url}/work-orders?status=COMPLETED")
        for p in low:
            lines[s].append(f"[LOW STOCK] {p['part_no']} {p['name']}: {p['qty_on_hand']:g} {p['unit']} on hand "
                            f"(reorder at {p['reorder_point']:g})")

    for uid, ls in lines.items():
        p = people.get(uid)
        if p and ls:
            out[p["email"]] = (p["full_name"], list(dict.fromkeys(ls)), uid)  # dedupe, keep order
    return out


def send(cfg: Config, to: str, subject: str, body: str) -> None:
    if not cfg.smtp_host:
        log.info("SMTP not configured; would send to %s: %s\n%s", to, subject, body)
        return
    msg = EmailMessage()
    msg["From"], msg["To"], msg["Subject"] = cfg.smtp_from, to, subject
    msg.set_content(body)
    with smtplib.SMTP(cfg.smtp_host, cfg.smtp_port, timeout=30) as s:
        if cfg.smtp_starttls:
            s.starttls()
        if cfg.smtp_user:
            s.login(cfg.smtp_user, cfg.smtp_password or "")
        s.send_message(msg)


def run(conn, cfg: Config, today: date | None = None) -> int:
    """Send new-request notices (once per request) and the daily digest (once per user per day)."""
    sent = 0
    sups = conn.execute("""SELECT id, email FROM users WHERE active AND role IN ('ADMIN','SUPERVISOR')
                           AND email IS NOT NULL AND email <> ''""").fetchall()
    for r in conn.execute("""SELECT r.id, r.title, r.priority, u.full_name FROM maintenance_requests r
                             JOIN users u ON u.id = r.requester_id WHERE r.status = 'NEW'""").fetchall():
        with conn.transaction():
            if not _claim(conn, "new_request", r["id"], once_ever=True):
                continue
            for s in sups:
                send(cfg, s["email"], f"New maintenance request ({r['priority']}): {r['title']}",
                     f"{r['full_name']} reported: {r['title']}\n\nReview it at {cfg.app_url}/requests")
                sent += 1

    for email, (name, lines, uid) in build_digests(conn, cfg, today).items():
        with conn.transaction():
            if not _claim(conn, "daily_digest", uid):
                continue
            send(cfg, email, f"Maintenance: {len(lines)} item(s) need attention",
                 f"Hello {name},\n\n" + "\n".join(f"- {l}" for l in lines) + f"\n\n{cfg.app_url}\n")
            sent += 1
    return sent

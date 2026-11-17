"""Monthly spending pack: PDF + Excel written to CMMS_REPORT_DIR (listed in the UI under Reports)."""
import logging
import os
from datetime import date
from decimal import Decimal

from openpyxl import Workbook
from openpyxl.styles import Font, PatternFill
from reportlab.lib import colors
from reportlab.lib.pagesizes import letter
from reportlab.lib.styles import getSampleStyleSheet
from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

from .pm import add_months

log = logging.getLogger(__name__)

SECTIONS = {
    "By asset": ("COALESCE(a.tag || ' - ' || a.name, '(no asset)')", "LEFT JOIN assets a ON a.id = c.asset_id"),
    "By category": ("COALESCE(a.category, '(uncategorised)')", "LEFT JOIN assets a ON a.id = c.asset_id"),
    "By employee (labor)": ("COALESCE(u.full_name, '(not a person)')", "LEFT JOIN users u ON u.id = c.user_id"),
    "By work type": ("wo.type", "JOIN work_orders wo ON wo.id = c.work_order_id"),
}
COLS = ["Group", "Labor", "Parts", "Other", "Total", "Hours", "Work orders"]


def month_range(month: str) -> tuple[date, date]:
    y, m = (int(x) for x in month.split("-"))
    start = date(y, m, 1)
    return start, add_months(start, 1)


def previous_month(today: date | None = None) -> str:
    d = add_months((today or date.today()).replace(day=1), -1)
    return f"{d.year:04d}-{d.month:02d}"


def gather(conn, start: date, end: date) -> dict[str, list[list]]:
    data: dict[str, list[list]] = {}
    for title, (key, join) in SECTIONS.items():
        rows = conn.execute(f"""
            SELECT {key} AS label,
                   COALESCE(SUM(c.amount) FILTER (WHERE c.cost_type='LABOR'),0) AS labor,
                   COALESCE(SUM(c.amount) FILTER (WHERE c.cost_type='PARTS'),0) AS parts,
                   COALESCE(SUM(c.amount) FILTER (WHERE c.cost_type='OTHER'),0) AS other,
                   SUM(c.amount) AS total, SUM(c.hours) AS hours, COUNT(DISTINCT c.work_order_id) AS wos
              FROM v_cost_lines c {join}
             WHERE c.cost_date >= %s AND c.cost_date < %s
             GROUP BY 1 ORDER BY total DESC""", (start, end)).fetchall()
        data[title] = [[r["label"], r["labor"], r["parts"], r["other"], r["total"], r["hours"], r["wos"]] for r in rows]
    s = conn.execute("""
        SELECT COUNT(*) FILTER (WHERE created_at >= %s AND created_at < %s) AS created,
               COUNT(*) FILTER (WHERE completed_at >= %s AND completed_at < %s) AS completed,
               COUNT(*) FILTER (WHERE type='PM' AND completed_at >= %s AND completed_at < %s) AS pm_done,
               COUNT(*) FILTER (WHERE type='PM' AND completed_at >= %s AND completed_at < %s AND completed_at::date <= due_date) AS pm_on_time
          FROM work_orders""", (start, end) * 4).fetchone()
    data["Summary"] = [["Work orders created", s["created"]], ["Work orders completed", s["completed"]],
                       ["PM completed", s["pm_done"]], ["PM completed on time", s["pm_on_time"]]]
    return data


def money(v) -> str:
    return f"${Decimal(v or 0):,.2f}"


def write_excel(path: str, month: str, data: dict[str, list[list]]) -> None:
    wb = Workbook()
    ws = wb.active
    ws.title = "Summary"
    ws.append([f"Maintenance spending report {month}"])
    ws["A1"].font = Font(bold=True, size=14)
    for row in data["Summary"]:
        ws.append(row)
    ws.column_dimensions["A"].width = 28
    for title, rows in data.items():
        if title == "Summary":
            continue
        sh = wb.create_sheet(title[:31])
        sh.append(COLS)
        for c in sh[1]:
            c.font = Font(bold=True, color="FFFFFF")
            c.fill = PatternFill("solid", fgColor="1565C0")
        for r in rows:
            sh.append([r[0]] + [float(x or 0) for x in r[1:6]] + [r[6]])
        n = len(rows) + 1
        sh.append(["TOTAL"] + [f"=SUM({col}2:{col}{n})" for col in "BCDEFG"])
        for row in sh.iter_rows(min_row=2, min_col=2, max_col=5):
            for c in row:
                c.number_format = '"$"#,##0.00'
        sh.column_dimensions["A"].width = 40
    wb.save(path)


def write_pdf(path: str, month: str, data: dict[str, list[list]]) -> None:
    styles = getSampleStyleSheet()
    doc = SimpleDocTemplate(path, pagesize=letter, title=f"Maintenance spending {month}")
    story = [Paragraph(f"Maintenance spending report — {month}", styles["Title"])]
    story.append(Table(data["Summary"], hAlign="LEFT"))
    for title, rows in data.items():
        if title == "Summary":
            continue
        story += [Spacer(1, 14), Paragraph(title, styles["Heading2"])]
        if not rows:
            story.append(Paragraph("No spending recorded.", styles["Normal"]))
            continue
        body = [[str(r[0])[:45], money(r[1]), money(r[2]), money(r[3]), money(r[4]), f"{Decimal(r[5] or 0):g}", r[6]] for r in rows]
        tot = [sum(Decimal(r[i] or 0) for r in rows) for i in range(1, 6)]
        body.append(["TOTAL"] + [money(t) for t in tot[:4]] + [f"{tot[4]:g}", ""])
        t = Table([COLS] + body, hAlign="LEFT", repeatRows=1)
        t.setStyle(TableStyle([
            ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#1565c0")), ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
            ("FONTSIZE", (0, 0), (-1, -1), 8), ("ALIGN", (1, 0), (-1, -1), "RIGHT"),
            ("ROWBACKGROUNDS", (0, 1), (-1, -2), [colors.white, colors.HexColor("#f2f5f9")]),
            ("FONTNAME", (0, -1), (-1, -1), "Helvetica-Bold"), ("LINEABOVE", (0, -1), (-1, -1), 0.5, colors.black),
        ]))
        story.append(t)
    doc.build(story)


def build(conn, report_dir: str, month: str) -> list[str]:
    start, end = month_range(month)
    data = gather(conn, start, end)
    os.makedirs(report_dir, exist_ok=True)
    base = os.path.join(report_dir, f"maintenance-spending-{month}")
    write_excel(base + ".xlsx", month, data)
    write_pdf(base + ".pdf", month, data)
    log.info("Wrote %s.pdf/.xlsx", base)
    return [base + ".pdf", base + ".xlsx"]

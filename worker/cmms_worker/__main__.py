"""python -m cmms_worker {generate-pm|alerts|report} [options]"""
import argparse
import logging
import sys

from . import alerts, config, db, pm, reports


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="cmms_worker", description="CMMS background jobs")
    sub = ap.add_subparsers(dest="cmd", required=True)
    g = sub.add_parser("generate-pm", help="create work orders for PM schedules that are due")
    g.add_argument("--dry-run", action="store_true")
    sub.add_parser("alerts", help="email overdue/due-soon work, low stock and new requests")
    r = sub.add_parser("report", help="write monthly spending PDF + Excel")
    r.add_argument("--month", help="YYYY-MM (default: previous month)")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args(argv)

    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    cfg = config.load()
    with db.connect(cfg) as conn:
        conn.autocommit = True  # each unit of work uses an explicit transaction block
        if args.cmd == "generate-pm":
            created = pm.generate_due(conn, dry_run=args.dry_run)
            print(f"{len(created)} PM work order(s) created: {', '.join(created) or '-'}")
        elif args.cmd == "alerts":
            print(f"{alerts.run(conn, cfg)} email(s) sent")
        elif args.cmd == "report":
            files = reports.build(conn, cfg.report_dir, args.month or reports.previous_month())
            print("\n".join(files))
    return 0


if __name__ == "__main__":
    sys.exit(main())

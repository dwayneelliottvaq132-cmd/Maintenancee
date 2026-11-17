#!/usr/bin/env python3
"""Load realistic demo data through the API (for trying the system out; don't run on production).

Usage: seed_demo.py [base_url] [admin_username] [admin_password]
Creates users with password 'Demo1234!' (they must change it at first login).
"""
import datetime as dt
import json
import sys
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
ADMIN_USER = sys.argv[2] if len(sys.argv) > 2 else "admin"
ADMIN_PW = sys.argv[3] if len(sys.argv) > 3 else "admin123"
PW = "Demo1234!"
TODAY = dt.date.today()


def call(method, path, body=None, token=None):
    req = urllib.request.Request(BASE + "/api" + path, method=method,
                                 data=None if body is None else json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{method} {path} failed: {e.code} {e.read().decode()}")


def login(u, p):
    return call("POST", "/auth/login", {"username": u, "password": p})["token"]


def d(days):
    return (TODAY + dt.timedelta(days=days)).isoformat()


def main():
    admin = login(ADMIN_USER, ADMIN_PW)
    existing = {u["username"] for u in call("GET", "/users", token=admin)}
    people = [
        ("msmith", "Maria Smith", "SUPERVISOR", 52, "Maintenance Lead", "maria@example.com"),
        ("jlee", "James Lee", "TECHNICIAN", 38, "Electrician", "james@example.com"),
        ("agarcia", "Ana Garcia", "TECHNICIAN", 35, "HVAC", "ana@example.com"),
        ("bwong", "Ben Wong", "TECHNICIAN", 32, "Millwright", "ben@example.com"),
        ("pkhan", "Priya Khan", "REQUESTER", 0, "Production", "priya@example.com"),
    ]
    for un, name, role, rate, trade, email in people:
        if un not in existing:
            call("POST", "/users", {"username": un, "fullName": name, "role": role, "hourlyRate": rate,
                                    "trade": trade, "email": email, "password": PW}, admin)
    users = {u["username"]: u for u in call("GET", "/users", token=admin)}
    sup = admin  # admin can do everything a supervisor can

    if call("GET", "/assets", token=admin):
        print("Assets already exist; skipping the rest of the demo data.")
        return

    loc = {}
    plant = call("POST", "/locations", {"name": "Main Plant"}, sup)
    for name in ["Production Floor", "Boiler Room", "Warehouse", "Office Building"]:
        loc[name] = call("POST", "/locations", {"name": name, "parentId": plant["id"]}, sup)["id"]
    loc["Fleet Yard"] = call("POST", "/locations", {"name": "Fleet Yard"}, sup)["id"]

    A = {}
    for tag, name, cat, where, mfr, model, crit, meter_unit, cost in [
        ("AC-01", "Air Compressor #1", "Compressors", "Production Floor", "Atlas Copco", "GA30", "HIGH", "hours", 18500),
        ("BLR-01", "Steam Boiler", "Boilers", "Boiler Room", "Cleaver-Brooks", "CB-200", "HIGH", None, 96000),
        ("RTU-03", "Rooftop HVAC Unit 3", "HVAC", "Office Building", "Carrier", "48TC", "MEDIUM", None, 22000),
        ("CNV-02", "Packaging Conveyor", "Conveyors", "Production Floor", "Hytrol", "E24", "MEDIUM", None, 14000),
        ("FL-07", "Forklift 7", "Vehicles", "Warehouse", "Toyota", "8FGU25", "MEDIUM", "hours", 31000),
        ("TRK-02", "Service Truck", "Vehicles", "Fleet Yard", "Ford", "F-250", "LOW", "miles", 48000),
    ]:
        A[tag] = call("POST", "/assets", {"tag": tag, "name": name, "category": cat, "locationId": loc[where],
                                          "manufacturer": mfr, "model": model, "criticality": crit, "meterUnit": meter_unit,
                                          "purchaseCost": cost, "installDate": d(-900), "status": "ACTIVE"}, sup)
    call("POST", f"/assets/{A['FL-07']['id']}/meter", {"reading": 2390}, sup)
    call("POST", f"/assets/{A['TRK-02']['id']}/meter", {"reading": 41250}, sup)

    P = {}
    for no, name, unit, cost, qty, reorder, binloc, supplier in [
        ("FLT-AC30", "Compressor air filter", "ea", 42.50, 6, 2, "A-1", "Grainger"),
        ("OIL-ROTO", "Compressor oil (5 gal)", "pail", 189.00, 2, 2, "A-2", "Atlas Copco"),
        ("FLT-2020", "HVAC filter 20x20x2", "ea", 8.75, 24, 12, "B-4", "Grainger"),
        ("BLT-A48", "V-belt A48", "ea", 14.20, 3, 4, "C-1", "Motion"),
        ("GRS-EP2", "EP2 grease cartridge", "ea", 6.90, 30, 10, "C-3", "Motion"),
        ("OIL-5W30", "Engine oil 5W-30 (qt)", "qt", 7.50, 18, 12, "D-1", "NAPA"),
    ]:
        P[no] = call("POST", "/parts", {"partNo": no, "name": name, "unit": unit, "unitCost": cost, "qtyOnHand": qty,
                                        "reorderPoint": reorder, "binLocation": binloc, "supplier": supplier}, sup)

    def wi(title, steps, parts, safety, ppe, tools, mins):
        return call("POST", "/instructions", {
            "instruction": {"title": title, "safetyNotes": safety, "requiredPpe": ppe, "toolsRequired": tools, "estimatedMinutes": mins},
            "steps": [{"text": s, "requiresCheck": True} for s in steps],
            "parts": [{"partId": P[p]["id"], "qty": q} for p, q in parts]}, sup)["instruction"]

    W = {
        "comp": wi("Air compressor 500-hour service", [
            "Apply lockout/tagout to compressor main disconnect and bleed down receiver",
            "Replace air intake filter", "Check oil level and top up; change oil every 2nd service",
            "Inspect belts and hoses for wear", "Drain condensate and test auto-drain",
            "Remove LOTO, start unit and verify discharge pressure"],
            [("FLT-AC30", 1)], "Stored energy: bleed receiver to 0 psi before opening.", "Safety glasses, gloves, hearing protection",
            "Socket set, filter wrench, drain pan", 60),
        "hvac": wi("Rooftop unit quarterly PM", [
            "Disconnect power at unit", "Replace filters", "Clean condenser coil", "Check belt tension and condition",
            "Check refrigerant pressures and record", "Restore power and verify operation"],
            [("FLT-2020", 4)], "Roof access: use fall protection inside 6 ft of the edge.", "Harness, gloves, safety glasses",
            "Gauges, coil cleaner, belt tension gauge", 90),
        "boiler": wi("Boiler weekly safety check", [
            "Check water level gauge glass and blow down", "Test low-water cut-off",
            "Check flame signal and burner operation", "Record stack temperature and pressure"],
            [], "Hot surfaces and steam — never open a pressurised vessel.", "Face shield, heat-resistant gloves", "Log sheet", 30),
        "fork": wi("Forklift 250-hour service", [
            "Change engine oil", "Grease all fittings", "Inspect forks, chains and mast", "Check brakes and horn"],
            [("OIL-5W30", 5), ("GRS-EP2", 2)], "Chock wheels and lower forks fully.", "Gloves, safety glasses", "Grease gun", 75),
    }

    def pm(title, asset, instr, freq, interval, next_due=None, assignee=None, next_meter=None):
        return call("POST", "/pm-schedules", {"title": title, "assetId": A[asset]["id"], "instructionId": W[instr]["id"] if instr else None,
                                              "frequencyType": freq, "intervalValue": interval, "nextDue": next_due, "nextMeter": next_meter,
                                              "defaultAssigneeId": users[assignee]["id"] if assignee else None, "active": True,
                                              "leadDays": 2, "priority": "MEDIUM"}, sup)

    pm("500-hour service", "AC-01", "comp", "MONTHS", 3, d(-3), "bwong")
    pm("Quarterly PM", "RTU-03", "hvac", "MONTHS", 3, d(5), "agarcia")
    pm("Weekly safety check", "BLR-01", "boiler", "WEEKS", 1, d(1), "jlee")
    pm("Belt inspection", "CNV-02", None, "MONTHS", 1, d(12), "bwong")
    pm("250-hour service", "FL-07", "fork", "METER", 250, assignee="bwong", next_meter=2400)
    pm("Oil change", "TRK-02", None, "METER", 5000, assignee="jlee", next_meter=45000)
    created = call("POST", "/pm-schedules/generate-due", None, sup)
    print(f"Generated {len(created)} PM work orders")

    # A finished, signed-off job with costs (shows up in spending reports)
    tech = login("bwong", PW)
    wo = call("POST", "/work-orders", {"workOrder": {"title": "Replace worn conveyor belt", "assetId": A["CNV-02"]["id"],
                                                     "type": "CORRECTIVE", "priority": "HIGH", "dueDate": d(-2)},
                                       "assigneeIds": [users["bwong"]["id"]]}, sup)
    call("POST", f"/work-orders/{wo['id']}/labor", {"hours": 3.5, "workDate": d(-1), "note": "Belt swap and tracking"}, tech)
    call("POST", f"/work-orders/{wo['id']}/parts", {"partId": P["BLT-A48"]["id"], "qty": 2}, tech)
    call("POST", f"/work-orders/{wo['id']}/costs", {"description": "Belt splicing service", "vendor": "Conveyor Pros", "amount": 275}, sup)
    call("POST", f"/work-orders/{wo['id']}/complete", {"notes": "Replaced belt, tracked and tensioned. Running smoothly."}, tech)
    call("POST", f"/work-orders/{wo['id']}/sign-off", {"signatureName": "Administrator", "notes": "Verified on line 2"}, sup)

    # A job waiting for sign-off
    wo2 = call("POST", "/work-orders", {"workOrder": {"title": "Reset tripped breaker on RTU-03", "assetId": A["RTU-03"]["id"],
                                                      "type": "CORRECTIVE", "dueDate": d(0)},
                                        "assigneeIds": [users["agarcia"]["id"]]}, sup)
    ana = login("agarcia", PW)
    call("POST", f"/work-orders/{wo2['id']}/labor", {"hours": 1}, ana)
    call("POST", f"/work-orders/{wo2['id']}/complete", {"notes": "Breaker reset; found loose lug, tightened."}, ana)

    # Requests from production
    priya = login("pkhan", PW)
    call("POST", "/requests", {"title": "Forklift 7 horn not working", "assetId": A["FL-07"]["id"], "priority": "HIGH",
                               "description": "Noticed at start of 2nd shift."}, priya)
    call("POST", "/requests", {"title": "Office too warm in afternoons", "locationId": loc["Office Building"], "priority": "LOW"}, priya)
    print("Demo data loaded. Users: " + ", ".join(p[0] for p in people) + f" (password {PW})")


if __name__ == "__main__":
    main()

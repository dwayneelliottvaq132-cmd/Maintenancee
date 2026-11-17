#!/usr/bin/env python3
"""End-to-end API smoke test. Usage: smoke_test.py [base_url] [admin_password]
Runs the full lifecycle against a running API (creates data with a random suffix)."""
import json, sys, uuid, urllib.request, urllib.error, datetime

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
ADMIN_PW = sys.argv[2] if len(sys.argv) > 2 else "admin123"
SFX = uuid.uuid4().hex[:6]


def call(method, path, body=None, token=None, expect=200):
    req = urllib.request.Request(BASE + path, method=method,
                                 data=None if body is None else json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as r:
            status, raw = r.status, r.read()
    except urllib.error.HTTPError as e:
        status, raw = e.code, e.read()
    data = json.loads(raw) if raw else None
    if status != expect:
        raise SystemExit(f"FAIL {method} {path}: expected {expect}, got {status}: {data}")
    return data


def login(u, p):
    return call("POST", "/api/auth/login", {"username": u, "password": p})["token"]


def main():
    admin = login("admin", ADMIN_PW)
    pw = "Passw0rd!x"
    mk = lambda name, role, rate: call("POST", "/api/users", {"username": f"{name}{SFX}", "fullName": f"{name.title()} {SFX}",
                                                               "role": role, "hourlyRate": rate, "password": pw}, admin)
    sup, tech, reqr = mk("sup", "SUPERVISOR", 45), mk("tech", "TECHNICIAN", 30), mk("req", "REQUESTER", 0)
    sup_t, tech_t, req_t = login(sup["username"], pw), login(tech["username"], pw), login(reqr["username"], pw)

    loc = call("POST", "/api/locations", {"name": f"Plant {SFX}"}, sup_t)
    asset = call("POST", "/api/assets", {"tag": f"AC-{SFX}", "name": "Air compressor", "category": "Compressors",
                                         "locationId": loc["id"], "meterUnit": "hours"}, sup_t)
    part = call("POST", "/api/parts", {"partNo": f"FLT-{SFX}", "name": "Air filter", "unitCost": 12.5,
                                       "qtyOnHand": 10, "reorderPoint": 9}, sup_t)
    wi = call("POST", "/api/instructions", {"instruction": {"title": f"Compressor service {SFX}", "estimatedMinutes": 45,
                                                            "safetyNotes": "Lock out / tag out"},
                                            "steps": [{"text": "Isolate power", "requiresCheck": True},
                                                      {"text": "Replace filter", "requiresCheck": True},
                                                      {"text": "Wipe down", "requiresCheck": False}],
                                            "parts": [{"partId": part["id"], "qty": 1}]}, sup_t)
    today = datetime.date.today().isoformat()
    pm = call("POST", "/api/pm-schedules", {"title": "Monthly service", "assetId": asset["id"], "instructionId": wi["instruction"]["id"],
                                            "frequencyType": "MONTHS", "intervalValue": 1, "nextDue": today,
                                            "defaultAssigneeId": tech["id"], "active": True}, sup_t)
    call("POST", "/api/pm-schedules/generate-due", None, tech_t, expect=403)   # techs can't run generation
    wo = call("POST", f"/api/pm-schedules/{pm['id']}/generate-now", None, sup_t)
    call("POST", f"/api/pm-schedules/{pm['id']}/generate-now", None, sup_t, expect=409)  # no duplicate open PM WO
    assert wo["status"] == "ASSIGNED", wo

    d = call("GET", f"/api/work-orders/{wo['id']}", token=tech_t)
    steps = d["steps"]
    call("POST", f"/api/work-orders/{wo['id']}/complete", {"notes": "x"}, tech_t, expect=409)  # steps not done
    for s in steps[:2]:
        call("PUT", f"/api/work-orders/{wo['id']}/steps/{s['stepId']}", {"done": True}, tech_t)
    call("POST", f"/api/work-orders/{wo['id']}/labor", {"hours": 1.5}, tech_t)
    call("POST", f"/api/work-orders/{wo['id']}/parts", {"partId": part["id"], "qty": 2}, tech_t)
    call("POST", f"/api/work-orders/{wo['id']}/parts", {"partId": part["id"], "qty": 500}, tech_t, expect=409)
    call("POST", f"/api/work-orders/{wo['id']}/costs", {"description": "Contractor callout", "amount": 100}, tech_t)
    call("POST", f"/api/work-orders/{wo['id']}/complete", {"notes": "All good", "meterReading": 1200}, tech_t)
    call("POST", f"/api/work-orders/{wo['id']}/sign-off", {"signatureName": "Sup"}, tech_t, expect=403)
    signed = call("POST", f"/api/work-orders/{wo['id']}/sign-off", {"signatureName": sup["fullName"]}, sup_t)
    assert signed["status"] == "SIGNED_OFF"

    d = call("GET", f"/api/work-orders/{wo['id']}", token=sup_t)
    assert float(d["totals"]["total"]) == 1.5 * 30 + 2 * 12.5 + 100, d["totals"]
    p = call("GET", f"/api/parts/{part['id']}", token=sup_t)
    assert float(p["qtyOnHand"]) == 8, p
    sched = call("GET", f"/api/pm-schedules/{pm['id']}", token=sup_t)
    assert sched["nextDue"] > today, sched
    a = call("GET", f"/api/assets/{asset['id']}", token=sup_t)
    assert float(a["costs"]["total"]) == 170.0 and float(a["asset"]["meterReading"]) == 1200

    r = call("POST", "/api/requests", {"title": "Compressor leaking oil", "assetId": asset["id"], "priority": "HIGH"}, req_t)
    call("POST", f"/api/requests/{r['id']}/approve", {}, req_t, expect=403)
    call("GET", "/api/work-orders", token=req_t, expect=403)
    ap = call("POST", f"/api/requests/{r['id']}/approve", {"assigneeIds": [tech["id"]]}, sup_t)
    assert ap["status"] == "APPROVED" and ap["workOrderId"]
    mine = call("GET", "/api/requests", token=req_t)
    assert [x["id"] for x in mine] == [r["id"]]

    rep = call("GET", f"/api/reports/spending?from={today}&to={today}&groupBy=employee", token=sup_t)
    assert any(row["label"] == tech["fullName"] for row in rep), rep
    dash = call("GET", "/api/dashboard", token=sup_t)
    assert "counts" in dash and len(dash["monthlySpend"]) == 12
    print("SMOKE TEST PASSED — work order", wo["number"], "request WO", ap["workOrderId"])


if __name__ == "__main__":
    main()

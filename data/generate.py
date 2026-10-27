"""Synthetic books for Brightwater Components Inc., Q3 2026 (Jul 1 to Sep 30).

Four separate systems, each exported to its own folder, the way a mid-size manufacturer keeps them:
  erp/      invoices, payments, policies   the accounting system
  vendors/  vendors                        procurement's vendor master
  hr/       employees                      the HR system
  bank/     transactions                   the bank's statement feed

Six frauds are planted, plus two look-alikes that are not fraud. The answer key goes to
data/answer_key.json. Tally never reads it; only the tests and scripts/evaluate.py do.

    python data/generate.py
"""
from __future__ import annotations

import csv
import json
import math
import random
from datetime import date, timedelta
from pathlib import Path

SEED = 20261027
HERE = Path(__file__).resolve().parent
OUT = HERE / "sources"
Q_START, Q_END = date(2026, 7, 1), date(2026, 9, 30)
AP_LIMIT, CFO_LIMIT, NEW_VENDOR_DAYS, DUPLICATE_WINDOW = 10_000, 25_000, 30, 14

rng = random.Random(SEED)

KEY_PEOPLE = [
    ("E-1001", "Karen Liu", "Finance", "Controller"),
    ("E-1002", "Tom Becker", "Finance", "Chief Financial Officer"),
    ("E-1007", "Derek Holloway", "Finance", "Accounts Payable Specialist"),
    ("E-1008", "Sandra Okafor", "Finance", "Accounts Payable Lead"),
    ("E-1012", "Marcus Webb", "Operations", "Facilities Manager"),
    ("E-1019", "Priya Nandakumar", "Operations", "Director of Operations"),
    ("E-1023", "Elena Ruiz", "Engineering", "Technical Writer"),
    ("E-1031", "Jason Park", "Manufacturing", "Production Manager"),
    ("E-1036", "Aisha Bello", "IT", "IT Manager"),
    ("E-1040", "Rafael Ortiz", "Procurement", "Procurement Manager"),
    ("E-1044", "Grace Kim", "Marketing", "Marketing Manager"),
    ("E-1048", "Omar Haddad", "Logistics", "Logistics Manager"),
]
CONTROLLER, CFO, DEREK, SANDRA = "E-1001", "E-1002", "E-1007", "E-1008"
MARCUS, PRIYA, ELENA, JASON = "E-1012", "E-1019", "E-1023", "E-1031"
AISHA, RAFAEL, GRACE, OMAR = "E-1036", "E-1040", "E-1044", "E-1048"

FIRST = ["Alex", "Ben", "Carla", "Dana", "Eli", "Fatima", "Gabe", "Hana", "Ivan", "Jade", "Kofi", "Lena",
         "Mateo", "Nina", "Owen", "Paula", "Quinn", "Rosa", "Sam", "Tara", "Umar", "Vera", "Wes", "Yusuf",
         "Zoe", "Bilal", "Chloe", "Diego", "Erin", "Felix", "Gina", "Hugo", "Iris", "Jon", "Kira", "Leo",
         "Maya", "Noah", "Olga", "Ravi"]
LAST = ["Adams", "Baker", "Chen", "Diaz", "Evans", "Fischer", "Garcia", "Hughes", "Ibrahim", "Jensen",
        "Kowalski", "Lopez", "Murphy", "Nguyen", "Olsen", "Patel", "Quinlan", "Reyes", "Schmidt", "Turner",
        "Usman", "Vargas", "Walsh", "Xu", "Young", "Zimmerman", "Brooks", "Carter", "Doyle", "Ellis"]
DEPTS = {
    "Manufacturing": (["Machine Operator", "Assembler", "Quality Inspector", "Shift Supervisor",
                       "Maintenance Technician"], 60),
    "Engineering": (["Mechanical Engineer", "Process Engineer", "CAD Designer"], 14),
    "Sales": (["Account Executive", "Sales Coordinator"], 12),
    "Logistics": (["Warehouse Associate", "Shipping Clerk", "Forklift Operator"], 18),
    "Finance": (["Staff Accountant", "Payroll Specialist"], 5),
    "Operations": (["Operations Analyst"], 4),
    "IT": (["Systems Administrator", "Help Desk Technician"], 4),
    "HR": (["HR Generalist", "Recruiter"], 3),
    "Procurement": (["Buyer"], 3),
    "Marketing": (["Marketing Coordinator"], 3),
}

VENDORS = [
    ("Kessler Steel Supply", "Raw materials"), ("Ironwood Metals Inc.", "Raw materials"),
    ("Bluestone Fasteners Co.", "Raw materials"), ("Meridian Plastics Corp.", "Raw materials"),
    ("Granite State Machining LLC", "Raw materials"), ("Redwood Hydraulics Inc.", "Raw materials"),
    ("Silverline Coatings Co.", "Raw materials"), ("Keystone Alloys Inc.", "Raw materials"),
    ("Pioneer Tooling LLC", "Raw materials"), ("Oakmont Industrial Gases", "Raw materials"),
    ("Cedar Ridge Polymers", "Raw materials"), ("Tidewater Copper Works", "Raw materials"),
    ("Tri-County Electrical", "Facilities"), ("Pinecrest Janitorial Services", "Facilities"),
    ("Westbrook Properties LLC", "Facilities"), ("Lakeshore HVAC Inc.", "Facilities"),
    ("Stonebridge Plumbing Co.", "Facilities"), ("Vantage Safety Equipment", "Facilities"),
    ("Northfield Landscaping", "Facilities"), ("Riverside Pest Control", "Facilities"),
    ("Cobalt Network Services", "IT"), ("Lumen Cloud Software", "IT"), ("Bytewise IT Solutions", "IT"),
    ("Sentinel Security Systems", "IT"), ("Arcadia Telecom", "IT"),
    ("Brennan Packaging Co.", "Logistics"), ("Harbor Freightways Inc.", "Logistics"),
    ("Crossroads Trucking LLC", "Logistics"), ("Bayline Freight Co.", "Logistics"),
    ("Midstate Pallet Supply", "Logistics"),
    ("Summit Office Supply", "Office"), ("Clearview Printing Co.", "Office"),
    ("Fairway Water Delivery", "Office"), ("Parkside Catering", "Office"),
    ("Foxglove Creative Studio", "Marketing"), ("Signal Media Group", "Marketing"),
    ("Trailhead Trade Shows", "Marketing"), ("Ruiz Translation Services", "Marketing"),
    ("Hollis & Grant LLP", "Services"), ("Mercer Calibration Labs", "Services"),
    ("Atlas Staffing Partners", "Services"), ("Pinnacle Consulting Group", "Services"),
    ("Evergreen Waste Management", "Services"),
    ("Apex Facility Services LLC", "Facilities"), ("Northline Logistics Group", "Logistics"),
]
REQUESTER = {"Raw materials": JASON, "Facilities": MARCUS, "IT": AISHA, "Logistics": OMAR,
             "Office": PRIYA, "Marketing": GRACE, "Services": RAFAEL}
TYPICAL = {"Raw materials": 4200, "Facilities": 1600, "IT": 1300, "Logistics": 1900, "Office": 420,
           "Marketing": 2600, "Services": 3100}
VOLUME = {"Raw materials": 5, "Facilities": 2, "IT": 1.5, "Logistics": 2.5, "Office": 1.2, "Marketing": 1,
          "Services": 1.3}
TERMS = {"Raw materials": 30, "Services": 30}
RECURRING = {"Westbrook Properties LLC": (14250.00, 1), "Lumen Cloud Software": (2180.00, 1),
             "Sentinel Security Systems": (3400.00, 15)}
CUSTOMERS = ["DELMAR AUTOMOTIVE", "KELLER AGRICULTURAL EQUIPMENT", "NORTHGATE RAIL SYSTEMS",
             "COASTAL MARINE ENGINES", "PRAIRIE WIND TURBINES", "VALLEY MEDICAL DEVICES",
             "RIDGELINE HVAC MANUFACTURING"]
ROUTING = ["990100017", "990200024", "990300031", "990400048"]
STREETS = ["MAPLE", "OAK", "CEDAR", "ELM", "PINE", "LAKE", "HILL", "RIVER", "PARK", "WASHINGTON", "LINCOLN",
           "FRANKLIN", "JEFFERSON", "HIGHLAND", "SUNSET", "RIDGE", "MEADOW", "FOREST", "SPRING", "CHURCH"]
SUFFIXES = ["ST", "AVE", "RD", "DR", "LN", "CT", "BLVD", "WAY"]
CITIES = [("DAYTON", "OH", "454"), ("COLUMBUS", "OH", "432"), ("CINCINNATI", "OH", "452"),
          ("SPRINGFIELD", "OH", "455"), ("TOLEDO", "OH", "436"), ("AKRON", "OH", "443")]

_accounts: set[str] = set()
_addresses: set[str] = set()


def bank_account() -> str:
    while True:
        acct = f"{rng.choice(ROUTING)}-{rng.randrange(10**9, 10**10)}"
        if acct not in _accounts:
            _accounts.add(acct)
            return acct


def street_address() -> str:
    while True:
        city, state, zip3 = rng.choice(CITIES)
        a = f"{rng.randrange(10, 9999)} {rng.choice(STREETS)} {rng.choice(SUFFIXES)}, {city}, {state} {zip3}{rng.randrange(10, 99)}"
        if a not in _addresses:
            _addresses.add(a)
            return a


def next_business_day(d: date) -> date:
    while d.weekday() >= 5:
        d += timedelta(days=1)
    return d


def add_business_days(d: date, n: int) -> date:
    while n > 0:
        d += timedelta(days=1)
        if d.weekday() < 5:
            n -= 1
    return d


def business_days(start: date, end: date) -> list[date]:
    out, d = [], start
    while d <= end:
        if d.weekday() < 5:
            out.append(d)
        d += timedelta(days=1)
    return out


def approver(amount: float) -> str:
    return SANDRA if amount < AP_LIMIT else CONTROLLER if amount < CFO_LIMIT else CFO


def initials(name: str) -> str:
    words = [w for w in name.replace("&", " ").replace("-", " ").split() if w[0].isalpha()]
    return "".join(w[0] for w in words[:3]).upper()


def money(x: float) -> str:
    return f"{x:.2f}"


def build():
    # --- people ---------------------------------------------------------------------------------
    employees = []
    taken_names = {p[1] for p in KEY_PEOPLE}
    for eid, name, dept, title in KEY_PEOPLE:
        employees.append({"employee_id": eid, "name": name, "department": dept, "title": title,
                          "hire_date": date(rng.randrange(2012, 2024), rng.randrange(1, 13), rng.randrange(1, 28)),
                          "status": "Active", "bank_account": bank_account(), "home_address": street_address()})
    key_ids = {p[0] for p in KEY_PEOPLE}
    dept_names = list(DEPTS)
    weights = [DEPTS[d][1] for d in dept_names]
    n = 1003
    while len(employees) < 180:
        eid = f"E-{n}"
        n += 1
        if eid in key_ids:
            continue
        while True:
            name = f"{rng.choice(FIRST)} {rng.choice(LAST)}"
            if name not in taken_names:
                taken_names.add(name)
                break
        dept = rng.choices(dept_names, weights)[0]
        employees.append({"employee_id": eid, "name": name, "department": dept, "title": rng.choice(DEPTS[dept][0]),
                          "hire_date": date(rng.randrange(2012, 2026), rng.randrange(1, 13), rng.randrange(1, 28)),
                          "status": "Active", "bank_account": bank_account(), "home_address": street_address()})
    emp = {e["employee_id"]: e for e in employees}

    # --- vendor master ----------------------------------------------------------------------------
    vendors, by_name = [], {}
    for i, (name, category) in enumerate(VENDORS):
        v = {"vendor_id": f"V-{2001 + i}", "name": name, "category": category,
             "tax_id": f"99-{rng.randrange(1000000, 9999999)}", "bank_account": bank_account(),
             "address": street_address(),
             "created_date": date(2020, 1, 1) + timedelta(days=rng.randrange(0, 2190)),
             "created_by": SANDRA if rng.random() < 0.75 else DEREK, "status": "Active",
             "coi_disclosed": "no", "coi_approved_by": "", "coi_note": ""}
        vendors.append(v)
        by_name[name] = v
    for name in ("Kessler Steel Supply", "Tri-County Electrical", "Summit Office Supply", "Brennan Packaging Co."):
        by_name[name]["created_by"] = SANDRA

    apex, northline, ruiz = by_name["Apex Facility Services LLC"], by_name["Northline Logistics Group"], by_name["Ruiz Translation Services"]
    # Planted D2: a vendor paid into the AP specialist's own bank account, set up by the same person.
    apex.update(bank_account=emp[DEREK]["bank_account"], address="PO BOX 4471, DAYTON, OH 45401",
                created_date=date(2026, 5, 14), created_by=DEREK)
    # Planted D5: a brand-new vendor, a large first payment two weeks later.
    northline.update(created_date=date(2026, 9, 2), created_by=DEREK)
    # Decoy B: an employee who is also a contractor, disclosed and approved by the Controller.
    ruiz.update(address=emp[ELENA]["home_address"], created_date=date(2025, 11, 4), created_by=SANDRA,
                coi_disclosed="yes", coi_approved_by=CONTROLLER,
                coi_note="Conflict of interest disclosed 2025-11-03 by Elena Ruiz; approved by the Controller.")

    # --- invoices -------------------------------------------------------------------------------
    invoices: list[dict] = []
    counters = {v["name"]: rng.randrange(1000, 60000) for v in vendors}
    used_numbers: set[str] = set()
    used_vendor_day: set[tuple[str, date]] = set()
    used_vendor_amount: set[tuple[str, float]] = set()

    def vendor_invoice_no(v):
        while True:
            counters[v["name"]] += rng.randrange(1, 40)
            no = f"{initials(v['name'])}-{counters[v['name']]}"
            if no not in used_numbers:
                used_numbers.add(no)
                return no

    def add_invoice(v, d, amount, requested_by, *, number=None, approved_by=None, approved_date=None,
                    po=None, memo="", pay_on=None, method=None):
        inv = {"vendor": v, "invoice_no": number or vendor_invoice_no(v), "invoice_date": d,
               "amount": round(amount, 2), "po_number": po or "", "requested_by": requested_by,
               "approved_by": approved_by or approver(amount),
               "approved_date": approved_date or add_business_days(d, rng.randrange(0, 3)),
               "memo": memo, "pay_on": pay_on, "method": method}
        if number:
            used_numbers.add(number)
        used_vendor_day.add((v["vendor_id"], d))
        used_vendor_amount.add((v["vendor_id"], inv["amount"]))
        invoices.append(inv)
        return inv

    # Planted cases first, so random invoices cannot collide with them.
    kessler, tri, summit, brennan = (by_name[n] for n in ("Kessler Steel Supply", "Tri-County Electrical",
                                                          "Summit Office Supply", "Brennan Packaging Co."))
    # D1: the same Kessler invoice paid twice, the second time as a resubmitted copy.
    add_invoice(kessler, date(2026, 7, 27), 18450.00, JASON, number="KSS-20931", approved_date=date(2026, 7, 28),
                po="PO-5214", pay_on=date(2026, 8, 4), method="WIRE")
    add_invoice(kessler, date(2026, 8, 3), 18450.00, JASON, number="KSS-20931-R", approved_date=date(2026, 8, 5),
                po="PO-5214", memo="Resubmitted", pay_on=date(2026, 8, 11), method="WIRE")
    # D2: five invoices to Apex, each just under the approval limit.
    for d, amt, no in [(date(2026, 7, 8), 9450.00, "AFS-1001"), (date(2026, 7, 29), 9720.00, "AFS-1002"),
                       (date(2026, 8, 19), 9380.00, "AFS-1003"), (date(2026, 9, 2), 9600.00, "AFS-1004"),
                       (date(2026, 9, 10), 9650.00, "AFS-1005")]:
        add_invoice(apex, d, amt, DEREK, number=no, memo="Facility maintenance", method="ACH")
    # D3: one purchase split into three invoices on one day, each under the $10,000 limit.
    for amt, no in [(9800.00, "TCE-5512"), (9650.00, "TCE-5513"), (9900.00, "TCE-5514")]:
        add_invoice(tri, date(2026, 9, 9), amt, MARCUS, number=no, approved_date=date(2026, 9, 10), method="ACH")
    # D5: Northline's first invoice, approved by the CFO, paid 13 days after the vendor was created.
    add_invoice(northline, date(2026, 9, 4), 38000.00, DEREK, number="NLG-0001", approved_date=date(2026, 9, 8),
                memo="Expedited freight, Q3", pay_on=date(2026, 9, 15), method="WIRE")
    # D6: an invoice approved by the same person who requested it.
    add_invoice(summit, date(2026, 8, 12), 6240.00, PRIYA, number="SOS-88213", approved_by=PRIYA,
                approved_date=date(2026, 8, 12), memo="Office furniture", method="ACH")
    # Decoy A: a real split shipment. Same amount, a week apart, which looks like a duplicate.
    add_invoice(brennan, date(2026, 9, 8), 4200.00, OMAR, number="BPC-7781", po="PO-5531",
                memo="Split shipment 1 of 2 (PO-5531)", method="ACH")
    add_invoice(brennan, date(2026, 9, 15), 4200.00, OMAR, number="BPC-7782", po="PO-5531",
                memo="Split shipment 2 of 2 (PO-5531)", method="ACH")
    # Decoy B: ordinary invoices from the disclosed contractor.
    add_invoice(ruiz, date(2026, 7, 21), 1850.00, GRACE, memo="Manual translation, German")
    add_invoice(ruiz, date(2026, 9, 1), 2340.00, GRACE, memo="Manual translation, Spanish")

    # Recurring monthly charges: identical amounts about 30 days apart, which a good check must not flag.
    for name, (amount, dom) in RECURRING.items():
        v = by_name[name]
        for month in (7, 8, 9):
            d = next_business_day(date(2026, month, dom))
            add_invoice(v, d, amount, REQUESTER[v["category"]], memo="Monthly", pay_on=add_business_days(d, 3),
                              method="ACH")
    planted_vendors = {apex["vendor_id"], northline["vendor_id"]}

    # Clean background activity.
    pool = [v for v in vendors if v["vendor_id"] not in planted_vendors and v["name"] not in RECURRING]
    pool_w = [VOLUME[v["category"]] for v in pool]
    for d in business_days(Q_START, Q_END):
        for _ in range(rng.randrange(10, 17)):
            for _attempt in range(50):
                v = rng.choices(pool, pool_w)[0]
                if (v["vendor_id"], d) in used_vendor_day:
                    continue
                if v["category"] == "Raw materials" and rng.random() < 0.03:
                    amount = rng.uniform(26000, 58000)
                else:
                    amount = math.exp(rng.gauss(math.log(TYPICAL[v["category"]]), 0.75))
                amount = round(min(max(amount, 85.0), 24000.0) if amount < 26000 else amount, 2)
                if (v["vendor_id"], amount) in used_vendor_amount:
                    continue
                po = f"PO-{rng.randrange(4000, 6000)}" if v["category"] in ("Raw materials", "Logistics", "Services") else ""
                add_invoice(v, d, amount, REQUESTER[v["category"]], po=po)
                break

    invoices.sort(key=lambda i: (i["invoice_date"], i["vendor"]["vendor_id"], i["invoice_no"]))
    for k, inv in enumerate(invoices):
        inv["invoice_id"] = f"INV-{100001 + k}"

    # --- payments -------------------------------------------------------------------------------
    payments = []
    for inv in invoices:
        v = inv["vendor"]
        paid = inv["pay_on"] or next_business_day(inv["invoice_date"] + timedelta(days=TERMS.get(v["category"], 15)))
        if paid > Q_END:
            inv["status"] = "Approved"
            continue
        inv["status"] = "Paid"
        method = inv["method"] or (("WIRE" if rng.random() < 0.6 else "ACH") if inv["amount"] >= AP_LIMIT
                                   else ("ACH" if rng.random() < 0.85 else "CHECK"))
        payments.append({"invoice": inv, "vendor_id": v["vendor_id"], "amount": inv["amount"], "paid_date": paid,
                         "method": method, "bank_account": v["bank_account"]})
    payments.sort(key=lambda p: (p["paid_date"], p["invoice"]["invoice_id"]))
    for k, p in enumerate(payments):
        p["payment_id"] = f"PAY-{300001 + k}"

    # --- bank feed ------------------------------------------------------------------------------
    txns = []
    vend_by_id = {v["vendor_id"]: v for v in vendors}
    for p in payments:
        v = vend_by_id[p["vendor_id"]]
        posted = p["paid_date"] if p["method"] != "CHECK" else add_business_days(p["paid_date"], rng.randrange(3, 7))
        if posted > Q_END:
            continue  # an outstanding check: not cleared by quarter end
        txns.append({"posted_date": posted, "direction": "debit", "txn_type": p["method"], "amount": p["amount"],
                     "counterparty_name": v["name"].upper(), "counterparty_account": v["bank_account"],
                     "reference": p["payment_id"], "description": f"{p['method']} DEBIT {v['name'].upper()}"})
        if p["method"] == "WIRE":
            txns.append({"posted_date": posted, "direction": "debit", "txn_type": "FEE", "amount": 35.00,
                         "counterparty_name": "FIRST HARBOR BANK", "counterparty_account": "",
                         "reference": f"FEE {p['payment_id']}", "description": "OUTGOING WIRE FEE"})
    # D4: a wire out of the bank account with no payment behind it in the ERP.
    jlr_account = bank_account()
    txns.append({"posted_date": date(2026, 8, 21), "direction": "debit", "txn_type": "WIRE", "amount": 12600.00,
                 "counterparty_name": "JLR HOLDINGS LLC", "counterparty_account": jlr_account,
                 "reference": "INV-0821", "description": "WIRE OUT JLR HOLDINGS LLC CONSULTING SVCS"})
    txns.append({"posted_date": date(2026, 8, 21), "direction": "debit", "txn_type": "FEE", "amount": 35.00,
                 "counterparty_name": "FIRST HARBOR BANK", "counterparty_account": "", "reference": "FEE INV-0821",
                 "description": "OUTGOING WIRE FEE"})
    payday = date(2026, 7, 10)
    while payday <= Q_END:
        txns.append({"posted_date": payday, "direction": "debit", "txn_type": "PAYROLL",
                     "amount": round(rng.uniform(405000, 420000), 2), "counterparty_name": "COREPAY PAYROLL SERVICES",
                     "counterparty_account": "", "reference": f"PR-{payday.isoformat()}", "description": "PAYROLL FUNDING"})
        payday += timedelta(days=14)
    for month in (7, 8, 9):
        last = date(2026, month + 1, 1) - timedelta(days=1) if month < 12 else date(2026, 12, 31)
        while last.weekday() >= 5:
            last -= timedelta(days=1)
        txns.append({"posted_date": last, "direction": "debit", "txn_type": "FEE", "amount": 250.00,
                     "counterparty_name": "FIRST HARBOR BANK", "counterparty_account": "",
                     "reference": f"SVC-{month:02d}", "description": "MONTHLY SERVICE FEE"})
    for d in business_days(Q_START, Q_END):
        for _ in range(rng.randrange(2, 5)):
            customer = rng.choice(CUSTOMERS)
            txns.append({"posted_date": d, "direction": "credit", "txn_type": "DEPOSIT",
                         "amount": round(rng.uniform(8000, 140000), 2), "counterparty_name": customer,
                         "counterparty_account": "", "reference": f"REMIT {rng.randrange(100000, 999999)}",
                         "description": f"ACH CREDIT {customer}"})
    txns.sort(key=lambda t: (t["posted_date"], t["direction"], t["reference"]))
    for k, t in enumerate(txns):
        t["txn_id"] = f"TXN-{500001 + k}"

    return employees, vendors, invoices, payments, txns, jlr_account


def write_csv(path: Path, rows: list[dict], fields: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        for r in rows:
            w.writerow({k: (r[k].isoformat() if isinstance(r[k], date) else money(r[k]) if isinstance(r[k], float) else r[k])
                        for k in fields})


def main() -> None:
    employees, vendors, invoices, payments, txns, jlr_account = build()
    write_csv(OUT / "hr" / "employees.csv", employees,
              ["employee_id", "name", "department", "title", "hire_date", "status", "bank_account", "home_address"])
    write_csv(OUT / "vendors" / "vendors.csv", vendors,
              ["vendor_id", "name", "category", "tax_id", "bank_account", "address", "created_date", "created_by",
               "status", "coi_disclosed", "coi_approved_by", "coi_note"])
    inv_rows = [{**i, "vendor_id": i["vendor"]["vendor_id"]} for i in invoices]
    write_csv(OUT / "erp" / "invoices.csv", inv_rows,
              ["invoice_id", "vendor_id", "invoice_no", "invoice_date", "amount", "po_number", "requested_by",
               "approved_by", "approved_date", "status", "memo"])
    pay_rows = [{**p, "invoice_id": p["invoice"]["invoice_id"]} for p in payments]
    write_csv(OUT / "erp" / "payments.csv", pay_rows,
              ["payment_id", "invoice_id", "vendor_id", "amount", "paid_date", "method", "bank_account"])
    write_csv(OUT / "erp" / "policies.csv", [
        {"policy": "ap_approval_limit", "value": AP_LIMIT, "description": "Invoices at or above this need the Controller"},
        {"policy": "cfo_approval_limit", "value": CFO_LIMIT, "description": "Invoices at or above this need the CFO"},
        {"policy": "new_vendor_days", "value": NEW_VENDOR_DAYS, "description": "A vendor younger than this is new"},
        {"policy": "duplicate_window_days", "value": DUPLICATE_WINDOW,
         "description": "Two equal payments to one vendor within this many days are a possible duplicate"},
    ], ["policy", "value", "description"])
    write_csv(OUT / "bank" / "transactions.csv", txns,
              ["txn_id", "posted_date", "direction", "txn_type", "amount", "counterparty_name", "counterparty_account",
               "reference", "description"])

    vid = {v["name"]: v["vendor_id"] for v in vendors}
    pay_by_inv = {p["invoice"]["invoice_no"]: p["payment_id"] for p in payments}
    jlr_txn = next(t["txn_id"] for t in txns if t["counterparty_account"] == jlr_account)
    key = {
        "company": "Brightwater Components Inc.", "period": "2026-07-01 to 2026-09-30",
        "frauds": [
            {"detector": "D1", "vendor_id": vid["Kessler Steel Supply"], "amount": 18450.00,
             "payment_ids": [pay_by_inv["KSS-20931"], pay_by_inv["KSS-20931-R"]]},
            {"detector": "D2", "vendor_id": vid["Apex Facility Services LLC"], "employee_id": DEREK, "amount": 47800.00},
            {"detector": "D3", "vendor_id": vid["Tri-County Electrical"], "employee_id": MARCUS, "amount": 29350.00},
            {"detector": "D4", "txn_id": jlr_txn, "amount": 12600.00},
            {"detector": "D5", "vendor_id": vid["Northline Logistics Group"], "employee_id": DEREK, "amount": 38000.00},
            {"detector": "D6", "vendor_id": vid["Summit Office Supply"], "employee_id": PRIYA, "amount": 6240.00},
        ],
        "decoys": [
            {"detector": "D1", "vendor_id": vid["Brennan Packaging Co."], "amount": 4200.00,
             "expected": "flagged on the first run; once the controller marks it expected, remembered and suppressed"},
            {"detector": "D2", "vendor_id": vid["Ruiz Translation Services"], "employee_id": ELENA,
             "expected": "cleared by the vendor master's disclosed, Controller-approved conflict of interest"},
        ],
        "connected": {"employee_id": DEREK, "vendor_ids": [vid["Apex Facility Services LLC"], vid["Northline Logistics Group"]]},
    }
    (HERE / "answer_key.json").write_text(json.dumps(key, indent=2), encoding="utf-8")
    print(f"wrote {len(employees)} employees, {len(vendors)} vendors, {len(invoices)} invoices, "
          f"{len(payments)} payments, {len(txns)} bank transactions to {OUT}")


if __name__ == "__main__":
    main()

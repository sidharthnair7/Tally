"""Load the four exports into four separate Postgres databases, so Zetaris can register each one as its own
datasource and query them in place (the way a real ERP, vendor master, HR system and bank feed are separate).

Uses the Postgres container that ships with the local Zetaris bundle (or any Postgres container):

    python zetaris/load_postgres.py --container <postgres container> --user <postgres user>
"""
import argparse
import subprocess
from pathlib import Path

SOURCES = Path(__file__).resolve().parent.parent / "data" / "sources"
DDL = {
    "erp": {
        "invoices": "invoice_id text primary key, vendor_id text, invoice_no text, invoice_date date, amount numeric(12,2), "
                    "po_number text, requested_by text, approved_by text, approved_date date, status text, memo text",
        "payments": "payment_id text primary key, invoice_id text, vendor_id text, amount numeric(12,2), paid_date date, "
                    "method text, bank_account text",
        "policies": "policy text primary key, value numeric, description text",
    },
    "vendors": {
        "vendors": "vendor_id text primary key, name text, category text, tax_id text, bank_account text, address text, "
                   "created_date date, created_by text, status text, coi_disclosed text, coi_approved_by text, coi_note text",
    },
    "hr": {
        "employees": "employee_id text primary key, name text, department text, title text, hire_date date, status text, "
                     "bank_account text, home_address text",
    },
    "bank": {
        "transactions": "txn_id text primary key, posted_date date, direction text, txn_type text, amount numeric(12,2), "
                        "counterparty_name text, counterparty_account text, reference text, description text",
    },
}


def psql(container: str, user: str, db: str, sql: str, stdin=None) -> None:
    subprocess.run(["docker", "exec", "-i", container, "psql", "-v", "ON_ERROR_STOP=1", "-U", user, "-d", db, "-c", sql],
                   stdin=stdin, check=True)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--container", required=True)
    ap.add_argument("--user", default="postgres")
    ap.add_argument("--prefix", default="tally_")
    args = ap.parse_args()
    for system, tables in DDL.items():
        db = f"{args.prefix}{system}"
        exists = subprocess.run(["docker", "exec", args.container, "psql", "-U", args.user, "-d", "postgres", "-tAc",
                                 f"SELECT 1 FROM pg_database WHERE datname = '{db}'"], capture_output=True, text=True)
        if exists.stdout.strip() != "1":
            psql(args.container, args.user, "postgres", f"CREATE DATABASE {db}")
        for table, cols in tables.items():
            psql(args.container, args.user, db, f"DROP TABLE IF EXISTS {table}; CREATE TABLE {table} ({cols})")
            with (SOURCES / system / f"{table}.csv").open("rb") as f:
                psql(args.container, args.user, db, f"\\copy {table} FROM STDIN WITH (FORMAT csv, HEADER true)", stdin=f)
            print(f"loaded {db}.{table}")


if __name__ == "__main__":
    main()

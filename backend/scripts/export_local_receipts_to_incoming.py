from __future__ import annotations

import argparse
import json
import sqlite3
from dataclasses import dataclass
from pathlib import Path


@dataclass
class LocalReceipt:
    receipt_id: str
    merchant: str
    expense_date: str
    total_amount_raw: str


def _read_receipts(conn: sqlite3.Connection, limit: int | None) -> list[LocalReceipt]:
    query = """
        SELECT receiptId, merchant, expenseDate, totalAmountRaw
        FROM local_receipts
        ORDER BY savedAtMillis DESC
    """
    params: tuple[int, ...] = ()
    if limit is not None:
        query += " LIMIT ?"
        params = (limit,)
    rows = conn.execute(query, params).fetchall()
    return [
        LocalReceipt(
            receipt_id=str(row[0]),
            merchant=str(row[1]),
            expense_date=str(row[2]),
            total_amount_raw=str(row[3]),
        )
        for row in rows
    ]


def _read_items(conn: sqlite3.Connection, receipt_id: str) -> list[tuple[str, str | None]]:
    rows = conn.execute(
        """
        SELECT description, amountRaw
        FROM local_receipt_items
        WHERE receiptId = ?
        ORDER BY position ASC
        """,
        (receipt_id,),
    ).fetchall()
    return [(str(row[0]), str(row[1]) if row[1] is not None else None) for row in rows]


def _build_ocr_lines(receipt: LocalReceipt, items: list[tuple[str, str | None]]) -> list[str]:
    lines: list[str] = [
        receipt.merchant.strip(),
        receipt.expense_date.strip(),
    ]
    for description, amount_raw in items:
        desc = description.strip()
        if not desc:
            continue
        if amount_raw and amount_raw.strip():
            lines.append(f"{desc} {amount_raw.strip()}")
        else:
            lines.append(desc)
    lines.append(f"TOTAL {receipt.total_amount_raw.strip()}")
    return [line for line in lines if line]


def export_to_incoming(
    *,
    sqlite_db: Path,
    output_jsonl: Path,
    limit: int | None,
    locale: str,
    currency_hint: str,
) -> int:
    if not sqlite_db.exists():
        print(f"[receipt-export] sqlite db not found: {sqlite_db}")
        return 2

    conn = sqlite3.connect(str(sqlite_db))
    try:
        receipts = _read_receipts(conn, limit=limit)
        rows: list[dict[str, object]] = []
        for receipt in receipts:
            items = _read_items(conn, receipt.receipt_id)
            row = {
                "id": f"local-{receipt.receipt_id}",
                "request": {
                    "ocr_lines": _build_ocr_lines(receipt, items),
                    "locale": locale,
                    "currency_hint": currency_hint,
                },
                "reviewed": {
                    "total_amount": receipt.total_amount_raw.strip(),
                    "total_amount_uncertain": False,
                    "expected_warning_codes": [],
                },
                "meta": {
                    "source": "android-room-local_receipts",
                    "receipt_id": receipt.receipt_id,
                },
            }
            rows.append(row)
    finally:
        conn.close()

    output_jsonl.parent.mkdir(parents=True, exist_ok=True)
    payload = "\n".join(
        json.dumps(row, separators=(",", ":"), ensure_ascii=True)
        for row in rows
    )
    output_jsonl.write_text(payload + ("\n" if payload else ""), encoding="utf-8")
    print(f"[receipt-export] exported={len(rows)} output={output_jsonl}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Export Android local reviewed receipts into eval incoming JSONL.",
    )
    parser.add_argument(
        "--sqlite-db",
        required=True,
        help="Path to Android Room SQLite DB containing local_receipts tables.",
    )
    parser.add_argument(
        "--output",
        default="evals/incoming/new_scans.jsonl",
        help="Output JSONL path for staging ingestion.",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help="Optional max receipt count to export (newest first).",
    )
    parser.add_argument("--locale", default="en-PH", help="Locale attached to exported requests.")
    parser.add_argument(
        "--currency-hint",
        default="PHP",
        help="Currency hint attached to exported requests.",
    )
    args = parser.parse_args()
    return export_to_incoming(
        sqlite_db=Path(args.sqlite_db),
        output_jsonl=Path(args.output),
        limit=args.limit,
        locale=args.locale,
        currency_hint=args.currency_hint,
    )


if __name__ == "__main__":
    raise SystemExit(main())

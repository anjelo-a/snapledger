from __future__ import annotations

import argparse
import hashlib
import json
import re
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

PHONE_RE = re.compile(r"(?:\+?\d{1,3}[\s-]?)?(?:\(?\d{2,4}\)?[\s-]?){2,4}\d{3,4}")
CARD_RE = re.compile(r"\b(?:\d[ -]*?){13,19}\b")
EMAIL_RE = re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b")
URL_RE = re.compile(r"\b(?:https?://|www\.)\S+\b", re.IGNORECASE)
ADDRESS_RE = re.compile(
    r"\b\d{1,5}\s+[A-Za-z0-9 .,'-]+\s(?:street|st|avenue|ave|road|rd|blvd|boulevard)\b",
    re.IGNORECASE,
)

PII_PATTERNS = (
    ("phone", PHONE_RE, "[REDACTED_PHONE]"),
    ("card", CARD_RE, "[REDACTED_CARD]"),
    ("email", EMAIL_RE, "[REDACTED_EMAIL]"),
    ("url", URL_RE, "[REDACTED_URL]"),
    ("address", ADDRESS_RE, "[REDACTED_ADDRESS]"),
)


@dataclass
class IngestResult:
    ingested: int = 0
    duplicates: int = 0


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    rows: list[dict[str, Any]] = []
    for idx, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw_line.strip()
        if not line:
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"Invalid JSONL at line {idx} in {path}: {exc}") from exc
        if not isinstance(row, dict):
            raise ValueError(f"Invalid JSONL at line {idx} in {path}: expected object")
        rows.append(row)
    return rows


def _write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = "\n".join(json.dumps(row, separators=(",", ":"), ensure_ascii=True) for row in rows)
    path.write_text(payload + ("\n" if payload else ""), encoding="utf-8")


def _redact_text(value: str) -> tuple[str, list[str]]:
    redacted = value
    hits: list[str] = []
    for label, pattern, replacement in PII_PATTERNS:
        if pattern.search(redacted):
            redacted = pattern.sub(replacement, redacted)
            hits.append(label)
    return redacted, sorted(set(hits))


def _sanitize_request(request: dict[str, Any]) -> tuple[dict[str, Any], list[str]]:
    sanitized = dict(request)
    ocr_lines = sanitized.get("ocr_lines")
    pii_hits: list[str] = []
    if isinstance(ocr_lines, list):
        clean_lines: list[str] = []
        for line in ocr_lines:
            if not isinstance(line, str):
                clean_lines.append(str(line))
                continue
            redacted, hits = _redact_text(line)
            pii_hits.extend(hits)
            clean_lines.append(redacted)
        sanitized["ocr_lines"] = clean_lines
    return sanitized, sorted(set(pii_hits))


def _stable_fingerprint(request: dict[str, Any], total_amount: Any) -> str:
    material = {
        "request": request,
        "total_amount": total_amount,
    }
    encoded = json.dumps(
        material,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=True,
    ).encode("utf-8")
    digest = hashlib.sha256(encoded).hexdigest()
    return digest


def _to_staging_row(raw: dict[str, Any], source: str) -> dict[str, Any]:
    request = raw.get("request")
    if not isinstance(request, dict):
        raise ValueError("Each input row requires object field: request")

    ground_truth = raw.get("ground_truth")
    reviewed = raw.get("reviewed")
    if not isinstance(ground_truth, dict):
        ground_truth = {}
    if not isinstance(reviewed, dict):
        reviewed = {}

    total_amount = reviewed.get("total_amount", ground_truth.get("total_amount"))
    total_uncertain = bool(
        reviewed.get("total_amount_uncertain", ground_truth.get("total_amount_uncertain", False))
    )
    expected_warning_codes = reviewed.get(
        "expected_warning_codes", ground_truth.get("expected_warning_codes", [])
    )
    if not isinstance(expected_warning_codes, list):
        expected_warning_codes = []

    sanitized_request, pii_hits = _sanitize_request(request)
    fingerprint = _stable_fingerprint(sanitized_request, total_amount)

    now = datetime.now(tz=UTC).isoformat()
    row_id = raw.get("id")
    if not isinstance(row_id, str) or not row_id.strip():
        row_id = f"stage-{datetime.now(tz=UTC).strftime('%Y%m%dT%H%M%SZ')}-{fingerprint[:10]}"

    return {
        "id": row_id,
        "source": source,
        "ingested_at": now,
        "approved": False,
        "promoted": False,
        "fingerprint": fingerprint,
        "pii_detected": bool(pii_hits),
        "pii_hits": pii_hits,
        "request": sanitized_request,
        "ground_truth": {
            "total_amount": total_amount,
            "total_amount_uncertain": total_uncertain,
            "expected_warning_codes": expected_warning_codes,
        },
    }


def ingest(*, input_path: Path, staging_path: Path, source: str) -> int:
    incoming = _read_jsonl(input_path)
    staging = _read_jsonl(staging_path)

    existing_fingerprints = {
        row.get("fingerprint") for row in staging if isinstance(row.get("fingerprint"), str)
    }

    result = IngestResult()
    for raw in incoming:
        row = _to_staging_row(raw, source)
        fingerprint = row["fingerprint"]
        if fingerprint in existing_fingerprints:
            result.duplicates += 1
            continue
        staging.append(row)
        existing_fingerprints.add(fingerprint)
        result.ingested += 1

    _write_jsonl(staging_path, staging)
    print(
        "[staging-ingest] "
        f"added={result.ingested} "
        f"duplicates_skipped={result.duplicates} "
        f"total={len(staging)}"
    )
    return 0


def approve(*, staging_path: Path, ids: list[str], approve_all_clean: bool) -> int:
    staging = _read_jsonl(staging_path)
    updated = 0

    for row in staging:
        row_id = row.get("id")
        if not isinstance(row_id, str):
            continue
        if approve_all_clean:
            if not bool(row.get("pii_detected")) and not bool(row.get("promoted")):
                row["approved"] = True
                updated += 1
            continue
        if row_id in ids:
            row["approved"] = True
            updated += 1

    _write_jsonl(staging_path, staging)
    print(f"[staging-approve] approved={updated}")
    return 0


def _to_eval_row(staging_row: dict[str, Any], target_prefix: str, sequence: int) -> dict[str, Any]:
    tier = staging_row.get("tier")
    if not isinstance(tier, str) or not tier:
        tier = "real"
    return {
        "id": f"{target_prefix}-{sequence:02d}",
        "tier": tier,
        "request": staging_row["request"],
        "ground_truth": staging_row["ground_truth"],
    }


def promote(
    *,
    staging_path: Path,
    target_dataset_path: Path,
    target_prefix: str,
    limit: int,
) -> int:
    staging = _read_jsonl(staging_path)
    target = _read_jsonl(target_dataset_path)

    seq = len(target) + 1
    promoted_count = 0
    blocked_pii = 0

    for row in staging:
        if promoted_count >= limit:
            break
        if bool(row.get("promoted")):
            continue
        if not bool(row.get("approved")):
            continue
        if bool(row.get("pii_detected")):
            blocked_pii += 1
            continue

        eval_row = _to_eval_row(row, target_prefix=target_prefix, sequence=seq)
        seq += 1
        target.append(eval_row)
        row["promoted"] = True
        row["promoted_at"] = datetime.now(tz=UTC).isoformat()
        promoted_count += 1

    _write_jsonl(target_dataset_path, target)
    _write_jsonl(staging_path, staging)
    print(
        "[staging-promote] "
        f"promoted={promoted_count} blocked_pii={blocked_pii} target_total={len(target)}"
    )
    return 0


def stats(*, staging_path: Path) -> int:
    staging = _read_jsonl(staging_path)
    total = len(staging)
    approved = sum(1 for row in staging if bool(row.get("approved")))
    promoted = sum(1 for row in staging if bool(row.get("promoted")))
    pii = sum(1 for row in staging if bool(row.get("pii_detected")))
    pending = sum(1 for row in staging if not bool(row.get("promoted")))
    print(
        "[staging-stats] "
        f"total={total} approved={approved} promoted={promoted} "
        f"pending={pending} pii_detected={pii}"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Manage staging-to-eval pipeline for receipt extraction datasets."
    )
    parser.add_argument(
        "--staging",
        default="evals/staging/receipt_staging.jsonl",
        help="Path to staging JSONL file.",
    )

    sub = parser.add_subparsers(dest="cmd", required=True)

    ingest_parser = sub.add_parser(
        "ingest",
        help="Ingest raw rows into staging with redaction/dedupe.",
    )
    ingest_parser.add_argument("--input", required=True, help="Path to incoming JSONL rows.")
    ingest_parser.add_argument(
        "--source",
        default="local-scan-export",
        help="Source label for audit.",
    )

    approve_parser = sub.add_parser("approve", help="Mark staging rows approved for promotion.")
    approve_parser.add_argument("--ids", default="", help="Comma-separated staging ids to approve.")
    approve_parser.add_argument(
        "--all-clean",
        action="store_true",
        help="Approve all non-PII unpromoted rows.",
    )

    promote_parser = sub.add_parser(
        "promote",
        help="Promote approved clean rows into eval dataset.",
    )
    promote_parser.add_argument(
        "--target",
        required=True,
        help="Path to target eval dataset JSONL (for example evals/receipt_full.jsonl).",
    )
    promote_parser.add_argument(
        "--prefix",
        default="real",
        help="ID prefix used for promoted rows.",
    )
    promote_parser.add_argument(
        "--limit",
        type=int,
        default=20,
        help="Maximum number of rows to promote per run.",
    )

    sub.add_parser("stats", help="Show staging summary.")

    args = parser.parse_args()
    staging_path = Path(args.staging)

    if args.cmd == "ingest":
        return ingest(input_path=Path(args.input), staging_path=staging_path, source=args.source)
    if args.cmd == "approve":
        ids = [item.strip() for item in args.ids.split(",") if item.strip()]
        return approve(staging_path=staging_path, ids=ids, approve_all_clean=args.all_clean)
    if args.cmd == "promote":
        return promote(
            staging_path=staging_path,
            target_dataset_path=Path(args.target),
            target_prefix=args.prefix,
            limit=args.limit,
        )
    return stats(staging_path=staging_path)


if __name__ == "__main__":
    raise SystemExit(main())

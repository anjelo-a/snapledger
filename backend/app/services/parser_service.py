from __future__ import annotations

import base64
import json
import logging
import random
import re
import time
from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal, InvalidOperation
from io import BytesIO
from typing import Generic, TypeVar

import httpx
from PIL import Image

from app.core.config import get_settings
from app.schemas.expense import (
    ExpenseItemWrite,
    ParsedReceiptCandidate,
    ParsedReceiptFieldConfidence,
    ReceiptProcessRequest,
)

logger = logging.getLogger(__name__)


class GeminiProcessError(Exception):
    def __init__(self, status_code: int, message: str) -> None:
        super().__init__(message)
        self.status_code = status_code


_GEMINI_ALLOWED_TOP_LEVEL_KEYS = {"merchant", "date", "subtotal", "tax", "total", "line_items"}
_INJECTION_PATTERNS = (
    "ignore previous",
    "system prompt",
    "developer message",
    "tool call",
    "execute",
    "instruction",
)
_MAX_TOTAL = Decimal("1000000.00")
_MIN_POSITIVE_AMOUNT = Decimal("0.01")

_AMOUNT_RE = re.compile(r"(?:[$₱]|PHP\s*)?\d+(?:,\d{3})*(?:\.\d{2})")
_ISO_DATE_RE = re.compile(r"\b\d{4}-\d{2}-\d{2}\b")
_SLASH_DATE_RE = re.compile(r"\b\d{1,2}[/-]\d{1,2}[/-]\d{2,4}\b")
_MONTH_DATE_RE = re.compile(
    r"\b(?:Jan|January|Feb|February|Mar|March|Apr|April|May|Jun|June|Jul|July|Aug|August|"
    r"Sep|Sept|September|Oct|October|Nov|November|Dec|December)\s+\d{1,2},?\s+\d{2,4}\b",
    re.IGNORECASE,
)

_TOTAL_KEYWORDS = ("grand total", "total due", "amount due", "balance due", "total")
_NON_TOTAL_KEYWORDS = ("subtotal", "tax", "vat", "tip", "change", "cash", "savings")
_NON_ITEM_KEYWORDS = (
    "subtotal",
    "total",
    "tax",
    "vat",
    "amount due",
    "balance due",
    "change",
    "cash",
    "credit",
    "debit",
    "visa",
    "mastercard",
    "receipt",
    "invoice",
    "order",
    "phone",
    "www",
    "http",
    "savings",
)


@dataclass(frozen=True)
class _NormalizedLine:
    index: int
    text: str


_T = TypeVar("_T")


@dataclass(frozen=True)
class _FieldSelection(Generic[_T]):
    value: _T | None
    confidence: float | None
    line_index: int | None
    warnings: list[str]
    warning_codes: list[str]


def parse_receipt(payload: ReceiptProcessRequest) -> ParsedReceiptCandidate:
    if payload.image_base64:
        return _parse_receipt_with_gemini(payload)

    ocr_lines = payload.ocr_lines or []
    return _parse_receipt_from_ocr_lines(ocr_lines)


def _parse_receipt_with_gemini(payload: ReceiptProcessRequest) -> ParsedReceiptCandidate:
    settings = get_settings()
    if not settings.gemini_api_key:
        return ParsedReceiptCandidate(
            warnings=["Gemini API key is not configured on the backend."],
            warning_codes=["gemini_api_key_missing"],
        )

    try:
        image_bytes = base64.b64decode(payload.image_base64, validate=False)
        image_bytes, mime_type = _prepare_image_for_gemini(image_bytes)
        response_text = _call_gemini_extract(
            image_bytes=image_bytes,
            mime_type=mime_type,
            locale=payload.locale,
            currency_hint=payload.currency_hint,
        )
        return _map_gemini_response_to_candidate(response_text)
    except TimeoutError as exc:
        raise GeminiProcessError(504, "Receipt extraction timed out.") from exc
    except httpx.HTTPStatusError as exc:
        status = exc.response.status_code
        if status == 429:
            raise GeminiProcessError(
                429,
                "Receipt extraction is rate-limited. Try again shortly.",
            ) from exc
        raise GeminiProcessError(502, "Receipt extraction upstream request failed.") from exc
    except Exception as exc:
        raise GeminiProcessError(502, f"Receipt extraction failed: {exc}") from exc


def _parse_receipt_from_ocr_lines(ocr_lines: list[str]) -> ParsedReceiptCandidate:
    lines, warnings, warning_codes = _normalize_ocr_lines(ocr_lines)
    if not lines:
        field_confidence = ParsedReceiptFieldConfidence(
            merchant=0.0,
            expense_date=0.0,
            total_amount=0.0,
            items=0.0,
        )
        return ParsedReceiptCandidate(
            warnings=warnings + ["No usable OCR lines remained after normalization."],
            warning_codes=warning_codes + ["normalized_lines_empty"],
            field_confidence=field_confidence,
        )

    merchant = _select_merchant(lines)
    expense_date = _select_expense_date(lines)
    total_amount = _select_total_amount(lines)
    items = _select_items(
        lines=lines,
        merchant_line_index=merchant.line_index,
        total_line_index=total_amount.line_index,
    )

    warnings.extend(merchant.warnings)
    warnings.extend(expense_date.warnings)
    warnings.extend(total_amount.warnings)
    warnings.extend(items.warnings)

    warning_codes.extend(merchant.warning_codes)
    warning_codes.extend(expense_date.warning_codes)
    warning_codes.extend(total_amount.warning_codes)
    warning_codes.extend(items.warning_codes)

    if merchant.value is None:
        warnings.append("Merchant could not be determined from OCR lines.")
        warning_codes.append("merchant_missing")
    if expense_date.value is None:
        warnings.append("Expense date could not be determined from OCR lines.")
        warning_codes.append("expense_date_missing")
    if total_amount.value is None:
        warnings.append("Total amount could not be determined from OCR lines.")
        warning_codes.append("total_amount_missing")

    field_confidence = ParsedReceiptFieldConfidence(
        merchant=merchant.confidence,
        expense_date=expense_date.confidence,
        total_amount=total_amount.confidence,
        items=items.confidence,
    )
    return ParsedReceiptCandidate(
        merchant=merchant.value,
        expense_date=expense_date.value,
        total_amount=total_amount.value,
        items=items.value,
        warnings=_dedupe_strings(warnings),
        warning_codes=_dedupe_strings(warning_codes),
        field_confidence=field_confidence,
    )


def _prepare_image_for_gemini(image_bytes: bytes) -> tuple[bytes, str]:
    image = Image.open(BytesIO(image_bytes))
    image = image.convert("RGB")
    max_dimension = max(image.width, image.height)
    if max_dimension > 1200:
        scale = 1200 / max_dimension
        resized = image.resize((int(image.width * scale), int(image.height * scale)))
        image = resized
    output = BytesIO()
    image.save(output, format="JPEG", optimize=True, quality=85)
    return output.getvalue(), "image/jpeg"


def _call_gemini_extract(
    *,
    image_bytes: bytes,
    mime_type: str,
    locale: str | None,
    currency_hint: str | None,
) -> str:
    settings = get_settings()
    prompt = (
        "Return JSON only: "
        "{merchant,date,subtotal,tax,total,line_items:[{name,amount}]}. "
        "Use null when uncertain. No extra keys or text. "
        f"Locale={locale or 'unknown'}; currency_hint={currency_hint or 'unknown'}."
    )
    endpoint = (
        f"https://generativelanguage.googleapis.com/v1beta/models/{settings.gemini_model}:generateContent"
        f"?key={settings.gemini_api_key}"
    )
    request_payload = {
        "contents": [
            {
                "parts": [
                    {"text": prompt},
                    {
                        "inline_data": {
                            "mime_type": mime_type,
                            "data": base64.b64encode(image_bytes).decode("utf-8"),
                        }
                    },
                ]
            }
        ],
        "generationConfig": {
            "responseMimeType": "application/json",
            "temperature": 0,
            "maxOutputTokens": 650,
        },
    }
    with httpx.Client(timeout=settings.gemini_timeout_seconds) as client:
        for attempt in range(2):
            try:
                response = client.post(endpoint, json=request_payload)
            except httpx.TimeoutException as exc:
                raise TimeoutError from exc
            if response.status_code == 429 and attempt == 0:
                logger.warning(
                    "gemini_receipt_429_retry attempt=%s backoff_seconds=%.2f",
                    attempt + 1,
                    2.5,
                )
                time.sleep(random.uniform(2.0, 3.0))
                continue
            response.raise_for_status()
            body = response.json()
            response_text = (
                body["candidates"][0]["content"]["parts"][0].get("text")
                if body.get("candidates")
                else "{}"
            )
            logger.info(
                "gemini_receipt_raw_response model=%s text_len=%s text=%s",
                settings.gemini_model,
                len(response_text or ""),
                _truncate_for_log(response_text),
            )
            return response_text
    return "{}"


def _map_gemini_response_to_candidate(response_text: str) -> ParsedReceiptCandidate:
    warnings: list[str] = []
    warning_codes: list[str] = []
    parsed = json.loads(response_text or "{}")
    if not isinstance(parsed, dict):
        return ParsedReceiptCandidate(
            warnings=["Gemini response was not a JSON object and was discarded."],
            warning_codes=["GEMINI_INVALID_PAYLOAD"],
        )

    unexpected_keys = set(parsed.keys()) - _GEMINI_ALLOWED_TOP_LEVEL_KEYS
    if unexpected_keys:
        logger.warning(
            "gemini_receipt_rejected reason=unexpected_keys keys=%s",
            sorted(unexpected_keys),
        )
        return ParsedReceiptCandidate(
            warnings=["Gemini response contained unexpected keys and was discarded."],
            warning_codes=["GEMINI_PROMPT_INJECTION_DETECTED"],
        )

    if _contains_injection_content(parsed):
        logger.warning("gemini_receipt_rejected reason=injection_like_content")
        return ParsedReceiptCandidate(
            warnings=[
                "Gemini response contained non-receipt "
                "instruction-like content and was discarded."
            ],
            warning_codes=["GEMINI_PROMPT_INJECTION_DETECTED"],
        )

    merchant_raw = parsed.get("merchant")
    merchant = str(merchant_raw).strip()[:160] if isinstance(merchant_raw, str) else None
    if merchant_raw is not None and merchant is None:
        warning_codes.append("GEMINI_INVALID_MERCHANT")
        warnings.append("Merchant failed validation and was nulled.")

    date_value = _coerce_iso_date(parsed.get("date"))
    if parsed.get("date") is not None and date_value is None:
        warning_codes.append("GEMINI_INVALID_DATE")
        warnings.append("Date failed validation and was nulled.")

    subtotal = _coerce_amount_field(parsed.get("subtotal"), allow_zero=True)
    tax = _coerce_amount_field(parsed.get("tax"), allow_zero=True)
    total = _coerce_amount_field(parsed.get("total"), allow_zero=False)
    if parsed.get("subtotal") is not None and subtotal is None:
        warning_codes.append("GEMINI_INVALID_SUBTOTAL")
        warnings.append("Subtotal failed validation and was nulled.")
    if parsed.get("tax") is not None and tax is None:
        warning_codes.append("GEMINI_INVALID_TAX")
        warnings.append("Tax failed validation and was nulled.")
    if parsed.get("total") is not None and total is None:
        warning_codes.append("GEMINI_INVALID_TOTAL")
        warnings.append("Total failed validation and was nulled.")

    items = []
    for item in parsed.get("line_items") or []:
        if not isinstance(item, dict):
            warning_codes.append("GEMINI_INVALID_LINE_ITEM")
            continue
        name = str(
            item.get("name")
            or item.get("description")
            or item.get("item")
            or ""
        ).strip()
        amount = _coerce_amount_field(
            item.get("amount")
            if "amount" in item
            else item.get("price")
            if "price" in item
            else item.get("total"),
            allow_zero=False,
        )
        if not name:
            continue
        if amount is None:
            continue
        items.append(ExpenseItemWrite(name=name[:160], amount=amount))

    items_total = sum((line.amount for line in items), Decimal("0.00"))
    if total is not None and items and items_total > Decimal("0.00"):
        diff_ratio = abs(total - items_total) / items_total
        if diff_ratio > Decimal("0.05"):
            warning_codes.append("TOTAL_MISMATCH")
            warnings.append("Total differs from sum of line items by more than 5%.")

    if merchant is None:
        warning_codes.append("merchant_missing")
    if date_value is None:
        warning_codes.append("expense_date_missing")
    if total is None:
        warning_codes.append("total_amount_missing")
    if parsed.get("line_items") and not items:
        warning_codes.append("line_items_amount_unparsed")
        warnings.append("Line items were detected but none had valid numeric amounts.")
    if warning_codes:
        warnings.append("Some receipt fields were not confidently extracted and remain null.")
    logger.info(
        "gemini_receipt_mapped merchant=%s date=%s total=%s items=%s warning_codes=%s",
        merchant,
        date_value.isoformat() if date_value else None,
        str(total) if total is not None else None,
        len(items),
        sorted(set(warning_codes)),
    )
    return ParsedReceiptCandidate(
        merchant=merchant,
        expense_date=date_value,
        total_amount=total,
        items=items,
        warnings=_dedupe_strings(warnings),
        warning_codes=_dedupe_strings(warning_codes),
    )


def _coerce_iso_date(raw: object) -> date | None:
    if raw is None:
        return None
    text = str(raw).strip()
    if not text:
        return None

    # 1) Strict ISO first.
    try:
        return date.fromisoformat(text)
    except ValueError:
        pass

    # 2) Common receipt date formats.
    formats = (
        "%m/%d/%Y",
        "%m-%d-%Y",
        "%d/%m/%Y",
        "%d-%m-%Y",
        "%m/%d/%y",
        "%m-%d-%y",
        "%d/%m/%y",
        "%d-%m-%y",
        "%b %d, %Y",
        "%B %d, %Y",
        "%b %d %Y",
        "%B %d %Y",
    )
    for fmt in formats:
        try:
            parsed = datetime.strptime(text, fmt).date()
            if 2000 <= parsed.year <= 2100:
                return parsed
        except ValueError:
            continue

    # 3) Pull date token from noisy text and retry.
    token_match = re.search(
        r"(\d{1,2}[/-]\d{1,2}[/-]\d{2,4}|[A-Za-z]{3,9}\s+\d{1,2},?\s+\d{2,4})",
        text,
    )
    if token_match:
        return _coerce_iso_date(token_match.group(1))
    return None


def _coerce_decimal(raw: object) -> Decimal | None:
    if raw is None:
        return None
    if isinstance(raw, int | float):
        try:
            return Decimal(str(raw)).quantize(Decimal("0.01"))
        except (InvalidOperation, ValueError):
            return None
    try:
        text = str(raw).strip()
        if not text:
            return None
        # Accept common currency-formatted strings like "₱1,234.50" or "PHP 99.00".
        sanitized = text.replace(",", "")
        sanitized = re.sub(r"(?i)php\s*", "", sanitized)
        sanitized = sanitized.replace("₱", "").replace("$", "").strip()
        if not re.fullmatch(r"-?\d+(?:\.\d{1,4})?", sanitized):
            match = re.search(r"-?\d+(?:\.\d{1,4})?", sanitized)
            if not match:
                return None
            sanitized = match.group(0)
        value = Decimal(sanitized)
        return value.quantize(Decimal("0.01"))
    except (InvalidOperation, ValueError):
        return None


def _coerce_amount_field(raw: object, *, allow_zero: bool) -> Decimal | None:
    value = _coerce_decimal(raw)
    if value is None:
        return None
    if value >= _MAX_TOTAL:
        return None
    if allow_zero:
        if value < Decimal("0.00"):
            return None
        return value
    if value < _MIN_POSITIVE_AMOUNT:
        return None
    return value


def _contains_injection_content(payload: dict[str, object]) -> bool:
    serialized = json.dumps(payload).lower()
    return any(pattern in serialized for pattern in _INJECTION_PATTERNS)


def _truncate_for_log(value: str | None, limit: int = 1200) -> str:
    if not value:
        return ""
    normalized = re.sub(r"\s+", " ", value).strip()
    if len(normalized) <= limit:
        return normalized
    return f"{normalized[:limit]}...<truncated>"


@dataclass(frozen=True)
class _ItemsSelection:
    value: list[ExpenseItemWrite]
    confidence: float | None
    warnings: list[str]
    warning_codes: list[str]


def _normalize_ocr_lines(
    ocr_lines: list[str],
) -> tuple[list[_NormalizedLine], list[str], list[str]]:
    warnings: list[str] = []
    warning_codes: list[str] = []
    normalized_lines: list[_NormalizedLine] = []
    whitespace_adjusted = 0
    blank_dropped = 0

    for idx, raw_line in enumerate(ocr_lines):
        collapsed = re.sub(r"\s+", " ", raw_line).strip()
        if collapsed != raw_line:
            whitespace_adjusted += 1
        if not collapsed:
            blank_dropped += 1
            continue
        normalized_lines.append(_NormalizedLine(index=idx, text=collapsed))

    if whitespace_adjusted:
        warnings.append(f"Normalized whitespace in {whitespace_adjusted} OCR line(s).")
        warning_codes.append("ocr_whitespace_normalized")
    if blank_dropped:
        warnings.append(f"Dropped {blank_dropped} blank OCR line(s) after normalization.")
        warning_codes.append("ocr_blank_lines_dropped")

    return normalized_lines, warnings, warning_codes


def _select_merchant(lines: list[_NormalizedLine]) -> _FieldSelection[str]:
    candidates = sorted(
        (
            (_score_merchant_line(line), line)
            for line in lines[:6]
        ),
        key=lambda item: item[0],
        reverse=True,
    )
    if not candidates or candidates[0][0] <= 0:
        return _FieldSelection(None, 0.0, None, [], [])

    best_score, best_line = candidates[0]
    warnings: list[str] = []
    warning_codes: list[str] = []

    if len(candidates) > 1 and abs(best_score - candidates[1][0]) <= 4:
        warnings.append(
            f"Merchant is ambiguous; selected '{best_line.text}' over '{candidates[1][1].text}'."
        )
        warning_codes.append("merchant_ambiguous")

    merchant_text = best_line.text[:160]
    if merchant_text != best_line.text:
        warnings.append("Merchant text exceeded 160 characters and was truncated.")
        warning_codes.append("merchant_truncated")

    confidence = 0.55 if "merchant_ambiguous" in warning_codes else 0.9
    return _FieldSelection(merchant_text, confidence, best_line.index, warnings, warning_codes)


def _score_merchant_line(line: _NormalizedLine) -> int:
    text = line.text
    lowered = text.lower()
    letters = sum(char.isalpha() for char in text)
    digits = sum(char.isdigit() for char in text)
    uppercase_letters = sum(char.isupper() for char in text)
    if len(text) < 3:
        return -10_000

    score = 0
    if line.index <= 1:
        score += 10
    if letters >= 4:
        score += 12
    if digits == 0:
        score += 10
    if letters > 0 and uppercase_letters >= int(letters * 0.6):
        score += 8

    if "receipt" in lowered or "invoice" in lowered or "order" in lowered:
        score -= 12
    if "copy" in lowered:
        score -= 18
    if "total" in lowered or "subtotal" in lowered or "tax" in lowered:
        score -= 20
    if "date" in lowered or "time" in lowered:
        score -= 16
    if "phone" in lowered or "tel" in lowered or "www" in lowered or "http" in lowered:
        score -= 22
    if any(token in lowered for token in ("street", " st", "ave", "road", "blvd")):
        score -= 20
    if _contains_amount(text):
        score -= 24
    if _parse_date_from_text(text).value is not None:
        score -= 18
    return score


def _select_expense_date(lines: list[_NormalizedLine]) -> _FieldSelection[date]:
    warnings: list[str] = []
    warning_codes: list[str] = []

    for line in lines:
        selection = _parse_date_from_text(line.text)
        if selection.value is None:
            continue
        warnings.extend(selection.warnings)
        warning_codes.extend(selection.warning_codes)
        confidence = 0.6 if selection.warning_codes else 0.9
        return _FieldSelection(selection.value, confidence, line.index, warnings, warning_codes)

    return _FieldSelection(None, 0.0, None, [], [])


def _parse_date_from_text(text: str) -> _FieldSelection[date]:
    for match in _SLASH_DATE_RE.finditer(text):
        raw = match.group(0)
        first_s, second_s, year_s = re.split(r"[/-]", raw)
        first = int(first_s)
        second = int(second_s)
        year = _normalize_year(int(year_s))
        ambiguous = 1 <= first <= 12 and 1 <= second <= 12
        month = first if ambiguous or 1 <= first <= 12 else second
        day = second if ambiguous or 1 <= first <= 12 else first
        try:
            parsed = date(year, month, day)
        except ValueError:
            continue
        warnings: list[str] = []
        warning_codes: list[str] = []
        if ambiguous:
            warnings.append(
                f"Expense date '{raw}' is ambiguous; interpreted as {parsed.isoformat()}."
            )
            warning_codes.append("expense_date_ambiguous")
        return _FieldSelection(parsed, None, None, warnings, warning_codes)

    for match in _MONTH_DATE_RE.finditer(text):
        raw = match.group(0)
        for fmt in ("%b %d %Y", "%b %d, %Y", "%B %d %Y", "%B %d, %Y"):
            try:
                month_date = datetime.strptime(raw, fmt).date()
                return _FieldSelection(month_date, None, None, [], [])
            except ValueError:
                continue

    for match in _ISO_DATE_RE.finditer(text):
        raw = match.group(0)
        try:
            return _FieldSelection(date.fromisoformat(raw), None, None, [], [])
        except ValueError:
            continue

    return _FieldSelection(None, None, None, [], [])


def _select_total_amount(lines: list[_NormalizedLine]) -> _FieldSelection[Decimal]:
    best_value: Decimal | None = None
    best_index: int | None = None
    best_score = -10_000
    warnings: list[str] = []
    warning_codes: list[str] = []

    for idx, line in enumerate(lines):
        lowered = line.text.lower()
        has_total_keyword = any(keyword in lowered for keyword in _TOTAL_KEYWORDS)
        if any(token in lowered for token in _NON_TOTAL_KEYWORDS):
            continue

        inline_amount = _parse_last_amount(line.text)
        next_amount = (
            _parse_last_amount(lines[idx + 1].text)
            if has_total_keyword and inline_amount is None and idx + 1 < len(lines)
            else None
        )

        score = -10_000
        if "grand total" in lowered:
            score = 120
        elif "amount due" in lowered:
            score = 118
        elif "balance due" in lowered:
            score = 116
        elif has_total_keyword and inline_amount is not None:
            score = 112
        elif has_total_keyword and next_amount is not None:
            score = 108

        amount = inline_amount or next_amount
        if amount is not None and score > best_score:
            best_score = score
            best_value = amount
            best_index = line.index
            warnings = []
            warning_codes = []
            if inline_amount is None and next_amount is not None:
                warnings.append("Total amount was assembled from a multi-line total label.")
                warning_codes.append("total_amount_multiline")

    if best_value is not None:
        confidence = 0.75 if "total_amount_multiline" in warning_codes else 0.95
        return _FieldSelection(best_value, confidence, best_index, warnings, warning_codes)

    return _FieldSelection(None, 0.0, None, [], [])


def _select_items(
    *,
    lines: list[_NormalizedLine],
    merchant_line_index: int | None,
    total_line_index: int | None,
) -> _ItemsSelection:
    start = 0
    end = len(lines)
    if merchant_line_index is not None:
        merchant_pos = next(
            (position for position, line in enumerate(lines) if line.index == merchant_line_index),
            None,
        )
        if merchant_pos is not None:
            start = merchant_pos + 1
    if total_line_index is not None:
        total_pos = next(
            (position for position, line in enumerate(lines) if line.index == total_line_index),
            None,
        )
        if total_pos is not None:
            end = total_pos

    warnings: list[str] = []
    warning_codes: list[str] = []
    items: list[ExpenseItemWrite] = []

    for line in lines[start:end]:
        lowered = line.text.lower()
        if any(keyword in lowered for keyword in _NON_ITEM_KEYWORDS):
            continue
        if _parse_date_from_text(line.text).value is not None:
            continue
        amount_match = list(_AMOUNT_RE.finditer(line.text))
        if not amount_match:
            continue
        raw_amount = amount_match[-1].group(0)
        amount = _parse_amount(raw_amount)
        if amount is None:
            continue
        description = re.sub(r"[\s.:-]+$", "", line.text[: amount_match[-1].start()]).strip()
        if len(description) < 2 or sum(char.isalpha() for char in description) < 2:
            continue
        if len(description) > 160:
            description = description[:160]
            warnings.append("One or more item names exceeded 160 characters and were truncated.")
            warning_codes.append("item_name_truncated")
        items.append(ExpenseItemWrite(name=description, amount=amount))

    if not items:
        warnings.append("No line items were confidently parsed from OCR lines.")
        warning_codes.append("items_missing")
        confidence = 0.2
    else:
        confidence = 0.8

    return _ItemsSelection(items, confidence, warnings, warning_codes)


def _parse_last_amount(text: str) -> Decimal | None:
    matches = list(_AMOUNT_RE.finditer(text))
    if not matches:
        return None
    return _parse_amount(matches[-1].group(0))


def _parse_amount(raw_amount: str) -> Decimal | None:
    normalized = (
        raw_amount.replace("$", "")
        .replace("₱", "")
        .replace("PHP", "")
        .replace(",", "")
        .strip()
    )
    try:
        amount = Decimal(normalized)
    except InvalidOperation:
        return None
    if amount <= Decimal("0"):
        return None
    return amount.quantize(Decimal("0.01"))


def _contains_amount(text: str) -> bool:
    return _AMOUNT_RE.search(text) is not None


def _normalize_year(year: int) -> int:
    if year < 100:
        return 1900 + year if year >= 70 else 2000 + year
    return year


def _dedupe_strings(values: list[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        if value in seen:
            continue
        seen.add(value)
        result.append(value)
    return result

from __future__ import annotations

from datetime import date
from decimal import Decimal

from app.schemas.expense import ReceiptProcessRequest
from app.services.parser_service import parse_receipt


def test_parse_receipt_happy_path() -> None:
    candidate = parse_receipt(
        ReceiptProcessRequest(
            ocr_lines=[
                "BEAN BARN CAFE",
                "04/29/2026",
                "Latte 4.50",
                "Blueberry Muffin 3.25",
                "TOTAL 7.75",
            ],
            locale="en-PH",
            currency_hint="php",
        )
    )

    assert candidate.merchant == "BEAN BARN CAFE"
    assert candidate.expense_date == date(2026, 4, 29)
    assert candidate.total_amount == Decimal("7.75")
    assert [item.name for item in candidate.items] == ["Latte", "Blueberry Muffin"]
    assert candidate.warning_codes == []


def test_parse_receipt_noisy_ocr_still_returns_structured_candidate() -> None:
    candidate = parse_receipt(
        ReceiptProcessRequest(
            ocr_lines=[
                "STORE COPY",
                "SUNRISE MARKET",
                "123 MAIN ST",
                "Date: 04/05/2026",
                "BANANAS 2.49",
                "MEMBER SAVINGS 0.50",
                "TOTAL DUE 2.49",
                "VISA **** 1111",
            ]
        )
    )

    assert candidate.merchant == "SUNRISE MARKET"
    assert candidate.expense_date == date(2026, 4, 5)
    assert candidate.total_amount == Decimal("2.49")
    assert [item.name for item in candidate.items] == ["BANANAS"]
    assert "expense_date_ambiguous" in candidate.warning_codes


def test_parse_receipt_missing_total_returns_warning_only() -> None:
    candidate = parse_receipt(
        ReceiptProcessRequest(
            ocr_lines=[
                "CORNER SHOP",
                "04/29/2026",
                "Bread 2.00",
                "Milk 1.50",
            ]
        )
    )

    assert candidate.merchant == "CORNER SHOP"
    assert candidate.expense_date == date(2026, 4, 29)
    assert candidate.total_amount is None
    assert "total_amount_missing" in candidate.warning_codes


def test_parse_receipt_can_infer_total_from_trailing_standalone_amount() -> None:
    candidate = parse_receipt(
        ReceiptProcessRequest(
            ocr_lines=[
                "CORNER SHOP",
                "04/29/2026",
                "Bread 2.00",
                "Milk 1.50",
                "$3.50",
            ]
        )
    )

    assert candidate.total_amount == Decimal("3.50")
    assert "total_amount_inferred" in candidate.warning_codes


def test_parse_receipt_ambiguous_merchant_propagates_warning() -> None:
    candidate = parse_receipt(
        ReceiptProcessRequest(
            ocr_lines=[
                "WEST COFFEE",
                "EAST COFFEE",
                "04/29/2026",
                "Americano 3.00",
                "TOTAL 3.00",
            ]
        )
    )

    assert candidate.merchant in {"WEST COFFEE", "EAST COFFEE"}
    assert "merchant_ambiguous" in candidate.warning_codes


def test_parse_receipt_warning_propagation_from_normalization_and_multiline_total() -> None:
    candidate = parse_receipt(
        ReceiptProcessRequest(
            ocr_lines=[
                "NORTH   DELI   ",
                "April 29, 2026",
                "Sandwich 8.00",
                "TOTAL",
                "$8.00",
            ]
        )
    )

    assert candidate.merchant == "NORTH DELI"
    assert candidate.total_amount == Decimal("8.00")
    assert "ocr_whitespace_normalized" in candidate.warning_codes
    assert "total_amount_multiline" in candidate.warning_codes

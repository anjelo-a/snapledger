from __future__ import annotations

import base64
from datetime import date
from decimal import Decimal
from io import BytesIO

from PIL import Image

from app.core.config import get_settings
from app.schemas.expense import ReceiptProcessRequest
from app.services import parser_service
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


def test_parse_receipt_does_not_infer_total_from_trailing_standalone_amount() -> None:
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

    assert candidate.total_amount is None
    assert "total_amount_missing" in candidate.warning_codes


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


def test_parse_receipt_image_missing_gemini_key_returns_structured_warning(monkeypatch) -> None:
    monkeypatch.delenv("GEMINI_API_KEY", raising=False)
    get_settings.cache_clear()
    try:
        candidate = parse_receipt(
            ReceiptProcessRequest(
                image_base64=_tiny_jpeg_base64(),
                image_mime_type="image/jpeg",
            )
        )
    finally:
        get_settings.cache_clear()

    assert candidate.merchant is None
    assert candidate.total_amount is None
    assert candidate.warning_codes == ["gemini_api_key_missing"]


def test_parse_receipt_image_maps_mocked_gemini_response(monkeypatch) -> None:
    monkeypatch.setenv("GEMINI_API_KEY", "test-gemini-key")
    get_settings.cache_clear()

    def fake_call_gemini_extract(**_: object) -> str:
        return """
        {
          "merchant": "BEAN BARN CAFE",
          "date": "2026-04-29",
          "subtotal": "7.25",
          "tax": "0.50",
          "total": "7.75",
          "line_items": [
            {"name": "Latte", "amount": "4.50"},
            {"name": "Blueberry Muffin", "amount": "3.25"}
          ]
        }
        """

    monkeypatch.setattr(parser_service, "_call_gemini_extract", fake_call_gemini_extract)
    try:
        candidate = parse_receipt(
            ReceiptProcessRequest(
                image_base64=_tiny_jpeg_base64(),
                image_mime_type="image/jpeg",
                locale="en-PH",
                currency_hint="PHP",
            )
        )
    finally:
        get_settings.cache_clear()

    assert candidate.merchant == "BEAN BARN CAFE"
    assert candidate.expense_date == date(2026, 4, 29)
    assert candidate.total_amount == Decimal("7.75")
    assert [item.name for item in candidate.items] == ["Latte", "Blueberry Muffin"]
    assert candidate.warning_codes == []


def test_call_gemini_extract_uses_header_key_and_structured_response_schema(monkeypatch) -> None:
    captured: dict[str, object] = {}

    class FakeResponse:
        status_code = 200

        def raise_for_status(self) -> None:
            return None

        def json(self) -> dict[str, object]:
            return {
                "candidates": [
                    {
                        "content": {
                            "parts": [
                                {
                                    "text": (
                                        '{"merchant":null,"date":null,"subtotal":null,'
                                        '"tax":null,"total":null,"line_items":[]}'
                                    )
                                }
                            ]
                        }
                    }
                ]
            }

    class FakeClient:
        def __init__(self, timeout: float) -> None:
            captured["timeout"] = timeout

        def __enter__(self) -> FakeClient:
            return self

        def __exit__(self, *_: object) -> None:
            return None

        def post(
            self,
            endpoint: str,
            *,
            headers: dict[str, str],
            json: dict[str, object],
        ) -> FakeResponse:
            captured["endpoint"] = endpoint
            captured["headers"] = headers
            captured["json"] = json
            return FakeResponse()

    monkeypatch.setenv("GEMINI_API_KEY", "test-gemini-key")
    get_settings.cache_clear()
    monkeypatch.setattr(parser_service.httpx, "Client", FakeClient)
    try:
        response_text = parser_service._call_gemini_extract(
            image_bytes=b"image-bytes",
            mime_type="image/jpeg",
            locale="en-PH",
            currency_hint="PHP",
            model_name="gemini-2.5-flash",
        )
    finally:
        get_settings.cache_clear()

    assert "test-gemini-key" not in str(captured["endpoint"])
    assert captured["headers"] == {"x-goog-api-key": "test-gemini-key"}
    generation_config = captured["json"]["generationConfig"]  # type: ignore[index]
    assert generation_config["responseMimeType"] == "application/json"  # type: ignore[index]
    assert generation_config["responseJsonSchema"]["additionalProperties"] is False  # type: ignore[index]
    assert response_text.startswith('{"merchant":null')


def _tiny_jpeg_base64() -> str:
    output = BytesIO()
    Image.new("RGB", (1, 1), color="white").save(output, format="JPEG")
    return base64.b64encode(output.getvalue()).decode("utf-8")

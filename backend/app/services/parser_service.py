from app.schemas.expense import ParsedReceiptCandidate, ReceiptProcessRequest


def parse_receipt(_payload: ReceiptProcessRequest) -> ParsedReceiptCandidate:
    # Deterministic parsing rules are implemented in Phase 2.
    return ParsedReceiptCandidate(warnings=["Receipt parsing is not available in Phase 0."])

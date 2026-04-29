from app.schemas.expense import ParsedReceiptCandidate, ReceiptProcessRequest


def parse_receipt(_payload: ReceiptProcessRequest) -> ParsedReceiptCandidate:
    # Phase 2 parser work remains deterministic-only; no LLM parsing is allowed here.
    return ParsedReceiptCandidate(warnings=["Receipt parsing is not available in Phase 2."])

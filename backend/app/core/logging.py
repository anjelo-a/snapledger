import logging
import re


class _RedactApiKeyFilter(logging.Filter):
    _KEY_RE = re.compile(r"(key=)[^&\s]+", re.IGNORECASE)

    def filter(self, record: logging.LogRecord) -> bool:
        message = record.getMessage()
        if "key=" in message:
            redacted = self._KEY_RE.sub(r"\1<REDACTED>", message)
            record.msg = redacted
            record.args = ()
        return True


def configure_logging() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s - %(message)s",
    )
    root_logger = logging.getLogger()
    redact_filter = _RedactApiKeyFilter()
    root_logger.addFilter(redact_filter)
    # Keep noisy HTTP client internals out of normal logs.
    logging.getLogger("httpx").setLevel(logging.WARNING)

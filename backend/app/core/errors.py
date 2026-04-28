from __future__ import annotations

from dataclasses import dataclass


@dataclass
class DomainError(Exception):
    message: str


@dataclass
class NotFoundError(DomainError):
    pass


@dataclass
class ConflictError(DomainError):
    pass


@dataclass
class InvalidOperationError(DomainError):
    pass


@dataclass
class ServiceUnavailableError(DomainError):
    pass

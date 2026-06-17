"""Typed no-op declarations for Runtime Conditions Profile generation.

Applications may import this package and write declaration code next to their
integration code. The declarations are intentionally inert at runtime; the
Python profiler reads calls to this package from source using ``ast``.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, TypeVar

T = TypeVar("T")

Postgres = "postgres"
MySQL = "mysql"
MariaDB = "mariadb"
SQLServer = "sqlserver"
Oracle = "oracle"
SQLite = "sqlite"

MongoDB = "mongodb"
Couchbase = "couchbase"

Redis = "redis"
Memcached = "memcached"

NATS = "nats"


@dataclass(frozen=True)
class Declaration:
    """Inert declaration value returned by top-level declaration helpers."""


@dataclass(frozen=True)
class APIOption:
    """Inert API option value."""


@dataclass(frozen=True)
class OperationOption:
    """Inert operation option value."""


@dataclass(frozen=True)
class DatastoreOption:
    """Inert datastore option value."""


@dataclass(frozen=True)
class CacheOption:
    """Inert cache option value."""


@dataclass(frozen=True)
class MessageBusOption:
    """Inert message bus option value."""


@dataclass(frozen=True)
class SubjectOption:
    """Inert subject option value."""


def API(name: str, *options: APIOption) -> Declaration:
    return Declaration()


def Spec(format: str, uri: str, version: str | None = None) -> APIOption:
    return APIOption()


def GET(path: str, *options: OperationOption) -> APIOption:
    return APIOption()


def HEAD(path: str, *options: OperationOption) -> APIOption:
    return APIOption()


def POST(path: str, *options: OperationOption) -> APIOption:
    return APIOption()


def PUT(path: str, *options: OperationOption) -> APIOption:
    return APIOption()


def PATCH(path: str, *options: OperationOption) -> APIOption:
    return APIOption()


def DELETE(path: str, *options: OperationOption) -> APIOption:
    return APIOption()


def OPTIONS(path: str, *options: OperationOption) -> APIOption:
    return APIOption()


def TRACE(path: str, *options: OperationOption) -> APIOption:
    return APIOption()


def Request(schema: type[T]) -> OperationOption:
    return OperationOption()


def Response(schema: type[T]) -> OperationOption:
    return OperationOption()


def Datastore(name: str, *options: DatastoreOption) -> Declaration:
    return Declaration()


def Relational(engine: str) -> DatastoreOption:
    return DatastoreOption()


def Document(engine: str) -> DatastoreOption:
    return DatastoreOption()


def Cache(name: str, *options: CacheOption) -> Declaration:
    return Declaration()


def KeyValue(engine: str) -> CacheOption:
    return CacheOption()


def MessageBus(name: str, *options: MessageBusOption) -> Declaration:
    return Declaration()


def PubSub(engine: str) -> MessageBusOption:
    return MessageBusOption()


def Publishes(subject: str, *options: SubjectOption) -> MessageBusOption:
    return MessageBusOption()


def Subscribes(subject: str, *options: SubjectOption) -> MessageBusOption:
    return MessageBusOption()


def Payload(schema: type[T]) -> SubjectOption:
    return SubjectOption()


def declares(*declarations: Declaration) -> Any:
    """Decorator form for associating declarations with a function or class."""

    def decorator(target: T) -> T:
        return target

    return decorator

from __future__ import annotations

import argparse
import ast
import json
import re
import sys
from pathlib import Path
from typing import Any

DECLARATION_MODULE = "runtimeconditions"
CORE_EXTENSION = "core"
MESSAGE_BUS_EXTENSION = "runtimeconditions.io/message-bus/v1alpha1"

HTTP_METHODS = {"GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE"}

ENGINE_NAMES = {
    "Postgres": "postgres",
    "MySQL": "mysql",
    "MariaDB": "mariadb",
    "SQLServer": "sqlserver",
    "Oracle": "oracle",
    "SQLite": "sqlite",
    "MongoDB": "mongodb",
    "Couchbase": "couchbase",
    "Redis": "redis",
    "Memcached": "memcached",
    "NATS": "nats",
}


class ExtractionError(Exception):
    pass


class ImportSet:
    def __init__(self) -> None:
        self.package_aliases: set[str] = set()
        self.direct_names: dict[str, str] = {}

    def recognizes_runtimeconditions(self) -> bool:
        return bool(self.package_aliases or self.direct_names)


class SourceScope:
    def __init__(self) -> None:
        self.classes: dict[str, ast.ClassDef] = {}
        self.string_consts: dict[str, str] = {}

    def collect(self, tree: ast.Module) -> None:
        for stmt in tree.body:
            if isinstance(stmt, ast.ClassDef):
                self.classes[stmt.name] = stmt
            elif isinstance(stmt, ast.Assign):
                value = string_literal(stmt.value)
                if value is None:
                    continue
                for target in stmt.targets:
                    if isinstance(target, ast.Name):
                        self.string_consts[target.id] = value
            elif isinstance(stmt, ast.AnnAssign) and isinstance(stmt.target, ast.Name):
                value = string_literal(stmt.value)
                if value is not None:
                    self.string_consts[stmt.target.id] = value


class PythonExtractor:
    def __init__(self, root: Path, name: str, workload_uri: str, workload_version: str):
        self.root = root
        self.name = name
        self.workload_uri = workload_uri
        self.workload_version = workload_version
        self.scope = SourceScope()
        self.trees: list[tuple[Path, ast.Module]] = []
        self.extensions = {CORE_EXTENSION}

    def extract(self) -> dict[str, Any]:
        self._load_trees()
        conditions: list[dict[str, Any]] = []

        for path, tree in self.trees:
            imports = runtimecondition_imports(tree)
            if not imports.recognizes_runtimeconditions():
                continue
            for call in ast.walk(tree):
                if not isinstance(call, ast.Call):
                    continue
                name = call_name(call, imports)
                if name is None:
                    continue
                try:
                    if name == "API":
                        conditions.append(self._parse_api(call, imports))
                    elif name == "Datastore":
                        conditions.append(self._parse_datastore(call, imports))
                    elif name == "Cache":
                        conditions.append(self._parse_cache(call, imports))
                    elif name == "MessageBus":
                        conditions.append(self._parse_message_bus(call, imports))
                        self.extensions.add(MESSAGE_BUS_EXTENSION)
                except ExtractionError as exc:
                    line = getattr(call, "lineno", 0)
                    raise ExtractionError(f"{path}:{line}: {exc}") from exc

        return {
            "apiVersion": "runtimeconditions.io/v1alpha1",
            "kind": "RuntimeConditionsProfile",
            "metadata": {"name": self.name},
            "workload": omit_empty(
                {
                    "uri": self.workload_uri,
                    "version": self.workload_version,
                }
            ),
            "extensions": sorted(self.extensions),
            "conditions": conditions,
        }

    def _load_trees(self) -> None:
        for path in sorted(self.root.rglob("*.py")):
            if "__pycache__" in path.parts:
                continue
            source = path.read_text(encoding="utf-8")
            tree = ast.parse(source, filename=str(path))
            self.trees.append((path, tree))
            self.scope.collect(tree)

    def _parse_api(self, call: ast.Call, imports: ImportSet) -> dict[str, Any]:
        if not call.args:
            raise ExtractionError("API requires a name")
        name = self._string_value(call.args[0])
        if name is None:
            raise ExtractionError("API name must be a string literal or string constant")

        interface: dict[str, Any] = {"type": "http"}
        operations: list[dict[str, Any]] = []

        for arg in call.args[1:]:
            if not isinstance(arg, ast.Call):
                continue
            option_name = call_name(arg, imports)
            if option_name in HTTP_METHODS:
                operations.append(self._parse_operation(option_name, arg, imports))
            elif option_name == "Spec":
                interface["spec"] = self._parse_spec(arg)
            elif option_name in {"Request", "Response"}:
                if not operations:
                    raise ExtractionError(f"{option_name} must follow an HTTP operation")
                schema = self._schema_call_argument(arg, option_name)
                key = "requestBodySchema" if option_name == "Request" else "responseSchema"
                operations[-1][key] = schema

        if operations:
            interface["operations"] = operations
        if "spec" not in interface and not operations:
            raise ExtractionError("API requires at least one Spec or HTTP operation")

        return {
            "name": name,
            "kind": "api",
            "interface": interface,
        }

    def _parse_operation(
        self, method: str, call: ast.Call, imports: ImportSet
    ) -> dict[str, Any]:
        if not call.args:
            raise ExtractionError(f"{method} requires a path")
        path = self._string_value(call.args[0])
        if path is None:
            raise ExtractionError(f"{method} path must be a string literal or string constant")

        operation: dict[str, Any] = {"method": method, "path": path}
        for arg in call.args[1:]:
            if not isinstance(arg, ast.Call):
                continue
            option_name = call_name(arg, imports)
            if option_name not in {"Request", "Response"}:
                continue
            schema = self._schema_call_argument(arg, option_name)
            key = "requestBodySchema" if option_name == "Request" else "responseSchema"
            operation[key] = schema
        return operation

    def _parse_spec(self, call: ast.Call) -> dict[str, Any]:
        if len(call.args) < 2:
            raise ExtractionError("Spec requires format and URI")
        format_name = self._string_value(call.args[0])
        uri = self._string_value(call.args[1])
        if format_name is None:
            raise ExtractionError("Spec format must be a string literal or string constant")
        if uri is None:
            raise ExtractionError("Spec URI must be a string literal or string constant")
        spec = {"format": format_name, "uri": uri}
        if len(call.args) > 2:
            version = self._string_value(call.args[2])
            if version is None:
                raise ExtractionError("Spec version must be a string literal or string constant")
            spec["version"] = version
        return spec

    def _parse_datastore(self, call: ast.Call, imports: ImportSet) -> dict[str, Any]:
        if not call.args:
            raise ExtractionError("Datastore requires a name")
        name = self._string_value(call.args[0])
        if name is None:
            raise ExtractionError("Datastore name must be a string literal or string constant")

        interface: dict[str, Any] = {}
        for arg in call.args[1:]:
            if not isinstance(arg, ast.Call):
                continue
            option_name = call_name(arg, imports)
            if option_name == "Relational":
                interface["type"] = "relational"
                if arg.args:
                    interface["engine"] = self._engine_value(arg.args[0], imports)
            elif option_name == "Document":
                interface["type"] = "document"
                if arg.args:
                    interface["engine"] = self._engine_value(arg.args[0], imports)
        if "type" not in interface:
            raise ExtractionError("Datastore requires Relational or Document")
        return {"name": name, "kind": "datastore", "interface": omit_empty(interface)}

    def _parse_cache(self, call: ast.Call, imports: ImportSet) -> dict[str, Any]:
        if not call.args:
            raise ExtractionError("Cache requires a name")
        name = self._string_value(call.args[0])
        if name is None:
            raise ExtractionError("Cache name must be a string literal or string constant")

        interface: dict[str, Any] = {}
        for arg in call.args[1:]:
            if not isinstance(arg, ast.Call):
                continue
            option_name = call_name(arg, imports)
            if option_name == "KeyValue":
                interface["type"] = "key_value"
                if arg.args:
                    interface["engine"] = self._engine_value(arg.args[0], imports)
        if "type" not in interface:
            raise ExtractionError("Cache requires KeyValue")
        return {"name": name, "kind": "cache", "interface": omit_empty(interface)}

    def _parse_message_bus(self, call: ast.Call, imports: ImportSet) -> dict[str, Any]:
        if not call.args:
            raise ExtractionError("MessageBus requires a name")
        name = self._string_value(call.args[0])
        if name is None:
            raise ExtractionError("MessageBus name must be a string literal or string constant")

        interface: dict[str, Any] = {"type": "runtimeconditions.pubsub"}
        subjects: list[dict[str, Any]] = []
        for arg in call.args[1:]:
            if not isinstance(arg, ast.Call):
                continue
            option_name = call_name(arg, imports)
            if option_name == "PubSub":
                if arg.args:
                    interface["engine"] = self._engine_value(arg.args[0], imports)
            elif option_name in {"Publishes", "Subscribes"}:
                subjects.append(self._parse_subject(option_name, arg, imports))
        if subjects:
            interface["subjects"] = subjects

        return {
            "name": name,
            "kind": "runtimeconditions.message_bus",
            "interface": interface,
        }

    def _parse_subject(
        self, option_name: str, call: ast.Call, imports: ImportSet
    ) -> dict[str, Any]:
        if not call.args:
            raise ExtractionError(f"{option_name} requires a subject name")
        name = self._string_value(call.args[0])
        if name is None:
            raise ExtractionError(
                f"{option_name} subject must be a string literal or string constant"
            )
        subject: dict[str, Any] = {
            "name": name,
            "direction": "publish" if option_name == "Publishes" else "subscribe",
        }
        for arg in call.args[1:]:
            if not isinstance(arg, ast.Call):
                continue
            suboption_name = call_name(arg, imports)
            if suboption_name != "Payload":
                continue
            subject["payloadSchema"] = self._schema_call_argument(arg, "Payload")
        return subject

    def _schema_call_argument(self, call: ast.Call, function_name: str) -> Any:
        if len(call.args) != 1:
            raise ExtractionError(f"{function_name} requires exactly one type argument")
        return self._schema_for_annotation(call.args[0])

    def _schema_for_annotation(self, annotation: ast.AST) -> Any:
        if isinstance(annotation, ast.Name):
            builtin = builtin_schema_type(annotation.id)
            if builtin is not None:
                return builtin
            if annotation.id in {"datetime", "date"}:
                return "string"
            class_def = self.scope.classes.get(annotation.id)
            if class_def is None:
                raise ExtractionError(f"unsupported schema type {annotation.id!r}")
            return self._schema_for_class(class_def)

        if isinstance(annotation, ast.Attribute):
            dotted = dotted_name(annotation)
            if dotted in {"datetime.datetime", "datetime.date"}:
                return "string"
            raise ExtractionError(f"unsupported external schema type {dotted!r}")

        if isinstance(annotation, ast.Subscript):
            base = dotted_name(annotation.value)
            if base in {"list", "typing.List", "List", "Sequence", "typing.Sequence"}:
                return [self._schema_for_annotation(single_subscript(annotation.slice))]
            if base in {"Optional", "typing.Optional"}:
                return self._schema_for_annotation(single_subscript(annotation.slice))
            if base in {"Union", "typing.Union"}:
                for option in tuple_subscript(annotation.slice):
                    if not is_none_annotation(option):
                        return self._schema_for_annotation(option)
            raise ExtractionError(f"unsupported schema container {base!r}")

        if isinstance(annotation, ast.BinOp) and isinstance(annotation.op, ast.BitOr):
            if is_none_annotation(annotation.left):
                return self._schema_for_annotation(annotation.right)
            if is_none_annotation(annotation.right):
                return self._schema_for_annotation(annotation.left)

        if isinstance(annotation, ast.Constant) and annotation.value is None:
            return "null"

        raise ExtractionError(f"unsupported schema expression {ast.dump(annotation)}")

    def _schema_for_class(self, class_def: ast.ClassDef) -> dict[str, Any]:
        schema: dict[str, Any] = {}
        for stmt in class_def.body:
            if not isinstance(stmt, ast.AnnAssign):
                continue
            if not isinstance(stmt.target, ast.Name):
                continue
            name = stmt.target.id
            if name.startswith("_"):
                continue
            schema[name] = self._schema_for_annotation(stmt.annotation)
        return schema

    def _string_value(self, expr: ast.AST | None) -> str | None:
        value = string_literal(expr)
        if value is not None:
            return value
        if isinstance(expr, ast.Name):
            return self.scope.string_consts.get(expr.id)
        return None

    def _engine_value(self, expr: ast.AST, imports: ImportSet) -> str:
        value = self._string_value(expr)
        if value is not None:
            return value
        if isinstance(expr, ast.Attribute) and isinstance(expr.value, ast.Name):
            if expr.value.id in imports.package_aliases:
                return ENGINE_NAMES.get(expr.attr, expr.attr.lower())
        if isinstance(expr, ast.Name):
            original = imports.direct_names.get(expr.id)
            if original in ENGINE_NAMES:
                return ENGINE_NAMES[original]
        return ""


def extract_dir(
    root: str | Path,
    name: str,
    workload_uri: str,
    workload_version: str,
) -> dict[str, Any]:
    return PythonExtractor(Path(root), name, workload_uri, workload_version).extract()


def runtimecondition_imports(tree: ast.Module) -> ImportSet:
    imports = ImportSet()
    for node in tree.body:
        if isinstance(node, ast.Import):
            for alias in node.names:
                if alias.name == DECLARATION_MODULE:
                    imports.package_aliases.add(alias.asname or alias.name)
        elif isinstance(node, ast.ImportFrom) and node.module == DECLARATION_MODULE:
            for alias in node.names:
                if alias.name == "*":
                    continue
                imports.direct_names[alias.asname or alias.name] = alias.name
    return imports


def call_name(call: ast.Call, imports: ImportSet) -> str | None:
    func = call.func
    if isinstance(func, ast.Attribute) and isinstance(func.value, ast.Name):
        if func.value.id in imports.package_aliases:
            return func.attr
    if isinstance(func, ast.Name):
        return imports.direct_names.get(func.id)
    return None


def string_literal(expr: ast.AST | None) -> str | None:
    if isinstance(expr, ast.Constant) and isinstance(expr.value, str):
        return expr.value
    return None


def builtin_schema_type(name: str) -> str | None:
    if name == "str":
        return "string"
    if name == "bool":
        return "boolean"
    if name == "int":
        return "integer"
    if name == "float":
        return "number"
    if name == "None":
        return "null"
    return None


def dotted_name(node: ast.AST) -> str:
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Attribute):
        return f"{dotted_name(node.value)}.{node.attr}"
    return ast.dump(node)


def single_subscript(node: ast.AST) -> ast.AST:
    if isinstance(node, ast.Tuple) and len(node.elts) == 1:
        return node.elts[0]
    return node


def tuple_subscript(node: ast.AST) -> tuple[ast.AST, ...]:
    if isinstance(node, ast.Tuple):
        return tuple(node.elts)
    return (node,)


def is_none_annotation(node: ast.AST) -> bool:
    return (
        isinstance(node, ast.Constant)
        and node.value is None
        or isinstance(node, ast.Name)
        and node.id == "None"
    )


def omit_empty(mapping: dict[str, Any]) -> dict[str, Any]:
    return {
        key: value
        for key, value in mapping.items()
        if value is not None and value != "" and value != [] and value != {}
    }


def dump_yaml(value: Any) -> str:
    lines: list[str] = []
    emit_yaml(value, 0, lines)
    return "\n".join(lines) + "\n"


def emit_yaml(value: Any, indent: int, lines: list[str]) -> None:
    if isinstance(value, dict):
        for key, item in value.items():
            prefix = " " * indent + f"{key}:"
            if is_scalar(item):
                lines.append(prefix + " " + scalar_yaml(item))
            elif item == []:
                lines.append(prefix + " []")
            elif item == {}:
                lines.append(prefix + " {}")
            else:
                lines.append(prefix)
                emit_yaml(item, indent + 2, lines)
        return

    if isinstance(value, list):
        for item in value:
            prefix = " " * indent + "-"
            if is_scalar(item):
                lines.append(prefix + " " + scalar_yaml(item))
            elif isinstance(item, dict) and item:
                first = True
                for key, nested in item.items():
                    if first:
                        if is_scalar(nested):
                            lines.append(prefix + f" {key}: " + scalar_yaml(nested))
                        else:
                            lines.append(prefix + f" {key}:")
                            emit_yaml(nested, indent + 4, lines)
                        first = False
                    else:
                        nested_prefix = " " * (indent + 2) + f"{key}:"
                        if is_scalar(nested):
                            lines.append(nested_prefix + " " + scalar_yaml(nested))
                        elif nested == []:
                            lines.append(nested_prefix + " []")
                        elif nested == {}:
                            lines.append(nested_prefix + " {}")
                        else:
                            lines.append(nested_prefix)
                            emit_yaml(nested, indent + 4, lines)
            elif isinstance(item, list):
                lines.append(prefix)
                emit_yaml(item, indent + 2, lines)
            else:
                lines.append(prefix + " {}")
        return

    lines.append(" " * indent + scalar_yaml(value))


def is_scalar(value: Any) -> bool:
    return value is None or isinstance(value, (str, int, float, bool))


def scalar_yaml(value: Any) -> str:
    if value is None:
        return "null"
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, (int, float)):
        return str(value)
    if value == "":
        return '""'
    if re.match(r"^[A-Za-z0-9_./:{}/-]+$", value) and value not in {
        "true",
        "false",
        "null",
    }:
        return value
    return json.dumps(value)


def default_workload_uri(root: Path) -> str:
    current = root.resolve()
    for parent in [current, *current.parents]:
        marker = parent / "pyproject.toml"
        if marker.exists():
            return current.relative_to(parent).as_posix() or parent.name
    return current.name


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Generate a Runtime Conditions Profile from Python declaration code."
    )
    parser.add_argument("-d", "--dir", default=".", help="directory containing Python source")
    parser.add_argument("-n", "--name", default="", help="profile metadata.name")
    parser.add_argument("--workload-uri", default="", help="workload.uri")
    parser.add_argument("--workload-version", default="dev", help="workload.version")
    parser.add_argument("-o", "--out", default="", help="output file path; defaults to stdout")
    args = parser.parse_args(argv)

    root = Path(args.dir).resolve()
    name = args.name or root.name
    workload_uri = args.workload_uri or default_workload_uri(root)
    profile = extract_dir(root, name, workload_uri, args.workload_version)
    data = dump_yaml(profile)

    if args.out:
        Path(args.out).write_text(data, encoding="utf-8")
    else:
        sys.stdout.write(data)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

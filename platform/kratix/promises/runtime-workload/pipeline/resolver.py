from __future__ import annotations

import json
import os
import re
import ssl
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml


INPUT_DIR = Path(os.getenv("KRATIX_INPUT_DIR", "/kratix/input"))
OUTPUT_DIR = Path(os.getenv("KRATIX_OUTPUT_DIR", "/kratix/output"))
METADATA_DIR = Path(os.getenv("KRATIX_METADATA_DIR", "/kratix/metadata"))


class ContractError(Exception):
    pass


class NoAliasDumper(yaml.SafeDumper):
    def ignore_aliases(self, data: Any) -> bool:
        return True


@dataclass
class CatalogAPI:
    name: str
    definition: dict[str, Any]
    openapi: dict[str, Any]
    base_url: str | None


def main() -> int:
    try:
        request = yaml.safe_load((INPUT_DIR / "object.yaml").read_text(encoding="utf-8"))
        output = resolve(request)
        OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
        write_yaml_documents(OUTPUT_DIR / "runtime-workload.yaml", output)
        write_status(
            {
                "message": "Runtime workload resolved",
                "resolvedConditions": {
                    "apis": output.summary["apis"],
                    "caches": output.summary["caches"],
                },
            }
        )
        return 0
    except ContractError as exc:
        message = f"API contract validation failed\n\n{exc}"
        print(message, file=sys.stderr)
        write_status({"message": "API contract validation failed", "validationError": str(exc)})
        return 1
    except Exception as exc:
        print(f"runtime workload resolution failed: {exc}", file=sys.stderr)
        write_status({"message": "Runtime workload resolution failed", "error": str(exc)})
        return 1


class OutputDocuments(list):
    def __init__(self, values: list[dict[str, Any]], summary: dict[str, Any]):
        super().__init__(values)
        self.summary = summary


def resolve(request: dict[str, Any]) -> OutputDocuments:
    metadata = request.get("metadata", {})
    spec = request.get("spec", {})
    name = metadata["name"]
    namespace = metadata.get("namespace", "default")
    image = require_string(spec, "image")
    port = int(spec.get("port", 8080))
    replicas = int(spec.get("replicas", 1))
    image_pull_policy = spec.get("imagePullPolicy", "IfNotPresent")
    readiness_path = spec.get("readinessPath", "/ready")

    profile = yaml.safe_load(require_string(spec, "profile"))
    conditions = profile.get("conditions") or []

    catalog = load_catalog(spec.get("catalog", {}).get("configMapRef"))
    apis = parse_catalog_apis(catalog)

    env: list[dict[str, str]] = []
    emitted: list[dict[str, Any]] = []
    summary: dict[str, Any] = {"apis": [], "caches": []}

    for condition in conditions:
        if condition.get("kind") != "api":
            continue
        api = validate_api_condition(condition, apis)
        env_name = api_url_env_name(condition.get("name") or api.name)
        base_url = api.base_url or f"http://{api.name}.{namespace}.svc.cluster.local:{port}"
        env.append({"name": env_name, "value": base_url})
        summary["apis"].append(
            {
                "condition": condition.get("name"),
                "catalogApi": api.name,
                "env": env_name,
                "url": base_url,
            }
        )

    redis_cache_count = 0
    for condition in conditions:
        if condition.get("kind") != "cache":
            continue
        interface = condition.get("interface") or {}
        if interface.get("type") != "key_value" or interface.get("engine") != "redis":
            continue
        redis_cache_count += 1
        redis_name = f"{name}-cache" if redis_cache_count == 1 else f"{name}-cache-{redis_cache_count}"
        emitted.append(redis_request(redis_name, namespace, name))
        redis_host = f"{redis_name}.{namespace}.svc.cluster.local"
        env.extend(
            [
                {"name": "REDIS_URL", "value": f"redis://{redis_host}:6379"},
                {"name": "REDIS_HOST", "value": redis_host},
                {"name": "REDIS_PORT", "value": "6379"},
            ]
        )
        summary["caches"].append(
            {
                "condition": condition.get("name"),
                "resource": redis_name,
                "engine": "redis",
            }
        )

    emitted.extend(
        [
            workload_deployment(name, namespace, image, image_pull_policy, port, replicas, readiness_path, env),
            workload_service(name, namespace, port),
        ]
    )
    return OutputDocuments(emitted, summary)


def validate_api_condition(condition: dict[str, Any], apis: list[CatalogAPI]) -> CatalogAPI:
    interface = condition.get("interface") or {}
    if interface.get("type") != "http":
        raise ContractError(f"condition {condition.get('name')}: unsupported API interface {interface.get('type')!r}")

    operations = interface.get("operations") or []
    if not operations:
        return find_api(condition, apis, operations)

    api = find_api(condition, apis, operations)
    for operation in operations:
        validate_operation(condition, operation, api)
    return api


def find_api(condition: dict[str, Any], apis: list[CatalogAPI], operations: list[dict[str, Any]]) -> CatalogAPI:
    condition_name = condition.get("name")
    for api in apis:
        if api.name == condition_name:
            return api

    for api in apis:
        if all(openapi_has_operation(api.openapi, op.get("method"), op.get("path")) for op in operations):
            return api

    raise ContractError(f"condition api: {condition_name}\nresult: no matching catalog API")


def validate_operation(condition: dict[str, Any], operation: dict[str, Any], api: CatalogAPI) -> None:
    method = str(operation.get("method", "")).lower()
    path = operation.get("path")
    operation_doc = ((api.openapi.get("paths") or {}).get(path) or {}).get(method)
    if operation_doc is None:
        raise ContractError(
            f"condition:\n  api: {condition.get('name')}\n  operation: {method.upper()} {path}\n\n"
            "result:\n  missing operation in published OpenAPI"
        )

    expected = operation.get("responseSchema")
    if not expected:
        return

    published = response_schema(api.openapi, operation_doc)
    if published is None:
        raise ContractError(
            f"condition:\n  api: {condition.get('name')}\n  operation: {method.upper()} {path}\n\n"
            "result:\n  missing JSON response schema in published OpenAPI"
        )
    compare_schema(condition.get("name"), method.upper(), path, expected, published, api.openapi, "response")


def compare_schema(
    api_name: str,
    method: str,
    path: str,
    expected: Any,
    published: Any,
    openapi: dict[str, Any],
    location: str,
) -> None:
    published = resolve_ref(openapi, published)
    if isinstance(expected, str):
        published_type = published.get("type") if isinstance(published, dict) else published
        if published_type != expected:
            raise ContractError(
                f"condition:\n  api: {api_name}\n  operation: {method} {path}\n\n"
                f"expected by workload:\n  {location}: {expected}\n\n"
                f"published by catalog:\n  {location}: {published_type}\n\n"
                "result:\n  incompatible"
            )
        return

    if isinstance(expected, list):
        if not isinstance(published, dict) or published.get("type") != "array":
            raise ContractError(
                f"condition:\n  api: {api_name}\n  operation: {method} {path}\n\n"
                f"expected by workload:\n  {location}: array\n\n"
                f"published by catalog:\n  {location}: {published.get('type') if isinstance(published, dict) else published}\n\n"
                "result:\n  incompatible"
            )
        if expected:
            compare_schema(api_name, method, path, expected[0], published.get("items", {}), openapi, f"{location}[]")
        return

    if isinstance(expected, dict):
        if isinstance(published, dict) and published.get("type") not in (None, "object"):
            raise ContractError(
                f"condition:\n  api: {api_name}\n  operation: {method} {path}\n\n"
                f"expected by workload:\n  {location}: object\n\n"
                f"published by catalog:\n  {location}: {published.get('type')}\n\n"
                "result:\n  incompatible"
            )
        properties = published.get("properties", {}) if isinstance(published, dict) else {}
        for field, field_expected in expected.items():
            if field not in properties:
                raise ContractError(
                    f"condition:\n  api: {api_name}\n  operation: {method} {path}\n\n"
                    f"expected by workload:\n  {location}.{field}: present\n\n"
                    f"published by catalog:\n  {location}.{field}: missing\n\n"
                    "result:\n  incompatible"
                )
            compare_schema(api_name, method, path, field_expected, properties[field], openapi, f"{location}.{field}")


def response_schema(openapi: dict[str, Any], operation_doc: dict[str, Any]) -> dict[str, Any] | None:
    responses = operation_doc.get("responses") or {}
    response = responses.get("200") or next((value for key, value in responses.items() if str(key).startswith("2")), None)
    if not response:
        return None
    response = resolve_ref(openapi, response)
    content = response.get("content") or {}
    media = content.get("application/json") or next(iter(content.values()), None)
    if not media:
        return None
    return resolve_ref(openapi, media.get("schema") or {})


def resolve_ref(document: dict[str, Any], value: Any) -> Any:
    if not isinstance(value, dict) or "$ref" not in value:
        return value
    ref = value["$ref"]
    if not ref.startswith("#/"):
        raise ContractError(f"unsupported external OpenAPI reference: {ref}")
    current: Any = document
    for part in ref[2:].split("/"):
        current = current[part.replace("~1", "/").replace("~0", "~")]
    return current


def openapi_has_operation(openapi: dict[str, Any], method: str | None, path: str | None) -> bool:
    if not method or not path:
        return False
    return str(method).lower() in ((openapi.get("paths") or {}).get(path) or {})


def load_catalog(config_map_ref: dict[str, Any] | None) -> dict[str, str]:
    local_dir = os.getenv("RUNTIME_CONDITIONS_CATALOG_DIR")
    if local_dir:
        return {path.name: path.read_text(encoding="utf-8") for path in Path(local_dir).iterdir() if path.is_file()}

    if not config_map_ref:
        raise ContractError("spec.catalog.configMapRef is required")
    namespace = config_map_ref["namespace"]
    name = config_map_ref["name"]

    host = os.getenv("KUBERNETES_SERVICE_HOST")
    port = os.getenv("KUBERNETES_SERVICE_PORT", "443")
    token_path = Path("/var/run/secrets/kubernetes.io/serviceaccount/token")
    ca_path = Path("/var/run/secrets/kubernetes.io/serviceaccount/ca.crt")
    if not host or not token_path.exists():
        raise ContractError("cannot load catalog ConfigMap outside Kubernetes without RUNTIME_CONDITIONS_CATALOG_DIR")

    token = token_path.read_text(encoding="utf-8")
    url = f"https://{host}:{port}/api/v1/namespaces/{namespace}/configmaps/{name}"
    request = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})
    context = ssl.create_default_context(cafile=str(ca_path))
    try:
        with urllib.request.urlopen(request, context=context, timeout=10) as response:
            configmap = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        raise ContractError(f"could not read catalog ConfigMap {namespace}/{name}: HTTP {exc.code}") from exc
    return configmap.get("data") or {}


def parse_catalog_apis(catalog_data: dict[str, str]) -> list[CatalogAPI]:
    apis: list[CatalogAPI] = []
    for key, value in catalog_data.items():
        if not key.endswith((".catalog-info.yaml", ".catalog-info.yml")):
            continue
        for entity in yaml.safe_load_all(value):
            if not entity or entity.get("kind") != "API":
                continue
            name = entity.get("metadata", {}).get("name")
            definition_ref = (
                entity.get("spec", {})
                .get("definition", {})
                .get("$text")
            )
            if not definition_ref:
                continue
            definition_key = Path(str(definition_ref).removeprefix("./")).name
            if definition_key not in catalog_data:
                raise ContractError(f"catalog API {name} references missing OpenAPI document {definition_ref}")
            annotations = entity.get("metadata", {}).get("annotations", {})
            apis.append(
                CatalogAPI(
                    name=name,
                    definition=entity,
                    openapi=yaml.safe_load(catalog_data[definition_key]),
                    base_url=annotations.get("runtimeconditions.io/base-url"),
                )
            )
    return apis


def redis_request(name: str, namespace: str, workload_name: str) -> dict[str, Any]:
    return {
        "apiVersion": "runtimeconditions.io/v1alpha1",
        "kind": "Redis",
        "metadata": {
            "name": name,
            "namespace": namespace,
            "labels": {
                "kratix.io/component-of-promise-name": "runtime-workload",
                "kratix.io/component-of-resource-name": workload_name,
                "kratix.io/component-of-resource-namespace": namespace,
            },
        },
        "spec": {"size": "small"},
    }


def workload_deployment(
    name: str,
    namespace: str,
    image: str,
    image_pull_policy: str,
    port: int,
    replicas: int,
    readiness_path: str,
    env: list[dict[str, str]],
) -> dict[str, Any]:
    labels = {
        "app.kubernetes.io/name": name,
        "app.kubernetes.io/component": "application",
        "app.kubernetes.io/managed-by": "kratix",
    }
    return {
        "apiVersion": "apps/v1",
        "kind": "Deployment",
        "metadata": {"name": name, "namespace": namespace, "labels": labels},
        "spec": {
            "replicas": replicas,
            "selector": {"matchLabels": {"app.kubernetes.io/name": name}},
            "template": {
                "metadata": {"labels": labels},
                "spec": {
                    "containers": [
                        {
                            "name": "app",
                            "image": image,
                            "imagePullPolicy": image_pull_policy,
                            "ports": [{"name": "http", "containerPort": port}],
                            "env": env,
                            "readinessProbe": {
                                "httpGet": {"path": readiness_path, "port": "http"},
                                "initialDelaySeconds": 3,
                                "periodSeconds": 5,
                            },
                            "livenessProbe": {
                                "httpGet": {"path": readiness_path, "port": "http"},
                                "initialDelaySeconds": 15,
                                "periodSeconds": 10,
                            },
                        }
                    ]
                },
            },
        },
    }


def workload_service(name: str, namespace: str, port: int) -> dict[str, Any]:
    return {
        "apiVersion": "v1",
        "kind": "Service",
        "metadata": {
            "name": name,
            "namespace": namespace,
            "labels": {
                "app.kubernetes.io/name": name,
                "app.kubernetes.io/managed-by": "kratix",
            },
        },
        "spec": {
            "selector": {"app.kubernetes.io/name": name},
            "ports": [{"name": "http", "port": port, "targetPort": "http"}],
        },
    }


def api_url_env_name(name: str) -> str:
    normalized = re.sub(r"[^A-Za-z0-9]+", "_", name).strip("_").upper()
    return f"{normalized}_URL"


def require_string(mapping: dict[str, Any], key: str) -> str:
    value = mapping.get(key)
    if not isinstance(value, str) or not value:
        raise ContractError(f"spec.{key} is required")
    return value


def write_yaml_documents(path: Path, documents: list[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8") as file:
        yaml.dump_all(documents, file, Dumper=NoAliasDumper, sort_keys=False)


def write_status(status: dict[str, Any]) -> None:
    METADATA_DIR.mkdir(parents=True, exist_ok=True)
    (METADATA_DIR / "status.yaml").write_text(
        yaml.dump(status, Dumper=NoAliasDumper, sort_keys=False),
        encoding="utf-8",
    )


if __name__ == "__main__":
    raise SystemExit(main())

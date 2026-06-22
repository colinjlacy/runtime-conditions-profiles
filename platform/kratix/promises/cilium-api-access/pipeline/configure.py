from __future__ import annotations

import os
import re
from pathlib import Path
from typing import Any

import yaml


INPUT_DIR = Path(os.getenv("KRATIX_INPUT_DIR", "/kratix/input"))
OUTPUT_DIR = Path(os.getenv("KRATIX_OUTPUT_DIR", "/kratix/output"))
METADATA_DIR = Path(os.getenv("KRATIX_METADATA_DIR", "/kratix/metadata"))


class RequestError(Exception):
    pass


class NoAliasDumper(yaml.SafeDumper):
    def ignore_aliases(self, data: Any) -> bool:
        return True


def main() -> int:
    try:
        request = yaml.safe_load((INPUT_DIR / "object.yaml").read_text(encoding="utf-8"))
        policy, status = render_policy(request)

        OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
        write_yaml_documents(OUTPUT_DIR / "cilium-api-access.yaml", [policy])

        METADATA_DIR.mkdir(parents=True, exist_ok=True)
        (METADATA_DIR / "status.yaml").write_text(
            yaml.dump(status, Dumper=NoAliasDumper, sort_keys=False),
            encoding="utf-8",
        )
        return 0
    except RequestError as exc:
        message = str(exc)
        print(message)
        METADATA_DIR.mkdir(parents=True, exist_ok=True)
        (METADATA_DIR / "status.yaml").write_text(
            yaml.dump({"message": "Cilium API access request rejected", "error": message}, sort_keys=False),
            encoding="utf-8",
        )
        return 1


def render_policy(request: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    metadata = request.get("metadata") or {}
    spec = request.get("spec") or {}
    name = require_string(metadata, "name", "metadata.name")
    namespace = metadata.get("namespace") or "default"

    selector = require_mapping(spec, "workloadSelector", "spec.workloadSelector")
    match_labels = require_mapping(selector, "matchLabels", "spec.workloadSelector.matchLabels")
    destination = require_mapping(spec, "destination", "spec.destination")
    port = str(require_int(destination, "port", "spec.destination.port"))
    protocol = destination.get("protocol") or "TCP"
    if protocol != "TCP":
        raise RequestError("spec.destination.protocol must be TCP")

    rules = render_http_rules(require_list(spec, "rules", "spec.rules"))
    destination_selector = destination.get("podSelector")
    egress = {
        **render_destination(destination),
        "toPorts": [
            {
                "ports": [
                    {
                        "port": port,
                        "protocol": protocol,
                    }
                ],
                "rules": {
                    "http": [
                        {"method": rule["method"], "path": rule["policyPath"]}
                        for rule in rules
                    ]
                },
            }
        ],
    }

    labels = {
        "app.kubernetes.io/name": name,
        "app.kubernetes.io/component": "network-policy",
        "app.kubernetes.io/managed-by": "kratix",
        "platform.demoteam.dev/policy-type": "api-access",
    }
    policy: dict[str, Any] = {
        "apiVersion": "cilium.io/v2",
        "kind": "CiliumNetworkPolicy",
        "metadata": {"name": name, "namespace": namespace, "labels": labels},
    }
    egress_spec = {
        "endpointSelector": {"matchLabels": match_labels},
        "egress": [egress],
    }
    if destination_selector:
        destination_match_labels = require_mapping(
            require_mapping(destination, "podSelector", "spec.destination.podSelector"),
            "matchLabels",
            "spec.destination.podSelector.matchLabels",
        )
        policy["specs"] = [
            egress_spec,
            {
                "endpointSelector": {"matchLabels": destination_match_labels},
                "ingress": [
                    {
                        "fromEndpoints": [{"matchLabels": match_labels}],
                        "toPorts": [
                            {
                                "ports": [
                                    {
                                        "port": port,
                                        "protocol": protocol,
                                    }
                                ],
                                "rules": {
                                    "http": [
                                        {"method": rule["method"], "path": rule["policyPath"]}
                                        for rule in rules
                                    ]
                                },
                            }
                        ],
                    }
                ],
            },
        ]
    else:
        policy["spec"] = egress_spec
    status = {
        "message": "Cilium API access policy rendered",
        "policy": name,
        "destination": summarize_destination(destination),
        "rules": rules,
    }
    return policy, status


def render_destination(destination: dict[str, Any]) -> dict[str, Any]:
    service = destination.get("service")
    fqdn = destination.get("fqdn")
    if service and fqdn:
        raise RequestError("spec.destination must define service or fqdn, not both")
    if service:
        service_mapping = require_mapping(destination, "service", "spec.destination.service")
        return {
            "toServices": [
                {
                    "k8sService": {
                        "serviceName": require_string(service_mapping, "name", "spec.destination.service.name"),
                        "namespace": require_string(
                            service_mapping,
                            "namespace",
                            "spec.destination.service.namespace",
                        ),
                    }
                }
            ]
        }
    if fqdn:
        return {"toFQDNs": [{"matchName": require_string(destination, "fqdn", "spec.destination.fqdn")}]}
    raise RequestError("spec.destination must define service or fqdn")


def summarize_destination(destination: dict[str, Any]) -> dict[str, Any]:
    if destination.get("service"):
        service = destination["service"]
        return {
            "service": f"{service.get('namespace')}/{service.get('name')}",
            "port": destination.get("port"),
        }
    return {"fqdn": destination.get("fqdn"), "port": destination.get("port")}


def render_http_rules(rules: list[Any]) -> list[dict[str, str]]:
    if not rules:
        raise RequestError("spec.rules must contain at least one HTTP rule")

    rendered: list[dict[str, str]] = []
    for index, raw_rule in enumerate(rules):
        if not isinstance(raw_rule, dict):
            raise RequestError(f"spec.rules[{index}] must be an object")
        method = require_string(raw_rule, "method", f"spec.rules[{index}].method").upper()
        path = require_string(raw_rule, "path", f"spec.rules[{index}].path")
        rendered.append(
            {
                "method": method,
                "declaredPath": path,
                "policyPath": cilium_http_path(path),
            }
        )
    return rendered


def cilium_http_path(path: str) -> str:
    if not path.startswith("/"):
        raise RequestError(f"HTTP path must start with /: {path}")

    output = ["^"]
    index = 0
    while index < len(path):
        char = path[index]
        if char == "{":
            end = path.find("}", index + 1)
            if end < 0:
                raise RequestError(f"HTTP path template contains an unterminated parameter: {path}")
            parameter_name = path[index + 1:end]
            if not parameter_name:
                raise RequestError(f"HTTP path template contains an empty parameter: {path}")
            output.append("[^/]+")
            index = end + 1
            continue
        if char == "}":
            raise RequestError(f"HTTP path template contains an unmatched }}: {path}")
        output.append(re.escape(char))
        index += 1
    output.append("$")
    return "".join(output)


def require_mapping(mapping: dict[str, Any], key: str, path: str) -> dict[str, Any]:
    value = mapping.get(key)
    if not isinstance(value, dict):
        raise RequestError(f"{path} must be an object")
    return value


def require_list(mapping: dict[str, Any], key: str, path: str) -> list[Any]:
    value = mapping.get(key)
    if not isinstance(value, list):
        raise RequestError(f"{path} must be an array")
    return value


def require_string(mapping: dict[str, Any], key: str, path: str) -> str:
    value = mapping.get(key)
    if not isinstance(value, str) or not value:
        raise RequestError(f"{path} must be a non-empty string")
    return value


def require_int(mapping: dict[str, Any], key: str, path: str) -> int:
    value = mapping.get(key)
    if not isinstance(value, int):
        raise RequestError(f"{path} must be an integer")
    return value


def write_yaml_documents(path: Path, documents: list[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8") as file:
        yaml.dump_all(documents, file, Dumper=NoAliasDumper, sort_keys=False)


if __name__ == "__main__":
    raise SystemExit(main())

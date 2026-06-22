from __future__ import annotations

import os
from pathlib import Path
from typing import Any

import yaml


INPUT_DIR = Path(os.getenv("KRATIX_INPUT_DIR", "/kratix/input"))
OUTPUT_DIR = Path(os.getenv("KRATIX_OUTPUT_DIR", "/kratix/output"))
METADATA_DIR = Path(os.getenv("KRATIX_METADATA_DIR", "/kratix/metadata"))


class NoAliasDumper(yaml.SafeDumper):
    def ignore_aliases(self, data: Any) -> bool:
        return True


def main() -> int:
    request = yaml.safe_load((INPUT_DIR / "object.yaml").read_text(encoding="utf-8"))
    metadata = request.get("metadata", {})
    spec = request.get("spec", {})
    name = metadata["name"]
    namespace = metadata.get("namespace", "default")
    size = spec.get("size", "small")
    consumers = spec.get("consumers") or []

    labels = {
        "app.kubernetes.io/name": name,
        "app.kubernetes.io/component": "cache",
        "app.kubernetes.io/managed-by": "kratix",
        "platform.demoteam.dev/cache-engine": "redis",
    }

    deployment: dict[str, Any] = {
        "apiVersion": "apps/v1",
        "kind": "Deployment",
        "metadata": {"name": name, "namespace": namespace, "labels": labels},
        "spec": {
            "replicas": 1,
            "selector": {"matchLabels": {"app.kubernetes.io/name": name}},
            "template": {
                "metadata": {"labels": labels},
                "spec": {
                    "containers": [
                        {
                            "name": "redis",
                            "image": "redis:7-alpine",
                            "ports": [{"name": "redis", "containerPort": 6379}],
                            "resources": resources_for_size(size),
                            "readinessProbe": {
                                "tcpSocket": {"port": "redis"},
                                "initialDelaySeconds": 2,
                                "periodSeconds": 5,
                            },
                            "livenessProbe": {
                                "tcpSocket": {"port": "redis"},
                                "initialDelaySeconds": 10,
                                "periodSeconds": 10,
                            },
                        }
                    ]
                },
            },
        },
    }

    service: dict[str, Any] = {
        "apiVersion": "v1",
        "kind": "Service",
        "metadata": {"name": name, "namespace": namespace, "labels": labels},
        "spec": {
            "selector": {"app.kubernetes.io/name": name},
            "ports": [{"name": "redis", "port": 6379, "targetPort": "redis"}],
        },
    }

    host = f"{name}.{namespace}.svc.cluster.local"
    connection_config: dict[str, Any] = {
        "apiVersion": "v1",
        "kind": "ConfigMap",
        "metadata": {"name": f"{name}-connection", "namespace": namespace, "labels": labels},
        "data": {
            "url": f"redis://{host}:6379",
            "hostname": host,
            "port": "6379",
        },
    }

    policies = [redis_access_policy(name, namespace, labels, consumer) for consumer in consumers]

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    write_yaml_documents(OUTPUT_DIR / "redis.yaml", [deployment, service, connection_config, *policies])

    status = {
        "message": "Redis cache provisioned",
        "connectionDetails": {
            "configMap": connection_config["metadata"]["name"],
            "host": host,
            "port": 6379,
            "url": f"redis://{host}:6379",
        },
        "networkPolicies": [policy["metadata"]["name"] for policy in policies],
    }
    METADATA_DIR.mkdir(parents=True, exist_ok=True)
    (METADATA_DIR / "status.yaml").write_text(
        yaml.dump(status, Dumper=NoAliasDumper, sort_keys=False),
        encoding="utf-8",
    )
    return 0


def redis_access_policy(
    name: str,
    namespace: str,
    redis_labels: dict[str, str],
    consumer: dict[str, Any],
) -> dict[str, Any]:
    consumer_name = consumer.get("name") or "consumer"
    selector = consumer.get("workloadSelector") or {}
    match_labels = selector.get("matchLabels")
    if not isinstance(match_labels, dict) or not match_labels:
        raise ValueError("Redis consumers require workloadSelector.matchLabels")

    policy_name = dns_name(f"{name}-{consumer_name}-access")
    return {
        "apiVersion": "cilium.io/v2",
        "kind": "CiliumNetworkPolicy",
        "metadata": {
            "name": policy_name,
            "namespace": namespace,
            "labels": {
                "app.kubernetes.io/name": policy_name,
                "app.kubernetes.io/component": "network-policy",
                "app.kubernetes.io/managed-by": "kratix",
                "platform.demoteam.dev/policy-type": "cache-access",
            },
        },
        "specs": [
            {
                "endpointSelector": {"matchLabels": match_labels},
                "egress": [
                    {
                        "toServices": [
                            {
                                "k8sService": {
                                    "serviceName": name,
                                    "namespace": namespace,
                                }
                            }
                        ],
                        "toPorts": [
                            {
                                "ports": [
                                    {
                                        "port": "6379",
                                        "protocol": "TCP",
                                    }
                                ]
                            }
                        ],
                    }
                ],
            },
            {
                "endpointSelector": {"matchLabels": {"app.kubernetes.io/name": redis_labels["app.kubernetes.io/name"]}},
                "ingress": [
                    {
                        "fromEndpoints": [{"matchLabels": match_labels}],
                        "toPorts": [
                            {
                                "ports": [
                                    {
                                        "port": "6379",
                                        "protocol": "TCP",
                                    }
                                ]
                            }
                        ],
                    }
                ],
            },
        ],
    }


def resources_for_size(size: str) -> dict[str, Any]:
    if size == "medium":
        return {
            "requests": {"cpu": "100m", "memory": "128Mi"},
            "limits": {"cpu": "500m", "memory": "512Mi"},
        }
    if size == "large":
        return {
            "requests": {"cpu": "250m", "memory": "512Mi"},
            "limits": {"cpu": "1", "memory": "1Gi"},
        }
    return {
        "requests": {"cpu": "50m", "memory": "64Mi"},
        "limits": {"cpu": "250m", "memory": "256Mi"},
    }


def dns_name(value: str) -> str:
    normalized = "".join(char.lower() if char.isalnum() else "-" for char in value)
    normalized = "-".join(part for part in normalized.split("-") if part)
    if not normalized:
        return "runtime-condition"
    return normalized[:63].strip("-")


def write_yaml_documents(path: Path, documents: list[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8") as file:
        yaml.dump_all(documents, file, Dumper=NoAliasDumper, sort_keys=False)


if __name__ == "__main__":
    raise SystemExit(main())

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

    labels = {
        "app.kubernetes.io/name": name,
        "app.kubernetes.io/component": "cache",
        "app.kubernetes.io/managed-by": "kratix",
        "runtimeconditions.io/cache-engine": "redis",
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

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    write_yaml_documents(OUTPUT_DIR / "redis.yaml", [deployment, service])

    host = f"{name}.{namespace}.svc.cluster.local"
    status = {
        "message": "Redis cache provisioned",
        "connectionDetails": {
            "host": host,
            "port": 6379,
            "url": f"redis://{host}:6379",
        },
    }
    METADATA_DIR.mkdir(parents=True, exist_ok=True)
    (METADATA_DIR / "status.yaml").write_text(
        yaml.dump(status, Dumper=NoAliasDumper, sort_keys=False),
        encoding="utf-8",
    )
    return 0


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


def write_yaml_documents(path: Path, documents: list[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8") as file:
        yaml.dump_all(documents, file, Dumper=NoAliasDumper, sort_keys=False)


if __name__ == "__main__":
    raise SystemExit(main())

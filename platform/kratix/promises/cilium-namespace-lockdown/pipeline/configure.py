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
    metadata = request.get("metadata") or {}
    spec = request.get("spec") or {}
    name = metadata["name"]
    namespace = metadata.get("namespace", "default")
    allow_dns = spec.get("allowDNS", True)

    policy = {
        "apiVersion": "cilium.io/v2",
        "kind": "CiliumNetworkPolicy",
        "metadata": {
            "name": name,
            "namespace": namespace,
            "labels": {
                "app.kubernetes.io/name": name,
                "app.kubernetes.io/component": "network-policy",
                "app.kubernetes.io/managed-by": "kratix",
                "platform.demoteam.dev/policy-type": "namespace-lockdown",
            },
        },
        "spec": {
            "endpointSelector": {},
            "ingress": [],
            "egress": dns_egress_rules() if allow_dns else [],
        },
    }

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    write_yaml_documents(OUTPUT_DIR / "cilium-namespace-lockdown.yaml", [policy])

    status = {
        "message": "Cilium namespace lockdown policy rendered",
        "policy": name,
        "allowDNS": allow_dns,
    }
    METADATA_DIR.mkdir(parents=True, exist_ok=True)
    (METADATA_DIR / "status.yaml").write_text(
        yaml.dump(status, Dumper=NoAliasDumper, sort_keys=False),
        encoding="utf-8",
    )
    return 0


def dns_egress_rules() -> list[dict[str, Any]]:
    dns_endpoint = {
        "matchLabels": {
            "k8s:io.kubernetes.pod.namespace": "kube-system",
            "k8s:k8s-app": "kube-dns",
        }
    }
    return [
        {
            "toEndpoints": [dns_endpoint],
            "toPorts": [
                {
                    "ports": [
                        {"port": "53", "protocol": "UDP"},
                        {"port": "53", "protocol": "TCP"},
                    ],
                    "rules": {"dns": [{"matchPattern": "*"}]},
                }
            ],
        }
    ]


def write_yaml_documents(path: Path, documents: list[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8") as file:
        yaml.dump_all(documents, file, Dumper=NoAliasDumper, sort_keys=False)


if __name__ == "__main__":
    raise SystemExit(main())

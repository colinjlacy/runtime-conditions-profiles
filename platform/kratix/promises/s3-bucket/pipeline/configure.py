from __future__ import annotations

import base64
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
    bucket = spec.get("bucketName") or name
    region = spec.get("region", "us-east-1")
    consumers = spec.get("consumers") or []

    labels = {
        "app.kubernetes.io/name": name,
        "app.kubernetes.io/component": "object-store",
        "app.kubernetes.io/managed-by": "kratix",
        "runtimeconditions.io/object-store-interface": "aws.s3",
    }

    connection_config = {
        "apiVersion": "v1",
        "kind": "ConfigMap",
        "metadata": {"name": f"{name}-connection", "namespace": namespace, "labels": labels},
        "data": {
            "bucket": bucket,
            "region": region,
        },
    }

    credentials = {
        "apiVersion": "v1",
        "kind": "Secret",
        "metadata": {"name": f"{name}-credentials", "namespace": namespace, "labels": labels},
        "type": "Opaque",
        "data": {
            "accessKeyId": b64("demo-access-key"),
            "secretAccessKey": b64("demo-secret-key"),
        },
    }

    policies = [s3_access_policy(name, namespace, bucket, region, consumer) for consumer in consumers]

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    write_yaml_documents(OUTPUT_DIR / "s3-bucket.yaml", [connection_config, credentials, *policies])

    status = {
        "message": "S3 bucket access provisioned",
        "connectionDetails": {
            "configMap": connection_config["metadata"]["name"],
            "secret": credentials["metadata"]["name"],
            "bucket": bucket,
            "region": region,
        },
        "networkPolicies": [policy["metadata"]["name"] for policy in policies],
    }
    METADATA_DIR.mkdir(parents=True, exist_ok=True)
    (METADATA_DIR / "status.yaml").write_text(
        yaml.dump(status, Dumper=NoAliasDumper, sort_keys=False),
        encoding="utf-8",
    )
    return 0


def s3_access_policy(
    name: str,
    namespace: str,
    bucket: str,
    region: str,
    consumer: dict[str, Any],
) -> dict[str, Any]:
    consumer_name = consumer.get("name") or "consumer"
    selector = consumer.get("workloadSelector") or {}
    match_labels = selector.get("matchLabels")
    if not isinstance(match_labels, dict) or not match_labels:
        raise ValueError("S3Bucket consumers require workloadSelector.matchLabels")

    fqdn_matches = [
        {"matchName": f"s3.{region}.amazonaws.com"},
        {"matchName": f"{bucket}.s3.{region}.amazonaws.com"},
        {"matchPattern": f"*.s3.{region}.amazonaws.com"},
    ]
    if region == "us-east-1":
        fqdn_matches.append({"matchName": "s3.amazonaws.com"})

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
                "runtimeconditions.io/policy-type": "object-store-access",
            },
        },
        "spec": {
            "endpointSelector": {"matchLabels": match_labels},
            "egress": [
                {
                    "toFQDNs": fqdn_matches,
                    "toPorts": [
                        {
                            "ports": [
                                {
                                    "port": "443",
                                    "protocol": "TCP",
                                }
                            ]
                        }
                    ],
                }
            ],
        },
    }


def b64(value: str) -> str:
    return base64.b64encode(value.encode("utf-8")).decode("ascii")


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

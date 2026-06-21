from __future__ import annotations

import base64
import hashlib
import json
import os
from pathlib import Path
from typing import Any

import yaml


INPUT_DIR = Path(os.getenv("KRATIX_INPUT_DIR", "/kratix/input"))
OUTPUT_DIR = Path(os.getenv("KRATIX_OUTPUT_DIR", "/kratix/output"))
METADATA_DIR = Path(os.getenv("KRATIX_METADATA_DIR", "/kratix/metadata"))


class ProvisioningError(Exception):
    pass


class NoAliasDumper(yaml.SafeDumper):
    def ignore_aliases(self, data: Any) -> bool:
        return True


def main() -> int:
    try:
        request = yaml.safe_load((INPUT_DIR / "object.yaml").read_text(encoding="utf-8"))
        output, status = provision(request)
        OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
        write_yaml_documents(OUTPUT_DIR / "s3-bucket.yaml", output)
        write_status(status)
        return 0
    except Exception as exc:
        message = str(exc)
        print(f"S3 bucket provisioning failed: {message}")
        write_status({"message": "S3 bucket provisioning failed", "error": message})
        return 1


def provision(request: dict[str, Any]) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    metadata = request.get("metadata", {})
    spec = request.get("spec", {})
    name = metadata["name"]
    namespace = metadata.get("namespace", "default")
    region = spec.get("region", "us-east-1")
    consumers = spec.get("consumers") or []

    aws = provision_aws_resources(name, namespace, spec, region)
    bucket = aws["bucket"]
    iam_user_name = aws["iamUserName"]

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
            "accessKeyId": b64(aws["accessKeyId"]),
            "secretAccessKey": b64(aws["secretAccessKey"]),
        },
    }

    policies = [s3_access_policy(name, namespace, bucket, region, consumer) for consumer in consumers]

    status = {
        "message": "S3 bucket and IAM credentials provisioned",
        "aws": {
            "bucket": bucket,
            "region": region,
            "iamUserName": iam_user_name,
        },
        "connectionDetails": {
            "configMap": connection_config["metadata"]["name"],
            "secret": credentials["metadata"]["name"],
            "bucket": bucket,
            "region": region,
        },
        "networkPolicies": [policy["metadata"]["name"] for policy in policies],
    }
    return [connection_config, credentials, *policies], status


def provision_aws_resources(name: str, namespace: str, spec: dict[str, Any], region: str) -> dict[str, str]:
    import boto3

    admin_access_key_id = os.getenv("AWS_ADMIN_ACCESS_KEY_ID")
    admin_secret_access_key = os.getenv("AWS_ADMIN_SECRET_ACCESS_KEY")
    if not admin_access_key_id or not admin_secret_access_key:
        raise ProvisioningError(
            "AWS admin credentials are required in AWS_ADMIN_ACCESS_KEY_ID and AWS_ADMIN_SECRET_ACCESS_KEY"
        )

    session = boto3.Session(
        aws_access_key_id=admin_access_key_id,
        aws_secret_access_key=admin_secret_access_key,
        region_name=region,
    )
    sts = session.client("sts")
    account_id = sts.get_caller_identity()["Account"]
    bucket = spec.get("bucketName") or default_bucket_name(namespace, name, account_id, region)
    iam_user_name = spec.get("iamUserName") or default_iam_user_name(namespace, name)

    s3 = session.client("s3", region_name=region)
    ensure_bucket(s3, bucket, region)
    ensure_bucket_controls(s3, bucket)

    iam = session.client("iam")
    ensure_iam_user(iam, iam_user_name, namespace, name)
    ensure_bucket_policy(iam, iam_user_name, bucket)
    access_key = issue_access_key(iam, iam_user_name)

    return {
        "bucket": bucket,
        "region": region,
        "iamUserName": iam_user_name,
        "accessKeyId": access_key["AccessKeyId"],
        "secretAccessKey": access_key["SecretAccessKey"],
    }


def ensure_bucket(s3: Any, bucket: str, region: str) -> None:
    from botocore.exceptions import ClientError

    try:
        s3.head_bucket(Bucket=bucket)
        return
    except ClientError as exc:
        code = str(exc.response.get("Error", {}).get("Code", ""))
        if code not in {"404", "NoSuchBucket", "NotFound"}:
            raise

    create_args: dict[str, Any] = {"Bucket": bucket}
    if region != "us-east-1":
        create_args["CreateBucketConfiguration"] = {"LocationConstraint": region}
    s3.create_bucket(**create_args)
    s3.get_waiter("bucket_exists").wait(Bucket=bucket)


def ensure_bucket_controls(s3: Any, bucket: str) -> None:
    s3.put_public_access_block(
        Bucket=bucket,
        PublicAccessBlockConfiguration={
            "BlockPublicAcls": True,
            "IgnorePublicAcls": True,
            "BlockPublicPolicy": True,
            "RestrictPublicBuckets": True,
        },
    )
    s3.put_bucket_encryption(
        Bucket=bucket,
        ServerSideEncryptionConfiguration={
            "Rules": [
                {
                    "ApplyServerSideEncryptionByDefault": {
                        "SSEAlgorithm": "AES256",
                    }
                }
            ]
        },
    )


def ensure_iam_user(iam: Any, user_name: str, namespace: str, resource_name: str) -> None:
    from botocore.exceptions import ClientError

    try:
        iam.get_user(UserName=user_name)
    except ClientError as exc:
        if exc.response.get("Error", {}).get("Code") != "NoSuchEntity":
            raise
        iam.create_user(
            UserName=user_name,
            Tags=[
                {"Key": "managed-by", "Value": "runtimeconditions-kratix-demo"},
                {"Key": "runtimeconditions-namespace", "Value": namespace},
                {"Key": "runtimeconditions-resource", "Value": resource_name},
            ],
        )


def ensure_bucket_policy(iam: Any, user_name: str, bucket: str) -> None:
    policy_document = {
        "Version": "2012-10-17",
        "Statement": [
            {
                "Effect": "Allow",
                "Action": [
                    "s3:GetBucketLocation",
                    "s3:ListBucket",
                ],
                "Resource": f"arn:aws:s3:::{bucket}",
            },
            {
                "Effect": "Allow",
                "Action": [
                    "s3:AbortMultipartUpload",
                    "s3:DeleteObject",
                    "s3:GetObject",
                    "s3:PutObject",
                ],
                "Resource": f"arn:aws:s3:::{bucket}/*",
            },
        ],
    }
    iam.put_user_policy(
        UserName=user_name,
        PolicyName="runtimeconditions-s3-access",
        PolicyDocument=json.dumps(policy_document),
    )


def issue_access_key(iam: Any, user_name: str) -> dict[str, str]:
    keys = iam.list_access_keys(UserName=user_name).get("AccessKeyMetadata", [])

    sorted_keys = sorted(keys, key=lambda key: key.get("CreateDate"))
    while len(sorted_keys) >= 2:
        oldest = sorted_keys.pop(0)
        iam.delete_access_key(UserName=user_name, AccessKeyId=oldest["AccessKeyId"])

    old_key_ids = [key["AccessKeyId"] for key in sorted_keys]
    response = iam.create_access_key(UserName=user_name)
    access_key = response["AccessKey"]

    for key_id in old_key_ids:
        iam.delete_access_key(UserName=user_name, AccessKeyId=key_id)

    return {
        "AccessKeyId": access_key["AccessKeyId"],
        "SecretAccessKey": access_key["SecretAccessKey"],
    }


def default_bucket_name(namespace: str, name: str, account_id: str, region: str) -> str:
    prefix = dns_name(f"rc-{namespace}-{name}")
    suffix = hashlib.sha256(f"{namespace}/{name}/{account_id}/{region}".encode("utf-8")).hexdigest()[:10]
    max_prefix_length = 63 - len(account_id) - len(suffix) - 2
    return f"{prefix[:max_prefix_length].strip('-')}-{account_id}-{suffix}"


def default_iam_user_name(namespace: str, name: str) -> str:
    base = dns_name(f"rc-{namespace}-{name}")
    suffix = hashlib.sha256(f"{namespace}/{name}".encode("utf-8")).hexdigest()[:8]
    max_base_length = 64 - len(suffix) - 1
    return f"{base[:max_base_length].strip('-')}-{suffix}"


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
    normalized = normalized.strip("-")
    if not normalized:
        return "runtime-condition"
    return normalized[:63].strip("-")


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

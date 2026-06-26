#!/usr/bin/env bash
set -euo pipefail

for command in kubectl curl; do
  if ! command -v "${command}" >/dev/null 2>&1; then
    printf '[platform-demo] required command not found: %s\n' "${command}" >&2
    exit 1
  fi
done

kubectl version --client=true
curl --version

printf '[platform-demo] checking cluster access\n'
kubectl get nodes

printf '[platform-demo] prerequisites look usable\n'

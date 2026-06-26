#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MANIFEST_DIR="$(cd "${SCRIPT_DIR}/../manifests" && pwd)"

printf '[platform-demo] submitting ApplicationRelease request through Kratix\n'
kubectl apply -f "${MANIFEST_DIR}/apps/request-logger-application-release.yaml"

printf '[platform-demo] waiting for ApplicationRelease configure workflow\n'
kubectl -n demo wait applicationrelease/request-logger \
  --for=condition=ConfigureWorkflowCompleted \
  --timeout=180s

printf '[platform-demo] waiting for generated Redis request\n'
kubectl -n demo wait redis/request-logger-cache \
  --for=create \
  --timeout=180s

kubectl -n demo wait redis/request-logger-cache \
  --for=condition=ConfigureWorkflowCompleted \
  --timeout=180s

printf '[platform-demo] waiting for generated application Deployment\n'
kubectl -n demo rollout status deployment/request-logger --timeout=240s

kubectl -n demo get applicationrelease request-logger
kubectl -n demo get redis request-logger-cache
kubectl -n demo get deployment request-logger

#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MANIFEST_DIR="$(cd "${SCRIPT_DIR}/../manifests" && pwd)"

printf '[platform-demo] installing Redis Promise\n'
kubectl apply -f "${MANIFEST_DIR}/promises/redis.yaml"
kubectl wait --for=condition=Established crd/redis.platform.demoteam.io --timeout=120s

printf '[platform-demo] installing ApplicationRelease Promise\n'
kubectl apply -f "${MANIFEST_DIR}/promises/application-release.yaml"
kubectl wait --for=condition=Established crd/applicationreleases.platform.demoteam.io --timeout=120s

printf '[platform-demo] platform Promises are installed\n'
kubectl get crds -l kratix.io/promise-name

#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MANIFEST_DIR="$(cd "${SCRIPT_DIR}/../manifests" && pwd)"

KRATIX_INSTALLER_URL="${KRATIX_INSTALLER_URL:-https://github.com/syntasso/kratix/releases/download/latest/kratix-quick-start-installer.yaml}"

printf '[platform-demo] applying demo namespaces\n'
kubectl apply -f "${MANIFEST_DIR}/namespaces.yaml"

printf '[platform-demo] installing Kratix quick-start stack\n'
kubectl apply -f "${KRATIX_INSTALLER_URL}"

printf '[platform-demo] waiting for quick-start installer job\n'
kubectl -n default wait --for=condition=complete job/kratix-quick-start-installer --timeout=10m

printf '[platform-demo] waiting for Kratix platform controller\n'
kubectl -n kratix-platform-system rollout status deployment/kratix-platform-controller-manager --timeout=5m

printf '[platform-demo] Kratix pods\n'
kubectl get pods -n kratix-platform-system

printf '[platform-demo] Kratix installation step complete\n'

#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"

require_kubectl_context

KRATIX_INSTALLER_URL="${KRATIX_INSTALLER_URL:-https://github.com/syntasso/kratix/releases/download/latest/kratix-quick-start-installer.yaml}"

log "Ensure Kratix namespace exists"
kubectl create namespace kratix-platform-system --dry-run=client -o yaml | kubectl apply -f -

log "Installing Kratix quick-start stack"
kubectl apply -f "${KRATIX_INSTALLER_URL}"

log "Waiting for quick-start installer job"
kubectl -n default wait --for=condition=complete job/kratix-quick-start-installer --timeout=10m

log "Waiting for Kratix platform controller"
kubectl -n kratix-platform-system rollout status deployment/kratix-platform-controller-manager --timeout=5m

log "Creating demo namespaces"
kubectl create namespace "${DEMO_NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -
kubectl create namespace "${CATALOG_NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -

log "Kratix pods"
kubectl get pods -n kratix-platform-system

log "Kratix installation step complete"


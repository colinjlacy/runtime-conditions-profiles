#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"
source_generated_env

require_kubectl_context

[[ -n "${TODOS_API_IMAGE:-}" ]] || fail "TODOS_API_IMAGE is not set; run 02-build-and-push-images.sh first"

kubectl create namespace "${DEMO_NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -
kubectl create namespace "${CATALOG_NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -

CATALOG_BUILD_DIR="${BUILD_DIR}/catalog"
mkdir -p "${CATALOG_BUILD_DIR}"
cp "${PLATFORM_DIR}/catalog/apis/todos-api.catalog-info.yaml" "${CATALOG_BUILD_DIR}/todos-api.catalog-info.yaml"
cp "${PLATFORM_DIR}/catalog/apis/todos.openapi.yaml" "${CATALOG_BUILD_DIR}/todos.openapi.yaml"

log "Publishing compatible API catalog bundle"
kubectl -n "${CATALOG_NAMESPACE}" create configmap "${CATALOG_CONFIGMAP}" \
  --from-file="${CATALOG_BUILD_DIR}/todos-api.catalog-info.yaml" \
  --from-file="${CATALOG_BUILD_DIR}/todos.openapi.yaml" \
  --dry-run=client -o yaml | kubectl apply -f -

PROVIDER_MANIFEST="${BUILD_DIR}/todos-api.yaml"
render_template \
  "${PLATFORM_DIR}/demo/provider/todos-api/k8s.yaml.tmpl" \
  "${PROVIDER_MANIFEST}" \
  DEMO_NAMESPACE "${DEMO_NAMESPACE}" \
  TODOS_API_NAME "${TODOS_API_NAME}" \
  TODOS_API_IMAGE "${TODOS_API_IMAGE}" \
  TODOS_API_PORT "${TODOS_API_PORT}"

log "Deploying provider API"
kubectl apply -f "${PROVIDER_MANIFEST}"
wait_for_deployment "${DEMO_NAMESPACE}" "${TODOS_API_NAME}" 180s

log "Provider API is available"
kubectl -n "${DEMO_NAMESPACE}" get svc "${TODOS_API_NAME}"


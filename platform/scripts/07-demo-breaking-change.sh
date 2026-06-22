#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"
source_generated_env

require_kubectl_context

APP_IMAGE="${APP_IMAGE:-${REQUEST_LOGGER_IMAGE:-}}"
[[ -n "${APP_IMAGE}" ]] || fail "APP_IMAGE is not set; run 02-build-and-push-images.sh first or set APP_IMAGE"

BREAKING_NAME="${BREAKING_NAME:-request-logger-breaking}"
BREAKING_NAMESPACE="${BREAKING_NAMESPACE:-${DEMO_NAMESPACE}}"

CATALOG_BUILD_DIR="${BUILD_DIR}/catalog-breaking"
mkdir -p "${CATALOG_BUILD_DIR}"
cp "${PLATFORM_DIR}/catalog/apis/todos-api.catalog-info.yaml" "${CATALOG_BUILD_DIR}/todos-api.catalog-info.yaml"
cp "${PLATFORM_DIR}/catalog/apis/todos.openapi.breaking.yaml" "${CATALOG_BUILD_DIR}/todos.openapi.yaml"

log "Publishing breaking API catalog bundle"
kubectl -n "${CATALOG_NAMESPACE}" create configmap "${CATALOG_CONFIGMAP}" \
  --from-file="${CATALOG_BUILD_DIR}/todos-api.catalog-info.yaml" \
  --from-file="${CATALOG_BUILD_DIR}/todos.openapi.yaml" \
  --dry-run=client -o yaml | kubectl apply -f -

log "Submitting a separate ApplicationRelease expected to fail contract validation"
set +e
REQUEST_NAME="${BREAKING_NAME}" \
REQUEST_NAMESPACE="${BREAKING_NAMESPACE}" \
APP_IMAGE="${APP_IMAGE}" \
APP_SOURCE_DIR="${REPO_ROOT}/go/apps/request-logger-http" \
APP_PORT="${APP_PORT}" \
"${SCRIPT_DIR}/05-rc-deploy.sh"
DEPLOY_STATUS=$?
set -e

if [[ "${DEPLOY_STATUS}" -eq 0 ]]; then
  fail "breaking OpenAPI deployment unexpectedly succeeded"
fi

log "ApplicationRelease failed as expected. Recent workflow logs:"
kubectl -n "${BREAKING_NAMESPACE}" get pods -l kratix.io/promise-name=application-release || true
for pod in $(kubectl -n "${BREAKING_NAMESPACE}" get pods -l kratix.io/promise-name=application-release -o name 2>/dev/null | tail -n 3); do
  log "Logs for ${pod}"
  kubectl -n "${BREAKING_NAMESPACE}" logs "${pod}" --all-containers=true --tail=120 || true
done

log "Restoring compatible API catalog bundle"
"${SCRIPT_DIR}/04-deploy-catalog-and-provider.sh"

log "Breaking change demo completed"

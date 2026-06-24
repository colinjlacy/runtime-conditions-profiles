#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"
source_generated_env

require_kubectl_context

APP_SOURCE_DIR="${APP_SOURCE_DIR:-${REPO_ROOT}/go/apps/request-logger-http}"
APP_IMAGE="${APP_IMAGE:-${REQUEST_LOGGER_IMAGE:-}}"
WORKLOAD_VERSION="${WORKLOAD_VERSION:-dev}"
REQUEST_NAME="${REQUEST_NAME:-${APP_NAME}}"
REQUEST_NAMESPACE="${REQUEST_NAMESPACE:-${DEMO_NAMESPACE}}"

[[ -d "${APP_SOURCE_DIR}" ]] || fail "APP_SOURCE_DIR does not exist: ${APP_SOURCE_DIR}"
[[ -n "${APP_IMAGE}" ]] || fail "APP_IMAGE is not set; run 02-build-and-push-images.sh first or set APP_IMAGE"

PROFILE_FILE="${BUILD_DIR}/${REQUEST_NAME}-profile.yaml"
REQUEST_FILE="${BUILD_DIR}/${REQUEST_NAME}-runtimeworkload.yaml"

contains_files() {
  local pattern="$1"
  find "${APP_SOURCE_DIR}" -name "${pattern}" -type f | grep -q .
}

log "Generating Runtime Conditions Profile from ${APP_SOURCE_DIR}"
if contains_files '*.go'; then
  require_cmd go
  (
    cd "${REPO_ROOT}/go"
    go run ./profiler/cmd/runtimeconditions \
      -dir "${APP_SOURCE_DIR}" \
      -name "${REQUEST_NAME}" \
      -workload-version "${WORKLOAD_VERSION}" \
      -out "${PROFILE_FILE}"
  )
elif contains_files '*.py'; then
  require_cmd python3
  PYTHONPATH="${REPO_ROOT}/python${PYTHONPATH:+:${PYTHONPATH}}" \
    python3 -m runtimeconditions.profiler \
      --dir "${APP_SOURCE_DIR}" \
      --name "${REQUEST_NAME}" \
      --workload-version "${WORKLOAD_VERSION}" \
      --out "${PROFILE_FILE}"
else
  fail "could not detect Go or Python source in ${APP_SOURCE_DIR}"
fi

log "Writing RuntimeWorkload request"
{
  cat <<EOF
apiVersion: runtimeconditions.io/v1alpha1
kind: RuntimeWorkload
metadata:
  name: ${REQUEST_NAME}
  namespace: ${REQUEST_NAMESPACE}
spec:
  image: ${APP_IMAGE}
  port: ${APP_PORT}
  readinessPath: /ready
  catalog:
    configMapRef:
      name: ${CATALOG_CONFIGMAP}
      namespace: ${CATALOG_NAMESPACE}
  profile: |
EOF
  sed 's/^/    /' "${PROFILE_FILE}"
} >"${REQUEST_FILE}"

kubectl create namespace "${REQUEST_NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -

log "Submitting RuntimeWorkload request through Kratix"
kubectl apply -f "${REQUEST_FILE}"

log "Waiting for RuntimeWorkload configure workflow"
kubectl -n "${REQUEST_NAMESPACE}" wait "runtimeworkload/${REQUEST_NAME}" \
  --for=condition=ConfigureWorkflowCompleted \
  --timeout=180s

log "Waiting for generated Redis request"
kubectl -n "${REQUEST_NAMESPACE}" wait "redis/${REQUEST_NAME}-cache" \
  --for=create \
  --timeout=180s

kubectl -n "${REQUEST_NAMESPACE}" wait "redis/${REQUEST_NAME}-cache" \
  --for=condition=ConfigureWorkflowCompleted \
  --timeout=180s

log "Waiting for generated application Deployment"
wait_for_deployment "${REQUEST_NAMESPACE}" "${REQUEST_NAME}" 240s

kubectl -n "${REQUEST_NAMESPACE}" get runtimeworkload "${REQUEST_NAME}"
kubectl -n "${REQUEST_NAMESPACE}" get redis "${REQUEST_NAME}-cache"
kubectl -n "${REQUEST_NAMESPACE}" get deployment "${REQUEST_NAME}"

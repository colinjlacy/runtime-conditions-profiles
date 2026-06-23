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
REQUEST_CONTROL_NAMESPACE="${REQUEST_CONTROL_NAMESPACE:-${CONTROL_NAMESPACE}}"
LOCKDOWN_REQUEST_NAME="${LOCKDOWN_REQUEST_NAME:-namespace-lockdown}"
API_ACCESS_REQUEST_NAME="${API_ACCESS_REQUEST_NAME:-${REQUEST_NAME}-todos-api-access}"
CACHE_REQUEST_NAME="${CACHE_REQUEST_NAME:-${REQUEST_NAME}-cache}"
OBJECT_STORE_REQUEST_NAME="${OBJECT_STORE_REQUEST_NAME:-${REQUEST_NAME}-object-store}"

[[ -d "${APP_SOURCE_DIR}" ]] || fail "APP_SOURCE_DIR does not exist: ${APP_SOURCE_DIR}"
[[ -n "${APP_IMAGE}" ]] || fail "APP_IMAGE is not set; run 02-build-and-push-images.sh first or set APP_IMAGE"

PROFILE_FILE="${BUILD_DIR}/${REQUEST_NAME}-profile.yaml"
REQUEST_FILE="${BUILD_DIR}/${REQUEST_NAME}-applicationrelease.yaml"

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

log "Writing ApplicationRelease request"
{
  cat <<EOF
apiVersion: platform.demoteam.dev/v1alpha1
kind: ApplicationRelease
metadata:
  name: ${REQUEST_NAME}
  namespace: ${REQUEST_CONTROL_NAMESPACE}
  annotations:
    platform.demoteam.dev/application-release-pipeline-image: "${APPLICATION_RELEASE_PIPELINE_IMAGE:-unknown}"
spec:
  image: ${APP_IMAGE}
  imagePullPolicy: Always
  port: ${APP_PORT}
  readinessPath: /ready
  targetNamespace: ${REQUEST_NAMESPACE}
  catalog:
    configMapRef:
      name: ${CATALOG_CONFIGMAP}
      namespace: ${CATALOG_NAMESPACE}
  profile: |
EOF
  sed 's/^/    /' "${PROFILE_FILE}"
} >"${REQUEST_FILE}"

kubectl create namespace "${REQUEST_CONTROL_NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -
kubectl create namespace "${REQUEST_NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -

log "Submitting ApplicationRelease request through Kratix in ${REQUEST_CONTROL_NAMESPACE}"
kubectl apply -f "${REQUEST_FILE}"

log "Waiting for ApplicationRelease configure workflow"
wait_for_resource_condition \
  "${REQUEST_CONTROL_NAMESPACE}" \
  "applicationrelease/${REQUEST_NAME}" \
  ConfigureWorkflowCompleted \
  180s

log "Waiting for generated Cilium namespace lockdown request"
wait_for_resource_condition \
  "${REQUEST_CONTROL_NAMESPACE}" \
  "ciliumnamespacelockdown/${LOCKDOWN_REQUEST_NAME}" \
  ConfigureWorkflowCompleted \
  180s

log "Waiting for generated Cilium API access request"
wait_for_resource_condition \
  "${REQUEST_CONTROL_NAMESPACE}" \
  "ciliumapiaccess/${API_ACCESS_REQUEST_NAME}" \
  ConfigureWorkflowCompleted \
  180s

log "Waiting for generated Redis request"
wait_for_resource_condition \
  "${REQUEST_CONTROL_NAMESPACE}" \
  "redis/${CACHE_REQUEST_NAME}" \
  ConfigureWorkflowCompleted \
  180s

log "Waiting for generated S3Bucket request"
wait_for_resource_condition \
  "${REQUEST_CONTROL_NAMESPACE}" \
  "s3bucket/${OBJECT_STORE_REQUEST_NAME}" \
  ConfigureWorkflowCompleted \
  180s

log "Waiting for generated application Deployment"
wait_for_deployment "${REQUEST_NAMESPACE}" "${REQUEST_NAME}" 240s

kubectl -n "${REQUEST_CONTROL_NAMESPACE}" get applicationrelease "${REQUEST_NAME}"
kubectl -n "${REQUEST_CONTROL_NAMESPACE}" get ciliumnamespacelockdown "${LOCKDOWN_REQUEST_NAME}"
kubectl -n "${REQUEST_CONTROL_NAMESPACE}" get ciliumapiaccess "${API_ACCESS_REQUEST_NAME}"
kubectl -n "${REQUEST_CONTROL_NAMESPACE}" get redis "${CACHE_REQUEST_NAME}"
kubectl -n "${REQUEST_CONTROL_NAMESPACE}" get s3bucket "${OBJECT_STORE_REQUEST_NAME}"
kubectl -n "${REQUEST_NAMESPACE}" get deployment "${REQUEST_NAME}"

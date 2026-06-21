#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"

require_cmd docker

TARGET_PLATFORM="${TARGET_PLATFORM:-linux/amd64}"

if [[ -n "${IMAGE_REGISTRY:-}" ]]; then
  IMAGE_TAG="${IMAGE_TAG:-dev}"
  IMAGE_PREFIX="${IMAGE_REGISTRY%/}"
  REDIS_PIPELINE_IMAGE="${REDIS_PIPELINE_IMAGE:-${IMAGE_PREFIX}/redis-pipeline:${IMAGE_TAG}}"
  CILIUM_API_ACCESS_PIPELINE_IMAGE="${CILIUM_API_ACCESS_PIPELINE_IMAGE:-${IMAGE_PREFIX}/cilium-api-access-pipeline:${IMAGE_TAG}}"
  CILIUM_NAMESPACE_LOCKDOWN_PIPELINE_IMAGE="${CILIUM_NAMESPACE_LOCKDOWN_PIPELINE_IMAGE:-${IMAGE_PREFIX}/cilium-namespace-lockdown-pipeline:${IMAGE_TAG}}"
  S3_BUCKET_PIPELINE_IMAGE="${S3_BUCKET_PIPELINE_IMAGE:-${IMAGE_PREFIX}/s3-bucket-pipeline:${IMAGE_TAG}}"
  RUNTIME_WORKLOAD_PIPELINE_IMAGE="${RUNTIME_WORKLOAD_PIPELINE_IMAGE:-${IMAGE_PREFIX}/runtime-workload-pipeline:${IMAGE_TAG}}"
  TODOS_API_IMAGE="${TODOS_API_IMAGE:-${IMAGE_PREFIX}/todos-api:${IMAGE_TAG}}"
  REQUEST_LOGGER_IMAGE="${REQUEST_LOGGER_IMAGE:-${IMAGE_PREFIX}/request-logger:${IMAGE_TAG}}"
else
  TTL_NAME="$(id -un 2>/dev/null || printf user)"
  TTL_NAME="$(printf '%s' "${TTL_NAME}" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9-')"
  TTL_STAMP="$(date +%Y%m%d%H%M%S)"
  IMAGE_TAG="${IMAGE_TAG:-24h}"
  REDIS_PIPELINE_IMAGE="${REDIS_PIPELINE_IMAGE:-ttl.sh/runtimeconditions-${TTL_NAME}-${TTL_STAMP}-redis-pipeline:${IMAGE_TAG}}"
  CILIUM_API_ACCESS_PIPELINE_IMAGE="${CILIUM_API_ACCESS_PIPELINE_IMAGE:-ttl.sh/runtimeconditions-${TTL_NAME}-${TTL_STAMP}-cilium-api-access-pipeline:${IMAGE_TAG}}"
  CILIUM_NAMESPACE_LOCKDOWN_PIPELINE_IMAGE="${CILIUM_NAMESPACE_LOCKDOWN_PIPELINE_IMAGE:-ttl.sh/runtimeconditions-${TTL_NAME}-${TTL_STAMP}-cilium-namespace-lockdown-pipeline:${IMAGE_TAG}}"
  S3_BUCKET_PIPELINE_IMAGE="${S3_BUCKET_PIPELINE_IMAGE:-ttl.sh/runtimeconditions-${TTL_NAME}-${TTL_STAMP}-s3-bucket-pipeline:${IMAGE_TAG}}"
  RUNTIME_WORKLOAD_PIPELINE_IMAGE="${RUNTIME_WORKLOAD_PIPELINE_IMAGE:-ttl.sh/runtimeconditions-${TTL_NAME}-${TTL_STAMP}-runtime-workload-pipeline:${IMAGE_TAG}}"
  TODOS_API_IMAGE="${TODOS_API_IMAGE:-ttl.sh/runtimeconditions-${TTL_NAME}-${TTL_STAMP}-todos-api:${IMAGE_TAG}}"
  REQUEST_LOGGER_IMAGE="${REQUEST_LOGGER_IMAGE:-ttl.sh/runtimeconditions-${TTL_NAME}-${TTL_STAMP}-request-logger:${IMAGE_TAG}}"
fi

build_and_push() {
  local image="$1"
  local dockerfile="$2"
  local context="$3"
  log "Building ${image}"
  docker build --platform "${TARGET_PLATFORM}" -t "${image}" -f "${dockerfile}" "${context}"
  log "Pushing ${image}"
  docker push "${image}"
}

build_and_push \
  "${REDIS_PIPELINE_IMAGE}" \
  "${PLATFORM_DIR}/kratix/promises/redis/pipeline/Dockerfile" \
  "${PLATFORM_DIR}/kratix/promises/redis/pipeline"

build_and_push \
  "${CILIUM_API_ACCESS_PIPELINE_IMAGE}" \
  "${PLATFORM_DIR}/kratix/promises/cilium-api-access/pipeline/Dockerfile" \
  "${PLATFORM_DIR}/kratix/promises/cilium-api-access/pipeline"

build_and_push \
  "${CILIUM_NAMESPACE_LOCKDOWN_PIPELINE_IMAGE}" \
  "${PLATFORM_DIR}/kratix/promises/cilium-namespace-lockdown/pipeline/Dockerfile" \
  "${PLATFORM_DIR}/kratix/promises/cilium-namespace-lockdown/pipeline"

build_and_push \
  "${S3_BUCKET_PIPELINE_IMAGE}" \
  "${PLATFORM_DIR}/kratix/promises/s3-bucket/pipeline/Dockerfile" \
  "${PLATFORM_DIR}/kratix/promises/s3-bucket/pipeline"

build_and_push \
  "${RUNTIME_WORKLOAD_PIPELINE_IMAGE}" \
  "${PLATFORM_DIR}/kratix/promises/runtime-workload/pipeline/Dockerfile" \
  "${PLATFORM_DIR}/kratix/promises/runtime-workload/pipeline"

build_and_push \
  "${TODOS_API_IMAGE}" \
  "${PLATFORM_DIR}/demo/provider/todos-api/Dockerfile" \
  "${PLATFORM_DIR}/demo/provider/todos-api"

build_and_push \
  "${REQUEST_LOGGER_IMAGE}" \
  "${REPO_ROOT}/go/apps/request-logger-http/Dockerfile" \
  "${REPO_ROOT}"

write_generated_env

log "Wrote image references to ${ENV_FILE}"
cat "${ENV_FILE}"

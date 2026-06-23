#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"
source_generated_env

require_kubectl_context

[[ -n "${REDIS_PIPELINE_IMAGE:-}" ]] || fail "REDIS_PIPELINE_IMAGE is not set; run 02-build-and-push-images.sh first"
[[ -n "${APPLICATION_RELEASE_PIPELINE_IMAGE:-}" ]] || fail "APPLICATION_RELEASE_PIPELINE_IMAGE is not set; run 02-build-and-push-images.sh first"

kubectl create namespace "${DEMO_NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -
kubectl create namespace "${CATALOG_NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -

REDIS_PROMISE="${BUILD_DIR}/redis-promise.yaml"
APPLICATION_RELEASE_PROMISE="${BUILD_DIR}/application-release-promise.yaml"

render_template \
  "${PLATFORM_DIR}/kratix/promises/redis/promise.yaml.tmpl" \
  "${REDIS_PROMISE}" \
  REDIS_PIPELINE_IMAGE "${REDIS_PIPELINE_IMAGE}"

render_template \
  "${PLATFORM_DIR}/kratix/promises/application-release/promise.yaml.tmpl" \
  "${APPLICATION_RELEASE_PROMISE}" \
  APPLICATION_RELEASE_PIPELINE_IMAGE "${APPLICATION_RELEASE_PIPELINE_IMAGE}" \
  CATALOG_NAMESPACE "${CATALOG_NAMESPACE}"

log "Installing Redis Promise"
kubectl apply -f "${REDIS_PROMISE}"
wait_for_crd redis.runtimeconditions.io

log "Installing RuntimeWorkload Promise"
kubectl apply -f "${APPLICATION_RELEASE_PROMISE}"
wait_for_crd runtimeworkloads.runtimeconditions.io

log "Runtime Conditions Promises are installed"
kubectl get crds -l kratix.io/promise-name

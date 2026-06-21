#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"
source_generated_env

require_kubectl_context

[[ -n "${REDIS_PIPELINE_IMAGE:-}" ]] || fail "REDIS_PIPELINE_IMAGE is not set; run 02-build-and-push-images.sh first"
[[ -n "${CILIUM_API_ACCESS_PIPELINE_IMAGE:-}" ]] || fail "CILIUM_API_ACCESS_PIPELINE_IMAGE is not set; run 02-build-and-push-images.sh first"
[[ -n "${CILIUM_NAMESPACE_LOCKDOWN_PIPELINE_IMAGE:-}" ]] || fail "CILIUM_NAMESPACE_LOCKDOWN_PIPELINE_IMAGE is not set; run 02-build-and-push-images.sh first"
[[ -n "${S3_BUCKET_PIPELINE_IMAGE:-}" ]] || fail "S3_BUCKET_PIPELINE_IMAGE is not set; run 02-build-and-push-images.sh first"
[[ -n "${RUNTIME_WORKLOAD_PIPELINE_IMAGE:-}" ]] || fail "RUNTIME_WORKLOAD_PIPELINE_IMAGE is not set; run 02-build-and-push-images.sh first"

kubectl create namespace "${DEMO_NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -
kubectl create namespace "${CATALOG_NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -

REDIS_PROMISE="${BUILD_DIR}/redis-promise.yaml"
CILIUM_API_ACCESS_PROMISE="${BUILD_DIR}/cilium-api-access-promise.yaml"
CILIUM_NAMESPACE_LOCKDOWN_PROMISE="${BUILD_DIR}/cilium-namespace-lockdown-promise.yaml"
S3_BUCKET_PROMISE="${BUILD_DIR}/s3-bucket-promise.yaml"
RUNTIME_WORKLOAD_PROMISE="${BUILD_DIR}/runtime-workload-promise.yaml"

render_template \
  "${PLATFORM_DIR}/kratix/promises/redis/promise.yaml.tmpl" \
  "${REDIS_PROMISE}" \
  REDIS_PIPELINE_IMAGE "${REDIS_PIPELINE_IMAGE}"

render_template \
  "${PLATFORM_DIR}/kratix/promises/cilium-api-access/promise.yaml.tmpl" \
  "${CILIUM_API_ACCESS_PROMISE}" \
  CILIUM_API_ACCESS_PIPELINE_IMAGE "${CILIUM_API_ACCESS_PIPELINE_IMAGE}"

render_template \
  "${PLATFORM_DIR}/kratix/promises/cilium-namespace-lockdown/promise.yaml.tmpl" \
  "${CILIUM_NAMESPACE_LOCKDOWN_PROMISE}" \
  CILIUM_NAMESPACE_LOCKDOWN_PIPELINE_IMAGE "${CILIUM_NAMESPACE_LOCKDOWN_PIPELINE_IMAGE}"

render_template \
  "${PLATFORM_DIR}/kratix/promises/s3-bucket/promise.yaml.tmpl" \
  "${S3_BUCKET_PROMISE}" \
  S3_BUCKET_PIPELINE_IMAGE "${S3_BUCKET_PIPELINE_IMAGE}"

render_template \
  "${PLATFORM_DIR}/kratix/promises/runtime-workload/promise.yaml.tmpl" \
  "${RUNTIME_WORKLOAD_PROMISE}" \
  RUNTIME_WORKLOAD_PIPELINE_IMAGE "${RUNTIME_WORKLOAD_PIPELINE_IMAGE}" \
  CATALOG_NAMESPACE "${CATALOG_NAMESPACE}"

log "Installing Redis Promise"
kubectl apply -f "${REDIS_PROMISE}"
wait_for_crd redis.runtimeconditions.io

log "Installing CiliumAPIAccess Promise"
kubectl apply -f "${CILIUM_API_ACCESS_PROMISE}"
wait_for_crd ciliumapiaccesses.runtimeconditions.io

log "Installing CiliumNamespaceLockdown Promise"
kubectl apply -f "${CILIUM_NAMESPACE_LOCKDOWN_PROMISE}"
wait_for_crd ciliumnamespacelockdowns.runtimeconditions.io

log "Installing S3Bucket Promise"
kubectl apply -f "${S3_BUCKET_PROMISE}"
wait_for_crd s3buckets.runtimeconditions.io

log "Installing RuntimeWorkload Promise"
kubectl apply -f "${RUNTIME_WORKLOAD_PROMISE}"
wait_for_crd runtimeworkloads.runtimeconditions.io

log "Runtime Conditions Promises are installed"
kubectl get crds -l kratix.io/promise-name

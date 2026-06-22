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
[[ -n "${APPLICATION_RELEASE_PIPELINE_IMAGE:-}" ]] || fail "APPLICATION_RELEASE_PIPELINE_IMAGE is not set; run 02-build-and-push-images.sh first"

kubectl create namespace "${DEMO_NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -
kubectl create namespace "${CATALOG_NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -

REDIS_PROMISE="${BUILD_DIR}/redis-promise.yaml"
CILIUM_API_ACCESS_PROMISE="${BUILD_DIR}/cilium-api-access-promise.yaml"
CILIUM_NAMESPACE_LOCKDOWN_PROMISE="${BUILD_DIR}/cilium-namespace-lockdown-promise.yaml"
S3_BUCKET_PROMISE="${BUILD_DIR}/s3-bucket-promise.yaml"
APPLICATION_RELEASE_PROMISE="${BUILD_DIR}/application-release-promise.yaml"

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
  "${PLATFORM_DIR}/kratix/promises/application-release/promise.yaml.tmpl" \
  "${APPLICATION_RELEASE_PROMISE}" \
  APPLICATION_RELEASE_PIPELINE_IMAGE "${APPLICATION_RELEASE_PIPELINE_IMAGE}" \
  CATALOG_NAMESPACE "${CATALOG_NAMESPACE}"

log "Installing Redis Promise"
kubectl apply -f "${REDIS_PROMISE}"
wait_for_crd redis.platform.demoteam.dev

log "Installing CiliumAPIAccess Promise"
kubectl apply -f "${CILIUM_API_ACCESS_PROMISE}"
wait_for_crd ciliumapiaccesses.platform.demoteam.dev

log "Installing CiliumNamespaceLockdown Promise"
kubectl apply -f "${CILIUM_NAMESPACE_LOCKDOWN_PROMISE}"
wait_for_crd ciliumnamespacelockdowns.platform.demoteam.dev

log "Installing S3Bucket Promise"
kubectl apply -f "${S3_BUCKET_PROMISE}"
wait_for_crd s3buckets.platform.demoteam.dev

log "Installing ApplicationRelease Promise"
kubectl apply -f "${APPLICATION_RELEASE_PROMISE}"
wait_for_crd applicationreleases.platform.demoteam.dev

log "demoteam platform Promises are installed"
kubectl get crds -l kratix.io/promise-name

#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"

IMAGE_TAG="${IMAGE_TAG:-latest}"

repo_slug_from_git() {
  local remote
  remote="$(git -C "${REPO_ROOT}" config --get remote.origin.url 2>/dev/null || true)"
  [[ -n "${remote}" ]] || return 1

  case "${remote}" in
    git@github.com:*)
      remote="${remote#git@github.com:}"
      ;;
    https://github.com/*)
      remote="${remote#https://github.com/}"
      ;;
    http://github.com/*)
      remote="${remote#http://github.com/}"
      ;;
    *)
      return 1
      ;;
  esac

  remote="${remote%.git}"
  printf '%s\n' "${remote}"
}

if [[ -z "${GHCR_OWNER:-}" || -z "${GHCR_REPOSITORY:-}" ]]; then
  if slug="$(repo_slug_from_git)"; then
    GHCR_OWNER="${GHCR_OWNER:-${slug%%/*}}"
    GHCR_REPOSITORY="${GHCR_REPOSITORY:-${slug##*/}}"
  fi
fi

[[ -n "${GHCR_OWNER:-}" ]] || fail "GHCR_OWNER is not set and could not be inferred from git remote.origin.url"
[[ -n "${GHCR_REPOSITORY:-}" ]] || fail "GHCR_REPOSITORY is not set and could not be inferred from git remote.origin.url"

GHCR_OWNER="$(printf '%s' "${GHCR_OWNER}" | tr '[:upper:]' '[:lower:]')"
GHCR_REPOSITORY="$(printf '%s' "${GHCR_REPOSITORY}" | tr '[:upper:]' '[:lower:]')"

# IMAGE_PREFIX="${IMAGE_PREFIX:-ghcr.io/${GHCR_OWNER}/${GHCR_REPOSITORY}}"
IMAGE_PREFIX="${IMAGE_PREFIX:-ghcr.io/${GHCR_OWNER}/}golang-http-profiler"

REDIS_PIPELINE_IMAGE="${REDIS_PIPELINE_IMAGE:-${IMAGE_PREFIX}-redis-pipeline:${IMAGE_TAG}}"
CILIUM_API_ACCESS_PIPELINE_IMAGE="${CILIUM_API_ACCESS_PIPELINE_IMAGE:-${IMAGE_PREFIX}-cilium-api-access-pipeline:${IMAGE_TAG}}"
CILIUM_NAMESPACE_LOCKDOWN_PIPELINE_IMAGE="${CILIUM_NAMESPACE_LOCKDOWN_PIPELINE_IMAGE:-${IMAGE_PREFIX}-cilium-namespace-lockdown-pipeline:${IMAGE_TAG}}"
S3_BUCKET_PIPELINE_IMAGE="${S3_BUCKET_PIPELINE_IMAGE:-${IMAGE_PREFIX}-s3-bucket-pipeline:${IMAGE_TAG}}"
APPLICATION_RELEASE_PIPELINE_IMAGE="${APPLICATION_RELEASE_PIPELINE_IMAGE:-${IMAGE_PREFIX}-application-release-pipeline:${IMAGE_TAG}}"
TODOS_API_IMAGE="${TODOS_API_IMAGE:-${IMAGE_PREFIX}-todos-api:${IMAGE_TAG}}"
REQUEST_LOGGER_IMAGE="${REQUEST_LOGGER_IMAGE:-${IMAGE_PREFIX}-request-logger:${IMAGE_TAG}}"

write_generated_env

log "Wrote public GHCR image references to ${ENV_FILE}"
cat "${ENV_FILE}"

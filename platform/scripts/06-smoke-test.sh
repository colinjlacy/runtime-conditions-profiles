#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"
source_generated_env

require_kubectl_context
require_cmd curl

REQUEST_NAME="${REQUEST_NAME:-${APP_NAME}}"
REQUEST_NAMESPACE="${REQUEST_NAMESPACE:-${DEMO_NAMESPACE}}"
LOCAL_PORT="${LOCAL_PORT:-8080}"
PORT_FORWARD_LOG="${BUILD_DIR}/${REQUEST_NAME}-port-forward.log"

log "Opening port-forward to svc/${REQUEST_NAME} on localhost:${LOCAL_PORT}"
kubectl -n "${REQUEST_NAMESPACE}" port-forward "svc/${REQUEST_NAME}" "${LOCAL_PORT}:${APP_PORT}" >"${PORT_FORWARD_LOG}" 2>&1 &
PF_PID="$!"
trap 'kill "${PF_PID}" >/dev/null 2>&1 || true' EXIT

for _ in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:${LOCAL_PORT}/ready" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

RESPONSE="$(curl -fsS "http://127.0.0.1:${LOCAL_PORT}/demo")"
printf '%s\n' "${RESPONSE}"

printf '%s' "${RESPONSE}" | grep -q '"todosApi":"ok"' || fail "todos API smoke check failed"
printf '%s' "${RESPONSE}" | grep -q '"cache":"ok"' || fail "cache smoke check failed"
printf '%s' "${RESPONSE}" | grep -q '"auditLog":"ok"' || fail "audit log smoke check failed"

log "Smoke test passed"
